package com.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.iptvapp.data.local.entities.CommercialSegmentEntity

@Dao
interface CommercialDao {
    @Query("SELECT * FROM commercial_segments WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun forRecording(recordingId: Int): List<CommercialSegmentEntity>

    @Query("DELETE FROM commercial_segments WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Int)

    @Insert
    suspend fun insertAll(rows: List<CommercialSegmentEntity>)

    @Transaction
    suspend fun replace(recordingId: Int, rows: List<CommercialSegmentEntity>) {
        deleteForRecording(recordingId)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
