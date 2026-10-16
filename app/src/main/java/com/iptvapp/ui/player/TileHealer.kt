package com.iptvapp.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When a tile is sick, and what to do about it. Plain numbers only, so it can be tested.
 *
 * A tile is sick when its player errors, buffers for [STUCK_BUFFERING_MS], or claims to play
 * while neither its position nor its rendered frames have moved for [FROZEN_MS] (the picture
 * froze with no error at all). Each repair reloads the tile after a growing delay. From the
 * [BACKUP_FROM_ATTEMPT]th repair in a row, the tile tries a backup copy of its channel first.
 * A tile that has played cleanly for [HEALTHY_AFTER_MS] starts its count over.
 */
object TileHealPolicy {
    const val STUCK_BUFFERING_MS = 12_000L
    const val FROZEN_MS = 8_000L
    const val HEALTHY_AFTER_MS = 30_000L
    const val BACKUP_FROM_ATTEMPT = 3
    private val DELAYS_MS = longArrayOf(1_000L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L)

    /** Wait before repair number [attempt], counted from 1. Capped, never gives up. */
    fun delayMs(attempt: Int): Long = DELAYS_MS[(attempt - 1).coerceIn(0, DELAYS_MS.size - 1)]

    fun tryBackup(attempt: Int): Boolean = attempt >= BACKUP_FROM_ATTEMPT

    /**
     * Watches for a frozen picture from periodic samples. [renderedFrames] is null for a tile
     * with no video decoder (radio), which is judged on position alone.
     */
    class FreezeDetector {
        private var lastPosition = Long.MIN_VALUE
        private var lastFrames: Int? = null
        private var stillSince = 0L

        fun reset() {
            lastPosition = Long.MIN_VALUE
            lastFrames = null
            stillSince = 0L
        }

        /** True once, when the tile has been frozen for [FROZEN_MS]. */
        fun sample(nowMs: Long, playing: Boolean, positionMs: Long, renderedFrames: Int?): Boolean {
            if (!playing) {
                reset()
                return false
            }
            // With video, new frames are what the viewer sees: a picture can freeze while the sound,
            // and so the clock, keeps going, which on a muted tile looks exactly like a dead one. Even
            // a still image is re-rendered every frame. Radio has no frames, so it is judged on the clock.
            val still = if (renderedFrames != null) renderedFrames == lastFrames else positionMs == lastPosition
            lastPosition = positionMs
            lastFrames = renderedFrames
            if (!still) {
                stillSince = 0L
                return false
            }
            if (stillSince == 0L) {
                stillSince = nowMs
                return false
            }
            if (nowMs - stillSince >= FROZEN_MS) {
                stillSince = 0L
                return true
            }
            return false
        }
    }
}

/**
 * Keeps one Multi-View or Mosaic tile alive on its own, without touching the other tiles.
 * The screen tells it which channel the tile shows ([watch]); it reports what it is doing
 * through [onStatus] (null when healthy) so the tile can say "Reconnecting".
 *
 * [freshUrl] builds the URL again for each reload, since a provider's tokenised link can expire.
 * [backup] returns another copy of the channel and its label, or null; it is passed the copies
 * already given up on so it never offers one back.
 */
