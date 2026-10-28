package com.iptvapp.util

import com.iptvapp.data.local.entities.WatchLogEntity
import org.junit.Assert.*
import org.junit.Test

class WatchStatsTest {
    private val now = 1_793_145_600_000L
    private fun day(back: Int) = WatchStats.day(now - back * 86_400_000L)

    @Test fun `totals weeks, months and channels across providers`() {
        val rows = listOf(
            WatchLogEntity(day(0), -1, 1, "ESPN", minutes = 60f, starts = 2),
            WatchLogEntity(day(1), 0, 9, "ESPN", minutes = 30f, starts = 1),
            WatchLogEntity(day(8), -1, 2, "CNN", minutes = 45f, starts = 1),
            WatchLogEntity(day(20), -1, 3, "FOX", minutes = 5f, starts = 4, failures = 3),
        )
        val s = WatchStats.summarize(rows, now)
        assertEquals(90f, s.thisWeekMinutes, 0.01f)
        assertEquals(45f, s.lastWeekMinutes, 0.01f)
        assertEquals(140f, s.monthMinutes, 0.01f)
        assertEquals(4, s.activeDays)
        assertEquals(14, s.days.size)
        assertEquals(60f, s.days.last().second, 0.01f)
        // ESPN on two providers is one channel, and the most watched.
        assertEquals("ESPN", s.topChannels.first().name)
        assertEquals(90f, s.topChannels.first().minutes, 0.01f)
        assertNull(s.topChannels.first().serverIndex)
        assertEquals(listOf("FOX"), s.trouble.map { it.name })
    }

    @Test fun `stalls count as trouble only with enough watching`() {
        val rows = listOf(
            WatchLogEntity(day(0), -1, 1, "A", minutes = 30f, stalls = 3),
            WatchLogEntity(day(0), -1, 2, "B", minutes = 2f, stalls = 2),
        )
        assertEquals(listOf("A"), WatchStats.summarize(rows, now).trouble.map { it.name })
        assertTrue(WatchStats.summarize(emptyList(), now).empty)
        assertEquals("1 h 30 min", WatchStats.duration(90f))
    }
}
