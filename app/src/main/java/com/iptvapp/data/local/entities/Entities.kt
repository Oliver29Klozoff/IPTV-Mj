package com.iptvapp.data.local.entities

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val tvArchive: Int,
    val num: Int,
    val isFavorite: Boolean = false,
    val lastWatched: Long? = null,
    val cachedAt: Long = System.currentTimeMillis(),
    val streamUrl: String? = null,
    val favOrder: Int = 0,
    val viewCount: Int = 0,
    val isHidden: Boolean = false,
    // Null = not in a folder (shows in "Unsorted"). Only meaningful when isFavorite is true.
    val favoriteFolderId: Int? = null,
    // Manually pins this favorite to one genre chip (currently only ever "Other," the sole
    // user-manageable chip — see GenreClassifier.OTHER), overriding keyword auto-classification
    // entirely so it shows ONLY under that chip instead of wherever its category would otherwise
    // land it. Null = no manual override, auto-classify as before.
    val manualGenre: String? = null,
    // User-assigned channel number, overriding the provider's own `num` for sorting and for the
    // TV remote's numeric channel-jump. Only ever set for US channels — some providers number
    // their US block in the tens of thousands (e.g. 34783-46555), so those channels are
    // otherwise unreachable by number entry and awkward to keep straight by number at all. Null
    // = no override, use `num` as before.
    val customNum: Int? = null
)

// FTS4 shadow table for channels.name, replacing the old `LIKE '%query%'` full-table-scan search
// (a real bottleneck on 55k-112k+ row catalogs). Uses `contentEntity` ("external content") so Room
// wires up SQLite triggers that keep this index in sync automatically on every insert/update/delete
// through ChannelDao's existing @Upsert — no manual sync code needed. FTS5 is NOT available here:
// Android's bundled SQLite build doesn't include it without a custom SQLite binary, so FTS4 is the
// correct (not a downgraded) choice for this app. See ChannelDao.searchChannels for the MATCH query
// and the substring-vs-prefix tradeoff this introduces.
@Fts4(contentEntity = ChannelEntity::class)
@Entity(tableName = "channels_fts")
data class ChannelFts(val name: String)

// User-created groups for organizing favorites (e.g. "Sports", "News", "Kids") — same
// drill-down UX as Movies' categories, but user-named instead of provider-supplied.
@Entity(tableName = "favorite_folders")
data class FavoriteFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val parentId: Int,
    val type: String,
    val isFavorite: Boolean = false
)

@Entity(tableName = "vod_streams")
data class VodEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val categoryId: String?,
    val rating: String?,
    val containerExtension: String,
    val added: String?,
    val isFavorite: Boolean = false,
    val watchedMs: Long = 0L,
    val durationMs: Long = 0L,
    val cachedAt: Long = System.currentTimeMillis(),
    val dismissedFromContinueWatching: Boolean = false,
    // Set whenever updateWatchProgress() is called (i.e. actual playback progress, not catalog
    // refresh) — cachedAt above only reflects when this row was last synced from the provider's
    // catalog, not when the user last actually watched it, so it can't back a staleness cleanup.
    // Used by ContinueWatchingCleanupWorker to auto-clear long-abandoned in-progress entries.
    val lastWatchedAt: Long = 0L
)

// See ChannelFts kdoc — same FTS4 external-content pattern, mirroring vod_streams.name.
@Fts4(contentEntity = VodEntity::class)
@Entity(tableName = "vod_streams_fts")
data class VodFts(val name: String)

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesId: Int,
    val name: String,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val rating: String?,
    val categoryId: String?,
    val isFavorite: Boolean = false,
    val watchedMs: Long = 0L,
    val durationMs: Long = 0L,
    val cachedAt: Long = System.currentTimeMillis(),
    val isHidden: Boolean = false,
    val dismissedFromContinueWatching: Boolean = false,
    val lastWatchedAt: Long = 0L
)

// See ChannelFts kdoc — same FTS4 external-content pattern, mirroring series.name.
@Fts4(contentEntity = SeriesEntity::class)
@Entity(tableName = "series_fts")
data class SeriesFts(val name: String)

