package com.iptvapp.data.home

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.search.SearchHit
import com.iptvapp.util.Resource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One card on a Smart Home row. [line] replaces the usual detail line under the title when
 * set (a channel's current show, an episode code); [progress] is 0–100 for something started. */
data class HomeCard(val hit: SearchHit, val line: String? = null, val progress: Int? = null)

/** One row. [wide] rows show 16:9 channel cards, the rest posters. */
data class HomeRow(val kind: Kind, val cards: List<HomeCard>) {
    enum class Kind(val title: String, val wide: Boolean) {
        CONTINUE("Continue Watching", false),
        ON_NOW("On Now", true),
        FAVORITES("Favorite Channels", true),
        RECENT("Recently Watched", true),
        STARTING_SOON("Starting Soon", true),
        NEW_EPISODES("New Episodes", false),
        RECORDINGS("Recordings", true),
        RECOMMENDED("Recommended for You", false)
    }
}

/**
 * The Smart Home screen's rows, from what this device already has: what you're partway through,
 * what's on your channels now and soon, what you watched last, recordings, and picks from the
 * categories you watch most. Rows with nothing in them are left out.
 *
 * [rows] is local and quick. [newEpisodes] asks providers for the episodes of the series you
 * follow (they aren't stored), so it comes separately and is kept for a while.
 */
@Singleton
class SmartHome @Inject constructor(
    private val repository: XtreamRepository,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) {
    companion object {
        const val ROW_LIMIT = 20
        /** How far ahead Starting Soon looks. */
        const val STARTING_SOON_MS = 2 * 60 * 60_000L
        /** Channels On Now and Starting Soon look at: favorites first, then recently watched. */
        private const val GUIDE_CHANNELS = 60
        private const val FOLLOWED_SERIES = 8
        private const val NEW_EPISODES_TTL_MS = 30 * 60_000L

        private fun ms(t: Long) = if (t < 100_000_000_000L) t * 1000L else t

        fun percent(watched: Long, duration: Long): Int? =
            if (watched > 0 && duration > 0) ((watched * 100) / duration).toInt().coerceIn(1, 99) else null
    }

    /**
     * Channels On Now and Starting Soon are drawn from. Favorites first, then recently watched,
     * plus other providers' favorites. Callers top up the stored guide for this same set.
     */
    suspend fun guideChannels(): Pair<List<ChannelEntity>, List<MergedChannelEntity>> {
        val shelf = shelf()
        return shelf.guideChannels to shelf.guideOthers
    }

    private data class Shelf(
        val favorites: List<ChannelEntity>,
        val otherFavorites: List<MergedChannelEntity>,
        val recent: List<ChannelEntity>
    ) {
        val guideChannels = (favorites + recent).distinctBy { it.streamId }.take(GUIDE_CHANNELS)
        val guideOthers = otherFavorites.take(GUIDE_CHANNELS)
    }

    private suspend fun shelf() = Shelf(
        favorites = repository.getFavoriteChannels().first().filter { !it.isHidden },
        otherFavorites = db.mergedChannelDao().getAllFavorites().first(),
        recent = repository.getRecentChannels().first()
    )

    suspend fun rows(nowMs: Long = System.currentTimeMillis()): List<HomeRow> = coroutineScope {
        val primaryLabel = prefs.serverNickname.first().ifBlank { "Primary" }
        val shelf = shelf()
        val favorites = shelf.favorites
        val otherFavorites = shelf.otherFavorites
        val recent = shelf.recent
        val guideChannels = shelf.guideChannels
        val guideOthers = shelf.guideOthers
        val primaryGuide = async { programs(guideChannels.map { it.streamId }, -1, nowMs) }
        val otherGuide = async {
            guideOthers.groupBy { it.serverIndex }.flatMap { (idx, list) -> programs(list.map { it.streamId }, idx, nowMs) }
        }
        val continueRow = async { continueWatching(primaryLabel) }
        val recordings = async { db.recordingDao().getForHome().map { HomeCard(SearchHit.Recording(it)) } }
        val recommended = async { recommended(primaryLabel) }

        val guide = primaryGuide.await() + otherGuide.await()
        val nowByChannel = guide.filter { ms(it.startTimestamp) <= nowMs && ms(it.stopTimestamp) > nowMs }
            .associateBy { it.serverIndex to it.streamId }
        fun nowTitle(serverIndex: Int, streamId: Int) = nowByChannel[serverIndex to streamId]?.title

        val primaryById = guideChannels.associateBy { it.streamId }
        val otherByKey = guideOthers.associateBy { it.serverIndex to it.streamId }
        fun program(e: EpgEntity, kind: SearchHit.Kind): HomeCard? {
            val channel = if (e.serverIndex == -1) primaryById[e.streamId] else null
            val other = if (e.serverIndex != -1) otherByKey[e.serverIndex to e.streamId] else null
            if (channel == null && other == null) return null
            val start = ms(e.startTimestamp)
            val stop = ms(e.stopTimestamp)
            val progress = if (kind == SearchHit.Kind.ON_NOW && stop > start) {
                (((nowMs - start) * 100) / (stop - start)).toInt().coerceIn(0, 100)
            } else null
            return HomeCard(SearchHit.Program(channel, other, e.title, start, stop, kind, other?.serverNickname ?: primaryLabel), progress = progress)
        }

        val onNow = nowByChannel.values.sortedBy { channelOrder(it, guideChannels, guideOthers) }
            .mapNotNull { program(it, SearchHit.Kind.ON_NOW) }.take(ROW_LIMIT)
        // The next show on each channel, starting soon (not already on).
        val startingSoon = guide.filter { ms(it.startTimestamp) > nowMs && ms(it.startTimestamp) <= nowMs + STARTING_SOON_MS }
            .groupBy { it.serverIndex to it.streamId }.values.map { airings -> airings.minBy { ms(it.startTimestamp) } }
            .sortedBy { ms(it.startTimestamp) }
            .mapNotNull { program(it, SearchHit.Kind.UPCOMING) }.take(ROW_LIMIT)

        val favoriteCards = favorites.map { HomeCard(SearchHit.Channel(it, null, primaryLabel), line = nowTitle(-1, it.streamId)) } +
            otherFavorites.map { HomeCard(SearchHit.Channel(null, it, it.serverNickname), line = nowTitle(it.serverIndex, it.streamId)) }
        val recentCards = recent.take(ROW_LIMIT).map { HomeCard(SearchHit.Channel(it, null, primaryLabel), line = nowTitle(-1, it.streamId)) }

        listOf(
            HomeRow(HomeRow.Kind.CONTINUE, continueRow.await()),
            HomeRow(HomeRow.Kind.ON_NOW, onNow),
            HomeRow(HomeRow.Kind.FAVORITES, favoriteCards.take(ROW_LIMIT)),
            HomeRow(HomeRow.Kind.RECENT, recentCards),
            HomeRow(HomeRow.Kind.STARTING_SOON, startingSoon),
            HomeRow(HomeRow.Kind.RECORDINGS, recordings.await()),
            HomeRow(HomeRow.Kind.RECOMMENDED, recommended.await())
        ).filter { it.cards.isNotEmpty() }
    }

    // Favorites in their own order, then recently watched; other providers' favorites after.
    private fun channelOrder(e: EpgEntity, primary: List<ChannelEntity>, others: List<MergedChannelEntity>): Int {
        if (e.serverIndex == -1) return primary.indexOfFirst { it.streamId == e.streamId }.let { if (it < 0) Int.MAX_VALUE else it }
        return primary.size + others.indexOfFirst { it.serverIndex == e.serverIndex && it.streamId == e.streamId }.let { if (it < 0) 0 else it }
    }

    private suspend fun programs(ids: List<Int>, serverIndex: Int, nowMs: Long): List<EpgEntity> {
        val toMs = nowMs + STARTING_SOON_MS
        return ids.chunked(500).flatMap { db.epgDao().getProgramsBetween(it, serverIndex, nowMs, toMs) }
    }

    // Movies and series partway through, most recently watched first.
    private suspend fun continueWatching(primaryLabel: String): List<HomeCard> {
        val movies = repository.getInProgressVod().first().map { vod ->
            vod.lastWatchedAt to HomeCard(SearchHit.Movie(vod, null, primaryLabel), progress = percent(vod.watchedMs, vod.durationMs))
        }
        val series = repository.getInProgressSeries().first().mapIndexed { i, row ->
            val hit = SearchHit.Series(row.series, null, primaryLabel)
            // Rows without a recorded time keep their query order (most recent first).
            val at = row.series.lastWatchedAt.takeIf { it > 0 } ?: (Long.MAX_VALUE / 2 - i)
            at to HomeCard(SearchHit.Episode(hit, row.lastSeason, row.lastEpisode, ""),
                line = "S${row.lastSeason}E${row.lastEpisode}", progress = percent(row.lastEpisodeWatchedMs, row.lastEpisodeDurationMs))
        }
        return (movies + series).sortedByDescending { it.first }.map { it.second }.take(ROW_LIMIT)
    }

    // Movies and series from the categories watched most, alternating.
    private suspend fun recommended(primaryLabel: String): List<HomeCard> {
        val movies = db.vodDao().getRecommended(ROW_LIMIT).map { HomeCard(SearchHit.Movie(it, null, primaryLabel)) }
        val series = db.seriesDao().getRecommended(ROW_LIMIT / 2).map { HomeCard(SearchHit.Series(it, null, primaryLabel)) }
        val out = ArrayList<HomeCard>()
        for (i in 0 until maxOf(movies.size, series.size)) {
            movies.getOrNull(i)?.let { out += it }
            series.getOrNull(i)?.let { out += it }
        }
        return out.take(ROW_LIMIT)
    }

    private data class ListedEpisode(val season: Int, val episodeNum: Int, val title: String, val addedSec: Long)

    /** Null [episodes] means the provider did not answer. A list is kept so a later watch can change the card. */
    private data class CachedListing(val atMs: Long, val episodes: List<ListedEpisode>?)

    private val episodeCache = HashMap<String, CachedListing>()

    /** Account the cache currently belongs to. A request still in flight after a provider switch must not write. */
    @Volatile private var episodeCacheAccount: String = ""

    private suspend fun accountKey(): String {
        val creds = prefs.credentials.first()
        return creds.serverUrl + "\n" + creds.username
    }

    /**
     * New Episodes: for the series you watch (most recent first) and your favorite series, the
     * first episode you haven't seen that the provider added since you last watched — or, for a
     * series you haven't started, one added in the last two weeks. Asks each series' provider,
     * at most four at a time; each answer is kept for half an hour.
     * The kept answer is the provider's episode list, for this account only. Which episode is
     * new is decided from what you have watched now, so watching one drops it from the row.
     */
    suspend fun newEpisodes(nowMs: Long = System.currentTimeMillis()): HomeRow? = coroutineScope {
        val account = accountKey()
        episodeCacheAccount = account
        val primaryLabel = prefs.serverNickname.first().ifBlank { "Primary" }
        val followed = db.seriesDao().getFollowed(FOLLOWED_SERIES)
        val gate = Semaphore(4)
        val cards = followed.map { series ->
            async {
                val key = account + "#" + series.seriesId
                val cached = synchronized(episodeCache) { episodeCache[key] }
                    ?.takeIf { nowMs - it.atMs < NEW_EPISODES_TTL_MS }
                val episodes = if (cached != null) {
                    cached.episodes
                } else {
                    val fetched = gate.withPermit { withTimeoutOrNull(8_000) { listingOf(series.seriesId) } }
                    synchronized(episodeCache) {
                        if (episodeCacheAccount == account) episodeCache[key] = CachedListing(nowMs, fetched)
                    }
                    fetched
                }
                if (episodes == null) return@async null
                cardFor(series, episodes, primaryLabel, nowMs)
            }
        }.awaitAll().filterNotNull()
        if (cards.isEmpty()) null else HomeRow(HomeRow.Kind.NEW_EPISODES, cards)
    }

    private suspend fun listingOf(seriesId: Int): List<ListedEpisode>? {
        val info = repository.fetchSeriesInfo(seriesId) as? Resource.Success ?: return null
        return info.data.episodes?.values?.flatten().orEmpty()
            .sortedWith(compareBy({ it.season }, { it.episodeNum }))
            .map { ListedEpisode(it.season, it.episodeNum, it.title.orEmpty(), it.added?.toLongOrNull() ?: 0L) }
    }

    private suspend fun cardFor(
        series: SeriesEntity,
        episodes: List<ListedEpisode>,
        primaryLabel: String,
        nowMs: Long
    ): HomeCard? {
        if (episodes.isEmpty()) return null
        val seen = db.episodeWatchedDao().getForSeries(series.seriesId)
            .filter { it.watchedAt > 0 || it.watchedMs > 0 }
            .map { it.season to it.episode }.toSet()
        val last = seen.maxWithOrNull(compareBy({ it.first }, { it.second }))
        val since = if (series.lastWatchedAt > 0) series.lastWatchedAt / 1000 else (nowMs - 14L * 86_400_000L) / 1000
        val fresh = episodes.filter { ep ->
            (ep.season to ep.episodeNum) !in seen &&
                (last == null || ep.season > last.first || (ep.season == last.first && ep.episodeNum > last.second)) &&
                ep.addedSec > since
        }
        val first = fresh.firstOrNull() ?: return null
        val hit = SearchHit.Series(series, null, primaryLabel)
        val more = if (fresh.size > 1) " · ${fresh.size} new" else ""
        return HomeCard(SearchHit.Episode(hit, first.season, first.episodeNum, first.title), line = "S${first.season}E${first.episodeNum}$more")
    }
}
