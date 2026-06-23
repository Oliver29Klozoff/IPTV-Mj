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
    val cachedAt: Long = System.currentTimeMillis()
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
