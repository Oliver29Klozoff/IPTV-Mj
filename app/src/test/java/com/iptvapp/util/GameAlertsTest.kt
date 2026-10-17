package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAlertsTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val ch = SportsFinder.Channel(-1, 1, "ESPN", false)
    private fun game(title: String, startMs: Long) =
        SportsFinder.Game("NBA", title, startMs, startMs + 150 * min, ch, 0)

    @Test
    fun `a favorite team's game is planned for its start`() {
        val g = game("NBA: Celtics vs Lakers", now + 90 * min)
        val plan = GameAlerts.plan(listOf(g, game("NBA: Bulls vs Heat", now + 30 * min)), listOf("Lakers"), emptySet(), now)
        assertEquals(1, plan.size)
        assertEquals(90 * min, plan.single().delayMs)
        assertEquals(GameAlerts.keyOf(g), plan.single().key)
    }

    @Test
    fun `a game just under way is announced at once, a long-running one is not`() {
        val justStarted = game("NBA: Celtics vs Lakers", now - 10 * min)
        val wellUnderWay = game("NBA: Lakers vs Suns", now - 40 * min)
        val plan = GameAlerts.plan(listOf(justStarted, wellUnderWay), listOf("Lakers"), emptySet(), now)
        assertEquals(listOf(0L), plan.map { it.delayMs })
    }

    @Test
    fun `nothing twice, nothing past tomorrow, nothing without teams`() {
        val g = game("NBA: Celtics vs Lakers", now + 60 * min)
        assertTrue(GameAlerts.plan(listOf(g), listOf("Lakers"), setOf(GameAlerts.keyOf(g)), now).isEmpty())
        assertTrue(GameAlerts.plan(listOf(game("NBA: Celtics vs Lakers", now + 30 * 60 * min)), listOf("Lakers"), emptySet(), now).isEmpty())
        assertTrue(GameAlerts.plan(listOf(g), emptyList(), emptySet(), now).isEmpty())
    }

    @Test
    fun `one game on several feeds is one alert`() {
        val a = game("NBA: Celtics vs Lakers", now + 60 * min)
        val b = a.copy(channel = SportsFinder.Channel(0, 9, "ESPN · Backup", false))
        assertEquals(1, GameAlerts.plan(listOf(a, b), listOf("Lakers", "Celtics"), emptySet(), now).size)
    }
}
