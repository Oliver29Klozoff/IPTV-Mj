package com.iptvapp.util

import com.iptvapp.util.PlaybackSync.Offsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSyncTest {
    @Test
    fun offsetsStopAtTenSecondsEitherWay() {
        assertEquals(10_000, PlaybackSync.clampAudio(25_000))
        assertEquals(-10_000, PlaybackSync.clampAudio(-25_000))
        assertEquals(10_000, PlaybackSync.clampSubtitle(Long.MAX_VALUE))
        assertEquals(-10_000, PlaybackSync.clampSubtitle(Long.MIN_VALUE))
        assertEquals(-350, PlaybackSync.clampSubtitle(-350))
    }

    @Test
    fun labelsShowTheSignAndTheUnit() {
        assertEquals("0 ms", PlaybackSync.label(0))
        assertEquals("+250 ms", PlaybackSync.label(250))
        assertEquals("−50 ms", PlaybackSync.label(-50))
        assertEquals("+1 s", PlaybackSync.label(1_000))
        assertEquals("−1.5 s", PlaybackSync.label(-1_500))
        assertEquals("+2.25 s", PlaybackSync.label(2_250))
        assertEquals("Sound 250 ms later than the picture", PlaybackSync.audioMeaning(250))
        assertEquals("Subtitles 1.5 s earlier", PlaybackSync.subtitleMeaning(-1_500))
        assertEquals("plus 250 milliseconds", PlaybackSync.spoken(250))
        assertEquals("minus 1.5 seconds", PlaybackSync.spoken(-1_500))
    }

    @Test
    fun keysCarryIdsAndHashesOnly() {
        assertEquals("c:-1:42", PlaybackSync.channelKey(-1, 42))
        assertNull(PlaybackSync.channelKey(0, -1))
        val movie = PlaybackSync.movieKey(2, 900, "Heat")!!
        assertTrue(movie.startsWith("m:2:900:"))
        assertTrue(PlaybackSync.isKey(movie))
        // Same id, different film (a provider reusing ids): a different key.
        assertNotEquals(movie, PlaybackSync.movieKey(2, 900, "Ronin"))
        // Case and spacing of the title don't matter.
        assertEquals(movie, PlaybackSync.movieKey(2, 900, "  heat "))
        val episode = PlaybackSync.episodeKey(-1, "77/../12", "S01E02")!!
        assertTrue(episode.startsWith("e:-1:7712:"))
        assertNull(PlaybackSync.episodeKey(-1, "", "S01E02"))
        val url = "http://host.example:8080/movie/user/secretpass/900.mkv"
        val file = PlaybackSync.fileKey(url, "Heat")!!
        assertFalse(file.contains("secretpass"))
        assertFalse(file.contains("host.example"))
        assertTrue(PlaybackSync.isKey(file))
        assertNull(PlaybackSync.fileKey(" ", "Heat"))
    }

    @Test
    fun aSavedValueReadsBackAndZeroRemovesIt() {
        val key = PlaybackSync.movieKey(-1, 5, "Heat")!!
        var stored = PlaybackSync.write("", key, Offsets(audioMs = 200, subtitleMs = -1_500))
        assertEquals(Offsets(200, -1_500), PlaybackSync.read(stored, key))
        stored = PlaybackSync.write(stored, key, Offsets())
        assertEquals("", stored)
        assertEquals(Offsets(), PlaybackSync.read(stored, key))
    }

    @Test
    fun audioAndSubtitlesAreIndependent() {
        val key = "c:-1:7"
        val stored = PlaybackSync.write("", key, Offsets(audioMs = 0, subtitleMs = 400))
        assertEquals(Offsets(0, 400), PlaybackSync.read(stored, key))
    }

    @Test
    fun damagedOrForeignEntriesAreIgnored() {
        val stored = "c:-1:7=100/200,garbage,c:x:7=1/1,m:1:2:short=5/5,c:-1:8=abc/1,c:-1:9=99999999/-99999999"
        val map = PlaybackSync.decode(stored)
        assertEquals(Offsets(100, 200), map["c:-1:7"])
        assertEquals(Offsets(10_000, -10_000), map["c:-1:9"])
        assertEquals(2, map.size)
        assertEquals(Offsets(), PlaybackSync.read("", "c:-1:7"))
        // An invalid key is never written.
        assertEquals("", PlaybackSync.write("", "user:pass", Offsets(1, 1)))
    }

    @Test
    fun theStoreKeepsTheMostRecentlySaved() {
        var stored = ""
        for (i in 0..PlaybackSync.MAX_SAVED) stored = PlaybackSync.write(stored, "c:-1:$i", Offsets(subtitleMs = 100))
        val map = PlaybackSync.decode(stored)
        assertEquals(PlaybackSync.MAX_SAVED, map.size)
        assertFalse(map.containsKey("c:-1:0"))
        assertTrue(map.containsKey("c:-1:${PlaybackSync.MAX_SAVED}"))
        // Saving an old one again makes it recent.
        stored = PlaybackSync.write(stored, "c:-1:1", Offsets(subtitleMs = 200))
        stored = PlaybackSync.write(stored, "c:-1:9999", Offsets(subtitleMs = 200))
        val after = PlaybackSync.decode(stored)
        assertTrue(after.containsKey("c:-1:1"))
        assertFalse(after.containsKey("c:-1:2"))
    }

    @Test
    fun providerChangesMoveOrDropEntries() {
        var stored = ""
        stored = PlaybackSync.write(stored, "c:-1:1", Offsets(subtitleMs = 100))
        stored = PlaybackSync.write(stored, "c:0:2", Offsets(subtitleMs = 200))
        stored = PlaybackSync.write(stored, "m:1:3:abcdef12", Offsets(audioMs = 300))
        val fileKey = PlaybackSync.fileKey("/sdcard/rec.ts", "News")!!
        stored = PlaybackSync.write(stored, fileKey, Offsets(audioMs = 400))

        // Extra provider 0 is gone, 1 moved to 0.
        val moved = PlaybackSync.decode(PlaybackSync.reindex(stored, mapOf(1 to 0)))
        assertEquals(Offsets(subtitleMs = 100), moved["c:-1:1"])
        assertNull(moved["c:0:2"])
        assertEquals(Offsets(audioMs = 300), moved["m:0:3:abcdef12"])
        assertEquals(Offsets(audioMs = 400), moved[fileKey])

        // The primary account replaced: its entries go, the others stay.
        val dropped = PlaybackSync.decode(PlaybackSync.dropServer(stored, -1))
        assertNull(dropped["c:-1:1"])
        assertNotNull(dropped["c:0:2"])
        assertNotNull(dropped[fileKey])
    }

    @Test
    fun audioSaysWhyItCannotAct() {
        assertNull(PlaybackSync.audioUnavailableReason(tunneled = false, casting = false, listeningOnly = false, hasVideo = true))
        assertNotNull(PlaybackSync.audioUnavailableReason(tunneled = true, casting = false, listeningOnly = false, hasVideo = true))
        assertNotNull(PlaybackSync.audioUnavailableReason(tunneled = false, casting = true, listeningOnly = false, hasVideo = true))
        assertNotNull(PlaybackSync.audioUnavailableReason(tunneled = false, casting = false, listeningOnly = true, hasVideo = true))
        assertNotNull(PlaybackSync.audioUnavailableReason(tunneled = false, casting = false, listeningOnly = false, hasVideo = false))
    }

    @Test
    fun theSoundShiftMovesThePictureTheOtherWay() {
        // Positive audio delay = sound later = each frame shown sooner.
        assertEquals(-250_000L, ChannelAudioDelay.videoShiftUs(250))
        assertEquals(1_500_000L, ChannelAudioDelay.videoShiftUs(-1_500))
    }
}
