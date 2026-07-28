package com.iptvapp.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Reads a recording's file size for display, whether it's a plain file path or a
 * MediaStore content:// URI. Local metadata lookups only — no network — so this is
 * cheap enough to call synchronously from an adapter bind(). Also holds the
 * play/share-a-recording logic — previously duplicated verbatim between
 * RecordingSchedulerActivity (phone) and TvRecordingActivity (TV), the same drift risk
 * that caused several other phone/TV bugs in this project. */
object RecordingFileUtils {

    // A real recorded file always ends in the actual container extension, so endsWith is
    // safe here (unlike guessing content type from a live-stream URL, which can carry a
    // query string after the extension).
    fun mimeTypeFor(path: String): String =
        if (path.endsWith(".mp4", ignoreCase = true)) "video/mp4" else "video/mp2t"

    private fun resolveUri(context: Context, path: String): Uri? {
        if (path.startsWith("content://")) return Uri.parse(path)
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "File not found: $path", Toast.LENGTH_LONG).show()
            return null
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    fun playFile(context: Context, path: String) {
        val uri = resolveUri(context, path) ?: return
        if (!path.startsWith("content://")) {
            val length = File(path).length()
            if (length < 1024) {
                Toast.makeText(context, "Recording incomplete ($length bytes)", Toast.LENGTH_LONG).show()
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open recording with...").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, "No video player installed", Toast.LENGTH_SHORT).show() }
    }

    // In-app playback via this app's own PlayerActivity — same player used for live/VOD, which
    // means a finished recording can now scrobble to Trakt (using the EPG program title captured
    // at record time, see RecordingEntity.programTitle) instead of only ever opening in whatever
    // external video player the user has installed. Replaces playFile() as the default tap
    // action; external "Open with..." is still reachable via Share.
    fun playInApp(context: Context, rec: com.iptvapp.data.local.entities.RecordingEntity) {
        val path = rec.outputPath
        val uri = resolveUri(context, path) ?: return
        if (!path.startsWith("content://")) {
            val length = File(path).length()
            if (length < 1024) {
                Toast.makeText(context, "Recording incomplete ($length bytes)", Toast.LENGTH_LONG).show()
                return
            }
        }
        val intent = Intent(context, com.iptvapp.ui.player.PlayerActivity::class.java).apply {
            putExtra("stream_url", uri.toString())
            putExtra("stream_title", rec.programTitle ?: rec.channelName)
            putExtra("stream_id", rec.id)
            putExtra("is_vod", true)
            putExtra("is_recording", true)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "Couldn't play recording", Toast.LENGTH_SHORT).show() }
    }

    /** Deletes the actual recorded file/MediaStore entry — separate from removing the
     * RecordingEntity DB row, since "remove from this list" and "delete from device storage"
     * are two different user intents (see the delete-confirmation flow in both Activities). */
    fun deleteFile(context: Context, path: String) {
        runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(path), null, null)
            } else {
                File(path).delete()
            }
        }
    }

    fun shareFile(context: Context, path: String) {
        val uri = resolveUri(context, path) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Upload recording to...").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, "No app available to share with", Toast.LENGTH_SHORT).show() }
    }

    // RecordingEntity.durationMs is the originally-SCHEDULED duration (requested length +
    // pre/post-roll), never updated to reflect what was actually captured (reconnects/stalls can
    // make the real file shorter, or a slow read can overshoot slightly) — anything that needs
    // the real length (e.g. "Remove Padding" computing where post-roll actually starts) must
    // probe the file itself, same MediaMetadataRetriever pattern RecordingService.probeVideoHeight
    // already uses for video height.
    fun durationMs(context: Context, path: String): Long? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, resolveUri(context, path) ?: return null)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun sizeLabel(context: Context, path: String): String {
        val bytes = sizeBytes(context, path)
        return if (bytes < 0) "" else formatBytes(bytes)
    }

    private fun sizeBytes(context: Context, path: String): Long = try {
        if (path.startsWith("content://")) {
            context.contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getLong(idx) else -1L
                } ?: -1L
        } else {
            File(path).let { if (it.exists()) it.length() else -1L }
        }
    } catch (_: Exception) {
        -1L
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIdx = 0
        while (value >= 1024 && unitIdx < units.size - 1) {
            value /= 1024
            unitIdx++
        }
        return if (unitIdx == 0) "$bytes B" else "%.1f %s".format(value, units[unitIdx])
    }
}
