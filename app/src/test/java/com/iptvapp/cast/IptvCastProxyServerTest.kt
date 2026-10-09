package com.iptvapp.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** The real proxy, over real sockets: a fake provider on this machine's LAN address, and a
 * "private service" on loopback that nothing may ever reach through the proxy. */
class IptvCastProxyServerTest {

    private lateinit var lanIp: String
    private val servers = mutableListOf<ServerSocket>()
    private val loopbackHits = AtomicInteger(0)
    private var loopbackPort = 0
    private var providerPort = 0
    private lateinit var proxy: IptvCastProxy

    /** A one-request-per-connection HTTP server answering from [respond] (path -> raw response). */
    private fun serve(address: InetAddress, onRequest: () -> Unit = {}, respond: (String) -> String): Int {
        val socket = ServerSocket().apply { bind(InetSocketAddress(address, 0)) }
        servers += socket
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) { /* headers */ }
                    onRequest()
                    it.getOutputStream().write(respond(path).toByteArray())
                }
            }
        }.apply { isDaemon = true }.start()
        return socket.localPort
    }

    private fun ok(body: String, type: String = "text/plain") =
        "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    @Before fun setUp() {
        lanIp = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: ""
        assumeTrue("needs a LAN IPv4 address", lanIp.isNotEmpty())

        loopbackPort = serve(InetAddress.getByName("127.0.0.1"), onRequest = { loopbackHits.incrementAndGet() }) { ok("LOOPBACK-SECRET") }
        providerPort = serve(InetAddress.getByName(lanIp)) { path ->
            when (path) {
                "/live/user/pass/1.m3u8" -> ok(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\nhttp://127.0.0.1:$loopbackPort/secret.ts\n",
                    "application/x-mpegURL"
                )
                "/live/user/pass/seg1.ts" -> ok("SEGMENT-ONE", "video/mp2t")
                "/redirect" -> "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$loopbackPort/secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
        }
        proxy = IptvCastProxy(lanIp, appContext = null).also { it.start() }
    }

    @After fun tearDown() {
        if (::proxy.isInitialized) proxy.stop()
        servers.forEach { runCatching { it.close() } }
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 20000
        return try {
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
        } finally { c.disconnect() }
    }

    @Test fun registeredStreamIsProxiedAndRewrittenToTokenUrls() {
        val castUrl = proxy.proxyUrl("http://$lanIp:$providerPort/live/user/pass/1.m3u8")
        assertTrue(castUrl.startsWith("http://$lanIp:${proxy.listeningPort}/"))
        assertTrue(castUrl.endsWith(".m3u8"))
        assertFalse("no login in the cast URL", castUrl.contains("user") || castUrl.contains("pass"))

        val (code, playlist) = get(castUrl)
        assertEquals(200, code)
        val token = URL(castUrl).path.split('/')[1]
        val children = playlist.lines().filter { it.startsWith("http") }
        assertEquals(2, children.size)
        children.forEach {
            assertTrue(it, it.startsWith("http://$lanIp:${proxy.listeningPort}/$token/r/"))
            assertFalse(it, it.contains("user") || it.contains("127.0.0.1"))
        }

        // The approved child segment plays…
        assertEquals(200 to "SEGMENT-ONE", get(children[0]))
        // …but the child that points at this device is refused before anything reaches it.
        assertEquals(502, get(children[1]).first)
        assertEquals(0, loopbackHits.get())
    }

    @Test fun redirectToThisDeviceIsRefused() {
        val castUrl = proxy.proxyUrl("http://$lanIp:$providerPort/redirect")
        assertEquals(502, get(castUrl).first)
        assertEquals(0, loopbackHits.get())
    }

    @Test fun requestsWithoutTheTokenOrForUnregisteredThingsAreRefused() {
        val castUrl = proxy.proxyUrl("http://$lanIp:$providerPort/live/user/pass/1.m3u8")
        val base = "http://$lanIp:${proxy.listeningPort}"
        val token = URL(castUrl).path.split('/')[1]
        val target = "http://127.0.0.1:$loopbackPort/secret"
        for (path in listOf(
            URL(castUrl).path.removePrefix("/$token"),                 // no token
            "/" + "0".repeat(64) + URL(castUrl).path.removePrefix("/$token"), // wrong token
            "/s?u=$target",                                            // the old open-proxy form
            "/$token/s?u=$target",
            "/file?p=/data/data/com.iptvapp/files/datastore/iptv_prefs.preferences_pb",
            "/$token/file?p=%2Fdata%2Fdata%2Fcom.iptvapp",
            "/$token/f/..%2F..%2Fdata",
            "/$token/r/${"a".repeat(32)}.m3u8"
        )) {
            val code = get(base + path).first
            assertTrue("$path -> $code", code == 403 || code == 404)
        }
        assertEquals(0, loopbackHits.get())
    }

    @Test fun recordingsOutsideAllowedFoldersAreNotRegistered() {
        // No allowed recording folders exist in this host test, so nothing is servable.
        assertEquals(null, proxy.proxyLocalFile("/data/data/com.iptvapp/files/datastore/iptv_prefs.preferences_pb"))
        assertEquals(null, proxy.proxyLocalFile("../../etc/passwd"))
    }

    @Test fun stoppingEndsTheSession() {
        val castUrl = proxy.proxyUrl("http://$lanIp:$providerPort/live/user/pass/1.m3u8")
        assertEquals(200, get(castUrl).first)
        proxy.stop()
        val stillServed = try { get(castUrl).first == 200 } catch (_: Exception) { false }
        assertFalse("a stopped cast's URLs must stop working", stillServed)
    }
}
