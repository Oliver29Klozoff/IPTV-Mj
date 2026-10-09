package com.iptvapp.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/** The cast proxy's rules: only this cast's token, only resources MKTV registered, only recordings,
 * only http(s), and never this device or unrelated local-network hosts. */
class CastProxyGuardTest {

    private lateinit var tmp: File
    private lateinit var recordings: File
    private lateinit var appPrivate: File
    private lateinit var recording: File
    private lateinit var secret: File
    private lateinit var guard: CastProxyGuard

    @Before fun setUp() {
        tmp = Files.createTempDirectory("castguard").toFile()
        recordings = File(tmp, "Movies/MKTV").apply { mkdirs() }
        appPrivate = File(tmp, "data/com.iptvapp").apply { mkdirs() }
        recording = File(recordings, "show.ts").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        secret = File(appPrivate, "files/datastore/iptv_prefs.preferences_pb").apply {
            parentFile!!.mkdirs(); writeText("password=hunter2")
        }
        guard = CastProxyGuard(allowedFileRoots = listOf(File(tmp, "Movies")), forbiddenFileRoots = listOf(appPrivate))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun token() = guard.prefix().removePrefix("/")

    // 1. Missing token
    @Test fun missingTokenIsRejected() {
        val path = guard.registerUpstream("https://cdn.example.com/live/1.m3u8", castSource = true)!!
        val withoutToken = path.removePrefix(guard.prefix())
        assertEquals(CastProxyGuard.Route.Rejected("403 Forbidden"), guard.route("GET", withoutToken))
        assertEquals(CastProxyGuard.Route.Rejected("403 Forbidden"), guard.route("GET", "/"))
        assertEquals(CastProxyGuard.Route.Rejected("403 Forbidden"), guard.route("GET", ""))
    }

    // 2. Wrong token
    @Test fun wrongTokenIsRejected() {
        val path = guard.registerUpstream("https://cdn.example.com/live/1.m3u8", castSource = true)!!
        val wrong = "/" + "0".repeat(64) + path.removePrefix(guard.prefix())
        assertEquals(CastProxyGuard.Route.Rejected("403 Forbidden"), guard.route("GET", wrong))
        val otherSession = CastProxyGuard(emptyList(), emptyList())
        val theirs = otherSession.prefix() + path.removePrefix(guard.prefix())
        assertEquals(CastProxyGuard.Route.Rejected("403 Forbidden"), guard.route("GET", theirs))
    }

    @Test fun tokensAreLongAndRandom() {
        val a = token()
        val b = CastProxyGuard(emptyList(), emptyList()).prefix().removePrefix("/")
        assertEquals(64, a.length) // 256 bits
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(a, b)
    }

    // 3 + 12. A closed session's token and resources stop working
    @Test fun closedSessionRejectsEverything() {
        val up = guard.registerUpstream("https://cdn.example.com/live/1.m3u8", castSource = true)!!
        val file = guard.registerFile(recording.path)!!
        val (liveId, live) = guard.registerLive()!!
        val seg = guard.segmentPath(liveId, 3)
        guard.close()
        for (p in listOf(up, file, live, seg)) {
            assertEquals(CastProxyGuard.Route.Rejected("410 Gone"), guard.route("GET", p))
        }
        assertNull(guard.registerUpstream("https://cdn.example.com/x.ts", castSource = false))
        assertNull(guard.registerFile(recording.path))
        assertNull(guard.registerLive())
    }

    // 4. Arbitrary filesystem paths can't be requested or registered
    @Test fun arbitraryPathsAreNotServed() {
        val t = guard.prefix()
        for (p in listOf(
            "$t/file?p=${secret.path}", "$t/file?p=%2Fdata%2Fdata%2Fcom.iptvapp", "$t/f${secret.path}",
            "$t/f/${secret.name}", "/file?p=${secret.path}", "$t/s?u=file:///etc/passwd"
        )) {
            assertEquals("$p must be refused", CastProxyGuard.Route.Rejected(if (p.startsWith(t)) "404 Not Found" else "403 Forbidden"), guard.route("GET", p))
        }
        assertNull(guard.registerFile(secret.path))
        assertNull(guard.registerFile("/data/data/com.iptvapp/databases/iptv_db"))
        assertNull(guard.registerFile("relative/show.ts"))
        assertNull(guard.registerFile("content://com.iptvapp.provider/files/prefs"))
    }

    // 5. ../ traversal
    @Test fun dotDotTraversalFails() {
        assertNull(guard.registerFile("${recordings.path}/../../data/com.iptvapp/files/datastore/iptv_prefs.preferences_pb"))
        assertNull(guard.registerFile("file://${recordings.path}/../../data/com.iptvapp/files/datastore/iptv_prefs.preferences_pb"))
        val t = guard.prefix()
        assertEquals(CastProxyGuard.Route.Rejected("404 Not Found"), guard.route("GET", "$t/f/../../data/com.iptvapp"))
    }

    // 6. URL-encoded traversal (never decoded, so never a path)
    @Test fun encodedTraversalFails() {
        val t = guard.prefix()
        for (p in listOf("$t/f/%2e%2e%2f%2e%2e%2fdata", "$t/f/..%2F..%2Fdata", "$t/%2e%2e/f/x", "/%2e%2e$t/f/x")) {
            assertTrue("$p must be refused", guard.route("GET", p) is CastProxyGuard.Route.Rejected)
        }
        assertNull(guard.registerFile("${recordings.path}/%2e%2e/%2e%2e/data/com.iptvapp/files/datastore/iptv_prefs.preferences_pb"))
    }

    @Test fun symlinkOutOfRecordingsFails() {
        val link = File(recordings, "link.ts")
        try {
            Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (_: Exception) {
            return // this machine can't make symlinks; nothing to test
        }
        assertNull(guard.registerFile(link.path))
    }

    // 7. Unregistered files
    @Test fun unregisteredFileIdFails() {
        guard.registerFile(recording.path)!!
        assertEquals(CastProxyGuard.Route.Rejected("404 Not Found"), guard.route("GET", "${guard.prefix()}/f/${"a".repeat(32)}.ts"))
    }

    // 8. Unregistered upstream URLs
    @Test fun unregisteredUpstreamFails() {
        guard.registerUpstream("https://cdn.example.com/live/1.m3u8", castSource = true)!!
        val t = guard.prefix()
        assertEquals(CastProxyGuard.Route.Rejected("404 Not Found"), guard.route("GET", "$t/r/${"b".repeat(32)}.m3u8"))
        assertEquals(CastProxyGuard.Route.Rejected("404 Not Found"), guard.route("GET", "$t/s?u=http%3A%2F%2Fevil.example%2F"))
        assertEquals(CastProxyGuard.Route.Rejected("404 Not Found"), guard.route("GET", "$t/r?u=http://evil.example/"))
    }

    // 9. localhost / internal-network targets
    @Test fun internalAddressesAreBlocked() {
        guard.registerUpstream("http://192.168.1.50:8080/live/a/b/1.ts", castSource = true)
        fun blocked(ip: String, host: String = ip) = guard.isBlockedAddress(InetAddress.getByName(ip), host)
        assertTrue(blocked("127.0.0.1", "localhost"))
        assertTrue(blocked("::1"))
        assertTrue(blocked("0.0.0.0"))
        assertTrue(blocked("169.254.169.254")) // cloud metadata
        assertTrue(blocked("fe80::1"))
        assertTrue(blocked("224.0.0.1"))
        assertTrue(blocked("10.0.2.2")) // emulator host alias
        assertTrue(blocked("192.168.1.1")) // the router — not the registered source
        assertTrue(blocked("172.16.0.5"))
        assertTrue(blocked("100.64.0.1"))
        assertTrue(blocked("fd00::5"))
        assertTrue(blocked("::ffff:127.0.0.1"))
        // The LAN IPTV server MKTV itself chose to cast from is allowed; public hosts are allowed.
        assertFalse(blocked("192.168.1.50"))
        assertFalse(blocked("93.184.216.34", "cdn.example.com"))
        // A private address under some other name (DNS rebinding, a playlist's child) is not.
        assertTrue(blocked("192.168.1.50", "evil.example"))
    }

    @Test fun playlistChildrenDoNotGrantLocalNetworkAccess() {
        guard.registerUpstream("https://cdn.example.com/live/1.m3u8", castSource = true)
        assertNotNull(guard.registerUpstream("http://192.168.1.1/admin", castSource = false))
        assertTrue(guard.isBlockedAddress(InetAddress.getByName("192.168.1.1"), "192.168.1.1"))
    }

    // 10. Unsupported schemes
    @Test fun unsupportedSchemesFail() {
        for (u in listOf("file:///etc/passwd", "content://media/external/video/1", "ftp://example.com/x",
            "javascript:alert(1)", "data:text/plain,hi", "jar:file:///x!/y", "gopher://example.com", "not a url", "")) {
            assertNull(u, guard.registerUpstream(u, castSource = true))
            assertFalse(u, guard.allowCastSource(u))
        }
    }

    // 11. Registered resources work
    @Test fun registeredResourcesWork() {
        val url = "https://cdn.example.com/live/user/pass/1.m3u8"
        val up = guard.registerUpstream(url, castSource = true)!!
        assertTrue(up.endsWith(".m3u8"))
        assertFalse("the cast URL must not carry the stream URL", up.contains("user") || up.contains("cdn"))
        assertEquals(CastProxyGuard.Route.Upstream(url), guard.route("GET", up))
        assertEquals(up, guard.registerUpstream(url, castSource = false)) // same URL, same handle

        val file = guard.registerFile(recording.path)!!
        assertTrue(file.endsWith(".ts"))
        assertEquals(CastProxyGuard.Route.LocalFile(recording.path), guard.route("GET", file))
        assertEquals(CastProxyGuard.Route.LocalFile(recording.path), guard.route("HEAD", file))

        val media = guard.registerFile("content://media/external/video/media/42")!!
        assertEquals(CastProxyGuard.Route.LocalFile("content://media/external/video/media/42"), guard.route("GET", media))

        val (liveId, live) = guard.registerLive()!!
        assertEquals(liveId, guard.liveIdOf("http://192.168.1.2:1234$live"))
        assertEquals(CastProxyGuard.Route.Live(liveId), guard.route("GET", live))
        assertEquals(CastProxyGuard.Route.Segment(liveId, 7), guard.route("GET", guard.segmentPath(liveId, 7)))
        assertEquals(CastProxyGuard.Route.Options, guard.route("OPTIONS", up))
    }

    // A long VOD playlist's own segments stay valid while it's served, and the source survives.
    @Test fun largePlaylistKeepsItsHandles() {
        val source = guard.registerUpstream("https://cdn.example.com/vod/movie.m3u8", castSource = true)!!
        val segments = (0 until 5000).map { guard.registerUpstream("https://cdn.example.com/vod/seg$it.ts", castSource = false)!! }
        segments.forEachIndexed { i, p ->
            assertEquals(CastProxyGuard.Route.Upstream("https://cdn.example.com/vod/seg$i.ts"), guard.route("GET", p))
        }
        assertEquals(CastProxyGuard.Route.Upstream("https://cdn.example.com/vod/movie.m3u8"), guard.route("GET", source))
    }

    @Test fun otherMethodsAndMalformedPathsFail() {
        val up = guard.registerUpstream("https://cdn.example.com/1.m3u8", castSource = true)!!
        assertEquals(CastProxyGuard.Route.Rejected("405 Method Not Allowed"), guard.route("POST", up))
        assertEquals(CastProxyGuard.Route.Rejected("405 Method Not Allowed"), guard.route("CONNECT", "cdn.example.com:443"))
        val t = guard.prefix()
        val (liveId, _) = guard.registerLive()!!
        assertTrue(guard.route("GET", "$t/seg/$liveId/-1.ts") is CastProxyGuard.Route.Rejected)
        assertTrue(guard.route("GET", "$t/seg/$liveId/abc.ts") is CastProxyGuard.Route.Rejected)
        assertTrue(guard.route("GET", "$t/seg/${"c".repeat(32)}/1.ts") is CastProxyGuard.Route.Rejected)
        assertTrue(guard.route("GET", "$t/r/${up.substringAfterLast('/')}/extra") is CastProxyGuard.Route.Rejected)
    }

    @Test fun extensionsCarryOnlyTheMediaType() {
        assertEquals(".m3u8", CastProxyGuard.extensionOf("/live/u/p/1.m3u8"))
        assertEquals(".ts", CastProxyGuard.extensionOf("/live/u/p/1.TS"))
        assertEquals("", CastProxyGuard.extensionOf("/get.php"))
        assertEquals("", CastProxyGuard.extensionOf("/x.secret-token"))
        assertEquals("", CastProxyGuard.extensionOf(null))
    }
}
