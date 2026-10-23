package com.iptvapp.util

import com.iptvapp.data.local.entities.FeedStatsEntity
import com.iptvapp.ui.player.FeedTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedScoreTest {
    private val now = 10_000_000_000L
    private fun row(
        outcomes: String = "",
        tuneMs: Long = 0L,
        stallsPerHour: Float = 0f,
        watchedMinutes: Float = 0f,
        lastFailureAt: Long = 0L,
    ) = FeedStatsEntity(0, 1, outcomes, tuneMs, stallsPerHour, watchedMinutes, lastFailureAt, now)

    @Test
    fun `no history is the neutral score`() {
        assertEquals(FeedScore.UNKNOWN, FeedScore.score(null, now))
        assertEquals(FeedScore.UNKNOWN, FeedScore.score(row(), now))
    }

    @Test
    fun `a proven clean copy beats an unknown one and a flaky one falls below the backup bar`() {
        val clean = FeedScore.score(row("1111111111", tuneMs = 1_500L, stallsPerHour = 0f, watchedMinutes = 300f), now)
        val flaky = FeedScore.score(
            row("1010010100", tuneMs = 9_000L, stallsPerHour = 30f, watchedMinutes = 120f, lastFailureAt = now - 60_000L),
            now,
        )
        assertTrue("clean=$clean", clean > 85)
        assertTrue(clean > FeedScore.UNKNOWN)
        assertTrue("flaky=$flaky", flaky < com.iptvapp.data.repository.XtreamRepository.MIN_BACKUP_SCORE)
    }

    @Test
    fun `one lucky start does not outrank a long clean record`() {
        val lucky = FeedScore.score(row("1", tuneMs = 800L), now)
        val steady = FeedScore.score(row("1111111111", tuneMs = 2_000L, watchedMinutes = 200f), now)
        assertTrue("lucky=$lucky steady=$steady", steady > lucky)
    }

    @Test
    fun `a recent failure costs more than an old one`() {
        val base = row("1111111110", tuneMs = 2_000L, watchedMinutes = 60f)
        val justFailed = FeedScore.score(base.copy(lastFailureAt = now - 5 * 60_000L), now)
        val failedYesterday = FeedScore.score(base.copy(lastFailureAt = now - 24 * 3_600_000L), now)
        assertTrue(failedYesterday > justFailed)
    }

    @Test
    fun `starts keep the last ten and smooth the tune time`() {
        var r = row("1111111111", tuneMs = 2_000L)
        r = FeedScore.afterStart(r, played = false, tuneMs = 30_000L, nowMs = now)
        assertEquals("1111111110", r.outcomes)
        assertEquals(2_000L, r.tuneMs) // a failure says nothing about tune time
        assertEquals(now, r.lastFailureAt)
        r = FeedScore.afterStart(r, played = true, tuneMs = 4_000L, nowMs = now)
        assertEquals(2_600L, r.tuneMs)
    }

    @Test
    fun `watching folds stalls into a per-hour rate and the history is capped`() {
        var r = FeedScore.afterWatching(row(), minutes = 30f, stalls = 3, nowMs = now)
        assertEquals(6f, r.stallsPerHour, 0.001f)
        assertEquals(30f, r.watchedMinutes, 0.001f)
        r = FeedScore.afterWatching(r, minutes = 30f, stalls = 0, nowMs = now)
        assertEquals(3f, r.stallsPerHour, 0.001f)
        r = FeedScore.afterWatching(r.copy(watchedMinutes = 599f), minutes = 30f, stalls = 0, nowMs = now)
        assertEquals(FeedScore.MAX_WATCHED_MINUTES, r.watchedMinutes, 0.001f)
    }

    @Test
    fun `three stalls in two minutes switch, but not while the channel settles`() {
        val m = StallMonitor()
        m.newChannel(0L)
        assertFalse(m.onStall(5_000L)) // settling
        assertFalse(m.onStall(20_000L))
        assertFalse(m.onStall(60_000L))
        assertTrue(m.onStall(100_000L))
    }

    @Test
    fun `stalls spread out never switch`() {
        val m = StallMonitor()
        m.newChannel(0L)
        assertFalse(m.onStall(20_000L))
        assertFalse(m.onStall(200_000L))
        assertFalse(m.onStall(400_000L))
        assertFalse(m.onStall(600_000L))
    }

    @Test
    fun `after a switch the new copy gets a cooldown and the budget runs out`() {
        val m = StallMonitor()
        m.newChannel(0L)
        listOf(20_000L, 30_000L).forEach { m.onStall(it) }
        assertTrue(m.onStall(40_000L))
        m.switched(40_000L)
        // Three quick stalls on the new copy, still inside its cooldown.
        assertFalse(m.onStall(60_000L))
        assertFalse(m.onStall(70_000L))
        assertFalse(m.onStall(80_000L))
        val n = StallMonitor(maxSwitches = 1)
        n.newChannel(0L)
        listOf(20_000L, 30_000L).forEach { n.onStall(it) }
        assertTrue(n.onStall(40_000L))
        n.switched(40_000L)
        listOf(300_000L, 310_000L).forEach { n.onStall(it) }
        assertFalse(n.onStall(320_000L)) // budget spent
        n.newChannel(400_000L)
        listOf(420_000L, 430_000L).forEach { n.onStall(it) }
        assertTrue(n.onStall(440_000L)) // a channel the user picked starts fresh
    }

    @Test
    fun `tracker reports a start, its tune time, and watching between stalls`() {
        val t = FeedTracker()
        val key = FeedTracker.Key(2, 77)
        assertTrue(t.open(key, 1_000L).isEmpty())
        assertFalse(t.onBuffering(1_500L)) // initial load is not a stall
        val started = t.onReady(3_500L)
        assertEquals(listOf(FeedTracker.Report.Started(key, true, 2_500L)), started)
        assertFalse(t.onBuffering(63_500L)) // one stall after a minute of watching
        t.onReady(65_500L)
        val watched = t.open(FeedTracker.Key(-1, 5), 125_500L)
        assertEquals(1, watched.size)
        val w = watched.single() as FeedTracker.Report.Watched
        assertEquals(key, w.key)
        assertEquals(2f, w.minutes, 0.001f)
        assertEquals(1, w.stalls)
    }

    @Test
    fun `a copy that fails before playing is a failed start, once`() {
        val t = FeedTracker()
        val key = FeedTracker.Key(-1, 9)
        t.open(key, 0L)
        assertEquals(listOf(FeedTracker.Report.Started(key, false, 4_000L)), t.onFailed(4_000L))
        assertTrue(t.onFailed(8_000L).isEmpty())
        assertTrue(t.onReady(9_000L).isEmpty())
    }

    @Test
    fun `paused time is not watching time`() {
        val t = FeedTracker()
        val key = FeedTracker.Key(0, 3)
        t.open(key, 0L)
        t.onReady(1_000L)
        t.onNotPlaying(61_000L)
        t.onReady(3_661_000L) // an hour paused, then one more minute
        val w = t.flush(3_721_000L).single() as FeedTracker.Report.Watched
        assertEquals(2f, w.minutes, 0.001f)
    }

    @Test
    fun aCopyThatPlaysThenFailsCountsAsARecentFailure() {
        val tracker = FeedTracker()
        val key = FeedTracker.Key(-1, 101)
        tracker.open(key, 0L)
        tracker.onReady(1_500L)
        val reports = tracker.onLost(60_000L)
        assertTrue(reports.last() is FeedTracker.Report.Lost)
        assertEquals(key, reports.last().key)

        val now = 10_000_000L
        val good = FeedStatsEntity(-1, 101, outcomes = "1111", tuneMs = 1_500L, updatedAt = now)
        val lost = FeedScore.afterLost(good, now)
        assertEquals(now, lost.lastFailureAt)
        // Its start history stays; the recent failure lowers the score.
        assertEquals("1111", lost.outcomes)
        assertTrue(FeedScore.score(lost, now + 1_000L) < FeedScore.score(good, now + 1_000L))
    }

    @Test
    fun aCopyLostBeforeItEverPlayedIsAFailedStart() {
        val tracker = FeedTracker()
        tracker.open(FeedTracker.Key(0, 7), 0L)
        val r = tracker.onLost(5_000L).single()
        assertTrue(r is FeedTracker.Report.Started && !r.played)
    }
}
