package com.iptvapp.ui.mosaic

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ActivityMosaicBinding
import com.iptvapp.ui.home.HomeViewModel
import com.iptvapp.ui.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MosaicActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMosaicBinding
    private val viewModel: HomeViewModel by viewModels()

    private val players = mutableListOf<ExoPlayer>()
    private val cells = mutableListOf<MosaicCell>()
    private var gridSize = 4 // 4 or 6
    private var allMuted = false
    private var focusedCell = -1 // -1 = none focused

    private data class MosaicCell(
        val root: FrameLayout,
        val playerView: PlayerView,
        val tvName: TextView,
        val progress: ProgressBar,
        val focusRing: View,
        var channel: ChannelEntity? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMosaicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMosaicBack.setOnClickListener { finish() }
        binding.btnGridSize.setOnClickListener { cycleGridSize() }
        binding.btnMuteAll.setOnClickListener { toggleMuteAll() }

        setupGrid(gridSize)
        loadFavorites()
    }

    private fun loadFavorites() {
        viewModel.showFavoriteChannels()
        lifecycleScope.launch {
            val favs = viewModel.channels.first { it.isNotEmpty() }
            favs.take(gridSize).forEachIndexed { i, ch ->
                if (i < cells.size) loadChannel(i, ch)
            }
        }
    }

    private fun setupGrid(size: Int) {
        binding.mosaicGrid.removeAllViews()
        cells.forEach { it.playerView.player = null }
        players.forEach { it.release() }
        players.clear()
        cells.clear()
        focusedCell = -1

        val cols = if (size <= 4) 2 else 3
        val rows = if (size == 6) 2 else 2
        binding.mosaicGrid.columnCount = cols
        binding.mosaicGrid.rowCount = rows

        repeat(size) { index ->
            val player = ExoPlayer.Builder(this).build()
            players.add(player)
            val cell = buildCell(index, player, cols)
            cells.add(cell)
            binding.mosaicGrid.addView(cell.root)
        }
    }

    private fun buildCell(index: Int, player: ExoPlayer, cols: Int): MosaicCell {
        val dp2px = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }
        val margin = dp2px(2f)

        val root = FrameLayout(this).apply {
            layoutParams = GridLayout.LayoutParams().also { p ->
                p.columnSpec = GridLayout.spec(index % cols, 1, GridLayout.FILL, 1f)
                p.rowSpec = GridLayout.spec(index / cols, 1, GridLayout.FILL, 1f)
                p.width = 0
                p.height = 0
                p.setMargins(margin, margin, margin, margin)
            }
            setBackgroundColor(0xFF111111.toInt())
        }

        val playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            this.player = player
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

        val focusRing = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(android.R.drawable.picture_frame)
            visibility = View.GONE
        }

        val tvName = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setBackgroundColor(0xBB000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setPadding(dp2px(6f), dp2px(4f), dp2px(6f), dp2px(4f))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Loading…"
        }

        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            indeterminateTintList = ColorStateList.valueOf(0xFF008CFF.toInt())
            visibility = View.VISIBLE
        }

        root.addView(playerView)
        root.addView(focusRing)
        root.addView(tvName)
        root.addView(progress)

        val cell = MosaicCell(root, playerView, tvName, progress, focusRing)

        root.setOnClickListener {
            val ch = cell.channel ?: return@setOnClickListener
            if (focusedCell == index) {
                // Second tap → fullscreen
                lifecycleScope.launch {
                    val url = viewModel.getLiveStreamUrl(ch.streamId)
                    startActivity(Intent(this@MosaicActivity, PlayerActivity::class.java).apply {
                        putExtra("stream_url", url)
                        putExtra("stream_title", ch.name)
                        putExtra("stream_id", ch.streamId)
                    })
                }
            } else {
                setFocus(index)
            }
        }

        root.setOnLongClickListener {
            this@MosaicActivity.showChannelPicker(index)
            true
        }

        return cell
    }

    private fun setFocus(index: Int) {
        cells.forEachIndexed { i, cell ->
            val focused = i == index
            cell.focusRing.visibility = if (focused) View.VISIBLE else View.GONE
            // Mute non-focused cells, unmute focused
            if (!allMuted) {
                players.getOrNull(i)?.volume = if (focused) 1f else 0f
            }
        }
        focusedCell = index
    }

    private fun loadChannel(index: Int, channel: ChannelEntity) {
        val cell = cells.getOrNull(index) ?: return
        val player = players.getOrNull(index) ?: return
        cell.channel = channel
        cell.tvName.text = channel.name
        cell.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val url = viewModel.getLiveStreamUrl(channel.streamId)
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
            player.volume = if (allMuted || (focusedCell != -1 && focusedCell != index)) 0f else 1f

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    cell.progress.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
            })
        }
    }

    private fun showChannelPicker(cellIndex: Int) {
        lifecycleScope.launch {
            val channels = viewModel.channels.value.ifEmpty {
                viewModel.showFavoriteChannels()
                viewModel.channels.first { it.isNotEmpty() }
            }
            val names = channels.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MosaicActivity)
                .setTitle("Select Channel")
                .setItems(names) { _, which ->
                    loadChannel(cellIndex, channels[which])
                }
                .show()
        }
    }

    private fun cycleGridSize() {
        gridSize = if (gridSize == 4) 6 else 4
        binding.btnGridSize.text = if (gridSize == 4) "2×2" else "2×3"
        setupGrid(gridSize)
        loadFavorites()
    }

    private fun toggleMuteAll() {
        allMuted = !allMuted
        binding.btnMuteAll.text = if (allMuted) "🔊" else "🔇"
        players.forEachIndexed { i, player ->
            player.volume = when {
                allMuted -> 0f
                focusedCell == -1 -> 0f  // no focus = all muted by default
                i == focusedCell -> 1f
                else -> 0f
            }
        }
    }

    override fun onPause() {
        super.onPause()
        players.forEach { it.pause() }
    }

    override fun onResume() {
        super.onResume()
        players.forEach { it.play() }
    }

    override fun onDestroy() {
        cells.forEach { it.playerView.player = null }
        players.forEach { it.release() }
        players.clear()
        super.onDestroy()
    }
}
