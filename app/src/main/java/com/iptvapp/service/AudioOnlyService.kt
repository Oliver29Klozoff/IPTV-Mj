package com.iptvapp.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Audio-only playback of a live channel: no picture, the lowest-bitrate variant where the stream
 * offers a choice, and it keeps going with the screen off or the app in the background. Media3's
 * session service supplies the media notification and lock-screen / Bluetooth controls.
 *
 * Started from the full-screen player while it's still on screen (background starts of a
 * foreground service are blocked on newer Android), and the player releases its own stream
 * first — most plans allow one connection, so the two never play at once. Anything that is about
 * to play video again (the player's SHOW PICTURE, the home screens) calls [stop] first.
 */
@AndroidEntryPoint
class AudioOnlyService : MediaSessionService() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager
    @Inject lateinit var db: com.iptvapp.data.local.IptvDatabase

    // Counts listening toward Settings > Data Usage and the per-provider budget, like the player.
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var tracker: com.iptvapp.ui.player.BandwidthTracker? = null
    private var serverIndex = -1

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    // A sleep timer carried over from the player (see play's sleepAfterMs).
    private var sleepDeadlineMs = 0L
    private val sleepRunnable = Runnable { sleepDeadlineMs = 0L; session?.player?.pause() }
    // Re-prepares only; play/pause stays whatever the listener last chose (an error doesn't
    // clear it), so a pause during the backoff isn't undone.
    // Reopening the item (not just prepare) also restarts a live stream that ended cleanly.
    private val retryRunnable = Runnable {
        session?.player?.let { p -> p.currentMediaItem?.let { item -> p.setMediaItem(item); p.prepare() } }
    }

    override fun onCreate() {
        super.onCreate()
        val dataSource = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        val tracked = com.iptvapp.ui.player.BandwidthTracker(db.bandwidthUsageDao(), serverIndex, scope) {
            com.iptvapp.ui.player.BandwidthBudgetManager(db, prefs).checkAndWarn(applicationContext, serverIndex)
        }.also { it.startPeriodicFlush(); tracker = it }
        // Same audio settings as the video player (PlayerActivity.buildPlayer): the preferred
        // language, and the stereo-PCM sink for boxes that go silent with passthrough.
        val passthroughFallback = kotlinx.coroutines.runBlocking { prefs.audioPassthroughFallbackEnabled.first() }
        val preferredAudioLanguage = kotlinx.coroutines.runBlocking { prefs.preferredAudioLanguage.first() }
        val renderersFactory = if (passthroughFallback) {
            object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
            }
        } else {
            androidx.media3.exoplayer.DefaultRenderersFactory(this)
        }
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(tracked.wrap(dataSource)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setForceLowestBitrate(true)
            .apply { if (preferredAudioLanguage.isNotBlank()) setPreferredAudioLanguage(preferredAudioLanguage) }
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                // A live stream has no end: the provider closed it, so reconnect like after an error.
                if (state == Player.STATE_ENDED) scheduleRetry()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) handler.removeCallbacks(retryRunnable)
            }
            // Live streams drop now and then; reconnect with a growing pause, like the player does.
            override fun onPlayerError(error: PlaybackException) {
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "AUDIO ONLY ERROR: errorCode=${error.errorCodeName} retries=$retries"
                )
                scheduleRetry()
            }
        })
        val builder = MediaSession.Builder(this, player)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            // Brings the existing task (with the player on top) back rather than starting fresh.
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
        instance = this
        session = builder.build().also {
            // Started with startService, so no controller has connected to register the session —
            // without this Media3 never posts the notification or goes foreground, and the system
            // may kill the playback once the app is in the background.
            addSession(it)
        }
        _running.value = true
    }

    private fun scheduleRetry() {
        if (retries >= MAX_RETRIES) return
        retries++
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, (2_000L * retries).coerceAtMost(15_000L))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> shutDown()
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrEmpty()) return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                serverIndex = intent.getIntExtra(EXTRA_SERVER_INDEX, -1)
                tracker?.updateServerIndex(serverIndex)
                sleepDeadlineMs = 0L
                handler.removeCallbacks(sleepRunnable)
                intent.getLongExtra(EXTRA_SLEEP_MS, 0L).takeIf { it > 0L }?.let { 
                    sleepDeadlineMs = System.currentTimeMillis() + it
                    handler.postDelayed(sleepRunnable, it)
                }
                _playingTitle.value = title
                retries = 0
                session?.player?.let {
                    it.setMediaItem(
                        MediaItem.Builder()
                            .setUri(url)
                            .setMediaMetadata(
                                MediaMetadata.Builder().setTitle(title).setArtist("Audio only · MKTV").build()
                            )
                            .build()
                    )
                    it.prepare()
                    it.playWhenReady = true
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away from recents ends it, like closing the player would.
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutDown()
    }

    override fun onDestroy() {
        releaseSession()
        if (instance === this) instance = null
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        _running.value = false
        _playingTitle.value = ""
        super.onDestroy()
    }

    /** Releasing the session drops the notification's own controller connection too, so the
     * service can actually stop — stopService alone leaves it bound and running. */
    private fun shutDown() {
        releaseSession()
        _running.value = false
        stopSelf()
    }

    private fun releaseSession() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(sleepRunnable)
        tracker?.let { t ->
            t.stop()
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch { t.flush() }
        }
        tracker = null
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
    }

    companion object {
        private const val ACTION_PLAY = "com.iptvapp.audioonly.PLAY"
        private const val ACTION_STOP = "com.iptvapp.audioonly.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SLEEP_MS = "sleep_ms"
        private const val EXTRA_SERVER_INDEX = "server_index"

        // The running instance (same process, main thread only) — lets [stop] release the
        // stream synchronously instead of queueing an intent.
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var instance: AudioOnlyService? = null
        private const val MAX_RETRIES = 20

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _playingTitle = MutableStateFlow("")
        val playingTitle: StateFlow<String> = _playingTitle

        /** [sleepAfterMs] > 0 pauses playback after that long — a sleep timer the player had armed. */
        /** Time left on a sleep timer the service is running (0 = none), for handing back to video. */
        fun remainingSleepMs(): Long =
            instance?.sleepDeadlineMs?.let { it - System.currentTimeMillis() }?.takeIf { it > 0L } ?: 0L

        fun play(context: Context, url: String, title: String, serverIndex: Int, sleepAfterMs: Long = 0L) {
            context.startService(
                Intent(context, AudioOnlyService::class.java)
                    .setAction(ACTION_PLAY)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SLEEP_MS, sleepAfterMs)
                    .putExtra(EXTRA_SERVER_INDEX, serverIndex)
            )
        }

        /** No-op unless it's running, so callers can use it freely before starting video. When the
         * service is up, its player is released right here, before this returns, so the video
         * player that follows never overlaps it on a one-connection account. */
        fun stop(context: Context) {
            if (!_running.value) return
            _running.value = false
            instance?.let { it.shutDown(); return }
            try {
                context.startService(Intent(context, AudioOnlyService::class.java).setAction(ACTION_STOP))
            } catch (_: IllegalStateException) {
                // Background start refused — nothing to stop from here; it ends with the task.
            }
        }
    }
}
