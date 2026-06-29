package com.iptvapp.data.repository

import android.util.Base64
import com.iptvapp.data.api.*
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dao.ChannelUserData
import com.iptvapp.data.local.entities.*
import com.iptvapp.util.M3uParser
import com.iptvapp.util.Resource
import com.iptvapp.util.safeApiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        db.clearAllTables()
    }

    suspend fun fetchLiveCategories(): Resource<List<Category>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getLiveCategories(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
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
                    isHidden = prev?.isHidden ?: false
                )
            })
            prefs.setLastChannelsFetchTime(System.currentTimeMillis())
            list
        }
    }

    suspend fun isChannelCacheStale(maxAgeMs: Long = 4 * 60 * 60 * 1000L): Boolean {
        val lastFetch = prefs.lastChannelsFetchTime.first()
        return lastFetch == 0L || System.currentTimeMillis() - lastFetch > maxAgeMs
    }

    fun getAllChannels(): Flow<List<ChannelEntity>> = db.channelDao().getAllChannels()

    suspend fun getChannelCount(): Int = db.channelDao().getCount()

    suspend fun getNewestEpgStop(): Long? = db.epgDao().getNewestEpgStopTimestamp()

    fun getChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>> =
        db.channelDao().getChannelsByCategory(categoryId)

    fun searchChannels(query: String): Flow<List<ChannelEntity>> =
        db.channelDao().searchChannels(query)

    fun getFavoriteChannels(): Flow<List<ChannelEntity>> =
        db.channelDao().getFavoriteChannels()

    fun getFavoriteLiveCategoryIds(): Flow<Set<String>> =
        prefs.favoriteLiveCategoryIds

    fun getRecentChannels(): Flow<List<ChannelEntity>> =
        db.channelDao().getRecentChannels()

    suspend fun getChannelById(streamId: Int) = db.channelDao().getChannelById(streamId)

    suspend fun isChannelFavorite(streamId: Int): Boolean {
        return db.channelDao().getChannelById(streamId)?.isFavorite ?: false
    }

    suspend fun toggleChannelFavorite(streamId: Int) {
        val ch = db.channelDao().getChannelById(streamId) ?: return
        db.channelDao().setFavorite(streamId, !ch.isFavorite)
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

    fun getSimilarChannels(categoryId: String, excludeStreamId: Int): Flow<List<ChannelEntity>> =
        db.channelDao().getSimilarChannels(categoryId, excludeStreamId)

    suspend fun setLiveCategoryFavorite(categoryId: String, isFavorite: Boolean) {
        if (isFavorite) {
            prefs.addFavoriteLiveCategoryId(categoryId)
        } else {
            prefs.removeFavoriteLiveCategoryId(categoryId)
        }
    }

    suspend fun getLiveStreamUrl(streamId: Int): String {
        val channel = db.channelDao().getChannelById(streamId)
        if (channel?.streamUrl != null) return channel.streamUrl
        val format = prefs.preferredFormat.first()
        return urlBuilder().liveStreamUrl(streamId, format)
    }

    suspend fun getLiveStreamUrlForCast(streamId: Int): String {
        // Always build a fresh m3u8 URL — Chromecast Default Media Receiver only supports HLS.
        // streamUrl in DB may be .ts or bare (no extension) which Chromecast cannot play.
        return urlBuilder().liveStreamUrl(streamId, "m3u8")
    }

    suspend fun fetchVodStreams(): Resource<List<VodStream>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getVodStreams(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            db.vodDao().upsertVod(list.map {
                VodEntity(
                    streamId = it.streamId,
                    name = it.name,
                    streamIcon = it.streamIcon,
                    categoryId = it.categoryId,
                    rating = it.rating,
                    containerExtension = it.containerExtension,
                    added = it.added
                )
            })
            list
        }
    }

    suspend fun fetchVodCategories(): Resource<List<Category>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getVodCategories(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            db.categoryDao().deleteCategoriesByType("vod")
            db.categoryDao().upsertCategories(list.map {
                CategoryEntity(it.categoryId, it.categoryName, it.parentId, "vod")
            })
            list
        }
    }

    fun getVodCategories(): Flow<List<CategoryEntity>> = db.categoryDao().getCategoriesByType("vod")

    fun getVodByCategory(categoryId: String): Flow<List<VodEntity>> = db.vodDao().getVodByCategory(categoryId)

    suspend fun getVodStreamUrl(streamId: Int, containerExtension: String): String =
        urlBuilder().vodStreamUrl(streamId, containerExtension)

    fun getAllVod(): Flow<List<VodEntity>> = db.vodDao().getAllVod()

    fun searchVod(query: String): Flow<List<VodEntity>> = db.vodDao().searchVod(query)

    suspend fun fetchSeries(): Resource<List<Series>> {
        val b = urlBuilder(); val c = creds()
        return safeApiCall {
            val response = api.getSeries(b.apiUrl(), c.username, c.password)
            if (!response.isSuccessful) throw Exception("Server returned ${response.code()}")
            val list = response.body() ?: emptyList()
            db.seriesDao().upsertSeries(list.map {
                SeriesEntity(
                    seriesId = it.seriesId,
                    name = it.name,
                    cover = it.cover,
                    plot = it.plot,
                    genre = it.genre,
                    rating = it.rating,
                    categoryId = it.categoryId
                )
            })
            list
        }
    }

    fun getAllSeries(): Flow<List<SeriesEntity>> = db.seriesDao().getAllSeries()

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

    fun getEpgForStreams(streamIds: List<Int>): Flow<List<EpgEntity>> =
        db.epgDao().getEpgForStreams(streamIds)

    fun getInProgressVod(): Flow<List<VodEntity>> = db.vodDao().getInProgressVod()

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

    suspend fun getTimeshiftUrl(streamId: Int, startTimestampSec: Long, durationMinutes: Int): String =
        urlBuilder().timeshiftUrl(streamId, startTimestampSec, durationMinutes)

    suspend fun saveFavOrder(orderedIds: List<Int>) {
        orderedIds.forEachIndexed { index, streamId ->
            db.channelDao().updateFavOrder(streamId, index)
        }
    }

    suspend fun checkStreamHealth(url: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).head().build()
            val code = client.newCall(request).execute().use { it.code }
            code in 200..499
        } catch (e: Exception) {
            false
        }
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
        db.categoryDao().deleteCategoriesByType("m3u")
        db.categoryDao().upsertCategories(groups.mapIndexed { idx, name ->
            CategoryEntity(
                categoryId = "m3u_${name.hashCode().toLong() and 0xFFFFFFFFL}",
                categoryName = name,
                parentId = 0,
                type = "m3u"
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
}