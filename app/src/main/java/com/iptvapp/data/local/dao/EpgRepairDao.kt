package com.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.iptvapp.data.local.entities.EpgGuideChannelEntity
import com.iptvapp.data.local.entities.EpgGuideSourceEntity
import com.iptvapp.data.local.entities.EpgOverrideEntity
import kotlinx.coroutines.flow.Flow

/** The EPG repair tables, plus the few guide-row reads the health scan needs. */
@Dao
interface EpgRepairDao {

    // ── Sources ────────────────────────────────────────────────────────

    @Query("SELECT * FROM epg_guide_sources WHERE serverIndex = :serverIndex AND sourceKey = :sourceKey")
    suspend fun getSource(serverIndex: Int, sourceKey: String): EpgGuideSourceEntity?

    @Query("SELECT * FROM epg_guide_sources ORDER BY serverIndex, rank")
    suspend fun getSources(): List<EpgGuideSourceEntity>

    @Upsert
    suspend fun upsertSource(source: EpgGuideSourceEntity)

    @Query("DELETE FROM epg_guide_sources WHERE serverIndex = :serverIndex AND sourceKey NOT IN (:keep)")
    suspend fun deleteSourcesExcept(serverIndex: Int, keep: List<String>)

    @Query("DELETE FROM epg_guide_sources WHERE serverIndex = :serverIndex")
    suspend fun deleteSourcesForServer(serverIndex: Int)

    // ── Guide channel catalog ──────────────────────────────────────────

    @Query("SELECT * FROM epg_guide_channels WHERE serverIndex = :serverIndex")
    suspend fun getGuideChannels(serverIndex: Int): List<EpgGuideChannelEntity>

    /** The catalog without its listing samples: what a scan needs, a fraction of the memory. */
    @Query("SELECT serverIndex, sourceKey, xmltvId, displayName, programCount, lastStop, '' AS sample FROM epg_guide_channels WHERE serverIndex = :serverIndex")
    suspend fun getGuideChannelsLite(serverIndex: Int): List<EpgGuideChannelEntity>

    @Query("SELECT * FROM epg_guide_channels WHERE serverIndex = :serverIndex AND sourceKey = :sourceKey AND xmltvId = :xmltvId")
    suspend fun getGuideChannel(serverIndex: Int, sourceKey: String, xmltvId: String): EpgGuideChannelEntity?

    /** Manual matching: guide channels whose name or id contains [like] (already "%x%"). */
    @Query(
        "SELECT * FROM epg_guide_channels WHERE serverIndex = :serverIndex " +
            "AND (displayName LIKE :like OR xmltvId LIKE :like) ORDER BY displayName LIMIT :limit"
    )
    suspend fun searchGuideChannels(serverIndex: Int, like: String, limit: Int): List<EpgGuideChannelEntity>

    @Query("DELETE FROM epg_guide_channels WHERE serverIndex = :serverIndex AND sourceKey = :sourceKey")
    suspend fun deleteGuideChannels(serverIndex: Int, sourceKey: String)

    @Query("DELETE FROM epg_guide_channels WHERE serverIndex = :serverIndex AND sourceKey NOT IN (:keep)")
    suspend fun deleteGuideChannelsExcept(serverIndex: Int, keep: List<String>)

    @Query("DELETE FROM epg_guide_channels WHERE serverIndex = :serverIndex")
    suspend fun deleteGuideChannelsForServer(serverIndex: Int)

    @Upsert
    suspend fun upsertGuideChannels(rows: List<EpgGuideChannelEntity>)

    /** One source's catalog, replaced whole: only ever called with a successful parse. */
    @Transaction
    suspend fun replaceGuideChannels(serverIndex: Int, sourceKey: String, rows: List<EpgGuideChannelEntity>) {
        deleteGuideChannels(serverIndex, sourceKey)
        rows.chunked(500).forEach { upsertGuideChannels(it) }
    }

    // ── Corrections ────────────────────────────────────────────────────

    @Query("SELECT * FROM epg_overrides")
    suspend fun getOverrides(): List<EpgOverrideEntity>

    @Query("SELECT * FROM epg_overrides")
    fun observeOverrides(): Flow<List<EpgOverrideEntity>>

