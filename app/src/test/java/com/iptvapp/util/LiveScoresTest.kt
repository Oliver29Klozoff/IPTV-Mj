package com.iptvapp.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveScoresTest {
    private val start = 1_793_145_600_000L // 2026-10-28T00:00Z

    private fun board(vararg games: String) = """{"events":[${games.joinToString(",")}]}"""
    private fun game(away: Pair<String, String>, home: Pair<String, String>, awayScore: Int, homeScore: Int,
                     state: String = "in", detail: String = "Bot 9th", period: Int = 9, clock: String = "0:00",
                     date: String = "2026-10-28T00:00Z", network: String = "Fox") = """
        {"date":"$date","competitions":[{"status":{"period":$period,"displayClock":"$clock",
          "type":{"state":"$state","shortDetail":"$detail"}},
          "broadcasts":[{"names":["$network"]}],
          "competitors":[
            {"homeAway":"home","score":"$homeScore","team":{"abbreviation":"${home.first}","displayName":"${home.second}","shortDisplayName":"${home.second.substringAfterLast(' ')}","name":"${home.second.substringAfterLast(' ')}","location":"${home.second.substringBeforeLast(' ')}"}},
            {"homeAway":"away","score":"$awayScore","team":{"abbreviation":"${away.first}","displayName":"${away.second}","shortDisplayName":"${away.second.substringAfterLast(' ')}","name":"${away.second.substringAfterLast(' ')}","location":"${away.second.substringBeforeLast(' ')}"}}
          ]}]}"""

    @Test fun `parses a scoreboard into labelled events`() {
        val e = LiveScores.parse(board(game("MIL" to "Milwaukee Brewers", "TB" to "Tampa Bay Rays", 3, 4)), "MLB").single()
        assertEquals(start, e.startMs)
        assertEquals("MIL 3 – TB 4 · Bot 9th", e.label)
        assertEquals(listOf("Fox"), e.networks)
        assertTrue(e.close)
        assertEquals("", LiveScores.parse(board(game("A" to "Aa Bbb", "B" to "Cc Ddd", 0, 0, state = "pre")), "MLB").single().label)
    }

    @Test fun `close games are late and tight, per sport`() {
        fun ev(league: String, a: Int, h: Int, period: Int, clock: String = "0:00") =
            LiveScores.parse(board(game("A" to "Aa Bbb", "B" to "Cc Ddd", a, h, period = period, clock = clock)), league).single()
        assertTrue(ev("NFL", 20, 24, 4).close)
        assertFalse(ev("NFL", 20, 24, 2).close)
        assertFalse(ev("NBA", 90, 110, 4).close)
        assertTrue(ev("NHL", 2, 2, 3).close)
        assertTrue(ev("Soccer", 1, 1, 2, "78'").close)
        assertFalse(ev("Soccer", 1, 1, 2, "50'").close)
    }

    @Test fun `matches by teams, then by the one game on the channel's network`() {
        val events = LiveScores.parse(board(
            game("MIL" to "Milwaukee Brewers", "TB" to "Tampa Bay Rays", 3, 4, network = "Fox"),
            game("NYY" to "New York Yankees", "BOS" to "Boston Red Sox", 1, 0, network = "ESPN"),
        ), "MLB")
        assertEquals("TB", LiveScores.match("MLB", "MLB Baseball: Brewers at Rays", start, listOf("Anything"), events)?.home?.abbr)
        assertEquals("BOS", LiveScores.match("MLB", "MLB Baseball", start, listOf("US: ESPN HD"), events)?.home?.abbr)
        // Two games at once and no network to tell them apart: no guess.
        assertNull(LiveScores.match("MLB", "MLB Baseball", start, listOf("Local 4"), events))
        // Teams named that are not on the board: someone else's game.
        assertNull(LiveScores.match("MLB", "MLB Baseball: Cubs at Mets", start, listOf("US: FOX"), events))
        assertNull(LiveScores.match("NFL", "NFL Football", start, listOf("US: FOX"), events))
    }

    @Test fun `channel names carry networks`() {
        assertTrue(LiveScores.carries("US: FOX 5 HD", "Fox"))
        assertTrue(LiveScores.carries("ESPN 2", "ESPN2"))
        assertFalse(LiveScores.carries("CNN", "TNT"))
    }

    @Test fun `guide titles the sports screen used to miss`() {
        val expect = mapOf(
            "Monday Night Football" to "NFL", "Sunday Night Football" to "NFL", "NFL RedZone" to "NFL",
            "Saturday Night Football" to "College football",
            "World Series" to "MLB", "MLB Postseason" to "MLB", "College Baseball" to "College baseball",
            "College Hockey" to "College hockey", "UEFA Champions League" to "Soccer", "Fútbol Mexicano" to "Soccer",
            "Top Rank Boxing" to "Fighting", "IndyCar Series" to "Racing", "LPGA Tour Golf" to "Golf",
            "Tennis: US Open" to "Tennis", "ATP Tennis" to "Tennis",
        )
        expect.forEach { (title, league) -> assertEquals(title, league, SportsFinder.leagueOf(title)) }
        for (show in listOf("World Series of Poker", "Fútbol Picante", "NFL Live", "SportsCenter", "School of Golf", "Little League World Series"))
            assertNotEquals(show, "MLB", SportsFinder.leagueOf(show))
        assertNull(SportsFinder.leagueOf("World Series of Poker"))
        assertNull(SportsFinder.leagueOf("School of Golf"))
    }

    @Test fun `team-only titles need today's teams on both sides`() {
        val teams = mapOf("NFL" to setOf("Dallas Cowboys", "Cowboys", "New York Giants", "Giants"))
        assertEquals("NFL", SportsFinder.leagueOf("Cowboys vs. Giants", teams))
        assertNull(SportsFinder.leagueOf("Cowboys vs. Aliens", teams))
        assertNull(SportsFinder.leagueOf("Cowboys vs. Giants"))
    }
}
