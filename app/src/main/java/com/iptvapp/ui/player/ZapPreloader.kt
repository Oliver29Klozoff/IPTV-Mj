package com.iptvapp.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl

/**
 * Prepares at most the next and previous live channels with Media3's [DefaultPreloadManager].
 * The activity decides which indexes are allowed. This class does not open anything else,
 * and an account-busy response drops the preload without touching the channel that is playing.
 *
 * Media3 only lets a player consume a preloaded source when both share one playback thread, so
 * the player itself is built here, from the same builder as the manager ([buildPlayer]), with the
 * player's own sources, renderers, buffer and track selection. Each new player gets a new manager.
 */
@UnstableApi
class ZapPreloader(context: Context) {
    data class Neighbor(val mediaId: String, val item: MediaItem, val index: Int, val sports: Boolean)

    private val appContext = context.applicationContext
    private val control = NeighborControl()
    private val items = LinkedHashMap<String, MediaItem>()
    private val sportsById = HashMap<String, Boolean>()
    private var manager: DefaultPreloadManager? = null
    private var blockedServer: Int? = null
    private var playingMediaId: String? = null

    private val listener = object : PreloadManagerListener {
        override fun onError(preloadException: PreloadException) {
            if (!ChannelZap.accountBusy(httpStatus(preloadException))) return
            // The panel refused a second stream. Drop the neighbors. The playing source stays.
            blockedServer = pendingServer
            dropExcept(playingMediaId)
        }
    }

    private var pendingServer: Int = Int.MIN_VALUE

    fun source(mediaId: String, sports: Boolean): MediaSource? {
        if (sportsById[mediaId] != sports) return null
        val item = items[mediaId] ?: return null
        return manager?.getMediaSource(item)
    }

    /**
     * [playingId] stays registered so removing the old window cannot release the source
     * the player is using. [serverIndex] clears an account-busy block when the provider changes.
     */
    fun replace(
        serverIndex: Int,
        playingId: String?,
        currentIndex: Int,
        neighbors: List<Neighbor>
    ) {
        if (blockedServer != null && blockedServer != serverIndex) blockedServer = null
        pendingServer = serverIndex
        playingMediaId = playingId
        control.playingIndex = currentIndex
        if (blockedServer == serverIndex || neighbors.isEmpty()) {
            dropExcept(playingId)
            return
        }
        // No player built through here yet: nothing that could play a preload.
        val mgr = manager ?: return
        val keep = HashSet<String>(neighbors.size + 1)
        if (playingId != null) keep += playingId
        neighbors.forEach { keep += it.mediaId }
        val gone = items.keys.filter { it !in keep }
        for (id in gone) {
            val item = items.remove(id) ?: continue
            sportsById.remove(id)
            mgr.remove(item)
        }
        for (neighbor in neighbors) {
            if (neighbor.mediaId == playingId) continue
            val existingSports = sportsById[neighbor.mediaId]
            if (existingSports != null && existingSports != neighbor.sports) {
                items.remove(neighbor.mediaId)?.let { mgr.remove(it) }
                sportsById.remove(neighbor.mediaId)
            }
            if (items.containsKey(neighbor.mediaId)) continue
            items[neighbor.mediaId] = neighbor.item
            sportsById[neighbor.mediaId] = neighbor.sports
            mgr.add(neighbor.item, neighbor.index)
        }
        control.allowed = neighbors.map { it.index }.toSet()
        mgr.setCurrentPlayingIndex(currentIndex)
        mgr.invalidate()
    }

    fun clear() {
        dropExcept(null)
    }

    fun release() {
        items.clear()
        sportsById.clear()
        control.allowed = emptySet()
        control.playingIndex = Int.MIN_VALUE
        playingMediaId = null
        val mgr = manager
        manager = null
        mgr?.removeListener(listener)
        mgr?.release()
    }

    private fun dropExcept(playingId: String?) {
        val mgr = manager ?: return
        val gone = items.keys.filter { it != playingId }
        for (id in gone) {
            val item = items.remove(id) ?: continue
            sportsById.remove(id)
            mgr.remove(item)
        }
        control.allowed = emptySet()
        mgr.invalidate()
    }

    /**
     * The player and its preload manager, from one builder. buildExoPlayer copies the builder's
     * sources, renderers, load control and track selector onto the player, so they are the
     * player's own. [loadControl] must hold a byte budget for PlayerId.PRELOAD. Any earlier
     * manager belonged to an earlier player and is released first.
     */
    fun buildPlayer(
        mediaSourceFactory: MediaSource.Factory,
        renderersFactory: RenderersFactory,
        loadControl: LoadControl,
        trackSelectorFactory: androidx.media3.exoplayer.trackselection.TrackSelector.Factory,
    ): ExoPlayer {
        release()
        val builder = DefaultPreloadManager.Builder(appContext, control)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelectorFactory(trackSelectorFactory)
        val player = builder.buildExoPlayer(ExoPlayer.Builder(appContext))
        manager = builder.build().also { it.addListener(listener) }
        return player
    }

    private fun httpStatus(error: Throwable): Int? {
        var current: Throwable? = error
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }

    private class NeighborControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        var allowed: Set<Int> = emptySet()
        var playingIndex: Int = Int.MIN_VALUE

        override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
            // The item in the player must not be cleared. A null status crashes this Media3 version.
            if (rankingData == playingIndex || rankingData in allowed) {
                return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(ChannelZap.PRELOAD_RANGE_MS)
            }
            return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }
}
