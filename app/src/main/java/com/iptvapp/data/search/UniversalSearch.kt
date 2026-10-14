package com.iptvapp.data.search

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.MergedSeriesEntity
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.util.CategoryLanguage
import com.iptvapp.util.MovieSearch
import com.iptvapp.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One result of [UniversalSearch]. [title] is what's matched and shown; [provider] names the
 * provider for anything that has one. */
sealed class SearchHit {
    abstract val title: String
    abstract val provider: String?
    abstract val kind: Kind

    enum class Kind(val label: String) {
        CHANNEL("Live"), ON_NOW("On now"), MOVIE("Movie"), SERIES("Series"), EPISODE("Episode"),
        RECORDING("Recording"), CATCH_UP("Catch-up"), UPCOMING("Upcoming")
    }

    /** A live channel: [channel] on the main provider, or [merged] on another. */
    data class Channel(val channel: ChannelEntity?, val merged: MergedChannelEntity?, override val provider: String?) : SearchHit() {
        override val title get() = channel?.name ?: merged?.name.orEmpty()
        override val kind get() = Kind.CHANNEL
        val icon get() = channel?.streamIcon ?: merged?.streamIcon
    }

    /** A guide programme: on now, upcoming, or past and replayable (catch-up, main provider only). */
    data class Program(
        val channel: ChannelEntity?, val merged: MergedChannelEntity?, val programTitle: String,
        val startMs: Long, val stopMs: Long, override val kind: Kind, override val provider: String?
    ) : SearchHit() {
        override val title get() = programTitle
        val channelName get() = channel?.name ?: merged?.name.orEmpty()
        val icon get() = channel?.streamIcon ?: merged?.streamIcon
    }

    data class Movie(val vod: VodEntity?, val merged: MergedVodEntity?, override val provider: String?) : SearchHit() {
        override val title get() = vod?.name ?: merged?.name.orEmpty()
        override val kind get() = Kind.MOVIE
        val poster get() = vod?.streamIcon ?: merged?.streamIcon
    }

    data class Series(val series: SeriesEntity?, val merged: MergedSeriesEntity?, override val provider: String?) : SearchHit() {
        override val title get() = series?.name ?: merged?.name.orEmpty()
        override val kind get() = Kind.SERIES
        val cover get() = series?.cover ?: merged?.cover
    }

    /** An episode inside a series that matched (episodes aren't stored; see [UniversalSearch.episodes]). */
    data class Episode(
        val series: Series, val season: Int, val episode: Int, val episodeTitle: String
    ) : SearchHit() {
        override val title get() = episodeTitle
        override val provider get() = series.provider
        override val kind get() = Kind.EPISODE
    }

    data class Recording(val recording: RecordingEntity) : SearchHit() {
        override val title get() = recording.programTitle ?: recording.channelName
        override val provider: String? get() = null
        override val kind get() = Kind.RECORDING
    }
}

/**
 * One search across everything MKTV knows, on every configured provider: live channels, the
 * guide (on now, upcoming, and replayable past programmes), movies, series, episodes and
 * recordings, best match first.
 *
 * Everything but episodes is a local lookup. Episodes aren't stored — a series' episodes come
 * from its provider when it's opened — so [episodes] is a second step: for a query that starts
 * with a series' name ("breaking bad s5e14", "the office dinner party") it asks that series'
 * provider for its episodes and matches the rest.
 */
