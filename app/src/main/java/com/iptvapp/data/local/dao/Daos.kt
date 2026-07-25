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
    @Query("SELECT * FROM series WHERE isHidden = 0 ORDER BY name ASC")
    fun getAllSeries(): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE categoryId = :categoryId AND isHidden = 0 ORDER BY name ASC")
    fun getSeriesByCategory(categoryId: String): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE isFavorite = 1 AND isHidden = 0 ORDER BY name ASC")
    fun getFavoriteSeries(): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE seriesId = :seriesId LIMIT 1")
    suspend fun getSeriesById(seriesId: Int): SeriesEntity?
    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY name ASC")
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
    @Query("SELECT seriesId, isFavorite, watchedMs, durationMs, isHidden FROM series")
    suspend fun getUserData(): List<SeriesUserData>
    // Hide-individual-show support, same shape as ChannelDao's hide/unhide/getHidden.
    @Query("UPDATE series SET isHidden = 1 WHERE seriesId IN (:seriesIds)")
    suspend fun bulkSetHidden(seriesIds: List<Int>)
    @Query("UPDATE series SET isHidden = 0 WHERE seriesId = :seriesId")
    suspend fun setUnhidden(seriesId: Int)
    @Query("SELECT * FROM series WHERE isHidden = 1 ORDER BY name ASC")
    fun getHiddenSeries(): Flow<List<SeriesEntity>>

    // Continue Watching (series half) — one row per series, picking whichever episode row was
    // most recently written, then keeping only the ones where THAT specific episode is still
    // in-progress (not finished) — same 95%-threshold convention getInProgressVod already uses
    // for movies. A series with its most recent episode fully watched isn't "in progress" even
    // if an earlier episode was left mid-way long ago. episode_watched has no timestamp for
    // partial progress (watchedAt is only set once an episode is marked fully watched, per
    // EpisodeWatchedDao.ensureRow's kdoc) — SQLite's own rowid (monotonically increasing on
    // insert, and Room's @Upsert here is effectively insert-then-update-in-place so rowid is
    // stable/increasing across saveProgress calls) is the best available recency proxy.
    @Query("""
        SELECT s.*, ew.season AS lastSeason, ew.episode AS lastEpisode,
               ew.watchedMs AS lastEpisodeWatchedMs, ew.durationMs AS lastEpisodeDurationMs
        FROM series s
        INNER JOIN (
            SELECT ew1.rowid AS rid, ew1.seriesId, ew1.season, ew1.episode, ew1.watchedMs, ew1.durationMs
            FROM episode_watched ew1
            WHERE ew1.rowid = (
                SELECT MAX(ew2.rowid) FROM episode_watched ew2
                WHERE ew2.seriesId = ew1.seriesId
            )
        ) ew ON ew.seriesId = s.seriesId
        WHERE s.isHidden = 0
          AND ew.durationMs > 0
          AND ew.watchedMs > 0
          AND CAST(ew.watchedMs AS REAL) / ew.durationMs < 0.95
        ORDER BY ew.rid DESC
        LIMIT 20
    """)
    fun getInProgressSeries(): Flow<List<InProgressSeriesRow>>
}

data class SeriesUserData(val seriesId: Int, val isFavorite: Boolean, val watchedMs: Long, val durationMs: Long, val isHidden: Boolean = false)

data class InProgressSeriesRow(
    @Embedded val series: SeriesEntity,
    val lastSeason: Int,
    val lastEpisode: Int,
    val lastEpisodeWatchedMs: Long,
    val lastEpisodeDurationMs: Long
)