class TileHealer(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val tag: String,
    private val log: (String) -> Unit,
    private val onStatus: (String?) -> Unit,
    /** The tile moved to a backup copy for good. The screen updates its label and its saved channel. */
    private val onSwitched: (Source) -> Unit = {},
    /** Settings > Network > Automatic backup feed. Off still reconnects, just never to another copy. */
    private val backupAllowed: () -> Boolean = { true },
) {
    /** [payload] is the screen's own channel object, handed back through onSwitched. */
    class Source(val key: Any, val label: String, val payload: Any? = null, val freshUrl: suspend () -> String?)

    private var source: Source? = null
    private var backup: (suspend (Set<Any>) -> Source?)? = null
    private val givenUp = mutableSetOf<Any>()
    private var attempt = 0
    private var healJob: Job? = null
    private var stuckJob: Job? = null
    private var watchdog: Job? = null
    private var healthySince = 0L
    private val freeze = TileHealPolicy.FreezeDetector()
    private var released = false
    private var paused = false
    /** A repair that was due while the screen was away, carried out when it returns. */
    private var deferredReason: String? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> armStuck()
                Player.STATE_READY -> {
                    stuckJob?.cancel()
                    if (healthySince == 0L) healthySince = android.os.SystemClock.elapsedRealtime()
                    if (healJob?.isActive != true) onStatus(null)
                }
                // A live stream that ends has dropped, not finished.
                Player.STATE_ENDED -> heal("ended")
                else -> stuckJob?.cancel()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            heal("error ${error.errorCodeName}")
        }
    }

    init {
        player.addListener(listener)
    }

    /** The tile now shows [next]. Forget earlier trouble; the new channel starts clean. */
    fun watch(next: Source, backupFinder: (suspend (Set<Any>) -> Source?)?) {
        source = next
        backup = backupFinder
        givenUp.clear()
        attempt = 0
        deferredReason = null
        healthySince = 0L
        healJob?.cancel()
        stuckJob?.cancel()
        freeze.reset()
        onStatus(null)
        startWatchdog()
    }

    /**
     * Paused with the screen: nothing is judged or reloaded until it returns, since a reload
     * starts playback and would play behind the user's back. A repair that was waiting is kept.
     */
    fun pause() {
        paused = true
        if (healJob?.isActive == true) {
            healJob?.cancel()
            deferredReason = deferredReason ?: "repair postponed"
            attempt = (attempt - 1).coerceAtLeast(0) // it never ran
        }
        stuckJob?.cancel()
        watchdog?.cancel()
        freeze.reset()
    }

    fun resume() {
        paused = false
        if (source == null || released) return
        startWatchdog()
        val due = deferredReason
        deferredReason = null
        when {
            due != null -> heal(due)
            // play() does not change a state that is already BUFFERING, so no new callback comes.
            player.playbackState == Player.STATE_BUFFERING -> armStuck()
        }
    }

    fun release() {
        released = true
        healJob?.cancel()
        stuckJob?.cancel()
        watchdog?.cancel()
        player.removeListener(listener)
    }

    private fun armStuck() {
        stuckJob?.cancel()
        stuckJob = scope.launch {
            delay(TileHealPolicy.STUCK_BUFFERING_MS)
            if (player.playbackState == Player.STATE_BUFFERING) heal("stuck buffering")
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        freeze.reset()
        watchdog = scope.launch {
            while (isActive) {
                delay(2_000L)
                val now = android.os.SystemClock.elapsedRealtime()
                if (healthySince > 0L && attempt > 0 && now - healthySince >= TileHealPolicy.HEALTHY_AFTER_MS) {
                    attempt = 0
                }
                val counters = player.videoDecoderCounters
                counters?.ensureUpdated()
                val frames = counters?.renderedOutputBufferCount
                if (healJob?.isActive != true &&
                    freeze.sample(now, player.isPlaying, player.currentPosition, frames)
                ) heal("frozen picture")
            }
        }
    }

    private fun heal(reason: String) {
        if (released || source == null || healJob?.isActive == true) return
        if (paused) {
            deferredReason = deferredReason ?: reason
            return
        }
        stuckJob?.cancel()
        healthySince = 0L
        attempt++
        val n = attempt
        healJob = scope.launch {
            onStatus("Reconnecting…")
            delay(TileHealPolicy.delayMs(n))
            var target = source ?: return@launch
            if (TileHealPolicy.tryBackup(n) && backupAllowed()) {
                val alt = try {
                    backup?.invoke(givenUp + target.key)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { null }
                if (alt != null) {
                    givenUp += target.key
                    source = alt
                    target = alt
                    onSwitched(alt)
                    onStatus("Backup feed: ${alt.label}")
                }
            }
            val url = try {
                target.freshUrl()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
            log("$tag HEAL #$n: $reason -> reload ${target.label}${if (url == null) " (no url)" else ""}")
            if (url == null) {
                // Nothing to load right now. Try again on the next, longer delay.
                healJob = null
                heal("no url")
                return@launch
            }
            freeze.reset()
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }
}
