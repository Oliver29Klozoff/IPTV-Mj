package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateMatchTest {
    private fun wanted(setting: Int, sports: Boolean = false, onDemand: Boolean = false, casting: Boolean = false, audio: Boolean = false) =
        FrameRateMatch.wanted(setting, sports, onDemand, casting, audio)

    @Test
    fun `the default matches movies and series but leaves channel surfing alone`() {
        assertEquals(FrameRateMatch.ON_DEMAND, FrameRateMatch.DEFAULT)
        assertTrue(wanted(FrameRateMatch.DEFAULT, onDemand = true))
        assertFalse(wanted(FrameRateMatch.DEFAULT))
    }

    @Test
    fun `sports mode keeps matching unless the setting is off`() {
        assertTrue(wanted(FrameRateMatch.ON_DEMAND, sports = true))
        assertFalse(wanted(FrameRateMatch.OFF, sports = true, onDemand = true))
    }

    @Test
    fun `everything includes live TV, and casting or listening never switch the screen`() {
        assertTrue(wanted(FrameRateMatch.EVERYTHING))
        assertFalse(wanted(FrameRateMatch.EVERYTHING, casting = true))
        assertFalse(wanted(FrameRateMatch.EVERYTHING, audio = true))
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(FrameRateMatch.DEFAULT, FrameRateMatch.normalize(9))
        assertEquals("Movies & series", FrameRateMatch.label(-3))
        assertEquals("Everything", FrameRateMatch.label(FrameRateMatch.EVERYTHING))
    }

    @Test
    fun `the mode picker sends a 24 fps film to 24 Hz and 25 fps TV to 50 Hz at the same resolution`() {
        val modes = listOf(
            SportsMode.RefreshMode(1, 60f, 3840, 2160),
            SportsMode.RefreshMode(2, 24f, 3840, 2160),
            SportsMode.RefreshMode(3, 50f, 3840, 2160),
            SportsMode.RefreshMode(4, 24f, 1920, 1080),
        )
        assertEquals(2, SportsMode.displayModeId(23.976f, modes, 3840, 2160))
        assertEquals(3, SportsMode.displayModeId(25f, modes, 3840, 2160))
        assertEquals(1, SportsMode.displayModeId(29.97f, modes, 3840, 2160))
        assertEquals(0, SportsMode.displayModeId(-1f, modes, 3840, 2160)) // unknown rate: leave the TV alone
    }
}
