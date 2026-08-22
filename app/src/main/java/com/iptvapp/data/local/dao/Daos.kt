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
    val favoriteFolderId: Int?,
    val manualGenre: String?
)

data class WatchHistoryEntry(
    val streamId: Int,
    val lastWatched: Long,
    val viewCount: Int
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE isHidden = 0 ORDER BY COALESCE(customNum, num) ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>
    // Picks any one real channel to test actual playback against — used by the Provider Speed
    // Test's real-stream check (see XtreamRepository.checkStreamHealth call site), which needs a
    // concrete streamId, not just "the server is reachable" the ping/HTTP checks already cover.
    @Query("SELECT * FROM channels WHERE isHidden = 0 LIMIT 1")
    suspend fun getFirstChannel(): ChannelEntity?
    // customNum first (user override), falling back to the provider's own num when unset or when
    // no channel has that as a custom number — see ChannelEntity.customNum's kdoc.
    @Query("SELECT * FROM channels WHERE customNum = :num AND isHidden = 0 LIMIT 1")
    suspend fun getChannelByCustomNumber(num: Int): ChannelEntity?
    @Query("SELECT * FROM channels WHERE num = :num AND isHidden = 0 LIMIT 1")
    suspend fun getChannelByNumber(num: Int): ChannelEntity?
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND isHidden = 0 ORDER BY COALESCE(customNum, num) ASC")
    fun getChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isHidden = 0 ORDER BY favOrder ASC, name ASC")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>
    // One-shot list for the favorite-number backfill (see
    // XtreamRepository.backfillFavoriteChannelNumbers's kdoc) — plain suspend fetch, not a Flow,
    // since this only ever runs once as an imperative pass over already-favorited channels.
    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND customNum IS NULL")
    suspend fun getUnnumberedFavorites(): List<ChannelEntity>
    @Query("UPDATE channels SET favOrder = :order WHERE streamId = :streamId")
    suspend fun updateFavOrder(streamId: Int, order: Int)
    @Query("UPDATE channels SET customNum = :customNum WHERE streamId = :streamId")
    suspend fun setCustomNum(streamId: Int, customNum: Int?)
    // Highest custom number assigned so far — the auto-favorite-numbering feature uses
    // this + 1 (or 2 if null) as the next number to hand out, keeping the sequence compact
    // rather than searching for gaps left by cleared/unfavorited channels.
    @Query("SELECT MAX(customNum) FROM channels")
    suspend fun getMaxCustomNum(): Int?
    // Same idea, scoped to one genre's reserved number block (see
    // XtreamRepository.genreNumberBlockStart's kdoc) — next number within [start, end], or start
    // itself if the block is still empty.
    @Query("SELECT MAX(customNum) FROM channels WHERE customNum BETWEEN :start AND :end")
    suspend fun getMaxCustomNumInRange(start: Int, end: Int): Int?
    @Query("SELECT * FROM channels WHERE lastWatched IS NOT NULL AND isHidden = 0 ORDER BY lastWatched DESC LIMIT 30")
    fun getRecentChannels(): Flow<List<ChannelEntity>>
    // FTS4 MATCH replaces the old LIKE '%query%' full-table scan (55k-112k+ row catalogs made
    // that a real per-keystroke bottleneck). The caller appends '*' to the raw query for
    // prefix-as-you-type matching (see XtreamRepository.searchChannels) — FTS4 MATCH with a
    // trailing '*' is a per-token PREFIX match, not a substring match, so "atma" will no longer
    // find "Batman" the way LIKE '%atma%' did; "bat*" still finds "Batman" fine. This is an
    // inherent FTS4 tradeoff for the large speedup, not worked around with a LIKE fallback.
    @Query("""
        SELECT channels.* FROM channels
        JOIN channels_fts ON channels.streamId = channels_fts.rowid
        WHERE channels_fts.name MATCH :query AND channels.isHidden = 0
        ORDER BY COALESCE(channels.customNum, channels.num) ASC
    """)
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
    @Query("UPDATE channels SET isHidden = 1 WHERE streamId IN (:streamIds)")
    suspend fun bulkSetHidden(streamIds: List<Int>)
    @Query("SELECT * FROM channels WHERE isHidden = 1 ORDER BY name ASC")
    fun getHiddenChannels(): Flow<List<ChannelEntity>>
    @Query("UPDATE channels SET isFavorite = 1 WHERE streamId IN (:streamIds)")
    suspend fun bulkSetFavorite(streamIds: List<Int>)
    @Query("UPDATE channels SET isFavorite = 0 WHERE streamId IN (:streamIds)")
    suspend fun bulkClearFavorite(streamIds: List<Int>)
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND streamId != :excludeStreamId AND isHidden = 0 ORDER BY viewCount DESC, name ASC LIMIT 20")
    fun getSimilarChannels(categoryId: String, excludeStreamId: Int): Flow<List<ChannelEntity>>
    @Query("SELECT streamId, isFavorite, lastWatched, viewCount, favOrder, isHidden, favoriteFolderId, manualGenre FROM channels")
    suspend fun getUserData(): List<ChannelUserData>
    @Query("SELECT streamId, lastWatched, viewCount FROM channels WHERE lastWatched IS NOT NULL")
    suspend fun getWatchHistoryForBackup(): List<WatchHistoryEntry>
    @Query("UPDATE channels SET lastWatched = :lastWatched, viewCount = :viewCount WHERE streamId = :streamId")
    suspend fun restoreWatchHistory(streamId: Int, lastWatched: Long, viewCount: Int)
    @Query("DELETE FROM channels WHERE categoryId LIKE 'm3u_%'")
    suspend fun deleteM3uChannels()
    // Sweeps rows the server no longer returns (see fetchLiveStreams's kdoc on why this matters
    // for stale favorites left behind by a primary-provider swap).
    @Query("DELETE FROM channels WHERE streamId IN (:streamIds)")
    suspend fun deleteChannelsByIds(streamIds: List<Int>)

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
    @Query("UPDATE channels SET manualGenre = :genre WHERE streamId IN (:streamIds)")
    suspend fun bulkSetManualGenre(streamIds: List<Int>, genre: String?)
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
    @Query("SELECT * FROM categories WHERE categoryId = :categoryId LIMIT 1")
    suspend fun getCategoryById(categoryId: String): CategoryEntity?
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_streams ORDER BY added DESC, name ASC")
    fun getAllVod(): Flow<List<VodEntity>>
    // Fast first paint for TvHomeActivity's full-screen Movies grid — getAllVod() above loads
    // every row (176k+ on a large merged-provider catalog observed during testing, 15-25+
    // seconds just to deserialize), leaving the grid blank that whole time on cold launch. This
    // returns instantly with enough rows to fill the screen; showMoviesFullScreen submits this
    // first, then the existing getAllVod() collector (already running for every other Movies
    // consumer — phone, old TV Movies section, favorites) seamlessly replaces it once the full
    // catalog finishes loading, with no separate loading path to maintain.
    @Query("SELECT * FROM vod_streams ORDER BY added DESC, name ASC LIMIT 100")
    fun getVodFirstPage(): Flow<List<VodEntity>>
    @Query("SELECT * FROM vod_streams WHERE categoryId = :categoryId ORDER BY added DESC, name ASC")
    fun getVodByCategory(categoryId: String): Flow<List<VodEntity>>
    // Backs the TV home landing screen's "Recently Added" row — same added-string ordering
    // getAllVod already uses (Xtream's "added" field is a Unix-timestamp string; consistent
    // digit-length means lexicographic ORDER BY already sorts newest-first correctly), just
    // capped to a small row instead of the full catalog.
    @Query("SELECT * FROM vod_streams ORDER BY added DESC, name ASC LIMIT 20")
    fun getRecentlyAddedVod(): Flow<List<VodEntity>>
    @Query("SELECT * FROM vod_streams WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteVod(): Flow<List<VodEntity>>
    // Single-row lookup for VodDetailActivity's favorite button — needs just this one movie's
    // current isFavorite state to render/toggle correctly, not a live-updating Flow.
    @Query("SELECT * FROM vod_streams WHERE streamId = :streamId LIMIT 1")
    suspend fun getVodByStreamId(streamId: Int): VodEntity?
    // See ChannelDao.searchChannels kdoc for the FTS4-vs-LIKE rationale and the prefix-vs-
    // substring matching tradeoff (caller appends '*' for prefix-as-you-type).
    @Query("""
        SELECT vod_streams.* FROM vod_streams
        JOIN vod_streams_fts ON vod_streams.streamId = vod_streams_fts.rowid
        WHERE vod_streams_fts.name MATCH :query
        ORDER BY vod_streams.name ASC
    """)
    fun searchVod(query: String): Flow<List<VodEntity>>
    @Upsert
    suspend fun upsertVod(vod: List<VodEntity>)
    @Query("UPDATE vod_streams SET isFavorite = :isFavorite WHERE streamId = :streamId")
    suspend fun setFavorite(streamId: Int, isFavorite: Boolean)
    @Query("SELECT COUNT(*) FROM vod_streams")
    suspend fun getCount(): Int
    // Clears dismissedFromContinueWatching on every progress save — a dismissed movie should
    // reappear in Continue Watching once the user actually resumes it, not stay hidden forever.
    // Returns the number of rows updated (0 if streamId isn't in the local catalog yet) — a
    // cross-device sync pull for a movie this device hasn't cached the catalog row for used to
    // silently no-op while still counting as "merged" (see SyncManager.pullFromCloud's kdoc).
    @Query("UPDATE vod_streams SET watchedMs = :watchedMs, durationMs = :durationMs, dismissedFromContinueWatching = 0, lastWatchedAt = :nowMs WHERE streamId = :streamId")
    suspend fun updateWatchProgress(streamId: Int, watchedMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()): Int
    @Query("SELECT watchedMs FROM vod_streams WHERE streamId = :streamId")
    suspend fun getWatchedMs(streamId: Int): Long?
    @Query("SELECT durationMs FROM vod_streams WHERE streamId = :streamId")
    suspend fun getDurationMs(streamId: Int): Long?
    @Query("SELECT * FROM vod_streams WHERE watchedMs > 0 AND durationMs > 0 AND CAST(watchedMs AS REAL) / durationMs < 0.95 AND dismissedFromContinueWatching = 0 ORDER BY watchedMs DESC LIMIT 20")
    fun getInProgressVod(): Flow<List<VodEntity>>
    // Watched History — the complement of getInProgressVod's 0.95 threshold, movies considered
    // "finished" rather than still in progress. Not capped/limited like Continue Watching (that
    // row only ever shows a handful of recents) — this is the full log, sorted most-recent-first.
    @Query("SELECT * FROM vod_streams WHERE watchedMs > 0 AND durationMs > 0 AND CAST(watchedMs AS REAL) / durationMs >= 0.95 ORDER BY lastWatchedAt DESC")
    fun getWatchHistoryVod(): Flow<List<VodEntity>>
    @Query("UPDATE vod_streams SET dismissedFromContinueWatching = 1 WHERE streamId = :streamId")
    suspend fun dismissFromContinueWatching(streamId: Int)
    // Used by ContinueWatchingCleanupWorker — dismisses (doesn't delete the catalog row, just
    // clears it from the Continue Watching row) any in-progress movie whose last watch activity
    // is older than the cutoff, mirroring RecordingCleanupWorker's age-based retention pattern.
    @Query("UPDATE vod_streams SET dismissedFromContinueWatching = 1 WHERE watchedMs > 0 AND durationMs > 0 AND CAST(watchedMs AS REAL) / durationMs < 0.95 AND dismissedFromContinueWatching = 0 AND lastWatchedAt < :cutoffMs")
    suspend fun dismissStaleContinueWatching(cutoffMs: Long)
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
    // See ChannelDao.searchChannels kdoc for the FTS4-vs-LIKE rationale and the prefix-vs-
    // substring matching tradeoff (caller appends '*' for prefix-as-you-type).
    @Query("""
        SELECT series.* FROM series
        JOIN series_fts ON series.seriesId = series_fts.rowid
        WHERE series_fts.name MATCH :query AND series.isHidden = 0
        ORDER BY series.name ASC
    """)
    fun searchSeries(query: String): Flow<List<SeriesEntity>>
    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)
    @Query("UPDATE series SET isFavorite = :isFavorite WHERE seriesId = :seriesId")
    suspend fun setFavorite(seriesId: Int, isFavorite: Boolean)
    @Query("SELECT COUNT(*) FROM series")
    suspend fun getCount(): Int
    @Query("UPDATE series SET watchedMs = :watchedMs, durationMs = :durationMs, lastWatchedAt = :nowMs WHERE seriesId = :seriesId")
    suspend fun updateWatchProgress(seriesId: Int, watchedMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis())
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
          AND s.dismissedFromContinueWatching = 0
          AND ew.durationMs > 0
          AND ew.watchedMs > 0
          AND CAST(ew.watchedMs AS REAL) / ew.durationMs < 0.95
        ORDER BY ew.rid DESC
        LIMIT 20
    """)
    fun getInProgressSeries(): Flow<List<InProgressSeriesRow>>
    @Query("UPDATE series SET dismissedFromContinueWatching = 1 WHERE seriesId = :seriesId")
    suspend fun dismissFromContinueWatching(seriesId: Int)
    @Query("UPDATE series SET lastWatchedAt = :nowMs WHERE seriesId = :seriesId")
    suspend fun touchLastWatched(seriesId: Int, nowMs: Long = System.currentTimeMillis())
    // Used by ContinueWatchingCleanupWorker — same age-based dismiss as VodDao's
    // dismissStaleContinueWatching, applied to any series whose last episode-progress touch
    // (see touchLastWatched, called from saveEpisodeProgress) is older than the cutoff.
    @Query("UPDATE series SET dismissedFromContinueWatching = 1 WHERE dismissedFromContinueWatching = 0 AND lastWatchedAt > 0 AND lastWatchedAt < :cutoffMs")
    suspend fun dismissStaleContinueWatching(cutoffMs: Long)
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
    // "What's airing" search — matches program title/description rather than channel name, so a
    // search for e.g. a movie title finds every channel currently or soon showing it, which
    // plain channel-name search can never do. Bounded to stopTimestamp >= :nowSec (nothing that
    // already ended) so results stay actionable — a match from three days ago that already aired
    // isn't useful to a "what can I watch" search. One row per (serverIndex, streamId) via
    // GROUP BY, since the same channel can have many matching programs across its EPG window and
    // the caller only cares about which channels to show, not every individual match.
    @Query("""
        SELECT * FROM epg_entries
        WHERE stopTimestamp >= :nowSec
        AND (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
        GROUP BY serverIndex, streamId
        ORDER BY startTimestamp ASC
        LIMIT 100
    """)
    suspend fun searchProgramsAcrossChannels(query: String, nowSec: Long = System.currentTimeMillis() / 1000): List<EpgEntity>
    @Upsert
    suspend fun upsertEpg(entries: List<EpgEntity>)
    @Query("DELETE FROM epg_entries WHERE stopTimestamp < :before")
    suspend fun deleteExpiredEpg(before: Long = System.currentTimeMillis() / 1000)
    // upsertEpg's id ("x_${xmltvChannelId}_${startSec}") is scoped to the XMLTV feed's OWN
    // channel id, not the resolved local streamId — so when a re-fetch resolves an XMLTV channel
    // to a DIFFERENT (now-correct, post name-matching-fix) local streamId than a previous fetch
    // did, upsert just adds a new row under the new streamId; the old row under the old, wrong
    // streamId is never touched and stays wrong forever. fetchXmltvEpg/fetchXmltvEpgForMergedServer
    // call this right before writing a fresh batch, since both already fetch the complete current
    // dataset — there's no partial-update case where keeping old rows around is correct.
    @Query("DELETE FROM epg_entries WHERE serverIndex = :serverIndex")
    suspend fun deleteAllForServer(serverIndex: Int = -1)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY scheduledStartMs ASC")
    fun getAll(): Flow<List<RecordingEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long
    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)
    // Only ever called with status = "FAILED" — see RecordingService.classifyFailureReason.
    // Clears any stale reason on a non-failure status change (e.g. a manual Retry starting a
    // fresh attempt) since updateStatus (above) doesn't touch this column and a leftover reason
    // from a previous failed attempt would otherwise linger and mislabel the new attempt.
    @Query("UPDATE recordings SET status = :status, failureReason = :failureReason WHERE id = :id")
    suspend fun updateStatusWithReason(id: Int, status: String, failureReason: String?)
    @Query("UPDATE recordings SET outputPath = :path, status = :status WHERE id = :id")
    suspend fun updatePathAndStatus(id: Int, path: String, status: String)
    // durationMs is otherwise just the originally-SCHEDULED length (see RecordingFileUtils.
    // durationMs kdoc) — "Remove Padding" corrects it to the actual trimmed file's real length
    // so anything reading this field afterward (list display, auto-cleanup age calculations)
    // isn't working off stale padding-inclusive timing.
    @Query("UPDATE recordings SET outputPath = :path, durationMs = :durationMs, status = :status WHERE id = :id")
    suspend fun updatePathDurationAndStatus(id: Int, path: String, durationMs: Long, status: String)
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
    // Auto-delete candidates for RecordingCleanupWorker — only finished recordings (DONE/FAILED
    // both leave a row that's safe to age out; SCHEDULED/RECORDING/COMPRESSING must never be
    // touched since they're not finished yet). Anchored on scheduledStartMs + durationMs (the
    // recording's actual end time) rather than scheduledStartMs alone, so a long recording
    // doesn't get deleted before it even finishes airing.
    @Query("SELECT * FROM recordings WHERE status IN ('DONE', 'FAILED') AND (scheduledStartMs + durationMs) < :cutoffMs")
    suspend fun getOlderThan(cutoffMs: Long): List<RecordingEntity>
}

@Dao
interface ReliabilityDao {
    @Query("SELECT * FROM channel_reliability WHERE streamId = :streamId")
    suspend fun get(streamId: Int): ChannelReliabilityEntity?
    // Paged instead of a single unbounded SELECT * — on a large catalog (tens of thousands of
    // channels, each potentially with a reliability row) a full-table read in one cursor window
    // was observed to throw CursorWindowAllocationException on a low-RAM device (crash log:
    // Amazon AFTMM, 0.7GB free, 55k channels), even though this table's rows are individually
    // tiny. Room's single-window cursor sizing doesn't scale with row count safely on constrained
    // devices, so this reads in bounded chunks instead — see
    // XtreamRepository.getAllReliabilityPercents for how they're stitched back together.
    @Query("SELECT * FROM channel_reliability LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<ChannelReliabilityEntity>
    @Upsert
    suspend fun upsert(entity: ChannelReliabilityEntity)
}

@Dao
interface ProviderHourlyStatsDao {
    // Upsert-with-increment, same ON CONFLICT...DO UPDATE idiom as BandwidthUsageDao.addUsage —
    // one row per (serverIndex, hourOfDay), incremented on every recorded playback outcome rather
    // than read-modify-write from Kotlin (which would race under concurrent playback/retry calls).
    @Query("""
        INSERT INTO provider_hourly_stats (serverIndex, hourOfDay, eventCount, sampleCount)
        VALUES (:serverIndex, :hourOfDay, :eventDelta, 1)
        ON CONFLICT(serverIndex, hourOfDay) DO UPDATE SET
            eventCount = eventCount + :eventDelta,
            sampleCount = sampleCount + 1
    """)
    suspend fun recordOutcome(serverIndex: Int, hourOfDay: Int, eventDelta: Int)

    @Query("SELECT * FROM provider_hourly_stats WHERE serverIndex = :serverIndex ORDER BY hourOfDay ASC")
    suspend fun getForProvider(serverIndex: Int): List<ProviderHourlyStatsEntity>

    @Query("SELECT DISTINCT serverIndex FROM provider_hourly_stats")
    suspend fun getTrackedServerIndexes(): List<Int>
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
    // Clears the series' dismissedFromContinueWatching flag on every episode progress save — a
    // dismissed series should reappear in Continue Watching once the user resumes it.
    @Query("UPDATE series SET dismissedFromContinueWatching = 0 WHERE seriesId = :seriesId")
    suspend fun clearContinueWatchingDismissal(seriesId: Int)
    suspend fun saveProgress(seriesId: Int, season: Int, episode: Int, watchedMs: Long, durationMs: Long) {
        ensureRow(seriesId, season, episode)
        updateProgress(seriesId, season, episode, watchedMs, durationMs)
        clearContinueWatchingDismissal(seriesId)
    }
    @Query("SELECT watchedMs FROM episode_watched WHERE seriesId = :seriesId AND season = :season AND episode = :episode")
    suspend fun getWatchedMs(seriesId: Int, season: Int, episode: Int): Long?
    @Query("SELECT durationMs FROM episode_watched WHERE seriesId = :seriesId AND season = :season AND episode = :episode")
    suspend fun getDurationMs(seriesId: Int, season: Int, episode: Int): Long?
    // Series with any episode progress (started or fully watched) — used to float
    // "watching/watched" series to the top of the main Series list.
    @Query("SELECT DISTINCT seriesId FROM episode_watched WHERE watchedAt > 0 OR watchedMs > 0")
    fun getSeriesIdsWithProgress(): kotlinx.coroutines.flow.Flow<List<Int>>

    // Watched History (series) — one row per finished episode, joined to the parent series for
    // its name/cover, most-recent-first. Uses the same 0.95 watchedMs/durationMs completion
    // ratio as getInProgressVod/getInProgressSeries rather than watchedAt, since watchedAt is
    // only ever set by Trakt import/cross-device sync (see SeriesDetailActivity's kdoc) — a
    // user who's never connected Trakt would otherwise see an empty history despite having
    // actually finished episodes locally. rowid stands in for "most recently watched" (no
    // per-row timestamp exists for local completion), same convention getInProgressSeries uses.
    @Query("""
        SELECT ew.seriesId AS seriesId, s.name AS seriesName, s.cover AS seriesCover,
               ew.season AS season, ew.episode AS episode, ew.rowid AS watchedAt
        FROM episode_watched ew
        INNER JOIN series s ON s.seriesId = ew.seriesId
        WHERE ew.durationMs > 0 AND ew.watchedMs > 0
          AND CAST(ew.watchedMs AS REAL) / ew.durationMs >= 0.95
        ORDER BY ew.rowid DESC
    """)
    fun getWatchHistoryEpisodes(): kotlinx.coroutines.flow.Flow<List<WatchedEpisodeRow>>

    // "Previously on..." recap card (SeriesDetailActivity) — last few COMPLETED episodes for one
    // series, most recent first. Same 0.95 completion-ratio + rowid-recency convention as
    // getWatchHistoryEpisodes/getInProgressSeries above, deliberately NOT watchedAt (only ever
    // set by Trakt import/cross-device sync, see those kdocs — would leave the recap empty for
    // anyone who's never connected Trakt despite having really finished episodes locally).
    @Query("""
        SELECT season, episode FROM episode_watched
        WHERE seriesId = :seriesId AND durationMs > 0 AND watchedMs > 0
          AND CAST(watchedMs AS REAL) / durationMs >= 0.95
        ORDER BY rowid DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyCompletedEpisodes(seriesId: Int, limit: Int = 3): List<CompletedEpisodeRef>
}

data class CompletedEpisodeRef(val season: Int, val episode: Int)

data class WatchedEpisodeRow(
    val seriesId: Int,
    val seriesName: String,
    val seriesCover: String?,
    val season: Int,
    val episode: Int,
    val watchedAt: Long
)

@Dao
interface MergedChannelDao {
    @Query("SELECT * FROM merged_channels WHERE isHidden = 0 ORDER BY serverIndex ASC, num ASC")
    fun getAll(): Flow<List<MergedChannelEntity>>
    // See ChannelDao.getFirstChannel's matching kdoc — same purpose, scoped to one server.
    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex AND isHidden = 0 LIMIT 1")
    suspend fun getFirstChannel(serverIndex: Int): MergedChannelEntity?
    @Upsert
    suspend fun upsertAll(channels: List<MergedChannelEntity>)
    @Query("DELETE FROM merged_channels")
    suspend fun clearAll()
    // A per-server clear, used instead of clearAll() when refreshing — a server whose fetch
    // failed contributes no new rows, so wiping the whole table would permanently delete that
    // server's (still possibly correct) cached channels and favorites, not just staleness.
    @Query("DELETE FROM merged_channels WHERE serverIndex = :serverIndex")
    suspend fun clearForServer(serverIndex: Int)

    @Query("SELECT serverIndex, serverNickname, COUNT(*) as channelCount FROM merged_channels WHERE isHidden = 0 GROUP BY serverIndex, serverNickname ORDER BY serverIndex")
    fun getServerSummaries(): Flow<List<MergedServerSummary>>

    // Every channel for one server regardless of category — needed to resolve XMLTV channels
    // to merged-channel streamIds the same way fetchXmltvEpg resolves against the primary
    // provider's full channel list (getByServerAndCategory requires a specific/null category,
    // not "any category").
    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex")
    suspend fun getAllForServer(serverIndex: Int): List<MergedChannelEntity>

    @Query("SELECT categoryId, categoryName, COUNT(*) as channelCount FROM merged_channels WHERE serverIndex = :serverIndex AND isHidden = 0 GROUP BY categoryId, categoryName ORDER BY categoryName")
    fun getCategorySummaries(serverIndex: Int): Flow<List<MergedCategorySummary>>

    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex AND (categoryId = :categoryId OR (categoryId IS NULL AND :categoryId IS NULL)) AND isHidden = 0 ORDER BY num")
    fun getByServerAndCategory(serverIndex: Int, categoryId: String?): Flow<List<MergedChannelEntity>>

    // Single-row lookup by composite key — used by recording retry to re-resolve a merged
    // channel's current URL/name without pulling the whole table.
    @Query("SELECT * FROM merged_channels WHERE serverIndex = :serverIndex AND streamId = :streamId LIMIT 1")
    suspend fun getByIndexAndId(serverIndex: Int, streamId: Int): MergedChannelEntity?

    // Searches across every configured server at once (not scoped to a selected server/
    // category) — matches how search already works on every other tab in this app.
    @Query("SELECT * FROM merged_channels WHERE name LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY serverIndex, num")
    fun search(query: String): Flow<List<MergedChannelEntity>>

    // Favorites/folders for merged channels, same shape as ChannelDao's — reuses the same
    // FavoriteFolderEntity rows (shared folder names) so "the way favorites work for the
    // primary provider" applies here too, just scoped to this table.
    @Query("SELECT serverIndex, streamId, isFavorite, favoriteFolderId, manualGenre FROM merged_channels")
    suspend fun getUserData(): List<MergedChannelUserData>
    @Query("SELECT * FROM merged_channels WHERE isFavorite = 1 AND isHidden = 0 ORDER BY favOrder ASC, name ASC")
    fun getAllFavorites(): Flow<List<MergedChannelEntity>>
    @Query("SELECT * FROM merged_channels WHERE isFavorite = 1 AND favoriteFolderId = :folderId AND isHidden = 0 ORDER BY favOrder ASC, name ASC")
    fun getFavoritesInFolder(folderId: Int): Flow<List<MergedChannelEntity>>
    @Query("SELECT * FROM merged_channels WHERE isFavorite = 1 AND favoriteFolderId IS NULL AND isHidden = 0 ORDER BY favOrder ASC, name ASC")
    fun getUnfiledFavorites(): Flow<List<MergedChannelEntity>>
    @Query("UPDATE merged_channels SET favOrder = :order WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun updateFavOrder(serverIndex: Int, streamId: Int, order: Int)
    @Query("SELECT favoriteFolderId, COUNT(*) as channelCount FROM merged_channels WHERE isFavorite = 1 AND isHidden = 0 GROUP BY favoriteFolderId")
    fun getFavoriteCountsByFolder(): Flow<List<FavoriteFolderCount>>
    @Query("UPDATE merged_channels SET isFavorite = :favorite WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun setFavorite(serverIndex: Int, streamId: Int, favorite: Boolean)
    @Query("UPDATE merged_channels SET favoriteFolderId = :folderId, isFavorite = 1 WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun setFavoriteFolder(serverIndex: Int, streamId: Int, folderId: Int?)
    @Query("UPDATE merged_channels SET favoriteFolderId = NULL WHERE favoriteFolderId = :folderId")
    suspend fun clearFolderFromChannels(folderId: Int)
    // Hide-individual-channel support, same shape as MergedVodDao's bulkSetHidden/getHidden.
    @Query("UPDATE merged_channels SET isHidden = 1 WHERE serverIndex = :serverIndex AND streamId IN (:streamIds)")
    suspend fun bulkSetHidden(serverIndex: Int, streamIds: List<Int>)
    @Query("SELECT * FROM merged_channels WHERE isHidden = 1 ORDER BY serverIndex ASC, name ASC")
    fun getHidden(): Flow<List<MergedChannelEntity>>
    @Query("UPDATE merged_channels SET isHidden = 0 WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun unhide(serverIndex: Int, streamId: Int)
    @Query("UPDATE merged_channels SET manualGenre = :genre WHERE serverIndex = :serverIndex AND streamId IN (:streamIds)")
    suspend fun bulkSetManualGenre(serverIndex: Int, streamIds: List<Int>, genre: String?)
}

data class MergedChannelUserData(val serverIndex: Int, val streamId: Int, val isFavorite: Boolean, val favoriteFolderId: Int?, val manualGenre: String?)

// Movies-tab equivalent of MergedChannelDao — same shape throughout (per-server clear on
// refresh, favorites/folders reusing FavoriteFolderEntity), see MergedVodEntity kdoc.
@Dao
interface MergedVodDao {
    @Query("SELECT * FROM merged_vod WHERE isHidden = 0 ORDER BY serverIndex ASC, name ASC")
    fun getAll(): Flow<List<MergedVodEntity>>

    // Cross-provider "Recently Added" for the TV home landing screen — same added-string
    // ordering as VodDao.getRecentlyAddedVod, spanning every configured secondary provider at
    // once rather than scoped to one serverIndex like getByServerAndCategory is.
    @Query("SELECT * FROM merged_vod WHERE isHidden = 0 ORDER BY added DESC, name ASC LIMIT 20")
    fun getRecentlyAdded(): Flow<List<MergedVodEntity>>
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

@Dao
interface DownloadedContentDao {
    // Drives the detail-page download icon/state reactively (Download / Downloading X% / Downloaded).
    @Query("SELECT * FROM downloaded_content WHERE streamId = :streamId LIMIT 1")
    fun observeByStreamId(streamId: Int): Flow<DownloadedContentEntity?>

    @Query("SELECT * FROM downloaded_content WHERE streamId = :streamId LIMIT 1")
    suspend fun getByStreamId(streamId: Int): DownloadedContentEntity?

    @Query("SELECT * FROM downloaded_content WHERE status = 'COMPLETE'")
    fun getAllCompleted(): Flow<List<DownloadedContentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedContentEntity)

    @Query("UPDATE downloaded_content SET status = :status, progressPercent = :progressPercent WHERE streamId = :streamId")
    suspend fun updateProgress(streamId: Int, status: DownloadStatus, progressPercent: Int)

    // Fed by DownloadProgressListener's poll loop (Media3 has no periodic progress callback —
    // see that class's kdoc). bytesDownloaded doubles as the display value when the server
    // sends no Content-Length and percentDownloaded stays unset.
    @Query("UPDATE downloaded_content SET status = :status, progressPercent = :progressPercent, fileSizeBytes = :fileSizeBytes WHERE streamId = :streamId")
    suspend fun updateProgressAndBytes(streamId: Int, status: DownloadStatus, progressPercent: Int, fileSizeBytes: Long)

    @Query("UPDATE downloaded_content SET status = :status, progressPercent = :progressPercent, fileSizeBytes = :fileSizeBytes, downloadedAt = :downloadedAt WHERE streamId = :streamId")
    suspend fun markComplete(streamId: Int, status: DownloadStatus, progressPercent: Int, fileSizeBytes: Long, downloadedAt: Long)

    @Query("UPDATE downloaded_content SET status = :status WHERE streamId = :streamId")
    suspend fun setStatus(streamId: Int, status: DownloadStatus)

    @Query("SELECT downloadId FROM downloaded_content WHERE streamId = :streamId LIMIT 1")
    suspend fun getDownloadId(streamId: Int): String?

    @Query("DELETE FROM downloaded_content WHERE streamId = :streamId")
    suspend fun deleteByStreamId(streamId: Int)
}

@Dao
interface BandwidthUsageDao {
    // Accumulates onto whatever's already recorded for this provider/month rather than
    // overwriting — PlayerActivity's debounced flush calls this once per ~5-10s chunk of
    // playback, not once per month total.
    @Query("""
        INSERT INTO bandwidth_usage (serverIndex, yearMonth, bytesTransferred)
        VALUES (:serverIndex, :yearMonth, :bytes)
        ON CONFLICT(serverIndex, yearMonth) DO UPDATE SET bytesTransferred = bytesTransferred + :bytes
    """)
    suspend fun addUsage(serverIndex: Int, yearMonth: String, bytes: Long)

    @Query("SELECT * FROM bandwidth_usage WHERE yearMonth = :yearMonth AND bytesTransferred > 0 ORDER BY bytesTransferred DESC")
    suspend fun getUsageForMonth(yearMonth: String): List<BandwidthUsageEntity>

    @Query("SELECT bytesTransferred FROM bandwidth_usage WHERE serverIndex = :serverIndex AND yearMonth = :yearMonth")
    suspend fun getUsageForServerMonth(serverIndex: Int, yearMonth: String): Long?

    @Query("SELECT COALESCE(SUM(bytesTransferred), 0) FROM bandwidth_usage WHERE yearMonth = :yearMonth")
    suspend fun getTotalUsageForMonth(yearMonth: String): Long
}

@Dao
interface EpgDiffAlertDao {
    @Insert
    suspend fun insert(alert: EpgDiffAlertEntity)

    @Query("SELECT * FROM epg_diff_alerts WHERE shown = 0 ORDER BY timestamp ASC")
    suspend fun getUnshown(): List<EpgDiffAlertEntity>

    @Query("UPDATE epg_diff_alerts SET shown = 1 WHERE id IN (:ids)")
    suspend fun markShown(ids: List<Long>)

    // Keeps the table from growing forever — only unshown rows and a short recent tail of
    // already-shown ones are worth keeping around.
    @Query("DELETE FROM epg_diff_alerts WHERE shown = 1 AND timestamp < :beforeMs")
    suspend fun pruneShownOlderThan(beforeMs: Long)
}