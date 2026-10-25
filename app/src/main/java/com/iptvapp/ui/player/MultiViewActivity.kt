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
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MultiViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiViewBinding

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager
    @Inject lateinit var smartChannels: com.iptvapp.data.smart.SmartChannelStore

    private var leftPlayer: ExoPlayer? = null
    private var rightPlayer: ExoPlayer? = null
    // Each side reloads on its own when it errors, sticks buffering, or freezes.
    private var leftHealer: TileHealer? = null
    private var rightHealer: TileHealer? = null
    /** What each side shows, for its label while it reconnects. */
    private var leftLabel = ""
    private var rightLabel = ""
    private var activeSide = 0   // 0 = left, 1 = right
    private var audioSide = 0    // 0 = left has audio, 1 = right

    private data class Choice(val server: Int, val streamId: Int, val name: String, val provider: String = "Primary") {
        val label get() = "$name · $provider"
    }
    private val selections = arrayOfNulls<Choice>(2)
    private val connections by lazy { com.iptvapp.ui.mosaic.MultiViewConnections(repository) }
    private var allChannels = listOf<Choice>()
    private var filteredChannels = listOf<Choice>()
    private lateinit var pickerAdapter: PickerAdapter
    private var channelPickerVisible = false
    private var searchJob: kotlinx.coroutines.Job? = null

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

        if (com.iptvapp.ui.mosaic.MultiViewPolicy.deviceLimit(this) < 2) {
            startActivity(android.content.Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java))
            finish()
            return
        }
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
        leftPlayer = newPlayer().also { binding.playerLeft.player = it }
        rightPlayer = newPlayer().also {
            binding.playerRight.player = it
            it.volume = 0f
        }
        leftPlayer?.volume = 1f
        leftPlayer?.addListener(bufferListener(binding.progressLeft))
        rightPlayer?.addListener(bufferListener(binding.progressRight))
        leftHealer = leftPlayer?.let { healerFor(it, "left") { s -> showSideStatus(0, s) } }
        rightHealer = rightPlayer?.let { healerFor(it, "right") { s -> showSideStatus(1, s) } }
        setupTileDrag()
        binding.root.post { updateActiveSideUI() } // wait for a layout pass so parent width/height are known
    }

    private fun newPlayer(): ExoPlayer {
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1920, 1080)
                .setMaxVideoBitrate(8_000_000).setExceedVideoConstraintsIfNecessary(false))
        }
        return ExoPlayer.Builder(this).setTrackSelector(selector)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 10_000, 750, 2_000)
                .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .build().apply { volume = 0f }
    }
    // ─── Fullscreen-primary + draggable PiP tile ────────────────────────────────

    private var tileX = -1f // -1 = not yet positioned (defaults to bottom-right on first layout)
    private var tileY = -1f

    private fun tileContainer() = if (activeSide == 0) binding.containerRight else binding.containerLeft
    private fun fullscreenContainer() = if (activeSide == 0) binding.containerLeft else binding.containerRight

    private fun setupTileDrag() {
        // Only the (small, precisely-targetable) tile swaps to fullscreen on tap — the
        // fullscreen container shares this same touch-handling shape (drag is a no-op there
        // since it's already match_parent) but a tap on IT reveals the controls bar instead.
        // Previously both containers used one identical listener where a tap on EITHER called
        // swapFullscreen(), so there was no way to tap the (much larger) fullscreen area to
        // bring controls back once the bar's 4s auto-hide timer elapsed — after picking the
        // first channel, the only touch target left was the tiny tile, which swapped instead of
        // revealing the Channels button, making the second stream unreachable.
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
                        if (v === tileContainer()) swapFullscreen() else showControls()
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

    /** One side's player that looks after itself; see TileHealer. */
    private fun healerFor(player: ExoPlayer, side: String, onStatus: (String?) -> Unit) = TileHealer(
        player = player,
        scope = lifecycleScope,
        tag = "MULTIVIEW $side",
        log = { com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, it) },
        onStatus = onStatus,
        onSwitched = { source ->
            if (side == "left") leftLabel = source.label else rightLabel = source.label
            val key = source.key as? Pair<*, *>
            if (key?.first is Int && key.second is Int) {
                selections[if (side == "left") 0 else 1] = Choice(key.first as Int, key.second as Int, source.label)
            }
        },
        backupAllowed = { prefs.smartAutoBackupNow() == com.iptvapp.util.SmartChannels.BACKUP_ON },
    )

    private fun showSideStatus(side: Int, status: String?) {
        val label = if (side == 0) leftLabel else rightLabel
        val text = if (status == null) label else "$label  ·  $status"
        if (side == 0) binding.tvLeftChannel.text = text else binding.tvRightChannel.text = text
    }

    private fun sourceFor(side: Int, serverIndex: Int, streamId: Int, label: String) = TileHealer.Source(
        key = serverIndex to streamId,
        label = label,
        freshUrl = {
            if (serverIndex != -1 && serverIndex !in prefs.enabledExtraServerIndices.first()) throw TileHealer.AdmissionDenied("Provider disabled")
            else if (!connections.acquire(side, serverIndex)) throw TileHealer.AdmissionDenied("Provider connection limit — choose another provider")
            else if (serverIndex == -1) repository.getLiveStreamUrl(streamId)
            else repository.getMergedLiveStreamUrl(serverIndex, streamId)
        },
    )

    private fun playChannel(channel: Choice) {
        val side = activeSide
        selections[side] = channel
        if (side == 0) leftLabel = channel.label else rightLabel = channel.label
        val healer = if (side == 0) leftHealer else rightHealer
        val session = com.iptvapp.util.SmartChannels.Session().apply {
            userTuned(com.iptvapp.util.SmartChannels.Key(channel.server, channel.streamId))
        }
        healer?.open(sourceFor(side, channel.server, channel.streamId, channel.label)) { tried ->
            val choice = smartChannels.choose(session, true, true,
                prefs.smartPreferHealthyNow() == com.iptvapp.util.SmartChannels.PREFER_SELECTED, System.currentTimeMillis())
            if (choice is com.iptvapp.util.SmartChannels.BackupChoice.Switch && (choice.serverIndex to choice.streamId) !in tried) {
                session.noteSwitch(com.iptvapp.util.SmartChannels.Key(choice.serverIndex, choice.streamId), System.currentTimeMillis())
                sourceFor(side, choice.serverIndex, choice.streamId, choice.displayName)
            } else null
        }
        showSideStatus(side, "Connecting…")
        hideChannelPicker()
        showControls()
    }

    private fun openLayouts() {
        val layout = com.iptvapp.ui.mosaic.MosaicLayout("Current", 2, selections.map { ch ->
            ch?.let { com.iptvapp.ui.mosaic.MosaicLayout.Tile(it.server, it.streamId, it.name) }
        }, activeSide)
        startActivity(android.content.Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java)
            .putExtra(com.iptvapp.ui.mosaic.MosaicActivity.EXTRA_LAYOUT,
                com.iptvapp.ui.mosaic.MosaicLayouts.encode(listOf(layout))))
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
                searchJob?.cancel()
                if (q.length >= 2) searchJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    val enabled = prefs.enabledExtraServerIndices.first()
                    val primary = repository.searchChannels(q).first().filter { !it.isHidden }.take(100)
                        .map { Choice(-1, it.streamId, it.name) }
                    val merged = repository.searchMergedChannelsOn(q, enabled, 100)
                        .map { Choice(it.serverIndex, it.streamId, it.name, it.serverNickname) }
                    pickerAdapter.submitList(primary + merged)
                }
            }
        })
    }

    private fun loadChannels() {
        lifecycleScope.launch {
            val channels = repository.getAllChannels().first()
            // Honors Settings' "USA Only" toggle, same as every other channel list in the app —
            // this screen's picker was pulling the raw unfiltered catalog with no regard for it.
            val usaOnly = prefs.usaOnlyChannels.first()
            allChannels = if (usaOnly) {
                val categoryNames = repository.getLiveCategories().first().associate { it.categoryId to it.categoryName }
                channels.filter { com.iptvapp.util.CategoryFilters.isUsCategory(categoryNames[it.categoryId]) }.map { Choice(-1, it.streamId, it.name) }
            } else {
                channels.map { Choice(-1, it.streamId, it.name) }
            }
            val primary = allChannels
            val enabled = prefs.enabledExtraServerIndices.first()
            val merged = repository.searchMergedChannelsOn("", enabled, 100)
            allChannels = primary + merged.map { Choice(it.serverIndex, it.streamId, it.name, it.serverNickname) }
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
        searchJob?.cancel()
        channelPickerVisible = false
        binding.channelPickerOverlay.visibility = View.GONE
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private fun switchToSide(side: Int) {
        activeSide = side
        // Tapping/swapping a tile is how you'd naturally expect to "switch to watching/listening
        // to that stream" — audio always follows whichever side is now fullscreen.
        audioSide = side
        leftPlayer?.volume = 0f; rightPlayer?.volume = 0f
        leftPlayer?.volume = if (audioSide == 0) 1f else 0f
        rightPlayer?.volume = if (audioSide == 1) 1f else 0f
        binding.btnMvAudio.text = "Audio: ${if (audioSide == 0) "Left" else "Right"}"
        updateActiveSideUI()
    }

    private fun toggleAudio() {
        audioSide = 1 - audioSide
        leftPlayer?.volume = 0f; rightPlayer?.volume = 0f
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
        binding.btnMvChannels.setOnLongClickListener { openLayouts(); true }
        binding.btnMvLayouts.setOnClickListener { openLayouts() }
        binding.btnMvChannels.contentDescription = "Choose channel; hold for saved layouts and four tiles"
        binding.btnMvBack.setOnClickListener { finish() }
        binding.btnMvAudio.setOnClickListener { toggleAudio() }
        // Only touch entry point into showChannelPicker() — the D-pad KEYCODE_DPAD_CENTER/ENTER
        // path in dispatchKeyEvent has no touch equivalent otherwise, so on phone every tile was
        // permanently stuck on its placeholder text with no way to actually pick a channel.
        binding.btnMvChannels.setOnClickListener { if (!channelPickerVisible) showChannelPicker() }
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
                KeyEvent.KEYCODE_MENU -> { openLayouts(); return true }
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

    override fun onPause() {
        leftHealer?.pause(); rightHealer?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        leftHealer?.resume(); rightHealer?.resume()
    }

    override fun onStop() {
        super.onStop()
        run {
            leftHealer?.pause(); rightHealer?.pause()
            leftPlayer?.pause(); rightPlayer?.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        leftPlayer?.play(); rightPlayer?.play()
        leftHealer?.resume(); rightHealer?.resume()
    }

    override fun onDestroy() {
        controlsHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        leftHealer?.release(); leftHealer = null
        rightHealer?.release(); rightHealer = null
        leftPlayer?.release(); leftPlayer = null
        rightPlayer?.release(); rightPlayer = null
    }

    // ─── Channel picker adapter ───────────────────────────────────────────────

    private inner class PickerAdapter(
        private val onClick: (Choice) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

        private var items = listOf<Choice>()
        fun submitList(list: List<Choice>) { items = list; notifyDataSetChanged() }
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
            (holder.itemView as TextView).text = ch.label
            holder.itemView.setOnClickListener { onClick(ch) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v)
    }
}
