package com.iptvapp.cast

import android.util.Log
import com.iptvapp.BuildConfig
import com.iptvapp.util.LogSanitizer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HTTP proxy that adds CORS headers so the Chromecast Default Media Receiver
 * (Chrome browser context) can load IPTV streams that lack CORS headers.
 *
 * Also repackages raw, unsegmented MPEG-TS live streams (many IPTV panels serve live channels
 * this way — no HLS manifest at all) into a live HLS presentation on the fly: the Default
 * Media Receiver's video pipeline is built around segmented media (HLS/DASH/progressive MP4),
 * not an infinite raw transport-stream socket, so handing it the raw .ts URL directly connects
 * and then silently disconnects a few seconds in with nothing ever rendered. See LiveHlsSession.
 *
 * Security (v7.20): the proxy is reachable by anything on the network while a cast runs, so it
 * serves only what MKTV registered for this cast, behind a per-cast token — see CastProxyGuard.
 * It used to take any file path (/file?p=…) or URL (/s?u=…) from whoever asked.
 */
class IptvCastProxy(
    private val localIp: String,
    // Only needed for proxyLocalFile()/serving a recorded file's bytes (a Cast receiver can't
    // resolve a file:// or content:// URI at all — those are meaningful only on this device — so
    // this proxy has to actually open and stream the bytes itself, unlike the HTTP passthrough
    // paths above which just forward an upstream network request).
    private val appContext: android.content.Context? = null
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    var listeningPort: Int = 0

    // Recordings live in the public Movies folder (or MediaStore) or this app's external files;
    // nothing in the app's private storage (preferences, databases, logs) is ever served.
    private val guard = CastProxyGuard(
        allowedFileRoots = listOfNotNull(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
            appContext?.getExternalFilesDir(null)
        ),
        forbiddenFileRoots = listOfNotNull(appContext?.applicationInfo?.dataDir?.let { java.io.File(it) }, appContext?.filesDir?.parentFile)
    )

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host] ?: emptyList()
    }

    // Upstream connections may only go where the guard allows. Every socket is checked BEFORE it
    // connects (GuardedSocket — every hop, redirects and IP-literal hosts included, which bypass
    // DNS), host names are filtered as they resolve, and each request is checked again on its
    // connection — so neither a playlist nor a redirect can reach this device or the local network.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .socketFactory(object : javax.net.SocketFactory() {
            override fun createSocket(): Socket = GuardedSocket()
            override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        })
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                guard.noteResolved(hostname, all)
                return all.filterNot { guard.isBlockedAddress(it, hostname) }
                    .ifEmpty { throw java.net.UnknownHostException("Not an allowed cast address") }
            }
        })
        .addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || guard.isBlockedAddress(address, chain.request().url.host)) {
                throw IOException("Not an allowed cast address")
            }
            chain.proceed(chain.request())
        }
        .build()

    // A few requests at a time (the receiver fetches a playlist and a segment or two at once);
    // more are closed at once rather than each getting a thread.
    private val workers = ThreadPoolExecutor(
        0, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue()
    ) { r -> Thread(r, "CastProxyWorker").apply { isDaemon = true } }

    // Live-HLS repackaging sessions, keyed by their registered id — one per raw-.ts channel
    // currently being cast. Almost always just one entry (one active cast at a time), but keyed
    // rather than a single field so a channel change mid-cast doesn't tear down a session another
    // in-flight request still holds a reference to.
    private val liveSessions = ConcurrentHashMap<String, LiveHlsSession>()

    private val baseUrl get() = "http://${if (localIp.contains(':')) "[$localIp]" else localIp}:$listeningPort"

    fun start() {
        // On the network the receiver is on — the cast's own Wi-Fi address — not every interface.
        val socket = try {
            ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName(localIp), 0)) }
        } catch (e: Exception) {
            ServerSocket(0)
        }
        serverSocket = socket
        listeningPort = socket.localPort
        running = true
        Thread {
            while (running) {
                try {
                    val client = socket.accept()
                    try {
                        workers.execute { handleSocket(client) }
                    } catch (_: RejectedExecutionException) {
                        try { client.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    if (running) Log.e("CastProxy", "Accept error: ${e.javaClass.simpleName}")
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
        if (BuildConfig.DEBUG) Log.d("CastProxy", "Started on port $listeningPort")
    }

    /** Ends this cast's proxy: its token and every registered resource stop working at once. */
    fun stop() {
        running = false
        guard.close()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        liveSessions.values.forEach { it.stop() }
        liveSessions.clear()
        workers.shutdownNow()
    }

    /** The cast URL for an upstream stream MKTV chose to cast; the URL itself unchanged when it
     * isn't http(s) (nothing to proxy). */
    fun proxyUrl(originalUrl: String): String =
        guard.registerUpstream(originalUrl, castSource = true)?.let { baseUrl + it } ?: originalUrl

    /** Entry point for casting a locally recorded file (content:// or file:// path) — unlike
     * proxyUrl(), there's no upstream HTTP server to forward to, so the proxy reads the local
     * bytes directly via ContentResolver/File and serves them, with HTTP Range support since the
     * Cast Default Media Receiver issues range requests for seeking on VOD-style content.
     * Null when [localPath] isn't a recording location (see CastProxyGuard.isAllowedFile). */
    fun proxyLocalFile(localPath: String): String? =
        guard.registerFile(localPath)?.let { baseUrl + it }

    /** Entry point for a raw/live .ts channel — starts a LiveHlsSession for this upstream URL and
     * returns the URL to hand to the Cast receiver as its MediaInfo content URL: a live-updating
     * .m3u8 manifest instead of the raw stream URL proxyUrl() would give. */
    fun proxyLiveUrl(originalUrl: String, userAgent: String?): String {
        if (!guard.allowCastSource(originalUrl)) return proxyUrl(originalUrl)
        val (id, path) = guard.registerLive() ?: return originalUrl
        val session = LiveHlsSession(id, originalUrl, userAgent, client) { n -> baseUrl + guard.segmentPath(id, n) }
        liveSessions[id] = session
        session.start()
        return baseUrl + path
    }

    /** Blocks (up to [timeoutMs]) until the LiveHlsSession behind the given proxyLiveUrl()
     * result has enough clean segments ready to serve a real manifest — see the caller
     * (PlayerActivity.stopLocalAndCast) for why this matters: the Cast Default Media Receiver
     * only polls a live manifest a handful of times in the first several seconds after load()
     * and then gives up permanently if it never saw real segments in that window, so the
     * MediaInfo.load() call needs to happen AFTER real segments already exist, not race them.
     * Returns true if ready before the timeout, false otherwise (caller loads anyway either
     * way — this only controls when, not whether). */
    fun awaitLiveSessionReady(castUrl: String, timeoutMs: Long): Boolean {
        val id = guard.liveIdOf(castUrl) ?: return false
        val session = liveSessions[id] ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (session.hasEnoughGoodSegments()) return true
            Thread.sleep(250)
        }
        return session.hasEnoughGoodSegments()
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            socket.use {
                val input = java.io.BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                // Read headers (bounded), capture User-Agent to forward and Range for recordings
                var incomingUserAgent: String? = null
                var rangeHeader: String? = null
                var headers = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (++headers > MAX_HEADERS) return
                    if (header.startsWith("User-Agent:", ignoreCase = true))
                        incomingUserAgent = header.substringAfter(":").trim().take(256)
                    if (header.startsWith("Range:", ignoreCase = true))
                        rangeHeader = header.substringAfter(":").trim()
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "" }
                val path = parts.getOrElse(1) { "" }

                when (val route = guard.route(method, path)) {
                    is CastProxyGuard.Route.Rejected ->
                        writeResponse(socket, route.status, "text/plain", ByteArray(0))
                    CastProxyGuard.Route.Options ->
                        writeResponse(socket, "200 OK", "text/plain", ByteArray(0))
                    is CastProxyGuard.Route.Live -> {
                        val session = liveSessions[route.id]
                        if (session == null) {
                            writeResponse(socket, "404 Not Found", "text/plain", ByteArray(0))
                        } else {
                            val manifest = session.buildManifest()
                            if (BuildConfig.DEBUG) Log.d("CastProxy", "← live manifest bytes=${manifest.length}")
                            writeResponse(socket, "200 OK", "application/x-mpegURL", manifest.toByteArray())
                        }
                    }
                    is CastProxyGuard.Route.Segment -> {
                        val segment = liveSessions[route.id]?.getSegment(route.sequence)
                        if (segment == null) {
                            writeResponse(socket, "404 Not Found", "text/plain", ByteArray(0))
                        } else {
                            writeResponse(socket, "200 OK", "video/mp2t", segment)
                        }
                    }
                    is CastProxyGuard.Route.LocalFile -> serveLocalFile(socket, route.path, rangeHeader)
                    is CastProxyGuard.Route.Upstream -> proxyRequest(socket, route.url, incomingUserAgent)
                }
            }
        } catch (e: Exception) {
            Log.w("CastProxy", "Request failed: ${e.javaClass.simpleName}")
        }
    }

    /** Read one HTTP line (ends with \n, strips \r), at most MAX_LINE bytes. Returns null on EOF
     * or an over-long line. */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) return null
            sb.append(b.toChar())
        }
    }

    private fun proxyRequest(socket: Socket, url: String, incomingUserAgent: String? = null) {
        try {
            val ua = incomingUserAgent ?: "ExoPlayerLib/1.4.1 (Linux; Android)"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                val status = resp.code
                val serverCt = resp.header("Content-Type") ?: ""
                val body = resp.body ?: run {
                    writeResponse(socket, "502 Bad Gateway", "text/plain", ByteArray(0))
                    return
                }

                val looksLikePlaylistByMeta = serverCt.contains("mpegurl", ignoreCase = true) ||
                    serverCt.contains("m3u", ignoreCase = true) ||
                    url.contains(".m3u8", ignoreCase = true)

                val bodyBytes = body.bytes()
                val bodyStr = bodyBytes.toString(Charsets.UTF_8)
                val isPlaylist = looksLikePlaylistByMeta || bodyStr.trimStart().startsWith("#EXTM3U")

                // Use the final URL after redirects as the base for resolving relative segment paths
                val finalUrl = resp.request.url.toString()
                if (BuildConfig.DEBUG) {
                    Log.d("CastProxy", "← status=$status url=${LogSanitizer.redactCredentials(url).takeLast(60)} ct=$serverCt playlist=$isPlaylist bytes=${bodyBytes.size}")
                }

                if (isPlaylist) {
                    val rewritten = rewritePlaylist(bodyStr, finalUrl).toByteArray()
                    writeResponse(socket, "200 OK", "application/x-mpegURL", rewritten)
                } else {
                    writeResponse(socket, "200 OK", serverCt.ifBlank { guessContentType(url) }, bodyBytes)
                }
            }
        } catch (e: Exception) {
            // No detail back to the requester: an upstream error message can carry the stream URL.
            Log.w("CastProxy", "Upstream error: ${e.javaClass.simpleName}")
            try { writeResponse(socket, "502 Bad Gateway", "text/plain", ByteArray(0)) } catch (_: Exception) {}
        }
    }

    // Opens a recording's bytes (content:// via ContentResolver, file:// / plain path via
    // FileInputStream) and serves them with HTTP Range support — the Default Media Receiver
    // relies on 206 Partial Content to seek within VOD-style media, unlike the always-from-
    // scratch live proxy paths above which never need to support arbitrary byte offsets.
    private fun serveLocalFile(socket: Socket, localPath: String, rangeHeader: String?) {
        val ctx = appContext
        if (ctx == null) {
            writeResponse(socket, "500 Internal Server Error", "text/plain", ByteArray(0))
            return
        }
        try {
            val totalLength = localFileLength(ctx, localPath)
            if (totalLength < 0) {
                writeResponse(socket, "404 Not Found", "text/plain", ByteArray(0))
                return
            }
            val contentType = guessContentType(localPath)
            var start = 0L
            var end = totalLength - 1
            val isRange = rangeHeader?.startsWith("bytes=") == true
            if (isRange) {
                val spec = rangeHeader!!.removePrefix("bytes=")
                val parts = spec.split("-")
                parts.getOrNull(0)?.toLongOrNull()?.let { start = it }
                parts.getOrNull(1)?.toLongOrNull()?.let { end = it }
                end = end.coerceAtMost(totalLength - 1)
            }
            if (start < 0 || start > end) {
                writeResponse(socket, "416 Range Not Satisfiable", "text/plain", ByteArray(0))
                return
            }
            val length = end - start + 1

            openLocalFileStream(ctx, localPath).use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val n = input.skip(start - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                val out = socket.getOutputStream()
                val header = buildString {
                    if (isRange) {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Range: bytes $start-$end/$totalLength\r\n")
                    } else {
                        append("HTTP/1.1 200 OK\r\n")
                    }
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: $length\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                out.write(header.toByteArray(Charsets.US_ASCII))
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val n = input.read(buffer, 0, toRead)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    remaining -= n
                }
                out.flush()
            }
        } catch (e: Exception) {
            Log.w("CastProxy", "Recording read failed: ${e.javaClass.simpleName}")
            try { writeResponse(socket, "500 Internal Server Error", "text/plain", ByteArray(0)) } catch (_: Exception) {}
        }
    }

    private fun localFileLength(ctx: android.content.Context, path: String): Long {
        return if (path.startsWith("content://")) {
            ctx.contentResolver.query(android.net.Uri.parse(path), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } else {
            val f = java.io.File(path.removePrefix("file://"))
            if (f.isFile) f.length() else -1L
        }
    }

    private fun openLocalFileStream(ctx: android.content.Context, path: String): java.io.InputStream =
        if (path.startsWith("content://")) {
            ctx.contentResolver.openInputStream(android.net.Uri.parse(path)) ?: throw java.io.IOException("Cannot open recording")
        } else {
            java.io.FileInputStream(path.removePrefix("file://"))
        }

    // Child playlists, segments and keys are registered as they're found in a playlist MKTV
    // approved, so the receiver can fetch exactly those — and nothing it makes up.
    private fun rewritePlaylist(content: String, baseUrl: String): String {
        val baseUri = URI(baseUrl)
        return content.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> rewriteTagUris(line, baseUri)
                else -> childUrl(baseUri, trimmed) ?: line
            }
        }
    }

    // Rewrites URI="..." attributes inside HLS tags (e.g. #EXT-X-MEDIA subtitle tracks, keys)
    private fun rewriteTagUris(line: String, baseUri: URI): String =
        line.replace(Regex("""URI="([^"]+)"""")) { m ->
            val resolved = childUrl(baseUri, m.groupValues[1]) ?: m.groupValues[1]
            """URI="$resolved""""
        }

    private fun childUrl(baseUri: URI, ref: String): String? {
        val absolute = try { baseUri.resolve(ref).toString() } catch (_: Exception) { return null }
        return guard.registerUpstream(absolute, castSource = false)?.let { this.baseUrl + it }
    }

    private fun writeResponse(socket: Socket, status: String, ct: String, body: ByteArray) {
        val out = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $ct\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun guessContentType(url: String) = when {
        url.contains(".ts",   ignoreCase = true) -> "video/mp2t"
        url.contains(".mp4",  ignoreCase = true) -> "video/mp4"
        url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        else -> "application/octet-stream"
    }

    /** A socket that refuses, before connecting, any address the guard forbids. */
    private inner class GuardedSocket : Socket() {
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (address == null || guard.isBlockedConnection(address)) throw IOException("Not an allowed cast address")
            super.connect(endpoint, timeout)
        }
        override fun connect(endpoint: java.net.SocketAddress?) = connect(endpoint, 0)
    }

    private companion object {
        const val MAX_WORKERS = 16
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 64
    }
}


