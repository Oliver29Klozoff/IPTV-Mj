package com.iptvapp.data.local.dao

import androidx.room.*
import com.iptvapp.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY num ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY num ASC")
    fun getChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE lastWatched IS NOT NULL ORDER BY lastWatched DESC LIMIT 20")
    fun getRecentChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' ORDER BY num ASC")
    fun searchChannels(query: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE streamId = :streamId")
    suspend fun getChannelById(streamId: Int): ChannelEntity?
    @Upsert
    suspend fun upsertChannels(channels: List<ChannelEntity>)
    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE streamId = :streamId")
    suspend fun setFavorite(streamId: Int, isFavorite: Boolean)
    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE categoryId = :categoryId")
    suspend fun setFavoriteForCategory(categoryId: String, isFavorite: Boolean)
    @Query("UPDATE channels SET lastWatched = :timestamp WHERE streamId = :streamId")
    suspend fun updateLastWatched(streamId: Int, timestamp: Long = System.currentTimeMillis())
    @Query("SELECT COUNT(*) FROM channels")
    suspend fun getCount(): Int
    @Query("SELECT COUNT(*) FROM channels WHERE isFavorite = 1")
    suspend fun getFavoriteCount(): Int

    @Query("SELECT streamId FROM channels WHERE isFavorite = 1")
    suspend fun getFavoriteChannelIds(): List<Int>

    @Query("UPDATE channels SET isFavorite = 0")
    suspend fun clearAllFavorites()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY categoryName ASC")
    fun getCategoriesByType(type: String): Flow<List<CategoryEntity>>
    @Upsert
    suspend fun upsertCategories(categories: List<CategoryEntity>)
    @Query("DELETE FROM categories WHERE type = :type")
    suspend fun deleteCategoriesByType(type: String)
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_streams ORDER BY added DESC, name ASC")
    fun getAllVod(): Flow<List<VodEntity>>
    @Query("SELECT * FROM vod_streams WHERE categoryId = :categoryId ORDER BY added DESC, name ASC")
    fun getVodByCategory(categoryId: String): Flow<List<VodEntity>>
    @Query("SELECT * FROM vod_streams WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteVod(): Flow<List<VodEntity>>
    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchVod(query: String): Flow<List<VodEntity>>
    @Upsert
    suspend fun upsertVod(vod: List<VodEntity>)
    @Query("UPDATE vod_streams SET isFavorite = :isFavorite WHERE streamId = :streamId")
    suspend fun setFavorite(streamId: Int, isFavorite: Boolean)
    @Query("SELECT COUNT(*) FROM vod_streams")
    suspend fun getCount(): Int
    @Query("UPDATE vod_streams SET watchedMs = :watchedMs, durationMs = :durationMs WHERE streamId = :streamId")
    suspend fun updateWatchProgress(streamId: Int, watchedMs: Long, durationMs: Long)
    @Query("SELECT watchedMs FROM vod_streams WHERE streamId = :streamId")
    suspend fun getWatchedMs(streamId: Int): Long
    @Query("SELECT durationMs FROM vod_streams WHERE streamId = :streamId")
    suspend fun getDurationMs(streamId: Int): Long
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY name ASC")
    fun getAllSeries(): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name ASC")
    fun getSeriesByCategory(categoryId: String): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteSeries(): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchSeries(query: String): Flow<List<SeriesEntity>>
    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)
    @Query("UPDATE series SET isFavorite = :isFavorite WHERE seriesId = :seriesId")
    suspend fun setFavorite(seriesId: Int, isFavorite: Boolean)
    @Query("SELECT COUNT(*) FROM series")
    suspend fun getCount(): Int
    @Query("UPDATE series SET watchedMs = :watchedMs, durationMs = :durationMs WHERE seriesId = :seriesId")
    suspend fun updateWatchProgress(seriesId: Int, watchedMs: Long, durationMs: Long)
    @Query("SELECT watchedMs FROM series WHERE seriesId = :streamId")
    suspend fun getWatchedMs(streamId: Int): Long
    @Query("SELECT durationMs FROM series WHERE seriesId = :streamId")
    suspend fun getDurationMs(streamId: Int): Long
}

@Dao
interface EpgDao {
    @Query("SELECT COUNT(*) FROM epg_entries")
    suspend fun getEpgCount(): Int
    @Query("SELECT * FROM epg_entries WHERE streamId = :streamId ORDER BY startTimestamp ASC")
    fun getEpgForStream(streamId: Int): Flow<List<EpgEntity>>
    @Query("SELECT * FROM epg_entries WHERE streamId IN (:streamIds) ORDER BY streamId ASC, startTimestamp ASC")
    fun getEpgForStreams(streamIds: List<Int>): Flow<List<EpgEntity>>
    @Query("SELECT DISTINCT streamId FROM epg_entries")
    suspend fun getStreamIdsWithEpg(): List<Int>
    @Query("SELECT MIN(startTimestamp) FROM epg_entries")
    suspend fun getOldestEpgStartTimestamp(): Long?
    @Query("SELECT MAX(stopTimestamp) FROM epg_entries")
    suspend fun getNewestEpgStopTimestamp(): Long?
    @Query("SELECT * FROM epg_entries WHERE streamId = :streamId AND nowPlaying = 1 LIMIT 1")
    suspend fun getNowPlaying(streamId: Int): EpgEntity?
    @Upsert
    suspend fun upsertEpg(entries: List<EpgEntity>)
    @Query("DELETE FROM epg_entries WHERE stopTimestamp < :before")
    suspend fun deleteExpiredEpg(before: Long = System.currentTimeMillis() / 1000)
}