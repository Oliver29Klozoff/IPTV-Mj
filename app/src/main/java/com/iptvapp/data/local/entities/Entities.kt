package com.iptvapp.data.local.entities

import androidx.room.Entity
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
    val favoriteFolderId: Int? = null
)

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
    val cachedAt: Long = System.currentTimeMillis()
)

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
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "epg_entries")
data class EpgEntity(
    @PrimaryKey val id: String,
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
    val serverIndex: Int = -1
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
    val watchedAt: Long = System.currentTimeMillis()
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
@Entity(tableName = "merged_channels", primaryKeys = ["serverIndex", "streamId"])
data class MergedChannelEntity(
    val serverIndex: Int,
    val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val num: Int,
    val serverNickname: String,
    // A single server can itself have tens of thousands of channels (real-world reseller
    // panels observed at 30k-85k), so category grouping is required even per-server, not just
    // across servers — categoryName is denormalized here rather than a separate merged
    // categories table, since this whole cache is wholesale-refetched on every manual refresh
    // anyway (no incremental-update case to optimize for).
    val categoryId: String?,
    val categoryName: String?,
    val isFavorite: Boolean = false,
    val favoriteFolderId: Int? = null,
    val cachedAt: Long = System.currentTimeMillis()
)

// Small aggregate row (not a persisted entity) for the server-picker and category-picker
// screens in the "All Providers" view — computed with GROUP BY so the UI never needs to load
// or diff a server/provider's full multi-tens-of-thousands channel list just to show counts.
data class MergedServerSummary(val serverIndex: Int, val serverNickname: String, val channelCount: Int)
data class MergedCategorySummary(val categoryId: String?, val categoryName: String?, val channelCount: Int)
