package com.iptvapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.ui.series.SeriesDetailActivity
import com.iptvapp.util.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

// Runs daily (see SettingsActivity/TvSettingsActivity's scheduleNewEpisodeCheck, same
// PeriodicWorkRequest shape as AutoBackupWorker/SyncWorker) — for every favorited series,
// compares the provider's current total episode count against the last-seen count stored in
// PreferencesManager (per-seriesId, see getNewEpisodeLastSeenCounts' kdoc). A growth fires a
// real notification; the count is always updated to the current total afterward regardless (so
// a series that loses then regains episodes, or one checked for the first time, doesn't
// re-notify or under/over-count next run).
@HiltWorker
class NewEpisodeCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: IptvDatabase,
    private val repository: XtreamRepository,
    private val prefs: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Belt-and-suspenders — the toggle also gates scheduling/cancellation at the call site,
        // but a run already queued before the user just disabled it would otherwise still fire.
        if (!prefs.newEpisodeNotificationsEnabled.first()) return Result.success()
        return try {
            val favorites = db.seriesDao().getFavoriteSeries().first()
            if (favorites.isEmpty()) return Result.success()
            val lastSeenCounts = prefs.getNewEpisodeLastSeenCounts().toMutableMap()
            var notifiedCount = 0
            for (series in favorites) {
                val info = repository.fetchSeriesInfo(series.seriesId)
                if (info !is Resource.Success) continue
                val currentCount = info.data.episodes?.values?.sumOf { it.size } ?: continue
                val lastSeen = lastSeenCounts[series.seriesId]
                // First-ever check for a series just establishes the baseline — nothing to
                // compare against yet, so no notification (would otherwise fire once for every
                // existing episode of every favorite the very first time this worker runs).
                if (lastSeen != null && currentCount > lastSeen) {
                    notifyNewEpisode(series.seriesId, series.name, series.cover)
                    notifiedCount++
                }
                lastSeenCounts[series.seriesId] = currentCount
                prefs.setNewEpisodeLastSeenCount(series.seriesId, currentCount)
            }
            Log.i(TAG, "New-episode check complete: $notifiedCount series had new episodes")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "New-episode check failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun notifyNewEpisode(seriesId: Int, seriesName: String, seriesCover: String?) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New Episodes", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Alerts when a favorited show gets a new episode" }
            )
        }
        val intent = Intent(appContext, SeriesDetailActivity::class.java).apply {
            putExtra("series_id", seriesId)
            putExtra("series_name", seriesName)
            putExtra("series_cover", seriesCover)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, seriesId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            nm.notify(NOTIFICATION_BASE_ID + seriesId, NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("New episode: $seriesName")
                .setContentText("A new episode is available.")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build())
        } catch (_: SecurityException) {}
    }

    companion object {
        const val WORK_NAME = "new_episode_check_work"
        private const val CHANNEL_ID = "new_episode_channel"
        private const val NOTIFICATION_BASE_ID = 5000
        private const val TAG = "NewEpisodeCheckWorker"
    }
}
