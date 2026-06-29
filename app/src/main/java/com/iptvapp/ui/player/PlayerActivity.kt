package com.iptvapp.ui.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.util.Log
import android.widget.Toast
import android.content.Context
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

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private lateinit var guideAdapter: ChannelAdapter

    private val hideRunnable = Runnable {
        binding.epgOverlay.visibility = View.GONE
        binding.btnBack.visibility = View.GONE
        binding.btnGuide.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.btnCast.visibility = View.GONE
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

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private var resizeModeIndex = 0

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var okHttpClient: OkHttpClient

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var channels: List<ChannelEntity> = emptyList()
    private var currentIndex: Int = -1

    private var retryCount = 0
    private val maxRetries = 20
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null

    private var sleepTimer: CountDownTimer? = null
    private var isAdjustingGesture = false
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
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) { castSession = session; stopLocalAndCast(session) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { castSession = session; stopLocalAndCast(session) }
        override fun onSessionEnded(session: CastSession, error: Int) { castSession = null; loadStream(streamUrl) }
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
        binding.btnSpeed.setOnClickListener { showSpeedDialog() }
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
        val p = player ?: run {
            binding.bufferHealthBadge.visibility = View.GONE
            return
        }
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
        val label = buildString {
            append("$bufPct%")
            if (bitrate.isNotEmpty()) append("  $bitrate")
        }
        binding.tvHealthBadge.text = label
        binding.bufferHealthBadge.visibility = View.VISIBLE
    }

    private fun startHealthBadge() {
        binding.bufferHealthBadge.visibility = View.VISIBLE
        updateHealthBadge()
        healthHandler.postDelayed(healthRunnable, 2000)
    }

    private fun stopHealthBadge() {
        healthHandler.removeCallbacks(healthRunnable)
        binding.bufferHealthBadge.visibility = View.GONE
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.25×", "0.5×", "0.75×", "Normal (1×)", "1.25×", "1.5×", "2×")
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val current = player?.playbackParameters?.speed ?: 1f
        val checked = values.indexOfFirst { it == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                player?.setPlaybackSpeed(values[which])
                binding.btnSpeed.text = if (values[which] == 1f) "1×" else "${values[which]}×"
                dialog.dismiss()
                resetHideTimer()
            }
            .show()
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
        binding.btnResize.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            binding.playerView.resizeMode = resizeModes[resizeModeIndex]
            resetHideTimer()
        }
    }

    // ─── Brightness & Volume gesture helpers ────────────────────────────────

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        val current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        lp.screenBrightness = (current + delta).coerceIn(0.01f, 1f)
        window.attributes = lp
        val pct = (lp.screenBrightness * 100).toInt()
        binding.brightnessBar.progress = pct
        showIndicator(binding.brightnessIndicator, hideBrightnessRunnable)
    }

    private fun adjustVolume(delta: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVol = (current + (delta * max).toInt()).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        binding.volumeBar.progress = if (max > 0) (newVol * 100 / max) else 0
        showIndicator(binding.volumeIndicator, hideVolumeRunnable)
    }

    private fun showIndicator(view: View, hideRunnable: Runnable) {
        view.visibility = View.VISIBLE
        indicatorHandler.removeCallbacks(hideRunnable)
        indicatorHandler.postDelayed(hideRunnable, 1200)
    }

    // ─── Gesture detector ───────────────────────────────────────────────────

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY = 100

            override fun onDown(e: MotionEvent): Boolean {
                isAdjustingGesture = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val w = binding.root.width.toFloat()
                val h = binding.root.height.toFloat()
                val startX = e1?.x ?: return false
                if (abs(distanceY) < abs(distanceX)) return false  // horizontal scroll — ignore
                val sensitivity = 1.5f / h
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

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isVod) return false
                val dy = (e2.y) - (e1?.y ?: 0f)
                val dx = (e2.x) - (e1?.x ?: 0f)
                if (abs(dy) > abs(dx) && abs(dy) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY) {
                    if (dy < 0) { nextChannel(); showChannelOsd() }
                    else { previousChannel(); showChannelOsd() }
                    return true
                }
                return false
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
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.resizeMode = resizeModes[resizeModeIndex]
                binding.playerView.useController = false

                binding.playerView.setOnClickListener {
                    if (binding.epgOverlay.visibility == View.VISIBLE) {
                        hideHandler.removeCallbacks(hideRunnable)
                        hideRunnable.run()
                    } else {
                        showOverlay()
                    }
                }

                binding.btnPlayPause.setOnClickListener {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    updatePlayPauseButton()
                    resetHideTimer()
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
                            }
                            Player.STATE_BUFFERING -> {
                                binding.progressBuffering.visibility = View.VISIBLE
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBuffering.visibility = View.GONE
                                if (!isVod) scheduleRetry()
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

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying ?: false
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun scheduleRetry() {
        if (retryCount >= maxRetries) {
            binding.tvRetryStatus.text = "Stream unavailable after $maxRetries attempts"
            binding.tvRetryStatus.visibility = View.VISIBLE
            return
        }
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            val backoffMs = (2000L * (retryCount + 1)).coerceAtMost(16000L)
            val attempt = retryCount + 1
            val delaySec = backoffMs / 1000
            binding.tvRetryStatus.text = "● Reconnecting in ${delaySec}s (attempt $attempt of $maxRetries)…"
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
        lifecycleScope.launch {
            delay(1500)
            val castUrl = if (!isVod) repository.getLiveStreamUrlForCast(streamId) else streamUrl
            Log.d("CastDebug", "Casting: isVod=$isVod streamId=$streamId url=$castUrl")
            Toast.makeText(this@PlayerActivity, "Casting: ${castUrl.takeLast(40)}", Toast.LENGTH_LONG).show()

            val contentType = when {
                castUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                castUrl.contains(".mpd",  ignoreCase = true) -> "application/dash+xml"
                castUrl.contains(".mp4",  ignoreCase = true) -> "video/mp4"
                else -> "application/x-mpegURL"
            }
            val streamType = if (isVod) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE
            val metadata = MediaMetadata(if (isVod) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
                putString(MediaMetadata.KEY_TITLE, streamTitle)
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

            val client = session.remoteMediaClient
            if (client == null) {
                Log.e("CastDebug", "remoteMediaClient is null")
                Toast.makeText(this@PlayerActivity, "Cast error: no media client", Toast.LENGTH_LONG).show()
                return@launch
            }
            client.load(loadRequest).addStatusListener { status ->
                Log.d("CastDebug", "load result: success=${status.isSuccess} code=${status.statusCode} msg=${status.statusMessage}")
                if (!status.isSuccess) {
                    runOnUiThread {
                        Toast.makeText(this@PlayerActivity,
                            "Cast failed (${status.statusCode}): ${status.statusMessage ?: "unknown"}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    override fun onPause() {
        super.onPause()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    private fun loadStream(url: String) {
        retryCount = 0
        retryJob?.cancel()
        streamUrl = url
        player?.let {
            it.setMediaItem(MediaItem.fromUri(url))
            it.prepare()
            it.playWhenReady = true
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
        binding.tvChannelTitle.text = streamTitle
        binding.epgOverlay.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnGuide.visibility = View.VISIBLE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        if (castAvailable) binding.btnCast.visibility = View.VISIBLE
        updatePlayPauseButton()
        resetHideTimer()
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
            KeyEvent.KEYCODE_DPAD_UP -> { if (!isVod) { previousChannel(); showChannelOsd() }; true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!isVod) { nextChannel(); showChannelOsd() }; true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (isVod) { player?.seekTo(((player?.currentPosition ?: 0L) - 10000L).coerceAtLeast(0L)) }; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isVod) { player?.seekTo(((player?.currentPosition ?: 0L) + 10000L).coerceAtMost(player?.duration ?: Long.MAX_VALUE)) }; true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (player?.isPlaying == true) player?.pause() else player?.play()
                updatePlayPauseButton(); true
            }
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
            binding.epgOverlay.visibility = View.GONE
            binding.btnBack.visibility = View.GONE
            binding.btnGuide.visibility = View.GONE
            binding.btnPlayPause.visibility = View.GONE
            binding.btnResize.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
            binding.btnCast.visibility = View.GONE
        } else {
            binding.btnResize.visibility = View.VISIBLE
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
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        osdHandler.removeCallbacks(hideOsdRunnable)
        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        indicatorHandler.removeCallbacks(hideVolumeRunnable)
    }
}
