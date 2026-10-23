package com.iptvapp.ui.player

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.smart.SmartChannelStore
import com.iptvapp.ui.smart.DialogFocus
import com.iptvapp.util.ProviderDashboard
import com.iptvapp.util.SmartChannels
import kotlinx.coroutines.launch

/**
 * "All sources": every copy of one channel across the user's providers, best first, each with
 * its provider, the quality in its name, its rating from feed scores and how fast it tunes.
 * Picking one plays that exact copy through the host's own [open], which releases the Home
 * preview stream first (single-connection accounts) and honours an external player choice.
 * Shared by every long-press channel menu, phone and TV.
 */
object ChannelSourcesDialog {

    /** Arguments are openPlayer's: url, title, primary stream id or -1, serverIndex, mergedStreamId. */
    fun interface Open {
        fun open(url: String, title: String, streamId: Int, serverIndex: Int, mergedStreamId: Int)
    }

    fun show(
        activity: AppCompatActivity,
        repository: XtreamRepository,
        channelName: String,
        smart: SmartChannelStore? = null,
        anchor: View? = null,
        selectedServer: Int? = null,
        selectedStream: Int? = null,
        open: Open,
    ) {
        activity.lifecycleScope.launch {
            val sources = try {
                repository.channelSources(channelName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            val group = smartGroup(repository, smart, selectedServer, selectedStream)
            val rows = if (group == null) sources.map { SourceRow(it, null, false) } else groupRows(repository, group, sources)
            if (rows.isEmpty()) {
                Toast.makeText(activity, "No copies of \"$channelName\" found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val lines = rows.map { row ->
                val line = describe(row.feed, now)
                if (row.preferred) "$line · Preferred" else line
            }
            val title = if (group == null) "All sources: $channelName" else "Sources · ${rows.size}"
            val builder = AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(lines.toTypedArray()) { _, which ->
                    sourceActions(activity, repository, smart, group?.id, rows[which], anchor, open)
                }
                .setNegativeButton("Close", null)
            if (group != null && smart != null) {
                val backup = if (group.failoverEnabled) "Backup on" else "Backup off"
                builder.setNeutralButton(backup) { _, _ ->
                    activity.lifecycleScope.launch {
                        smart.setFailover(group.id, !group.failoverEnabled)
                    }
                }
            }
            builder.setOnDismissListener { DialogFocus.restore(anchor) }
            builder.show()
        }
    }

    private data class SourceRow(val feed: XtreamRepository.RankedFeed, val groupId: String?, val preferred: Boolean)

    private suspend fun smartGroup(
        repository: XtreamRepository,
        smart: SmartChannelStore?,
        selectedServer: Int?,
        selectedStream: Int?,
    ): SmartChannels.PlaybackGroup? {
        if (smart == null || selectedServer == null || selectedStream == null) return null
        val key = SmartChannels.Key(selectedServer, selectedStream)
        val live = repository.smartSource(selectedServer, selectedStream)
        return SmartChannels.playbackGroupFor(smart.playbackGroups(), key, live)
    }

    private suspend fun groupRows(
        repository: XtreamRepository,
        group: SmartChannels.PlaybackGroup,
        named: List<XtreamRepository.RankedFeed>,
    ): List<SourceRow> {
        val now = System.currentTimeMillis()
        val out = ArrayList<SourceRow>()
        for (member in group.members) {
            val live = repository.smartSource(member.key.serverIndex, member.key.streamId) ?: continue
            if (SmartChannels.isStale(member.epgChannelId, member.nameFingerprint, live)) continue
            val known = named.firstOrNull { it.serverIndex == member.key.serverIndex && it.streamId == member.key.streamId }
            val feed = known ?: XtreamRepository.RankedFeed(
                member.key.serverIndex,
                member.key.streamId,
                live.name,
                SmartChannelStore.UNMEASURED_SCORE,
                if (member.key.serverIndex == -1) "Main provider" else "Provider ${member.key.serverIndex + 1}",
                null,
            )
            out += SourceRow(feed, group.id, group.hasPreferred && group.preferred == member.key)
        }
        return out
    }

    private fun sourceActions(
        activity: AppCompatActivity,
        repository: XtreamRepository,
        smart: SmartChannelStore?,
        groupId: String?,
        row: SourceRow,
        anchor: View?,
        open: Open,
    ) {
        val actions = SmartChannels.sourceActions()
        AlertDialog.Builder(activity)
            .setTitle(row.feed.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Play this source" -> play(activity, repository, row.feed, open)
                    "Set as preferred" -> {
                        if (smart == null || groupId == null) {
                            Toast.makeText(activity, "This channel is not in a Smart Channel group", Toast.LENGTH_SHORT).show()
                        } else {
                            activity.lifecycleScope.launch {
                                smart.setPreferred(groupId, row.feed.serverIndex, row.feed.streamId)
                                Toast.makeText(activity, "Preferred source saved", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "Automatic backup" -> {
                        if (smart == null || groupId == null) {
                            Toast.makeText(activity, "Automatic backup applies to a verified group", Toast.LENGTH_SHORT).show()
                        } else {
                            activity.lifecycleScope.launch {
                                val enabled = smart.playbackGroups().firstOrNull { it.id == groupId }?.failoverEnabled != true
                                smart.setFailover(groupId, enabled)
                                Toast.makeText(activity, if (enabled) "Backup on for this group" else "Backup off for this group", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    else -> {
                        val measured = if (row.feed.stats == null) {
                            "Not measured yet. A missing measurement is not a failure."
                        } else {
                            describe(row.feed, System.currentTimeMillis())
                        }
                        val quality = ProviderDashboard.qualityOf(row.feed.name)?.let { "Advertises $it. " }.orEmpty()
                        AlertDialog.Builder(activity)
                            .setTitle(row.feed.name)
                            .setMessage(quality + measured)
                            .setPositiveButton("Close", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { DialogFocus.restore(anchor) }
            .show()
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

    private fun play(activity: AppCompatActivity, repository: XtreamRepository, feed: XtreamRepository.RankedFeed, open: Open) {
        activity.lifecycleScope.launch {
            val url = try {
                if (feed.serverIndex == -1) repository.getLiveStreamUrl(feed.streamId)
                else repository.getMergedLiveStreamUrl(feed.serverIndex, feed.streamId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(activity, "Couldn't load this copy", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (feed.serverIndex == -1) open.open(url, feed.name, feed.streamId, -1, -1)
            else open.open(
                url,
                if (feed.providerName.isBlank()) feed.name else "${feed.name} · ${feed.providerName}",
                -1, feed.serverIndex, feed.streamId,
            )
        }
    }
}
