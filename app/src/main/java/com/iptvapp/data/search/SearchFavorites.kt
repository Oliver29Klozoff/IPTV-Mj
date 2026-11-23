package com.iptvapp.data.search

import com.iptvapp.data.repository.XtreamRepository

/**
 * Favoriting straight from a search result. A channel, or a programme on one (its channel is
 * what gets the star), a movie or a series; episodes and recordings have no favorite of their own.
 *
 * Each copy is its own favorite, as in the channel lists: the same channel on two providers, or
 * the same id on two providers, are two keys. A Smart Channel group is not changed from here.
 */
object SearchFavorites {

    /** What a favorite on [hit] belongs to, the same for every hit on it; null when it can't have one. */
    fun keyOf(hit: SearchHit): String? = when (hit) {
        is SearchHit.Channel -> channelKey(hit.channel?.streamId, hit.merged?.serverIndex, hit.merged?.streamId)
        is SearchHit.Program -> channelKey(hit.channel?.streamId, hit.merged?.serverIndex, hit.merged?.streamId)
        is SearchHit.Movie -> hit.vod?.let { "vod:-1:${it.streamId}" } ?: hit.merged?.let { "vod:${it.serverIndex}:${it.streamId}" }
        is SearchHit.Series -> hit.series?.let { "series:-1:${it.seriesId}" } ?: hit.merged?.let { "series:${it.serverIndex}:${it.seriesId}" }
        else -> null
    }

    private fun channelKey(primaryId: Int?, serverIndex: Int?, mergedId: Int?) = when {
        primaryId != null -> "channel:-1:$primaryId"
        serverIndex != null && mergedId != null -> "channel:$serverIndex:$mergedId"
        else -> null
    }

    fun canFavorite(hit: SearchHit) = keyOf(hit) != null

    /** Whether [hit] is a favorite: a change made on this screen ([changed], by key) wins over what the search found. */
    fun isFavorite(hit: SearchHit, changed: Map<String, Boolean> = emptyMap()): Boolean {
        keyOf(hit)?.let { key -> changed[key]?.let { return it } }
        return when (hit) {
            is SearchHit.Channel -> hit.channel?.isFavorite ?: hit.merged?.isFavorite ?: false
            is SearchHit.Program -> hit.channel?.isFavorite ?: hit.merged?.isFavorite ?: false
            is SearchHit.Movie -> hit.vod?.isFavorite ?: hit.merged?.isFavorite ?: false
            is SearchHit.Series -> hit.series?.isFavorite ?: hit.merged?.isFavorite ?: false
            else -> false
        }
    }

    /** The name a favorite is shown under: the channel's for a programme. */
    fun nameOf(hit: SearchHit) = if (hit is SearchHit.Program) hit.channelName else hit.title

    /**
     * Makes [hit] a favorite, or not. A main-provider channel goes through the same toggle as the
     * channel lists, so Primary's US favorites still get (and give back) their channel number.
     * Returns whether anything was changed.
     */
    suspend fun set(repository: XtreamRepository, hit: SearchHit, favorite: Boolean): Boolean {
        when (hit) {
            is SearchHit.Channel -> return setChannel(repository, hit.channel?.streamId, hit.merged, favorite)
            is SearchHit.Program -> return setChannel(repository, hit.channel?.streamId, hit.merged, favorite)
            is SearchHit.Movie -> {
                hit.vod?.let { repository.setVodFavorite(it.streamId, favorite); return true }
                hit.merged?.let { repository.setMergedVodFavorite(it, favorite); return true }
            }
            is SearchHit.Series -> {
                hit.series?.let { repository.setSeriesFavorite(it.seriesId, favorite); return true }
                hit.merged?.let { repository.setMergedSeriesFavorite(it, favorite); return true }
            }
            else -> {}
        }
        return false
    }

    private suspend fun setChannel(
        repository: XtreamRepository, primaryId: Int?, merged: com.iptvapp.data.local.entities.MergedChannelEntity?, favorite: Boolean,
    ): Boolean {
        if (primaryId != null) {
            // Only when it differs: the toggle flips whatever is saved.
            if (repository.isChannelFavorite(primaryId) != favorite) repository.toggleChannelFavorite(primaryId)
            return true
        }
        if (merged != null) {
            repository.setMergedChannelFavorite(merged.serverIndex, merged.streamId, favorite)
            return true
        }
        return false
    }
}
