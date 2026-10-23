package com.iptvapp.data.local.dao

import androidx.room.*
import com.iptvapp.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesRecordingDao {
    @Query("SELECT * FROM series_recording_rules ORDER BY priority DESC, id")
    suspend fun rules(): List<SeriesRecordingRule>
    @Query("SELECT * FROM series_recording_rules WHERE id = :id")
    suspend fun rule(id: Long): SeriesRecordingRule?
    @Insert suspend fun insert(rule: SeriesRecordingRule): Long
    @Update suspend fun update(rule: SeriesRecordingRule)
    @Query("SELECT * FROM series_recording_episodes ORDER BY startMs")
    suspend fun episodes(): List<SeriesRecordingEpisode>
    @Query("SELECT * FROM series_recording_episodes WHERE identity = :identity")
    suspend fun episode(identity: String): SeriesRecordingEpisode?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(episode: SeriesRecordingEpisode): Long
    @Update suspend fun update(episode: SeriesRecordingEpisode)
    @Query("SELECT * FROM series_recording_episodes WHERE recordingId = :recordingId LIMIT 1")
    suspend fun forRecording(recordingId: Int): SeriesRecordingEpisode?
}
