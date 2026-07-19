package com.iptvapp.data.local.dao

import androidx.room.*
import com.iptvapp.data.local.entities.*
import kotlinx.coroutines.flow.Flow

data class ChannelUserData(
    val streamId: Int,
    val isFavorite: Boolean,
    val lastWatched: Long?,
    val viewCount: Int,
    val favOrder: Int,
    val isHidden: Boolean,
    val favoriteFolderId: Int?
)

data class WatchHistoryEntry(
    val streamId: Int,
    val lastWatched: Long,
    val viewCount: Int
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE isHidden = 0 ORDER BY num ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE num = :num AND isHidden = 0 LIMIT 1")
    suspend fun getChannelByNumber(num: Int): ChannelEntity?
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND isHidden = 0 ORDER BY num ASC")
    fun getChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isHidden = 0 ORDER BY favOrder ASC, name ASC")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>
    @Query("UPDATE channels SET favOrder = :order WHERE streamId = :streamId")
    suspend fun updateFavOrder(streamId: Int, order: Int)
    @Query("SELECT * FROM channels WHERE lastWatched IS NOT NULL AND isHidden = 0 ORDER BY lastWatched DESC LIMIT 30")
    fun getRecentChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY num ASC")
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
    @Query("UPDATE channels SET viewCount = viewCount + 1 WHERE streamId = :streamId")
    suspend fun incrementViewCount(streamId: Int)
    @Query("SELECT COUNT(*) FROM channels")
    suspend fun getCount(): Int
    @Query("SELECT COUNT(*) FROM channels WHERE isFavorite = 1")
    suspend fun getFavoriteCount(): Int

    @Query("SELECT streamId FROM channels WHERE isFavorite = 1")
    suspend fun getFavoriteChannelIds(): List<Int>

    @Query("SELECT streamId FROM channels")
    suspend fun getAllChannelIds(): List<Int>

    @Query("UPDATE channels SET isFavorite = 0")
    suspend fun clearAllFavorites()
    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY favOrder ASC, name ASC")
    fun getFavoriteChannelsBlocking(): List<ChannelEntity>
    @Query("UPDATE channels SET isHidden = :hidden WHERE streamId = :streamId")
    suspend fun setHidden(streamId: Int, hidden: Boolean)
    @Query("SELECT * FROM channels WHERE isHidden = 1 ORDER BY name ASC")
    fun getHiddenChannels(): Flow<List<ChannelEntity>>
    @Query("UPDATE channels SET isFavorite = 1 WHERE streamId IN (:streamIds)")
    suspend fun bulkSetFavorite(streamIds: List<Int>)
    @Query("UPDATE channels SET isFavorite = 0 WHERE streamId IN (:streamIds)")
    suspend fun bulkClearFavorite(streamIds: List<Int>)
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND streamId != :excludeStreamId AND isHidden = 0 ORDER BY viewCount DESC, name ASC LIMIT 20")
    fun getSimilarChannels(categoryId: String, excludeStreamId: Int): Flow<List<ChannelEntity>>
    @Query("SELECT streamId, isFavorite, lastWatched, viewCount, favOrder, isHidden, favoriteFolderId FROM channels")
    suspend fun getUserData(): List<ChannelUserData>
    @Query("SELECT streamId, lastWatched, viewCount FROM channels WHERE lastWatched IS NOT NULL")
    suspend fun getWatchHistoryForBackup(): List<WatchHistoryEntry>
    @Query("UPDATE channels SET lastWatched = :lastWatched, viewCount = :viewCount WHERE streamId = :streamId")
    suspend fun restoreWatchHistory(streamId: Int, lastWatched: Long, viewCount: Int)
    @Query("DELETE FROM channels WHERE categoryId LIKE 'm3u_%'")
    suspend fun deleteM3uChannels()

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isHidden = 0 AND favoriteFolderId = :folderId ORDER BY favOrder ASC, name ASC")
    fun getFavoritesInFolder(folderId: Int): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isHidden = 0 AND favoriteFolderId IS NULL ORDER BY favOrder ASC, name ASC")
    fun getUnfiledFavorites(): Flow<List<ChannelEntity>>
    @Query("SELECT favoriteFolderId, COUNT(*) as channelCount FROM channels WHERE isFavorite = 1 AND isHidden = 0 GROUP BY favoriteFolderId")
    fun getFavoriteCountsByFolder(): Flow<List<FavoriteFolderCount>>
    // Assigning a folder (Unsorted counts as one) always favorites the channel too — folder
    // queries require isFavorite = 1, so a channel moved via bulk-select (which can select
    // non-favorited channels from Live/Categories, not just existing favorites) would
    // otherwise silently vanish from every folder view despite having a folder assignment.
    @Query("UPDATE channels SET favoriteFolderId = :folderId, isFavorite = 1 WHERE streamId = :streamId")
    suspend fun setFavoriteFolder(streamId: Int, folderId: Int?)
    @Query("UPDATE channels SET favoriteFolderId = NULL WHERE favoriteFolderId = :folderId")
    suspend fun clearFolderFromChannels(folderId: Int)
}

