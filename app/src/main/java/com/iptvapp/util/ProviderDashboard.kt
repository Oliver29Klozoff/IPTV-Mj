package com.iptvapp.util

import com.iptvapp.data.local.entities.FeedStatsEntity

/**
 * Words and numbers for Provider Health and the All Sources list, built from feed scores.
 * Plain data in and text out, so it can be tested without a screen.
 */
object ProviderDashboard {

    /** One provider's channels, summed over every copy that has been watched. */
    data class FeedSummary(
        val channelsTracked: Int,
        /** Share of starts that played, or null with no starts yet. */
        val startSuccessPercent: Int?,
        val avgTuneMs: Long?,
        /** Weighted by watching time, or null with no watching yet. */
        val stallsPerHour: Double?,
        val failuresLast24h: Int,
    )

    fun summarize(rows: List<FeedStatsEntity>, nowMs: Long): FeedSummary {
        val starts = rows.sumOf { it.outcomes.length }
        val played = rows.sumOf { r -> r.outcomes.count { it == '1' } }
        val tunes = rows.map { it.tuneMs }.filter { it > 0L }
        val minutes = rows.sumOf { it.watchedMinutes.toDouble() }
        val stallMinutes = rows.sumOf { it.stallsPerHour.toDouble() * it.watchedMinutes }
        return FeedSummary(
            channelsTracked = rows.count { it.outcomes.isNotEmpty() || it.watchedMinutes > 0f },
            startSuccessPercent = if (starts == 0) null else played * 100 / starts,
            avgTuneMs = if (tunes.isEmpty()) null else tunes.average().toLong(),
            stallsPerHour = if (minutes <= 0.0) null else stallMinutes / minutes,
            failuresLast24h = rows.count { it.lastFailureAt > 0L && nowMs - it.lastFailureAt < DAY_MS },
        )
    }

    /** Words for a [FeedScore]. A copy never played is New, not Fair: there is nothing to judge. */
    fun scoreLabel(score: Int, hasHistory: Boolean): String = when {
        !hasHistory -> "New"
        score >= 85 -> "Excellent"
        score >= 70 -> "Good"
        score >= 50 -> "Fair"
        else -> "Poor"
    }

    private val QUALITY = listOf(
        Regex("""\b(4k|uhd|2160p?)\b""", RegexOption.IGNORE_CASE) to "4K",
        Regex("""\b(fhd|1080[pi]?)\b""", RegexOption.IGNORE_CASE) to "FHD",
        Regex("""\b(hd|720p?)\b""", RegexOption.IGNORE_CASE) to "HD",
        Regex("""\b(sd|480p?|576p?)\b""", RegexOption.IGNORE_CASE) to "SD",
    )

    /** The quality a provider writes into the channel name, like "ESPN FHD", or null. */
    fun qualityOf(channelName: String): String? = QUALITY.firstOrNull { it.first.containsMatchIn(channelName) }?.second

    fun tuneText(ms: Long): String = if (ms < 10_000L) "%.1f s".format(ms / 1000.0) else "${ms / 1000} s"

    /** "just now", "12 min ago", "3 h ago", "2 days ago". Null for never. */
    fun agoText(thenMs: Long, nowMs: Long): String? {
        if (thenMs <= 0L) return null
        val mins = ((nowMs - thenMs).coerceAtLeast(0L)) / 60_000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }

    /**
     * How far ahead the guide runs from [guideEnd] (seconds or milliseconds, as a panel sends it).
     * "Guide: none" with no programmes, "Guide: ended 5 h ago" when it has run out.
     */
    fun guideText(guideEnd: Long?, nowMs: Long): String {
        val endMs = guideEnd?.takeIf { it > 0L }?.let { if (it < 100_000_000_000L) it * 1000L else it }
            ?: return "Guide: none"
        if (endMs <= nowMs) return "Guide: ended ${agoText(endMs, nowMs)}"
        val hours = (endMs - nowMs) / 3_600_000L
        return if (hours < 48) "Guide: next $hours h" else "Guide: next ${hours / 24} days"
    }

    /**
     * One provider's block in Provider Health. [reachable] null is an M3U playlist, which has
     * no login to check.
     */
    fun providerBlock(
        name: String,
        isPrimary: Boolean,
        reachable: Boolean?,
        responseMs: Long?,
        error: String?,
        feeds: FeedSummary,
        catalogRefreshedAt: Long,
        guideEnd: Long?,
        nowMs: Long,
    ): String {
        val lines = mutableListOf(if (isPrimary) "$name (main)" else name)
        lines += when (reachable) {
            null -> "Playlist, no login to check"
            true -> "● Online" + (responseMs?.let { " · answers in $it ms" } ?: "")
            false -> "✗ Not answering" + (error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
        lines += if (feeds.channelsTracked == 0) "No channels watched yet" else listOfNotNull(
            feeds.startSuccessPercent?.let { "starts $it%" },
            feeds.avgTuneMs?.let { "tunes in ${tuneText(it)}" },
            feeds.stallsPerHour?.let { "%.1f stalls an hour".format(it) },
            "${feeds.channelsTracked} channel${if (feeds.channelsTracked == 1) "" else "s"} watched",
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
        lines += when (feeds.failuresLast24h) {
            0 -> "No failures in the last 24 h"
            1 -> "1 channel failed in the last 24 h"
            else -> "${feeds.failuresLast24h} channels failed in the last 24 h"
        }
        lines += "Channel list ${agoText(catalogRefreshedAt, nowMs)?.let { "refreshed $it" } ?: "never refreshed"} · ${guideText(guideEnd, nowMs)}"
        return lines.joinToString("\n")
    }

    private const val DAY_MS = 24 * 60 * 60_000L
}
