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
    @javax.inject.Inject lateinit var okHttpClient: okhttp3.OkHttpClient

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

        // See HomeActivity.initMiniPlayer's kdoc — ExoPlayer's default User-Agent gets
        // blocked by some Cloudflare-fronted IPTV CDNs on the stream endpoint specifically.
        val upstreamDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${android.os.Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(upstreamDataSourceFactory)
        repeat(size) { index ->
            val player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
            players.add(player)
            val cell = buildCell(index, player, cols)
            cells.add(cell)
            binding.mosaicGrid.addView(cell.root)
        }

        // Default to the first tile focused, not none — loadChannel() only mutes a cell when
        // focusedCell is set to something else, so with no focus at all every tile played its
        // audio simultaneously the instant channels loaded in.
        setFocus(0)
        cells.getOrNull(0)?.root?.requestFocus()
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
            // Built with only touch in mind originally — not reachable via D-pad at all on TV
            // without these, since a plain FrameLayout isn't focusable by default.
            isFocusable = true
            isFocusableInTouchMode = false
        }

        val playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            this.player = player
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        }

        val focusRing = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // android.R.drawable.picture_frame (a legacy Gallery-app decorative border) is
            // largely opaque white, not a thin outline — showing it on tap made the tapped
            // tile look like it had gone blank/white, when the video was actually still
            // playing underneath it the whole time. Use the same transparent-fill/blue-stroke
            // selection style Multi-View already uses.
            setBackgroundResource(com.iptvapp.R.drawable.player_active_border)
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

        // D-pad moves View focus between cells automatically (GridLayout + isFocusable),
        // but until now nothing reacted to that — only a tap/click called setFocus(), so the
        // highlight ring never followed the D-pad cursor, making it "hard to tell where the
        // dpad is." Route D-pad focus into the same setFocus() path used for taps, so the
        // ring (and audio-follows-focus) tracks the actual key-navigation cursor.
        root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setFocus(index)
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
                // Mosaic previously logged nothing at all — any failure (very plausible given
                // it opens several simultaneous connections on one account, which can exceed a
                // provider's concurrent-connection limit) left zero trace anywhere.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "MOSAIC PLAYER ERROR: cell=$index streamId=${channel.streamId} " +
                            "errorCode=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName} " +
                            "message=${error.message} url=$url"
                    )
                }
            })
        }
    }

    // A plain AlertDialog.setItems() list of every favorite's name was fine for a handful of
    // channels, but unusable once someone has 50+ favorites with no way to filter — mirrors
    // the search-over-a-ListView pattern RecordingSchedulerActivity's channel picker already
    // uses elsewhere in the app.
    private fun showChannelPicker(cellIndex: Int) {
        lifecycleScope.launch {
            val channels = viewModel.channels.value.ifEmpty {
                viewModel.showFavoriteChannels()
                viewModel.channels.first { it.isNotEmpty() }
            }
            var filtered = channels

            val layout = android.widget.LinearLayout(this@MosaicActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 16, 32, 0)
            }
            val etSearch = android.widget.EditText(this@MosaicActivity).apply {
                hint = "Search channels…"
                setSingleLine()
            }
            layout.addView(etSearch)
            val listView = android.widget.ListView(this@MosaicActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.5f).toInt()
                )
            }
            layout.addView(listView)

            val dialog = AlertDialog.Builder(this@MosaicActivity)
                .setTitle("Select Channel")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .create()

            fun rebuildList() {
                val q = etSearch.text.toString().trim()
                filtered = if (q.isBlank()) channels else channels.filter { it.name.contains(q, ignoreCase = true) }
                listView.adapter = android.widget.ArrayAdapter(
                    this@MosaicActivity, android.R.layout.simple_list_item_1, filtered.map { it.name }
                )
            }
            listView.setOnItemClickListener { _, _, pos, _ ->
                filtered.getOrNull(pos)?.let { loadChannel(cellIndex, it) }
                dialog.dismiss()
            }
            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { rebuildList() }
            })
            rebuildList()
            dialog.show()
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
