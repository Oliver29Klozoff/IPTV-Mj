package com.iptvapp.util

import com.iptvapp.ui.player.timeshift.TimeshiftLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsModeTest {

    private val chiefs = "NFL Football: Kansas City Chiefs at Buffalo Bills"
    private val studio = "Inside the NBA"

    @Test
    fun sportsModeStaysOffUntilAGameOrAnOverride() {
        assertEquals(SportsMode.OFF, SportsMode.normalizeSetting(99))
        assertFalse(SportsMode.active(SportsMode.OFF, chiefs, null))
        assertFalse(SportsMode.active(SportsMode.GAMES, studio, null))
        assertFalse(SportsMode.active(SportsMode.GAMES, null, null))
        assertFalse(SportsMode.active(SportsMode.GAMES, "NFL Live", null))
        assertTrue(SportsMode.active(SportsMode.GAMES, chiefs, null))
        assertTrue(SportsMode.active(SportsMode.ALL_LIVE, null, null))
        assertTrue(SportsMode.active(SportsMode.OFF, studio, true))
        assertFalse(SportsMode.active(SportsMode.GAMES, chiefs, false))
    }

    @Test
    fun sportsReplayDoesNotShortenASavedTimeshiftWindow() {
        assertEquals(60, SportsMode.replayMinutes(60, true))
        assertEquals(30, SportsMode.replayMinutes(30, true))
        assertEquals(15, SportsMode.replayMinutes(15, false))
        assertEquals(0, SportsMode.replayMinutes(0, false))
        assertEquals(SportsMode.SESSION_REPLAY_MINUTES, SportsMode.replayMinutes(0, true))
        val session = TimeshiftLimits.resolve(SportsMode.SESSION_REPLAY_MINUTES, Long.MAX_VALUE)
        assertEquals(5L * 60L * 1000L, session.durationMs)
        assertTrue(session.localEnabled)
        assertEquals(30L * 60L * 1000L, TimeshiftLimits.resolve(99, Long.MAX_VALUE).durationMs)
    }

    @Test
    fun fastestBufferIsOnlyTheSportsProfile() {
        val fast = SportsMode.loadControl(sportsOn = true, extraBuffering = true)
        assertEquals(2_000, fast.minBufferMs)
        assertEquals(8_000, fast.maxBufferMs)
        assertEquals(1_000, fast.bufferForPlaybackMs)
        assertEquals(2_000, fast.bufferForPlaybackAfterRebufferMs)
        assertEquals(120_000, fast.backBufferMs)
        val extra = SportsMode.loadControl(sportsOn = false, extraBuffering = true)
        assertEquals(90_000, extra.minBufferMs)
        assertEquals(240_000, extra.maxBufferMs)
        assertEquals(10_000, extra.bufferForPlaybackMs)
        assertEquals(15_000, extra.bufferForPlaybackAfterRebufferMs)
        val normal = SportsMode.loadControl(sportsOn = false, extraBuffering = false)
        assertEquals(50_000, normal.minBufferMs)
        assertEquals(120_000, normal.maxBufferMs)
        assertEquals(5_000, normal.bufferForPlaybackMs)
        assertEquals(10_000, normal.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun frameRatePicksThePanelOrTwiceTheRate() {
        val modes = listOf(
            SportsMode.RefreshMode(1, 24f),
            SportsMode.RefreshMode(2, 48f),
            SportsMode.RefreshMode(3, 50f),
            SportsMode.RefreshMode(4, 60f),
            SportsMode.RefreshMode(5, 59.94f),
            SportsMode.RefreshMode(6, 120f)
        )
        assertEquals(1, SportsMode.displayModeId(23.976f, modes))
        assertEquals(2, SportsMode.displayModeId(24f, listOf(modes[1], modes[3])))
        assertEquals(3, SportsMode.displayModeId(25f, modes))
        assertEquals(5, SportsMode.displayModeId(30f, listOf(modes[4])))
        assertEquals(4, SportsMode.displayModeId(60f, listOf(modes[5], modes[3])))
        assertEquals(0, SportsMode.displayModeId(24f, listOf(modes[3])))
        assertEquals(0, SportsMode.displayModeId(0f, modes))
        assertEquals(0, SportsMode.displayModeId(30f, emptyList()))
        val hd = SportsMode.RefreshMode(4, 60f, 1920, 1080)
        val uhd = SportsMode.RefreshMode(7, 60f, 3840, 2160)
        val hd48 = SportsMode.RefreshMode(2, 48f, 1920, 1080)
        assertEquals(7, SportsMode.displayModeId(60f, listOf(hd, uhd), 3840, 2160))
        assertEquals(0, SportsMode.displayModeId(24f, listOf(hd48, uhd), 3840, 2160))
    }

    @Test
    fun aSettledNativeWindowIsNotProbedAgain() {
        assertFalse(SportsMode.needsSessionBuffer(true, 0, sessionRunning = false, directFallback = false, nativeSettled = true))
        assertTrue(SportsMode.needsSessionBuffer(true, 0, sessionRunning = false, directFallback = false, nativeSettled = false))
        assertFalse(SportsMode.needsSessionBuffer(true, 30, sessionRunning = false, directFallback = false, nativeSettled = false))
    }

    @Test
    fun scoreComesOnlyFromTheTitle() {
        assertEquals("2\u20131", SportsMode.scoreInTitle("Premier League Soccer: Arsenal 2-1 Chelsea"))
        assertEquals(
            "Yankees 4, Red Sox 3",
            SportsMode.scoreInTitle("MLB Baseball: Yankees 4, Red Sox 3")
        )
        assertEquals("Score 2 to 1", SportsMode.scoreDescription("2\u20131"))
        assertNull(SportsMode.scoreInTitle(studio))
        assertNull(SportsMode.scoreInTitle("UFC 310: Pantoja vs. Asakura"))
        assertNull(SportsMode.scoreInTitle("NFL 24-25: Chiefs at Bills"))
        assertNull(SportsMode.scoreInTitle("NFL Football: 7-9 pm"))
        assertNull(SportsMode.scoreInTitle("Local News at 6"))
    }

    @Test
    fun teamsComeFromTheMatchupAndFavoritesStayShort() {
        assertEquals(
            listOf("Kansas City Chiefs", "Buffalo Bills"),
            SportsMode.teamsInTitle("NFL Football: Kansas City Chiefs at Buffalo Bills - ESPN")
        )
        assertEquals(listOf("Pantoja", "Asakura"), SportsMode.teamsInTitle("UFC 310: Pantoja vs. Asakura"))
        assertEquals(listOf("Yankees", "Red Sox"), SportsMode.teamsInTitle("MLB: Yankees 4 at Red Sox 2"))
        assertTrue(SportsMode.teamsInTitle(studio).isEmpty())

        var stored = ""
        stored = SportsMode.addFavorite(stored, "Yankees")
        stored = SportsMode.addFavorite(stored, " yankees ")
        stored = SportsMode.addFavorite(stored, "Red Sox")
        assertEquals(listOf("Yankees", "Red Sox"), SportsMode.parseTeams(stored))
        val longName = "A".repeat(50)
        stored = SportsMode.addFavorite(stored, longName)
        assertEquals(40, SportsMode.parseTeams(stored).last().length)
        repeat(20) { stored = SportsMode.addFavorite(stored, "Side$it") }
        assertEquals(SportsMode.MAX_FAVORITE_TEAMS, SportsMode.parseTeams(stored).size)
        stored = SportsMode.removeFavorite(stored, "red sox")
        assertFalse(SportsMode.parseTeams(stored).any { it.equals("Red Sox", true) })
        assertTrue(SportsMode.titleHasTeam(chiefs, "Buffalo Bills"))
        assertFalse(SportsMode.titleHasTeam(chiefs, "Billsburg"))
    }

    @Test
    fun aFavoriteTeamTunesTheLiveGameFirst() {
        val channel = SportsFinder.Channel(-1, 7, "ESPN", false)
        val later = game("NFL Football: Kansas City Chiefs at Buffalo Bills", 50_000L, 80_000L, channel)
        val live = game("NFL Football: Kansas City Chiefs at Buffalo Bills", 0L, 40_000L, channel)
        val other = game("NBA Basketball: Celtics at Knicks", 0L, 40_000L, channel)
        val picked = SportsMode.gameForTeam(listOf(later, other, live), "Chiefs", 10_000L)
        assertEquals(live.startMs, picked?.startMs)
        assertNull(SportsMode.gameForTeam(listOf(other), "Chiefs", 10_000L))
    }

    @Test
    fun catchUpOffersAfterThirtyFiveMinutesOnAnArchiveChannel() {
        val start = 1_000_000L
        val stop = start + 60L * 60_000L
        val at = start + SmartCatchUp.OFFER_AFTER_MS
        assertFalse(SmartCatchUp.shouldOffer(true, start, stop, at - 1, false, 0L, false))
        assertTrue(SmartCatchUp.shouldOffer(true, start, stop, at, false, 0L, false))
        assertEquals("Started 35 min ago", SmartCatchUp.startedLabel(start, at))
        assertFalse(SmartCatchUp.shouldOffer(false, start, stop, at, false, 0L, false))
        assertFalse(SmartCatchUp.shouldOffer(true, start, stop, at, true, 0L, false))
        assertFalse(SmartCatchUp.shouldOffer(true, start, stop, at, false, start, false))
        assertFalse(SmartCatchUp.shouldOffer(true, start, stop, at, false, 0L, true))
        assertFalse(SmartCatchUp.shouldOffer(true, start, stop, stop, false, 0L, false))
        assertFalse(SmartCatchUp.shouldOffer(true, start, stop, start - 1, false, 0L, false))
        assertEquals(SmartCatchUp.OFFER_AFTER_MS - 60_000L, SmartCatchUp.msUntilOffer(true, start, stop, start + 60_000L, false, 0L, false))
        assertNull(SmartCatchUp.msUntilOffer(true, start, stop, at, false, 0L, false))
        assertNull(SmartCatchUp.msUntilOffer(true, start, stop, start + 60_000L, false, start, false))
        assertNull(SmartCatchUp.msUntilOffer(true, start, start + 20L * 60_000L, start + 60_000L, false, 0L, false))
    }

    private fun game(title: String, start: Long, stop: Long, channel: SportsFinder.Channel) =
        SportsFinder.Game("NFL", title, start, stop, channel, 0)
}
