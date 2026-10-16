package com.iptvapp.ui.player

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.util.ProviderDashboard
import kotlinx.coroutines.launch

/**
 * "All sources": every copy of one channel across the user's providers, best first, each with
 * its provider, the quality in its name, its rating from feed scores and how fast it tunes.
 * Picking one plays that exact copy. Shared by every long-press channel menu, phone and TV.
 */
object ChannelSourcesDialog {

    fun show(activity: AppCompatActivity, repository: XtreamRepository, channelName: String) {
        activity.lifecycleScope.launch {
            val sources = try { repository.channelSources(channelName) } catch (_: Exception) { emptyList() }
            if (sources.isEmpty()) {
                Toast.makeText(activity, "No copies of \"$channelName\" found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val lines = sources.map { describe(it, now) }
            AlertDialog.Builder(activity)
                .setTitle("All sources: $channelName")
                .setItems(lines.toTypedArray()) { _, which -> play(activity, repository, sources[which]) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    /** "T-Rex · FHD · Excellent · tunes in 1.8 s · failed 2 h ago" */
    internal fun describe(feed: XtreamRepository.RankedFeed, nowMs: Long): String {
        val stats = feed.stats
        val played = stats != null && (stats.outcomes.isNotEmpty() || stats.watchedMinutes > 0f)
        val parts = mutableListOf(feed.providerName.ifBlank { if (feed.serverIndex == -1) "Main provider" else "Provider ${feed.serverIndex + 1}" })
        ProviderDashboard.qualityOf(feed.name)?.let { parts += it }
        parts += ProviderDashboard.scoreLabel(feed.score, played)
        if (stats != null && stats.tuneMs > 0L) parts += "tunes in ${ProviderDashboard.tuneText(stats.tuneMs)}"
        if (stats != null && stats.lastFailureAt > 0L && nowMs - stats.lastFailureAt < 24 * 60 * 60_000L) {
            ProviderDashboard.agoText(stats.lastFailureAt, nowMs)?.let { parts += "failed $it" }
        }
        return parts.joinToString(" · ")
    }

    private fun play(activity: AppCompatActivity, repository: XtreamRepository, feed: XtreamRepository.RankedFeed) {
        activity.lifecycleScope.launch {
            val url = try {
                if (feed.serverIndex == -1) repository.getLiveStreamUrl(feed.streamId)
                else repository.getMergedLiveStreamUrl(feed.serverIndex, feed.streamId)
            } catch (_: Exception) {
                Toast.makeText(activity, "Couldn't load this copy", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // The same extras Mosaic uses to open a primary or an extra provider's channel.
            activity.startActivity(Intent(activity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                if (feed.serverIndex == -1) {
                    putExtra("stream_title", feed.name)
                    putExtra("stream_id", feed.streamId)
                } else {
                    putExtra("stream_title", if (feed.providerName.isBlank()) feed.name else "${feed.name} · ${feed.providerName}")
                    putExtra("stream_id", -1)
                    putExtra("server_index", feed.serverIndex)
                    putExtra("merged_stream_id", feed.streamId)
                }
            })
        }
    }
}
