package com.iptvapp.ui.home

import android.content.Context
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feature A: Auto-Generated VOD Trailers — Netflix-row-style focus preview.
 *
 * VOD list items (VodEntity/MergedVodEntity) carry no duration field on the Xtream list
 * endpoint (get_vod_streams only returns catalog metadata — actual runtime is only available
 * per-title from get_vod_info, a separate network call per title). Fetching that just to compute
 * a "skip the first N%" clip point for a preview that's routinely cancelled within a second or
 * two of focus moving on is not worth it, so this deliberately plays from position 0 of the real
 * VOD stream for PREVIEW_DURATION_MS (~18s) and loops from there, rather than attempting any
 * clip-percentage math against a duration that isn't available at this call site.
 *
 * ONE shared ExoPlayer instance for the whole app — every VOD tile in every grid (item_vod.xml,
 * item_merged_vod.xml, item_tv_vod_poster.xml) has its own small, normally-`gone` PlayerView in
 * its layout, but they never each get their own player: onTileFocused reattaches this single
 * ExoPlayer (`player`) to whichever tile's PlayerView is currently the active one, detaching it
 * from the previous tile first. Only one tile is ever actually decoding video at a time, exactly
 * like a real Netflix row.
 */
object TilePreviewPlayer {
    private const val FOCUS_DELAY_MS = 700L
    private const val PREVIEW_DURATION_MS = 18_000L

    private val scope: CoroutineScope = MainScope()
    private var player: ExoPlayer? = null
    private var pendingJob: Job? = null
    private var loopJob: Job? = null
    private var activeView: PlayerView? = null
    private var activeKey: String? = null

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }
        player = p
        return p
    }

    /**
     * Call when a VOD tile gains focus/hover. [key] uniquely identifies the tile (e.g.
     * "streamId" or "serverIndex:streamId") so a redundant re-focus of the same still-active tile
     * is a no-op. [playerView] is that tile's own (normally `View.GONE`) PlayerView from its
     * layout. [urlProvider] is a suspend lambda resolving the real stream URL (kept lazy/suspend
     * since it may need a Room/repository lookup); it only runs after the focus delay elapses,
     * and the result is discarded if the tile is no longer the focused one by then.
     */
    fun onTileFocused(
        context: Context,
        key: String,
        playerView: PlayerView,
        urlProvider: suspend () -> String?
    ) {
        if (key == activeKey) return // already the actively-previewing tile
        cancelPending()
        pendingJob = scope.launch {
            delay(FOCUS_DELAY_MS)
            val url = urlProvider() ?: return@launch
            startPreview(context, key, playerView, url)
        }
    }

    /** Call when a tile loses focus/hover. Cancels any not-yet-started pending preview for this
     * key, and if this tile is the one currently playing, stops and detaches the shared player so
     * the tile reverts to its static poster. Safe to call unconditionally. */
    fun onTileUnfocused(key: String) {
        if (activeKey == key) {
            stopActive()
        } else {
            cancelPending()
        }
    }

    /** Guarantees the shared player is detached if [playerView] is about to be recycled by the
     * RecyclerView, regardless of which key was last focused — call from each VOD adapter's
     * onViewRecycled override. */
    fun releaseIfHolding(playerView: PlayerView) {
        if (activeView === playerView) {
            stopActive()
        }
    }

    private fun startPreview(context: Context, key: String, playerView: PlayerView, url: String) {
        stopActive()
        val exo = ensurePlayer(context)
        activeView?.let { it.player = null; it.visibility = View.GONE }
        playerView.player = exo
        playerView.visibility = View.VISIBLE
        activeView = playerView
        activeKey = key
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        // Loop the first PREVIEW_DURATION_MS of the stream — see class kdoc for why this is a
        // fixed window from position 0 rather than a clip computed from actual title duration.
        loopJob = scope.launch {
            while (activeKey == key) {
                delay(PREVIEW_DURATION_MS)
                if (activeKey == key) exo.seekTo(0)
            }
        }
    }

    private fun stopActive() {
        cancelPending()
        loopJob?.cancel()
        loopJob = null
        player?.apply {
            stop()
            clearMediaItems()
        }
        activeView?.let { it.player = null; it.visibility = View.GONE }
        activeView = null
        activeKey = null
    }

    private fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
    }

    /** Full teardown of the shared decoder — not required for normal recycling
     * (releaseIfHolding covers that), available for a process-level cleanup if ever needed. */
    fun releaseAll() {
        stopActive()
        player?.release()
        player = null
    }
}
