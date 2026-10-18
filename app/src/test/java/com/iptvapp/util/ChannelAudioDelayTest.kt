package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAudioDelayTest {
    @Test
    fun onlyAChannelOrItsReplayGetsADelay() {
        assertTrue(ChannelAudioDelay.offersFor(isVod = false, isCatchup = false, isRecording = false))
        assertTrue(ChannelAudioDelay.offersFor(isVod = true, isCatchup = true, isRecording = false))
        assertFalse(ChannelAudioDelay.offersFor(isVod = true, isCatchup = false, isRecording = false))
        assertFalse(ChannelAudioDelay.offersFor(isVod = false, isCatchup = false, isRecording = true))
    }

    @Test
    fun aNudgeStepsByFiftyAndStopsAtTwoSeconds() {
        assertEquals(50, ChannelAudioDelay.nudge(0, 1))
        assertEquals(-50, ChannelAudioDelay.nudge(0, -1))
        assertEquals(2_000, ChannelAudioDelay.nudge(2_000, 1))
        assertEquals(-2_000, ChannelAudioDelay.nudge(-1_950, -1))
        assertEquals(0, ChannelAudioDelay.nudge(50, -1))
    }

    @Test
    fun theLabelSaysWhichWayTheSoundSits() {
        assertEquals("Sound in sync", ChannelAudioDelay.label(0))
        assertEquals("Sound 200 ms late", ChannelAudioDelay.label(200))
        assertEquals("Sound 200 ms early", ChannelAudioDelay.label(-200))
    }

    @Test
    fun aLaterSoundShowsThePictureSooner() {
        assertEquals(-200_000L, ChannelAudioDelay.videoShiftUs(200))
        assertEquals(200_000L, ChannelAudioDelay.videoShiftUs(-200))
        assertEquals(0L, ChannelAudioDelay.videoShiftUs(0))
    }

    @Test
    fun eachChannelKeepsItsOwnDelayAndAResetDropsIt() {
        assertEquals("-1:15", ChannelAudioDelay.key(-1, 15))
        assertNull(ChannelAudioDelay.key(0, -1))
        val saved = ChannelAudioDelay.write("", "-1:15", 200)
        val both = ChannelAudioDelay.write(saved, "2:9", -100)
        assertEquals(200, ChannelAudioDelay.delayMs(both, "-1:15"))
        assertEquals(-100, ChannelAudioDelay.delayMs(both, "2:9"))
        assertEquals(0, ChannelAudioDelay.delayMs(both, "0:1"))
        assertEquals("-1:15=200", ChannelAudioDelay.write(both, "2:9", 0))
        assertEquals("", ChannelAudioDelay.write("nope,0:1=notanumber", "0:1", 0))
    }

    @Test
    fun removingAProviderDropsItsDelaysAndMovesLaterOnesDown() {
        val stored = listOf("-1:15" to 200, "0:9" to -100, "1:9" to 50, "2:4" to 150)
            .fold("") { acc, (key, ms) -> ChannelAudioDelay.write(acc, key, ms) }
        val afterFirst = ChannelAudioDelay.afterProviderRemoved(stored, 0)
        assertEquals(200, ChannelAudioDelay.delayMs(afterFirst, "-1:15"))
        assertEquals(50, ChannelAudioDelay.delayMs(afterFirst, "0:9"))
        assertEquals(150, ChannelAudioDelay.delayMs(afterFirst, "1:4"))
        assertEquals(0, ChannelAudioDelay.delayMs(afterFirst, "2:4"))
        assertEquals(stored, ChannelAudioDelay.afterProviderRemoved(stored, -1))
    }
}
