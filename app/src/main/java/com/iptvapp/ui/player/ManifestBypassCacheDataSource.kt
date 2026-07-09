package com.iptvapp.ui.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache

/**
 * Wraps a [CacheDataSource] but bypasses the cache entirely for HLS playlist (.m3u8) requests,
 * routing those straight to the network every time. Live playlists update every few seconds —
 * caching them like a normal media segment means that after the cache entry is written once,
 * every later request replays that same frozen snapshot of the playlist forever instead of
 * fetching the current one, which looks exactly like "the stream restarts and repeats the same
 * few minutes" after the cache write happens. Actual media segments (.ts etc.) still cache
 * normally, which is what the DVR/timeshift rewind feature depends on.
 */
class ManifestBypassCacheDataSource(
    private val cachedDelegate: DataSource,
    private val uncachedDelegate: DataSource
) : DataSource {
    private var active: DataSource = uncachedDelegate

    override fun addTransferListener(transferListener: TransferListener) {
        cachedDelegate.addTransferListener(transferListener)
        uncachedDelegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path ?: ""
        active = if (path.endsWith(".m3u8", ignoreCase = true)) uncachedDelegate else cachedDelegate
        return active.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun getUri() = active.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
    override fun close() = active.close()

    class Factory(
        private val cache: SimpleCache,
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val cached = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            val uncached = upstreamFactory.createDataSource()
            return ManifestBypassCacheDataSource(cached, uncached)
        }
    }
}
