package com.iptvapp.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.service.RecordingCompressor
import kotlinx.coroutines.flow.first
import java.io.File

// "Remove Padding" — trims the known ~20s pre-roll/post-roll (RecordingSchedulerActivity's
// PRE_ROLL_MS/POST_ROLL_MS) off an already-finished recording, on demand from the recordings
// list rather than automatically after every capture. Mirrors RecordingService's own
// output-path/finalize logic (MediaStore-aware on API 29+, plain file below that) since that
// logic is private to the Service and this needs to run standalone from a UI action, not as
// part of the record-then-compress pipeline.
object RecordingTrimmer {
    private const val PRE_ROLL_MS = 20_000L
    private const val POST_ROLL_MS = 20_000L
    // Below this, the padding would eat the entire recording — not worth attempting.
    private const val MIN_TRIMMABLE_MS = PRE_ROLL_MS + POST_ROLL_MS + 5_000L

    suspend fun removePadding(context: Context, db: IptvDatabase, rec: RecordingEntity): Boolean {
        val actualDurationMs = RecordingFileUtils.durationMs(context, rec.outputPath) ?: return false
        if (actualDurationMs < MIN_TRIMMABLE_MS) return false

        val trimStartMs = PRE_ROLL_MS
        val trimEndMs = actualDurationMs - POST_ROLL_MS
        val trimmedDurationMs = trimEndMs - trimStartMs

        val sourceUri = if (rec.outputPath.startsWith("content://")) {
            Uri.parse(rec.outputPath)
        } else {
            Uri.fromFile(File(rec.outputPath))
        }

        val tempFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        val success = try {
            val height = probeVideoHeight(context, sourceUri)
            RecordingCompressor.compress(context, sourceUri, tempFile.absolutePath, height, trimStartMs, trimEndMs)
        } catch (_: Exception) {
            false
        }
        if (!success || tempFile.length() < 1024) {
            runCatching { tempFile.delete() }
            return false
        }

        val finalTarget = createTrimmedOutputTarget(context, rec.channelName)
        try {
            openOutput(context, finalTarget).use { out -> tempFile.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {
            finalizeTarget(context, finalTarget, false)
            runCatching { tempFile.delete() }
            return false
        }
        finalizeTarget(context, finalTarget, true)
        runCatching { tempFile.delete() }

        // Old file (whichever path it was — raw .ts or the earlier _compressed.mp4) is replaced
        // entirely by the trimmed one, same "delete the superseded file" behavior
        // tryCompressRecording already uses when it replaces a raw capture with its compressed
        // version.
        RecordingFileUtils.deleteFile(context, rec.outputPath)
        db.recordingDao().updatePathDurationAndStatus(rec.id, finalTarget, trimmedDurationMs, "DONE")
        // The trim moved every moment 20 s earlier. Suggestions for the old file are dropped.
        // Confirmed and rejected markers shift with the cut and keep the user's decision.
        val stamp = CommercialStore.stamp(context, finalTarget)
        val now = System.currentTimeMillis()
        val shifted = CommercialStore.load(context, rec.id).mapNotNull { seg ->
            if (seg.origin == CommercialMarkers.Origin.AUTOMATIC && seg.review == CommercialMarkers.Review.SUGGESTED) {
                null
            } else {
                val span = CommercialMarkers.checked(
                    (seg.startMs - PRE_ROLL_MS).coerceAtLeast(0L),
                    seg.endMs - PRE_ROLL_MS,
                    trimmedDurationMs
                )
                if (span == null) null
                else seg.copy(
                    startMs = span.first,
                    endMs = span.second,
                    fileSize = stamp.size,
                    fileModifiedMs = stamp.modifiedMs,
                    updatedAtMs = now
                )
            }
        }
        CommercialStore.save(context, rec.id, shifted)
        // The new file has not been analyzed. A matching size here would make the worker skip it.
        val previousNote = CommercialStore.note(context, rec.id)
        val pending = if (shifted.any { it.review == CommercialMarkers.Review.CONFIRMED }) {
            CommercialStore.FOUND
        } else {
            ""
        }
        CommercialStore.putNote(
            context,
            rec.id,
            CommercialStore.AnalysisNote(pending, 0L, 0L, previousNote?.skipOverride, 0L, false, System.currentTimeMillis())
        )
        // Not an Analyze request. On-demand stays idle. Automatic mode waits until the device is charging.
        com.iptvapp.worker.AdBreakWorker.enqueue(
            context, rec.id, com.iptvapp.worker.AdBreakWorker.uriForPath(finalTarget),
            replace = true, force = false
        )
        return true
    }

    private fun probeVideoHeight(context: Context, uri: Uri): Int {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
        } catch (_: Exception) {
            1080
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun createTrimmedOutputTarget(context: Context, channelName: String): String {
        val safeName = channelName.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
        val fileName = "${safeName}_${System.currentTimeMillis()}_trimmed.mp4"
        val prefs = com.iptvapp.data.local.PreferencesManager(context)
        val folderName = prefs.recordingFolderName.first()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$folderName")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) return uri.toString()
        }

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folderName)
        dir.mkdirs()
        return File(dir, fileName).absolutePath
    }

    private fun openOutput(context: Context, target: String) =
        if (target.startsWith("content://")) {
            context.contentResolver.openOutputStream(Uri.parse(target), "w")
                ?: throw java.io.IOException("Unable to open recording output")
        } else {
            File(target).also { it.parentFile?.mkdirs() }.outputStream()
        }

    private fun finalizeTarget(context: Context, target: String, success: Boolean) {
        if (!target.startsWith("content://")) {
            if (!success) runCatching { File(target).delete() }
            return
        }
        val uri = Uri.parse(target)
        if (success) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
}