// serverIndex disambiguates which server this program listing belongs to — -1 = primary
// provider, 0..N-1 = extraServers[i]. Two different servers can reuse the same numeric
// streamId (and even coincidentally the same raw EPG listing id), so id alone can't be the
// primary key once merged/secondary providers have EPG data too — same sentinel/composite-key
// pattern as RecordingEntity.serverIndex and MergedChannelEntity's (serverIndex, streamId) key.
@Entity(tableName = "epg_entries", primaryKeys = ["serverIndex", "id"])
data class EpgEntity(
    val serverIndex: Int = -1,
    val id: String,
    val streamId: Int,
    val title: String,
    val description: String,
    val startTimestamp: Long,
    val stopTimestamp: Long,
    val nowPlaying: Int,
    val hasArchive: Int
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val streamId: Int,
    val channelName: String,
    val scheduledStartMs: Long,
    val durationMs: Long,
    val outputPath: String,
    val status: String = "SCHEDULED",
    // -1 = primary server (same sentinel MergedChannelEntity uses). streamId alone isn't
    // globally unique once merged-provider recordings exist — two different servers can reuse
    // the same numeric id — so this disambiguates which server streamId is scoped to.
    val serverIndex: Int = -1,
    // Only ever set when status becomes FAILED — previously a recording just showed "FAILED"
    // with zero indication why (connection-limit rejection vs. network blip vs. storage
    // failure), which was especially confusing for the single-connection-plan conflict case
    // (two recordings scheduled overlapping, one wins the one available connection and the
    // other fails). See RecordingService.classifyFailureReason.
    val failureReason: String? = null,
    // Captured from the EPG's "now" entry for this channel at the moment the recording was
    // scheduled/started (best-effort — null if no EPG data was available). This is the only
    // program-identity signal recordings have; channelName alone (e.g. "ESPN HD") is a channel,
    // not a program, and can't be scrobbled to Trakt correctly. See PlayerActivity's Trakt
    // scrobble gating, which now uses this when a recording is played back.
    val programTitle: String? = null
)

// Per-episode watched state, keyed by (seriesId, season, episode) — deliberately independent
// of whether that episode's stream info is currently cached locally (episodes themselves
// aren't persisted; only fetched live from the provider per series). This exists purely so a
// Trakt watched-history sync-back has somewhere to record "you've seen S02E05 of this show"
// even for episodes the app has never itself fetched/played.
@Entity(tableName = "episode_watched", primaryKeys = ["seriesId", "season", "episode"])
data class EpisodeWatchedEntity(
    val seriesId: Int,
    val season: Int,
    val episode: Int,
    val watchedAt: Long = System.currentTimeMillis(),
    // Resume position for this specific episode. Series-level resume can't work per-episode —
    // a series has many episodes, each with its own progress — so this lives here rather than
    // on SeriesEntity (which only tracks the last-opened series' own position for VOD-style use).
    val watchedMs: Long = 0L,
    val durationMs: Long = 0L
)

// Rolling reliability history per channel — outcomes is a string of '1'/'0' characters,
// oldest first, capped to the last 10 (see ReliabilityDao.recordOutcome). Built from both
// explicit "check favorites health" pings and real playback attempts (mini player ready/
// error), so it reflects actual usage, not just a one-off ping.
@Entity(tableName = "channel_reliability")
data class ChannelReliabilityEntity(
    @PrimaryKey val streamId: Int,
    val outcomes: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)

