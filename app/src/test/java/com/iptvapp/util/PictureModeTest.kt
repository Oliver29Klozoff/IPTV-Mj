package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureModeTest {
    @Test
    fun autoUsesSportsForALiveGameAndMovieForAFilm() {
        assertEquals(PictureMode.PROFILE_SPORTS, PictureMode.profileName(PictureMode.AUTO, live = true, title = "NBA: Celtics vs Lakers", movie = false))
        assertEquals(PictureMode.PROFILE_MOVIE, PictureMode.profileName(PictureMode.AUTO, live = false, title = "The Apartment", movie = true))
        assertEquals(PictureMode.PROFILE_STANDARD, PictureMode.profileName(PictureMode.AUTO, live = true, title = "Local news", movie = false))
        assertEquals(PictureMode.PROFILE_STANDARD, PictureMode.profileName(PictureMode.AUTO, live = false, title = "Episode 4", movie = false))
    }

    @Test
    fun anExplicitChoiceWinsOverTheTitle() {
        assertEquals(PictureMode.PROFILE_MOVIE, PictureMode.profileName(PictureMode.MOVIE, live = true, title = "Local news", movie = false))
        assertEquals(PictureMode.PROFILE_STANDARD, PictureMode.profileName(PictureMode.STANDARD, live = true, title = "NBA: Celtics vs Lakers", movie = false))
        assertEquals(PictureMode.PROFILE_SPORTS, PictureMode.profileName(PictureMode.SPORTS, live = false, title = "The Apartment", movie = true))
    }

    @Test
    fun sportsAndMoviesMatchTheFrameRateAndStandardLeavesItUnlessSportsModeIsOn() {
        assertTrue(PictureMode.matchFrameRate(PictureMode.AUTO, true, "NBA: Celtics vs Lakers", false, sportsBufferOn = false))
        assertTrue(PictureMode.matchFrameRate(PictureMode.AUTO, false, "The Apartment", true, sportsBufferOn = false))
        assertFalse(PictureMode.matchFrameRate(PictureMode.AUTO, true, "Local news", false, sportsBufferOn = false))
        assertTrue(PictureMode.matchFrameRate(PictureMode.STANDARD, true, "Local news", false, sportsBufferOn = true))
    }

    @Test
    fun aBadSavedValueFallsBackToAuto() {
        assertEquals(PictureMode.AUTO, PictureMode.normalize(9))
        assertEquals("Auto", PictureMode.label(9))
        assertEquals("Sports", PictureMode.label(PictureMode.SPORTS))
    }
}
