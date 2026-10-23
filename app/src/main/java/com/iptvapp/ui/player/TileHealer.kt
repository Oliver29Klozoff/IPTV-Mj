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
    const val MAX_ATTEMPTS = 6
    private val DELAYS_MS = longArrayOf(1_000L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L)

    /** Wait before repair number [attempt], counted from 1. Capped; recovery stops after MAX_ATTEMPTS. */
    fun delayMs(attempt: Int): Long = DELAYS_MS[(attempt - 1).coerceIn(0, DELAYS_MS.size - 1)]

    fun tryBackup(attempt: Int): Boolean = attempt in BACKUP_FROM_ATTEMPT..MAX_ATTEMPTS

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
 * The screen tells it which channel the tile shows ([open]); it reports what it is doing
 * through [onStatus] (null when healthy) so the tile can say "Reconnecting".
 *
 * [freshUrl] builds the URL again for each reload, since a provider's tokenised link can expire.
 * [backup] returns another copy of the channel and its label, or null; it is passed the copies
 * already given up on so it never offers one back.
 */
class TileHealer(
    private val player: Player,
    private val scope: CoroutineScope,
    private val tag: String,
    private val log: (String) -> Unit,
    private val onStatus: (String?) -> Unit,
    private val onSwitched: (Source) -> Unit = {},
    private val backupAllowed: () -> Boolean = { true },
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    class Source(val key: Any, val label: String, val payload: Any? = null, val freshUrl: suspend () -> String?)
    /** A known admission refusal needs a new user choice, not six provider requests. */
    class AdmissionDenied(val status: String) : Exception()

    private var source: Source? = null
    private var backup: (suspend (Set<Any>) -> Source?)? = null
    private val givenUp = mutableSetOf<Any>()
    private var attempt = 0
    private var generation = 0L
    private var healJob: Job? = null
    private var watchdog: Job? = null
    private var healthySince: Long? = null
    private var bufferingSince: Long? = null
    private val freeze = TileHealPolicy.FreezeDetector()
    private var released = false
    private var paused = false
    private var exhausted = false
    private var changing = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (released || paused || changing) return
            when (state) {
                Player.STATE_BUFFERING -> { healthySince = null; bufferingSince = clock() }
                Player.STATE_READY -> { bufferingSince = null; if (!exhausted) onStatus(null) }
                Player.STATE_ENDED -> heal("ended")
                else -> bufferingSince = null
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (!changing) heal("error ${error.errorCodeName}")
        }
    }

    init { player.addListener(listener) }

    /** Initial opening and repairs share one cancellable operation per tile. */
    fun open(next: Source, backupFinder: (suspend (Set<Any>) -> Source?)? = null) {
        if (released) return
        generation++
        healJob?.cancel()
        source = next
        backup = backupFinder
        givenUp.clear()
        attempt = 0
        exhausted = false
        healthySince = null
        bufferingSince = null
        freeze.reset()
        stopFeed()
        onStatus("Connecting…")
        if (!paused) { startWatchdog(); openSource("selection", initial = true) }
    }

    fun pause() {
        if (released || paused) return
        paused = true
        generation++
        healJob?.cancel()
        healJob = null
        watchdog?.cancel()
        healthySince = null
        bufferingSince = null
        freeze.reset()
        stopFeed()
    }

    fun resume() {
        if (released || !paused) return
        paused = false
        if (source == null || exhausted) return
        startWatchdog()
        openSource("resume", initial = true)
    }

    fun release() {
        if (released) return
        released = true
        generation++
        healJob?.cancel()
        watchdog?.cancel()
        player.removeListener(listener)
        source = null
        backup = null
    }

    private fun stopFeed() {
        changing = true
        try { player.stop(); player.clearMediaItems() } finally { changing = false }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && !released && !paused && !exhausted) {
                delay(2_000L)
                val now = clock()
                val counters = (player as? ExoPlayer)?.videoDecoderCounters
                counters?.ensureUpdated()
                val frozen = freeze.sample(now, player.isPlaying, player.currentPosition, counters?.renderedOutputBufferCount)
                if (!player.isPlaying || healJob?.isActive == true || frozen) healthySince = null
                else if (healthySince == null) healthySince = now
                if (healthySince?.let { now - it >= TileHealPolicy.HEALTHY_AFTER_MS } == true) attempt = 0
                if (frozen) heal("frozen picture")
                if (player.playbackState == Player.STATE_BUFFERING &&
                    bufferingSince?.let { now - it >= TileHealPolicy.STUCK_BUFFERING_MS } == true) heal("stuck buffering")
            }
        }
    }

    private fun heal(reason: String) {
        if (released || paused || exhausted || source == null || healJob?.isActive == true) return
        if (attempt >= TileHealPolicy.MAX_ATTEMPTS) {
            exhausted = true
            stopFeed()
            onStatus("Stopped after ${TileHealPolicy.MAX_ATTEMPTS} retries — select channel to retry")
            log("$tag recovery exhausted")
            return
        }
        attempt++
        healthySince = null
        bufferingSince = null
        openSource(reason, initial = false)
    }

    private fun openSource(reason: String, initial: Boolean) {
        val ticket = generation
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val began = clock()
            if (!initial) {
                onStatus("Reconnecting ($attempt/${TileHealPolicy.MAX_ATTEMPTS})…")
                delay(TileHealPolicy.delayMs(attempt))
            }
            var target = source ?: return@launch
            if (!initial && TileHealPolicy.tryBackup(attempt) && backupAllowed()) {
                val alt = try { backup?.invoke(givenUp + target.key) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
                if (!isActive || ticket != generation || released || paused) return@launch
                if (alt != null) target = alt
            }
            // Stop only this tile before opening/replacing its provider connection.
            stopFeed()
            val url = try { kotlinx.coroutines.withTimeoutOrNull(15_000L) { target.freshUrl() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: AdmissionDenied) {
                if (isActive && ticket == generation && !released && !paused) {
                    exhausted = true
                    healJob = null
                    onStatus(e.status)
                }
                return@launch
            }
            catch (_: Exception) { null }
            if (!isActive || ticket != generation || released || paused) return@launch
            if (url.isNullOrBlank()) {
                healJob = null
                heal("source unavailable")
                return@launch
            }
            if (source?.key != target.key) {
                source?.let { givenUp += it.key }
                source = target
                onSwitched(target)
            }
            freeze.reset()
            // Clear the job guard before prepare: synchronous error callbacks must be recoverable.
            healJob = null
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
            log("$tag open attempt=$attempt reason=$reason resolveMs=${clock() - began}")
        }
        healJob = job
        job.start()
    }
}
