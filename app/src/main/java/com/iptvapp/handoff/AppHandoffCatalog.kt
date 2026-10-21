package com.iptvapp.handoff

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.tv.LauncherLink
import com.iptvapp.util.Resource
import kotlinx.coroutines.flow.first

/**
 * The handoff catalog over this device's own data: provider slot -1 is the main account (channels,
 * vod_streams, series tables), 0.. are the extra providers (merged_* tables). Episodes come from the
 * provider (series info), since they aren't stored.
 */
class AppHandoffCatalog(
    private val db: IptvDatabase,
    private val repository: XtreamRepository,
    private val prefs: PreferencesManager,
) : HandoffCatalog {

    override suspend fun providers(): List<LocalProvider> {
        val out = mutableListOf<LocalProvider>()
        val creds = prefs.credentials.first()
        if (creds.serverUrl.isNotBlank()) {
            val nick = runCatching { prefs.serverNickname.first() }.getOrDefault("")
            out += LocalProvider(-1, nick.ifBlank { "Main provider" }, identity(creds.serverUrl, creds.username))
        }
        prefs.getExtraServersWithNick().forEachIndexed { i, s ->
            val enabled = s.getOrElse(5) { "true" }.toBoolean()
            if (!enabled) return@forEachIndexed
            val url = s.getOrElse(0) { "" }
            if (url.isBlank()) return@forEachIndexed
            val user = if (s.getOrElse(6) { "xtream" } == "m3u") "" else s.getOrElse(1) { "" }
            out += LocalProvider(i, s.getOrElse(3) { "" }.ifBlank { "Provider ${i + 2}" }, identity(url, user))
        }
        return out
    }

    override suspend fun movie(serverIndex: Int, streamId: Int): MovieItem? =
        if (serverIndex == -1) db.vodDao().getVodByStreamId(streamId)?.let { MovieItem(-1, it.streamId, it.name, yearOf(it.name), it.containerExtension) }
        else db.mergedVodDao().getByIndexAndId(serverIndex, streamId)?.let { MovieItem(serverIndex, it.streamId, it.name, yearOf(it.name), it.containerExtension) }

    override suspend fun moviesNamed(serverIndex: Int, title: String): List<MovieItem> {
        val q = ftsQuery(title) ?: return emptyList()
        return if (serverIndex == -1) db.vodDao().searchVod(q).first().map { MovieItem(-1, it.streamId, it.name, yearOf(it.name), it.containerExtension) }
        else db.mergedVodDao().search(q).first().filter { it.serverIndex == serverIndex }
            .map { MovieItem(serverIndex, it.streamId, it.name, yearOf(it.name), it.containerExtension) }
    }

    override suspend fun channel(serverIndex: Int, streamId: Int): ChannelItem? =
        if (serverIndex == -1) db.channelDao().getChannelById(streamId)?.let { ChannelItem(-1, it.streamId, it.name) }
        else db.mergedChannelDao().getByIndexAndId(serverIndex, streamId)?.let { ChannelItem(serverIndex, it.streamId, it.name) }

    override suspend fun channelsNamed(serverIndex: Int, name: String): List<ChannelItem> {
        val q = ftsQuery(ContentMatcher.channelNorm(name)) ?: return emptyList()
        return if (serverIndex == -1) db.channelDao().searchChannels(q).first().map { ChannelItem(-1, it.streamId, it.name) }
        else db.mergedChannelDao().searchOn(q, listOf(serverIndex), 200).map { ChannelItem(serverIndex, it.streamId, it.name) }
    }

    override suspend fun series(serverIndex: Int, seriesId: Int): SeriesItem? =
        if (serverIndex == -1) db.seriesDao().getSeriesById(seriesId)?.let { SeriesItem(-1, it.seriesId, it.name) }
        else db.mergedSeriesDao().getByIndexAndId(serverIndex, seriesId)?.let { SeriesItem(serverIndex, it.seriesId, it.name) }

    override suspend fun seriesNamed(serverIndex: Int, title: String): List<SeriesItem> {
        val q = ftsQuery(title) ?: return emptyList()
        return if (serverIndex == -1) db.seriesDao().searchSeries(q).first().map { SeriesItem(-1, it.seriesId, it.name) }
        else db.mergedSeriesDao().search(q).first().filter { it.serverIndex == serverIndex }.map { SeriesItem(serverIndex, it.seriesId, it.name) }
    }

    override suspend fun episodes(serverIndex: Int, seriesId: Int): List<EpisodeItem>? {
        val info = try {
            if (serverIndex == -1) repository.fetchSeriesInfo(seriesId) else repository.fetchMergedSeriesInfo(serverIndex, seriesId)
        } catch (_: Exception) {
            return null
        }
        val data = (info as? Resource.Success)?.data ?: return null
        return data.episodes.orEmpty().values.flatten()
            .filter { EPISODE_ID.matches(it.id) }
            .map { EpisodeItem(it.id, it.season, it.episodeNum, it.title, it.containerExtension) }
    }

    companion object {
        private val EPISODE_ID = Regex("[A-Za-z0-9]{1,32}")

        /** Address and account, normalized the way launcher links compare providers. Never sent. */
        fun identity(url: String, username: String): String = LauncherLink.normalizeServer(url) + "\n" + username.trim()

        /** "Heat (1995)" → 1995. */
        fun yearOf(title: String): Int? = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1880..2200 }

        /**
         * An FTS4 MATCH query of the title's words (letters and digits only, so nothing in a title
         * can become FTS syntax), each as a prefix, all required. Null when there are none.
         */
        fun ftsQuery(title: String): String? {
            val words = ContentMatcher.norm(title).split(' ').filter { it.isNotBlank() }.take(6)
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$it*" }
        }
    }
}