@Dao
interface EpgDao {
    @Query("SELECT COUNT(*) FROM epg_entries")
    suspend fun getEpgCount(): Int
    // serverIndex defaults to -1 (primary provider) so every existing call site keeps
    // compiling/behaving unchanged — only new merged-provider code passes it explicitly.
    @Query("SELECT * FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId ORDER BY startTimestamp ASC")
    fun getEpgForStream(streamId: Int, serverIndex: Int = -1): Flow<List<EpgEntity>>
    @Query("SELECT * FROM epg_entries WHERE serverIndex = :serverIndex AND streamId IN (:streamIds) ORDER BY streamId ASC, startTimestamp ASC")
    fun getEpgForStreams(streamIds: List<Int>, serverIndex: Int = -1): Flow<List<EpgEntity>>
    // Guide needs merged-provider programs for several servers at once — a plain IN() on
    // streamId alone would collide across servers reusing the same numeric id, so this takes
    // explicit (serverIndex, streamId) pairs instead of a single serverIndex + id list.
    @Query("SELECT * FROM epg_entries WHERE (serverIndex || ':' || streamId) IN (:serverStreamKeys) ORDER BY serverIndex ASC, streamId ASC, startTimestamp ASC")
    fun getEpgForServerStreamKeys(serverStreamKeys: List<String>): Flow<List<EpgEntity>>
    @Query("SELECT DISTINCT streamId FROM epg_entries WHERE serverIndex = :serverIndex")
    suspend fun getStreamIdsWithEpg(serverIndex: Int = -1): List<Int>
    @Query("SELECT * FROM epg_entries WHERE serverIndex = :serverIndex AND startTimestamp <= :nowMs AND stopTimestamp >= :nowMs")
    suspend fun getCurrentlyAiring(nowMs: Long, serverIndex: Int = -1): List<EpgEntity>
    @Query("SELECT MIN(startTimestamp) FROM epg_entries WHERE serverIndex = :serverIndex")
    suspend fun getOldestEpgStartTimestamp(serverIndex: Int = -1): Long?
    @Query("SELECT MAX(stopTimestamp) FROM epg_entries WHERE serverIndex = :serverIndex")
    suspend fun getNewestEpgStopTimestamp(serverIndex: Int = -1): Long?
    @Query("SELECT * FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId AND nowPlaying = 1 LIMIT 1")
    suspend fun getNowPlaying(streamId: Int, serverIndex: Int = -1): EpgEntity?
    @Query("SELECT * FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId AND startTimestamp <= :nowSec AND stopTimestamp >= :nowSec LIMIT 1")
    fun getCurrentProgramForWidget(streamId: Int, nowSec: Long, serverIndex: Int = -1): EpgEntity?
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
    // Every watched episode across every series — needed for cross-device sync, which pushes a
    // complete snapshot rather than looping per-seriesId (SyncManager doesn't otherwise know
    // every seriesId with watched episodes ahead of time).
    @Query("SELECT * FROM episode_watched")
    suspend fun getAll(): List<EpisodeWatchedEntity>
    // Ensures a row exists without touching watchedAt (which marks the episode as fully
    // "watched" in SeriesDetailActivity's UI dot) — a plain progress save mid-episode must not
    // create a false completed-watch marker for an episode never previously finished.
    @Query("INSERT OR IGNORE INTO episode_watched (seriesId, season, episode, watchedAt, watchedMs, durationMs) VALUES (:seriesId, :season, :episode, 0, 0, 0)")
    suspend fun ensureRow(seriesId: Int, season: Int, episode: Int)
    @Query("UPDATE episode_watched SET watchedMs = :watchedMs, durationMs = :durationMs WHERE seriesId = :seriesId AND season = :season AND episode = :episode")
    suspend fun updateProgress(seriesId: Int, season: Int, episode: Int, watchedMs: Long, durationMs: Long)
    suspend fun saveProgress(seriesId: Int, season: Int, episode: Int, watchedMs: Long, durationMs: Long) {
        ensureRow(seriesId, season, episode)
        updateProgress(seriesId, season, episode, watchedMs, durationMs)
    }
    @Query("SELECT watchedMs FROM episode_watched WHERE seriesId = :seriesId AND season = :season AND episode = :episode")
    suspend fun getWatchedMs(seriesId: Int, season: Int, episode: Int): Long?
    @Query("SELECT durationMs FROM episode_watched WHERE seriesId = :seriesId AND season = :season AND episode = :episode")
    suspend fun getDurationMs(seriesId: Int, season: Int, episode: Int): Long?
    // Series with any episode progress (started or fully watched) — used to float
    // "watching/watched" series to the top of the main Series list.
    @Query("SELECT DISTINCT seriesId FROM episode_watched WHERE watchedAt > 0 OR watchedMs > 0")
    fun getSeriesIdsWithProgress(): kotlinx.coroutines.flow.Flow<List<Int>>
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

