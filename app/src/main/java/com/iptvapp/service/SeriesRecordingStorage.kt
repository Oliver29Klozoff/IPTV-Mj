package com.iptvapp.service

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.room.withTransaction
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.RecordingEntity
import kotlinx.coroutines.flow.first
import java.io.File

object SeriesRecordingStorage {
    suspend fun clean(context: Context, db: IptvDatabase) {
        val episodes = db.seriesRecordingDao().episodes()
        val records = db.recordingDao().getAll().first().associateBy { it.id }
        for (rule in db.seriesRecordingDao().rules().filter { !it.cancelled }) {
            val own = episodes.filter { it.ruleId == rule.id }.mapNotNull { records[it.recordingId] }
            for (rec in SeriesRecordingPolicy.retention(own, rule.keepCount)) deleteEligible(context, db, rec.id)
        }
    }

    /** Recheck protection and status in the transaction used by protection updates. */
    suspend fun deleteEligible(context: Context, db: IptvDatabase, id: Int): Boolean = db.withTransaction {
        val rec = db.recordingDao().getById(id) ?: return@withTransaction false
        if (rec.status != "DONE" || rec.isProtected) return@withTransaction false
        val favorite = if (rec.serverIndex == -1) db.channelDao().getChannelById(rec.streamId)?.isFavorite
            else db.mergedChannelDao().getByIndexAndId(rec.serverIndex, rec.streamId)?.isFavorite
        if (favorite == true || !owns(context, rec.outputPath)) return@withTransaction false
        val deleted = try {
            if (rec.outputPath.startsWith("content://")) context.contentResolver.delete(Uri.parse(rec.outputPath), null, null) > 0
            else File(rec.outputPath).delete()
        } catch (_: Exception) { false }
        if (deleted) db.recordingDao().delete(rec)
        deleted
    }.also { if (it) com.iptvapp.util.CommercialStore.onRecordingDeleted(context, id) }

    private fun owns(context: Context, path: String): Boolean = runCatching {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            if (uri.authority != "media" || android.os.Build.VERSION.SDK_INT < 29) return false
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)
                ?.use { it.moveToFirst() && it.getString(0) == context.packageName } == true
        } else {
            val file = File(path).canonicalFile
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).canonicalFile
            file.isFile && file.path.startsWith(movies.path + File.separator) && file.extension.lowercase() in setOf("ts", "mp4", "mkv")
        }
    }.getOrDefault(false)
}
