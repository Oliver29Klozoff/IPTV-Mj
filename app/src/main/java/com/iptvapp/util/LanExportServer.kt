package com.iptvapp.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Minimal single-threaded HTTP/1.1 server for the "LAN Export" diagnostics feature — serves the
 * same debug bundle sendDebugReport() gathers (via DebugInfoCollector) as a downloadable .txt file
 * to any browser on the same WiFi network that hits it, discoverable via a QR code shown in a
 * dialog. No auth (by design, matches the competitor feature this is modeled on — "scan the QR on
 * your own LAN" is the security boundary), no external HTTP library (NanoHTTPD etc. isn't already
 * a dependency and would be overkill for two static responses).
 *
 * Lifecycle: caller starts this when the LAN Export dialog opens and calls stop() on dismiss/
 * onDestroy, whichever first — this is intentionally NOT a background/foreground service, it only
 * needs to live as long as the dialog is open. */
class LanExportServer(private val port: Int = 9479) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    /** Starts accepting connections on a background thread. bundleProvider is called fresh for
     * each request (cheap enough — it's just building a string from already-loaded prefs/db
     * counts, no need to cache/pre-fetch before the dialog is even shown). */
    fun start(bundleProvider: () -> String) {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (running) {
                    try {
                        val client = socket.accept()
                        handleClient(client, bundleProvider)
                    } catch (e: Exception) {
                        if (running) Log.w("LanExportServer", "accept/handle failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.w("LanExportServer", "server socket failed to start on port $port", e)
                running = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(client: Socket, bundleProvider: () -> String) {
        client.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Drain remaining request headers — we don't need them, but must read past them so
            // the client doesn't see a broken pipe before we write the response.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = sock.getOutputStream()
            when {
                path.startsWith("/debug") -> {
                    val body = bundleProvider().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(
                        out, "200 OK", "text/plain; charset=utf-8", body,
                        extraHeaders = "Content-Disposition: attachment; filename=\"mktv-debug-bundle.txt\"\r\n"
                    )
                }
                else -> {
                    val html = """
                        <!DOCTYPE html><html><head><meta charset="utf-8">
                        <title>MKTV LAN Export</title></head>
                        <body style="font-family:sans-serif;background:#111;color:#eee;padding:24px">
                        <h2>MKTV Debug Bundle</h2>
                        <p>This local device is sharing a diagnostics bundle over your WiFi network.</p>
                        <p><a href="/debug" style="color:#4da3ff">Download mktv-debug-bundle.txt</a></p>
                        </body></html>
                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(out, "200 OK", "text/html; charset=utf-8", html)
                }
            }
            out.flush()
        }
    }

    private fun writeResponse(
        out: java.io.OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: String = ""
    ) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(body)
    }

    /** URL to show/encode as a QR code, or null if no usable LAN IPv4 address was found (e.g. not
     * on WiFi/no local network). */
    fun localUrl(context: Context): String? {
        val ip = findLanIpv4(context) ?: return null
        return "http://$ip:$port/"
    }

    /** Modern LinkProperties-based lookup (available since API 21, well under this app's minSdk
     * 25) instead of the deprecated WifiManager.getConnectionInfo().ipAddress int API — works for
     * WiFi and Ethernet alike and doesn't require the legacy int->dotted-quad conversion. */
    private fun findLanIpv4(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val linkProperties = cm.getLinkProperties(network) ?: return null
            val addr = linkProperties.linkAddresses
                .mapNotNull { it as? LinkAddress }
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            addr?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
