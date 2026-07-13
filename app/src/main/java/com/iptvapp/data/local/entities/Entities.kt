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
    val isHidden: Boolean = false
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
    val status: String = "SCHEDULED"
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
