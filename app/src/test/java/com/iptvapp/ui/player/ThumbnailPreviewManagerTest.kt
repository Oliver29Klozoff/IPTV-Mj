package com.iptvapp.ui.player

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File

/**
 * The seek preview's picture source with Robolectric's stand-in MediaMetadataRetriever: what is
 * delivered, what is reused, and what is dropped as stale. The retriever runs on a real
 * background thread, so each check runs the main looper until the answer arrives (or a deadline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThumbnailPreviewManagerTest {
    private lateinit var context: Context
    private lateinit var manager: ThumbnailPreviewManager
    private val tenMinutes = 10 * 60_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "seek_thumbs").deleteRecursively()
        manager = ThumbnailPreviewManager(context)
    }

    @After
    fun tearDown() {
        manager.close()
        ShadowMediaMetadataRetriever.reset()
    }

    private fun recording(name: String, frames: Boolean): ThumbnailPreviewManager.Source.Local {
        val uri = Uri.parse("content://recordings/$name")
        if (frames) {
            val ds = DataSource.toDataSource(context, uri)
            // A frame in every 10-second bucket of a 10-minute recording, each a different size so they can be told apart.
            for (s in 0..600 step 10) {
                val bmp = Bitmap.createBitmap(160 + s / 10, 90, Bitmap.Config.ARGB_8888)
                ShadowMediaMetadataRetriever.addFrame(ds, s * 1_000_000L, bmp)
                ShadowMediaMetadataRetriever.addScaledFrame(ds, s * 1_000_000L, ThumbnailPreviewManager.WIDTH_PX, ThumbnailPreviewManager.HEIGHT_PX, bmp)
            }
        }
        return ThumbnailPreviewManager.Source.Local(uri, "rec|$name|1|0")
    }

    /** Runs the main looper until [done] or five seconds pass. */
    private fun runUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Answer { var bucket = -1L; var bitmap: Bitmap? = null; var calls = 0 }

    private fun ask(positionMs: Long): Answer {
        val a = Answer()
        manager.request(positionMs, tenMinutes) { bucket, bmp -> a.bucket = bucket; a.bitmap = bmp; a.calls++ }
        return a
    }

    @Test
    fun aRecordingGivesThePictureForTheNearestBucket() {
        manager.setSource(recording("a", frames = true))
        assertTrue(manager.supported)
        val a = ask(61_000)
        runUntil { a.calls > 0 }
        assertEquals(60_000L, a.bucket)
        assertNotNull(a.bitmap)
        assertEquals(166, a.bitmap!!.width)
    }

    @Test
    fun aPictureAlreadyMadeComesBackAtOnceFromMemory() {
        manager.setSource(recording("b", frames = true))
        val first = ask(120_000)
        runUntil { first.calls > 0 }
        val again = ask(121_000)
        // Answered inside request(), with no background work.
        assertEquals(1, again.calls)
        assertTrue(again.bitmap === first.bitmap)
    }

    @Test
    fun anAnswerForAnOlderPlaceNeverReplacesTheNewest() {
        manager.setSource(recording("c", frames = true))
        val old = ask(30_000)
        val newer = ask(300_000)
        runUntil { newer.calls > 0 }
        assertEquals(0, old.calls)
        assertEquals(300_000L, newer.bucket)
    }

    @Test
    fun anotherVideoDropsTheOldRequestsAndPictures() {
        manager.setSource(recording("d", frames = true))
        val forOld = ask(60_000)
        manager.setSource(recording("e", frames = true))
        runUntil { forOld.calls > 0 }
        assertEquals(0, forOld.calls)
        // No source: nothing is asked, nothing is answered.
        manager.setSource(null)
        assertFalse(manager.supported)
        val none = ask(60_000)
        runUntil { none.calls > 0 }
        assertEquals(0, none.calls)
    }

    @Test
    fun aSourceThatGivesNothingIsSwitchedOffAfterTwoTries() {
        manager.setSource(recording("broken", frames = false))
        val first = ask(60_000)
        runUntil { first.calls > 0 }
        assertNull(first.bitmap)
        assertTrue(manager.supported)
        val second = ask(120_000)
        runUntil { second.calls > 0 }
        assertNull(second.bitmap)
        // The time alone from here on; no more attempts.
        assertFalse(manager.supported)
        val third = ask(180_000)
        runUntil { third.calls > 0 }
        assertEquals(0, third.calls)
    }

    @Test
    fun picturesOutliveThePlayerOnDiskButNotInMemory() {
        manager.setSource(recording("f", frames = true))
        val made = ask(240_000)
        runUntil { made.calls > 0 }
        assertNotNull(made.bitmap)
        manager.close()
        // A new player, the same recording (same key), its retriever now unable to read it.
        manager = ThumbnailPreviewManager(context)
        manager.setSource(ThumbnailPreviewManager.Source.Local(Uri.parse("content://recordings/gone"), "rec|f|1|0"))
        val fromDisk = ask(240_000)
        runUntil { fromDisk.calls > 0 }
        assertNotNull(fromDisk.bitmap)
        // Not answered from memory: a fresh manager starts empty.
        assertEquals(1, fromDisk.calls)
    }

    @Test
    fun withoutTheGoAheadOnlyTheTimeShowsAndFailuresStillCount() {
        manager.setSource(recording("h", frames = false))
        manager.allowed = false
        assertFalse(manager.supported)
        val waiting = ask(60_000)
        runUntil { waiting.calls > 0 }
        assertEquals(0, waiting.calls)
        manager.allowed = true
        repeat(2) { i -> val a = ask(120_000L + i * 60_000L); runUntil { a.calls > 0 } }
        assertFalse(manager.supported)
        // Taking the go-ahead away and giving it back doesn't forget the failures.
        manager.allowed = false
        manager.allowed = true
        assertFalse(manager.supported)
        assertFalse(manager.needsConnectionCheck)
    }

    @Test
    fun closingStopsWorkInFlight() {
        manager.setSource(recording("g", frames = true))
        val a = ask(60_000)
        manager.close()
        runUntil { a.calls > 0 }
        assertEquals(0, a.calls)
    }

    @Test
    fun aRecordingKeyHasItsIdAndFileButNoPath() {
        val f = File(context.cacheDir, "My Show 2026.ts").apply { writeBytes(ByteArray(2048)) }
        val key = ThumbnailPreviewManager.recordingKey(context, 42, Uri.fromFile(f))!!
        assertTrue(key.startsWith("rec|42|2048|"))
        assertFalse(key.contains("My Show"))
        // A deleted recording has no key, so no pictures.
        f.delete()
        assertNull(ThumbnailPreviewManager.recordingKey(context, 42, Uri.fromFile(f)))
    }
}
