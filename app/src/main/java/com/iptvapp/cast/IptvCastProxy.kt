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
 * Minimal local HTTP proxy that adds CORS headers so the Chromecast Default Media
 * Receiver (which enforces CORS in its browser context) can load IPTV streams.
 * Rewrites m3u8 segment URLs to also route through this proxy.
 */
class IptvCastProxy(private val localIp: String) {

    private var serverSocket: ServerSocket? = null
    private var running = false
    var listeningPort: Int = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
        serverSocket?.close()
        serverSocket = null
    }

    fun proxyUrl(originalUrl: String): String =
        "http://$localIp:$listeningPort/s?u=${URLEncoder.encode(originalUrl, "UTF-8")}"

    private fun handleSocket(socket: Socket) {
        try {
            socket.use {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotBlank() == true) {} // drain headers

                val method = requestLine.split(" ").firstOrNull() ?: "GET"
                val path   = requestLine.split(" ").getOrElse(1) { "/" }

                if (method == "OPTIONS") {
                    writeResponse(socket, "200 OK", "text/plain", ByteArray(0))
                    return
                }

                val encodedUrl = if (path.contains("?u=")) path.substringAfter("?u=") else null
                if (encodedUrl == null) {
                    writeResponse(socket, "400 Bad Request", "text/plain", "Missing url".toByteArray())
                    return
                }

                val targetUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                Log.d("CastProxy", "Serving: $targetUrl")
                proxyRequest(socket, targetUrl)
            }
        } catch (e: Exception) {
            Log.e("CastProxy", "Socket handler error", e)
        }
    }

    private fun proxyRequest(socket: Socket, url: String) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            val ct = resp.header("Content-Type") ?: "application/octet-stream"
            val body = resp.body ?: run {
                writeResponse(socket, "502 Bad Gateway", "text/plain", "No body".toByteArray())
                return
            }

            if (ct.contains("mpegurl", ignoreCase = true) || ct.contains("m3u", ignoreCase = true)
                    || url.contains(".m3u8", ignoreCase = true)) {
                val rewritten = rewritePlaylist(body.string(), url).toByteArray()
                writeResponse(socket, "200 OK", "application/x-mpegURL", rewritten)
            } else {
                writeStreamingResponse(socket, "200 OK", ct, body.byteStream())
            }
        } catch (e: Exception) {
            Log.e("CastProxy", "Upstream error for $url", e)
            writeResponse(socket, "502 Bad Gateway", "text/plain", "Upstream: ${e.message}".toByteArray())
        }
    }

    private fun rewritePlaylist(content: String, baseUrl: String): String {
        val baseUri = URI(baseUrl)
        return content.lines().joinToString("\n") { line ->
            when {
                line.isBlank() || line.startsWith("#") -> line
                line.startsWith("http://") || line.startsWith("https://") -> proxyUrl(line.trim())
                else -> proxyUrl(baseUri.resolve(line.trim()).toString())
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
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeStreamingResponse(socket: Socket, status: String, ct: String, stream: java.io.InputStream) {
        val out = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $ct\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        val buf = ByteArray(8192)
        var n: Int
        while (stream.read(buf).also { n = it } != -1) {
            out.write("${n.toString(16)}\r\n".toByteArray())
            out.write(buf, 0, n)
            out.write("\r\n".toByteArray())
        }
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }
}