    // Every channel for one server regardless of category — needed to resolve XMLTV channels
    // to merged-channel streamIds the same way fetchXmltvEpg resolves against the primary
    // provider's full channel list (getByServerAndCategory requires a specific/null category,
    // not "any category").
    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex")
    suspend fun getAllForServer(serverIndex: Int): List<MergedChannelEntity>

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

// Movies-tab equivalent of MergedChannelDao — same shape throughout (per-server clear on
// refresh, favorites/folders reusing FavoriteFolderEntity), see MergedVodEntity kdoc.
@Dao
interface MergedVodDao {
    @Query("SELECT * FROM merged_vod WHERE isHidden = 0 ORDER BY serverIndex ASC, name ASC")
    fun getAll(): Flow<List<MergedVodEntity>>
    @Upsert
    suspend fun upsertAll(vod: List<MergedVodEntity>)
    @Query("DELETE FROM merged_vod")
    suspend fun clearAll()
    @Query("DELETE FROM merged_vod WHERE serverIndex = :serverIndex")
    suspend fun clearForServer(serverIndex: Int)

    @Query("SELECT serverIndex, serverNickname, COUNT(*) as vodCount FROM merged_vod GROUP BY serverIndex, serverNickname ORDER BY serverIndex")
    fun getServerSummaries(): Flow<List<MergedVodServerSummary>>

    @Query("SELECT * FROM merged_vod WHERE serverIndex = :serverIndex")
    suspend fun getAllForServer(serverIndex: Int): List<MergedVodEntity>

    @Query("SELECT categoryId, categoryName, COUNT(*) as vodCount FROM merged_vod WHERE serverIndex = :serverIndex GROUP BY categoryId, categoryName ORDER BY categoryName")
    fun getCategorySummaries(serverIndex: Int): Flow<List<MergedVodCategorySummary>>

    @Query("SELECT * FROM merged_vod WHERE serverIndex = :serverIndex AND (categoryId = :categoryId OR (categoryId IS NULL AND :categoryId IS NULL)) AND isHidden = 0 ORDER BY name")
    fun getByServerAndCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedVodEntity>>

    @Query("SELECT * FROM merged_vod WHERE serverIndex = :serverIndex AND streamId = :streamId LIMIT 1")
    suspend fun getByIndexAndId(serverIndex: Int, streamId: Int): MergedVodEntity?

    @Query("SELECT * FROM merged_vod WHERE name LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY serverIndex, name")
    fun search(query: String): Flow<List<MergedVodEntity>>

