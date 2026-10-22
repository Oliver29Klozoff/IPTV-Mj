package com.iptvapp.util

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import androidx.room.withTransaction
import com.iptvapp.IptvApplication
import com.iptvapp.data.local.entities.CommercialSegmentEntity
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Commercial markers live in Room, keyed by the recording row id.
 * Detection and skip choices live in a device preference, not in settings backup.
 * The old "recording_breaks" preference is read once and then removed.
 */
object CommercialStore {
    private const val SETTINGS = "commercial_settings"
    private const val ANALYSIS = "commercial_analysis"
    private const val KEY_DETECTION = "detection"
    private const val KEY_SKIP = "skip"

    const val ANALYZING = "ANALYZING"
    const val FOUND = "FOUND"
    const val REVIEW = "REVIEW"
    const val NONE = "NONE"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val FAILED = "FAILED"

    data class AnalysisNote(
        val status: String,
        val size: Long,
        val modifiedMs: Long,
        val skipOverride: CommercialMarkers.SkipMode?,
        val attempt: Long = 0L,
        /** True only after a finished analysis of this file. An editor save does not set it. */
        val analyzed: Boolean = false,
        /** Changes whenever stored markers change, so an open player can reload. */
        val revision: Long = 0L
    ) {
        fun cacheKey(): String = "$status|$size|$modifiedMs|$revision"
    }

    fun detectionMode(context: Context): CommercialMarkers.DetectionMode =
        CommercialMarkers.detectionModeOf(settings(context).getString(KEY_DETECTION, null))

    fun setDetectionMode(context: Context, mode: CommercialMarkers.DetectionMode) {
        settings(context).edit().putString(KEY_DETECTION, mode.name).apply()
    }

    fun skipMode(context: Context): CommercialMarkers.SkipMode =
        CommercialMarkers.skipModeOf(settings(context).getString(KEY_SKIP, null))

    fun setSkipMode(context: Context, mode: CommercialMarkers.SkipMode) {
        settings(context).edit().putString(KEY_SKIP, mode.name).apply()
    }

    fun note(context: Context, recordingId: Int): AnalysisNote? {
        val raw = analysis(context).getString(recordingId.toString(), null) ?: return null
        val bits = raw.split('|')
        if (bits.size < 4) return null
        return AnalysisNote(
            bits[0],
            bits[1].toLongOrNull() ?: 0L,
            bits[2].toLongOrNull() ?: 0L,
            when (bits[3]) {
                "OFF" -> CommercialMarkers.SkipMode.OFF
                "BUTTON" -> CommercialMarkers.SkipMode.BUTTON
                "AUTOMATIC" -> CommercialMarkers.SkipMode.AUTOMATIC
                else -> null
            },
            bits.getOrNull(4)?.toLongOrNull() ?: 0L,
            bits.getOrNull(5) == "1",
            bits.getOrNull(6)?.toLongOrNull() ?: 0L
        )
    }

    fun putNote(context: Context, recordingId: Int, note: AnalysisNote) {
        val override = note.skipOverride?.name ?: ""
        val analyzed = if (note.analyzed) "1" else "0"
        analysis(context).edit()
            .putString(
                recordingId.toString(),
                "${note.status}|${note.size}|${note.modifiedMs}|$override|${note.attempt}|$analyzed|${note.revision}"
            )
            .apply()
    }

    fun clearNote(context: Context, recordingId: Int) {
        analysis(context).edit().remove(recordingId.toString()).apply()
    }

    fun alreadyAnalyzed(context: Context, recordingId: Int, stamp: CommercialMarkers.FileStamp): Boolean {
        val saved = note(context, recordingId) ?: return false
        if (!saved.analyzed) return false
        if (saved.status.isEmpty() || saved.status == ANALYZING || saved.status == FAILED) return false
        return saved.size == stamp.size && saved.modifiedMs == stamp.modifiedMs
    }

    suspend fun load(context: Context, recordingId: Int): List<CommercialMarkers.Segment> {
        if (recordingId < 0) return emptyList()
        val dao = database(context).commercialDao()
        val existing = dao.forRecording(recordingId).map { it.toSegment() }
        if (existing.isNotEmpty() || note(context, recordingId) != null) return existing
        if (!AdBreakDetector.isAnalyzed(context, recordingId)) return emptyList()
        val legacy = AdBreakDetector.breaksFor(context, recordingId)
        val imported = legacy.map {
            CommercialMarkers.Segment(
                startMs = it.first,
                endMs = it.last + 1,
                origin = CommercialMarkers.Origin.AUTOMATIC,
                review = CommercialMarkers.Review.SUGGESTED,
                evidence = "silence_rhythm"
            )
        }
        if (imported.isNotEmpty()) dao.replace(recordingId, imported.map { it.toEntity(recordingId) })
        putNote(
            context,
            recordingId,
            AnalysisNote(
                if (imported.isEmpty()) NONE else REVIEW,
                0L,
                0L,
                null,
                analyzed = true,
                revision = System.currentTimeMillis()
            )
        )
        AdBreakDetector.forget(context, recordingId)
        return dao.forRecording(recordingId).map { it.toSegment() }
    }

