package com.iptvapp.ui.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.iptvapp.data.local.entities.ChannelEntity
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptvapp.ui.home.ChannelAdapter
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    }

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

    @Inject
    lateinit var repository: XtreamRepository

    private var channels: List<ChannelEntity> = emptyList()
    private var currentIndex: Int = -1

    private var retryCount = 0
    private val maxRetries = 5
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setupFavoritesGuide()
        setupResizeButton()

        streamUrl = intent.getStringExtra("stream_url") ?: ""
        streamTitle = intent.getStringExtra("stream_title") ?: ""
        streamId = intent.getIntExtra("stream_id", -1)
        isVod = intent.getBooleanExtra("is_vod", false)
        resumePositionMs = intent.getLongExtra("resume_ms", 0L)

        setupChannelZones()
        setupGestureDetector()
        // intent already read above ?: ""
        streamTitle = intent.getStringExtra("stream_title") ?: ""
        streamId = intent.getIntExtra("stream_id", -1)
        isVod = intent.getBooleanExtra("is_vod", false)
        resumePositionMs = intent.getLongExtra("resume_ms", 0L)

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

        // Show resume dialog for VOD if there's a saved position
        if (isVod && resumePositionMs > 0L) {
            showResumeDialog()
        }
    }

    private fun showResumeDialog() {
        val minutes = resumePositionMs / 1000 / 60
        val seconds = (resumePositionMs / 1000) % 60
        AlertDialog.Builder(this)
            .setTitle("Resume Playback")
            .setMessage("Resume from ${minutes}:${seconds.toString().padStart(2, '0')}?")
            .setPositiveButton("Resume") { _, _ -> /* resumePositionMs already set */ }
            .setNegativeButton("Start Over") { _, _ -> resumePositionMs = 0L }
            .setCancelable(false)
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

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 120000, 5000, 10000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.resizeMode = resizeModes[resizeModeIndex]

                // Always use our custom overlay, not ExoPlayer built-in controller
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
                                if (isVod) startSeekBarUpdater()
                                showOverlay()
                                updatePlayPauseButton()
                                // Seek to resume position for VOD
                                if (isVod && resumePositionMs > 0L) {
                                    exoPlayer.seekTo(resumePositionMs)
                                    resumePositionMs = 0L
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                binding.epgOverlay.visibility = View.GONE
                                binding.btnBack.visibility = View.GONE
                                binding.btnGuide.visibility = View.GONE
                                binding.btnPlayPause.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> if (!isVod) scheduleRetry()
                            else -> {}
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        scheduleRetry()
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
        if (retryCount >= maxRetries) { finish(); return }
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            val backoffMs = (2000L * (retryCount + 1)).coerceAtMost(16000L)
            delay(backoffMs)
            retryCount++
            player?.let {
                it.setMediaItem(MediaItem.fromUri(streamUrl))
                it.prepare()
                it.playWhenReady = true
            }
        }
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
        lifecycleScope.launch {
            repository.saveVodProgress(streamId, watched, duration)
        }
    }

    private fun updateSeekBar() {
        if (!isVod) return
        val duration = player?.duration ?: return
        if (duration <= 0) return
        val position = player?.currentPosition ?: 0L
        binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = position.toInt()
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
        val seekHandler = Handler(Looper.getMainLooper())
        val seekRunnable = object : Runnable {
            override fun run() {
                updateSeekBar()
                seekHandler.postDelayed(this, 1000)
            }
        }
        seekHandler.post(seekRunnable)
    }

    private fun showOverlay() {
        binding.tvChannelTitle.text = streamTitle
        binding.epgOverlay.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnGuide.visibility = View.VISIBLE
        binding.btnPlayPause.visibility = View.VISIBLE
        updatePlayPauseButton()
        resetHideTimer()
        if (!isVod && streamId != -1) {
            lifecycleScope.launch {
                repository.fetchEpg(streamId)
                val epg = repository.getEpgForStream(streamId).first()
                val now = epg.firstOrNull()
                val next = epg.drop(1).firstOrNull()
                binding.tvEpgNow.text = if (now != null) "NOW: " + now.title else ""
                binding.tvEpgNext.text = if (next != null) "NEXT: " + next.title else ""
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
        currentIndex++
        if (currentIndex >= channels.size) currentIndex = 0
        playChannel(channels[currentIndex])
    }

    private fun previousChannel() {
        if (channels.isEmpty() || currentIndex < 0) return
        currentIndex--
        if (currentIndex < 0) currentIndex = channels.lastIndex
        playChannel(channels[currentIndex])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (!isVod) { previousChannel(); showOverlay() }; true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!isVod) { nextChannel(); showOverlay() }; true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (isVod) { val pos = (player?.currentPosition ?: 0L) - 10000L; player?.seekTo(pos.coerceAtLeast(0L)) }; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isVod) { val pos = (player?.currentPosition ?: 0L) + 10000L; player?.seekTo(pos.coerceAtMost(player?.duration ?: Long.MAX_VALUE)) }; true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { if (player?.isPlaying == true) player?.pause() else player?.play(); updatePlayPauseButton(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isVod) return false
                val deltaY = (e2.y) - (e1?.y ?: 0f)
                val deltaX = (e2.x) - (e1?.x ?: 0f)
                if (Math.abs(deltaY) > Math.abs(deltaX) &&
                    Math.abs(deltaY) > SWIPE_THRESHOLD &&
                    Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (deltaY < 0) { nextChannel(); showOverlay() }
                    else { previousChannel(); showOverlay() }
                    return true
                }
                return false
            }
        })
        binding.root.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun hideSystemBars() {
        window.decorView.post {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupFavoritesGuide() {
        guideAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                binding.guideContainer.visibility = View.GONE
                playChannel(channel)
            },
            onFavoriteClick = { }
        )
        binding.rvFavoritesGuide.layoutManager = LinearLayoutManager(this)
        binding.rvFavoritesGuide.adapter = guideAdapter
        binding.btnGuide.setOnClickListener { toggleFavoritesGuide() }
        binding.btnCloseGuide.setOnClickListener {
            binding.guideContainer.visibility = View.GONE
        }
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
                        now != null && next != null -> "NOW: " + now.title + "   NEXT: " + next.title
                        now != null -> "NOW: " + now.title
                        else -> ""
                    }
                    ch.streamId to t
                }
                guideAdapter.submitEpgText(textMap)
            }
            binding.guideContainer.visibility = View.VISIBLE
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
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
        } else {
            binding.btnResize.visibility = View.VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
        retryJob?.cancel()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
    }
}