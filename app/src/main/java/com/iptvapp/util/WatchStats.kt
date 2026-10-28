package com.iptvapp.util

import com.iptvapp.data.local.entities.WatchLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watch stats (v7.75): what the watch_log adds up to. Pure, so the screen only draws it.
 * Channels are totalled by name across providers ("ESPN" on two providers is one channel), but a
 * channel's trouble is per provider copy, since that is what a viewer can switch away from.
 */
object WatchStats {

    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** The local date [back] calendar days before [nowMs]: whole days, so a DST change can't skip or repeat one. */
    fun daysBack(nowMs: Long, back: Int): String =
        day(java.util.Calendar.getInstance().apply { timeInMillis = nowMs; add(java.util.Calendar.DAY_OF_YEAR, -back) }.timeInMillis)

    /** Where a provider slot's history goes when the slot changes hands (WatchLogDao.retire): below
     * -1, and new each second, so two retirements never meet on a key. */
    fun retiredIndex(nowMs: Long): Int = -2 - ((nowMs / 1000) % 1_000_000_000L).toInt()

    data class ChannelTotal(
        val name: String,
        /** -1 the primary provider; null when the total spans providers. */
        val serverIndex: Int?,
        val minutes: Float,
        val stalls: Int,
        val starts: Int,
        val failures: Int,
    ) {
        val stallsPerHour: Float get() = if (minutes > 0f) stalls * 60f / minutes else 0f
    }

    data class Summary(
        /** The last 14 days, oldest first: day to minutes. */
        val days: List<Pair<String, Float>>,
        val thisWeekMinutes: Float,
        val lastWeekMinutes: Float,
        val monthMinutes: Float,
        val activeDays: Int,
        /** Most watched in 30 days, top 10. */
        val topChannels: List<ChannelTotal>,
        /** 30 days: copies that failed to start or dropped, or stalled often, worst first. */
        val trouble: List<ChannelTotal>,
    ) {
        val empty get() = monthMinutes <= 0f && trouble.isEmpty()
    }

    /** Thirty days of [rows] (fetch with [firstDay]) as of [nowMs]. */
    fun summarize(rows: List<WatchLogEntity>, nowMs: Long): Summary {
        val days = (13 downTo 0).map { daysBack(nowMs, it) }
        val byDay = rows.groupBy { it.day }.mapValues { (_, list) -> list.sumOf { it.minutes.toDouble() }.toFloat() }
        val week = days.takeLast(7).toSet()
        val lastWeek = days.take(7).toSet()
        val month = rows.filter { it.day >= firstDay(nowMs) }
        fun total(list: List<WatchLogEntity>, server: Int?) = ChannelTotal(
            list.first().name.ifBlank { "Channel ${list.first().streamId}" }, server,
            list.sumOf { it.minutes.toDouble() }.toFloat(), list.sumOf { it.stalls }, list.sumOf { it.starts }, list.sumOf { it.failures },
        )
        val top = month.filter { it.minutes > 0f }
            .groupBy { ChannelNameMatcher.normalize(it.name).ifBlank { "${it.serverIndex}:${it.streamId}" } }
            .values.map { list -> total(list, list.map { it.serverIndex }.distinct().singleOrNull()) }
            .sortedByDescending { it.minutes }.take(10)
        val trouble = month.groupBy { it.serverIndex to it.streamId }
            .values.map { total(it, it.first().serverIndex) }
            .filter { it.failures > 0 || it.minutes >= 10f && it.stallsPerHour >= 2f }
            .sortedByDescending { it.failures * 2f + it.stallsPerHour }.take(10)
        return Summary(
            days = days.map { it to (byDay[it] ?: 0f) },
            thisWeekMinutes = rows.filter { it.day in week }.sumOf { it.minutes.toDouble() }.toFloat(),
            lastWeekMinutes = rows.filter { it.day in lastWeek }.sumOf { it.minutes.toDouble() }.toFloat(),
            monthMinutes = month.sumOf { it.minutes.toDouble() }.toFloat(),
            activeDays = month.filter { it.minutes > 0f }.map { it.day }.distinct().size,
            topChannels = top,
            trouble = trouble,
        )
    }

    /** The first day the summary reads: 30 days back, today included. */
    fun firstDay(nowMs: Long) = daysBack(nowMs, 29)

    /** "3 h 20 min", "45 min", "0 min". */
    fun duration(minutes: Float): String {
        val m = minutes.toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