    @Query("SELECT * FROM epg_overrides WHERE serverIndex = :serverIndex")
    suspend fun getOverridesForServer(serverIndex: Int): List<EpgOverrideEntity>

    @Query("SELECT * FROM epg_overrides WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun getOverride(serverIndex: Int, streamId: Int): EpgOverrideEntity?

    @Upsert
    suspend fun upsertOverride(row: EpgOverrideEntity)

    @Query("DELETE FROM epg_overrides WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun deleteOverride(serverIndex: Int, streamId: Int)

    // ── Guide rows, read for the scan ──────────────────────────────────

    /** Per channel: when its last listing ends. Uses the (serverIndex, streamId, start) index. */
    @Query("SELECT streamId, MAX(stopTimestamp) AS lastStop, COUNT(*) AS rowCount FROM epg_entries WHERE serverIndex = :serverIndex GROUP BY streamId")
    suspend fun guideCoverage(serverIndex: Int): List<EpgCoverageRow>

    /** Per channel: one XMLTV row id, which names the guide channel it came from ("x_<id>_…"). */
    @Query("SELECT streamId, MAX(id) AS rowId FROM epg_entries WHERE serverIndex = :serverIndex AND id LIKE 'x\\_%' ESCAPE '\\' GROUP BY streamId")
    suspend fun xmltvRowIds(serverIndex: Int): List<EpgRowIdRow>

    /** One channel's XMLTV row id (which names the guide channel its listings come from), or null. */
    @Query("SELECT MAX(id) FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId AND id LIKE 'x\\_%' ESCAPE '\\'")
    suspend fun xmltvRowIdFor(serverIndex: Int, streamId: Int): String?

    /** When one channel's last listing ends (0 when it has none). */
    @Query("SELECT COALESCE(MAX(stopTimestamp), 0) FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun lastStopFor(serverIndex: Int, streamId: Int): Long

    /** Upcoming listings of one channel, for comparing the provider's own short guide with XMLTV. */
    @Query("SELECT id, title, startTimestamp, stopTimestamp FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId AND stopTimestamp > :afterSec ORDER BY startTimestamp LIMIT 200")
    suspend fun upcoming(serverIndex: Int, streamId: Int, afterSec: Long): List<EpgListingRow>

    /** Channels with both XMLTV rows and the provider's own short-guide rows (primary only). */
    @Query("SELECT DISTINCT streamId FROM epg_entries WHERE serverIndex = :serverIndex AND id NOT LIKE 'x\\_%' ESCAPE '\\' AND stopTimestamp > :afterSec")
    suspend fun streamsWithShortGuide(serverIndex: Int, afterSec: Long): List<Int>

    /** A saved time offset moved by [deltaSec]: this channel's XMLTV listings move with it. */
    @Query("UPDATE epg_entries SET startTimestamp = startTimestamp + :deltaSec, stopTimestamp = stopTimestamp + :deltaSec, nowPlaying = CASE WHEN startTimestamp + :deltaSec <= :nowSec AND stopTimestamp + :deltaSec > :nowSec THEN 1 ELSE 0 END WHERE serverIndex = :serverIndex AND streamId = :streamId AND id LIKE 'x\\_%' ESCAPE '\\'")
    suspend fun shiftXmltvRows(serverIndex: Int, streamId: Int, deltaSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): Int

    /** The provider's own short-guide rows of one channel (not XMLTV): dropped when the user chose its guide channel. */
    @Query("DELETE FROM epg_entries WHERE serverIndex = :serverIndex AND streamId = :streamId AND id NOT LIKE 'x\\_%' ESCAPE '\\'")
    suspend fun deleteShortGuideRows(serverIndex: Int, streamId: Int): Int

    /** Recordings still to come on one channel, for warning before its listings move. */
    @Query("SELECT COUNT(*) FROM recordings WHERE serverIndex = :serverIndex AND streamId = :streamId AND status = 'SCHEDULED' AND scheduledStartMs > :afterMs")
    suspend fun futureRecordings(serverIndex: Int, streamId: Int, afterMs: Long): Int
}

data class EpgCoverageRow(val streamId: Int, val lastStop: Long, val rowCount: Int)
data class EpgRowIdRow(val streamId: Int, val rowId: String)
data class EpgListingRow(val id: String, val title: String, val startTimestamp: Long, val stopTimestamp: Long)
