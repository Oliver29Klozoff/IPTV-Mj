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
 * Reading a provider's file for a seek preview picture: by ranges only, never the whole file,
 * and within a byte budget. Against a small local server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThumbnailRangeDataTest {
    private lateinit var server: java.net.ServerSocket
    private val file = ByteArray(1_000_000) { (it % 251).toByte() }
    @Volatile private var honourRanges = true
    private val requests = Collections.synchronizedList(ArrayList<Pair<String?, String?>>())

    @Before
    fun setUp() {
        server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        // One request per connection (Connection: close), answered from [file].
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(socket: java.net.Socket) = socket.use { s ->
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val range = headers["range"]
        requests += range to headers["user-agent"]
        val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        val out = s.getOutputStream()
        try {
            if (honourRanges && start != null) {
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${file.size - 1}/${file.size}\r\n" +
                    "Content-Length: ${file.size - start}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(file, start, file.size - start)
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${file.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(file)
            }
            out.flush()
        } catch (_: Exception) { /* the client closed early, as it should */ }
    }

    @After
    fun tearDown() = server.close()

    private val url get() = "http://127.0.0.1:${server.localPort}/movie/user/pass/1.mp4"

    @Test
    fun readsByRangeFromWhereverTheRetrieverAsks() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        assertEquals(file.size.toLong(), data.size)
        val buf = ByteArray(100)
        assertEquals(100, data.readAt(500_000, buf, 0, 100))
        assertArrayEquals(file.copyOfRange(500_000, 500_100), buf)
        // Carrying on from there reuses the open response; a jump opens a new range.
        assertEquals(100, data.readAt(500_100, buf, 0, 100))
        assertArrayEquals(file.copyOfRange(500_100, 500_200), buf)
        data.readAt(10, buf, 0, 10)
        assertEquals(-1, data.readAt(file.size.toLong(), buf, 0, 10))
        data.close()
        assertEquals(listOf("bytes=0-", "bytes=500000-", "bytes=10-"), requests.map { it.first })
        assertTrue(requests.all { it.second == "MKTV-test" })
    }

    @Test
    fun noConnectionIsHeldOpenBetweenPictures() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        val buf = ByteArray(10)
        data.readAt(1_000, buf, 0, 10)
        data.releaseConnection()
        // Carrying on from the same place still opens a fresh range: the old response is gone.
        assertEquals(10, data.readAt(1_010, buf, 0, 10))
        assertArrayEquals(file.copyOfRange(1_010, 1_020), buf)
        assertEquals(listOf("bytes=1000-", "bytes=1010-"), requests.map { it.first })
        data.close()
    }

    @Test
    fun aReadInProgressStopsWhenThePreviewCloses() {
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test")
        val buf = ByteArray(100)
        assertEquals(100, data.readAt(0, buf, 0, 100))
        data.abort = true
        try {
            data.readAt(100, buf, 0, 100)
            fail("expected the read to stop")
        } catch (_: IOException) {}
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
        val data = ThumbnailPreviewManager.RangeData(url, "MKTV-test", budget = 50_000)
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
        assertTrue(read <= 50_000)
        data.close()
    }
}