    suspend fun save(context: Context, recordingId: Int, segments: List<CommercialMarkers.Segment>) {
        database(context).commercialDao().replace(recordingId, segments.map { it.toEntity(recordingId) })
    }

    /**
     * Writes [suggestions] in place of older suggestions.
     * A confirmed, rejected, or manual row that is already stored is kept.
     * The library note is written only after that database commit, and only if those rows
     * are still the ones stored. A player therefore cannot cache the new revision with the old rows,
     * and a later edit is not replaced by this result.
     * Returns null when the recording is gone or its file is no longer the one that was read,
     * and leaves stored markers alone in that case.
     */
    suspend fun mergeAnalysis(
        context: Context,
        recordingId: Int,
        suggestions: List<CommercialMarkers.Segment>,
        stamp: CommercialMarkers.FileStamp,
        sameFile: (String) -> Boolean
    ): List<CommercialMarkers.Segment>? {
        val db = database(context)
        val merged = db.withTransaction {
            val recording = db.recordingDao().getById(recordingId)
            if (recording == null || recording.status != "DONE" || !sameFile(recording.outputPath)) {
                return@withTransaction null
            }
            if (stamp(context, recording.outputPath) != stamp) return@withTransaction null
            val existing = db.commercialDao().forRecording(recordingId).map { it.toSegment() }
            val merged = CommercialMarkers.applyAnalysis(existing, suggestions, stamp)
            db.commercialDao().replace(recordingId, merged.map { it.toEntity(recordingId) })
            merged
        } ?: return null
        publishNoteAfterCommit(context, recordingId, merged, stamp, fromAnalysis = true)
        return merged
    }

    /**
     * Saves one editor change without dropping markers stored since [before] was shown.
     * A skip override stays. The in-progress analysis attempt ends because the viewer wrote.
     * The note is published after the row commit, and only while those rows are still stored.
     */
    suspend fun saveEdit(
        context: Context,
        recordingId: Int,
        before: List<CommercialMarkers.Segment>,
        after: List<CommercialMarkers.Segment>,
        stamp: CommercialMarkers.FileStamp
    ) {
        val db = database(context)
        val merged = db.withTransaction {
            val current = db.commercialDao().forRecording(recordingId).map { it.toSegment() }
            val merged = CommercialMarkers.applyEdit(current, before, after)
            db.commercialDao().replace(recordingId, merged.map { it.toEntity(recordingId) })
            merged
        }
        publishNoteAfterCommit(context, recordingId, merged, stamp, fromAnalysis = false)
    }

