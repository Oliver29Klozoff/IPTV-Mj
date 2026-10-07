package com.iptvapp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.ui.guide.ReminderTapActivity
import com.iptvapp.ui.home.HomeActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.withLock

/**
 * Show alerts: the user saves words or titles ("Yankees", "Chicago P.D.") and gets a notification
 * when a matching show turns up in the guide within the next two days, with Watch and Record.
 *
 * Runs after every guide refresh (EpgRefreshWorker) and right after the keyword list changes.
 * One notification per airing — the same game on five regional feeds is one alert, naming the
 * best channel for it (a favorite first, then the main provider) — and never the same airing twice.
 */
object ShowAlerts {
    private const val CHANNEL_ID = "show_alerts"
    private const val STATE_PREFS = "show_alerts_state"
    private const val KEY_NOTIFIED = "notified"
    private const val WINDOW_SEC = 48 * 3600L
    // A broad keyword ("News") could match dozens of airings; the soonest few are what matter.
    private const val MAX_PER_RUN = 6

    private data class Candidate(
        val serverIndex: Int,
        val streamId: Int,
        val channelName: String,
        val isFavorite: Boolean
,
        // This channel's own end time — feeds can differ by a few minutes for the same airing.
        val stopSec: Long = 0L
    )

    private data class Airing(
        val key: String,
        val title: String,
        val keyword: String,
        val startSec: Long,
        var stopSec: Long,
        val channels: MutableList<Candidate> = mutableListOf()
    )

    // The worker, the Guide screen's refresh and Settings can all scan at once; overlapping scans
    // would each read the sent list before the other wrote it and alert the same airing twice.
    private val scanMutex = kotlinx.coroutines.sync.Mutex()

    // Guide rows hold seconds (XMLTV) or milliseconds (some short-EPG paths); see the guide code.
    private fun toSec(t: Long) = if (t < 100_000_000_000L) t else t / 1000L

