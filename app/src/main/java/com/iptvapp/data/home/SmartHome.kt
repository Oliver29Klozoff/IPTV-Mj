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
 * set (a channel's current show, an episode code); [progress] is 0–100 for something started.
 * [nextTitle] and [nextStartMs] are a channel's next show, on rows that show it (Top Favorites). */
data class HomeCard(
    val hit: SearchHit,
    val line: String? = null,
    val progress: Int? = null,
    val nextTitle: String? = null,
    val nextStartMs: Long? = null,
)

/**
 * One row. [kind] is set for the Smart Home rows MKTV has always built; rows added since, and the
 * ones people make (HomeLayout), have none. [order] is the row's place in the layout, so a row that
 * arrives later (New Episodes) slots into it. [emptyText] is shown when a row is kept while empty.
 * [customize] is the "Customize Home" row at the end.
 */
data class HomeRow(
    val kind: Kind?,
    val cards: List<HomeCard>,
    val id: String = kind?.builtin?.id ?: "",
    val title: String = kind?.title ?: "",
    val style: HomeLayout.Style = kind?.builtin?.style ?: HomeLayout.Style.WIDE,
    val order: Int = kind?.ordinal ?: 0,
    val emptyText: String? = null,
    val customize: Boolean = false,
) {
    enum class Kind(val title: String, val wide: Boolean) {
        CONTINUE("Continue Watching", false),
        ON_NOW("On Now", true),
        FAVORITES("Favorite Channels", true),
        RECENT("Recently Watched", true),
        STARTING_SOON("Starting Soon", true),
        NEW_EPISODES("New Episodes", false),
        RECORDINGS("Recordings", true),
        RECOMMENDED("Recommended for You", false);

        val builtin: HomeLayout.Builtin get() = HomeLayout.Builtin.valueOf(name)
    }

    /** 16:9 cards (channels, shows, recordings) rather than posters. */
    val wide get() = style != HomeLayout.Style.POSTER
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
        /** The Customize Home row at the end of Home. */
        const val CUSTOMIZE_ID = "customize"
        /** How far ahead Starting Soon looks. */
        const val STARTING_SOON_MS = 2 * 60 * 60_000L
        /** How far ahead the stored guide is read for a channel's next show. */
        private const val NEXT_MS = 4 * 60 * 60_000L
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

    /** The saved Home layout for TV ([tv]) or phone; the shipped one until someone customizes it. */
    suspend fun layout(tv: Boolean): HomeLayout.Layout = HomeLayout.parse(prefs.homeLayoutJson(tv).first())

    /**
     * The rows [layout] shows, in its order, ending with the Customize Home row. Only rows that
     * show are built. New Episodes asks providers, so it is left to [newEpisodes]; here it is only
     * a place-holder when it is kept while empty.
     */
    suspend fun rows(
        nowMs: Long = System.currentTimeMillis(),
        layout: HomeLayout.Layout = HomeLayout.default()
    ): List<HomeRow> = coroutineScope {
        val visible = layout.sections.withIndex().filter { it.value.visible }
        val shown = visible.mapNotNull { it.value.builtin }.toSet()
        fun shows(b: HomeLayout.Builtin) = b in shown
        val primaryLabel = prefs.serverNickname.first().ifBlank { "Primary" }
        val enabled = prefs.enabledExtraServerIndices.first()

        // Rows people made: their channels, films and series, looked up by id or one bounded category read.
        val custom = visible.filter { it.value.isCustom }
            .associate { (_, s) -> s.id to async { resolve(s, enabled) } }
        val topRow = if (shows(HomeLayout.Builtin.TOP_FAVORITES)) async { topFavorites(enabled, nowMs) } else null

        val needShelf = shows(HomeLayout.Builtin.ON_NOW) || shows(HomeLayout.Builtin.STARTING_SOON) ||
            shows(HomeLayout.Builtin.FAVORITES) || shows(HomeLayout.Builtin.RECENT)
        val shelf = if (needShelf) shelf() else Shelf(emptyList(), emptyList(), emptyList())
        val favorites = shelf.favorites
        val otherFavorites = shelf.otherFavorites
        val recent = shelf.recent
        val guideChannels = shelf.guideChannels
        val guideOthers = shelf.guideOthers

        val continueRow = if (shows(HomeLayout.Builtin.CONTINUE)) async { continueWatching(primaryLabel) } else null
        val recordings = if (shows(HomeLayout.Builtin.RECORDINGS)) async { db.recordingDao().getForHome().map { HomeCard(SearchHit.Recording(it)) } } else null
        val recommended = if (shows(HomeLayout.Builtin.RECOMMENDED)) async { recommended(primaryLabel) } else null
        val recentMovies = if (shows(HomeLayout.Builtin.RECENT_MOVIES)) async {
            db.vodDao().getRecentlyAddedVod().first().take(ROW_LIMIT).map { movieCard(it, primaryLabel) }
        } else null
        val favoriteMovies = if (shows(HomeLayout.Builtin.FAVORITE_MOVIES)) async {
            db.vodDao().getFavoriteVodLimited(ROW_LIMIT).map { movieCard(it, primaryLabel) }
        } else null
        val favoriteSeries = if (shows(HomeLayout.Builtin.FAVORITE_SERIES)) async {
            db.seriesDao().getFavoriteSeriesLimited(ROW_LIMIT).map { HomeCard(SearchHit.Series(it, null, primaryLabel)) }
        } else null

        val resolved = custom.mapValues { it.value.await() }
        val top = topRow?.await().orEmpty()
        // One guide read per provider for every channel a row shows (On Now, Starting Soon, the
        // current show under a channel card), all from the stored guide.
        val pickedChannels = resolved.values.flatMap { it.channels } + top
        val customPrimary = pickedChannels.mapNotNull { it.first?.streamId }
        val customOthers = pickedChannels.mapNotNull { it.second }
        val primaryIds = (guideChannels.map { it.streamId } + customPrimary).distinct()
        val otherKeys = (guideOthers.map { it.serverIndex to it.streamId } + customOthers.map { it.serverIndex to it.streamId }).distinct()
        val guide = if (primaryIds.isEmpty() && otherKeys.isEmpty()) emptyList() else {
            val primaryGuide = async { programs(primaryIds, -1, nowMs) }
            val otherGuide = async {
                otherKeys.groupBy({ it.first }, { it.second }).flatMap { (idx, ids) -> programs(ids, idx, nowMs) }
            }
            primaryGuide.await() + otherGuide.await()
        }
        val nowByChannel = guide.filter { ms(it.startTimestamp) <= nowMs && ms(it.stopTimestamp) > nowMs }
            .associateBy { it.serverIndex to it.streamId }
        fun nowTitle(serverIndex: Int, streamId: Int) = nowByChannel[serverIndex to streamId]?.title
        // The first show after the one on now, per channel.
        val nextByChannel = if (top.isEmpty()) emptyMap() else guide
            .filter { e -> ms(e.startTimestamp) > nowMs && nowByChannel[e.serverIndex to e.streamId]?.let { ms(e.startTimestamp) >= ms(it.stopTimestamp) } != false }
            .groupBy { it.serverIndex to it.streamId }
            .mapValues { (_, airings) -> airings.minBy { ms(it.startTimestamp) } }

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

        val onNow = if (!shows(HomeLayout.Builtin.ON_NOW)) emptyList() else nowByChannel.values
            .sortedBy { channelOrder(it, guideChannels, guideOthers) }
            .mapNotNull { program(it, SearchHit.Kind.ON_NOW) }.take(ROW_LIMIT)
        // The next show on each channel, starting soon (not already on).
        val startingSoon = if (!shows(HomeLayout.Builtin.STARTING_SOON)) emptyList() else guide
            .filter { ms(it.startTimestamp) > nowMs && ms(it.startTimestamp) <= nowMs + STARTING_SOON_MS }
            .groupBy { it.serverIndex to it.streamId }.values.map { airings -> airings.minBy { ms(it.startTimestamp) } }
            .sortedBy { ms(it.startTimestamp) }
            .mapNotNull { program(it, SearchHit.Kind.UPCOMING) }.take(ROW_LIMIT)

        fun channelCard(c: ChannelEntity?, m: MergedChannelEntity?): HomeCard = if (c != null) {
            HomeCard(SearchHit.Channel(c, null, primaryLabel), line = nowTitle(-1, c.streamId))
        } else {
            HomeCard(SearchHit.Channel(null, m!!, m.serverNickname), line = nowTitle(m.serverIndex, m.streamId))
        }
        val favoriteCards = favorites.map { channelCard(it, null) } + otherFavorites.map { channelCard(null, it) }
        val topCards = top.map { (c, m) ->
            val next = nextByChannel[(c?.let { -1 } ?: m!!.serverIndex) to (c?.streamId ?: m!!.streamId)]
            channelCard(c, m).copy(nextTitle = next?.title, nextStartMs = next?.let { ms(it.startTimestamp) })
        }
        val recentCards = recent.take(ROW_LIMIT).map { channelCard(it, null) }

        val builtinCards: Map<HomeLayout.Builtin, List<HomeCard>> = mapOf(
            HomeLayout.Builtin.TOP_FAVORITES to topCards,
            HomeLayout.Builtin.CONTINUE to (continueRow?.await() ?: emptyList()),
            HomeLayout.Builtin.ON_NOW to onNow,
            HomeLayout.Builtin.FAVORITES to favoriteCards.take(ROW_LIMIT),
            HomeLayout.Builtin.RECENT to recentCards,
            HomeLayout.Builtin.STARTING_SOON to startingSoon,
            HomeLayout.Builtin.RECORDINGS to (recordings?.await() ?: emptyList()),
            HomeLayout.Builtin.RECOMMENDED to (recommended?.await() ?: emptyList()),
            HomeLayout.Builtin.RECENT_MOVIES to (recentMovies?.await() ?: emptyList()),
            HomeLayout.Builtin.FAVORITE_MOVIES to (favoriteMovies?.await() ?: emptyList()),
            HomeLayout.Builtin.FAVORITE_SERIES to (favoriteSeries?.await() ?: emptyList()),
        )

        val out = ArrayList<HomeRow>()
        for ((order, s) in visible) {
            val b = s.builtin
            val cards = when {
                b == HomeLayout.Builtin.NEW_EPISODES -> emptyList()
                b != null -> builtinCards[b].orEmpty()
                else -> resolved[s.id]?.let { r ->
                    r.channels.map { channelCard(it.first, it.second) } +
                        r.movies.map { movieCard(it, primaryLabel) } +
                        r.series.map { HomeCard(SearchHit.Series(it, null, primaryLabel)) }
                }.orEmpty()
            }
            if (cards.isEmpty() && !s.showWhenEmpty) continue
            out += rowFor(s, order, cards)
        }
        out += HomeRow(null, emptyList(), id = CUSTOMIZE_ID, title = "Customize Home", order = Int.MAX_VALUE, customize = true)
        out
    }

    private fun rowFor(s: HomeLayout.Section, order: Int, cards: List<HomeCard>) = HomeRow(
        kind = HomeRow.Kind.entries.firstOrNull { it.builtin == s.builtin },
        cards = cards,
        id = s.id,
        title = s.displayTitle,
        style = s.style,
        order = order,
        emptyText = if (cards.isEmpty()) s.emptyText else null,
    )

    private fun movieCard(vod: com.iptvapp.data.local.entities.VodEntity, primaryLabel: String) =
        HomeCard(SearchHit.Movie(vod, null, primaryLabel), progress = percent(vod.watchedMs, vod.durationMs))

    private class Resolved(
        val channels: List<Pair<ChannelEntity?, MergedChannelEntity?>> = emptyList(),
        val movies: List<com.iptvapp.data.local.entities.VodEntity> = emptyList(),
        val series: List<SeriesEntity> = emptyList(),
    )

    /**
     * A row someone made, from the catalog as it is now: picked items in their chosen order
     * (anything no longer there is skipped, the rest stay), or one category, bounded. An item on
     * a provider that is switched off is skipped too.
     */
    private suspend fun resolve(s: HomeLayout.Section, enabled: Set<Int>): Resolved {
        val f = s.filter
        return when (s.contentType) {
            HomeLayout.Content.CHANNELS -> when {
                f?.gone == true -> Resolved()
                f != null && f.serverIndex == -1 ->
                    Resolved(channels = f.categoryId?.let { db.channelDao().getChannelsByCategoryLimited(it, ROW_LIMIT) }.orEmpty().map { it to null })
                f != null -> if (f.serverIndex !in enabled) Resolved() else
                    Resolved(channels = db.mergedChannelDao().getByServerAndCategoryLimited(f.serverIndex, f.categoryId, ROW_LIMIT).map { null to it })
                else -> Resolved(channels = channelsOf(s.items, enabled))
            }
            HomeLayout.Content.MOVIES -> when {
                f?.gone == true -> Resolved()
                f != null -> Resolved(movies = f.categoryId?.let { db.vodDao().getVodByCategoryLimited(it, ROW_LIMIT) }.orEmpty())
                else -> {
                    val byId = s.items.map { it.id }.chunked(500).flatMap { db.vodDao().getVodByIds(it) }.associateBy { it.streamId }
                    Resolved(movies = s.items.mapNotNull { byId[it.id] })
                }
            }
            HomeLayout.Content.SERIES -> when {
                f?.gone == true -> Resolved()
                f != null -> Resolved(series = f.categoryId?.let { db.seriesDao().getSeriesByCategoryLimited(it, ROW_LIMIT) }.orEmpty())
                else -> {
                    val byId = s.items.map { it.id }.chunked(500).flatMap { db.seriesDao().getSeriesByIds(it) }
                        .filter { !it.isHidden }.associateBy { it.seriesId }
                    Resolved(series = s.items.mapNotNull { byId[it.id] })
                }
            }
            else -> Resolved()
        }
    }

    /**
     * Picked channels in [items]' order, read by id: anything no longer in the catalog, hidden,
     * or on a provider that is switched off is skipped, the rest stay.
     */
    private suspend fun channelsOf(items: List<HomeLayout.ItemRef>, enabled: Set<Int>): List<Pair<ChannelEntity?, MergedChannelEntity?>> {
        val primary = items.filter { it.serverIndex == -1 }.map { it.id }.chunked(500)
            .flatMap { db.channelDao().getChannelsByIds(it) }.filter { !it.isHidden }.associateBy { it.streamId }
        val others = items.filter { it.serverIndex >= 0 && it.serverIndex in enabled }.groupBy { it.serverIndex }
            .flatMap { (idx, list) -> list.map { it.id }.chunked(500).flatMap { db.mergedChannelDao().getByServerAndIds(idx, it) } }
            .filter { !it.isHidden }.associateBy { it.serverIndex to it.streamId }
        return items.mapNotNull { i ->
            if (i.serverIndex == -1) primary[i.id]?.let { it to null } else others[i.serverIndex to i.id]?.let { null to it }
        }
    }

    /**
     * Top Favorites as the row shows them: the picked channels in their saved order, or with
     * Most Watched, by the last [TopFavorites.WINDOW_DAYS] days of real viewing. Local reads only.
     */
    private suspend fun topFavorites(enabled: Set<Int>, nowMs: Long): List<Pair<ChannelEntity?, MergedChannelEntity?>> {
        val state = TopFavoritesStore.load(prefs)
        if (state.items.isEmpty()) return emptyList()
        val order = if (state.sort != TopFavorites.Sort.MOST_WATCHED) state.items else {
            val since = com.iptvapp.util.WatchStats.day(nowMs - TopFavorites.WINDOW_DAYS * 86_400_000L)
            val minutes = TopFavorites.watchedMinutes(db.watchLogDao().since(since))
            // Copies of one channel in a confirmed Smart Channel group share their viewing time.
            val groups = db.smartChannelDao().groups()
                .filter { it.reviewState == com.iptvapp.util.SmartChannels.VERIFIED || it.reviewState == com.iptvapp.util.SmartChannels.MANUAL }
                .map { it.groupId }.toSet()
            val groupOf = db.smartChannelDao().members().filter { it.groupId in groups }
                .associate { (it.serverIndex to it.streamId) to it.groupId }
            TopFavorites.rank(state.items, minutes, groupOf)
        }
        return channelsOf(order, enabled)
    }

    /** [rows] with [row] in its place (New Episodes arrives after the rest). */
    fun withRow(rows: List<HomeRow>, row: HomeRow): List<HomeRow> =
        (rows.filterNot { it.id == row.id } + row).sortedBy { it.order }

    // Favorites in their own order, then recently watched; other providers' favorites after.
    private fun channelOrder(e: EpgEntity, primary: List<ChannelEntity>, others: List<MergedChannelEntity>): Int {
        if (e.serverIndex == -1) return primary.indexOfFirst { it.streamId == e.streamId }.let { if (it < 0) Int.MAX_VALUE else it }
        return primary.size + others.indexOfFirst { it.serverIndex == e.serverIndex && it.streamId == e.streamId }.let { if (it < 0) 0 else it }
    }

    private suspend fun programs(ids: List<Int>, serverIndex: Int, nowMs: Long): List<EpgEntity> {
        val toMs = nowMs + NEXT_MS
        return ids.chunked(500).flatMap { db.epgDao().getProgramsBetween(it, serverIndex, nowMs, toMs) }
    }

    // Movies and series partway through, most recently watched first.
    private suspend fun continueWatching(primaryLabel: String): List<HomeCard> {
        val movies = db.vodDao().getInProgressVodRecent(ROW_LIMIT).map { vod ->
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
    suspend fun newEpisodes(
        nowMs: Long = System.currentTimeMillis(),
        layout: HomeLayout.Layout = HomeLayout.default()
    ): HomeRow? = coroutineScope {
        // Hidden: no provider is asked at all.
        val order = layout.sections.indexOfFirst { it.id == HomeLayout.Builtin.NEW_EPISODES.id }
        val section = layout.sections.getOrNull(order)?.takeIf { it.visible } ?: return@coroutineScope null
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
        if (cards.isEmpty()) null else rowFor(section, order, cards)
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
