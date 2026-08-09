package com.iptvapp.data.repository

import android.util.Base64
import com.iptvapp.data.api.*
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dao.ChannelUserData
import com.iptvapp.data.local.entities.*
import com.iptvapp.util.M3uParser
import com.iptvapp.util.Resource
import com.iptvapp.util.XmltvFetcher
import com.iptvapp.util.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamRepository @Inject constructor(
    private val api: XtreamApiService,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
    private val okHttpClient: OkHttpClient
) {
    private suspend fun creds() = prefs.credentials.first()

    private suspend fun urlBuilder(): XtreamUrlBuilder {
        val c = creds()
        return XtreamUrlBuilder(c.serverUrl, c.username, c.password)
    }

    suspend fun authenticate(serverUrl: String, username: String, password: String): Resource<XtreamAuthResponse> {
        val builder = XtreamUrlBuilder(serverUrl, username, password)
        return safeApiCall {
            val response = api.authenticate(builder.apiUrl(), username, password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val body = response.body() ?: throw Exception("Empty response from server")
            if (body.userInfo.status != "Active") throw Exception("Account is not active")
            prefs.saveCredentials(serverUrl, username, password)
            body
        }
    }

    suspend fun logout() {
        prefs.clearCredentials()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    /** Called right before switching primary TO a server that was previously configured as a
     * secondary/"other" provider — captures whatever favorites/folder assignments it already
     * had under merged_channels (keyed by streamId, since that server's channel ids don't
     * change just because its role did) so they can be re-applied as PRIMARY favorites once
     * fetchLiveStreams() populates the channels table for it. Without this, a provider's
     * favorites would silently reset to none the moment it became primary, even though it had
     * favorites a moment earlier as a secondary provider. */
    suspend fun capturePendingPrimaryFavoritesFrom(serverIndex: Int) {
        val favorites = db.mergedChannelDao().getUserData().filter { it.serverIndex == serverIndex && it.isFavorite }
        if (favorites.isEmpty()) return
        prefs.setPendingFavoriteChannelIds(favorites.map { it.streamId }.toSet())

        val folderRows = favorites.mapNotNull { it.favoriteFolderId }.distinct()
        if (folderRows.isNotEmpty()) {
            val folderNameById = db.favoriteFolderDao().getAll().first().associate { it.id to it.name }
            val keys = favorites.mapNotNull { fav ->
                fav.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> "${fav.streamId}|$name" } }
            }.toSet()
            if (keys.isNotEmpty()) prefs.setPendingPrimaryChannelFolders(keys)
        }
    }

    /** Applies any pending primary-favorite carryover captured by
     * capturePendingPrimaryFavoritesFrom, once this provider's channels actually exist in the
     * channels table to apply them to. Called from fetchLiveStreams() right after upserting. */
    private suspend fun applyPendingPrimaryFavorites() {
        val pendingIds = prefs.pendingFavoriteChannelIds.first()
        if (pendingIds.isNotEmpty()) {
            val existingIds = db.channelDao().getAllChannelIds().toSet()
            val ids = pendingIds.mapNotNull { it.toIntOrNull() }
            val remaining = ids.filter { it !in existingIds }
            ids.filter { it in existingIds }.forEach { db.channelDao().setFavorite(it, true) }
            prefs.setPendingFavoriteChannelIds(remaining.toSet())
        }

        val pendingFolders = prefs.pendingPrimaryChannelFolders.first()
        if (pendingFolders.isNotEmpty()) {
            val existingIds = db.channelDao().getAllChannelIds().toSet()
            val existingFolders = db.favoriteFolderDao().getAll().first()
            val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
            var nextOrder = existingFolders.size
            val remaining = pendingFolders.filter { key ->
                val parts = key.split("|")
                val streamId = parts.getOrNull(0)?.toIntOrNull()
                val folderName = parts.getOrNull(1)
                if (streamId != null && folderName != null && streamId in existingIds) {
                    var folderId = idByName[folderName]
                    if (folderId == null) {
                        folderId = db.favoriteFolderDao().insert(
                            com.iptvapp.data.local.entities.FavoriteFolderEntity(name = folderName, sortOrder = nextOrder++)
                        ).toInt()
                        idByName[folderName] = folderId
                    }
                    db.channelDao().setFavoriteFolder(streamId, folderId)
                    false
                } else true
            }.toSet()
            if (remaining != pendingFolders) prefs.setPendingPrimaryChannelFolders(remaining)
        }
    }

    /** Used when switching which configured server is the PRIMARY provider — clears only data
     * scoped to the old primary (its channels/categories/VOD/series/EPG/reliability/
     * recordings), never merged_channels or favorite_folders. Switching used to call
     * db.clearAllTables() unconditionally, silently wiping every OTHER provider's favorites/
     * folder assignments/pinned categories too, even though those have nothing to do with
     * which server is currently "primary" — merged-provider data is scoped by its own
     * serverIndex and stays valid across a primary-provider switch. Series/VOD/reliability
     * history for the OLD primary is still cleared here since those ids aren't portable to
     * whatever the new primary provider is anyway. */
    suspend fun clearPrimaryProviderData() = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM channels")
        sql.execSQL("DELETE FROM categories")
        sql.execSQL("DELETE FROM vod_streams")
        sql.execSQL("DELETE FROM series")
        sql.execSQL("DELETE FROM epg_entries WHERE serverIndex = -1")
        sql.execSQL("DELETE FROM channel_reliability")
        sql.execSQL("DELETE FROM recordings WHERE serverIndex = -1")
        sql.execSQL("DELETE FROM episode_watched")
    }

    suspend fun fetchLiveCategories(): Resource<List<Category>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getLiveCategories(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            val unnamed = list.count { it.categoryName.isNullOrBlank() }
            if (com.iptvapp.BuildConfig.DEBUG) android.util.Log.d("VodDiag", "Live category count: ${list.size}, unnamed: $unnamed")
            db.categoryDao().deleteCategoriesByType("live")
            db.categoryDao().upsertCategories(list.map {
                CategoryEntity(it.categoryId, it.categoryName, it.parentId, "live")
            })
            list
        }
    }

    fun getLiveCategories(): Flow<List<CategoryEntity>> =
        db.categoryDao().getCategoriesByType("live")

    suspend fun fetchLiveStreams(): Resource<List<LiveStream>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getLiveStreams(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            val userData = db.channelDao().getUserData().associateBy { it.streamId }
            db.channelDao().upsertChannels(list.map {
                val prev = userData[it.streamId]
                ChannelEntity(
                    streamId = it.streamId,
                    name = it.name,
                    streamIcon = it.streamIcon,
                    categoryId = it.categoryId,
                    epgChannelId = it.epgChannelId,
                    tvArchive = it.tvArchive,
                    num = it.num,
                    isFavorite = prev?.isFavorite ?: false,
                    lastWatched = prev?.lastWatched,
                    viewCount = prev?.viewCount ?: 0,
                    favOrder = prev?.favOrder ?: 0,
                    isHidden = prev?.isHidden ?: false,
                    favoriteFolderId = prev?.favoriteFolderId,
                    manualGenre = prev?.manualGenre
                )
            })
            prefs.setLastChannelsFetchTime(System.currentTimeMillis())
            applyPendingPrimaryFavorites()
            list
        }
    }

    suspend fun isChannelCacheStale(maxAgeMs: Long = 4 * 60 * 60 * 1000L): Boolean {
        val lastFetch = prefs.lastChannelsFetchTime.first()
        return lastFetch == 0L || System.currentTimeMillis() - lastFetch > maxAgeMs
    }

    fun getAllChannels(): Flow<List<ChannelEntity>> = db.channelDao().getAllChannels()

    suspend fun getChannelCount(): Int = db.channelDao().getCount()

    suspend fun getVodCount(): Int = db.vodDao().getCount()

    suspend fun getSeriesCount(): Int = db.seriesDao().getCount()

    suspend fun getNewestEpgStop(): Long? = db.epgDao().getNewestEpgStopTimestamp()

    fun getChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>> =
        db.channelDao().getChannelsByCategory(categoryId)

    fun searchChannels(query: String): Flow<List<ChannelEntity>> =
        db.channelDao().searchChannels(query)

    /** "What's airing" search result: one row per channel whose EPG has a program matching
     * [query] in its title/description, still airing or upcoming (never something already
     * ended). Resolves each matched (serverIndex, streamId) back to its real channel row —
     * primary via ChannelDao, merged/secondary via MergedChannelDao — skipping any match whose
     * channel no longer exists locally (e.g. removed from the provider's lineup since this
     * program's EPG was cached). */
    data class ProgramSearchMatch(
        val channel: ChannelEntity?,
        val mergedChannel: MergedChannelEntity?,
        val programTitle: String
    )

    suspend fun searchProgramsAcrossChannels(query: String): List<ProgramSearchMatch> {
        val matches = db.epgDao().searchProgramsAcrossChannels(query)
        return matches.mapNotNull { epg ->
            if (epg.serverIndex == -1) {
                db.channelDao().getChannelById(epg.streamId)?.let { ProgramSearchMatch(it, null, epg.title) }
            } else {
                db.mergedChannelDao().getByIndexAndId(epg.serverIndex, epg.streamId)?.let { ProgramSearchMatch(null, it, epg.title) }
            }
        }
    }

    fun getFavoriteChannels(): Flow<List<ChannelEntity>> =
        db.channelDao().getFavoriteChannels()

    fun getFavoriteLiveCategoryIds(): Flow<Set<String>> =
        prefs.favoriteLiveCategoryIds

    fun getRecentChannels(): Flow<List<ChannelEntity>> =
        db.channelDao().getRecentChannels()

    suspend fun getChannelById(streamId: Int) = db.channelDao().getChannelById(streamId)

    // Custom number takes priority — if the digits typed match someone's user-assigned number,
    // that's what they meant; only falls back to the provider's raw num when no custom number
    // matches (the common case for every channel that hasn't been given one).
    suspend fun getChannelByNumber(num: Int) =
        db.channelDao().getChannelByCustomNumber(num) ?: db.channelDao().getChannelByNumber(num)

    suspend fun setCustomChannelNumber(streamId: Int, customNum: Int?) =
        db.channelDao().setCustomNum(streamId, customNum)

    suspend fun isChannelFavorite(streamId: Int): Boolean {
        return db.channelDao().getChannelById(streamId)?.isFavorite ?: false
    }

    suspend fun toggleChannelFavorite(streamId: Int) {
        val ch = db.channelDao().getChannelById(streamId) ?: return
        val nowFavorite = !ch.isFavorite
        db.channelDao().setFavorite(streamId, nowFavorite)
        // Auto-assign a cable-style number to newly-favorited US channels — see
        // genreNumberBlockStart's kdoc for the block layout — and clear it again on unfavorite so
        // the number is free for reuse by the next favorite in that genre instead of climbing
        // forever. Scoped to US only: the vast majority of non-US categories already have small,
        // usable provider numbers.
        if (ch.customNum == null && nowFavorite && isUsCategory(ch.categoryId)) {
            val categoryName = db.categoryDao().getCategoryById(ch.categoryId ?: "")?.categoryName
            val blockStart = genreNumberBlockStart(categoryName)
            val blockEnd = blockStart + 99
            val next = (db.channelDao().getMaxCustomNumInRange(blockStart, blockEnd) ?: (blockStart - 1)) + 1
            db.channelDao().setCustomNum(streamId, next)
        } else if (!nowFavorite && ch.customNum != null) {
            db.channelDao().setCustomNum(streamId, null)
        }
    }

    // Same matching rules as HomeViewModel.isUsCategory (kept in sync manually — this lives here
    // too since the repository can't depend on a ViewModel, and toggleChannelFavorite needs it
    // for auto-numbering regardless of which screen the favorite came from).
    private suspend fun isUsCategory(categoryId: String?): Boolean {
        if (categoryId == null) return false
        val name = db.categoryDao().getCategoryById(categoryId)?.categoryName?.trim()?.uppercase()
            ?.replace(Regex("\\s*\\|\\s*"), "|") ?: return false
        return name.startsWith("US|") || name.contains("|US|") ||
            Regex("""(^|\|)USA\b""").containsMatchIn(name)
    }

    // Cable-style reserved blocks (100 numbers each) by genre, matched against the category name
    // using the same keyword taxonomy as GenreClassifier (Favorites' genre chips) — explicitly
    // requested layout: 2-99 Basic TV, 100s Movies, 200s Sports, 300s News, 400s Kids. Anything
    // that doesn't match one of those falls into a general 500s block rather than being refused a
    // number outright.
    private fun genreNumberBlockStart(categoryName: String?): Int {
        val name = categoryName?.lowercase() ?: return 500
        val movies = listOf("movie", "film", "cinema", "hbo", "showtime", "starz", "amc", "fx movie")
        val sports = listOf("sport", "espn", "nfl", "nba", "mlb", "nhl", "nascar", "tennis", "golf", "soccer", "football")
        val news = listOf("news", "cnn", "cnbc", "msnbc", "bbc", "fox news", "abc news", "nbc news")
        val kids = listOf("kid", "children", "child", "disney", "nickelodeon", "nick", "cartoon", "toon")
        return when {
            movies.any { name.contains(it) } -> 100
            sports.any { name.contains(it) } -> 200
            news.any { name.contains(it) } -> 300
            kids.any { name.contains(it) } -> 400
            else -> 2 // Basic TV / everything else, cable's traditional low-number range
        }
    }

    // One-time pass over channels favorited before the auto-numbering feature existed (v5.65) —
    // toggleChannelFavorite only assigns a number at the moment of favoriting, so anything already
    // favorited (including everything pulled in via Sync's Pull from Cloud) was sitting with no
    // customNum at all. Gated by PreferencesManager.favoriteNumbersBackfilled so this only ever
    // runs once; the caller (HomeViewModel.loadAll) checks that flag before calling this.
    // Batches per-genre counters in memory instead of calling getMaxCustomNumInRange per channel
    // (would be O(n) queries across n favorites, and races against itself within the same batch).
    suspend fun backfillFavoriteChannelNumbers() {
        val unnumbered = db.channelDao().getUnnumberedFavorites()
        if (unnumbered.isEmpty()) return
        val usOnly = unnumbered.filter { isUsCategory(it.categoryId) }
        val nextInBlock = mutableMapOf<Int, Int>()
        for (ch in usOnly) {
            val categoryName = db.categoryDao().getCategoryById(ch.categoryId ?: "")?.categoryName
            val blockStart = genreNumberBlockStart(categoryName)
            val blockEnd = blockStart + 99
            val floor = db.channelDao().getMaxCustomNumInRange(blockStart, blockEnd) ?: (blockStart - 1)
            val next = maxOf(nextInBlock[blockStart] ?: (floor + 1), floor + 1)
            db.channelDao().setCustomNum(ch.streamId, next)
            nextInBlock[blockStart] = next + 1
        }
    }

    suspend fun markChannelWatched(streamId: Int) {
        db.channelDao().updateLastWatched(streamId)
        db.channelDao().incrementViewCount(streamId)
    }

    suspend fun setChannelHidden(streamId: Int, hidden: Boolean) =
        db.channelDao().setHidden(streamId, hidden)

    fun getHiddenChannels(): Flow<List<ChannelEntity>> =
        db.channelDao().getHiddenChannels()

    suspend fun bulkSetFavorite(streamIds: List<Int>) =
        db.channelDao().bulkSetFavorite(streamIds)

    suspend fun bulkClearFavorite(streamIds: List<Int>) =
        db.channelDao().bulkClearFavorite(streamIds)

    suspend fun bulkHideChannels(streamIds: List<Int>) =
        db.channelDao().bulkSetHidden(streamIds)

    suspend fun bulkSetChannelManualGenre(streamIds: List<Int>, genre: String?) =
        db.channelDao().bulkSetManualGenre(streamIds, genre)

    fun getSimilarChannels(categoryId: String, excludeStreamId: Int): Flow<List<ChannelEntity>> =
        db.channelDao().getSimilarChannels(categoryId, excludeStreamId)

    suspend fun setLiveCategoryFavorite(categoryId: String, isFavorite: Boolean) {
        if (isFavorite) {
            prefs.addFavoriteLiveCategoryId(categoryId)
        } else {
            prefs.removeFavoriteLiveCategoryId(categoryId)
        }
    }

    fun getFavoriteMergedCategoryIds(): Flow<Set<String>> = prefs.favoriteMergedCategoryIds

    // key is "$serverIndex:$categoryId" — plain categoryId collides across servers, same
    // reasoning as every other merged-provider composite key in this codebase.
    suspend fun setMergedCategoryFavorite(key: String, isFavorite: Boolean) {
        if (isFavorite) {
            prefs.addFavoriteMergedCategoryId(key)
        } else {
            prefs.removeFavoriteMergedCategoryId(key)
        }
    }

    // Hidden categories in Providers > Movies/Series — a separate concept from the above
    // favorite/pin one, see PreferencesManager.HIDDEN_MERGED_VOD_CATEGORY_IDS kdoc.
    fun getHiddenMergedVodCategoryIds(): Flow<Set<String>> = prefs.hiddenMergedVodCategoryIds
    suspend fun addHiddenMergedVodCategoryIds(keys: Set<String>) = prefs.addHiddenMergedVodCategoryIds(keys)
    suspend fun removeHiddenMergedVodCategoryId(key: String) = prefs.removeHiddenMergedVodCategoryId(key)

    fun getHiddenMergedSeriesCategoryIds(): Flow<Set<String>> = prefs.hiddenMergedSeriesCategoryIds
    suspend fun addHiddenMergedSeriesCategoryIds(keys: Set<String>) = prefs.addHiddenMergedSeriesCategoryIds(keys)
    suspend fun removeHiddenMergedSeriesCategoryId(key: String) = prefs.removeHiddenMergedSeriesCategoryId(key)

    suspend fun getLiveStreamUrl(streamId: Int): String {
        val channel = db.channelDao().getChannelById(streamId)
        if (channel?.streamUrl != null) return channel.streamUrl
        val format = prefs.preferredFormat.first()
        return urlBuilder().liveStreamUrl(streamId, format)
    }

    suspend fun getLiveStreamUrlForCast(streamId: Int): String {
        // Always build a fresh m3u8 URL â€” Chromecast Default Media Receiver only supports HLS.
        // streamUrl in DB may be .ts or bare (no extension) which Chromecast cannot play.
        return urlBuilder().liveStreamUrl(streamId, "m3u8")
    }

    suspend fun getLiveStreamUrlForRecording(streamId: Int): String {
        val channel = db.channelDao().getChannelById(streamId)
        if (channel?.streamUrl != null) return channel.streamUrl
        return urlBuilder().liveStreamUrl(streamId, "ts")
    }

    /** No per-channel cached streamUrl override to check here, unlike the primary path above —
     * MergedChannelEntity has no streamUrl column — so this is just getMergedLiveStreamUrl
     * (already .ts-forced, already per-server-credentialed) under a recording-specific name for
     * symmetry with getLiveStreamUrlForRecording. */
    suspend fun getMergedLiveStreamUrlForRecording(serverIndex: Int, streamId: Int): String =
        getMergedLiveStreamUrl(serverIndex, streamId)

    suspend fun fetchVodStreams(onProgress: (saved: Int, total: Int) -> Unit = { _, _ -> }): Resource<List<VodStream>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getVodStreams(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            // Without this, every VOD refresh (auto-refresh, pull-to-refresh, stale-cache
            // reload) silently un-favorited every movie and reset all watch progress back to
            // zero — @Upsert replaces the whole row, and a freshly-built VodEntity defaults
            // isFavorite/watchedMs/durationMs to their zero values. Same bug class already
            // fixed today for live channels (fetchLiveStreams) and merged channels
            // (refreshMergedChannels); VOD/series were the two remaining spots.
            val userData = db.vodDao().getUserData().associateBy { it.streamId }
            // Some providers have 100k+ item catalogs — mapping the whole list to a second
            // full-size List<VodEntity> before upserting doubles peak memory right when the
            // raw deserialized response is already at its largest. Chunk map+upsert together
            // so only one chunk's worth of entities exists at a time.
            var saved = 0
            list.chunked(2000).forEach { chunk ->
                db.vodDao().upsertVod(chunk.map {
                    val prev = userData[it.streamId]
                    VodEntity(
                        streamId = it.streamId,
                        name = it.name,
                        streamIcon = it.streamIcon,
                        categoryId = it.categoryId,
                        rating = it.rating,
                        containerExtension = it.containerExtension,
                        added = it.added,
                        isFavorite = prev?.isFavorite ?: false,
                        watchedMs = prev?.watchedMs ?: 0L,
                        durationMs = prev?.durationMs ?: 0L
                    )
                })
                saved += chunk.size
                onProgress(saved, list.size)
            }
            list
        }
    }

    suspend fun fetchVodCategories(): Resource<List<Category>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getVodCategories(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            val unnamed = list.count { it.categoryName.isNullOrBlank() }
            if (com.iptvapp.BuildConfig.DEBUG) android.util.Log.d("VodDiag", "VOD category count: ${list.size}, unnamed: $unnamed")
            db.categoryDao().deleteCategoriesByType("vod")
            db.categoryDao().upsertCategories(list.map {
                CategoryEntity(it.categoryId, it.categoryName, it.parentId, "vod")
            })
            list
        }
    }

    fun getVodCategories(): Flow<List<CategoryEntity>> = db.categoryDao().getCategoriesByType("vod")

    fun getVodByCategory(categoryId: String): Flow<List<VodEntity>> = db.vodDao().getVodByCategory(categoryId)
    fun getFavoriteVod(): Flow<List<VodEntity>> = db.vodDao().getFavoriteVod()

    suspend fun getVodStreamUrl(streamId: Int, containerExtension: String): String =
        urlBuilder().vodStreamUrl(streamId, containerExtension)

    fun getAllVod(): Flow<List<VodEntity>> = db.vodDao().getAllVod()
    fun getVodFirstPage(): Flow<List<VodEntity>> = db.vodDao().getVodFirstPage()

    fun getRecentlyAddedVod(): Flow<List<VodEntity>> = db.vodDao().getRecentlyAddedVod()
    fun getRecentlyAddedMergedVod(): Flow<List<MergedVodEntity>> = db.mergedVodDao().getRecentlyAdded()

    fun searchVod(query: String): Flow<List<VodEntity>> = db.vodDao().searchVod(query)

    fun searchSeries(query: String): Flow<List<SeriesEntity>> = db.seriesDao().searchSeries(query)

    suspend fun fetchSeries(onProgress: (saved: Int, total: Int) -> Unit = { _, _ -> }): Resource<List<Series>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getSeries(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            // Same preserve-across-refresh fix as fetchVodStreams above — without it, every
            // series refresh silently un-favorited every show and reset watch progress.
            val userData = db.seriesDao().getUserData().associateBy { it.seriesId }
            var saved = 0
            list.chunked(2000).forEach { chunk ->
                db.seriesDao().upsertSeries(chunk.map {
                    val prev = userData[it.seriesId]
                    SeriesEntity(
                        seriesId = it.seriesId,
                        name = it.name,
                        cover = it.cover,
                        // Some providers return a corrupted/duplicated `plot` field that's
                        // absurdly long (megabytes) — one such row is enough to blow past
                        // SQLite's CursorWindow limit and crash the whole series list query
                        // with SQLiteBlobTooBigException. A plot summary has no legitimate
                        // reason to exceed a few thousand characters.
                        plot = it.plot?.take(4000),
                        genre = it.genre,
                        rating = it.rating,
                        categoryId = it.categoryId,
                        isFavorite = prev?.isFavorite ?: false,
                        watchedMs = prev?.watchedMs ?: 0L,
                        durationMs = prev?.durationMs ?: 0L,
                        isHidden = prev?.isHidden ?: false
                    )
                })
                saved += chunk.size
                onProgress(saved, list.size)
            }
            list
        }
    }

    fun getAllSeries(): Flow<List<SeriesEntity>> = db.seriesDao().getAllSeries()
    fun getHiddenSeries(): Flow<List<SeriesEntity>> = db.seriesDao().getHiddenSeries()
    suspend fun bulkHideSeries(seriesIds: List<Int>) = db.seriesDao().bulkSetHidden(seriesIds)
    suspend fun unhideSeries(seriesId: Int) = db.seriesDao().setUnhidden(seriesId)

    suspend fun setSeriesFavorite(seriesId: Int, isFavorite: Boolean) = db.seriesDao().setFavorite(seriesId, isFavorite)

    suspend fun setVodFavorite(streamId: Int, isFavorite: Boolean) = db.vodDao().setFavorite(streamId, isFavorite)
    suspend fun getVodByStreamId(streamId: Int) = db.vodDao().getVodByStreamId(streamId)

    suspend fun fetchSeriesCategories(): Resource<List<Category>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getSeriesCategories(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            db.categoryDao().deleteCategoriesByType("series")
            db.categoryDao().upsertCategories(list.map {
                CategoryEntity(it.categoryId, it.categoryName, it.parentId, "series")
            })
            list
        }
    }

    fun getSeriesCategories(): Flow<List<CategoryEntity>> = db.categoryDao().getCategoriesByType("series")

    suspend fun fetchEpg(streamId: Int): Resource<List<EpgEntity>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getShortEpg(b.apiUrl(), c.username, c.password, streamId = streamId)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body()?.epgListings ?: emptyList()
            val entities = list.map {
                EpgEntity(
                    id = it.id,
                    streamId = streamId,
                    title = decodeBase64(it.title),
                    description = decodeBase64(it.description),
                    startTimestamp = it.startTimestamp,
                    stopTimestamp = it.stopTimestamp,
                    nowPlaying = it.nowPlaying,
                    hasArchive = it.hasArchive
                )
            }
            db.epgDao().upsertEpg(entities)
            entities
        }
    }

    /**
     * Fetch EPG from the provider's XMLTV endpoint (xmltv.php) and upsert into epg_entries.
     * Returns the number of programs written, or 0 if the endpoint returns nothing useful.
     * Never throws — silently no-ops on any failure.
     */
    // Fetches from the primary server's own built-in xmltv.php AND every manually-configured
    // EPG source (Settings > EPG "Add EPG Source" / the "Default US Guide" toggle) — these
    // used to be saved to prefs but never actually fetched from anywhere, since this method
    // only ever looked at the primary server's own guide. A provider with no EPG of its own
    // (relying entirely on a manual/default source) previously got zero program data despite
    // the source being "configured" in Settings.
    suspend fun fetchXmltvEpg(): Int = withContext(Dispatchers.IO) {
        val c = creds()
        val sources = mutableListOf<String>()
        if (c.isLoggedIn && c.serverUrl.isNotEmpty()) {
            sources.add(XmltvFetcher.buildUrl(c.serverUrl, c.username, c.password))
        }
        sources.addAll(prefs.getEpgUrls().filter { it.isNotBlank() })
        if (sources.isEmpty()) return@withContext 0

        // Build lookup once, shared across every source — the same primary-server channel
        // table is what every EPG source is matched against regardless of which XMLTV feed
        // supplied the program data.
        val allChannels = db.channelDao().getAllChannels().first()
        val byEpgId = mutableMapOf<String, Int>()
        val byName  = mutableMapOf<String, Int>()
        allChannels.forEach { ch ->
            if (!ch.epgChannelId.isNullOrBlank())
                byEpgId[ch.epgChannelId.lowercase()] = ch.streamId
            byName[normalizeForMatch(ch.name)] = ch.streamId
        }

        // Clear every existing primary-server EPG row before writing the fresh batch — see
        // EpgDao.deleteAllForServer's kdoc for why upsert alone can leave stale, WRONG entries
        // behind under an old streamId when a channel now resolves differently (or not at all)
        // than it did on a previous fetch. This is why refreshing the EPG after the v5.74 matching
        // fix didn't change anything you could see: the old mismatched rows were still sitting in
        // the table, untouched by upsert.
        db.epgDao().deleteAllForServer(-1)

        var totalCount = 0
        for (url in sources) {
            totalCount += try {
                fetchXmltvFromUrl(url, byEpgId, byName)
            } catch (_: Exception) { 0 }
        }
        totalCount
    }

    private suspend fun fetchXmltvFromUrl(
        url: String,
        byEpgId: Map<String, Int>,
        byName: Map<String, Int>
    ): Int {
        val (xmlChannels, xmlPrograms) = XmltvFetcher.fetch(url)
        if (xmlPrograms.isEmpty()) return 0

        // Resolve each distinct xmltv channel to a stream id once (not per-program —
        // there can be thousands of programs but only a few hundred channels): 1) exact
        // epg-channel-id match, 2) exact normalized-name match. A previous third fallback —
        // "first channel whose normalized name contains, or is contained by, the xmltv name" —
        // was removed: on a catalog this size (tens of thousands of channels), that kind of
        // substring match routinely matched the WRONG channel (e.g. "ESPN" as a substring of
        // "ESPN2", "ESPN News", "ESPN Deportes", ...), silently writing one network's real
        // schedule under a completely different channel's streamId — exactly the bug where the
        // guide shows one show/movie but the channel is actually playing something else. Losing
        // EPG data for a channel with an ambiguous/non-matching name is far better than showing
        // confidently wrong data for it.
        val xmlChannelToStreamId = mutableMapOf<String, Int>()
        xmlChannels.forEach { xmlCh ->
            val normXml = normalizeForMatch(xmlCh.displayName)
            val resolved = byEpgId[xmlCh.id.lowercase()] ?: byName[normXml]
            if (resolved != null) xmlChannelToStreamId[xmlCh.id] = resolved
        }

        val nowSec = System.currentTimeMillis() / 1000
        val entities = mutableListOf<EpgEntity>()

        xmlPrograms.forEach { prog ->
            val streamId = xmlChannelToStreamId[prog.channelId] ?: return@forEach

            entities.add(EpgEntity(
                id             = "x_${prog.channelId}_${prog.startSec}",
                streamId       = streamId,
                title          = prog.title,
                description    = prog.description,
                startTimestamp = prog.startSec,
                stopTimestamp  = prog.stopSec,
                nowPlaying     = if (prog.startSec <= nowSec && prog.stopSec > nowSec) 1 else 0,
                hasArchive     = 0
            ))
        }

        entities.chunked(500).forEach { db.epgDao().upsertEpg(it) }
        return entities.size
    }

    // Word-boundary-safe: the previous version's tokens had no \b, so e.g. "us"/"hd" could
    // strip a matching substring out of the middle of an unrelated word instead of only
    // matching whole quality/region tags, causing inconsistent normalization between a
    // channel's Xtream name and its XMLTV display name.
    private fun normalizeForMatch(name: String): String =
        name.lowercase()
            .replace(Regex("\\b(hd|fhd|uhd|4k|sd|the|us|usa|uk|ca|east|west|hevc|h264|h265)\\b"), " ")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()

    fun getEpgForStream(streamId: Int): Flow<List<EpgEntity>> =
        db.epgDao().getEpgForStream(streamId)

    suspend fun saveVodProgress(streamId: Int, watchedMs: Long, durationMs: Long) {
        db.vodDao().updateWatchProgress(streamId, watchedMs, durationMs)
    }

    suspend fun getVodProgress(streamId: Int): Pair<Long, Long> {
        val watched = db.vodDao().getWatchedMs(streamId) ?: 0L
        val duration = db.vodDao().getDurationMs(streamId) ?: 0L
        return Pair(watched, duration)
    }

    suspend fun saveSeriesProgress(seriesId: Int, watchedMs: Long, durationMs: Long) {
        db.seriesDao().updateWatchProgress(seriesId, watchedMs, durationMs)
    }

    suspend fun getSeriesProgress(seriesId: Int): Pair<Long, Long> {
        val watched = db.seriesDao().getWatchedMs(seriesId) ?: 0L
        val duration = db.seriesDao().getDurationMs(seriesId) ?: 0L
        return Pair(watched, duration)
    }

    suspend fun saveEpisodeProgress(seriesId: Int, season: Int, episode: Int, watchedMs: Long, durationMs: Long) {
        db.episodeWatchedDao().saveProgress(seriesId, season, episode, watchedMs, durationMs)
        // getInProgressSeries() is actually driven by episode_watched rows (see its own kdoc),
        // not series.watchedMs/durationMs — but series.lastWatchedAt is what
        // ContinueWatchingCleanupWorker checks for staleness, so it needs to be touched here too.
        db.seriesDao().touchLastWatched(seriesId)
    }

    suspend fun getEpisodeProgress(seriesId: Int, season: Int, episode: Int): Pair<Long, Long> {
        val watched = db.episodeWatchedDao().getWatchedMs(seriesId, season, episode) ?: 0L
        val duration = db.episodeWatchedDao().getDurationMs(seriesId, season, episode) ?: 0L
        return Pair(watched, duration)
    }

    fun getSeriesIdsWithProgress(): Flow<List<Int>> = db.episodeWatchedDao().getSeriesIdsWithProgress()

    suspend fun getSeriesById(seriesId: Int): SeriesEntity? = db.seriesDao().getSeriesById(seriesId)

    suspend fun getEpisodeWatchedForSeries(seriesId: Int): List<com.iptvapp.data.local.entities.EpisodeWatchedEntity> =
        db.episodeWatchedDao().getForSeries(seriesId)

    // A plain single-query IN(...) here blew past SQLite's ~999-bound-parameter limit and
    // crashed the whole app (SQLiteException: too many SQL variables) for an account with a
    // large enough favorites/category selection feeding the Guide tab — confirmed via a real
    // crash log. Chunking was already applied ad-hoc at one call site (loadEpgForChannels) but
    // missed here, the Guide's own query and by far the most likely to hit a large id list.
    // Every real call site only ever collects this once (.first()), never observes it live, so
    // combining chunk results into one list-emitting Flow changes nothing for callers.
    fun getEpgForStreams(streamIds: List<Int>): Flow<List<EpgEntity>> =
        if (streamIds.size <= 900) {
            db.epgDao().getEpgForStreams(streamIds)
        } else {
            kotlinx.coroutines.flow.flow {
                emit(streamIds.chunked(900).flatMap { chunk -> db.epgDao().getEpgForStreams(chunk).first() })
            }
        }

    /** EPG across multiple servers at once, keyed by exact (serverIndex, streamId) pairs so two
     * different servers reusing the same numeric streamId never collide — used by the Guide
     * tab once merged/secondary-provider favorites are included alongside the primary provider.
     * Same SQLite bound-parameter chunking as getEpgForStreams above — a large enough combined
     * merged-favorites count across providers could hit the same crash. */
    fun getEpgForServerStreams(pairs: List<Pair<Int, Int>>): Flow<List<EpgEntity>> {
        val keys = pairs.map { (serverIndex, streamId) -> "$serverIndex:$streamId" }
        return if (keys.size <= 900) {
            db.epgDao().getEpgForServerStreamKeys(keys)
        } else {
            kotlinx.coroutines.flow.flow {
                emit(keys.chunked(900).flatMap { chunk -> db.epgDao().getEpgForServerStreamKeys(chunk).first() })
            }
        }
    }
    suspend fun getStreamIdsWithEpg(serverIndex: Int) = db.epgDao().getStreamIdsWithEpg(serverIndex)

    fun getInProgressVod(): Flow<List<VodEntity>> = db.vodDao().getInProgressVod()
    fun getInProgressSeries(): Flow<List<com.iptvapp.data.local.dao.InProgressSeriesRow>> = db.seriesDao().getInProgressSeries()
    suspend fun dismissVodFromContinueWatching(streamId: Int) = db.vodDao().dismissFromContinueWatching(streamId)
    suspend fun dismissSeriesFromContinueWatching(seriesId: Int) = db.seriesDao().dismissFromContinueWatching(seriesId)

    suspend fun fetchSeriesInfo(seriesId: Int): Resource<SeriesInfo> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getSeriesInfo(b.apiUrl(), c.username, c.password, seriesId = seriesId)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            response.body() ?: throw Exception("Empty response")
        }
    }

    suspend fun getSeriesEpisodeUrl(episodeId: String, containerExtension: String): String =
        urlBuilder().seriesStreamUrl(episodeId, containerExtension)

    suspend fun fetchVodInfo(vodId: Int): Resource<VodInfo> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getVodInfo(b.apiUrl(), c.username, c.password, vodId = vodId)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            response.body() ?: throw Exception("Empty response")
        }
    }

    suspend fun getTimeshiftUrl(streamId: Int, startTimestampSec: Long, durationMinutes: Int): String =
        urlBuilder().timeshiftUrl(streamId, startTimestampSec, durationMinutes)

    suspend fun saveFavOrder(orderedIds: List<Int>) {
        orderedIds.forEachIndexed { index, streamId ->
            db.channelDao().updateFavOrder(streamId, index)
        }
    }

    /** Combined-Favorites drag-reorder commit — orderedIds are CombinedFavorite.id strings
     * ("primary:$streamId" or "$serverIndex:$streamId"), dispatched to whichever table each one
     * actually belongs to so a primary and a merged channel can share one flat favOrder sequence
     * (see MergedChannelEntity.favOrder kdoc) instead of two separately-ordered blocks. */
    suspend fun saveCombinedFavOrder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            if (id.startsWith("primary:")) {
                val streamId = id.substringAfter("primary:").toIntOrNull() ?: return@forEachIndexed
                db.channelDao().updateFavOrder(streamId, index)
            } else {
                val (serverIndex, streamId) = id.split(":", limit = 2).let { it[0].toInt() to it[1].toInt() }
                db.mergedChannelDao().updateFavOrder(serverIndex, streamId, index)
            }
        }
    }

    // ── Favorite folders ──────────────────────────────────────────────────────
    fun getFavoriteFolders(): Flow<List<FavoriteFolderEntity>> = db.favoriteFolderDao().getAll()

    suspend fun createFavoriteFolder(name: String): Int {
        val existing = db.favoriteFolderDao().getAll().first()
        return db.favoriteFolderDao().insert(
            FavoriteFolderEntity(name = name, sortOrder = existing.size)
        ).toInt()
    }

    suspend fun renameFavoriteFolder(id: Int, name: String) = db.favoriteFolderDao().rename(id, name)

    suspend fun deleteFavoriteFolder(id: Int) {
        db.channelDao().clearFolderFromChannels(id)
        db.mergedChannelDao().clearFolderFromChannels(id)
        db.favoriteFolderDao().delete(id)
    }

    suspend fun setChannelFavoriteFolder(streamId: Int, folderId: Int?) =
        db.channelDao().setFavoriteFolder(streamId, folderId)

    fun getFavoritesInFolder(folderId: Int): Flow<List<ChannelEntity>> = db.channelDao().getFavoritesInFolder(folderId)

    fun getUnfiledFavorites(): Flow<List<ChannelEntity>> = db.channelDao().getUnfiledFavorites()

    fun getFavoriteCountsByFolder(): Flow<List<com.iptvapp.data.local.dao.FavoriteFolderCount>> =
        db.channelDao().getFavoriteCountsByFolder()

    // GET + Range (not HEAD) with the same User-Agent PlayerActivity's ExoPlayer actually sends
    // (see its OkHttpDataSource.Factory.setUserAgent) — a real, common false-red cause: some
    // Xtream panels reject HEAD requests on live-stream endpoints outright, or 403 requests from
    // an unrecognized User-Agent (OkHttp's bare default UA), while happily serving the exact same
    // GET request a real player makes. Range asks for just the first byte so this doesn't
    // download an actual live segment, just confirms the server will start responding at all.
    suspend fun checkStreamHealth(url: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android) ExoPlayerLib/1.4.1")
                .header("Range", "bytes=0-0")
                .get()
                .build()
            val code = client.newCall(request).execute().use { it.code }
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    // Rolling last-10-outcomes reliability history — fed by both explicit health-check pings
    // and real playback attempts (mini player ready/error), so a channel that constantly
    // fails during actual use surfaces itself instead of you rediscovering the same dead
    // channel over and over.
    suspend fun recordChannelOutcome(streamId: Int, success: Boolean) {
        val existing = db.reliabilityDao().get(streamId)
        val updated = ((existing?.outcomes ?: "") + if (success) "1" else "0").takeLast(10)
        db.reliabilityDao().upsert(
            com.iptvapp.data.local.entities.ChannelReliabilityEntity(streamId, updated, System.currentTimeMillis())
        )
    }

    /** e.g. "7/10 succeeded recently" — null if there's no history yet for this channel. */
    suspend fun getReliabilityLabel(streamId: Int): String? {
        val outcomes = db.reliabilityDao().get(streamId)?.outcomes ?: return null
        if (outcomes.isEmpty()) return null
        val successes = outcomes.count { it == '1' }
        return "$successes/${outcomes.length} succeeded recently"
    }

    fun observeActiveRecording(serverIndex: Int, streamId: Int) = db.recordingDao().observeActive(serverIndex, streamId)

    suspend fun getAnyActiveRecording() = db.recordingDao().getAnyActive()

    suspend fun getOverlappingRecordings(startMs: Long, durationMs: Long) =
        db.recordingDao().getOverlapping(startMs, startMs + durationMs)

    /** streamId -> success percent, for channels with at least one recorded outcome — backs
     * the "Most Reliable" sort option. Reads in pages (see ReliabilityDao.getPage's kdoc) and
     * swallows failures rather than crashing — losing "Most Reliable" sort data for one session
     * is a fine trade against taking down the whole app on a memory-constrained device. */
    suspend fun getAllReliabilityPercents(): Map<Int, Int> = try {
        val all = mutableListOf<com.iptvapp.data.local.entities.ChannelReliabilityEntity>()
        var offset = 0
        val pageSize = 2000
        while (true) {
            val page = db.reliabilityDao().getPage(pageSize, offset)
            all.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        all.filter { it.outcomes.isNotEmpty() }
            .associate { it.streamId to (it.outcomes.count { c -> c == '1' } * 100 / it.outcomes.length) }
    } catch (e: Exception) {
        emptyMap()
    }

    suspend fun importM3uFromUrl(url: String): Resource<Int> = safeApiCall {
        val request = Request.Builder().url(url).build()
        val content = okHttpClient.newCall(request).execute().use { it.body?.string() }
            ?: throw Exception("Empty response from M3U URL")
        importM3uText(content)
    }

    suspend fun importM3uFromText(content: String): Resource<Int> = safeApiCall {
        importM3uText(content)
    }

    private suspend fun importM3uText(content: String): Int {
        val channels = M3uParser.parse(content)
        if (channels.isEmpty()) throw Exception("No channels found in playlist")

        val groups = channels.map { it.groupTitle }.distinct()
        db.categoryDao().deleteM3uCategories()
        db.channelDao().deleteM3uChannels()
        db.categoryDao().upsertCategories(groups.mapIndexed { idx, name ->
            CategoryEntity(
                categoryId = "m3u_${name.hashCode().toLong() and 0xFFFFFFFFL}",
                categoryName = name,
                parentId = 0,
                type = "live"
            )
        })

        db.channelDao().upsertChannels(channels.mapIndexed { idx, ch ->
            val rawId = ch.streamUrl.hashCode().toLong() and 0x7FFFFFFFL
            val streamId = (rawId + 10_000_000L).toInt()
            ChannelEntity(
                streamId = streamId,
                name = ch.name,
                streamIcon = ch.logoUrl,
                categoryId = "m3u_${ch.groupTitle.hashCode().toLong() and 0xFFFFFFFFL}",
                epgChannelId = ch.tvgId,
                tvArchive = 0,
                num = idx,
                streamUrl = ch.streamUrl
            )
        })
        return channels.size
    }

    private fun decodeBase64(encoded: String): String = try {
        String(Base64.decode(encoded, Base64.DEFAULT))
    } catch (e: Exception) {
        encoded
    }

    private data class ConfiguredServer(
        val serverIndex: Int,
        val serverUrl: String,
        val username: String,
        val password: String,
        val nickname: String,
        // Per-provider custom guide URL (Settings > Providers > Edit > EPG URL) — was never
        // carried through here at all, so fetchXmltvEpgForMergedServer always built its XMLTV
        // request from the server's base URL + "/xmltv.php" regardless of this field, silently
        // ignoring it. Blank means "use the server's own default xmltv.php path," same fallback
        // XmltvFetcher.buildUrl already assumed.
        val epgUrl: String = ""
    )

    // serverIndex -1 = primary, 0..N-1 = extraServers[i] — same convention as
    // PreferencesManager.activeServerIndex.
    private suspend fun allConfiguredServers(): List<ConfiguredServer> {
        val primary = creds()
        val primaryNick = prefs.serverNickname.first().ifBlank { primary.username }
        val servers = mutableListOf(
            ConfiguredServer(-1, primary.serverUrl, primary.username, primary.password, primaryNick, prefs.epgUrl.first())
        )
        prefs.getExtraServersWithNick().forEachIndexed { i, s ->
            // A disabled provider is treated as if it doesn't exist for every browsing/refresh/
            // health-check purpose — this is the single choke point every one of those reads
            // through, so a provider skipped here needs zero other code changes to disappear
            // from all of them. Settings' own provider-list UI reads the raw, unfiltered list
            // directly (not through this function), so a disabled provider still shows there —
            // dimmed, with its saved credentials intact — for the user to re-enable later.
            if (!s.getOrElse(5) { "true" }.toBoolean()) return@forEachIndexed
            val nick = s.getOrElse(3) { "" }.ifBlank { s[1] }
            servers.add(ConfiguredServer(i, s[0], s[1], s[2], nick, s.getOrElse(4) { "" }))
        }
        return servers
    }

    /** URL for a given serverIndex among currently-configured servers (0..N-1, extra providers
     * only — primary/-1 isn't relevant here since backup/sync scope merged-provider data by
     * its own serverIndex, and the primary's identity is handled separately). Used by callers
     * (Backup JSON, restore) that need the same URL-based cross-device identity SyncManager
     * already established for merged favorites, without duplicating allConfiguredServers(). */
    suspend fun getMergedServerUrls(): Map<Int, String> =
        allConfiguredServers().filter { it.serverIndex != -1 }.associate { it.serverIndex to it.serverUrl }

    /** Current nickname for each configured extra server, by serverIndex. MergedChannelEntity's
     * own serverNickname column is a snapshot written at refresh time — it goes stale the moment
     * a user renames a provider or reassigns which slot is primary vs. secondary (favorites
     * restored via sync/backup keep whatever nickname was cached on that row, not the current
     * one). UI code displaying a merged favorite's provider name should prefer this live lookup
     * over the entity's own field. */
    suspend fun getMergedServerNicknames(): Map<Int, String> =
        allConfiguredServers().filter { it.serverIndex != -1 }.associate { it.serverIndex to it.nickname }

    data class ProviderHealthStatus(
        val serverIndex: Int,
        val nickname: String,
        val serverUrl: String,
        val reachable: Boolean,
        val responseMs: Long?,
        val error: String?
    )

    data class ConnectionTestResult(val reachable: Boolean, val responseMs: Long?, val error: String?)

    /** Raw credentials in, reachability out — doesn't require the server to be saved/configured
     * yet, unlike checkAllProviderHealth() below (which only knows about servers already in
     * prefs.getExtraServersWithNick()). This is what the Add/Edit Provider dialogs' "Test
     * Connection" button calls, so a bad URL/credential typo is caught before ever being saved
     * — the actual root cause behind providers silently "not showing up" was that nothing
     * validated the connection at all until some other, unrelated screen happened to fail.
     *
     * Uses the plain login endpoint (api.authenticate, same one the real login screen calls)
     * rather than get_live_categories — Xtream panels almost never return HTTP 401 for bad
     * credentials, they return HTTP 200 with a JSON body describing WHY (status=Expired/
     * Disabled, active_cons >= max_connections, etc.), so reading that body gives a genuinely
     * actionable reason instead of a bare status code. A real HTTP 401 here usually means
     * something is rejecting the request before it even reaches the Xtream panel (a reverse
     * proxy/CDN with its own auth, or the URL/path being wrong entirely). */
    suspend fun testProviderConnection(serverUrl: String, username: String, password: String): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            withTimeout(8_000) {
                val b = XtreamUrlBuilder(serverUrl, username, password)
                val response = api.authenticate(b.apiUrl(), username, password)
                val elapsed = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    val hint = if (response.code() == 401)
                        "HTTP 401 — this is unusual for Xtream and often means something in front of the panel (a proxy/CDN) is blocking the request before it even reaches the login check, or the URL is wrong"
                    else "Server returned ${response.code()}"
                    return@withTimeout ConnectionTestResult(false, elapsed, hint)
                }
                val info = response.body()?.userInfo
                    ?: return@withTimeout ConnectionTestResult(false, elapsed, "Empty response from server")
                when {
                    info.status != "Active" -> ConnectionTestResult(false, elapsed, "Account status: ${info.status} — check with your provider (expired, disabled, or suspended)")
                    info.maxConnections.toIntOrNull() != null && info.activeCons.toIntOrNull() != null &&
                        info.activeCons.toInt() >= info.maxConnections.toInt() ->
                        ConnectionTestResult(false, elapsed, "Max connections reached (${info.activeCons}/${info.maxConnections}) — another device is already using this account's connection limit")
                    else -> ConnectionTestResult(true, elapsed, null)
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else (e.message ?: "Unreachable")
            ConnectionTestResult(false, elapsed, msg)
        }
    }

    /** Live reachability probe for every ALREADY-CONFIGURED provider — a lightweight
     * get_live_categories call (small payload, same endpoint the merged-channel refresh already
     * uses) rather than a real reliability history, since only the primary provider has
     * per-channel outcome tracking today (ChannelReliabilityEntity/checkStreamHealth). This
     * answers "is this provider up right now", not "how has it performed over time" — good
     * enough for a Settings diagnostics check without needing to build out full history
     * tracking for merged providers too. */
    suspend fun checkAllProviderHealth(): List<ProviderHealthStatus> = coroutineScope {
        allConfiguredServers().map { server ->
            async {
                val result = testProviderConnection(server.serverUrl, server.username, server.password)
                ProviderHealthStatus(server.serverIndex, server.nickname, server.serverUrl, result.reachable, result.responseMs, result.error)
            }
        }.awaitAll()
    }

    data class ProviderSpeedTestResult(
        val serverIndex: Int,
        val nickname: String,
        val host: String,
        val tcpAvgMs: Long?,
        val tcpSuccessCount: Int,
        val httpMs: Long?,
        val error: String?
    )

    /** Settings' "Provider Speed Test" used to only ever test the primary server (hardcoded
     * read of prefs.credentials) — extended to loop every currently-active provider, same
     * allConfiguredServers() choke point checkAllProviderHealth() already uses, so a disabled
     * merged provider is skipped here exactly like everywhere else. Same TCP-connect-time +
     * HTTP-response-time methodology the original single-server test used, just per server and
     * run in parallel (each server's own 3x TCP probe + 1 HTTP fetch, capped by their own
     * timeouts, so one slow/dead provider can't stall the others). */
    suspend fun runSpeedTestForAllProviders(): List<ProviderSpeedTestResult> = coroutineScope {
        allConfiguredServers().map { server ->
            async(Dispatchers.IO) {
                val uri = try { java.net.URI(server.serverUrl) } catch (_: Exception) {
                    return@async ProviderSpeedTestResult(server.serverIndex, server.nickname, server.serverUrl, null, 0, null, "Invalid server URL")
                }
                val host = uri.host ?: return@async ProviderSpeedTestResult(server.serverIndex, server.nickname, server.serverUrl, null, 0, null, "Could not parse host")
                val port = if (uri.port > 0) uri.port else 80

                val tcpTimes = mutableListOf<Long>()
                repeat(3) {
                    try {
                        val start = System.currentTimeMillis()
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(host, port), 3000)
                        tcpTimes.add(System.currentTimeMillis() - start)
                        socket.close()
                    } catch (_: Exception) {}
                }
                val tcpAvg = if (tcpTimes.isNotEmpty()) tcpTimes.average().toLong() else null

                val httpMs = try {
                    val start = System.currentTimeMillis()
                    val response = okHttpClient.newCall(Request.Builder().url(server.serverUrl).build()).execute()
                    val elapsed = System.currentTimeMillis() - start
                    response.close()
                    elapsed
                } catch (_: Exception) {
                    null
                }

                val error = if (tcpTimes.isEmpty() && httpMs == null) "Unreachable" else null
                ProviderSpeedTestResult(server.serverIndex, server.nickname, host, tcpAvg, tcpTimes.size, httpMs, error)
            }
        }.awaitAll()
    }

    /** Fetches live channels from every configured server (primary + extras) in parallel for
     * the "All Providers" merged browse-and-play view — a deliberately separate cache from the
     * primary server's ChannelEntity table, since two different Xtream servers can reuse the
     * same numeric stream id. Only servers that fetch successfully are written, in one atomic
     * clear+upsert, so a network hiccup on one server doesn't wipe a previously-cached other
     * server's rows. Returns serverIndex -> error message for any servers that failed. */
    // targetServerIndex: null = refresh every configured server (existing "Refresh All
    // Providers" behavior on Home), a specific index = just that one server — used by the
    // per-provider refresh button in Settings, which deliberately only touches live channels/
    // categories, never VOD/series (that's the separate Movies/Series refresh already in the
    // Display section).
    // Retrofit/Gson deserializes get_live_categories/get_live_streams' body immediately
    // (Response<List<...>>), so a provider returning HTML instead of JSON (an expired/disabled
    // account's login-redirect page, a WAF/Cloudflare block page, or just a wrong API path) never
    // surfaces as a clean HTTP error — it throws deep inside Gson with a message like "Use
    // JsonReader.setLenient(true) to accept malformed JSON at line 1 column 1 path $", which is
    // meaningless to a user and gives no hint about what's actually wrong. This recognizes that
    // specific failure signature and translates it into the real, actionable cause.
    private fun describeMergedChannelFetchError(e: Exception): String {
        if (e is kotlinx.coroutines.TimeoutCancellationException) return "Timed out"
        val message = e.message ?: return "Unknown error"
        val looksLikeNonJsonBody = e is com.google.gson.stream.MalformedJsonException ||
            message.contains("MalformedJsonException") ||
            message.contains("setLenient", ignoreCase = true) ||
            message.contains("Use JsonReader", ignoreCase = true)
        return if (looksLikeNonJsonBody) {
            "Provider returned an invalid response instead of channel data. This can mean the " +
                "login has expired/been disabled or the connection limit was hit — but it's also " +
                "commonly caused by a weak or unstable network connection corrupting the " +
                "response, or a router/network blocking or redirecting the request (e.g. a " +
                "captive portal or DNS filtering on that Wi-Fi). If this works on one network " +
                "but not another (or on one device but not another), suspect the network first " +
                "before assuming the provider is at fault."
        } else {
            message
        }
    }

    suspend fun refreshMergedChannels(
        targetServerIndex: Int? = null,
        onProgress: (completedServers: Int, totalServers: Int, itemsSoFar: Int) -> Unit = { _, _, _ -> }
    ): Map<Int, String> {
        val servers = allConfiguredServers().let { all ->
            if (targetServerIndex == null) all else all.filter { it.serverIndex == targetServerIndex }
        }
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = mutableMapOf<Int, String>()
        val results = mutableListOf<MergedChannelEntity>()
        // Wholesale re-fetch must not silently un-favorite/un-folder every merged channel —
        // same class of bug just fixed for the primary provider's ChannelEntity table.
        val mergedUserData = db.mergedChannelDao().getUserData().associateBy { it.serverIndex to it.streamId }
        coroutineScope {
            servers.map { server ->
                async {
                    try {
                        // The shared OkHttp client's read timeout is 120s (fine for a normal
                        // single-provider request) — with several extra providers configured,
                        // one slow/dead one used to stall the ENTIRE "All Providers" refresh
                        // for up to that long before the batch's error map was even populated.
                        // A per-server budget here means one bad provider can't hold the other,
                        // healthy ones hostage. Originally 15s, which was confirmed too tight for
                        // a large catalog (tens of thousands of channels) over a slower connection
                        // (a VPN, or a weak signal) — the request was otherwise succeeding
                        // (confirmed via a real device test: login succeeded through a VPN, only
                        // the channel-list fetch itself timed out), just not fast enough. Raised
                        // to give that case a real chance while still bounded.
                        kotlinx.coroutines.withTimeout(45_000) {
                        val builder = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
                        // Categories are fetched too — a single provider can itself have tens
                        // of thousands of channels, so a flat per-server list is just as
                        // unusable as one giant cross-server list; category grouping is
                        // required at both levels.
                        val catResponse = api.getLiveCategories(builder.apiUrl(), server.username, server.password)
                        val categoryNames = if (catResponse.isSuccessful) {
                            (catResponse.body() ?: emptyList()).associate { it.categoryId to it.categoryName }
                        } else emptyMap()
                        val response = api.getLiveStreams(builder.apiUrl(), server.username, server.password)
                        if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
                        val list = response.body() ?: emptyList()
                        if (com.iptvapp.BuildConfig.DEBUG) android.util.Log.d("MergedChannels", "serverIndex=${server.serverIndex} (${server.nickname}) fetched ${list.size} channels, ${categoryNames.size} categories")
                        if (com.iptvapp.BuildConfig.DEBUG) android.util.Log.d("MergedChannels", "serverIndex=${server.serverIndex} sample category names: ${categoryNames.values.take(30)}")
                        synchronized(results) {
                            results.addAll(list.map {
                                val prev = mergedUserData[server.serverIndex to it.streamId]
                                MergedChannelEntity(
                                    serverIndex = server.serverIndex,
                                    streamId = it.streamId,
                                    name = it.name,
                                    streamIcon = it.streamIcon,
                                    num = it.num,
                                    serverNickname = server.nickname,
                                    categoryId = it.categoryId,
                                    categoryName = it.categoryId?.let { id -> categoryNames[id] } ?: "Uncategorized",
                                    isFavorite = prev?.isFavorite ?: false,
                                    favoriteFolderId = prev?.favoriteFolderId,
                                    epgChannelId = it.epgChannelId,
                                    manualGenre = prev?.manualGenre
                                )
                            })
                        }
                        } // withTimeout
                    } catch (e: Exception) {
                        val msg = describeMergedChannelFetchError(e)
                        android.util.Log.e("MergedChannels", "serverIndex=${server.serverIndex} (${server.nickname}) failed: ${e.message}", e)
                        errors[server.serverIndex] = msg
                    } finally {
                        onProgress(completedCount.incrementAndGet(), servers.size, results.size)
                    }
                }
            }.forEach { it.await() }
        }
        if (com.iptvapp.BuildConfig.DEBUG) android.util.Log.d("MergedChannels", "refresh done: ${results.size} total channels, errors=$errors")
        // Only clear rows for servers that actually succeeded this refresh — a server whose
        // fetch failed (timeout, bad response) contributes nothing to `results`, so clearing
        // its rows unconditionally (the old behavior) permanently deleted that server's cached
        // channels AND favorites on a single transient hiccup, with nothing left for the next
        // refresh's `prev` lookup to restore them from either.
        results.map { it.serverIndex }.distinct().forEach { serverIndex ->
            db.mergedChannelDao().clearForServer(serverIndex)
        }
        // A single @Upsert call over the WHOLE combined results list runs as one giant
        // transaction — fine for a small catalog, but a provider with a large one (tens of
        // thousands of channels) could leave that one COMMIT running for minutes on slower
        // storage (confirmed via SQLiteConnectionPool logs on a Shield box: a stuck COMMIT held
        // the only active connection, starving every other DB read — including the one backing
        // the channel list UI — so channels never appeared even though the fetch itself
        // succeeded). Same fix already applied to VOD/series sync (fetchVodStreams/
        // fetchSeries) for the same reason — chunk so only one chunk's worth of rows commits at
        // a time.
        results.chunked(2000).forEach { chunk ->
            db.mergedChannelDao().upsertAll(chunk)
        }
        applyPendingMergedRestoreData(servers)
        return errors
    }

    /** Applies any merged favorites/folders/pinned categories restored from a backup, once the
     * relevant server's channels actually exist locally to apply them to — matched by server
     * URL (see buildBackupJson/applyBackupJson in SettingsActivity). Each pending entry is only
     * consumed (removed from the pending set) once its target channel/category was actually
     * found this refresh; anything for a server not yet refreshed stays pending. */
    private suspend fun applyPendingMergedRestoreData(refreshedServers: List<ConfiguredServer>) {
        val urlByServerIndex = refreshedServers.associate { it.serverUrl to it.serverIndex }

        val pendingFavorites = prefs.pendingMergedFavorites.first()
        if (pendingFavorites.isNotEmpty()) {
            val remaining = pendingFavorites.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val streamId = parts.getOrNull(1)?.toIntOrNull()
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && streamId != null) {
                    val channel = db.mergedChannelDao().getByIndexAndId(serverIndex, streamId)
                    if (channel != null) {
                        db.mergedChannelDao().setFavorite(serverIndex, streamId, true)
                        false // consumed, drop from pending
                    } else true // server refreshed but this channel wasn't in it — keep waiting
                } else true // not this server's URL — leave pending for its own refresh
            }.toSet()
            if (remaining != pendingFavorites) prefs.setPendingMergedFavorites(remaining)
        }

        val pendingCategories = prefs.pendingMergedFavoriteCategories.first()
        if (pendingCategories.isNotEmpty()) {
            val remaining = pendingCategories.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val categoryId = parts.getOrNull(1)
                val serverIndex = url?.let { urlByServerIndex[it] }
                serverIndex == null // keep pending only if this server hasn't been refreshed yet
            }.toSet()
            // Categories are just a preference marker (no local row to check existence
            // against), so apply as soon as we know this server was refreshed at all.
            pendingCategories.minus(remaining).forEach { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val categoryId = parts.getOrNull(1) ?: return@forEach
                val serverIndex = url?.let { urlByServerIndex[it] } ?: return@forEach
                prefs.addFavoriteMergedCategoryId("$serverIndex:$categoryId")
            }
            if (remaining != pendingCategories) prefs.setPendingMergedFavoriteCategories(remaining)
        }

        val pendingFolders = prefs.pendingMergedChannelFolders.first()
        if (pendingFolders.isNotEmpty()) {
            val existingFolders = db.favoriteFolderDao().getAll().first()
            val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
            var nextOrder = existingFolders.size
            val remaining = pendingFolders.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val streamId = parts.getOrNull(1)?.toIntOrNull()
                val folderName = parts.getOrNull(2)
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && streamId != null && folderName != null) {
                    val channel = db.mergedChannelDao().getByIndexAndId(serverIndex, streamId)
                    if (channel != null) {
                        var folderId = idByName[folderName]
                        if (folderId == null) {
                            folderId = db.favoriteFolderDao().insert(
                                com.iptvapp.data.local.entities.FavoriteFolderEntity(name = folderName, sortOrder = nextOrder++)
                            ).toInt()
                            idByName[folderName] = folderId
                        }
                        db.mergedChannelDao().setFavoriteFolder(serverIndex, streamId, folderId)
                        false
                    } else true
                } else true
            }.toSet()
            if (remaining != pendingFolders) prefs.setPendingMergedChannelFolders(remaining)
        }
    }

    /** VOD equivalent of applyPendingMergedRestoreData — same matched-by-URL, consume-once-
     * applied shape, minus the categories block (no per-category favorite concept exists for
     * merged VOD). */
    private suspend fun applyPendingMergedVodRestoreData(refreshedServers: List<ConfiguredServer>) {
        val urlByServerIndex = refreshedServers.associate { it.serverUrl to it.serverIndex }

        val pendingFavorites = prefs.pendingMergedVodFavorites.first()
        if (pendingFavorites.isNotEmpty()) {
            val remaining = pendingFavorites.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val streamId = parts.getOrNull(1)?.toIntOrNull()
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && streamId != null) {
                    val vod = db.mergedVodDao().getByIndexAndId(serverIndex, streamId)
                    if (vod != null) {
                        db.mergedVodDao().setFavorite(serverIndex, streamId, true)
                        false
                    } else true
                } else true
            }.toSet()
            if (remaining != pendingFavorites) prefs.setPendingMergedVodFavorites(remaining)
        }

        val pendingFolders = prefs.pendingMergedVodFolders.first()
        if (pendingFolders.isNotEmpty()) {
            val existingFolders = db.favoriteFolderDao().getAll().first()
            val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
            var nextOrder = existingFolders.size
            val remaining = pendingFolders.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val streamId = parts.getOrNull(1)?.toIntOrNull()
                val folderName = parts.getOrNull(2)
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && streamId != null && folderName != null) {
                    val vod = db.mergedVodDao().getByIndexAndId(serverIndex, streamId)
                    if (vod != null) {
                        var folderId = idByName[folderName]
                        if (folderId == null) {
                            folderId = db.favoriteFolderDao().insert(
                                com.iptvapp.data.local.entities.FavoriteFolderEntity(name = folderName, sortOrder = nextOrder++)
                            ).toInt()
                            idByName[folderName] = folderId
                        }
                        db.mergedVodDao().setFavoriteFolder(serverIndex, streamId, folderId)
                        false
                    } else true
                } else true
            }.toSet()
            if (remaining != pendingFolders) prefs.setPendingMergedVodFolders(remaining)
        }

        // Hidden categories — unlike favorites/folders above, no local-row dependency at all
        // (a category id is just a string, not tied to a fetched item), so this only needs the
        // URL to resolve to a serverIndex, not that server's categories to actually exist yet.
        val pendingHidden = prefs.pendingHiddenMergedVodCategories.first()
        if (pendingHidden.isNotEmpty()) {
            val remaining = pendingHidden.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val categoryId = parts.getOrNull(1)
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && categoryId != null) {
                    prefs.addHiddenMergedVodCategoryIds(setOf("$serverIndex:$categoryId"))
                    false
                } else true
            }.toSet()
            if (remaining != pendingHidden) prefs.setPendingHiddenMergedVodCategories(remaining)
        }
    }

    /** Series equivalent of applyPendingMergedVodRestoreData — same shape, seriesId instead of
     * streamId. */
    private suspend fun applyPendingMergedSeriesRestoreData(refreshedServers: List<ConfiguredServer>) {
        val urlByServerIndex = refreshedServers.associate { it.serverUrl to it.serverIndex }

        val pendingFavorites = prefs.pendingMergedSeriesFavorites.first()
        if (pendingFavorites.isNotEmpty()) {
            val remaining = pendingFavorites.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val seriesId = parts.getOrNull(1)?.toIntOrNull()
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && seriesId != null) {
                    val series = db.mergedSeriesDao().getByIndexAndId(serverIndex, seriesId)
                    if (series != null) {
                        db.mergedSeriesDao().setFavorite(serverIndex, seriesId, true)
                        false
                    } else true
                } else true
            }.toSet()
            if (remaining != pendingFavorites) prefs.setPendingMergedSeriesFavorites(remaining)
        }

        val pendingFolders = prefs.pendingMergedSeriesFolders.first()
        if (pendingFolders.isNotEmpty()) {
            val existingFolders = db.favoriteFolderDao().getAll().first()
            val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
            var nextOrder = existingFolders.size
            val remaining = pendingFolders.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val seriesId = parts.getOrNull(1)?.toIntOrNull()
                val folderName = parts.getOrNull(2)
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && seriesId != null && folderName != null) {
                    val series = db.mergedSeriesDao().getByIndexAndId(serverIndex, seriesId)
                    if (series != null) {
                        var folderId = idByName[folderName]
                        if (folderId == null) {
                            folderId = db.favoriteFolderDao().insert(
                                com.iptvapp.data.local.entities.FavoriteFolderEntity(name = folderName, sortOrder = nextOrder++)
                            ).toInt()
                            idByName[folderName] = folderId
                        }
                        db.mergedSeriesDao().setFavoriteFolder(serverIndex, seriesId, folderId)
                        false
                    } else true
                } else true
            }.toSet()
            if (remaining != pendingFolders) prefs.setPendingMergedSeriesFolders(remaining)
        }

        val pendingHidden = prefs.pendingHiddenMergedSeriesCategories.first()
        if (pendingHidden.isNotEmpty()) {
            val remaining = pendingHidden.filter { key ->
                val parts = key.split("|")
                val url = parts.getOrNull(0)
                val categoryId = parts.getOrNull(1)
                val serverIndex = url?.let { urlByServerIndex[it] }
                if (serverIndex != null && categoryId != null) {
                    prefs.addHiddenMergedSeriesCategoryIds(setOf("$serverIndex:$categoryId"))
                    false
                } else true
            }.toSet()
            if (remaining != pendingHidden) prefs.setPendingHiddenMergedSeriesCategories(remaining)
        }
    }

    /** Movies-tab equivalent of refreshMergedChannels — same per-server fetch/timeout/
     * clear-only-successful-servers/preserve-favorites shape, sourced from get_vod_categories
     * and get_vod_streams instead of the live-channel endpoints. Deliberately a separate
     * refresh action from refreshMergedChannels (not folded into "Refresh All Providers"),
     * since VOD catalogs are typically much larger than live channel lists and would make an
     * already-slow multi-provider refresh noticeably slower for users who only care about live
     * channels — matches the existing precedent of Live/Movies/Series having independent
     * refresh buttons for the primary provider too. */
    suspend fun refreshMergedVod(
        targetServerIndex: Int? = null,
        onProgress: (completedServers: Int, totalServers: Int, itemsSoFar: Int) -> Unit = { _, _, _ -> }
    ): Map<Int, String> {
        val servers = allConfiguredServers().let { all ->
            if (targetServerIndex == null) all else all.filter { it.serverIndex == targetServerIndex }
        }
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = mutableMapOf<Int, String>()
        val results = mutableListOf<MergedVodEntity>()
        val mergedUserData = db.mergedVodDao().getUserData().associateBy { it.serverIndex to it.streamId }
        coroutineScope {
            servers.map { server ->
                async {
                    try {
                        // Movie catalogs are typically far larger/slower to enumerate than live
                        // channel lists (which use a 15s budget just above/below) — 15s here was
                        // timing out both configured providers every time in practice, even
                        // though the request was genuinely still in flight, not actually stuck.
                        kotlinx.coroutines.withTimeout(60_000) {
                            val builder = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
                            val catResponse = api.getVodCategories(builder.apiUrl(), server.username, server.password)
                            val categoryNames = if (catResponse.isSuccessful) {
                                (catResponse.body() ?: emptyList()).associate { it.categoryId to it.categoryName }
                            } else emptyMap()
                            val response = api.getVodStreams(builder.apiUrl(), server.username, server.password)
                            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
                            val list = response.body() ?: emptyList()
                            synchronized(results) {
                                results.addAll(list.map {
                                    val prev = mergedUserData[server.serverIndex to it.streamId]
                                    MergedVodEntity(
                                        serverIndex = server.serverIndex,
                                        streamId = it.streamId,
                                        name = it.name,
                                        streamIcon = it.streamIcon,
                                        serverNickname = server.nickname,
                                        categoryId = it.categoryId,
                                        categoryName = it.categoryId?.let { id -> categoryNames[id] } ?: "Uncategorized",
                                        rating = it.rating,
                                        containerExtension = it.containerExtension,
                                        added = it.added,
                                        isFavorite = prev?.isFavorite ?: false,
                                        favoriteFolderId = prev?.favoriteFolderId,
                                        watchedMs = prev?.watchedMs ?: 0L,
                                        durationMs = prev?.durationMs ?: 0L,
                                        isHidden = prev?.isHidden ?: false
                                    )
                                })
                            }
                        }
                    } catch (e: Exception) {
                        val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else e.message
                        android.util.Log.e("MergedVod", "serverIndex=${server.serverIndex} (${server.nickname}) failed: $msg", e)
                        errors[server.serverIndex] = msg ?: "Unknown error"
                    } finally {
                        onProgress(completedCount.incrementAndGet(), servers.size, results.size)
                    }
                }
            }.forEach { it.await() }
        }
        results.map { it.serverIndex }.distinct().forEach { serverIndex ->
            db.mergedVodDao().clearForServer(serverIndex)
        }
        db.mergedVodDao().upsertAll(results)
        applyPendingMergedVodRestoreData(servers)
        return errors
    }

    fun getMergedVodServerSummaries(): Flow<List<MergedVodServerSummary>> = db.mergedVodDao().getServerSummaries()

    fun getMergedVodCategorySummaries(serverIndex: Int): Flow<List<MergedVodCategorySummary>> =
        db.mergedVodDao().getCategorySummaries(serverIndex)

    fun getMergedVodByCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedVodEntity>> =
        db.mergedVodDao().getByServerAndCategory(serverIndex, categoryId)

    suspend fun getMergedVodByIndexAndId(serverIndex: Int, streamId: Int): MergedVodEntity? =
        db.mergedVodDao().getByIndexAndId(serverIndex, streamId)

    fun searchMergedVod(query: String): Flow<List<MergedVodEntity>> = db.mergedVodDao().search(query)

    suspend fun setMergedVodFavorite(vod: MergedVodEntity, isFavorite: Boolean) {
        db.mergedVodDao().setFavorite(vod.serverIndex, vod.streamId, isFavorite)
    }

    suspend fun setMergedVodFolder(vod: MergedVodEntity, folderId: Int?) {
        db.mergedVodDao().setFavoriteFolder(vod.serverIndex, vod.streamId, folderId)
    }

    fun getHiddenMergedVod(): Flow<List<MergedVodEntity>> = db.mergedVodDao().getHidden()
    suspend fun bulkHideMergedVod(serverIndex: Int, streamIds: List<Int>) =
        db.mergedVodDao().bulkSetHidden(serverIndex, streamIds)

    suspend fun saveMergedVodProgress(serverIndex: Int, streamId: Int, watchedMs: Long, durationMs: Long) {
        db.mergedVodDao().updateWatchProgress(serverIndex, streamId, watchedMs, durationMs)
    }

    suspend fun getMergedVodProgress(serverIndex: Int, streamId: Int): Pair<Long, Long> {
        val watched = db.mergedVodDao().getWatchedMs(serverIndex, streamId) ?: 0L
        val duration = db.mergedVodDao().getDurationMs(serverIndex, streamId) ?: 0L
        return Pair(watched, duration)
    }

    /** Same per-server-credentialed URL-building precedent as getMergedLiveStreamUrl. */
    suspend fun getMergedVodStreamUrl(serverIndex: Int, streamId: Int, containerExtension: String): String {
        val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex }
            ?: throw Exception("Server no longer configured")
        return XtreamUrlBuilder(server.serverUrl, server.username, server.password).vodStreamUrl(streamId, containerExtension)
    }

    /** Series-tab equivalent of refreshMergedVod — same shape, sourced from get_series_categories
     * and get_series instead of the VOD endpoints. Only series METADATA is cached here; season/
     * episode data is fetched on demand per-open via fetchMergedSeriesInfo below and never
     * cached, matching how the primary provider's SeriesDetailActivity already works. */
    suspend fun refreshMergedSeries(
        targetServerIndex: Int? = null,
        onProgress: (completedServers: Int, totalServers: Int, itemsSoFar: Int) -> Unit = { _, _, _ -> }
    ): Map<Int, String> {
        val servers = allConfiguredServers().let { all ->
            if (targetServerIndex == null) all else all.filter { it.serverIndex == targetServerIndex }
        }
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = mutableMapOf<Int, String>()
        val results = mutableListOf<MergedSeriesEntity>()
        val mergedUserData = db.mergedSeriesDao().getUserData().associateBy { it.serverIndex to it.seriesId }
        coroutineScope {
            servers.map { server ->
                async {
                    try {
                        // Same reasoning as merged VOD's refresh above — series catalogs are
                        // large/slow enough that the 15s budget live channels use was timing out
                        // real, still-in-flight requests rather than catching genuinely stuck ones.
                        kotlinx.coroutines.withTimeout(60_000) {
                            val builder = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
                            val catResponse = api.getSeriesCategories(builder.apiUrl(), server.username, server.password)
                            val categoryNames = if (catResponse.isSuccessful) {
                                (catResponse.body() ?: emptyList()).associate { it.categoryId to it.categoryName }
                            } else emptyMap()
                            val response = api.getSeries(builder.apiUrl(), server.username, server.password)
                            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
                            val list = response.body() ?: emptyList()
                            synchronized(results) {
                                results.addAll(list.map {
                                    val prev = mergedUserData[server.serverIndex to it.seriesId]
                                    MergedSeriesEntity(
                                        serverIndex = server.serverIndex,
                                        seriesId = it.seriesId,
                                        name = it.name,
                                        cover = it.cover,
                                        // Same corrupted-oversized-plot guard as fetchSeries.
                                        plot = it.plot?.take(4000),
                                        genre = it.genre,
                                        rating = it.rating,
                                        serverNickname = server.nickname,
                                        categoryId = it.categoryId,
                                        categoryName = it.categoryId?.let { id -> categoryNames[id] } ?: "Uncategorized",
                                        isFavorite = prev?.isFavorite ?: false,
                                        favoriteFolderId = prev?.favoriteFolderId,
                                        isHidden = prev?.isHidden ?: false
                                    )
                                })
                            }
                        }
                    } catch (e: Exception) {
                        val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else e.message
                        android.util.Log.e("MergedSeries", "serverIndex=${server.serverIndex} (${server.nickname}) failed: $msg", e)
                        errors[server.serverIndex] = msg ?: "Unknown error"
                    } finally {
                        onProgress(completedCount.incrementAndGet(), servers.size, results.size)
                    }
                }
            }.forEach { it.await() }
        }
        results.map { it.serverIndex }.distinct().forEach { serverIndex ->
            db.mergedSeriesDao().clearForServer(serverIndex)
        }
        db.mergedSeriesDao().upsertAll(results)
        applyPendingMergedSeriesRestoreData(servers)
        return errors
    }

    fun getMergedSeriesServerSummaries(): Flow<List<MergedSeriesServerSummary>> = db.mergedSeriesDao().getServerSummaries()

    fun getMergedSeriesCategorySummaries(serverIndex: Int): Flow<List<MergedSeriesCategorySummary>> =
        db.mergedSeriesDao().getCategorySummaries(serverIndex)

    fun getMergedSeriesByCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedSeriesEntity>> =
        db.mergedSeriesDao().getByServerAndCategory(serverIndex, categoryId)

    suspend fun getMergedSeriesByIndexAndId(serverIndex: Int, seriesId: Int): MergedSeriesEntity? =
        db.mergedSeriesDao().getByIndexAndId(serverIndex, seriesId)

    fun searchMergedSeries(query: String): Flow<List<MergedSeriesEntity>> = db.mergedSeriesDao().search(query)

    suspend fun setMergedSeriesFavorite(series: MergedSeriesEntity, isFavorite: Boolean) {
        db.mergedSeriesDao().setFavorite(series.serverIndex, series.seriesId, isFavorite)
    }

    suspend fun setMergedSeriesFolder(series: MergedSeriesEntity, folderId: Int?) {
        db.mergedSeriesDao().setFavoriteFolder(series.serverIndex, series.seriesId, folderId)
    }

    /** Favorites every series in a merged category at once — long-press on a category in the
     * Providers > Series browse view, instead of favoriting one show at a time. */
    suspend fun setMergedSeriesFavoriteForCategory(serverIndex: Int, categoryId: String?, folderId: Int?) {
        db.mergedSeriesDao().setFavoriteForCategory(serverIndex, categoryId, folderId)
    }

    fun getHiddenMergedSeries(): Flow<List<MergedSeriesEntity>> = db.mergedSeriesDao().getHidden()
    suspend fun bulkHideMergedSeries(serverIndex: Int, seriesIds: List<Int>) =
        db.mergedSeriesDao().bulkSetHidden(serverIndex, seriesIds)
    suspend fun unhideMergedSeries(serverIndex: Int, seriesId: Int) =
        db.mergedSeriesDao().setUnhidden(serverIndex, seriesId)

    /** Merged-series equivalent of fetchSeriesInfo — fetches season/episode data from the
     * SPECIFIC server this series belongs to, never cached (see MergedSeriesEntity kdoc). */
    suspend fun fetchMergedSeriesInfo(serverIndex: Int, seriesId: Int): Resource<SeriesInfo> {
        val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex }
            ?: return Resource.Error("Server no longer configured")
        val b = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
        return safeApiCall {
            val response = api.getSeriesInfo(b.apiUrl(), server.username, server.password, seriesId = seriesId)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            response.body() ?: throw Exception("Empty response")
        }
    }

    /** Same per-server-credentialed URL-building precedent as getMergedVodStreamUrl. */
    suspend fun getMergedSeriesEpisodeUrl(serverIndex: Int, episodeId: String, containerExtension: String): String {
        val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex }
            ?: throw Exception("Server no longer configured")
        return XtreamUrlBuilder(server.serverUrl, server.username, server.password).seriesStreamUrl(episodeId, containerExtension)
    }

    fun getMergedServerSummaries(): Flow<List<MergedServerSummary>> = db.mergedChannelDao().getServerSummaries()

    fun getMergedCategorySummaries(serverIndex: Int): Flow<List<MergedCategorySummary>> =
        db.mergedChannelDao().getCategorySummaries(serverIndex)

    fun getMergedChannelsByCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedChannelEntity>> =
        db.mergedChannelDao().getByServerAndCategory(serverIndex, categoryId)

    suspend fun getMergedChannelByIndexAndId(serverIndex: Int, streamId: Int): MergedChannelEntity? =
        db.mergedChannelDao().getByIndexAndId(serverIndex, streamId)

    fun searchMergedChannels(query: String): Flow<List<MergedChannelEntity>> =
        db.mergedChannelDao().search(query)

    fun getHiddenMergedChannels(): Flow<List<MergedChannelEntity>> = db.mergedChannelDao().getHidden()
    suspend fun bulkHideMergedChannels(serverIndex: Int, streamIds: List<Int>) =
        db.mergedChannelDao().bulkSetHidden(serverIndex, streamIds)
    suspend fun unhideMergedChannel(serverIndex: Int, streamId: Int) =
        db.mergedChannelDao().unhide(serverIndex, streamId)

    suspend fun bulkSetMergedChannelManualGenre(serverIndex: Int, streamIds: List<Int>, genre: String?) =
        db.mergedChannelDao().bulkSetManualGenre(serverIndex, streamIds, genre)

    // Merged-channel favorites/folders — separate from the primary provider's Favorites tab
    // (see MergedChannelEntity kdoc), but reusing the same FavoriteFolderEntity rows so folder
    // names are one shared list rather than two parallel systems.
    /** Now/next guide line for a merged channel, fetched from that channel's OWN server via
     * get_short_epg — kept out of the epg_entries table entirely (it's keyed by the primary
     * server's streamIds, which merged channels would collide with) and returned as ready-made
     * display text instead, matching the "NOW: X (12m)  •  NEXT: Y" format the primary channel
     * list builds in HomeViewModel.publishEpgDisplay. Null when the server has no guide data. */
    suspend fun fetchMergedShortEpgText(serverIndex: Int, streamId: Int): String? {
        return try {
            val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex } ?: return null
            val b = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
            val response = api.getShortEpg(b.apiUrl(), server.username, server.password, streamId = streamId)
            if (!response.isSuccessful) return null
            val listings = response.body()?.epgListings ?: return null
            val now = listings.firstOrNull() ?: return null
            val next = listings.drop(1).firstOrNull()
            val nowSecs = System.currentTimeMillis() / 1000
            val minutesLeft = ((now.stopTimestamp - nowSecs) / 60).coerceAtLeast(0)
            val timeStr = if (minutesLeft > 0) " (${minutesLeft}m)" else ""
            val nowTitle = decodeBase64(now.title)
            if (next != null) "NOW: $nowTitle$timeStr  •  NEXT: ${decodeBase64(next.title)}"
            else "NOW: $nowTitle$timeStr"
        } catch (_: Exception) {
            null
        }
    }

    data class MergedEpgNowNext(
        val nowTitle: String,
        val nowStartMs: Long,
        val nowStopMs: Long,
        val nextTitle: String?
    )

    /** Same server lookup + get_short_epg call as fetchMergedShortEpgText, but returns the raw
     * now/next timing instead of a pre-formatted string — needed so PlayerActivity's live OSD
     * can compute a real progress bar for a merged channel the same way it already does for
     * primary channels, instead of only ever showing static list-row text. Also kept out of the
     * epg_entries table for the same reason as fetchMergedShortEpgText (bare-streamId collision
     * risk across servers). */
    suspend fun fetchMergedEpgNowNext(serverIndex: Int, streamId: Int): MergedEpgNowNext? {
        return try {
            val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex } ?: return null
            val b = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
            val response = api.getShortEpg(b.apiUrl(), server.username, server.password, streamId = streamId)
            if (!response.isSuccessful) return null
            val listings = response.body()?.epgListings ?: return null
            val now = listings.firstOrNull() ?: return null
            val next = listings.drop(1).firstOrNull()
            MergedEpgNowNext(
                nowTitle = decodeBase64(now.title),
                nowStartMs = now.startTimestamp * 1000L,
                nowStopMs = now.stopTimestamp * 1000L,
                nextTitle = next?.let { decodeBase64(it.title) }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Full-timeline EPG for one merged/secondary-provider channel — the multi-listing sibling
     * of fetchMergedShortEpgText/fetchMergedEpgNowNext, which only ever kept the first 1-2
     * listings and never persisted anything. This maps every listing get_short_epg returns
     * (not just now/next) into real EpgEntity rows stamped with this server's serverIndex, so
     * merged-provider channels can appear in the Guide tab's timeline the same way primary
     * channels do. Still only a few hours to ~1 day deep (whatever the panel's short-epg page
     * returns) — not the multi-day depth XMLTV gives, hence fetchXmltvEpgForMergedServer below
     * as the primary/deeper mechanism, with this as a fallback for channels XMLTV didn't cover. */
    suspend fun fetchMergedEpg(serverIndex: Int, streamId: Int): List<EpgEntity> {
        return try {
            val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex } ?: return emptyList()
            val b = XtreamUrlBuilder(server.serverUrl, server.username, server.password)
            val response = api.getShortEpg(b.apiUrl(), server.username, server.password, streamId = streamId)
            if (!response.isSuccessful) return emptyList()
            val listings = response.body()?.epgListings ?: return emptyList()
            val entities = listings.map {
                EpgEntity(
                    serverIndex = serverIndex,
                    id = it.id,
                    streamId = streamId,
                    title = decodeBase64(it.title),
                    description = decodeBase64(it.description),
                    startTimestamp = it.startTimestamp,
                    stopTimestamp = it.stopTimestamp,
                    nowPlaying = it.nowPlaying,
                    hasArchive = it.hasArchive
                )
            }
            db.epgDao().upsertEpg(entities)
            entities
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Bulk XMLTV fetch for one merged/secondary server — mirrors fetchXmltvEpg's body but
     * resolves channels against that server's own merged_channels rows (not the primary
     * provider's ChannelEntity table) and stamps every row with serverIndex. One HTTP request
     * per server regardless of channel count, same as the primary path — this is why it's the
     * low-rate-limit-risk way to get merged providers real timeline depth, rather than an
     * unpaced per-channel loop. Never throws — returns 0 on any failure. */
    suspend fun fetchXmltvEpgForMergedServer(serverIndex: Int): Int = withContext(Dispatchers.IO) {
        val tag = "MergedXmltv"
        val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex } ?: run {
            android.util.Log.w(tag, "serverIndex=$serverIndex: not in allConfiguredServers (disabled or removed)")
            return@withContext 0
        }
        if (server.serverUrl.isBlank()) {
            android.util.Log.w(tag, "serverIndex=$serverIndex (${server.nickname}): blank serverUrl")
            return@withContext 0
        }
        // Try the provider's own custom EPG URL first (Settings > Providers > Edit > EPG URL) —
        // previously ignored entirely here, so a provider whose real XMLTV feed lives at a
        // different URL than its Xtream panel's default /xmltv.php path (exactly the case the
        // per-provider EPG URL field exists for) always got zero channels/programs back, since
        // the default path either 404s or serves an empty feed for that panel. Falls back to the
        // default path if no custom URL is set, or if the custom one returns nothing.
        val sources = listOfNotNull(
            server.epgUrl.takeIf { it.isNotBlank() },
            XmltvFetcher.buildUrl(server.serverUrl, server.username, server.password)
        ).distinct()

        val channels = db.mergedChannelDao().getAllForServer(serverIndex)
        if (channels.isEmpty()) {
            android.util.Log.w(tag, "serverIndex=$serverIndex (${server.nickname}): no cached merged_channels rows for this server — channels haven't been refreshed yet")
            return@withContext 0
        }
        // Multiple local channels commonly share one epgChannelId — HD/SD/EAST/WEST variants of
        // the same network all carry their network's single EPG id (e.g. "US: USA NETWORK HD",
        // "US: USA NETWORK WEST HD", "US: USA NETWORK EAST HD" all use "usanetwork.us"). A
        // single-valued map here meant only the last channel processed for a given id kept its
        // match — every earlier variant silently lost its EPG entirely. Both maps are one-to-many
        // so every variant gets the same programs.
        val byEpgId = mutableMapOf<String, MutableList<Int>>()
        val byName = mutableMapOf<String, MutableList<Int>>()
        channels.forEach { ch ->
            if (!ch.epgChannelId.isNullOrBlank()) byEpgId.getOrPut(ch.epgChannelId.lowercase()) { mutableListOf() }.add(ch.streamId)
            byName.getOrPut(normalizeForMatch(ch.name)) { mutableListOf() }.add(ch.streamId)
        }

        try {
            var xmlChannels = emptyList<com.iptvapp.util.XmltvChannel>()
            var xmlPrograms = emptyList<com.iptvapp.util.XmltvProgram>()
            for (url in sources) {
                val (ch, pr) = XmltvFetcher.fetch(url)
                android.util.Log.d(tag, "serverIndex=$serverIndex (${server.nickname}): source ${com.iptvapp.util.LogSanitizer.redactCredentials(url)} returned ${ch.size} channels, ${pr.size} programs")
                if (pr.isNotEmpty()) { xmlChannels = ch; xmlPrograms = pr; break }
            }
            if (xmlPrograms.isEmpty()) {
                android.util.Log.w(tag, "serverIndex=$serverIndex (${server.nickname}): every source gave zero programs — provider may not offer XMLTV at these URLs, or the request failed silently (see XmltvFetcher.fetch, which swallows errors and returns empty)")
                return@withContext 0
            }

            // See EpgDao.deleteAllForServer's kdoc — upsert alone can leave stale, WRONG rows
            // behind under an old streamId when re-matching resolves a channel differently than a
            // previous fetch did. Placed after the empty-programs guard above so a failed/empty
            // fetch doesn't wipe out this server's still-good previous data for nothing.
            db.epgDao().deleteAllForServer(serverIndex)

            // Same byEpgId-first matching the primary provider's fetchXmltvFromUrl already used
            // (see MergedChannelEntity.epgChannelId kdoc for why this was missing here) — a
            // stable provider-assigned ID match is far more reliable than fuzzy channel-name
            // matching, which previously could resolve XMLTV entries to entirely different local
            // channels than the ones actually favorited on a provider with a large/messy feed.
            // The substring fallback that used to sit here (matching e.g. "ESPN" against
            // "ESPN2"/"ESPN News"/"ESPN Deportes" and grabbing whichever happened to be first)
            // is gone for the same reason as fetchXmltvFromUrl's: on a large catalog it routinely
            // picked the wrong channel and silently wrote one network's schedule under a
            // completely different channel's streamId.
            val xmlChannelToStreamIds = mutableMapOf<String, List<Int>>()
            xmlChannels.forEach { xmlCh ->
                val normXml = normalizeForMatch(xmlCh.displayName)
                val resolved = byEpgId[xmlCh.id.lowercase()] ?: byName[normXml]
                if (resolved != null) xmlChannelToStreamIds[xmlCh.id] = resolved
            }
            android.util.Log.d(tag, "serverIndex=$serverIndex (${server.nickname}): matched ${xmlChannelToStreamIds.size}/${xmlChannels.size} xmltv channels to ${xmlChannelToStreamIds.values.sumOf { it.size }} local channels (byEpgId available for ${byEpgId.size}/${channels.size} local channels)")

            val nowSec = System.currentTimeMillis() / 1000
            val entities = mutableListOf<EpgEntity>()
            xmlPrograms.forEach { prog ->
                val streamIds = xmlChannelToStreamIds[prog.channelId] ?: return@forEach
                streamIds.forEach { streamId ->
                    entities.add(EpgEntity(
                        serverIndex    = serverIndex,
                        id             = "x_${prog.channelId}_${streamId}_${prog.startSec}",
                        streamId       = streamId,
                        title          = prog.title,
                        description    = prog.description,
                        startTimestamp = prog.startSec,
                        stopTimestamp  = prog.stopSec,
                        nowPlaying     = if (prog.startSec <= nowSec && prog.stopSec > nowSec) 1 else 0,
                        hasArchive     = 0
                    ))
                }
            }
            android.util.Log.d(tag, "serverIndex=$serverIndex (${server.nickname}): saving ${entities.size} EPG entries after channel-match filtering")
            entities.chunked(500).forEach { db.epgDao().upsertEpg(it) }
            entities.size
        } catch (e: Exception) {
            android.util.Log.e(tag, "serverIndex=$serverIndex (${server.nickname}): failed", e)
            0
        }
    }

    fun getMergedAllFavorites(): Flow<List<MergedChannelEntity>> =
        db.mergedChannelDao().getAllFavorites()
    // Movies/Series equivalents — aggregate favorites across EVERY configured secondary
    // provider, backing the "★ Favorites" entry at the top of the Providers tab's server
    // picker (see HomeViewModel.selectMergedVodAllFavoritesAcrossServers/
    // selectMergedSeriesAllFavoritesAcrossServers).
    fun getMergedVodAllFavorites(): Flow<List<MergedVodEntity>> = db.mergedVodDao().getAllFavorites()
    fun getMergedSeriesAllFavorites(): Flow<List<MergedSeriesEntity>> = db.mergedSeriesDao().getAllFavorites()
    fun getMergedFavoritesInFolder(folderId: Int): Flow<List<MergedChannelEntity>> =
        db.mergedChannelDao().getFavoritesInFolder(folderId)
    fun getMergedUnfiledFavorites(): Flow<List<MergedChannelEntity>> =
        db.mergedChannelDao().getUnfiledFavorites()
    fun getMergedFavoriteCountsByFolder(): Flow<List<com.iptvapp.data.local.dao.FavoriteFolderCount>> =
        db.mergedChannelDao().getFavoriteCountsByFolder()
    suspend fun setMergedChannelFavorite(serverIndex: Int, streamId: Int, favorite: Boolean) =
        db.mergedChannelDao().setFavorite(serverIndex, streamId, favorite)
    suspend fun setMergedChannelFolder(serverIndex: Int, streamId: Int, folderId: Int?) =
        db.mergedChannelDao().setFavoriteFolder(serverIndex, streamId, folderId)

    /** Bulk favorite/unfavorite for merged channels, keyed by the same "$serverIndex:$streamId"
     * composite string used throughout the merged-channel UI layer. No dedicated bulk SQL query
     * (unlike ChannelDao.bulkSetFavorite's single-column IN) since the composite key can't be
     * expressed that way without a join table — a plain loop is fine at bulk-select's scale
     * (dozens of rows, not thousands). */
    suspend fun bulkSetMergedChannelFavorite(keys: Collection<String>, favorite: Boolean) {
        keys.forEach { key ->
            val (serverIndex, streamId) = key.split(":", limit = 2).let { it[0].toInt() to it[1].toInt() }
            db.mergedChannelDao().setFavorite(serverIndex, streamId, favorite)
            android.util.Log.d("BulkRemoveDebug", "setFavorite(serverIndex=$serverIndex, streamId=$streamId, favorite=$favorite) executed")
        }
    }

    /** Builds a playback URL using the specific server a merged channel came from, not
     * whatever's currently the primary/active server. Uses "ts" rather than "m3u8" — confirmed
     * against a real provider that some Xtream panels ignore the requested extension entirely
     * and always serve raw MPEG-TS bytes regardless (curl against a ".m3u8" URL here returned
     * HTTP 200 with Content-Type video/mp2t, i.e. actual TS video data, not an HLS playlist).
     * ExoPlayer picks its parser from the URL's extension, so requesting ".m3u8" against a
     * server that responds with raw TS makes it try to parse binary video as a text playlist —
     * ERROR_CODE_PARSING_MANIFEST_MALFORMED, endless retries, channel never plays. ".ts" makes
     * ExoPlayer use the TS extractor directly, matching what these servers actually send. */
    suspend fun getMergedLiveStreamUrl(serverIndex: Int, streamId: Int): String {
        val server = allConfiguredServers().firstOrNull { it.serverIndex == serverIndex }
            ?: throw Exception("Server no longer configured")
        return XtreamUrlBuilder(server.serverUrl, server.username, server.password).liveStreamUrl(streamId, "ts")
    }

    /** Live-playback failover match, found by com.iptvapp.util.ChannelNameMatcher's normalized-
     * name comparison — see its kdoc for why name comparison is the only cross-provider signal
     * available at all (no shared per-channel ID exists across different Xtream panels). Excludes
     * excludeServerIndex (the channel that just failed) so it never "fails over" back to the same
     * dead provider. Checks primary first, then each configured extra provider in order, and
     * returns the first hit — good enough for the common case (a handful of configured
     * providers), not worth ranking/scoring multiple candidates for.
     * @return (serverIndex, streamId, name) of the match, or null if no other provider has it. */
    suspend fun findFailoverChannel(name: String, excludeServerIndex: Int): Triple<Int, Int, String>? {
        val servers = allConfiguredServers().filter { it.serverIndex != excludeServerIndex }
        for (server in servers) {
            val candidates = if (server.serverIndex == -1) {
                db.channelDao().getAllChannels().first().map { Triple(-1, it.streamId, it.name) }
            } else {
                db.mergedChannelDao().getAllForServer(server.serverIndex).map { Triple(it.serverIndex, it.streamId, it.name) }
            }
            val hit = candidates.firstOrNull { com.iptvapp.util.ChannelNameMatcher.matches(it.third, name) }
            if (hit != null) return hit
        }
        return null
    }
}