// Browse-and-play-only cache for the merged "All Providers" view — deliberately separate from
// ChannelEntity (whose bare streamId PK assumes global uniqueness) since two different Xtream
// servers can reuse the same numeric stream id. serverIndex -1 = primary server, 0..N-1 =
// extraServers[i]. Refetched wholesale on manual refresh — recording and Trakt remain scoped
// to the primary server's ChannelEntity table only, but favorites/folders ARE supported here
// now (isFavorite/favoriteFolderId), reusing the same FavoriteFolderEntity rows as the primary
// provider's favorites so folder names stay one shared list. Preserved across every refresh the
// same way ChannelEntity's isFavorite/favoriteFolderId are (see XtreamRepository.fetchLiveStreams
// and refreshMergedChannels) — a wholesale re-fetch must not silently un-favorite everything.
// Indexed on (serverIndex, categoryId) — a real-world provider can have 30k-85k channels, and
// every category switch runs a query filtered exactly on those two columns (getByServerAndCategory).
// Without an index that's a full table scan per tap, which is what made switching categories in
// the combined Live tab noticeably laggy for large merged providers.
@Entity(
    tableName = "merged_channels",
    primaryKeys = ["serverIndex", "streamId"],
    indices = [Index(value = ["serverIndex", "categoryId"])]
)
data class MergedChannelEntity(
    val serverIndex: Int,
    val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val num: Int,
    val serverNickname: String,
    // Xtream's own stable per-channel EPG identifier (get_live_streams' epg_channel_id) — was
    // never captured for merged/secondary providers at all, unlike the primary ChannelEntity
    // which always had it. Without it, fetchXmltvEpgForMergedServer could only fall back to
    // fuzzy channel-name matching against the XMLTV feed, which is why favorited channels on a
    // provider with a large/messy XMLTV feed could get zero guide data even when the fetch
    // itself succeeded — see XtreamRepository.fetchXmltvFromUrl (primary path) for the
    // byEpgId-first matching this now allows merged channels to use too.
    val epgChannelId: String? = null,
    // A single server can itself have tens of thousands of channels (real-world reseller
    // panels observed at 30k-85k), so category grouping is required even per-server, not just
    // across servers — categoryName is denormalized here rather than a separate merged
    // categories table, since this whole cache is wholesale-refetched on every manual refresh
    // anyway (no incremental-update case to optimize for).
    val categoryId: String?,
    val categoryName: String?,
    val isFavorite: Boolean = false,
    val favoriteFolderId: Int? = null,
    val cachedAt: Long = System.currentTimeMillis(),
    val isHidden: Boolean = false,
    // Shares one flat ordering sequence with ChannelEntity.favOrder — Favorites' drag-reorder
    // (HomeActivity's Reorder mode) assigns sequential values across BOTH tables at once when a
    // drag is committed, so a primary channel and a merged channel can sit next to each other in
    // any order the user actually dragged them into, not just "primary block, then merged block."
    val favOrder: Int = 0,
    // Same manual genre-chip override as ChannelEntity.manualGenre — see its kdoc.
    val manualGenre: String? = null
)

// Small aggregate row (not a persisted entity) for the server-picker and category-picker
// screens in the "All Providers" view — computed with GROUP BY so the UI never needs to load
// or diff a server/provider's full multi-tens-of-thousands channel list just to show counts.
data class MergedServerSummary(val serverIndex: Int, val serverNickname: String, val channelCount: Int)
data class MergedCategorySummary(val categoryId: String?, val categoryName: String?, val channelCount: Int)

// Movies-tab equivalent of MergedChannelEntity — same serverIndex convention, same
// wholesale-refetch-preserving-favorites approach (see XtreamRepository.refreshMergedVod).
// No per-item plot/cast detail fetch — merged VOD plays directly rather than going through a
// detail screen, unlike primary VOD's VodDetailActivity.
@Entity(tableName = "merged_vod", primaryKeys = ["serverIndex", "streamId"])
data class MergedVodEntity(
    val serverIndex: Int,
    val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val serverNickname: String,
    val categoryId: String?,
    val categoryName: String?,
    val rating: String?,
    val containerExtension: String,
    val added: String?,
    val isFavorite: Boolean = false,
    val favoriteFolderId: Int? = null,
    val cachedAt: Long = System.currentTimeMillis(),
    val watchedMs: Long = 0L,
    val durationMs: Long = 0L,
    val isHidden: Boolean = false
)

data class MergedVodServerSummary(val serverIndex: Int, val serverNickname: String, val vodCount: Int)
data class MergedVodCategorySummary(val categoryId: String?, val categoryName: String?, val vodCount: Int)

