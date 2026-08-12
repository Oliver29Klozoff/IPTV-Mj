package com.iptvapp.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.DownloadStatus
import com.iptvapp.data.local.entities.DownloadType
import com.iptvapp.data.local.entities.DownloadedContentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Media3's DownloadManager for this app's two content types (Movies,
 * Series episodes) — builds the DownloadRequest, kicks it off via DownloadService, and keeps
 * the downloaded_content Room table (used purely for UI observation) in sync. Actual
 * progress/completion updates flow through DownloadProgressListener, not this class.
 */
@UnstableApi
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: IptvDatabase
) {
    private val dao get() = db.downloadedContentDao()

    fun observeStatus(streamId: Int): Flow<DownloadedContentEntity?> = dao.observeByStreamId(streamId)

    suspend fun getStatus(streamId: Int): DownloadedContentEntity? = dao.getByStreamId(streamId)

    fun startDownload(
        streamId: Int,
        url: String,
        title: String,
        type: DownloadType,
        seriesId: Int? = null,
        season: Int? = null,
        episode: Int? = null
    ) {
        val downloadId = "content_$streamId"
        val request = DownloadRequest.Builder(downloadId, android.net.Uri.parse(url))
            .setData(title.toByteArray())
            .build()

        // Media3 stores the downloaded bytes inside its own SimpleCache (DownloadUtil's
        // downloads/ directory), keyed by this content's original URL — not as a standalone
        // file we control the path of. localFilePath records that source URL (not a real
        // filesystem path) so PlayerActivity can look this row up by streamId and rebuild a
        // CacheDataSource pointed at the same cache + URL to play back the cached bytes with
        // zero network access. See PlayerActivity's buildPlayer() for the read side.
        kotlinx.coroutines.runBlocking {
            dao.upsert(
                DownloadedContentEntity(
                    streamId = streamId,
                    type = type,
                    seriesId = seriesId,
                    season = season,
                    episode = episode,
                    title = title,
                    localFilePath = url,
                    downloadId = downloadId,
                    status = DownloadStatus.QUEUED,
                    progressPercent = 0
                )
            )
        }

        DownloadService.sendAddDownload(context, MediaDownloadService::class.java, request, false)
    }

    fun cancelOrDeleteDownload(streamId: Int) {
        val downloadId = "content_$streamId"
        DownloadService.sendRemoveDownload(context, MediaDownloadService::class.java, downloadId, false)
        kotlinx.coroutines.runBlocking { dao.deleteByStreamId(streamId) }
    }

    /** The original source URL (used as the cache key) if a COMPLETE download exists for this streamId. */
    suspend fun getPlayableUri(streamId: Int): String? {
        val entity = dao.getByStreamId(streamId) ?: return null
        if (entity.status != DownloadStatus.COMPLETE) return null
        return entity.localFilePath
    }
}