@Singleton
class UniversalSearch @Inject constructor(
    private val repository: XtreamRepository,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) {
    companion object {
        /** How far back catch-up looks (the longest archive a provider commonly keeps). */
        const val CATCH_UP_DAYS = 7L
        /** How far ahead upcoming looks. */
        const val UPCOMING_DAYS = 3L
        private const val MAX_RESULTS = 300

        // Equal scores: the kind most likely meant first.
        private val kindOrder = listOf(
            SearchHit.Kind.CHANNEL, SearchHit.Kind.ON_NOW, SearchHit.Kind.MOVIE, SearchHit.Kind.SERIES,
            SearchHit.Kind.EPISODE, SearchHit.Kind.RECORDING, SearchHit.Kind.CATCH_UP, SearchHit.Kind.UPCOMING
        )

        /** [hits] best first: closest title, then kind, then favorites, then shorter titles. */
        fun rank(hits: List<SearchHit>, query: String): List<SearchHit> {
            val q = MovieSearch.normalizeTitle(query).lowercase()
            return hits.map { hit ->
                val t = MovieSearch.normalizeTitle(hit.title).lowercase()
                // An episode is only found when the query named its series and the episode
                // (see episodes): it is what was asked for, whatever its own title.
                val score = if (hit is SearchHit.Episode) 0 else MovieSearch.score(t, q)
                Ranked(hit, score, kindOrder.indexOf(hit.kind), if (isFavorite(hit)) 0 else 1, t.length)
            }.sortedWith(compareBy<Ranked>({ it.score }, { it.kind }, { it.favorite }, { it.length }))
                .map { it.hit }
        }

        private data class Ranked(val hit: SearchHit, val score: Int, val kind: Int, val favorite: Int, val length: Int)

        private fun isFavorite(hit: SearchHit) = when (hit) {
            is SearchHit.Channel -> hit.channel?.isFavorite == true || hit.merged?.isFavorite == true
            is SearchHit.Movie -> hit.vod?.isFavorite == true || hit.merged?.isFavorite == true
            is SearchHit.Series -> hit.series?.isFavorite == true || hit.merged?.isFavorite == true
            else -> false
        }

        private fun ms(t: Long) = if (t < 100_000_000_000L) t * 1000L else t

        /** Whether [program] on [channel] can be replayed (same rule as the guide's). */
        fun canReplay(channel: ChannelEntity, program: EpgEntity, nowMs: Long): Boolean {
            if (channel.tvArchive != 1 || ms(program.stopTimestamp) > nowMs) return false
            if (program.hasArchive == 1) return true
            val days = channel.tvArchiveDuration.takeIf { it > 0 } ?: 3
            return ms(program.startTimestamp) >= nowMs - days * 86_400_000L
        }

        // "s5e14", "s05 e14", "5x14" at the end of a query.
        private val episodeCode = Regex("""^(.*?)\s*(?:s(\d{1,2})\s*e(\d{1,3})|(\d{1,2})x(\d{1,3}))$""", RegexOption.IGNORE_CASE)

        /** What a query asks of a series it starts with: its rest as a title, or a season and episode. */
        data class EpisodeAsk(val seriesPart: String, val titlePart: String?, val season: Int?, val episode: Int?)

        fun parseEpisodeAsk(query: String): EpisodeAsk? {
            val q = query.trim()
            episodeCode.matchEntire(q)?.let { m ->
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) return null
                val season = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toInt()
                val episode = (m.groupValues[3].ifEmpty { m.groupValues[5] }).toInt()
                return EpisodeAsk(name, null, season, episode)
            }
            return null
        }
    }

    /**
     * Everything local that matches [query], ranked. Programmes are kept one per channel and
     * title: the airing on now, else the next one, else the latest replayable one.
     */
    suspend fun search(query: String, nowMs: Long = System.currentTimeMillis()): List<SearchHit> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        val primaryLabel = prefs.serverNickname.first().ifBlank { "Primary" }
        val englishOnly = prefs.englishOnlyMovies.first()

        val channels = async { channels(q, primaryLabel) }
        val programs = async { if (q.length >= 3) programs(q, nowMs, primaryLabel) else emptyList() }
        val movies = async { movies(q, primaryLabel, englishOnly) }
        val series = async { series(q, primaryLabel, englishOnly) }
        val recordings = async { db.recordingDao().search(q).map { SearchHit.Recording(it) } }
        val all = listOf(channels, programs, movies, series, recordings).awaitAll().flatten()
        withContext(Dispatchers.Default) { rank(all, q).take(MAX_RESULTS) }
    }

    private suspend fun channels(q: String, primaryLabel: String): List<SearchHit> {
        val primary = repository.searchChannels(q).first().map { SearchHit.Channel(it, null, primaryLabel) }
        val merged = repository.searchMergedChannels(q).first().map { SearchHit.Channel(null, it, it.serverNickname) }
        return primary + merged
    }

    private suspend fun programs(q: String, nowMs: Long, primaryLabel: String): List<SearchHit> {
        val nowSec = nowMs / 1000
        val rows = db.epgDao().searchProgramTitles(q, nowSec, nowSec + UPCOMING_DAYS * 86_400) +
            db.epgDao().searchReplayableProgramTitles(q, nowSec, nowSec - CATCH_UP_DAYS * 86_400)
        if (rows.isEmpty()) return emptyList()
        val primary = rows.filter { it.serverIndex == -1 }.map { it.streamId }.distinct().chunked(900)
            .flatMap { db.channelDao().getChannelsByIds(it) }.associateBy { it.streamId }
        val merged = HashMap<Pair<Int, Int>, MergedChannelEntity>()
        rows.filter { it.serverIndex != -1 }.groupBy { it.serverIndex }.forEach { (idx, list) ->
            list.map { it.streamId }.distinct().chunked(900).forEach { ids ->
                db.mergedChannelDao().getByServerAndIds(idx, ids).forEach { merged[idx to it.streamId] = it }
            }
        }
        val best = LinkedHashMap<Triple<Int, Int, String>, SearchHit.Program>()
        for (row in rows) {
            val channel = if (row.serverIndex == -1) primary[row.streamId] else null
            val other = if (row.serverIndex != -1) merged[row.serverIndex to row.streamId] else null
            if (channel == null && other == null) continue
            if (channel?.isHidden == true || other?.isHidden == true) continue
            val start = ms(row.startTimestamp)
            val stop = ms(row.stopTimestamp)
            val kind = when {
                start <= nowMs && stop > nowMs -> SearchHit.Kind.ON_NOW
                start > nowMs -> SearchHit.Kind.UPCOMING
                channel != null && canReplay(channel, row, nowMs) -> SearchHit.Kind.CATCH_UP
                else -> continue
            }
            val hit = SearchHit.Program(channel, other, row.title, start, stop, kind, other?.serverNickname ?: primaryLabel)
            val key = Triple(row.serverIndex, row.streamId, row.title.trim().lowercase())
            val kept = best[key]
            if (kept == null || better(hit, kept)) best[key] = hit
        }
        return best.values.toList()
    }

    // On now beats upcoming beats catch-up; the sooner upcoming and the latest catch-up win.
    private fun better(a: SearchHit.Program, b: SearchHit.Program): Boolean {
        val order = listOf(SearchHit.Kind.ON_NOW, SearchHit.Kind.UPCOMING, SearchHit.Kind.CATCH_UP)
        val ka = order.indexOf(a.kind)
        val kb = order.indexOf(b.kind)
        if (ka != kb) return ka < kb
        return if (a.kind == SearchHit.Kind.CATCH_UP) a.startMs > b.startMs else a.startMs < b.startMs
    }

    private suspend fun movies(q: String, primaryLabel: String, englishOnly: Boolean): List<SearchHit> {
        val filter = XtreamRepository.MovieSearchFilter(
            categoryIds = if (englishOnly) englishCategoryIds(repository.getVodCategories().first()) else null,
            otherCategoryNames = if (englishOnly) repository.otherProviderMovieCategoryNames().filter { CategoryLanguage.isEnglish(it) } else null
        )
        val (primary, merged) = repository.searchMoviesEverywhere(q, filter)
        return primary.map { SearchHit.Movie(it, null, primaryLabel) } + merged.map { SearchHit.Movie(null, it, it.serverNickname) }
    }

    private suspend fun series(q: String, primaryLabel: String, englishOnly: Boolean): List<SearchHit> {
        val allowed = if (englishOnly) englishCategoryIds(repository.getSeriesCategories().first()) else null
        // A query naming an episode ("breaking bad s5e14") finds its series by the name part.
        val name = parseEpisodeAsk(q)?.seriesPart ?: q
        val primary = repository.searchSeries(name).first()
            .filter { allowed == null || it.categoryId in allowed }
            .map { SearchHit.Series(it, null, primaryLabel) }
        val merged = repository.searchMergedSeries(name).first()
            .filter { !englishOnly || CategoryLanguage.isEnglish(it.categoryName) }
            .map { SearchHit.Series(null, it, it.serverNickname) }
        return primary + merged
    }

    /** Series on any provider whose bare name is exactly [name] (normalized, lowercase), under
     * the English-only setting like [series]. */
    private suspend fun seriesNamed(name: String): List<SearchHit.Series> {
        fun same(title: String) = MovieSearch.normalizeTitle(title).lowercase() == name
        val primaryLabel = prefs.serverNickname.first().ifBlank { "Primary" }
        val englishOnly = prefs.englishOnlyMovies.first()
        val allowed = if (englishOnly) englishCategoryIds(repository.getSeriesCategories().first()) else null
        return repository.searchSeries(name).first()
            .filter { same(it.name) && (allowed == null || it.categoryId in allowed) }
            .map { SearchHit.Series(it, null, primaryLabel) } +
            repository.searchMergedSeries(name).first()
                .filter { same(it.name) && (!englishOnly || CategoryLanguage.isEnglish(it.categoryName)) }
                .map { SearchHit.Series(null, it, it.serverNickname) }
    }

    // Null when no category is English, so the setting doesn't hide everything on a provider
    // whose names it can't read (the Movies grid does the same).
    private fun englishCategoryIds(categories: List<com.iptvapp.data.local.entities.CategoryEntity>): Set<String>? =
        categories.filter { CategoryLanguage.isEnglish(it.categoryName) }.map { it.categoryId }.toSet().ifEmpty { null }

    /**
     * Episodes for [query] within the series [ranked] found: a series whose name the query
     * starts with, and the rest is a season/episode ("s5e14", "5x14") or words from an episode
     * title. Asks at most [maxSeries] series' providers, each within [timeoutMs].
     */
    suspend fun episodes(query: String, ranked: List<SearchHit>, maxSeries: Int = 2, timeoutMs: Long = 5_000): List<SearchHit.Episode> {
        val q = query.trim()
        val ask = parseEpisodeAsk(q)
        val normalizedQuery = MovieSearch.normalizeTitle(ask?.seriesPart ?: q).lowercase()
        var candidates = ranked.filterIsInstance<SearchHit.Series>().mapNotNull { s ->
            val name = MovieSearch.normalizeTitle(s.title).lowercase()
            when {
                ask != null && normalizedQuery == name -> s to ask
                ask == null && normalizedQuery.startsWith("$name ") ->
                    s to EpisodeAsk(name, normalizedQuery.removePrefix("$name ").trim(), null, null)
                else -> null
            }
        }.take(maxSeries)
        // "world of wonders dinner": the series search wanted every word in the name, so it
        // found nothing. Its name is then the longest run of leading words that is one.
        val words = normalizedQuery.split(' ').filter { it.isNotEmpty() }
        if (candidates.isEmpty() && ask == null && words.size >= 2) {
            for (k in words.size - 1 downTo 1) {
                val name = words.take(k).joinToString(" ")
                val found = seriesNamed(name)
                if (found.isNotEmpty()) {
                    val rest = words.drop(k).joinToString(" ")
                    candidates = found.take(maxSeries).map { it to EpisodeAsk(name, rest, null, null) }
                    break
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        return coroutineScope {
            candidates.map { (series, want) ->
                async {
                    val info = withTimeoutOrNull(timeoutMs) {
                        val merged = series.merged
                        if (merged != null) repository.fetchMergedSeriesInfo(merged.serverIndex, merged.seriesId)
                        else repository.fetchSeriesInfo(series.series?.seriesId ?: return@withTimeoutOrNull null)
                    }
                    val episodes = (info as? Resource.Success)?.data?.episodes?.values?.flatten().orEmpty()
                    episodes.filter { ep ->
                        when {
                            want.season != null -> ep.season == want.season && ep.episodeNum == want.episode
                            !want.titlePart.isNullOrBlank() -> MovieSearch.normalizeTitle(ep.title).lowercase().contains(want.titlePart)
                            else -> false
                        }
                    }.sortedWith(compareBy({ it.season }, { it.episodeNum })).take(20)
                        .map { SearchHit.Episode(series, it.season, it.episodeNum, it.title) }
                }
            }.awaitAll().flatten()
        }
    }
}
