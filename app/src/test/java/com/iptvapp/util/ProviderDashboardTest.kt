package com.iptvapp.util

import com.iptvapp.data.local.entities.FeedStatsEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.ui.player.ChannelSourcesDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ProviderDashboardTest {
    private val now = 10_000_000_000_000L
    private val hour = 3_600_000L

    private fun row(id: Int, outcomes: String, tune: Long, sph: Float, minutes: Float, failedAt: Long = 0L) =
        FeedStatsEntity(0, id, outcomes, tune, sph, minutes, failedAt, now)

    @Test
    fun `summary adds up starts, averages tune time and weights stalls by watching`() {
        Locale.setDefault(Locale.US)
        val s = ProviderDashboard.summarize(
            listOf(
                row(1, "1111", 2_000L, 2f, 60f),
                row(2, "1100", 4_000L, 8f, 20f, failedAt = now - 2 * hour),
                row(3, "", 0L, 0f, 0f, failedAt = now - 48 * hour),
            ),
            now,
        )
        assertEquals(2, s.channelsTracked)
        assertEquals(75, s.startSuccessPercent)
        assertEquals(3_000L, s.avgTuneMs)
        assertEquals(3.5, s.stallsPerHour!!, 0.0001) // (2*60 + 8*20) / 80
        assertEquals(1, s.failuresLast24h)
    }

    @Test
    fun `nothing watched yet has no numbers to show`() {
        val s = ProviderDashboard.summarize(emptyList(), now)
        assertEquals(0, s.channelsTracked)
        assertNull(s.startSuccessPercent)
        assertNull(s.avgTuneMs)
        assertNull(s.stallsPerHour)
    }

    @Test
    fun `labels, quality tags and times read naturally`() {
        assertEquals("New", ProviderDashboard.scoreLabel(95, hasHistory = false))
        assertEquals("Excellent", ProviderDashboard.scoreLabel(90, true))
        assertEquals("Good", ProviderDashboard.scoreLabel(72, true))
        assertEquals("Fair", ProviderDashboard.scoreLabel(55, true))
        assertEquals("Poor", ProviderDashboard.scoreLabel(20, true))
        assertEquals("FHD", ProviderDashboard.qualityOf("US: ESPN FHD"))
        assertEquals("HD", ProviderDashboard.qualityOf("ESPN HD"))
        assertEquals("4K", ProviderDashboard.qualityOf("ESPN 4K"))
        assertEquals("SD", ProviderDashboard.qualityOf("ESPN (SD)"))
        assertNull(ProviderDashboard.qualityOf("HDTV Classics")) // a word, not a tag
        assertEquals("3 h ago", ProviderDashboard.agoText(now - 3 * hour, now))
        assertEquals("2 days ago", ProviderDashboard.agoText(now - 50 * hour, now))
        assertNull(ProviderDashboard.agoText(0L, now))
        assertEquals("Guide: none", ProviderDashboard.guideText(null, now))
        assertEquals("Guide: next 26 h", ProviderDashboard.guideText((now + 26 * hour) / 1000L, now)) // seconds
        assertEquals("Guide: next 3 days", ProviderDashboard.guideText(now + 80 * hour, now))
        assertEquals("Guide: ended 5 h ago", ProviderDashboard.guideText(now - 5 * hour, now))
    }

    @Test
    fun `a provider block says what a viewer needs to know`() {
        Locale.setDefault(Locale.US)
        val feeds = ProviderDashboard.FeedSummary(14, 92, 2_100L, 1.3, 2)
        val block = ProviderDashboard.providerBlock(
            "T-Rex", isPrimary = true, reachable = true, responseMs = 230L, error = null,
            feeds = feeds, catalogRefreshedAt = now - 3 * hour, guideEnd = now + 26 * hour, nowMs = now,
        )
        assertEquals(
            listOf(
                "T-Rex (main)",
                "● Online · answers in 230 ms",
                "Starts 92% · tunes in 2.1 s · 1.3 stalls an hour · 14 channels watched",
                "2 channels failed in the last 24 h",
                "Channel list refreshed 3 h ago · Guide: next 26 h",
            ).joinToString("\n"),
            block,
        )
        val idle = ProviderDashboard.providerBlock(
            "Backup", isPrimary = false, reachable = false, responseMs = null, error = "timeout",
            feeds = ProviderDashboard.summarize(emptyList(), now), catalogRefreshedAt = 0L, guideEnd = null, nowMs = now,
        )
        assertTrue(idle, idle.contains("✗ Not answering: timeout"))
        assertTrue(idle, idle.contains("No channels watched yet"))
        assertTrue(idle, idle.contains("Channel list never refreshed · Guide: none"))
    }

    @Test
    fun `a source line names the provider, quality, rating and recent trouble`() {
        Locale.setDefault(Locale.US)
        val stats = FeedStatsEntity(1, 5, "1111111111", 1_800L, 0f, 120f, now - 2 * hour, now)
        val good = XtreamRepository.RankedFeed(1, 5, "ESPN FHD", 92, "T-Rex", stats)
        assertEquals("T-Rex · FHD · Excellent · tunes in 1.8 s · failed 2 h ago", ChannelSourcesDialog.describe(good, now))
        val fresh = XtreamRepository.RankedFeed(-1, 9, "ESPN", FeedScore.UNKNOWN, "")
        assertEquals("Main provider · New", ChannelSourcesDialog.describe(fresh, now))
    }
}