// Series-tab equivalent of MergedVodEntity — same serverIndex convention, same
// wholesale-refetch-preserving-favorites approach. Unlike MergedVodEntity, this table stores
// ONLY series metadata (name/cover/plot/genre/rating) — season/episode data is fetched
// on-demand each time the detail screen opens (see XtreamRepository.fetchMergedSeriesInfo) and
// is never cached, since primary-provider SeriesEntity doesn't cache episodes either and
// caching them here would need a second new table plus invalidation for little real benefit.
@Entity(tableName = "merged_series", primaryKeys = ["serverIndex", "seriesId"])
data class MergedSeriesEntity(
    val serverIndex: Int,
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val rating: String?,
    val serverNickname: String,
    val categoryId: String?,
    val categoryName: String?,
    val isFavorite: Boolean = false,
    val favoriteFolderId: Int? = null,
    val cachedAt: Long = System.currentTimeMillis(),
    val isHidden: Boolean = false
)

data class MergedSeriesServerSummary(val serverIndex: Int, val serverNickname: String, val seriesCount: Int)
data class MergedSeriesCategorySummary(val categoryId: String?, val categoryName: String?, val seriesCount: Int)

enum class DownloadType { MOVIE, EPISODE }
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

// Tracks a Media3-managed offline download so the movie/episode detail pages can show
// "Download / Downloading X% / Downloaded" and PlayerActivity can prefer a local file over the
// network URL. Media3's own DownloadManager/DownloadIndex is the source of truth for the actual
// download bytes/progress — this table mirrors it for cheap Flow-driven UI queries (a Room Flow
// is far simpler to observe from an Activity than Media3's DownloadManager.DownloadListener).
// For MOVIE, streamId is the VOD's own streamId. For EPISODE, streamId is the same
// episode.id.hashCode() PlayerActivity already uses as its stream_id extra (see
// SeriesDetailActivity.launchEpisode) — reused here as the primary key so both places identify
// an episode the exact same way.
@Entity(tableName = "downloaded_content")
data class DownloadedContentEntity(
    @PrimaryKey val streamId: Int,
    val type: DownloadType,
    val seriesId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val localFilePath: String,
    // Media3's own DownloadRequest.id — needed to call DownloadService.sendRemoveDownload /
    // look the download back up in Media3's DownloadIndex.
    val downloadId: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Int = 0,
    val fileSizeBytes: Long = 0L,
    val downloadedAt: Long = 0L
)

// Per-provider network usage, bucketed by calendar month, so Settings can show "how much data
// has this provider used this month" — providers on metered/capped connections (common for
// IPTV resellers) are the motivating case. yearMonth is "yyyy-MM" (e.g. "2026-08") so a plain
// string PRIMARY KEY column sorts/filters correctly without a separate date type. Fed by
// PlayerActivity's TransferListener via a debounced accumulator (see BandwidthTracker) rather
// than a write per byte-transfer callback, which would fire far too often to hit Room directly.
@Entity(tableName = "bandwidth_usage", primaryKeys = ["serverIndex", "yearMonth"])
data class BandwidthUsageEntity(
    val serverIndex: Int,
    val yearMonth: String,
    val bytesTransferred: Long = 0L
)

// Provider Health Weather Map (Settings) — rolling, all-time (not day-bucketed, to keep the
// table tiny: 24 rows per provider forever, rather than growing one row-set per calendar day)
// hour-of-day view of how often playback hit an error/rebuffer-stall vs. played cleanly for each
// provider. Fed from the exact same PlayerActivity player-error/buffer-watchdog callsite that
// already feeds ChannelReliabilityEntity (see XtreamRepository.recordChannelOutcome) — one
// playback-outcome hook, two aggregations (per-channel there, per-provider-per-hour here).
// sampleCount is every recorded outcome (success+failure) in that hour bucket; eventCount is only
// the failures — so eventCount/sampleCount is directly a failure rate per hour-of-day, and a
// bucket with sampleCount == 0 is "no data yet" (rendered grey) vs. a genuinely clean bucket.
@Entity(tableName = "provider_hourly_stats", primaryKeys = ["serverIndex", "hourOfDay"])
data class ProviderHourlyStatsEntity(
    val serverIndex: Int,
    val hourOfDay: Int, // 0-23, local device time — matches when the user actually watches
    val eventCount: Int = 0,
    val sampleCount: Int = 0
)
