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

    data class ChannelScore(val name: String, val reliabilityPercent: Int)

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

        val reliability = db.reliabilityDao().getAll().filter { it.outcomes.isNotEmpty() }
        val percentages = reliability.map { entity ->
            entity to (entity.outcomes.count { it == '1' } * 100 / entity.outcomes.length)
        }
        val avg = if (percentages.isNotEmpty()) percentages.map { it.second }.average().toInt() else null
        val worst = percentages
            .sortedBy { it.second }
            .take(5)
            .mapNotNull { (entity, pct) -> db.channelDao().getChannelById(entity.streamId)?.name?.let { ChannelScore(it, pct) } }

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
        if (report.worstChannels.isNotEmpty()) {
            sb.append("\nLeast reliable channels:\n")
            report.worstChannels.forEach { sb.append("  ${it.name} — ${it.reliabilityPercent}%\n") }
        }
        return sb.toString()
    }
}
