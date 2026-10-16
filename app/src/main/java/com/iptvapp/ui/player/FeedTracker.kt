package com.iptvapp.ui.player

import com.iptvapp.util.StallMonitor

/**
 * Watches one live channel copy at a time and reports what feed scores need: whether a start
 * played and how long it took, and how many stalls each stretch of watching had. It also says
 * when the copy has stalled enough to try a backup (see [StallMonitor]).
 *
 * Plain state and timestamps only, so it can be tested without a player. PlayerActivity feeds
 * it player states and routes [Report]s to XtreamRepository.
 */
class FeedTracker(private val monitor: StallMonitor = StallMonitor()) {

    /** serverIndex -1 is the primary provider. streamId is that provider's own id. */
    data class Key(val serverIndex: Int, val streamId: Int)

    sealed class Report {
        abstract val key: Key
        data class Started(override val key: Key, val played: Boolean, val tuneMs: Long) : Report()
        data class Watched(override val key: Key, val minutes: Float, val stalls: Int) : Report()
    }

    var key: Key? = null
        private set
    private var openedAt = 0L
    private var started = false
    private var buffering = false
    private var playingSince = 0L
    private var playedMs = 0L

    /**
     * A new copy is opening. [automatic] is a backup switch, which keeps the switch budget so
     * two bad copies cannot bounce back and forth. Returns what the previous copy had pending.
     */
    fun open(next: Key?, nowMs: Long, automatic: Boolean = false): List<Report> {
        val out = flush(nowMs)
        key = next
        openedAt = nowMs
        started = false
        buffering = false
        playingSince = 0L
        playedMs = 0L
        if (automatic) monitor.switched(nowMs) else monitor.newChannel(nowMs)
        return out
    }

    /** The player reached READY. The first time is a successful start. */
    fun onReady(nowMs: Long): List<Report> {
        val k = key ?: return emptyList()
        buffering = false
        if (playingSince == 0L) playingSince = nowMs
        if (started) return emptyList()
        started = true
        return listOf(Report.Started(k, played = true, tuneMs = nowMs - openedAt))
    }

    /** The player is buffering. True when this copy should be swapped for a backup. */
    fun onBuffering(nowMs: Long): Boolean {
        if (key == null || !started || buffering) return false
        buffering = true
        pausePlaying(nowMs)
        return monitor.onStall(nowMs)
    }

    /** Paused, stopped or in the background: not watching, so not counted. */
    fun onNotPlaying(nowMs: Long) = pausePlaying(nowMs)

    /** This copy failed before it ever played. */
    fun onFailed(nowMs: Long): List<Report> {
        val k = key ?: return emptyList()
        if (started) return emptyList()
        started = true
        return listOf(Report.Started(k, played = false, tuneMs = nowMs - openedAt))
    }

    /** Watching so far, for a periodic save or leaving the channel. */
    fun flush(nowMs: Long): List<Report> {
        val k = key ?: return emptyList()
        val running = playingSince
        pausePlaying(nowMs)
        if (running > 0L && !buffering) playingSince = nowMs
        val minutes = playedMs / 60_000f
        val stalls = monitor.drainStalls()
        playedMs = 0L
        if (minutes <= 0f && stalls == 0) return emptyList()
        return listOf(Report.Watched(k, minutes, stalls))
    }

    private fun pausePlaying(nowMs: Long) {
        if (playingSince > 0L) playedMs += (nowMs - playingSince).coerceAtLeast(0L)
        playingSince = 0L
    }
}