/** Repackages a raw, continuous MPEG-TS byte stream into a live HLS presentation: one
 * background thread reads the upstream connection continuously and cuts a new ~4s segment
 * every SEGMENT_DURATION_MS, keeping the last [SEGMENT_RING_SIZE] segments in memory (a rolling
 * ~24s live buffer, matching typical live-HLS window sizes) so the manifest can always point at
 * segments that still exist. MPEG-TS packets are fixed 188 bytes and self-synchronizing, so
 * cutting on a wall-clock timer without any awareness of PES/frame boundaries still produces
 * segments each HLS player decodes correctly on their own — same assumption most simple
 * TS-to-HLS repackagers make. */
private class LiveHlsSession(
    private val id: String,
    private val upstreamUrl: String,
    private val userAgent: String?,
    private val client: OkHttpClient,
    // Full URL of segment n for this session — token-scoped, built by the proxy's guard.
    private val segmentUrl: (Long) -> String
) {
    companion object {
        // Tried 2000ms to shorten the MIN_SEGMENTS_BEFORE_SERVING wait — the manifest kept
        // refreshing fine, but the receiver only ever fetched the first 3 segments and then
        // silently stopped requesting more (while continuing to poll the manifest), a
        // different failure mode than the original bug. 4000ms is the confirmed-working value
        // — a real fix for the wait time needs a different angle (see PlayerActivity kdoc).
        private const val SEGMENT_DURATION_MS = 4000L
        private const val MAX_SEGMENT_BYTES = 4 * 1024 * 1024
        private const val SEGMENT_RING_SIZE = 8
        // The primary provider's own HLS (which casts fine) always serves consistent, evenly-
        // sized ~10s segments — the manifest never looks unusual to the receiver. Our very
        // first attempt at repackaging raw .ts cut segment 0 fast-and-small to beat the
        // receiver's polling window, then several more small/inconsistent ones as the initial
        // CDN burst drained (durations like 0.8s, 0.5s, 0.2s, 0.2s, 0.2s all in one manifest) —
        // spec-valid HLS, but nothing like what a real IPTV panel ever produces, and the
        // receiver silently gave up after one manifest fetch without ever requesting a segment.
        // Instead: keep the manifest as the always-available EMPTY fallback (buildManifest()
        // below) until MIN_SEGMENTS_BEFORE_SERVING consistent, full-duration segments exist —
        // the first thing the receiver ever sees is then just as normal-looking as the primary
        // provider's stream, at the cost of a few extra seconds before Cast starts playing.
        private const val MIN_SEGMENTS_BEFORE_SERVING = 3
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE = 0x47.toByte()
    }

    private data class Segment(val sequence: Long, val bytes: ByteArray, val durationSec: Double)

    @Volatile private var running = false
    private val segments = java.util.concurrent.ConcurrentSkipListMap<Long, Segment>()
    private val nextSequence = AtomicInteger(0)
    private var readerThread: Thread? = null
    // readLoop's input.read() blocks on the network — running alone only gets rechecked
    // between reads, so it never actually interrupted an in-flight upstream connection. A
    // channel change mid-cast (each proxyLiveUrl() call makes a brand-new session) or just
    // backing out of casting a few times used to leave every abandoned session's reader thread
    // and upstream connection running in the background indefinitely — call.cancel() closes
    // the underlying socket immediately, which unblocks the read() with an IOException the
    // readLoop's own try/catch (in start()) already swallows.
    @Volatile private var activeCall: okhttp3.Call? = null

    fun start() {
        if (running) return
        running = true
        readerThread = Thread {
            try {
                readLoop()
            } catch (e: Exception) {
                Log.e("CastProxy", "LiveHlsSession reader failed for ${com.iptvapp.util.LogSanitizer.redactCredentials(upstreamUrl)}", e)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        activeCall?.cancel()
        segments.clear()
    }

    private fun readLoop() {
        val ua = userAgent ?: "ExoPlayerLib/1.4.1 (Linux; Android)"
        val req = Request.Builder().url(upstreamUrl).header("User-Agent", ua).header("Accept", "*/*").build()
        Log.d("CastProxy", "LiveHlsSession[$id]: connecting to upstream ${com.iptvapp.util.LogSanitizer.redactCredentials(upstreamUrl)}")
        val call = client.newCall(req)
        activeCall = call
        call.execute().use { resp ->
            Log.d("CastProxy", "LiveHlsSession[$id]: upstream responded status=${resp.code} ct=${resp.header("Content-Type")}")
            val body = resp.body ?: run {
                Log.e("CastProxy", "LiveHlsSession[$id]: no body")
                return
            }
            val rawInput = body.byteStream()
            // Real MPEG-TS is a stream of fixed 188-byte packets, each starting with sync byte
            // 0x47 — but an HTTP response for a LIVE stream starts wherever the upstream
            // happened to be mid-transmission, not necessarily at a packet boundary. Cutting
            // segments by byte-count/time alone (the previous approach) therefore cut most
          // packets in half at every segment boundary — spec-valid-*looking* HLS with segment
            // data no demuxer could actually decode, since the Cast receiver never resyncs a
            // fed-in file the way a live broadcast decoder would. This wraps the raw stream in
            // a byte-aligning layer: skip to the first real sync byte once at the very start,
            // then only ever hand out whole 188-byte packets from then on, so every segment
            // this session cuts starts and ends exactly on a packet boundary.
            val input = SyncedTsInputStream(rawInput)
            val buffer = ByteArray(TS_PACKET_SIZE * 32)
            var currentSegment = java.io.ByteArrayOutputStream()
            var segmentStartMs = System.currentTimeMillis()
            var totalRead = 0L
            while (running) {
                val read = input.read(buffer)
                if (read == -1) {
                    Log.d("CastProxy", "LiveHlsSession[$id]: upstream EOF after $totalRead bytes")
                    break
                }
                totalRead += read
                currentSegment.write(buffer, 0, read)
                val elapsed = System.currentTimeMillis() - segmentStartMs
                // MAX_SEGMENT_BYTES only exists as a safety ceiling against an initial CDN
                // burst delivering data far faster than real-time — every segment still always
                // targets SEGMENT_DURATION_MS, matching the primary provider's consistent ~4-
                // 10s segments, rather than special-casing the first one short like an earlier
                // attempt did (which produced a manifest so unlike normal IPTV HLS — several
                // wildly inconsistent sub-1s segments — that the Cast receiver silently
                // abandoned the stream after one manifest fetch, never requesting a segment).
                if (elapsed >= SEGMENT_DURATION_MS || currentSegment.size() >= MAX_SEGMENT_BYTES) {
                    val seq = nextSequence.getAndIncrement().toLong()
                    val segBytes = currentSegment.toByteArray()
                    segments[seq] = Segment(seq, segBytes, elapsed / 1000.0)
                    if (com.iptvapp.BuildConfig.DEBUG) Log.d("CastProxy", "LiveHlsSession[$id]: cut segment n=$seq bytes=${segBytes.size} durationSec=${elapsed / 1000.0}")
                    // Evict old segments once the ring is full — an unbounded map here would
                    // leak memory for the whole lifetime of a long-running cast session.
                    while (segments.size > SEGMENT_RING_SIZE) segments.remove(segments.firstKey())
                    currentSegment = java.io.ByteArrayOutputStream()
                    segmentStartMs = System.currentTimeMillis()
                }
            }
        }
    }

    /** Wraps a raw MPEG-TS byte stream so every read() returns only whole 188-byte packets
     * starting on a real sync byte (0x47) — resyncs once at the very start (scanning forward
     * byte-by-byte until 3 consecutive packets validate, which rules out a false-positive 0x47
     * appearing inside packet payload data), then reads in fixed 188-byte units from then on
     * since the upstream connection itself never drops a byte once established. */
    private class SyncedTsInputStream(private val upstream: java.io.InputStream) : java.io.InputStream() {
        private var synced = false

        private fun resync() {
            val probe = ByteArray(TS_PACKET_SIZE * 4)
            var probeLen = 0
            // Fill a probe buffer first (a short single read could itself land mid-packet and
            // not contain 3 whole packets to validate against).
            while (probeLen < probe.size) {
                val n = upstream.read(probe, probeLen, probe.size - probeLen)
                if (n == -1) break
                probeLen += n
            }
            var offset = 0
            outer@ while (offset + TS_PACKET_SIZE * 3 <= probeLen) {
                if (probe[offset] == TS_SYNC_BYTE &&
                    probe[offset + TS_PACKET_SIZE] == TS_SYNC_BYTE &&
                    probe[offset + TS_PACKET_SIZE * 2] == TS_SYNC_BYTE
                ) {
                    pending = probe.copyOfRange(offset, probeLen)
                    synced = true
                    return
                }
                offset++
            }
            // No valid alignment found in the probe window at all — fall back to whatever was
            // read rather than blocking forever; later reads still round down to whole packets
            // via read(), so this only risks one malformed leading segment, not every one.
            pending = probe.copyOfRange(0, probeLen)
            synced = true
        }

        private var pending: ByteArray? = null
        private var pendingOffset = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!synced) resync()
            val pend = pending
            if (pend != null) {
                val available = pend.size - pendingOffset
                if (available <= 0) {
                    pending = null
                } else {
                    // Only ever hand back a whole-packet-count-sized chunk of the pending
                    // buffer, same rounding-down reasoning as the fresh-read path below.
                    val wholePackets = (minOf(available, len) / TS_PACKET_SIZE) * TS_PACKET_SIZE
                    if (wholePackets == 0) {
                        // Caller's buffer is smaller than one packet — extremely unlikely
                        // given readLoop's 188*32 buffer, but handle it rather than stall.
                        System.arraycopy(pend, pendingOffset, b, off, available)
                        pendingOffset = pend.size
                        pending = null
                        return available
                    }
                    System.arraycopy(pend, pendingOffset, b, off, wholePackets)
                    pendingOffset += wholePackets
                    if (pendingOffset >= pend.size) pending = null
                    return wholePackets
                }
            }
            val maxWhole = (len / TS_PACKET_SIZE) * TS_PACKET_SIZE
            if (maxWhole == 0) return upstream.read(b, off, len)
            var total = 0
            while (total < maxWhole) {
                val n = upstream.read(b, off + total, maxWhole - total)
                if (n == -1) break
                total += n
                // Only return early once we have at least one whole packet — a partial packet
                // held back here would otherwise get flushed as-is by the caller's own segment-
                // cut logic, reintroducing the exact misalignment this class exists to prevent.
                if (total >= TS_PACKET_SIZE && total % TS_PACKET_SIZE == 0) break
            }
            return if (total == 0) -1 else total
        }

        override fun read(): Int = throw UnsupportedOperationException("Use read(ByteArray, Int, Int)")
    }

    fun getSegment(sequence: Long): ByteArray? = segments[sequence]?.bytes

    // The MAX_SEGMENT_BYTES safety cap (needed for the initial CDN burst — see readLoop kdoc)
    // means the very first few segments can still land far short of SEGMENT_DURATION_MS even
    // though every segment always targets it. Filtering to only "close to the real target
    // duration" segments (rather than requiring the count alone) is what actually keeps the
    // malformed short ones from ever reaching the receiver — requiring a raw segment count
    // alone was satisfied by three 0.2s-long segments in testing, which still produced a
    // manifest nothing like a normal IPTV stream and which the receiver silently abandoned
    // without ever requesting a segment.
    private fun goodSegments() = segments.values.filter { it.durationSec >= (SEGMENT_DURATION_MS / 1000.0) * 0.5 }

    /** Used by IptvCastProxy.awaitLiveSessionReady to hold off calling MediaInfo.load() on the
     * Cast receiver until real segments already exist — the receiver only polls a live
     * manifest a handful of times right after load() and gives up permanently if it stays
     * empty that whole window, so starting the load early just races (and loses) that window. */
    fun hasEnoughGoodSegments(): Boolean = goodSegments().size >= MIN_SEGMENTS_BEFORE_SERVING

    /** Standard live-HLS manifest: EXT-X-MEDIA-SEQUENCE anchors playback to whichever segments
     * are still in the ring (segments before it no longer exist and must not be listed), no
     * EXT-X-ENDLIST since this is a live, unbounded presentation. */
    fun buildManifest(): String {
        val available = goodSegments()
        if (available.size < MIN_SEGMENTS_BEFORE_SERVING) {
            // Not enough good segments yet — a structurally-valid, empty-ish manifest lets the
            // receiver retry shortly instead of treating a 404/empty body as a fatal load error.
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n"
        }
        val targetDuration = available.maxOf { it.durationSec }.let { Math.ceil(it).toInt() }.coerceAtLeast(1)
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:$targetDuration\n")
            append("#EXT-X-MEDIA-SEQUENCE:${available.first().sequence}\n")
            for (seg in available) {
                append("#EXTINF:${"%.3f".format(seg.durationSec)},\n")
                // Fully-qualified rather than a relative "/seg?..." reference — the manifest
                // URL itself already carries a query string ("/live.m3u8?id=..."), and some HLS
                // parsers (the Cast Default Media Receiver's included, going by this session's
                // testing: it fetched the manifest fine but never once requested a listed
                // segment) don't reliably resolve a relative reference against a base URL that
                // already has its own query string. Spelling it out removes that ambiguity.
                append("${segmentUrl(seg.sequence)}\n")
            }
        }
    }
}
