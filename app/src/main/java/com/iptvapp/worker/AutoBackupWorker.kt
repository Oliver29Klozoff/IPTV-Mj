package com.iptvapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val creds = prefs.credentials.first()
            val json = JSONObject().apply {
                put("serverUrl", creds.serverUrl)
                put("username", creds.username)
                put("password", creds.password)
                put("epgUrl", prefs.epgUrl.first())
                put("preferredFormat", prefs.preferredFormat.first())
                put("preferredAudioLanguage", prefs.preferredAudioLanguage.first())
                put("preferredSubtitleLanguage", prefs.preferredSubtitleLanguage.first())
                put("epgAutoRefreshHours", prefs.epgAutoRefreshHours.first())
                put("epgRefreshMissingOnly", prefs.epgRefreshMissingOnly.first())
                put("usaOnlyChannels", prefs.usaOnlyChannels.first())
                put("showMovies", prefs.showMovies.first())
                put("showSeries", prefs.showSeries.first())
                put("showWatching", prefs.showWatching.first())
                // Same display/playback/misc toggles buildBackupJson (SettingsActivity.kt) now
                // includes — previously missing here too.
                put("accentColor", prefs.accentColor.first())
                put("accentColorEnd", prefs.accentColorEnd.first())
                put("amoledBlack", prefs.amoledBlack.first())
                put("externalPlayer", prefs.externalPlayer.first())
                put("tunneledPlaybackEnabled", prefs.tunneledPlaybackEnabled.first())
                put("dv7FallbackEnabled", prefs.dv7FallbackEnabled.first())
                put("audioPassthroughFallbackEnabled", prefs.audioPassthroughFallbackEnabled.first())
                put("autoplayNextEpisodeEnabled", prefs.autoplayNextEpisodeEnabled.first())
                put("extraBufferingEnabled", prefs.extraBufferingEnabled.first())
                put("silentSelfUpdateEnabled", prefs.silentSelfUpdateEnabled.first())
                put("crashReportingEnabled", prefs.crashReportingEnabled.first())
                put("recordingFolderName", prefs.recordingFolderName.first())
                put("autoDeleteRecordingsDays", prefs.autoDeleteRecordingsDays.first())
                put("favoriteCategoryIds", JSONArray(prefs.favoriteLiveCategoryIds.first().toList()))
                put("favoriteChannelIds", JSONArray(db.channelDao().getFavoriteChannelIds()))
                put("watchHistory", JSONArray(db.channelDao().getWatchHistoryForBackup().map {
                    JSONObject().apply {
                        put("streamId", it.streamId)
                        put("lastWatched", it.lastWatched)
                        put("viewCount", it.viewCount)
                    }
                }))
                // Folder ids are local autoincrement values (not portable across a restore
                // onto a different/reset device), so folders are saved by NAME — same
                // approach SyncManager already uses. Previously omitted entirely, so
                // restoring a backup brought favorites back but dumped every one of them
                // into Unsorted, silently losing folder organization.
                val folders = db.favoriteFolderDao().getAll().first()
                val folderNameById = folders.associate { it.id to it.name }
                val channelFolders = db.channelDao().getFavoriteChannelsBlocking()
                    .mapNotNull { ch -> ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> ch.streamId.toString() to name } } }
                    .toMap()
                put("favoriteFolders", JSONArray(folders.map { it.name }))
                put("channelFolders", JSONObject(channelFolders))

                // Extra providers (the "Providers" merged-browse feature) were never included
                // here — restoring an auto-backup silently dropped every non-primary provider.
                // Trakt's OAuth tokens are deliberately NOT included: this file is written to
                // app-private storage but is still a plaintext export a user could later share
                // manually; reconnecting Trakt after a restore is a small one-time action,
                // copying a bearer token into an exportable file is not.
                val extraServersList = prefs.getExtraServersWithNick()
                put("extraServers", JSONArray(extraServersList.map { s ->
                    JSONObject().apply {
                        put("url", s[0]); put("user", s[1]); put("pass", s[2])
                        put("nick", s.getOrElse(3) { "" }); put("epg", s.getOrElse(4) { "" })
                    }
                }))

                // Merged/other-provider favorites and pinned/hidden categories — previously
                // missing entirely from auto-backup even though the manual "Backup" button
                // includes them (buildBackupJson in SettingsActivity.kt), so a weekly
                // auto-backup silently dropped every non-primary-provider favorite. Keyed by
                // server URL, not serverIndex, for the same cross-device-identity reason
                // buildBackupJson's version is.
                val mergedUrlByIndex = extraServersList.mapIndexedNotNull { i, s ->
                    s.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { i to it }
                }.toMap()
                val mergedFavorites = db.mergedChannelDao().getAllFavorites().first()
                put("mergedFavorites", JSONArray(mergedFavorites.mapNotNull { ch ->
                    val url = mergedUrlByIndex[ch.serverIndex] ?: return@mapNotNull null
                    JSONObject().apply {
                        put("serverUrl", url)
                        put("streamId", ch.streamId)
                        ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { put("folderName", it) } }
                    }
                }))
                val favoriteMergedCategoryKeys = prefs.favoriteMergedCategoryIds.first()
                put("mergedFavoriteCategories", JSONArray(favoriteMergedCategoryKeys.mapNotNull { key ->
                    val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                    val categoryId = key.substringAfter(':', "")
                    val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                    JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
                }))
                val mergedVodFavorites = db.mergedVodDao().getAllFavorites().first()
                put("mergedVodFavorites", JSONArray(mergedVodFavorites.mapNotNull { v ->
                    val url = mergedUrlByIndex[v.serverIndex] ?: return@mapNotNull null
                    JSONObject().apply {
                        put("serverUrl", url)
                        put("streamId", v.streamId)
                        v.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { put("folderName", it) } }
                    }
                }))
                val mergedSeriesFavorites = db.mergedSeriesDao().getAllFavorites().first()
                put("mergedSeriesFavorites", JSONArray(mergedSeriesFavorites.mapNotNull { s ->
                    val url = mergedUrlByIndex[s.serverIndex] ?: return@mapNotNull null
                    JSONObject().apply {
                        put("serverUrl", url)
                        put("seriesId", s.seriesId)
                        s.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { put("folderName", it) } }
                    }
                }))
                val hiddenVodKeys = prefs.hiddenMergedVodCategoryIds.first()
                put("hiddenMergedVodCategories", JSONArray(hiddenVodKeys.mapNotNull { key ->
                    val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                    val categoryId = key.substringAfter(':', "")
                    val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                    JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
                }))
                val hiddenSeriesKeys = prefs.hiddenMergedSeriesCategoryIds.first()
                put("hiddenMergedSeriesCategories", JSONArray(hiddenSeriesKeys.mapNotNull { key ->
                    val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                    val categoryId = key.substringAfter(':', "")
                    val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                    JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
                }))

                // VOD/series watch progress and per-episode watched state — same fields
                // buildBackupJson already includes; previously missing here entirely, so a
                // weekly auto-backup restore would resume every movie/show from zero.
                put("vodProgress", JSONObject(db.vodDao().getUserData()
                    .filter { it.watchedMs > 0 }
                    .associate { it.streamId.toString() to JSONObject().apply {
                        put("watchedMs", it.watchedMs); put("durationMs", it.durationMs)
                    } }))
                put("seriesProgress", JSONObject(db.seriesDao().getUserData()
                    .filter { it.watchedMs > 0 }
                    .associate { it.seriesId.toString() to JSONObject().apply {
                        put("watchedMs", it.watchedMs); put("durationMs", it.durationMs)
                    } }))
                put("episodesWatched", JSONArray(db.episodeWatchedDao().getAll().map {
                    JSONObject().apply {
                        put("seriesId", it.seriesId); put("season", it.season); put("episode", it.episode)
                        put("watchedAt", it.watchedAt); put("watchedMs", it.watchedMs); put("durationMs", it.durationMs)
                    }
                }))

                val style = prefs.subtitleStyle.first()
                put("subtitleStyle", JSONObject().apply {
                    put("sizeScale", style.sizeScale)
                    put("verticalOffsetDp", style.verticalOffsetDp)
                    put("bold", style.bold)
                    put("textColor", style.textColor)
                    put("backgroundColor", style.backgroundColor)
                    put("outlineEnabled", style.outlineEnabled)
                    put("outlineColor", style.outlineColor)
                })
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "MKTV_backup_$timestamp.json"
            val body = json.toString(2)

            // This file contains the account's plaintext username/password (needed so a
            // restore can log back in) — it must live in app-private storage, never a public
            // Downloads/MediaStore location that any other app with storage/media
            // permissions could read.
            val dir = File(appContext.getExternalFilesDir(null), "backups").apply { mkdirs() }
            File(dir, fileName).writeText(body)
            dir.listFiles { f -> f.name.startsWith("MKTV_backup_") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }

            notifyComplete()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun notifyComplete() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW)
            )
        }
        try {
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MKTV backup saved")
                .setContentText("Your settings and favorites have been backed up.")
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {}
    }

    companion object {
        const val WORK_NAME = "auto_backup_work"
        private const val CHANNEL_ID = "backup_channel"
        private const val NOTIFICATION_ID = 4001
        private const val TAG = "AutoBackupWorker"
    }
}
