package com.iptvapp.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Reads a recording's file size for display, whether it's a plain file path or a
 * MediaStore content:// URI. Local metadata lookups only — no network — so this is
 * cheap enough to call synchronously from an adapter bind(). */
object RecordingFileUtils {

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
