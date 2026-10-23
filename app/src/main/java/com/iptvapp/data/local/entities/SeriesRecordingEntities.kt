package com.iptvapp.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "series_recording_rules")
data class SeriesRecordingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val seriesKey: String?,
    val providerKey: String,
    val serverIndex: Int,
    val streamId: Int,
    val channelName: String,
    val mode: String = "ALL",
    val startPaddingMinutes: Int = 0,
    val endPaddingMinutes: Int = 0,
    val keepCount: Int = 0,
    val stopLowStorage: Boolean = true,
    val priority: Int = 50,
    val paused: Boolean = false,
    val cancelled: Boolean = false
)

/** History survives rule cancellation and recording cleanup to prevent repeated captures. */
@Entity(tableName = "series_recording_episodes", indices = [Index("ruleId"), Index("recordingId")])
data class SeriesRecordingEpisode(
    @PrimaryKey val identity: String,
    val ruleId: Long,
    val epgId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val status: String,
    val reason: String,
    val recordingId: Int? = null,
    val confirmed: Boolean = false
)
