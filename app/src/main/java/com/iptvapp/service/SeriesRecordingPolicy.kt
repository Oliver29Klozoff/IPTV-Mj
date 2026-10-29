package com.iptvapp.service

import com.iptvapp.data.local.entities.*
import java.security.MessageDigest
import java.util.Locale

/** Pure decisions; titles select uncertain candidates, never establish episode identity. */
object SeriesRecordingPolicy {
    fun ms(value: Long) = if (value < 100_000_000_000L) value * 1000 else value
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun title(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun matches(rule: SeriesRecordingRule, epg: EpgEntity): Boolean =
        rule.serverIndex == epg.serverIndex && rule.streamId == epg.streamId &&
            (if (rule.seriesKey != null) rule.seriesKey == epg.seriesKey else title(rule.title) == title(epg.title))
    fun reliable(epg: EpgEntity): Boolean = !epg.seriesKey.isNullOrBlank() &&
        (!epg.episodeKey.isNullOrBlank() || (epg.seasonNumber != null && epg.episodeNumber != null))
    fun identity(providerKey: String, epg: EpgEntity): String {
        val episode = if (reliable(epg)) "${epg.seriesKey}|${epg.episodeKey ?: "${epg.seasonNumber}:${epg.episodeNumber}"}"
            else "uncertain|${epg.streamId}|${epg.id}|${ms(epg.startTimestamp)}"
        return hash("$providerKey|$episode")
    }
    fun uncertainty(rule: SeriesRecordingRule, epg: EpgEntity): String? = when {
        !reliable(epg) -> "Episode identity is uncertain; confirm this airing"
        rule.mode == "NEW" && epg.isNewEpisode == null -> "New/rerun status is unknown; confirm this airing"
        else -> null
    }
    fun eligible(rule: SeriesRecordingRule, epg: EpgEntity) = !rule.paused && !rule.cancelled &&
        matches(rule, epg) && !(rule.mode == "NEW" && epg.isNewEpisode == false)
    fun window(rule: SeriesRecordingRule, epg: EpgEntity): Pair<Long, Long> =
        (ms(epg.startTimestamp) - rule.startPaddingMinutes.coerceIn(0, 15) * 60_000L) to
            (ms(epg.stopTimestamp) + rule.endPaddingMinutes.coerceIn(0, 60) * 60_000L)
    fun overlaps(a: Long, b: Long, c: Long, d: Long) = a < d && c < b
    fun capacity(maximum: Int?, active: Int?, ownActive: Int = 0): Int =
        if (maximum == null || maximum <= 0 || active == null) 1
        else (maximum - (active - ownActive).coerceAtLeast(0)).coerceAtLeast(0)
    // RecordingService runs captures side by side and only copies the compressed stream (no
    // decoding), so the device cap is generous; the provider's connection count is the real limit.
    const val DEVICE_CAPTURE_LIMIT = 3
    fun conflict(start: Long, end: Long, server: Int, existing: List<RecordingEntity>, providerSlots: Int): List<RecordingEntity> {
        val overlapping = existing.filter { it.status in setOf("SCHEDULED", "RECORDING") && overlaps(start, end, it.scheduledStartMs, it.scheduledStartMs + it.durationMs) }
        return if (peak(start, end, overlapping) >= DEVICE_CAPTURE_LIMIT ||
            peak(start, end, overlapping.filter { it.serverIndex == server }) >= providerSlots) overlapping else emptyList()
    }

    /** Most of [recordings] running at one moment inside start..end (back-to-back ones don't stack). */
    fun peak(start: Long, end: Long, recordings: List<RecordingEntity>): Int {
        val edges = recordings.flatMap {
            listOf(maxOf(start, it.scheduledStartMs) to 1, minOf(end, it.scheduledStartMs + it.durationMs) to -1)
        }.sortedWith(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        var running = 0
        var most = 0
        for ((_, step) in edges) { running += step; most = maxOf(most, running) }
        return most
    }
    fun requiredBytes(durationMs: Long): Long = durationMs.coerceAtLeast(0) * 1_000L + 512L * 1024 * 1024 // 8 Mbps estimate + reserve
    fun retention(records: List<RecordingEntity>, keep: Int): List<RecordingEntity> =
        if (keep !in setOf(3, 5, 10)) emptyList() else records.filter { it.status == "DONE" }
            .sortedByDescending { it.scheduledStartMs }.drop(keep).filter { !it.isProtected }

    /**
     * A guide with season and episode numbers but no series id (most XMLTV feeds): the show is the
     * title on that channel, and the numbers say which episode. Never used without both numbers.
     */
    fun numberedSeries(title: String, season: Int?, episode: Int?): String? =
        if (season != null && episode != null && title.isNotBlank()) "title:" + title(title) else null

    data class EpisodeNumbers(val series: String? = null, val episode: String? = null, val season: Int? = null, val number: Int? = null)
    fun xmlNumbers(system: String, raw: String): EpisodeNumbers {
        val value = raw.trim()
        if (system == "dd_progid" && Regex("EP[0-9]{8}\\.[0-9]{4}").matches(value))
            return EpisodeNumbers("dd:${value.substringBefore('.')}", "dd:$value")
        if (system == "xmltv_ns") {
            val parts = value.split('.')
            fun number(index: Int) = parts.getOrNull(index)?.trim()?.substringBefore('/')?.toIntOrNull()?.takeIf { it in 0..100_000 }?.plus(1)
            return EpisodeNumbers(season = number(0), number = number(1))
        }
        if (system == "thetvdb.com" && Regex("series/[0-9]+").matches(value)) return EpisodeNumbers(series = "tvdb:$value")
        if (system == "thetvdb.com" && Regex("episode/[0-9]+").matches(value)) return EpisodeNumbers(episode = "tvdb:$value")
        if (system == "onscreen") Regex("(?i)^S(\\d{1,4})\\s*E(\\d{1,5})$").find(value)?.let {
            return EpisodeNumbers(season = it.groupValues[1].toInt(), number = it.groupValues[2].toInt())
        }
        return EpisodeNumbers()
    }
}