    /** Whether an alert posted now would actually show: app notifications allowed and the
     * "Show alerts" channel not turned off. Nothing is marked as sent while this is false, so
     * matches are still delivered once notifications are allowed again. */
    fun canNotify(context: Context): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
            if (nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Returns how many alerts were posted. Never throws. */
    suspend fun scan(context: Context, db: IptvDatabase, prefs: PreferencesManager): Int = try {
        scanMutex.withLock { scanInternal(context.applicationContext, db, prefs) }
    } catch (e: Exception) {
        android.util.Log.w("ShowAlerts", "scan failed: ${e.message}")
        0
    }

    private suspend fun scanInternal(context: Context, db: IptvDatabase, prefs: PreferencesManager): Int {
        val keywords = prefs.getShowAlertKeywords()
        if (keywords.isEmpty()) return 0
        if (!canNotify(context)) return 0
        val nowSec = System.currentTimeMillis() / 1000L
        val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        // "airingKey|stopSec" — pruned once an airing is over, so the set stays small.
        val notified = (state.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet())
            .filter { it.substringAfterLast('|').toLongOrNull()?.let { stop -> stop > nowSec } == true }
            .toMutableSet()
        val notifiedKeys = notified.map { it.substringBeforeLast('|') }.toSet()

        val airings = linkedMapOf<String, Airing>()
        for (keyword in keywords) {
            for (row in db.epgDao().findByTitleContaining(keyword)) {
                val start = toSec(row.startTimestamp)
                val stop = toSec(row.stopTimestamp)
                if (stop <= nowSec || start > nowSec + WINDOW_SEC) continue
                val title = row.title.trim()
                val key = "${title.lowercase()}@$start"
                if (key in notifiedKeys) continue
                val candidate = resolveChannel(db, row.serverIndex, row.streamId)?.copy(stopSec = stop) ?: continue
                val airing = airings.getOrPut(key) { Airing(key, title, keyword, start, stop) }
                if (airing.channels.none { it.serverIndex == candidate.serverIndex && it.streamId == candidate.streamId }) {
                    airing.channels += candidate
                }
                // Remembered as sent until the last of its copies ends.
                if (stop > airing.stopSec) airing.stopSec = stop
            }
        }
        if (airings.isEmpty()) return 0

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val toPost = airings.values.sortedBy { it.startSec }.take(MAX_PER_RUN)
        for (airing in toPost) {
            post(context, nm, airing, nowSec)
            notified += "${airing.key}|${airing.stopSec}"
        }
        state.edit().putStringSet(KEY_NOTIFIED, notified).apply()
        return toPost.size
    }

    /** The channel a guide row belongs to, or null when it's hidden or no longer in the list. */
    private suspend fun resolveChannel(db: IptvDatabase, serverIndex: Int, streamId: Int): Candidate? =
        if (serverIndex == -1) {
            db.channelDao().getChannelById(streamId)
                ?.takeIf { !it.isHidden }
                ?.let { Candidate(-1, it.streamId, it.name, it.isFavorite) }
        } else {
            db.mergedChannelDao().getByIndexAndId(serverIndex, streamId)
                ?.takeIf { !it.isHidden }
                ?.let { Candidate(it.serverIndex, it.streamId, "${it.name} · ${it.serverNickname}", it.isFavorite) }
        }

    private fun post(context: Context, nm: NotificationManager, airing: Airing, nowSec: Long) {
        // Favorite first, then the main provider (whose channels the recorder knows best).
        val best = airing.channels.sortedWith(
            compareByDescending<Candidate> { it.isFavorite }.thenByDescending { it.serverIndex == -1 }
        ).first()
        val others = airing.channels.size - 1
        val whereText = best.channelName + if (others > 0) " (+$others more)" else ""
        val whenText = if (airing.startSec <= nowSec) "On now" else formatStart(airing.startSec * 1000L)
        val requestCode = airing.key.hashCode()

        val watchIntent = Intent(context, ReminderTapActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("mktv://tune/${best.streamId}?server=${best.serverIndex}&t=$requestCode")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(HomeActivity.EXTRA_JUMP_TO_STREAM_ID, best.streamId)
            putExtra(HomeActivity.EXTRA_JUMP_SERVER_INDEX, best.serverIndex)
        }
        val watchPi = PendingIntent.getActivity(
            context, requestCode, watchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tv = context.isLargeScreenDevice()
        val recordClass = if (tv) com.iptvapp.ui.recordings.TvRecordingActivity::class.java
            else com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java
        val recordIntent = Intent(context, recordClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                if (tv) com.iptvapp.ui.recordings.TvRecordingActivity.EXTRA_PREFILL_START_MS
                else com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS,
                airing.startSec * 1000L
            )
            putExtra(
                if (tv) com.iptvapp.ui.recordings.TvRecordingActivity.EXTRA_PREFILL_DURATION_MS
                else com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS,
                ((best.stopSec - airing.startSec) * 1000L).coerceAtLeast(60_000L)
            )
            if (best.serverIndex == -1) {
                putExtra(
                    if (tv) com.iptvapp.ui.recordings.TvRecordingActivity.EXTRA_PREFILL_STREAM_ID
                    else com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID,
                    best.streamId
                )
            } else {
                putExtra(
                    if (tv) com.iptvapp.ui.recordings.TvRecordingActivity.EXTRA_PREFILL_SERVER_INDEX
                    else com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_SERVER_INDEX,
                    best.serverIndex
                )
                putExtra(
                    if (tv) com.iptvapp.ui.recordings.TvRecordingActivity.EXTRA_PREFILL_MERGED_STREAM_ID
                    else com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_MERGED_STREAM_ID,
                    best.streamId
                )
            }
        }
        val recordPi = PendingIntent.getActivity(
            context, requestCode xor 0x5245, recordIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(airing.title)
            .setContentText("$whenText · $whereText")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$whenText · $whereText\nMatches your alert \"${airing.keyword}\"")
            )
            .setContentIntent(watchPi)
            .addAction(0, "Watch", watchPi)
            .addAction(0, "Record", recordPi)
            .setAutoCancel(true)
            // Gone when the show ends — an old alert's Record would otherwise schedule a recording
            // of whatever is on by then.
            .setTimeoutAfter((best.stopSec * 1000L - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(requestCode, notification)
    }

    /** "Today 7:05 PM", "Tomorrow 8:00 PM", or "Sat 9:30 PM". */
    private fun formatStart(ms: Long): String {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.timeInMillis = ms
        val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        val label = when (day) {
            today -> "Today"
            tomorrow -> "Tomorrow"
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        }
        return "$label $time"
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Show alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shows in the guide that match your alert words"
                }
            )
        }
    }
}
