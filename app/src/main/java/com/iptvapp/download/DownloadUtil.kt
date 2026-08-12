package com.iptvapp.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import java.io.File
import java.util.concurrent.Executors

/**
 * Single process-wide home for Media3's offline-download plumbing (Cache, DownloadManager,
 * DownloadNotificationHelper) — mirrors the standard Media3 DemoUtil pattern. Everything here
 * is lazily created on first access and lives for the process lifetime, same as this app's other
 * app-wide singletons (see IptvApplication).
 */
@UnstableApi
object DownloadUtil {
    const val CHANNEL_ID = "mktv_downloads"
    private const val DOWNLOAD_CONTENT_DIRECTORY = "downloads"

    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var downloadCache: Cache? = null
    @Volatile private var notificationHelper: DownloadNotificationHelper? = null
    @Volatile private var downloadDirectory: File? = null

    // Single-thread executor for DownloadManager's own bookkeeping — matches Media3's demo
    // recommendation (avoid the default Runnable::run "run on caller thread" behavior).
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun getDownloadDirectory(context: Context): File {
        return downloadDirectory ?: (context.getExternalFilesDir(null) ?: context.filesDir).also {
            downloadDirectory = it
        }
    }

    @Synchronized
    fun getDownloadCache(context: Context): Cache {
        return downloadCache ?: run {
            val dir = File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            // NoOpCacheEvictor — downloads are user-requested and explicit, they must never be
            // silently evicted like a regular LRU playback cache would.
            SimpleCache(dir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context)).also {
                downloadCache = it
            }
        }
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: run {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            DownloadManager(
                context,
                StandaloneDatabaseProvider(context),
                getDownloadCache(context),
                httpDataSourceFactory,
                downloadExecutor
            ).also {
                it.maxParallelDownloads = 2
                downloadManager = it
            }
        }
    }

    @Synchronized
    fun getDownloadNotificationHelper(context: Context): DownloadNotificationHelper {
        return notificationHelper ?: DownloadNotificationHelper(context, CHANNEL_ID).also {
            notificationHelper = it
        }
    }
}
