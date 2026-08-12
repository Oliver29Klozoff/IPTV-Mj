package com.iptvapp.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mirrors Media3's own DownloadManager state into the downloaded_content Room table so the
 * detail-page download icon (a plain Room Flow observer) reflects live progress/completion
 * without every screen needing its own DownloadManager.Listener. Registered once at app startup
 * (see IptvApplication.onCreate) — same "one process-lifetime listener" convention as this app's
 * other app-wide singletons.
 */
@UnstableApi
class DownloadProgressListener(
    private val db: IptvDatabase,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : DownloadManager.Listener {

    fun register(context: Context) {
        val downloadManager = DownloadUtil.getDownloadManager(context)
        downloadManager.addListener(this)
        // onDownloadChanged only fires on STATE transitions — Media3 has no periodic progress
        // callback at all (that's why DownloadService's own notification takes a poll interval).
        // Without this loop the button sat at "Downloading 0%" for the entire download (caught
        // on-device: 419MB written, label never moved). currentDownloads must be read on the
        // main thread (DownloadManager is main-looper-bound); the Room writes are suspend calls
        // that hop off it internally.
        scope.launch(Dispatchers.Main) {
            while (true) {
                val active = downloadManager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                for (d in active) {
                    val streamId = d.request.id.removePrefix("content_").toIntOrNull() ?: continue
                    val percent = d.percentDownloaded.let { if (it.isNaN() || it < 0) 0 else it.toInt() }
                    db.downloadedContentDao().updateProgressAndBytes(
                        streamId, DownloadStatus.DOWNLOADING, percent, d.bytesDownloaded
                    )
                }
                kotlinx.coroutines.delay(if (active.isEmpty()) 5000 else 1500)
            }
        }
    }

    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
        val streamId = download.request.id.removePrefix("content_").toIntOrNull() ?: return
        val percent = download.percentDownloaded.let { if (it.isNaN()) 0 else it.toInt() }
        scope.launch {
            when (download.state) {
                Download.STATE_COMPLETED -> db.downloadedContentDao().markComplete(
                    streamId, DownloadStatus.COMPLETE, 100, download.bytesDownloaded, System.currentTimeMillis()
                )
                Download.STATE_FAILED -> db.downloadedContentDao().setStatus(streamId, DownloadStatus.FAILED)
                Download.STATE_DOWNLOADING -> db.downloadedContentDao().updateProgress(streamId, DownloadStatus.DOWNLOADING, percent)
                Download.STATE_QUEUED, Download.STATE_RESTARTING -> db.downloadedContentDao().updateProgress(streamId, DownloadStatus.QUEUED, percent)
                else -> {}
            }
        }
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
        val streamId = download.request.id.removePrefix("content_").toIntOrNull() ?: return
        scope.launch { db.downloadedContentDao().deleteByStreamId(streamId) }
    }
}
