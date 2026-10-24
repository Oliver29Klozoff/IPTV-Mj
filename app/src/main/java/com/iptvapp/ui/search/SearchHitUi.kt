package com.iptvapp.ui.search

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.R
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.search.SearchHit
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How a [SearchHit] reads, and what picking one does — shared by search (SearchActivity) and
 * the Smart Home rows, so a card and a search result behave the same.
 *
 * Live picks differ by screen: search hands a channel back to the home screen that opened it,
 * a home screen plays it itself — hence [playChannel] and [playReplay].
 */
class SearchHitUi(
    private val activity: AppCompatActivity,
    private val repository: XtreamRepository,
    /** Play a live channel: [ChannelEntity] on the main provider, or [MergedChannelEntity]. */
    private val playChannel: (ChannelEntity?, MergedChannelEntity?) -> Unit,
    /** Play a replay of a past programme (main provider; see SearchHit.Kind.CATCH_UP). */
    private val playReplay: (Replay) -> Unit
) {
    /**
     * A replay ready to play: [url] is the provider's timeshift URL for it. [serverIndex] -1 is
     * the main provider; otherwise [streamId] is that provider's own id (a merged channel).
     */
    data class Replay(
        val serverIndex: Int,
        val streamId: Int,
        val channelName: String,
        val url: String,
        val title: String,
        val startSec: Long,
        val durationMin: Int,
    )

    fun open(hit: SearchHit, launcherLink: String? = null) {
        when (hit) {
            is SearchHit.Channel -> playChannel(hit.channel, hit.merged)
            is SearchHit.Program -> when (hit.kind) {
                SearchHit.Kind.ON_NOW -> playChannel(hit.channel, hit.merged)
                SearchHit.Kind.CATCH_UP -> openReplay(hit)
                else -> offerUpcoming(hit)
            }
            is SearchHit.Movie -> openMovie(hit, launcherLink)
            is SearchHit.Series -> openSeries(hit, null)
            is SearchHit.Episode -> openSeries(hit.series, hit, launcherLink)
            is SearchHit.Recording -> openRecording(hit)
        }
    }

    private fun openReplay(hit: SearchHit.Program) {
        // Main provider or another one with an archive: each replays from its own panel.
        val server = hit.merged?.serverIndex ?: -1
        val id = hit.channel?.streamId ?: hit.merged?.streamId ?: return
        val name = hit.channelName
        activity.lifecycleScope.launch {
            val startSec = hit.startMs / 1000L
            val durationMin = ((hit.stopMs - hit.startMs) / 60_000L).toInt().coerceAtLeast(1)
            val url = try { repository.getTimeshiftUrlFor(server, id, startSec, durationMin) } catch (_: Exception) {
                Toast.makeText(activity, R.string.search_replay_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            playReplay(Replay(server, id, name, url, "$name — ${hit.programTitle}", startSec, durationMin))
        }
    }

    // Upcoming: a reminder (and a recording, for the main provider, as the guide offers), or the
    // channel now.
    private fun offerUpcoming(hit: SearchHit.Program) {
        val time = formatWhen(activity, hit.startMs, past = false)
        val builder = AlertDialog.Builder(activity)
            .setTitle("\"${hit.programTitle}\"")
            .setMessage("${hit.channelName} · $time")
            .setNegativeButton(R.string.search_watch_channel_now) { _, _ -> playChannel(hit.channel, hit.merged) }
            .setNeutralButton(R.string.search_remind_me) { _, _ ->
                val merged = hit.merged
                if (merged != null) {
                    com.iptvapp.ui.guide.ChannelTimerScheduler.schedule(activity, merged.streamId, merged.name, hit.programTitle, hit.startMs, merged.serverIndex)
                } else {
                    val ch = hit.channel ?: return@setNeutralButton
                    com.iptvapp.ui.guide.ChannelTimerScheduler.schedule(activity, ch.streamId, ch.name, hit.programTitle, hit.startMs)
                }
                Toast.makeText(activity, activity.getString(R.string.search_reminder_set, time), Toast.LENGTH_SHORT).show()
            }
        val ch = hit.channel
        if (ch != null) {
            builder.setPositiveButton(R.string.search_record) { _, _ ->
                activity.startActivity(
                    Intent(activity, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, ch.streamId)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, hit.startMs)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, (hit.stopMs - hit.startMs).coerceAtLeast(60_000L))
                )
            }
        }
        builder.show()
    }

    private fun openMovie(hit: SearchHit.Movie, launcherLink: String? = null) {
        val vod = hit.vod
        val merged = hit.merged
        if (vod != null) {
            activity.startActivity(Intent(activity, com.iptvapp.ui.vod.VodDetailActivity::class.java).apply {
                putExtra("vod_stream_id", vod.streamId)
                putExtra("launcher_link", launcherLink)
                putExtra("vod_name", vod.name)
                putExtra("vod_container_extension", vod.containerExtension)
                putExtra("vod_cover", vod.streamIcon)
                putExtra("vod_rating", vod.rating)
            })
        } else if (merged != null) {
            activity.startActivity(Intent(activity, com.iptvapp.ui.vod.MergedVodDetailActivity::class.java).apply {
                putExtra("server_index", merged.serverIndex)
                putExtra("vod_stream_id", merged.streamId)
                putExtra("vod_name", merged.name)
                putExtra("vod_container_extension", merged.containerExtension)
                putExtra("vod_cover", merged.streamIcon)
                putExtra("vod_rating", merged.rating)
                putExtra("vod_is_favorite", merged.isFavorite)
                putExtra("server_nickname", merged.serverNickname)
            })
        }
    }

    private fun openSeries(hit: SearchHit.Series, episode: SearchHit.Episode?, launcherLink: String? = null) {
        val series = hit.series
        val merged = hit.merged
        activity.startActivity(Intent(activity, com.iptvapp.ui.series.SeriesDetailActivity::class.java).apply {
            putExtra("series_id", series?.seriesId ?: merged?.seriesId ?: return)
            putExtra("launcher_link", launcherLink)
            putExtra("series_name", series?.name ?: merged?.name)
            putExtra("series_cover", series?.cover ?: merged?.cover)
            putExtra("series_genre", series?.genre ?: merged?.genre)
            putExtra("series_rating", series?.rating ?: merged?.rating)
            putExtra("series_plot", series?.plot ?: merged?.plot)
            if (merged != null) putExtra("server_index", merged.serverIndex)
            if (episode != null) {
                putExtra(com.iptvapp.ui.series.SeriesDetailActivity.EXTRA_FOCUS_SEASON, episode.season)
                putExtra(com.iptvapp.ui.series.SeriesDetailActivity.EXTRA_FOCUS_EPISODE, episode.episode)
            }
        })
    }

    private fun openRecording(hit: SearchHit.Recording) {
        val rec = hit.recording
        when (rec.status) {
            "DONE" -> com.iptvapp.util.RecordingFileUtils.playInApp(activity, rec)
            "RECORDING" -> Toast.makeText(activity, R.string.search_recording_now, Toast.LENGTH_SHORT).show()
            "SCHEDULED" -> Toast.makeText(activity, activity.getString(R.string.search_recording_scheduled, formatWhen(activity, rec.scheduledStartMs, past = false)), Toast.LENGTH_SHORT).show()
            "FAILED" -> Toast.makeText(activity, rec.failureReason ?: activity.getString(R.string.search_recording_failed), Toast.LENGTH_LONG).show()
            // COMPRESSING, or any later in-progress state: not playable yet, not failed.
            else -> Toast.makeText(activity, R.string.search_recording_finishing, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** "8:00 PM" today, "Tue 8:00 PM" within a week, else "Oct 21 8:00 PM". */
        fun formatWhen(context: Context, ms: Long, past: Boolean): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            val withinWeek = kotlin.math.abs(ms - now.timeInMillis) < 6L * 86_400_000L
            val pattern = when {
                sameDay -> "h:mm a"
                withinWeek -> "EEE h:mm a"
                else -> "MMM d h:mm a"
            }
            val text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
            return if (sameDay && past) context.getString(R.string.search_today_at, text) else text
        }

        /** The line under a hit's title: where and when, and its provider. */
        fun detailOf(context: Context, hit: SearchHit): String {
            val parts = mutableListOf<String>()
            when (hit) {
                is SearchHit.Channel -> {}
                is SearchHit.Program -> {
                    parts += hit.channelName
                    parts += when (hit.kind) {
                        SearchHit.Kind.ON_NOW -> context.getString(R.string.search_until, formatWhen(context, hit.stopMs, past = false))
                        SearchHit.Kind.CATCH_UP -> context.getString(R.string.search_aired, formatWhen(context, hit.startMs, past = true))
                        else -> formatWhen(context, hit.startMs, past = false)
                    }
                }
                is SearchHit.Movie -> (hit.vod?.rating ?: hit.merged?.rating)?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
                is SearchHit.Series -> (hit.series?.rating ?: hit.merged?.rating)?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
                is SearchHit.Episode -> {
                    parts += hit.series.title
                    parts += "S${hit.season}E${hit.episode}"
                }
                is SearchHit.Recording -> {
                    parts += hit.recording.channelName
                    parts += formatWhen(context, hit.recording.scheduledStartMs, past = true)
                    if (hit.recording.status != "DONE") parts += hit.recording.status.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
            hit.provider?.let { parts += it }
            // Which copy of a channel this is, and how it has played: the same words as All Sources.
            val (channelName, feed) = when (hit) {
                is SearchHit.Channel -> hit.title to hit.feed
                is SearchHit.Program -> hit.channelName to hit.feed
                else -> null to null
            }
            if (channelName != null) {
                com.iptvapp.util.ProviderDashboard.qualityOf(channelName)?.let { parts += it }
                feed?.takeIf { it.rated }?.let { parts += com.iptvapp.util.ProviderDashboard.scoreLabel(it.score, true) }
            }
            return parts.joinToString(" · ")
        }

        fun imageOf(hit: SearchHit): String? = when (hit) {
            is SearchHit.Channel -> hit.icon
            is SearchHit.Program -> hit.icon
            is SearchHit.Movie -> hit.poster
            is SearchHit.Series -> hit.cover
            is SearchHit.Episode -> hit.series.cover
            is SearchHit.Recording -> null
        }
    }
}