    @Query("SELECT serverIndex, streamId, isFavorite, favoriteFolderId, watchedMs, durationMs, isHidden FROM merged_vod")
    suspend fun getUserData(): List<MergedVodUserData>
    @Query("SELECT * FROM merged_vod WHERE isFavorite = 1 AND isHidden = 0 ORDER BY name ASC")
    fun getAllFavorites(): Flow<List<MergedVodEntity>>
    @Query("SELECT * FROM merged_vod WHERE isFavorite = 1 AND favoriteFolderId = :folderId AND isHidden = 0 ORDER BY name ASC")
    fun getFavoritesInFolder(folderId: Int): Flow<List<MergedVodEntity>>
    @Query("SELECT * FROM merged_vod WHERE isFavorite = 1 AND favoriteFolderId IS NULL AND isHidden = 0 ORDER BY name ASC")
    fun getUnfiledFavorites(): Flow<List<MergedVodEntity>>
    @Query("SELECT favoriteFolderId, COUNT(*) as channelCount FROM merged_vod WHERE isFavorite = 1 GROUP BY favoriteFolderId")
    fun getFavoriteCountsByFolder(): Flow<List<FavoriteFolderCount>>
    @Query("UPDATE merged_vod SET isFavorite = :favorite WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun setFavorite(serverIndex: Int, streamId: Int, favorite: Boolean)
    @Query("UPDATE merged_vod SET favoriteFolderId = :folderId, isFavorite = 1 WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun setFavoriteFolder(serverIndex: Int, streamId: Int, folderId: Int?)
    @Query("UPDATE merged_vod SET favoriteFolderId = NULL WHERE favoriteFolderId = :folderId")
    suspend fun clearFolderFromChannels(folderId: Int)
    // Hide-individual-item support, same shape as MergedSeriesDao's hide/unhide/getHidden.
    @Query("UPDATE merged_vod SET isHidden = 1 WHERE serverIndex = :serverIndex AND streamId IN (:streamIds)")
    suspend fun bulkSetHidden(serverIndex: Int, streamIds: List<Int>)
    @Query("SELECT * FROM merged_vod WHERE isHidden = 1 ORDER BY serverIndex ASC, name ASC")
    fun getHidden(): Flow<List<MergedVodEntity>>
    // Watch-progress support, same shape as VodDao's updateWatchProgress/getWatchedMs/getDurationMs.
    @Query("UPDATE merged_vod SET watchedMs = :watchedMs, durationMs = :durationMs WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun updateWatchProgress(serverIndex: Int, streamId: Int, watchedMs: Long, durationMs: Long)
    @Query("SELECT watchedMs FROM merged_vod WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun getWatchedMs(serverIndex: Int, streamId: Int): Long?
    @Query("SELECT durationMs FROM merged_vod WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun getDurationMs(serverIndex: Int, streamId: Int): Long?
}

data class MergedVodUserData(
    val serverIndex: Int, val streamId: Int, val isFavorite: Boolean, val favoriteFolderId: Int?,
    val watchedMs: Long = 0L, val durationMs: Long = 0L, val isHidden: Boolean = false
)

// Series-tab equivalent of MergedVodDao — same shape throughout, see MergedSeriesEntity kdoc.
@Dao
interface MergedSeriesDao {
    @Query("SELECT * FROM merged_series WHERE isHidden = 0 ORDER BY serverIndex ASC, name ASC")
    fun getAll(): Flow<List<MergedSeriesEntity>>
    @Upsert
    suspend fun upsertAll(series: List<MergedSeriesEntity>)
    @Query("DELETE FROM merged_series")
    suspend fun clearAll()
    @Query("DELETE FROM merged_series WHERE serverIndex = :serverIndex")
    suspend fun clearForServer(serverIndex: Int)

    @Query("SELECT serverIndex, serverNickname, COUNT(*) as seriesCount FROM merged_series GROUP BY serverIndex, serverNickname ORDER BY serverIndex")
    fun getServerSummaries(): Flow<List<MergedSeriesServerSummary>>

    @Query("SELECT * FROM merged_series WHERE serverIndex = :serverIndex")
    suspend fun getAllForServer(serverIndex: Int): List<MergedSeriesEntity>

    @Query("SELECT categoryId, categoryName, COUNT(*) as seriesCount FROM merged_series WHERE serverIndex = :serverIndex GROUP BY categoryId, categoryName ORDER BY categoryName")
    fun getCategorySummaries(serverIndex: Int): Flow<List<MergedSeriesCategorySummary>>

    @Query("SELECT * FROM merged_series WHERE serverIndex = :serverIndex AND (categoryId = :categoryId OR (categoryId IS NULL AND :categoryId IS NULL)) AND isHidden = 0 ORDER BY name")
    fun getByServerAndCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedSeriesEntity>>

    @Query("SELECT * FROM merged_series WHERE serverIndex = :serverIndex AND seriesId = :seriesId LIMIT 1")
    suspend fun getByIndexAndId(serverIndex: Int, seriesId: Int): MergedSeriesEntity?

    @Query("SELECT * FROM merged_series WHERE name LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY serverIndex, name")
    fun search(query: String): Flow<List<MergedSeriesEntity>>

    @Query("SELECT serverIndex, seriesId, isFavorite, favoriteFolderId, isHidden FROM merged_series")
    suspend fun getUserData(): List<MergedSeriesUserData>
    @Query("SELECT * FROM merged_series WHERE isFavorite = 1 AND isHidden = 0 ORDER BY name ASC")
    fun getAllFavorites(): Flow<List<MergedSeriesEntity>>
    // Hide-individual-show support, same shape as SeriesDao's hide/unhide/getHidden.
    @Query("UPDATE merged_series SET isHidden = 1 WHERE serverIndex = :serverIndex AND seriesId IN (:seriesIds)")
    suspend fun bulkSetHidden(serverIndex: Int, seriesIds: List<Int>)
    @Query("UPDATE merged_series SET isHidden = 0 WHERE serverIndex = :serverIndex AND seriesId = :seriesId")
    suspend fun setUnhidden(serverIndex: Int, seriesId: Int)
    @Query("SELECT * FROM merged_series WHERE isHidden = 1 ORDER BY serverIndex ASC, name ASC")
    fun getHidden(): Flow<List<MergedSeriesEntity>>
    @Query("SELECT * FROM merged_series WHERE isFavorite = 1 AND favoriteFolderId = :folderId ORDER BY name ASC")
    fun getFavoritesInFolder(folderId: Int): Flow<List<MergedSeriesEntity>>
    @Query("SELECT * FROM merged_series WHERE isFavorite = 1 AND favoriteFolderId IS NULL ORDER BY name ASC")
    fun getUnfiledFavorites(): Flow<List<MergedSeriesEntity>>
    @Query("SELECT favoriteFolderId, COUNT(*) as channelCount FROM merged_series WHERE isFavorite = 1 GROUP BY favoriteFolderId")
    fun getFavoriteCountsByFolder(): Flow<List<FavoriteFolderCount>>
    @Query("UPDATE merged_series SET isFavorite = :favorite WHERE serverIndex = :serverIndex AND seriesId = :seriesId")
    suspend fun setFavorite(serverIndex: Int, seriesId: Int, favorite: Boolean)
    // Long-pressing a merged-series category favorites every series in it at once (into an
    // optional folder), instead of requiring one tap per show — same bulk-by-category shape as
    // bulkSetHidden above, just setting isFavorite/favoriteFolderId instead of isHidden.
    @Query("UPDATE merged_series SET isFavorite = 1, favoriteFolderId = :folderId WHERE serverIndex = :serverIndex AND (categoryId = :categoryId OR (categoryId IS NULL AND :categoryId IS NULL))")
    suspend fun setFavoriteForCategory(serverIndex: Int, categoryId: String?, folderId: Int?)
    @Query("UPDATE merged_series SET favoriteFolderId = :folderId, isFavorite = 1 WHERE serverIndex = :serverIndex AND seriesId = :seriesId")
    suspend fun setFavoriteFolder(serverIndex: Int, seriesId: Int, folderId: Int?)
    @Query("UPDATE merged_series SET favoriteFolderId = NULL WHERE favoriteFolderId = :folderId")
    suspend fun clearFolderFromChannels(folderId: Int)
}

data class MergedSeriesUserData(val serverIndex: Int, val seriesId: Int, val isFavorite: Boolean, val favoriteFolderId: Int?, val isHidden: Boolean = false)