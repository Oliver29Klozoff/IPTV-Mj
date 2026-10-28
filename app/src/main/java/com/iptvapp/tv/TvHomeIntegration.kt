package com.iptvapp.tv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.InvalidationTracker
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/** One conflated application-lifetime consumer. No periodic worker or provider network access. */
object TvHomeIntegration {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    fun supported(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
        context.packageManager.resolveContentProvider("android.media.tv", 0) != null
    fun enabled(context: Context): Boolean {
        val home = context.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val defaultEnabled = home != "com.google.android.apps.tv.launcher"
        return supported(context) && context.getSharedPreferences("tv_home_settings", 0).getBoolean("enabled", defaultEnabled)
    }
    fun setEnabled(context: Context, value: Boolean) {
        if (value && supported(context)) TvHomeChannelPublisher.restoreDeletedRows(context)
        context.getSharedPreferences("tv_home_settings", 0).edit().putBoolean("enabled", value).apply()
        request()
    }
    fun request() { requests.trySend(Unit) }

    @Synchronized
    fun start(context: Context, db: IptvDatabase, prefs: PreferencesManager, repository: XtreamRepository) {
        if (started || !supported(context)) return
        started = true
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("channels", "vod_streams", "series", "episode_watched", "recordings") {
            override fun onInvalidated(tables: Set<String>) { request() }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { prefs.credentials.collect { request() } }
        scope.launch {
            for (ignored in requests) {
                delay(1500)
                try { sync(context, db, prefs, repository) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Optional launcher provider; internal home stays available. */ }
                delay(30000)
            }
        }
        request()
    }

    suspend fun sync(context: Context, db: IptvDatabase, prefs: PreferencesManager, repository: XtreamRepository) {
        repository.withLauncherCatalog { credentials ->
            WatchNextPublisher.refresh(context, db, prefs)
            val on = enabled(context)
            val favorites = if (on) LauncherLinks.entriesFor(context, db.channelDao().launcherFavorites(), credentials) else emptyList()
            TvHomeChannelPublisher.publishFavorites(context, favorites)
            val recent = if (on) LauncherLinks.entriesFor(context, db.channelDao().getRecentChannels().first().take(20), credentials) else emptyList()
            TvHomeChannelPublisher.publish(context, "recent", "MKTV Recently Watched", recent)
            val key = LauncherLinkKey.get(context)
            val recordings = if (on) db.recordingDao().launcherRecordings().filter { available(context, it) }.map {
                TvHomeChannelPublisher.Entry(it.id, it.programTitle ?: it.channelName, null,
                    LauncherLink.buildRecording(it.id, LauncherLink.token(key, LauncherLink.recordingIdentity(it.id, it.outputPath, it.scheduledStartMs))))
            } else emptyList()
            TvHomeChannelPublisher.publish(context, "recordings", "MKTV Recent Recordings", recordings, recording = true)
        }
        // Posters not cached yet are fetched here, after the catalog lock; the next sync shows them.
        if (enabled(context) && LauncherArtwork.fetchPending(context)) request()
    }

    fun available(context: Context, recording: RecordingEntity): Boolean {
        if (recording.status != "DONE") return false
        val path = recording.outputPath
        return runCatching {
            if (path.startsWith("content://")) context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(path), "r")?.use { it.length != 0L } == true
            else java.io.File(path).let { it.isFile && it.length() >= 1024 }
        }.getOrDefault(false)
    }
}