    /**
     * SharedPreferences is visible immediately, and a Room transaction is not.
     * Writing the note first lets a player pair the new revision with the previous rows and
     * then treat that pair as current. Publish only after the row commit, and skip the note
     * when a newer save has already replaced those rows.
     */
    private suspend fun publishNoteAfterCommit(
        context: Context,
        recordingId: Int,
        segments: List<CommercialMarkers.Segment>,
        stamp: CommercialMarkers.FileStamp,
        fromAnalysis: Boolean
    ) {
        val db = database(context)
        withContext(NonCancellable) {
            db.withTransaction {
                val rowsNow = db.commercialDao().forRecording(recordingId).map { it.toSegment() }
                if (storedSegments(rowsNow) != storedSegments(segments)) return@withTransaction
                val previous = note(context, recordingId)
                val status = if (fromAnalysis) {
                    CommercialMarkers.statusAfterAnalysis(segments)
                } else {
                    CommercialMarkers.statusAfterMarkerEdit(segments, previous?.analyzed == true, previous?.status)
                }
                val useStamp = fromAnalysis || stamp.known()
                putNote(
                    context,
                    recordingId,
                    AnalysisNote(
                        status,
                        if (useStamp) stamp.size else previous?.size ?: 0L,
                        if (useStamp) stamp.modifiedMs else previous?.modifiedMs ?: 0L,
                        previous?.skipOverride,
                        0L,
                        if (fromAnalysis) true else previous?.analyzed == true,
                        System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun storedSegments(segments: List<CommercialMarkers.Segment>): List<CommercialMarkers.Segment> =
        segments.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.review.ordinal }, { it.origin.ordinal }, { it.updatedAtMs }))

    /**
     * Gives stored rows with no file stamp the current file's stamp.
     * Reads and writes in one transaction, so a scan that committed newer rows is not replaced
     * by an older copy of the list.
     */
    suspend fun stampStoredRows(
        context: Context,
        recordingId: Int,
        stamp: CommercialMarkers.FileStamp
    ): List<CommercialMarkers.Segment> {
        if (!stamp.known() || recordingId < 0) return load(context, recordingId)
        val db = database(context)
        val written = db.withTransaction {
            val current = db.commercialDao().forRecording(recordingId).map { it.toSegment() }
            val stamped = CommercialMarkers.stampMissingFiles(current, stamp)
            if (stamped == current) return@withTransaction null
            db.commercialDao().replace(recordingId, stamped.map { it.toEntity(recordingId) })
            stamped
        }
        if (written != null) publishNoteAfterCommit(context, recordingId, written, stamp, fromAnalysis = false)
        return written ?: load(context, recordingId)
    }

    /** Library line from the saved note. A blank note means this recording was not analyzed. */
    fun noteStatus(context: Context, recordingId: Int, recordingDone: Boolean): String {
        if (!recordingDone) return ""
        return when (note(context, recordingId)?.status) {
            ANALYZING -> "Analyzing"
            REVIEW -> "Review Suggested Markers"
            FOUND -> "Commercials Found"
            NONE -> "No Commercials Detected"
            UNSUPPORTED -> "Analysis Unsupported"
            FAILED -> "Analysis Failed"
            else -> "Not Analyzed"
        }
    }

    suspend fun onRecordingDeleted(context: Context, recordingId: Int) {
        database(context).commercialDao().deleteForRecording(recordingId)
        clearNote(context, recordingId)
        AdBreakDetector.forget(context, recordingId)
    }

    fun stamp(context: Context, pathOrUri: String): CommercialMarkers.FileStamp {
        if (pathOrUri.startsWith("content://")) return contentStamp(context, Uri.parse(pathOrUri))
        val path = if (pathOrUri.startsWith("file://")) Uri.parse(pathOrUri).path ?: return CommercialMarkers.FileStamp(0L, 0L)
        else pathOrUri
        val file = File(path)
        if (!file.isFile) return CommercialMarkers.FileStamp(0L, 0L)
        return CommercialMarkers.FileStamp(file.length(), file.lastModified())
    }

    /**
     * Size, and modified time when the provider has that column. A FileProvider URI only has
     * size, so a missing column is unknown rather than a crash. Callers that need a stamp matching
     * analysis should pass the recording's own path, not the playback URI.
     */
    private fun contentStamp(context: Context, uri: Uri): CommercialMarkers.FileStamp = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return CommercialMarkers.FileStamp(0L, 0L)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val size = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
            val modified = if (modifiedIndex < 0 || cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex) * 1000L
            CommercialMarkers.FileStamp(size, modified)
        } ?: CommercialMarkers.FileStamp(0L, 0L)
    } catch (_: Exception) {
        CommercialMarkers.FileStamp(0L, 0L)
    }

    fun storageTight(context: Context): Boolean = try {
        StatFs(context.filesDir.absolutePath).availableBytes < 50L * 1024L * 1024L
    } catch (_: Exception) {
        false
    }

    fun statusLabel(context: Context, recordingId: Int, recordingDone: Boolean, segments: List<CommercialMarkers.Segment>): String {
        val saved = note(context, recordingId)
        return CommercialMarkers.libraryStatus(
            recordingDone = recordingDone,
            analyzed = saved?.status == NONE,
            analyzing = saved?.status == ANALYZING,
            failed = saved?.status == FAILED,
            unsupported = saved?.status == UNSUPPORTED,
            segments = segments
        )
    }

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun analysis(context: Context) =
        context.applicationContext.getSharedPreferences(ANALYSIS, Context.MODE_PRIVATE)

    private fun database(context: Context) = (context.applicationContext as IptvApplication).db
}

private fun CommercialSegmentEntity.toSegment() = CommercialMarkers.Segment(
    startMs = startMs,
    endMs = endMs,
    origin = runCatching { CommercialMarkers.Origin.valueOf(origin) }.getOrDefault(CommercialMarkers.Origin.AUTOMATIC),
    review = runCatching { CommercialMarkers.Review.valueOf(review) }.getOrDefault(CommercialMarkers.Review.SUGGESTED),
    evidence = evidence,
    fileSize = fileSize,
    fileModifiedMs = fileModifiedMs,
    analysisVersion = analysisVersion,
    updatedAtMs = updatedAtMs
)

private fun CommercialMarkers.Segment.toEntity(recordingId: Int) = CommercialSegmentEntity(
    recordingId = recordingId,
    startMs = startMs,
    endMs = endMs,
    origin = origin.name,
    review = review.name,
    evidence = evidence,
    analysisVersion = analysisVersion,
    fileSize = fileSize,
    fileModifiedMs = fileModifiedMs,
    updatedAtMs = updatedAtMs
)
