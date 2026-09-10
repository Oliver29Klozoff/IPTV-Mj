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
 * Live-channel equivalent of TilePreviewPlayer (Feature A's VOD focus-preview) — same shared-
 * single-decoder architecture (one ExoPlayer for the whole app, reattached to whichever channel
 * row's own normally-`gone` PlayerView is currently focused), but NOT a fork-and-copy: live
 * streams differ from VOD in ways that matter enough to keep this a separate object rather than
 * a shared one, both architecturally and for safety:
 *
 * - No loop. VOD's `seekTo(0)` restart exists because VOD previews are deliberately a bounded
 *   clip of an on-demand file; a live stream has no "restart" concept and REPEAT_MODE_ONE against
 *   a live HLS/TS source would be actively wrong.
 * - A much longer settle delay (SETTLE_DELAY_MS, 1800ms vs. VOD's 700ms) before a preview even
 *   starts. Every live preview is a brand-new real connection to the provider — unlike VOD, there
 *   is no "buffer once, loop cheaply" property to lean on, and Xtream providers commonly cap
 *   concurrent connections per account. Scanning quickly through a channel list must NOT open a
 *   real stream per row passed over; only a row the user actually pauses on for a deliberate
 *   moment should ever cost a connection.
 * - Exactly one live preview connection open at a time, guaranteed the same way VOD's is
 *   (activeKey/stopActive), but torn down eagerly on every focus move — see onChannelUnfocused.
 *   There is no bandwidth-budget or connection-count guard anywhere else in the app (see
 *   BandwidthBudgetManager's kdoc — it only tracks monthly data volume, never concurrent
 *   connections), so this object IS the safeguard for this feature; it must never let two
 *   previews overlap even for the brief instant between stopping one and starting the next.
 *
 * Kept as a fully separate object (not a shared base class with TilePreviewPlayer) so a VOD row
 * preview settling elsewhere on screen and a live-channel row preview can never contend for the
 * same underlying ExoPlayer instance — they are genuinely simultaneous, independent use cases.
 */
object LiveChannelPreviewPlayer {
    private const val SETTLE_DELAY_MS = 1800L

    private val scope: CoroutineScope = MainScope()
    private var player: ExoPlayer? = null
    private var pendingJob: Job? = null
    private var activeView: PlayerView? = null
    private var activeKey: String? = null

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
        }
        player = p
        return p
    }

    /** Call when a live-channel row gains focus/hover. [key] uniquely identifies the channel
     * (e.g. "streamId" or "serverIndex:streamId") so a redundant re-focus of the same still-
     * active row is a no-op. [playerView] is that row's own (normally `View.GONE`) PlayerView.
     * [urlProvider] is a suspend lambda resolving the real stream URL — it only runs after the
     * settle delay elapses, and its result is discarded if the row is no longer focused by then
     * (the same "did focus move on already" guard VOD's onTileFocused uses). */
    fun onChannelFocused(
        context: Context,
        key: String,
        playerView: PlayerView,
        urlProvider: suspend () -> String?
    ) {
        if (key == activeKey) return
        cancelPending()
        pendingJob = scope.launch {
            delay(SETTLE_DELAY_MS)
            val url = urlProvider() ?: return@launch
            startPreview(context, key, playerView, url)
        }
    }

    /** Call when a row loses focus/hover. Cancels any not-yet-started pending preview for this
     * key, and if this row is the one currently playing, stops and detaches the shared player
     * immediately — unlike VOD (where a brief lingering connection while scrolling away is
     * harmless), a live preview must be torn down the instant focus moves on, so fast scrolling
     * never leaves more than one real connection open even momentarily. */
    fun onChannelUnfocused(key: String) {
        if (activeKey == key) {
            stopActive()
        } else {
            cancelPending()
        }
    }

    /** Guarantees the shared player is detached if [playerView] is about to be recycled by the
     * RecyclerView, regardless of which key was last focused — call from the channel adapter's
     * onViewRecycled override. */
    fun releaseIfHolding(playerView: PlayerView) {
        if (activeView === playerView) {
            stopActive()
        }
    }

    /** True if [key]'s preview has actually started playing (settle delay already elapsed),
     * as opposed to still pending. Lets a caller distinguish "held long enough to see a preview"
     * from "tapped and released before the settle delay" without its own separate timer. */
    fun isActive(key: String): Boolean = activeKey == key

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
    }

    private fun stopActive() {
        cancelPending()
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
