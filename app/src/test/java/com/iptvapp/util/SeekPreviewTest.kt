package com.iptvapp.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SeekPreviewTest {

    @get:Rule val tmp = TemporaryFolder()

    private val min = 60_000L
    private val hour = 60 * min

    // ── Time buckets ───────────────────────────────────────────────────

    @Test
    fun picturesFiveOrTenSecondsApart() {
        assertEquals(10_000L, SeekPreview.intervalMs(30 * min))   // a 30-minute episode: 18 s, capped at 10
        assertEquals(10_000L, SeekPreview.intervalMs(2 * hour))   // a 2-hour film
        assertEquals(10_000L, SeekPreview.intervalMs(3 * hour))
        assertEquals(5_000L, SeekPreview.intervalMs(2 * min))     // a short clip: at least 5 s
        assertEquals(10_000L, SeekPreview.intervalMs(0))
    }

    @Test
    fun aPositionMapsToItsNearestBucketAndNeverPastTheEnd() {
        val film = 2 * hour + 5 * min
        assertEquals(76 * min, SeekPreview.bucketMs(76 * min + 4_000, film))
        assertEquals(76 * min + 10_000, SeekPreview.bucketMs(76 * min + 6_000, film))
        assertEquals(0L, SeekPreview.bucketMs(-5_000, film))
        assertEquals(124 * min + 50_000, SeekPreview.bucketMs(film, film))
        // Two places in the same bucket share one picture.
        assertEquals(SeekPreview.bucketMs(600_000, film), SeekPreview.bucketMs(604_000, film))
    }

    @Test
    fun pressesSpeedUpTheLongerTheyGoOn() {
        assertEquals(10_000L, SeekPreview.stepMs(1))
        assertEquals(10_000L, SeekPreview.stepMs(10))
        assertEquals(30_000L, SeekPreview.stepMs(11))
        assertEquals(60_000L, SeekPreview.stepMs(21))
    }

    // ── Choosing, then jumping ─────────────────────────────────────────

    @Test
    fun choosingAPlaceNeverMovesPlaybackUntilItIsCommittedOnce() {
        val s = SeekPreview.Session()
        assertNull(s.commit())
        s.begin(42 * min + 15_000, 2 * hour + 5 * min)
        val g = s.generation
        repeat(3) { s.nudge(forward = true) }
        assertEquals(42 * min + 45_000, s.targetMs)
        assertTrue(s.generation > g)
        s.setTarget(76 * min + 30_000)
        assertEquals(42 * min + 15_000, s.startMs)
        assertEquals(76 * min + 30_000, s.commit())
        assertFalse(s.active)
        assertNull(s.commit())
    }

    @Test
    fun backLeavesPlaybackWhereItWasAndTheTargetStaysInsideTheVideo() {
        val s = SeekPreview.Session()
        s.begin(5_000, 30 * min)
        s.nudge(forward = false)
        assertEquals(0L, s.targetMs)
        s.setTarget(99 * hour)
        assertEquals(30 * min, s.targetMs)
        s.cancel()
        assertFalse(s.active)
        assertNull(s.commit())
        // A new round starts from where playback is, with a fresh press count.
        s.begin(10 * min, 30 * min)
        assertEquals(10 * min, s.targetMs)
        assertEquals(10 * min + 10_000, s.nudge(forward = true))
    }

    @Test
    fun aRunOfFastPressesStaysStableAndAccelerates() {
        val s = SeekPreview.Session()
        s.begin(0, 3 * hour)
        repeat(200) { s.nudge(forward = true) }
        // 10 × 10 s + 10 × 30 s + 180 × 60 s, within the film.
        assertEquals(minOf(100_000L + 300_000L + 180 * 60_000L, 3 * hour), s.targetMs)
        repeat(500) { s.nudge(forward = true) }
        assertEquals(3 * hour, s.targetMs)
    }

    // ── Where the preview goes ─────────────────────────────────────────

    @Test
    fun thePreviewCentresOnTheTargetButStaysOnScreen() {
        // A 1920-px screen, the bar from 40 to 1880, a 400-px preview, 32-px margins.
        fun left(f: Float) = SeekPreview.previewLeftPx(f, 40f, 1840f, 400f, 1920f, 32f)
        assertEquals(40f + 920f - 200f, left(0.5f))
        assertEquals(32f, left(0f))              // the start: pinned to the left margin
        assertEquals(1920f - 32f - 400f, left(1f)) // the end: pinned to the right margin
        assertEquals(32f, left(-3f))
        // A screen narrower than the preview: pinned left, never negative.
        assertEquals(32f, SeekPreview.previewLeftPx(0.9f, 0f, 300f, 400f, 360f, 32f))
    }

    @Test
    fun timesReadAndSpeakNaturally() {
        assertEquals("1:16:30", SeekPreview.clock(76 * min + 30_000))
        assertEquals("4:05", SeekPreview.clock(245_000))
        assertEquals("1 hour 16 minutes 30 seconds", SeekPreview.spoken(76 * min + 30_000))
        assertEquals("2 hours", SeekPreview.spoken(2 * hour))
        assertEquals("0 seconds", SeekPreview.spoken(0))
    }

    // ── Cache identity ─────────────────────────────────────────────────

    @Test
    fun cacheKeysCarryNoCredentialsAndTellProvidersApart() {
        val movie = "http://line.provider.tv:8080/movie/johnsmith/s3cr3tPass/12345.mkv"
        val key = SeekPreview.keyForUrl(movie, -1, "salt")
        assertEquals("xt|-1|line.provider.tv|movie|12345.mkv", key)
        assertFalse(key.contains("johnsmith") || key.contains("s3cr3tPass"))
        // The same film id on another provider, or another panel, is another video.
        assertNotEquals(key, SeekPreview.keyForUrl(movie, 0, "salt"))
        assertNotEquals(key, SeekPreview.keyForUrl("http://other.tv/movie/a/b/12345.mkv", -1, "salt"))
        // Episodes keep their kind.
        assertEquals("xt|2|p.tv|series|778.mp4", SeekPreview.keyForUrl("https://user:pw@P.tv/series/u/p/778.mp4?token=x", 2, "s"))
        // Anything else: a salted hash, nothing readable.
        val odd = "https://cdn.example.com/vod/film.mp4?username=bob&password=hunter2"
        val oddKey = SeekPreview.keyForUrl(odd, -1, "salt")
        assertTrue(oddKey.startsWith("url|-1|"))
        assertFalse(oddKey.contains("bob") || oddKey.contains("hunter2") || oddKey.contains("cdn.example"))
        assertNotEquals(oddKey, SeekPreview.keyForUrl(odd, -1, "another device"))
        // Folder names on disk are hashes too.
        val folder = SeekPreview.fileNameFor(key)
        assertEquals(40, folder.length)
        assertFalse(folder.contains("12345"))
    }

    @Test
    fun aRecordingIsKnownByItsIdAndItsFileSoAReplacedFileIsNew() {
        val a = SeekPreview.keyForRecording(7, 1_000_000, 111)
        assertNotEquals(a, SeekPreview.keyForRecording(7, 2_000_000, 111))
        assertNotEquals(a, SeekPreview.keyForRecording(8, 1_000_000, 111))
        assertNotEquals(a, SeekPreview.keyForRecording(7, 1_000_000, 222))
        assertEquals(a, SeekPreview.keyForRecording(7, 1_000_000, 111))
    }

    // ── Memory ─────────────────────────────────────────────────────────

    @Test
    fun theMemoryCacheStaysUnderItsByteLimit() {
        val lru = SeekPreview.SizedLru<String, ByteArray>(1_000) { it.size.toLong() }
        val evicted = ArrayList<ByteArray>()
        for (i in 0 until 50) evicted += lru.put("k$i", ByteArray(230))
        assertTrue(lru.size <= 1_000)
        assertEquals(4, lru.count())
        assertEquals(46, evicted.size)
        assertNull(lru.get("k0"))
        // Reading keeps an entry: the least recently used goes first.
        lru.get("k46")
        lru.put("new", ByteArray(230))
        assertTrue(lru.get("k46") != null)
        assertNull(lru.get("k47"))
        // Something bigger than the whole cache isn't kept.
        assertTrue(lru.put("huge", ByteArray(5_000)).isEmpty())
        assertNull(lru.get("huge"))
        // Another video's pictures can be dropped together.
        lru.removeIf { it.startsWith("k") }
        assertEquals(1, lru.count())
        lru.clear()
        assertEquals(0L, lru.size)
    }

    // ── Disk ───────────────────────────────────────────────────────────

    private var now = 1_000_000_000_000L
    private var free = Long.MAX_VALUE
    private fun disk(dir: File, maxBytes: Long = 10_000, maxAgeMs: Long = 1_000_000) =
        SeekPreview.DiskCache(dir, maxBytes, maxAgeMs, minFreeBytes = 1_000, clock = { now }, freeBytes = { free })

    @Test
    fun diskPicturesAreReusedAndStayUnderTheSizeLimitOldestFirst() {
        val cache = disk(tmp.newFolder("thumbs"))
        assertTrue(cache.put("film", 60_000, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("film", 60_000))
        assertNull(cache.get("film", 120_000))
        assertNull(cache.get("another film", 60_000))
        for (i in 0 until 30) { now += 10; cache.put("film", i * 60_000L, ByteArray(1_000)) }
        cache.trim()
        assertTrue(cache.sizeBytes() <= 10_000)
        // The oldest went; the newest stayed.
        assertNull(cache.get("film", 0))
        assertTrue(cache.get("film", 29 * 60_000L) != null)
    }

    @Test
    fun oldPicturesExpireAndReplacedOrDeletedVideosAreForgotten() {
        val dir = tmp.newFolder("thumbs")
        val cache = disk(dir, maxAgeMs = 5_000)
        cache.put("rec|7|100|0", 0, ByteArray(10))
        cache.put("rec|8|100|0", 0, ByteArray(10))
        now += 6_000
        assertNull(cache.get("rec|7|100|0", 0))
        cache.trim()
        assertEquals(0L, cache.sizeBytes())
        now += 1
        cache.put("rec|9|100|0", 0, ByteArray(10))
        cache.remove("rec|9|100|0")
        assertNull(cache.get("rec|9|100|0", 0))
    }

    @Test
    fun aMissingFullOrDamagedCacheIsJustAMiss() {
        val dir = tmp.newFolder("thumbs")
        val cache = disk(dir)
        cache.put("film", 0, ByteArray(10))
        // Deleted from outside (Clear cache).
        dir.deleteRecursively()
        assertNull(cache.get("film", 0))
        assertTrue(cache.put("film", 0, ByteArray(10)))
        // An empty or half-written file isn't served.
        val folder = File(dir, SeekPreview.fileNameFor("film"))
        File(folder, "60000.jpg").writeBytes(ByteArray(0))
        File(folder, "120000.jpg.tmp").writeBytes(ByteArray(5))
        assertNull(cache.get("film", 60_000))
        cache.trim()
        assertFalse(File(folder, "120000.jpg.tmp").exists())
        // Low on space: nothing written, nothing thrown.
        free = 10
        assertFalse(cache.put("film", 180_000, ByteArray(10)))
        // A file where the folder should be: still no exception.
        val blocked = tmp.newFile("blocked")
        assertFalse(SeekPreview.DiskCache(blocked, 1_000, 1_000, 0).put("x", 0, ByteArray(1)))
        assertNull(SeekPreview.DiskCache(blocked, 1_000, 1_000, 0).get("x", 0))
    }

    // ── Newest request only ────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fastSeekingRunsTheCurrentJobAndOnlyTheNewestWaitingOne() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val done = ArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val worker = SeekPreview.LatestWorker<Int>(scope) { n ->
            if (n == 1) gate.await()
            done += n
        }
        worker.send(1)
        scope.advanceUntilIdle()   // 1 is running and waiting on the gate
        for (n in 2..100) worker.send(n)
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(listOf(1, 100), done)
        worker.close()
        worker.send(101)
        scope.advanceUntilIdle()
        assertEquals(listOf(1, 100), done)
    }
}
