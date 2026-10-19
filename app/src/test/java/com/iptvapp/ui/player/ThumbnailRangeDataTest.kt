package com.iptvapp.ui.player

import android.app.Application
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections

/**
 * Reading a provider's file for a seek preview picture: in bounded pieces only (never the whole
 * file), nearby reads served from pieces already read, within a byte budget, through the
 * provider's redirect. Against a small local server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThumbnailRangeDataTest {
    private lateinit var server: java.net.ServerSocket
    private val file = ByteArray(1_000_000) { (it % 251).toByte() }
    @Volatile private var honourRanges = true
    @Volatile private var redirect = false
    private val requests = Collections.synchronizedList(ArrayList<Triple<String, String?, String?>>())
    private val piece = ThumbnailPreviewManager.RangeData.PIECE_BYTES

    @Before
    fun setUp() {
        server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Keep-alive HTTP/1.1: several requests per connection, as a real panel allows. */
    private fun serve(socket: java.net.Socket) = socket.use { s ->
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val out = s.getOutputStream()
        while (true) {
            val first = reader.readLine() ?: return
            val path = first.split(' ').getOrNull(1).orEmpty()
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val range = headers["range"]
            requests += Triple(path, range, headers["user-agent"])
            try {
                if (redirect && path.startsWith("/movie/")) {
                    out.write("HTTP/1.1 302 Found\r\nLocation: /stream/token123/1.mp4\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush()
                    continue
                }
                val m = Regex("""bytes=(\d+)-(\d*)""").find(range.orEmpty())
                if (honourRanges && m != null) {
                    val start = m.groupValues[1].toInt()
                    val end = (m.groupValues[2].toIntOrNull() ?: (file.size - 1)).coerceAtMost(file.size - 1)
                    out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${file.size}\r\n" +
                        "Content-Length: ${end - start + 1}\r\n\r\n").toByteArray())
                    out.write(file, start, end - start + 1)
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${file.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(file)
                    out.flush()
                    return
                }
                out.flush()
            } catch (_: Exception) { return }
        }
    }

    @After
    fun tearDown() = server.close()

    private val url get() = "http://127.0.0.1:${server.localPort}/movie/user/pass/1.mp4"

    @Test
    fun readsTheRightBytesInBoundedPieces() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        assertEquals(file.size.toLong(), data.size)
        val buf = ByteArray(100)
        assertEquals(100, data.readAt(500_000, buf, 0, 100))
        assertArrayEquals(file.copyOfRange(500_000, 500_100), buf)
        // Across the edge of two pieces already read: no new request.
        val edge = ByteArray(20)
        assertEquals(20, data.readAt(piece - 10L, edge, 0, 20))
        assertArrayEquals(file.copyOfRange(piece - 10, piece + 10), edge)
        assertEquals(-1, data.readAt(file.size.toLong(), buf, 0, 10))
        data.close()
        assertEquals(listOf("bytes=0-${piece - 1}", "bytes=$piece-${2 * piece - 1}"), requests.map { it.second })
        assertTrue(requests.all { it.third == "MKTV-test" })
    }

    @Test
    fun theRetrieversSmallScatteredReadsCostOneRequest() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        val buf = ByteArray(8)
        // The pattern seen on a phone: probes at 0, 6295, 5927, 7659, 9674, ...
        for (at in listOf(0L, 6295L, 5927L, 7659L, 9674L, 12L, 40_000L, 3L)) {
            assertEquals(8, data.readAt(at, buf, 0, 8))
            assertArrayEquals(file.copyOfRange(at.toInt(), at.toInt() + 8), buf)
        }
        assertEquals(1, data.requests)
        data.close()
    }

    @Test
    fun theLastPieceIsShortAndTheEndIsReported() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        val buf = ByteArray(1_000)
        val n = data.readAt(file.size - 300L, buf, 0, 1_000)
        assertEquals(300, n)
        assertArrayEquals(file.copyOfRange(file.size - 300, file.size), buf.copyOf(300))
        data.close()
    }

    @Test
    fun aServerThatIgnoresRangesIsNotDownloadedFromTheStart() {
        honourRanges = false
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        try {
            data.readAt(400_000, ByteArray(10), 0, 10)
            fail("expected the read to be refused")
        } catch (_: IOException) {}
        data.close()
    }

    @Test
    fun aFileThatNeedsTooMuchReadingIsGivenUpOn() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test", budget = 600_000)
        val buf = ByteArray(8_192)
        var read = 0L
        try {
            while (true) {
                val n = data.readAt(read, buf, 0, buf.size)
                if (n < 0) break
                read += n
            }
            fail("expected the budget to stop it")
        } catch (_: IOException) {}
        // Three pieces at most: the third one passes the budget and stops it.
        assertTrue(read <= 3L * piece)
        assertTrue(data.requests <= 3)
        data.close()
    }

    @Test
    fun aReadStopsWhenThePreviewCloses() {
        var closed = false
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test", cancelled = { closed })
        val buf = ByteArray(100)
        assertEquals(100, data.readAt(0, buf, 0, 100))
        closed = true
        try {
            data.readAt(600_000, buf, 0, 100)
            fail("expected the read to stop")
        } catch (_: IOException) {}
        data.abort = true
        try {
            data.readAt(0, buf, 0, 100)
            fail("expected the read to stop")
        } catch (_: IOException) {}
        data.close()
    }

    @Test
    fun eachRequestIsTimedForTheOneStreamExperiment() {
        ThumbnailPreviewManager.RangeData.lastRequestAt = 0L
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        data.readAt(0, ByteArray(10), 0, 10)
        assertTrue(ThumbnailPreviewManager.RangeData.lastRequestAt > 0L)
        data.close()
    }

    @Test
    fun theProvidersRedirectIsFollowedOnceAndRemembered() {
        redirect = true
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        val buf = ByteArray(10)
        data.readAt(0, buf, 0, 10)
        data.readAt(600_000, buf, 0, 10)
        data.releaseConnection()
        data.readAt(900_000, buf, 0, 10)
        assertArrayEquals(file.copyOfRange(900_000, 900_010), buf)
        // The film's own address once; every later piece straight from where it was sent.
        assertEquals(1, requests.count { it.first.startsWith("/movie/") })
        assertEquals(3, requests.count { it.first.startsWith("/stream/") })
        data.close()
    }
}
