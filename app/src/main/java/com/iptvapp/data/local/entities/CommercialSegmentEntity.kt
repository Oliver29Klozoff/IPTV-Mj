package com.iptvapp.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One commercial interval for a recording row. Times are the player's timeline. */
@Entity(
    tableName = "commercial_segments",
    indices = [Index(value = ["recordingId"])]
)
data class CommercialSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Int,
    val startMs: Long,
    val endMs: Long,
    val origin: String,
    val review: String,
    val evidence: String,
    val analysisVersion: Int,
    val fileSize: Long,
    val fileModifiedMs: Long,
    val updatedAtMs: Long
)
