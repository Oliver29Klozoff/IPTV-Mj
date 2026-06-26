package com.iptvapp.ui.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityMultiViewBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MultiViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiViewBinding

    @Inject lateinit var repository: XtreamRepository

    private var leftPlayer: ExoPlayer? = null
    private var rightPlayer: ExoPlayer? = null
    private var activeSide = 0   // 0 = left, 1 = right
    private var audioSide = 0    // 0 = left has audio, 1 = right

    private var allChannels = listOf<ChannelEntity>()
    private var filteredChannels = listOf<ChannelEntity>()
    private lateinit var pickerAdapter: PickerAdapter
    private var channelPickerVisible = false

    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { binding.controlsBar.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMultiViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayers()
        setupChannelPicker()
        setupButtons()
        loadChannels()
        showControls()
    }

    // ─── Players ─────────────────────────────────────────────────────────────

    private fun setupPlayers() {
        leftPlayer = ExoPlayer.Builder(this).build().also { binding.playerLeft.player = it }
        rightPlayer = ExoPlayer.Builder(this).build().also {
            binding.playerRight.player = it
            it.volume = 0f
        }
        leftPlayer?.addListener(bufferListener(binding.progressLeft))
        rightPlayer?.addListener(bufferListener(binding.progressRight))
        updateActiveSideUI()
    }

    private fun bufferListener(indicator: View) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            indicator.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }
    }

    private fun playChannel(channel: ChannelEntity) {
        lifecycleScope.launch {
            val url = repository.getLiveStreamUrl(channel.streamId)
            val epgText = repository.getEpgForStream(channel.streamId).first()
                .firstOrNull()?.title ?: ""

            val player = if (activeSide == 0) leftPlayer else rightPlayer
            player?.setMediaItem(MediaItem.fromUri(url))
            player?.prepare()
            player?.playWhenReady = true

            if (activeSide == 0) {
                binding.tvLeftChannel.text = channel.name
                binding.tvLeftEpg.text = epgText
            } else {
                binding.tvRightChannel.text = channel.name
                binding.tvRightEpg.text = epgText
            }
            hideChannelPicker()
        }
    }

    // ─── Channel picker ──────────────────────────────────────────────────────

    private fun setupChannelPicker() {
        pickerAdapter = PickerAdapter { playChannel(it) }
        binding.rvChannelPicker.layoutManager = LinearLayoutManager(this)
        binding.rvChannelPicker.adapter = pickerAdapter

        binding.etChannelSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().trim()
                filteredChannels = if (q.isEmpty()) allChannels
                    else allChannels.filter { it.name.contains(q, ignoreCase = true) }
                pickerAdapter.submitList(filteredChannels)
            }
        })
    }

    private fun loadChannels() {
        lifecycleScope.launch {
            allChannels = repository.getAllChannels().first()
            filteredChannels = allChannels
            pickerAdapter.submitList(filteredChannels)
        }
    }

    private fun showChannelPicker() {
        channelPickerVisible = true
        binding.tvPickerTitle.text = "Select Channel — ${if (activeSide == 0) "Left" else "Right"}"
        binding.channelPickerOverlay.visibility = View.VISIBLE
        binding.etChannelSearch.setText("")
        pickerAdapter.submitList(allChannels)
        filteredChannels = allChannels
    }

    private fun hideChannelPicker() {
        channelPickerVisible = false
        binding.channelPickerOverlay.visibility = View.GONE
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private fun switchToSide(side: Int) {
        activeSide = side
        updateActiveSideUI()
    }

    private fun toggleAudio() {
        audioSide = 1 - audioSide
        leftPlayer?.volume = if (audioSide == 0) 1f else 0f
        rightPlayer?.volume = if (audioSide == 1) 1f else 0f
        binding.btnMvAudio.text = "Audio: ${if (audioSide == 0) "Left" else "Right"}"
        showControls()
    }

    private fun updateActiveSideUI() {
        binding.borderLeft.visibility = if (activeSide == 0) View.VISIBLE else View.GONE
        binding.borderRight.visibility = if (activeSide == 1) View.VISIBLE else View.GONE
    }

    private fun setupButtons() {
        binding.btnMvBack.setOnClickListener { finish() }
        binding.btnMvAudio.setOnClickListener { toggleAudio() }
        binding.containerLeft.setOnClickListener { switchToSide(0); showControls() }
        binding.containerRight.setOnClickListener { switchToSide(1); showControls() }
    }

    private fun showControls() {
        binding.controlsBar.visibility = View.VISIBLE
        controlsHandler.removeCallbacks(hideControls)
        controlsHandler.postDelayed(hideControls, 4000)
    }

    // ─── D-pad navigation ────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!channelPickerVisible) { switchToSide(0); showControls(); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!channelPickerVisible) { switchToSide(1); showControls(); return true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!channelPickerVisible) { toggleAudio(); return true }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!channelPickerVisible) { showChannelPicker(); return true }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (channelPickerVisible) { hideChannelPicker(); return true }
                    finish(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { leftPlayer?.pause(); rightPlayer?.pause() }
    }

    override fun onStart() {
        super.onStart()
        leftPlayer?.play(); rightPlayer?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        leftPlayer?.release(); leftPlayer = null
        rightPlayer?.release(); rightPlayer = null
    }

    // ─── Channel picker adapter ───────────────────────────────────────────────

    inner class PickerAdapter(
        private val onClick: (ChannelEntity) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

        private var items = listOf<ChannelEntity>()
        fun submitList(list: List<ChannelEntity>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (64 * resources.displayMetrics.density + 0.5f).toInt()
                )
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(40, 0, 40, 0)
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setBackgroundResource(R.drawable.focus_selector)
            }
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            (holder.itemView as TextView).text = ch.name
            holder.itemView.setOnClickListener { onClick(ch) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v)
    }
}
