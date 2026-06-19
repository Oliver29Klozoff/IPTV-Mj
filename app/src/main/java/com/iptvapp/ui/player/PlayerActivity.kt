package com.iptvapp.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
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
    }

    private var streamUrl: String = ""
    private var streamTitle: String = ""
    private var streamId: Int = -1

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private var resizeModeIndex = 2

    @Inject
    lateinit var repository: XtreamRepository

    private var channels: List<ChannelEntity> = emptyList()
    private var currentIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()
        setupFavoritesGuide()
        setupResizeButton()
        setupChannelZones()

        streamUrl = intent.getStringExtra("stream_url") ?: ""
        streamTitle = intent.getStringExtra("stream_title") ?: ""
        streamId = intent.getIntExtra("stream_id", -1)

        binding.tvChannelTitle.text = streamTitle
        binding.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            channels = repository.getAllChannels().first()
            currentIndex = channels.indexOfFirst { it.streamId == streamId }
        }

        initPlayer()
    }

    private fun setupChannelZones() {
        binding.zonePrevious.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (binding.epgOverlay.visibility == View.VISIBLE) {
                previousChannel()
            } else {
                showOverlay()
            }
        }
        binding.zoneNext.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (binding.epgOverlay.visibility == View.VISIBLE) {
                nextChannel()
            } else {
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

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 120000, 5000, 10000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.resizeMode = resizeModes[resizeModeIndex]
                binding.playerView.setOnClickListener {
                    if (binding.epgOverlay.visibility == View.VISIBLE) {
                        hideHandler.removeCallbacks(hideRunnable)
                        hideRunnable.run()
                    } else {
                        showOverlay()
                    }
                }

                val mediaItem = MediaItem.fromUri(streamUrl)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> showOverlay()
                            Player.STATE_BUFFERING -> {
                                binding.epgOverlay.visibility = View.GONE
                                binding.btnBack.visibility = View.GONE
                                binding.btnGuide.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> finish()
                            else -> {}
                        }
                    }
                })
            }
    }

    private fun showOverlay() {
        binding.tvChannelTitle.text = streamTitle
        binding.epgOverlay.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnGuide.visibility = View.VISIBLE
        resetHideTimer()
        if (streamId != -1) {
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
        lifecycleScope.launch {
            streamId = channel.streamId
            streamTitle = channel.name
            streamUrl = repository.getLiveStreamUrl(channel.streamId)
            binding.tvChannelTitle.text = streamTitle
            val idx = channels.indexOfFirst { it.streamId == channel.streamId }
            if (idx >= 0) currentIndex = idx
            player?.setMediaItem(MediaItem.fromUri(streamUrl))
            player?.prepare()
            player?.play()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}