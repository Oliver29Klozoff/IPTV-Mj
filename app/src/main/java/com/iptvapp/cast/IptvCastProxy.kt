package com.iptvapp.cast

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Local HTTP proxy that adds CORS headers so the Chromecast Default Media Receiver
 * (Chrome browser context) can load IPTV streams that lack CORS headers.
 */
class IptvCastProxy(
    private val localIp: String,
    private val onRequest: ((String) -> Unit)? = null
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    var listeningPort: Int = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun start() {
        serverSocket = ServerSocket(0).also { listeningPort = it.localPort }
        running = true
        Thread {
            while (running) {
                try {
                    val socket = serverSocket!!.accept()
                    Thread { handleSocket(socket) }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                    if (running) Log.e("CastProxy", "Accept error", e)
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
        Log.d("CastProxy", "Started on $localIp:$listeningPort")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun proxyUrl(originalUrl: String): String =
        "http://$localIp:$listeningPort/s?u=${URLEncoder.encode(originalUrl, "UTF-8")}"

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            socket.use {
                val input = socket.getInputStream()
                // Read request line
                val requestLine = readLine(input) ?: return
                // Drain headers
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "GET" }
                val path   = parts.getOrElse(1) { "/" }

                onRequest?.invoke(path.take(80))

                if (method == "OPTIONS") {
                    writeResponse(socket, "200 OK", "text/plain", ByteArray(0))
                    return
                }

                val encodedUrl = if (path.contains("?u=")) path.substringAfter("?u=") else null
                if (encodedUrl == null) {
                    writeResponse(socket, "400 Bad Request", "text/plain", "Missing url param".toByteArray())
                    return
                }

                val targetUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                Log.d("CastProxy", "→ $targetUrl")
                proxyRequest(socket, targetUrl)
            }
        } catch (e: Exception) {
            Log.e("CastProxy", "Socket handler error", e)
        }
    }

    /** Read one HTTP line (ends with \n, strips \r). Returns null on EOF. */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun proxyRequest(socket: Socket, url: String) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            val serverCt = resp.header("Content-Type") ?: ""
            val body = resp.body ?: run {
                Log.e("CastProxy", "No body for $url")
                writeResponse(socket, "502 Bad Gateway", "text/plain", "No body".toByteArray())
                return
            }

            // Detect playlist by content-type OR url OR by peeking at content starting with #EXTM3U
            val looksLikePlaylistByMeta = serverCt.contains("mpegurl", ignoreCase = true) ||
                serverCt.contains("m3u", ignoreCase = true) ||
                url.contains(".m3u8", ignoreCase = true)

            val bodyBytes = body.bytes()
            val bodyStr = bodyBytes.toString(Charsets.UTF_8)
            val isPlaylist = looksLikePlaylistByMeta || bodyStr.trimStart().startsWith("#EXTM3U")

            Log.d("CastProxy", "← url=${url.takeLast(60)} ct=$serverCt playlist=$isPlaylist bytes=${bodyBytes.size}")
            if (isPlaylist) Log.d("CastProxy", "m3u8 preview: ${bodyStr.take(300)}")

            if (isPlaylist) {
                val rewritten = rewritePlaylist(bodyStr, url).toByteArray()
                writeResponse(socket, "200 OK", "application/x-mpegURL", rewritten)
            } else {
                writeResponse(socket, "200 OK", serverCt.ifBlank { guessContentType(url) }, bodyBytes)
            }
        } catch (e: Exception) {
            Log.e("CastProxy", "Upstream error for $url", e)
            writeResponse(socket, "502 Bad Gateway", "text/plain",
                "Upstream: ${e.message}".toByteArray())
        }
    }

    private fun rewritePlaylist(content: String, baseUrl: String): String {
        val baseUri = URI(baseUrl)
        return content.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> line
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    proxyUrl(trimmed)
                else -> proxyUrl(baseUri.resolve(trimmed).toString())
            }
        }
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
}
