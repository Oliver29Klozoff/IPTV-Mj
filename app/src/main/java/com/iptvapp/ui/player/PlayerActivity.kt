package com.iptvapp.ui.player

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.util.Log
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.iptvapp.data.local.entities.ChannelEntity
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptvapp.ui.home.ChannelAdapter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    companion object {
        // Shared on-disk cache backing the live-TV timeshift/DVR buffer. A single SimpleCache
        // instance must be reused for a given directory for the process lifetime — ExoPlayer
        // throws if two instances open the same cache dir at once.
        private const val TIMESHIFT_CACHE_MAX_BYTES = 1024L * 1024L * 1024L // 1GB rolling window
        private var timeshiftCache: SimpleCache? = null

        @Synchronized
        fun getTimeshiftCache(context: Context): SimpleCache {
            return timeshiftCache ?: SimpleCache(
                java.io.File(context.cacheDir, "timeshift"),
                LeastRecentlyUsedCacheEvictor(TIMESHIFT_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { timeshiftCache = it }
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private lateinit var guideAdapter: ChannelAdapter

    private var isOverlayVisible = false
    private var isHealthBadgeActive = false

    private val hideRunnable = Runnable {
        isOverlayVisible = false
        binding.epgOverlay.visibility = View.GONE
        binding.btnBack.visibility = View.GONE
        binding.btnGuide.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.btnDvrRewind.visibility = View.GONE
        binding.btnDvrLive.visibility = View.GONE
        binding.btnCast.visibility = View.GONE
        binding.bufferHealthBadge.visibility = View.GONE
    }

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { binding.channelOsd.visibility = View.GONE }

    private val indicatorHandler = Handler(Looper.getMainLooper())
    private val hideBrightnessRunnable = Runnable { binding.brightnessIndicator.visibility = View.GONE }
    private val hideVolumeRunnable = Runnable { binding.volumeIndicator.visibility = View.GONE }

    private lateinit var gestureDetector: GestureDetector
    private var streamUrl: String = ""
    private var streamTitle: String = ""
    private var streamId: Int = -1
    private var isVod: Boolean = false
    private var resumePositionMs: Long = 0L

    // Trakt scrobbling (VOD only — live channels have no stable Trakt-identifiable content)
    @Inject lateinit var traktManager: com.iptvapp.trakt.TraktManager
    private var traktSeriesName: String = ""
    private var traktSeason: Int = -1
    private var traktEpisode: Int = -1
    private var traktScrobbleStarted = false
    private val traktIoScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    // Episode playlist for auto-play next
    private var epIds: List<String> = emptyList()
    private var epTitles: List<String> = emptyList()
    private var epExts: List<String> = emptyList()
    private var epIndex: Int = -1
    private var upNextJob: kotlinx.coroutines.Job? = null

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeModeLabels = listOf("Best Fit", "Zoom", "Stretch")
    private var resizeModeIndex = 0

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var channels: List<ChannelEntity> = emptyList()
    private var currentIndex: Int = -1

    private var retryCount = 0
    private var lastBackPressMs = 0L
    private val maxRetries = 5
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null

    private var sleepTimer: CountDownTimer? = null
    private var isAdjustingGesture = false
    private var gestureAccumY = 0f
    private val seekHandler = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable? = null

    private var statsVisible = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStats()
            statsHandler.postDelayed(this, 1000)
        }
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            updateHealthBadge()
            healthHandler.postDelayed(this, 2000)
        }
    }

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var castAvailable = false
    private var castProxy: com.iptvapp.cast.IptvCastProxy? = null
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) { castSession = session; stopLocalAndCast(session) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { castSession = session; stopLocalAndCast(session) }
        override fun onSessionEnded(session: CastSession, error: Int) {
            castSession = null
            castProxy?.stop()
            castProxy = null
            loadStream(streamUrl)
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, id: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setupFavoritesGuide()
        setupResizeButton()
        setupActionButtons()
        setupCast()

        streamUrl = intent.getStringExtra("stream_url") ?: ""
        streamTitle = intent.getStringExtra("stream_title") ?: ""
        streamId = intent.getIntExtra("stream_id", -1)
        isVod = intent.getBooleanExtra("is_vod", false)
        resumePositionMs = intent.getLongExtra("resume_ms", 0L)
        epIds    = intent.getStringArrayListExtra("ep_ids")    ?: emptyList()
        epTitles = intent.getStringArrayListExtra("ep_titles") ?: emptyList()
        epExts   = intent.getStringArrayListExtra("ep_exts")   ?: emptyList()
        epIndex  = intent.getIntExtra("ep_index", -1)
        traktSeriesName = intent.getStringExtra("series_name") ?: ""
        traktSeason  = intent.getIntExtra("season_num", -1)
        traktEpisode = intent.getIntExtra("episode_num", -1)

        setupChannelZones()
        setupGestureDetector()
        binding.tvChannelTitle.text = streamTitle
        binding.btnBack.setOnClickListener { finish() }

        val streamIds = intent.getIntArrayExtra("stream_ids")
        lifecycleScope.launch {
            channels = if (streamIds != null && streamIds.isNotEmpty()) {
                val all = repository.getAllChannels().first()
                val idSet = streamIds.toSet()
                val idOrder = streamIds.withIndex().associate { it.value to it.index }
                all.filter { it.streamId in idSet }.sortedBy { idOrder[it.streamId] }
            } else {
                repository.getAllChannels().first()
            }
            currentIndex = channels.indexOfFirst { it.streamId == streamId }
        }

        if (isVod && resumePositionMs > 0L) showResumeDialog()
    }

    private fun showUpNextIfAvailable() {
        val nextIndex = epIndex + 1
        if (epIds.isEmpty() || nextIndex >= epIds.size) return
        val nextTitle = epTitles.getOrElse(nextIndex) { "Next Episode" }
        binding.tvUpNextTitle.text = nextTitle
        binding.upNextCard.visibility = View.VISIBLE

        val totalMs = 10_000L
        upNextJob = lifecycleScope.launch {
            val start = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val remaining = (totalMs - elapsed).coerceAtLeast(0L)
                binding.upNextProgress.progress = ((remaining.toFloat() / totalMs) * 100).toInt()
                if (remaining == 0L) { playNextEpisode(nextIndex); break }
                kotlinx.coroutines.delay(100)
            }
        }

        binding.btnUpNextPlay.setOnClickListener {
            upNextJob?.cancel()
            playNextEpisode(nextIndex)
        }
        binding.btnUpNextCancel.setOnClickListener {
            upNextJob?.cancel()
            binding.upNextCard.visibility = View.GONE
        }
    }

    private fun playNextEpisode(nextIndex: Int) {
        binding.upNextCard.visibility = View.GONE
        lifecycleScope.launch {
            val url = repository.getSeriesEpisodeUrl(epIds[nextIndex], epExts[nextIndex])
            val intent = Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", epTitles[nextIndex])
                putExtra("stream_id", epIds[nextIndex].hashCode())
                putExtra("is_vod", true)
                putExtra("ep_index", nextIndex)
                putStringArrayListExtra("ep_ids",    ArrayList(epIds))
                putStringArrayListExtra("ep_titles", ArrayList(epTitles))
                putStringArrayListExtra("ep_exts",   ArrayList(epExts))
                putExtra("series_name", traktSeriesName)
                traktManager.parseSeasonEpisode(epTitles[nextIndex])?.let { (s, e) ->
                    putExtra("season_num", s)
                    putExtra("episode_num", e)
                }
            }
            finish()
            startActivity(intent)
        }
    }

    private fun showResumeDialog() {
        val minutes = resumePositionMs / 1000 / 60
        val seconds = (resumePositionMs / 1000) % 60
        AlertDialog.Builder(this)
            .setTitle("Resume Playback")
            .setMessage("Resume from ${minutes}:${seconds.toString().padStart(2, '0')}?")
            .setPositiveButton("Resume") { _, _ -> }
            .setNegativeButton("Start Over") { _, _ -> resumePositionMs = 0L }
            .setCancelable(false)
            .show()
    }

    private fun setupActionButtons() {
        binding.btnSleep.setOnClickListener { showSleepTimerDialog() }
        binding.btnTracks.setOnClickListener { showTrackSelectorDialog() }
        binding.btnStats.setOnClickListener {
            statsVisible = !statsVisible
            if (statsVisible) {
                binding.tvStats.visibility = View.VISIBLE
                updateStats()
                statsHandler.postDelayed(statsRunnable, 1000)
            } else {
                binding.tvStats.visibility = View.GONE
                statsHandler.removeCallbacks(statsRunnable)
            }
            resetHideTimer()
        }
    }

    private fun updateStats() {
        val p = player ?: return
        val vf = p.videoFormat
        val af = p.audioFormat
        val res = if (vf != null) "${vf.width}×${vf.height}" else "—"
        val fps = if (vf?.frameRate != null && vf.frameRate > 0) "${"%.1f".format(vf.frameRate)} fps" else ""
        val vCodec = vf?.sampleMimeType?.removePrefix("video/")?.uppercase() ?: "—"
        val aCodec = af?.sampleMimeType?.removePrefix("audio/")?.uppercase() ?: "—"
        val bitrate = when {
            vf != null && vf.bitrate > 0 -> "${"%.1f".format(vf.bitrate / 1_000_000f)} Mbps"
            else -> "—"
        }
        val bufMs = p.totalBufferedDuration
        val bufSec = bufMs / 1000
        val bufPct = p.bufferedPercentage
        binding.tvStats.text = buildString {
            appendLine("RES   $res  $fps")
            appendLine("VIDEO $vCodec")
            appendLine("AUDIO $aCodec")
            appendLine("BIT   $bitrate")
            append("BUF   ${bufSec}s  ($bufPct%)")
        }
    }

    private fun updateHealthBadge() {
        val p = player ?: return
        if (!isVod && binding.btnDvrLive.visibility == View.VISIBLE) updateDvrLiveButton()
        val bufPct = p.bufferedPercentage
        val vf = p.videoFormat
        val bitrate = if (vf != null && vf.bitrate > 0)
            "${"%.1f".format(vf.bitrate / 1_000f)}k" else ""
        val dotColor = when {
            p.playbackState == Player.STATE_BUFFERING -> android.graphics.Color.parseColor("#FF8800")
            bufPct >= 50 -> android.graphics.Color.parseColor("#00CC66")
            bufPct >= 20 -> android.graphics.Color.parseColor("#FFCC00")
            else -> android.graphics.Color.parseColor("#FF4444")
        }
        (binding.viewHealthDotPlayer.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(dotColor)
        binding.tvHealthBadge.text = buildString {
            append("$bufPct%")
            if (bitrate.isNotEmpty()) append("  $bitrate")
        }
    }

    private fun startHealthBadge() {
        isHealthBadgeActive = true
        if (isOverlayVisible) binding.bufferHealthBadge.visibility = View.VISIBLE
        updateHealthBadge()
        healthHandler.postDelayed(healthRunnable, 2000)
    }

    private fun stopHealthBadge() {
        isHealthBadgeActive = false
        healthHandler.removeCallbacks(healthRunnable)
        binding.bufferHealthBadge.visibility = View.GONE
    }

    private fun showSleepTimerDialog() {
        val labels = arrayOf("Off", "15 min", "30 min", "60 min", "90 min", "120 min")
        val mins = intArrayOf(0, 15, 30, 60, 90, 120)
        AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setItems(labels) { _, which ->
                sleepTimer?.cancel()
                val chosen = mins[which]
                if (chosen == 0) {
                    binding.btnSleep.text = "⏱"
                    binding.btnSleep.setTextColor(getColor(android.R.color.darker_gray))
                } else {
                    binding.btnSleep.setTextColor(0xFF00AAFF.toInt())
                    sleepTimer = object : CountDownTimer(chosen * 60_000L, 60_000L) {
                        override fun onTick(ms: Long) {
                            binding.btnSleep.text = "⏱${ms / 60_000}m"
                        }
                        override fun onFinish() {
                            player?.pause()
                            binding.btnSleep.text = "⏱"
                            binding.btnSleep.setTextColor(getColor(android.R.color.darker_gray))
                        }
                    }.start()
                    binding.btnSleep.text = "⏱${chosen}m"
                }
                resetHideTimer()
            }
            .show()
    }

    private fun showTrackSelectorDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        if (audioGroups.isNotEmpty()) {
            labels.add("── Audio ──")
            actions.add {}
            for (group in audioGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: fmt.label ?: "Track ${labels.size}"
                    val selected = group.isTrackSelected(i)
                    labels.add(if (selected) "✓  $lang" else "    $lang")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }

        if (textGroups.isNotEmpty()) {
            labels.add("── Subtitles ──")
            actions.add {}
            labels.add("    Off")
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            for (group in textGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: fmt.label ?: "Sub ${labels.size}"
                    val selected = group.isTrackSelected(i)
                    labels.add(if (selected) "✓  $lang" else "    $lang")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Tracks").setMessage("No selectable tracks available.").setPositiveButton("OK", null).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Audio & Subtitles")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun setupChannelZones() {
        binding.zonePrevious.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (isVod) {
                val pos = (player?.currentPosition ?: 0L) - 10000L
                player?.seekTo(pos.coerceAtLeast(0L))
                updateSeekBar()
                showOverlay()
            } else {
                previousChannel()
                showOverlay()
            }
        }
        binding.zoneNext.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (isVod) {
                val pos = (player?.currentPosition ?: 0L) + 10000L
                val duration = player?.duration ?: Long.MAX_VALUE
                player?.seekTo(pos.coerceAtMost(duration))
                updateSeekBar()
                showOverlay()
            } else {
                nextChannel()
                showOverlay()
            }
        }
    }

    private fun setupResizeButton() {
        binding.btnResize.setOnClickListener { cycleResizeMode() }
    }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
        binding.playerView.resizeMode = resizeModes[resizeModeIndex]
        binding.playerView.requestLayout()
        Toast.makeText(this, resizeModeLabels[resizeModeIndex], Toast.LENGTH_SHORT).show()
        resetHideTimer()
    }

    // ─── Brightness & Volume gesture helpers ────────────────────────────────

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        val current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        val newBrightness = (current + delta).coerceIn(0.01f, 1f)
        val hitEdge = (newBrightness <= 0.01f || newBrightness >= 1f) && newBrightness != current
        lp.screenBrightness = newBrightness
        window.attributes = lp
        val pct = (newBrightness * 100).toInt()
        binding.brightnessBar.progress = pct
        binding.tvBrightnessPercent.text = "$pct%"
        binding.tvBrightnessIcon.text = if (pct < 40) "🔅" else "☀"
        showIndicator(binding.brightnessIndicator, hideBrightnessRunnable)
        if (hitEdge) hapticTick()
    }

    private fun adjustVolume(delta: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVol = (current + (delta * max).toInt()).coerceIn(0, max)
        val hitEdge = (newVol == 0 || newVol == max) && newVol != current
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val pct = if (max > 0) (newVol * 100 / max) else 0
        binding.volumeBar.progress = pct
        binding.tvVolumePercent.text = "$pct%"
        binding.tvVolumeIcon.text = when {
            newVol == 0 -> "🔇"
            pct < 50 -> "🔉"
            else -> "🔊"
        }
        showIndicator(binding.volumeIndicator, hideVolumeRunnable)
        if (hitEdge) hapticTick()
    }

    private fun hapticTick() {
        binding.root.performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    private fun showIndicator(view: View, hideRunnable: Runnable) {
        view.visibility = View.VISIBLE
        indicatorHandler.removeCallbacks(hideRunnable)
        indicatorHandler.postDelayed(hideRunnable, 1200)
    }

    // ─── Gesture detector ───────────────────────────────────────────────────

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                isAdjustingGesture = false
                gestureAccumY = 0f
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val w = binding.root.width.toFloat()
                val h = binding.root.height.toFloat()
                val startX = e1?.x ?: return false
                if (abs(distanceY) < abs(distanceX)) return false  // horizontal scroll — ignore
                val sensitivity = 1.5f / h

                // Small dead zone before the first engagement so a slightly wobbly tap
                // (meant to toggle the controls) doesn't nudge brightness/volume by accident.
                if (!isAdjustingGesture) {
                    gestureAccumY += distanceY
                    val deadZonePx = resources.displayMetrics.density * 10f
                    if (abs(gestureAccumY) < deadZonePx) return false
                }

                return when {
                    startX < w * 0.35f -> {
                        isAdjustingGesture = true
                        adjustBrightness(-distanceY * sensitivity)
                        true
                    }
                    startX > w * 0.65f -> {
                        isAdjustingGesture = true
                        adjustVolume(-distanceY * sensitivity)
                        true
                    }
                    else -> false
                }
            }

        })

        binding.root.setOnTouchListener { _, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                val wasAdjusting = isAdjustingGesture
                isAdjustingGesture = false
                if (wasAdjusting) return@setOnTouchListener true
            }
            handled && isAdjustingGesture
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ─── Player ─────────────────────────────────────────────────────────────

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 120000, 5000, 10000)
            .setPrioritizeTimeOverSizeThresholds(true)
            // Retain already-played media so live channels can rewind without a network
            // re-fetch for the last couple of minutes; older content still seeks fine via
            // the on-disk timeshift cache below.
            .setBackBuffer(120_000, true)
            .build()

        val upstreamDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        // DVR/timeshift: cache every byte of the live stream to disk as it plays, so
        // rewinding into recently-played live TV re-reads from local disk instead of
        // requiring the provider to support server-side catchup.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getTimeshiftCache(applicationContext))
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory)

        val tunnelingEnabled = kotlinx.coroutines.runBlocking { prefs.tunneledPlaybackEnabled.first() }
        val dv7FallbackEnabled = kotlinx.coroutines.runBlocking { prefs.dv7FallbackEnabled.first() }

        // DV7 fallback: some devices lack proper Dolby Vision Profile 7 (dual-layer) decode
        // support and either fail or black-screen. When enabled, redirect DV7 content to a
        // standard HEVC decoder instead — DV7's base layer is valid HEVC on its own.
        val codecSelector = if (dv7FallbackEnabled) {
            androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION) {
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(
                        androidx.media3.common.MimeTypes.VIDEO_H265, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                } else {
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                }
            }
        } else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setMediaCodecSelector(codecSelector)

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            if (tunnelingEnabled) parameters = buildUponParameters().setTunnelingEnabled(true).build()
        }

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.resizeMode = resizeModes[resizeModeIndex]
                binding.playerView.useController = false
                applySubtitleStyle()

                binding.playerView.setOnClickListener {
                    if (binding.epgOverlay.visibility == View.VISIBLE) {
                        hideHandler.removeCallbacks(hideRunnable)
                        hideRunnable.run()
                    } else {
                        showOverlay()
                    }
                }

                binding.btnPlayPause.setOnClickListener {
                    val wasPlaying = exoPlayer.isPlaying
                    if (wasPlaying) exoPlayer.pause() else exoPlayer.play()
                    updatePlayPauseButton()
                    resetHideTimer()
                    if (wasPlaying) traktScrobble(::scrobblePauseCall)
                    else if (traktScrobbleStarted) traktScrobble(::scrobbleStartCall)
                }

                binding.btnDvrRewind.setOnClickListener {
                    if (!isVod) {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 60_000L).coerceAtLeast(0L))
                        updateDvrLiveButton()
                        resetHideTimer()
                    }
                }

                binding.btnDvrLive.setOnClickListener {
                    if (!isVod) {
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.play()
                        updateDvrLiveButton()
                        resetHideTimer()
                    }
                }

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                retryCount = 0
                                binding.progressBuffering.visibility = View.GONE
                                binding.tvRetryStatus.visibility = View.GONE
                                if (isVod) startSeekBarUpdater()
                                startHealthBadge()
                                showOverlay()
                                updatePlayPauseButton()
                                if (isVod && resumePositionMs > 0L) {
                                    exoPlayer.seekTo(resumePositionMs)
                                    resumePositionMs = 0L
                                }
                                if (isVod && !traktScrobbleStarted) {
                                    traktScrobbleStarted = true
                                    traktScrobble(::scrobbleStartCall)
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                binding.progressBuffering.visibility = View.VISIBLE
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBuffering.visibility = View.GONE
                                if (!isVod) scheduleRetry()
                                else showUpNextIfAvailable()
                                if (isVod) traktScrobble(::scrobbleStopCall, progressOverride = 100f)
                            }
                            else -> binding.progressBuffering.visibility = View.GONE
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        binding.progressBuffering.visibility = View.GONE
                        if (isVod) {
                            binding.tvRetryStatus.text = "Playback error: ${error.message}"
                            binding.tvRetryStatus.visibility = View.VISIBLE
                        } else {
                            scheduleRetry()
                        }
                    }
                })
            }
    }

    private fun applySubtitleStyle() {
        lifecycleScope.launch {
            val s = prefs.subtitleStyle.first()
            val subtitleView = binding.playerView.subtitleView ?: return@launch
            subtitleView.setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    s.textColor,
                    s.backgroundColor,
                    android.graphics.Color.TRANSPARENT,
                    if (s.outlineEnabled) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                    s.outlineColor,
                    if (s.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                )
            )
            subtitleView.setFractionalTextSize(
                androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.sizeScale
            )
            subtitleView.translationY = -s.verticalOffsetDp * resources.displayMetrics.density
        }
    }

    private fun scrobbleProgress(): Float {
        val p = player ?: return 0f
        val duration = p.duration.takeIf { it > 0 } ?: return 0f
        return (p.currentPosition.toFloat() / duration.toFloat() * 100f).coerceIn(0f, 100f)
    }

    private suspend fun scrobbleStartCall(progress: Float) {
        if (traktSeason >= 0 && traktEpisode >= 0 && traktSeriesName.isNotBlank()) {
            traktManager.scrobbleEpisodeStart(traktSeriesName, traktSeason, traktEpisode, progress)
        } else {
            val parsed = traktManager.parseTitle(streamTitle)
            traktManager.scrobbleMovieStart(parsed.title, parsed.year, progress)
        }
    }

    private suspend fun scrobblePauseCall(progress: Float) {
        if (traktSeason >= 0 && traktEpisode >= 0 && traktSeriesName.isNotBlank()) {
            traktManager.scrobbleEpisodePause(traktSeriesName, traktSeason, traktEpisode, progress)
        } else {
            val parsed = traktManager.parseTitle(streamTitle)
            traktManager.scrobbleMoviePause(parsed.title, parsed.year, progress)
        }
    }

    private suspend fun scrobbleStopCall(progress: Float) {
        if (traktSeason >= 0 && traktEpisode >= 0 && traktSeriesName.isNotBlank()) {
            traktManager.scrobbleEpisodeStop(traktSeriesName, traktSeason, traktEpisode, progress)
        } else {
            val parsed = traktManager.parseTitle(streamTitle)
            traktManager.scrobbleMovieStop(parsed.title, parsed.year, progress)
        }
    }

    private fun traktScrobble(call: suspend (Float) -> Unit, progressOverride: Float? = null) {
        if (!isVod) return
        val progress = progressOverride ?: scrobbleProgress()
        // Uses a process-wide scope, not lifecycleScope — finish()'s stop-scrobble call must
        // survive the activity being destroyed right after this is fired.
        traktIoScope.launch {
            try { call(progress) } catch (_: Exception) { /* best-effort — never blocks playback */ }
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying ?: false
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    /** Dims the "● LIVE" button while behind the live edge, highlights it once caught up. */
    private fun updateDvrLiveButton() {
        if (isVod) return
        val p = player ?: return
        val offsetMs = p.currentLiveOffset
        val atLiveEdge = offsetMs == androidx.media3.common.C.TIME_UNSET || offsetMs < 5_000L
        binding.btnDvrLive.setTextColor(
            if (atLiveEdge) 0xFF555555.toInt() else 0xFFFF3B30.toInt()
        )
    }

    private fun scheduleRetry() {
        if (isVod && retryCount >= maxRetries) {
            binding.tvRetryStatus.text = "Stream unavailable after $maxRetries attempts"
            binding.tvRetryStatus.visibility = View.VISIBLE
            return
        }
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            val backoffMs = if (isVod) {
                (2000L * (retryCount + 1)).coerceAtMost(16000L)
            } else {
                // Live: ramp up to 30s then hold there
                (2000L * (retryCount + 1)).coerceAtMost(30000L)
            }
            val attempt = retryCount + 1
            val delaySec = backoffMs / 1000
            val suffix = if (isVod) " (attempt $attempt of $maxRetries)" else ""
            binding.tvRetryStatus.text = "● Reconnecting in ${delaySec}s$suffix…"
            binding.tvRetryStatus.visibility = View.VISIBLE
            delay(backoffMs)
            retryCount++
            player?.let {
                it.setMediaItem(MediaItem.fromUri(streamUrl))
                it.prepare()
                it.playWhenReady = true
            }
        }
    }

    private fun setupCast() {
        try {
            castContext = CastContext.getSharedInstance(this)
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                    )
                )
                .build()
            binding.btnCast.routeSelector = selector
            binding.btnCast.visibility = View.GONE  // shown only when overlay is visible
            castAvailable = true
            // Listener is managed in onResume/onPause — don't add it here too
            castSession = castContext?.sessionManager?.currentCastSession
            castSession?.let { stopLocalAndCast(it) }
        } catch (e: Exception) {
            castAvailable = false
            binding.btnCast.visibility = View.GONE
        }
    }

    private fun stopLocalAndCast(session: CastSession) {
        if (streamUrl.isBlank()) return
        val localPositionMs = if (isVod) (player?.currentPosition?.takeIf { it > 0 } ?: resumePositionMs) else 0L
        player?.stop()
        player?.clearMediaItems()
        binding.bufferHealthBadge.visibility = View.GONE
        lifecycleScope.launch {
            delay(1500)
            val directUrl = if (!isVod) repository.getLiveStreamUrlForCast(streamId) else streamUrl

            // Start local CORS proxy — Chromecast Default Media Receiver runs in a browser
            // context and enforces CORS; most IPTV servers don't send CORS headers.
            castProxy?.stop()
            val localIp = getLocalIpAddress()
            val castUrl = if (localIp != null) {
                val proxy = com.iptvapp.cast.IptvCastProxy(localIp).also {
                    it.start()
                    castProxy = it
                }
                proxy.proxyUrl(directUrl)
            } else {
                directUrl
            }

            Log.d("CastDebug", "localIp=$localIp castUrl=$castUrl")

            val contentType = when {
                castUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                castUrl.contains(".mpd",  ignoreCase = true) -> "application/dash+xml"
                castUrl.contains(".mp4",  ignoreCase = true) -> "video/mp4"
                else -> "application/x-mpegURL"
            }
            // Live channels use STREAM_TYPE_LIVE — BUFFERED waits for #EXT-X-ENDLIST which
            // never comes on a live stream, causing indefinite LOADING state.
            val streamType = if (isVod) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE

            // Fetch current EPG program to show as subtitle on Chromecast screen
            var nowProgram: com.iptvapp.data.local.entities.EpgEntity? = null
            if (!isVod && streamId != -1) {
                try {
                    repository.fetchEpg(streamId)
                    val epg = repository.getEpgForStream(streamId).first()
                    val nowMs = System.currentTimeMillis()
                    fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                    fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.stopTimestamp < 100_000_000_000L) e.stopTimestamp * 1000L else e.stopTimestamp
                    nowProgram = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs }
                } catch (_: Exception) {}
            }

            val metadata = MediaMetadata(if (isVod) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
                putString(MediaMetadata.KEY_TITLE, streamTitle)
                nowProgram?.title?.takeIf { it.isNotBlank() }?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            }
            // contentId must be the URL (not the title) — some receiver versions use it as the fallback src
            val mediaInfo = MediaInfo.Builder(castUrl)
                .setContentUrl(castUrl)
                .setStreamType(streamType)
                .setContentType(contentType)
                .setMetadata(metadata)
                .build()
            val loadRequest = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .apply { if (isVod && localPositionMs > 0) setCurrentTime(localPositionMs) }
                .build()

            val client = session.remoteMediaClient ?: run {
                Log.e("CastDebug", "remoteMediaClient is null")
                return@launch
            }
            client.load(loadRequest).setResultCallback { result ->
                Log.d("CastDebug", "load result: success=${result.status.isSuccess} code=${result.status.statusCode}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
        // If the session started while we were paused (cast picker caused onPause),
        // onSessionStarted already fired with no listener — catch it here
        val activeSession = castContext?.sessionManager?.currentCastSession
        if (activeSession != null && castSession == null) {
            Log.d("CastDebug", "Caught missed session start in onResume")
            castSession = activeSession
            stopLocalAndCast(activeSession)
        }
    }

    override fun onPause() {
        super.onPause()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    private fun loadStream(url: String) {
        retryCount = 0
        retryJob?.cancel()
        streamUrl = url
        val activeSession = castSession
        if (activeSession != null) {
            stopLocalAndCast(activeSession)
        } else {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(url))
                it.prepare()
                it.playWhenReady = true
            }
        }
    }

    private fun saveVodProgress() {
        if (!isVod || streamId < 0) return
        val watched = player?.currentPosition ?: return
        val duration = player?.duration ?: return
        if (duration <= 0) return
        lifecycleScope.launch { repository.saveVodProgress(streamId, watched, duration) }
    }

    private fun updateSeekBar() {
        if (!isVod) return
        val duration = player?.duration ?: return
        if (duration <= 0) return
        binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = (player?.currentPosition ?: 0L).toInt()
    }

    private fun startSeekBarUpdater() {
        if (!isVod) return
        binding.seekBar.visibility = View.VISIBLE
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { hideHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) { resetHideTimer() }
        })
        seekRunnable = object : Runnable {
            override fun run() { updateSeekBar(); seekHandler.postDelayed(this, 1000) }
        }
        seekHandler.post(seekRunnable!!)
    }

    private fun showOverlay() {
        isOverlayVisible = true
        binding.tvChannelTitle.text = streamTitle
        binding.epgOverlay.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnGuide.visibility = View.VISIBLE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        if (!isVod) {
            binding.btnDvrRewind.visibility = View.VISIBLE
            binding.btnDvrLive.visibility = View.VISIBLE
            updateDvrLiveButton()
        }
        if (castAvailable) binding.btnCast.visibility = View.VISIBLE
        if (isHealthBadgeActive) binding.bufferHealthBadge.visibility = View.VISIBLE
        updatePlayPauseButton()
        resetHideTimer()
        binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
        if (!isVod && streamId != -1) {
            lifecycleScope.launch {
                repository.fetchEpg(streamId)
                val epg = repository.getEpgForStream(streamId).first()
                val nowMs = System.currentTimeMillis()
                fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity)  = if (e.stopTimestamp  < 100_000_000_000L) e.stopTimestamp  * 1000L else e.stopTimestamp
                val now  = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs }
                val next = epg.firstOrNull { now != null && startMs(it) > stopMs(now) }
                binding.tvEpgNow.text = if (now != null) "NOW: " + now.title else ""
                binding.tvEpgNext.text = if (next != null) "NEXT: " + next.title else ""
            }
        }
    }

    private fun showChannelOsd() {
        binding.tvOsdChannelName.text = streamTitle
        binding.tvOsdEpg.text = ""
        binding.osdEpgProgress.progress = 0
        binding.channelOsd.visibility = View.VISIBLE
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 2500)
        if (streamId != -1) {
            lifecycleScope.launch {
                val epg = repository.getEpgForStream(streamId).first()
                val nowMs = System.currentTimeMillis()
                fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity)  = if (e.stopTimestamp  < 100_000_000_000L) e.stopTimestamp  * 1000L else e.stopTimestamp
                val now = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs } ?: return@launch
                binding.tvOsdEpg.text = now.title
                val start = startMs(now); val stop = stopMs(now)
                val progress = if (stop > start) ((nowMs - start) * 100 / (stop - start)).toInt().coerceIn(0, 100) else 0
                binding.osdEpgProgress.progress = progress
            }
        }
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 5000)
    }

    private fun playChannel(channel: ChannelEntity) {
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            streamId = channel.streamId
            streamTitle = channel.name
            val url = repository.getLiveStreamUrl(channel.streamId)
            binding.tvChannelTitle.text = streamTitle
            val idx = channels.indexOfFirst { it.streamId == channel.streamId }
            if (idx >= 0) currentIndex = idx
            loadStream(url)
        }
    }

    private fun nextChannel() {
        if (channels.isEmpty() || currentIndex < 0) return
        currentIndex = (currentIndex + 1) % channels.size
        playChannel(channels[currentIndex])
    }

    private fun previousChannel() {
        if (channels.isEmpty() || currentIndex < 0) return
        currentIndex = if (currentIndex == 0) channels.lastIndex else currentIndex - 1
        playChannel(channels[currentIndex])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                val now = System.currentTimeMillis()
                when {
                    isOverlayVisible && now - lastBackPressMs < 500 -> {
                        // Double back while overlay open → exit
                        finish(); true
                    }
                    isOverlayVisible -> {
                        // Single back → dismiss overlay only
                        lastBackPressMs = now
                        hideHandler.removeCallbacks(hideRunnable)
                        hideRunnable.run()
                        true
                    }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                !isOverlayVisible && !isVod -> { nextChannel(); showChannelOsd(); true }
                !isOverlayVisible -> { showOverlay(); true }
                else -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                !isOverlayVisible && !isVod -> { previousChannel(); showChannelOsd(); true }
                !isOverlayVisible -> { showOverlay(); true }
                else -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                !isOverlayVisible -> { showOverlay(); true }
                isVod -> { resetHideTimer(); player?.seekTo(((player?.currentPosition ?: 0L) - 10000L).coerceAtLeast(0L)); true }
                else -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                !isOverlayVisible -> { showOverlay(); true }
                isVod -> { resetHideTimer(); player?.seekTo(((player?.currentPosition ?: 0L) + 10000L).coerceAtMost(player?.duration ?: Long.MAX_VALUE)); true }
                else -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player?.isPlaying == true) player?.pause() else player?.play()
                updatePlayPauseButton(); true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!isOverlayVisible) { showOverlay(); true }
                else { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_GUIDE -> { if (!isOverlayVisible) showOverlay(); true }
            // Yellow / X / F key cycles aspect ratio without opening the overlay
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_F -> { cycleResizeMode(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isVod) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            isOverlayVisible = false
            binding.epgOverlay.visibility = View.GONE
            binding.btnBack.visibility = View.GONE
            binding.btnGuide.visibility = View.GONE
            binding.btnPlayPause.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
            binding.btnCast.visibility = View.GONE
            binding.bufferHealthBadge.visibility = View.GONE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.decorView.post {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupFavoritesGuide() {
        guideAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                binding.guideContainer.visibility = View.GONE
                playChannel(channel)
            },
            onFavoriteClick = {}
        )
        binding.rvFavoritesGuide.layoutManager = LinearLayoutManager(this)
        binding.rvFavoritesGuide.adapter = guideAdapter
        binding.btnGuide.setOnClickListener { toggleFavoritesGuide() }
        binding.btnCloseGuide.setOnClickListener { binding.guideContainer.visibility = View.GONE }
    }

    private fun toggleFavoritesGuide() {
        if (binding.guideContainer.visibility == View.VISIBLE) {
            binding.guideContainer.visibility = View.GONE
            return
        }
        hideHandler.removeCallbacks(hideRunnable)
        lifecycleScope.launch {
            val favs = repository.getFavoriteChannels().first()
            guideAdapter.submitList(favs)
            val ids = favs.map { it.streamId }
            if (ids.isNotEmpty()) {
                val epg = repository.getEpgForStreams(ids).first().groupBy { it.streamId }
                val textMap = favs.associate { ch ->
                    val now = epg[ch.streamId].orEmpty().firstOrNull()
                    val next = epg[ch.streamId].orEmpty().drop(1).firstOrNull()
                    val t = when {
                        now != null && next != null -> "NOW: ${now.title}   NEXT: ${next.title}"
                        now != null -> "NOW: ${now.title}"
                        else -> ""
                    }
                    ch.streamId to t
                }
                guideAdapter.submitEpgText(textMap)
            }
            binding.guideContainer.visibility = View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        player?.let { binding.playerView.player = it }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            player = buildPlayer()
            loadStream(streamUrl)
        }
    }

    override fun onStop() {
        super.onStop()
        saveVodProgress()
        sleepTimer?.cancel()
        retryJob?.cancel()
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        statsHandler.removeCallbacks(statsRunnable)
        stopHealthBadge()
        if (!isChangingConfigurations) {
            player?.release()
            player = null
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK, android.content.Intent().apply {
            putExtra("stream_id", streamId)
            putExtra("stream_url", streamUrl)
            putExtra("stream_title", streamTitle)
        })
        if (isVod && traktScrobbleStarted) {
            traktScrobbleStarted = false
            traktScrobble(::scrobbleStopCall)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        upNextJob?.cancel()
        castProxy?.stop()
        castProxy = null
        hideHandler.removeCallbacks(hideRunnable)
        osdHandler.removeCallbacks(hideOsdRunnable)
        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        indicatorHandler.removeCallbacks(hideVolumeRunnable)
    }

    private fun getLocalIpAddress(): String? {
        try {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces() ?: return null) {
                if (iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        Log.d("CastProxy", "Local IP: $ip (${iface.name} up=${iface.isUp})")
                        return ip
                    }
                }
            }
            Log.e("CastProxy", "No non-loopback IPv4 address found")
        } catch (e: Exception) {
            Log.e("CastProxy", "getLocalIpAddress failed", e)
        }
        return null
    }
}
