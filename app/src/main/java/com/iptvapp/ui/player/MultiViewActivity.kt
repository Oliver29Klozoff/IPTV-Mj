package com.iptvapp.ui.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
        // PlayerView's shutter (the placeholder shown before the first video frame renders,
        // and again briefly on every channel switch) falls back to the current theme's
        // surface color when not set explicitly — on this app's light Material dialogs/theme
        // remnants that resolves to white instead of black, which read as "the stream turns
        // white" on every tap-triggered channel switch.
        binding.playerLeft.setShutterBackgroundColor(Color.BLACK)
        binding.playerRight.setShutterBackgroundColor(Color.BLACK)
        leftPlayer = ExoPlayer.Builder(this).build().also { binding.playerLeft.player = it }
        rightPlayer = ExoPlayer.Builder(this).build().also {
            binding.playerRight.player = it
            it.volume = 0f
        }
        leftPlayer?.addListener(bufferListener(binding.progressLeft))
        rightPlayer?.addListener(bufferListener(binding.progressRight))
        setupTileDrag()
        binding.root.post { updateActiveSideUI() } // wait for a layout pass so parent width/height are known
    }

    // ─── Fullscreen-primary + draggable PiP tile ────────────────────────────────

    private var tileX = -1f // -1 = not yet positioned (defaults to bottom-right on first layout)
    private var tileY = -1f

    private fun tileContainer() = if (activeSide == 0) binding.containerRight else binding.containerLeft
    private fun fullscreenContainer() = if (activeSide == 0) binding.containerLeft else binding.containerRight

    private fun setupTileDrag() {
        val listener = View.OnTouchListener { v, event ->
            val parent = binding.root
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartTranslationX = v.translationX
                    dragStartTranslationY = v.translationY
                    dragDistance = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    dragDistance = maxOf(dragDistance, kotlin.math.hypot(dx, dy))
                    val maxX = (parent.width - v.width).coerceAtLeast(0).toFloat()
                    val maxY = (parent.height - v.height).coerceAtLeast(0).toFloat()
                    v.translationX = (dragStartTranslationX + dx).coerceIn(0f, maxX)
                    v.translationY = (dragStartTranslationY + dy).coerceIn(0f, maxY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragDistance < TAP_SLOP_PX) {
                        // Treated as a tap, not a drag: swap this tile to fullscreen.
                        swapFullscreen()
                    } else {
                        tileX = v.translationX
                        tileY = v.translationY
                    }
                    true
                }
                else -> false
            }
        }
        binding.containerLeft.setOnTouchListener(listener)
        binding.containerRight.setOnTouchListener(listener)
    }

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private var dragDistance = 0f

    private val TAP_SLOP_PX get() = 10 * resources.displayMetrics.density

    /** Swaps which side is fullscreen vs. the small floating tile; audio follows the new fullscreen side. */
    private fun swapFullscreen() {
        switchToSide(1 - activeSide)
    }

    private fun applyPipLayout() {
        val root = binding.root
        val full = fullscreenContainer()
        val tile = tileContainer()

        full.translationX = 0f
        full.translationY = 0f
        full.layoutParams = (full.layoutParams as FrameLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }
        full.bringToFront()

        val density = resources.displayMetrics.density
        val tileWidth = (140 * density).toInt()
        val tileHeight = (90 * density).toInt()
        tile.layoutParams = (tile.layoutParams as FrameLayout.LayoutParams).apply {
            width = tileWidth
            height = tileHeight
            gravity = Gravity.TOP or Gravity.START
        }
        tile.bringToFront()
        binding.controlsBar.bringToFront()
        binding.channelPickerOverlay.bringToFront()

        root.post {
            val margin = (16 * density)
            val maxX = (root.width - tileWidth).coerceAtLeast(0).toFloat()
            val maxY = (root.height - tileHeight).coerceAtLeast(0).toFloat()
            if (tileX < 0f) {
                // First time positioning: default to bottom-right corner, PiP-style.
                tileX = (root.width - tileWidth - margin).coerceIn(0f, maxX)
                tileY = (root.height - tileHeight - margin).coerceIn(0f, maxY)
            } else {
                tileX = tileX.coerceIn(0f, maxX)
                tileY = tileY.coerceIn(0f, maxY)
            }
            tile.translationX = tileX
            tile.translationY = tileY
        }
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
        // Tapping/swapping a tile is how you'd naturally expect to "switch to watching/listening
        // to that stream" — audio always follows whichever side is now fullscreen.
        audioSide = side
        leftPlayer?.volume = if (audioSide == 0) 1f else 0f
        rightPlayer?.volume = if (audioSide == 1) 1f else 0f
        binding.btnMvAudio.text = "Audio: ${if (audioSide == 0) "Left" else "Right"}"
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
        // The border marks which side is currently fullscreen/active rather than a fixed left/right side.
        binding.borderLeft.visibility = if (activeSide == 0) View.VISIBLE else View.GONE
        binding.borderRight.visibility = if (activeSide == 1) View.VISIBLE else View.GONE
        applyPipLayout()
    }

    private fun setupButtons() {
        binding.btnMvBack.setOnClickListener { finish() }
        binding.btnMvAudio.setOnClickListener { toggleAudio() }
        // Tap-to-swap and drag are both handled by the OnTouchListener installed in setupTileDrag();
        // plain click listeners here would double-fire the swap.
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
                // No spatial left/right tiles to navigate between anymore (one is fullscreen,
                // the other a floating PiP tile) — D-pad left/right now swaps which side is
                // fullscreen, mirroring the tap-the-tile gesture used on touch.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!channelPickerVisible) { swapFullscreen(); showControls(); return true }
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
