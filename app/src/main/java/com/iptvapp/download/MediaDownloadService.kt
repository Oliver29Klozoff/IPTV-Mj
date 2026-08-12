package com.iptvapp.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * Standard Media3 DownloadService subclass — required foreground service so a movie/episode
 * download keeps running (and shows a real progress notification) even when the app is
 * backgrounded. Registered in AndroidManifest.xml with foregroundServiceType="dataSync".
 * Actual progress/completion is observed by DownloadProgressListener (registered once at app
 * startup in IptvApplication) rather than here, so Room stays in sync regardless of whether
 * this service process is even alive at the time (DownloadManager itself is the source of truth).
 */
@UnstableApi
class MediaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadUtil.CHANNEL_ID,
    // A real string resource is mandatory when channelId is non-null — Media3 unconditionally
    // calls context.getString() on it, so passing 0 here crashed the service
    // (Resources$NotFoundException) the instant the first download started (caught on-device).
    // The description id below may stay 0 (only resolved when non-zero).
    com.iptvapp.R.string.download_notification_channel_name,
    0
) {
    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 9100
    }

    override fun getDownloadManager(): DownloadManager = DownloadUtil.getDownloadManager(this)

    // Downloads are WiFi-agnostic at the OS-scheduler level on purpose — this app's own
    // "download over WiFi" expectation is a user setting/UX convention, not something we
    // enforce via a JobScheduler network constraint, so a plain null scheduler is fine (Media3
    // just won't auto-retry queued downloads via JobScheduler; DownloadManager still runs them
    // immediately while the app/service is alive).
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        return DownloadUtil.getDownloadNotificationHelper(this)
            .buildProgressNotification(
                this,
                android.R.drawable.stat_sys_download,
                null,
                null,
                downloads,
                notMetRequirements
            )
    }
}