data class FavoriteFolderCount(val favoriteFolderId: Int?, val channelCount: Int)

@Dao
interface FavoriteFolderDao {
    @Query("SELECT * FROM favorite_folders ORDER BY sortOrder ASC, name ASC")
    fun getAll(): Flow<List<FavoriteFolderEntity>>
    @Insert
    suspend fun insert(folder: FavoriteFolderEntity): Long
    @Query("UPDATE favorite_folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Int, name: String)
    @Query("DELETE FROM favorite_folders WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY categoryName ASC")
    fun getCategoriesByType(type: String): Flow<List<CategoryEntity>>
    @Upsert
    suspend fun upsertCategories(categories: List<CategoryEntity>)
    @Query("DELETE FROM categories WHERE type = :type")
    suspend fun deleteCategoriesByType(type: String)
    @Query("DELETE FROM categories WHERE categoryId LIKE 'm3u_%'")
    suspend fun deleteM3uCategories()
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
    suspend fun getWatchedMs(streamId: Int): Long?
    @Query("SELECT durationMs FROM vod_streams WHERE streamId = :streamId")
    suspend fun getDurationMs(streamId: Int): Long?
    @Query("SELECT * FROM vod_streams WHERE watchedMs > 0 AND durationMs > 0 AND CAST(watchedMs AS REAL) / durationMs < 0.95 ORDER BY watchedMs DESC LIMIT 20")
    fun getInProgressVod(): Flow<List<VodEntity>>
    // Backs fetchVodStreams' preserve-across-refresh merge — same pattern
    // ChannelDao.getUserData() already uses for live channels.
    @Query("SELECT streamId, isFavorite, watchedMs, durationMs FROM vod_streams")
    suspend fun getUserData(): List<VodUserData>
}

data class VodUserData(val streamId: Int, val isFavorite: Boolean, val watchedMs: Long, val durationMs: Long)

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
    // Backs fetchSeries' preserve-across-refresh merge — same pattern
    // ChannelDao.getUserData() already uses for live channels.
    @Query("SELECT seriesId, isFavorite, watchedMs, durationMs FROM series")
    suspend fun getUserData(): List<SeriesUserData>
}

data class SeriesUserData(val seriesId: Int, val isFavorite: Boolean, val watchedMs: Long, val durationMs: Long)

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
    @Query("SELECT * FROM epg_entries WHERE startTimestamp <= :nowMs AND stopTimestamp >= :nowMs")
    suspend fun getCurrentlyAiring(nowMs: Long): List<EpgEntity>
    @Query("SELECT MIN(startTimestamp) FROM epg_entries")
    suspend fun getOldestEpgStartTimestamp(): Long?
    @Query("SELECT MAX(stopTimestamp) FROM epg_entries")
    suspend fun getNewestEpgStopTimestamp(): Long?
    @Query("SELECT * FROM epg_entries WHERE streamId = :streamId AND nowPlaying = 1 LIMIT 1")
    suspend fun getNowPlaying(streamId: Int): EpgEntity?
    @Query("SELECT * FROM epg_entries WHERE streamId = :streamId AND startTimestamp <= :nowSec AND stopTimestamp >= :nowSec LIMIT 1")
    fun getCurrentProgramForWidget(streamId: Int, nowSec: Long): EpgEntity?
    @Upsert
    suspend fun upsertEpg(entries: List<EpgEntity>)
    @Query("DELETE FROM epg_entries WHERE stopTimestamp < :before")
    suspend fun deleteExpiredEpg(before: Long = System.currentTimeMillis() / 1000)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY scheduledStartMs ASC")
    fun getAll(): Flow<List<RecordingEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long
    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)
    @Query("UPDATE recordings SET outputPath = :path, status = :status WHERE id = :id")
    suspend fun updatePathAndStatus(id: Int, path: String, status: String)
    @Query("UPDATE recordings SET channelName = :name WHERE id = :id")
    suspend fun rename(id: Int, name: String)
    @Delete
    suspend fun delete(recording: RecordingEntity)
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Int): RecordingEntity?
    // Backs the player's small recording-indicator dot — observes whether the channel
    // currently on screen has an in-progress ad-hoc/scheduled recording. streamId alone isn't
    // globally unique once merged-provider recordings exist (two servers can reuse the same
    // numeric id), so serverIndex disambiguates which server this streamId belongs to.
    @Query("SELECT * FROM recordings WHERE streamId = :streamId AND serverIndex = :serverIndex AND status = 'RECORDING' LIMIT 1")
    fun observeActive(serverIndex: Int, streamId: Int): Flow<RecordingEntity?>
    // Backs the player's single-connection-conflict message — most Xtream plans allow only
    // one simultaneous stream, so a recording in progress on ANY channel (not just this one)
    // is the most common real-world cause of "every other channel just spins reconnecting."
    @Query("SELECT * FROM recordings WHERE status = 'RECORDING' LIMIT 1")
    suspend fun getAnyActive(): RecordingEntity?
    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' AND scheduledStartMs < :endMs AND (scheduledStartMs + durationMs) > :startMs")
    suspend fun getOverlapping(startMs: Long, endMs: Long): List<RecordingEntity>
}

