package com.iptvapp.util

import android.content.Context
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A per-channel reliability score plus recent stall/reconnect counts pulled straight from the
 * on-device crash/playback log — built after a long debugging session where a provider's
 * PlaylistStuckException-driven reconnect loop took hours of manual log-pulling to pin down.
 * Surfacing this in Settings turns that into a glance instead of a repeat investigation.
 */
object ProviderHealth {

    data class ChannelScore(val streamId: Int, val name: String, val reliabilityPercent: Int)

    data class Report(
        val serverLabel: String,
        val trackedChannelCount: Int,
        val avgReliabilityPercent: Int?,
        val worstChannels: List<ChannelScore>,
        val stallsLast24h: Int,
        val retriesLast24h: Int
    )

    suspend fun build(context: Context, db: IptvDatabase, prefs: PreferencesManager): Report {
        val creds = prefs.credentials.first()
        val nickname = prefs.serverNickname.first().ifEmpty { creds.username }
        val label = if (nickname.isNotBlank()) "$nickname — ${creds.serverUrl}" else creds.serverUrl

        // Paged, not a single SELECT * — see ReliabilityDao.getPage's kdoc for why (crashed
        // outright on a low-RAM device with a large catalog).
        val allReliability = mutableListOf<com.iptvapp.data.local.entities.ChannelReliabilityEntity>()
        var offset = 0
        val pageSize = 2000
        while (true) {
            val page = db.reliabilityDao().getPage(pageSize, offset)
            allReliability.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        val reliability = allReliability.filter { it.outcomes.isNotEmpty() }
        val percentages = reliability.map { entity ->
            entity to (entity.outcomes.count { it == '1' } * 100 / entity.outcomes.length)
        }
        val avg = if (percentages.isNotEmpty()) percentages.map { it.second }.average().toInt() else null
        val worst = percentages
            .sortedBy { it.second }
            .take(5)
            .mapNotNull { (entity, pct) ->
                db.channelDao().getChannelById(entity.streamId)?.name?.let { ChannelScore(entity.streamId, it, pct) }
            }

        val (stalls, retries) = countRecentEvents(context)

        return Report(
            serverLabel = label,
            trackedChannelCount = reliability.size,
            avgReliabilityPercent = avg,
            worstChannels = worst,
            stallsLast24h = stalls,
            retriesLast24h = retries
        )
    }

    private fun countRecentEvents(context: Context): Pair<Int, Int> {
        val logFile = File(context.filesDir, "crash_log.txt")
        if (!logFile.exists()) return 0 to 0
        val text = try { logFile.readText() } catch (_: Exception) { return 0 to 0 }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val tsRegex = Regex("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})]")
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        var errorCount = 0
        var retryCount = 0
        for (line in text.lineSequence()) {
            val match = tsRegex.find(line) ?: continue
            val ts = try { dateFmt.parse(match.groupValues[1])?.time } catch (_: Exception) { null } ?: continue
            if (ts < cutoff) continue
            if ("PLAYER ERROR" in line) errorCount++
            if ("RETRY SCHEDULED" in line) retryCount++
        }
        return errorCount to retryCount
    }

    /** One cell of the 24-hour weather-map strip. null percent = no samples yet for that hour
     * (rendered grey by the caller) — distinct from a 0% failure rate (rendered green). */
    data class HourCell(val hourOfDay: Int, val failurePercent: Int?, val sampleCount: Int)

    data class WeatherMapProvider(val serverIndex: Int, val label: String, val hours: List<HourCell>)

    /** Builds the 24-cell-per-provider weather map for every provider with at least one recorded
     * sample — primary (serverIndex -1) and every configured extra server, matching the same
     * label-resolution [ProviderHealth.build] and showDataUsageDialog already use. */
    suspend fun buildWeatherMap(db: IptvDatabase, prefs: PreferencesManager): List<WeatherMapProvider> {
        val tracked = db.providerHourlyStatsDao().getTrackedServerIndexes()
        if (tracked.isEmpty()) return emptyList()
        val primaryNick = prefs.serverNickname.first().ifBlank { "Primary" }
        val extraNicks = prefs.getExtraServersWithNick().map { it.getOrElse(3) { "" } }
        return tracked.sorted().map { serverIndex ->
            val label = if (serverIndex == -1) primaryNick
                else extraNicks.getOrNull(serverIndex).takeUnless { it.isNullOrBlank() } ?: "Provider ${serverIndex + 1}"
            val rows = db.providerHourlyStatsDao().getForProvider(serverIndex).associateBy { it.hourOfDay }
            val hours = (0..23).map { hour ->
                val row = rows[hour]
                val pct = if (row == null || row.sampleCount == 0) null else (row.eventCount * 100 / row.sampleCount)
                HourCell(hour, pct, row?.sampleCount ?: 0)
            }
            WeatherMapProvider(serverIndex, label, hours)
        }
    }

    /** Builds the scrollable weather-map dialog body: one labeled row per provider, each a
     * horizontal strip of 24 small colored cells (grey = no data, green = reliable, shading to
     * red = frequent errors at that hour). Plain View/TextView cells rather than a charting
     * library or custom-drawn View, per the feature's own "keep the rendering simple" design
     * note. Shared between phone SettingsActivity and TvSettingsActivity so the two don't
     * duplicate this view-building code. */
    fun buildWeatherMapView(context: Context, providers: List<WeatherMapProvider>): android.view.View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        providers.forEach { provider ->
            val label = android.widget.TextView(context).apply {
                text = provider.label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setPadding(0, dp(10), 0, dp(4))
            }
            root.addView(label)
            val strip = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            provider.hours.forEach { cell ->
                val color = when {
                    cell.sampleCount == 0 -> android.graphics.Color.parseColor("#3A3A3A")
                    cell.failurePercent == null || cell.failurePercent!! <= 10 -> android.graphics.Color.parseColor("#00CC66")
                    cell.failurePercent!! <= 40 -> android.graphics.Color.parseColor("#FFAA00")
                    else -> android.graphics.Color.parseColor("#FF4444")
                }
                val dot = android.view.View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(18), 1f).apply {
                        marginEnd = dp(1)
                    }
                    setBackgroundColor(color)
                    contentDescription = if (cell.sampleCount == 0) "${cell.hourOfDay}:00 — no data"
                        else "${cell.hourOfDay}:00 — ${cell.failurePercent}% error rate (${cell.sampleCount} samples)"
                }
                strip.addView(dot)
            }
            root.addView(strip)
            val hourLabels = android.widget.TextView(context).apply {
                text = "12am" + " ".repeat(40) + "12pm" + " ".repeat(40) + "11pm"
                setTextColor(android.graphics.Color.parseColor("#777777"))
                textSize = 9f
                setPadding(0, dp(2), 0, 0)
            }
            root.addView(hourLabels)
        }
        return root
    }

    fun formatReport(report: Report): String {
        val sb = StringBuilder()
        sb.append("Server: ${report.serverLabel}\n\n")
        sb.append("Tracked channels: ${report.trackedChannelCount}\n")
        sb.append("Avg reliability: ${report.avgReliabilityPercent?.let { "$it%" } ?: "Not enough data yet"}\n\n")
        sb.append("Last 24h:\n")
        sb.append("  Playback errors: ${report.stallsLast24h}\n")
        sb.append("  Auto-retries: ${report.retriesLast24h}\n")
        if (report.stallsLast24h == 0) {
            sb.append("  ✓ No playback errors in the last day\n")
        }
        return sb.toString()
    }
}