@Dao
interface ReliabilityDao {
    @Query("SELECT * FROM channel_reliability WHERE streamId = :streamId")
    suspend fun get(streamId: Int): ChannelReliabilityEntity?
    @Query("SELECT * FROM channel_reliability")
    suspend fun getAll(): List<ChannelReliabilityEntity>
    @Upsert
    suspend fun upsert(entity: ChannelReliabilityEntity)
}

@Dao
interface EpisodeWatchedDao {
    @Upsert
    suspend fun upsert(entity: EpisodeWatchedEntity)
    @Query("SELECT * FROM episode_watched WHERE seriesId = :seriesId")
    suspend fun getForSeries(seriesId: Int): List<EpisodeWatchedEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM episode_watched WHERE seriesId = :seriesId AND season = :season AND episode = :episode)")
    suspend fun isWatched(seriesId: Int, season: Int, episode: Int): Boolean
}

@Dao
interface MergedChannelDao {
    @Query("SELECT * FROM merged_channels ORDER BY serverIndex ASC, num ASC")
    fun getAll(): Flow<List<MergedChannelEntity>>
    @Upsert
    suspend fun upsertAll(channels: List<MergedChannelEntity>)
    @Query("DELETE FROM merged_channels")
    suspend fun clearAll()
    // A per-server clear, used instead of clearAll() when refreshing — a server whose fetch
    // failed contributes no new rows, so wiping the whole table would permanently delete that
    // server's (still possibly correct) cached channels and favorites, not just staleness.
    @Query("DELETE FROM merged_channels WHERE serverIndex = :serverIndex")
    suspend fun clearForServer(serverIndex: Int)

    @Query("SELECT serverIndex, serverNickname, COUNT(*) as channelCount FROM merged_channels GROUP BY serverIndex, serverNickname ORDER BY serverIndex")
    fun getServerSummaries(): Flow<List<MergedServerSummary>>

    @Query("SELECT categoryId, categoryName, COUNT(*) as channelCount FROM merged_channels WHERE serverIndex = :serverIndex GROUP BY categoryId, categoryName ORDER BY categoryName")
    fun getCategorySummaries(serverIndex: Int): Flow<List<MergedCategorySummary>>

    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex AND (categoryId = :categoryId OR (categoryId IS NULL AND :categoryId IS NULL)) ORDER BY num")
    fun getByServerAndCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedChannelEntity>>

    // Single-row lookup by composite key — used by recording retry to re-resolve a merged
    // channel's current URL/name without pulling the whole table.
    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex AND streamId = :streamId LIMIT 1")
    suspend fun getByIndexAndId(serverIndex: Int, streamId: Int): MergedChannelEntity?

    // Searches across every configured server at once (not scoped to a selected server/
    // category) — matches how search already works on every other tab in this app.
    @Query("SELECT * FROM merged_channels WHERE name LIKE '%' || :query || '%' ORDER BY serverIndex, num")
    fun search(query: String): Flow<List<MergedChannelEntity>>

    // Favorites/folders for merged channels, same shape as ChannelDao's — reuses the same
    // FavoriteFolderEntity rows (shared folder names) so "the way favorites work for the
    // primary provider" applies here too, just scoped to this table.
    @Query("SELECT serverIndex, streamId, isFavorite, favoriteFolderId FROM merged_channels")
    suspend fun getUserData(): List<MergedChannelUserData>
    @Query("SELECT * FROM merged_channels WHERE isFavorite = 1 ORDER BY name ASC")
    fun getAllFavorites(): Flow<List<MergedChannelEntity>>
    @Query("SELECT * FROM merged_channels WHERE isFavorite = 1 AND favoriteFolderId = :folderId ORDER BY name ASC")
    fun getFavoritesInFolder(folderId: Int): Flow<List<MergedChannelEntity>>
    @Query("SELECT * FROM merged_channels WHERE isFavorite = 1 AND favoriteFolderId IS NULL ORDER BY name ASC")
    fun getUnfiledFavorites(): Flow<List<MergedChannelEntity>>
    @Query("SELECT favoriteFolderId, COUNT(*) as channelCount FROM merged_channels WHERE isFavorite = 1 GROUP BY favoriteFolderId")
    fun getFavoriteCountsByFolder(): Flow<List<FavoriteFolderCount>>
    @Query("UPDATE merged_channels SET isFavorite = :favorite WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun setFavorite(serverIndex: Int, streamId: Int, favorite: Boolean)
    @Query("UPDATE merged_channels SET favoriteFolderId = :folderId, isFavorite = 1 WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun setFavoriteFolder(serverIndex: Int, streamId: Int, folderId: Int?)
    @Query("UPDATE merged_channels SET favoriteFolderId = NULL WHERE favoriteFolderId = :folderId")
    suspend fun clearFolderFromChannels(folderId: Int)
}

data class MergedChannelUserData(val serverIndex: Int, val streamId: Int, val isFavorite: Boolean, val favoriteFolderId: Int?)