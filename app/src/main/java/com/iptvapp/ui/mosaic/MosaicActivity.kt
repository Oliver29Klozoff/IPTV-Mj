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

    companion object {
        /** A MosaicLayouts JSON list; its first layout opens as a one-off grid. */
        const val EXTRA_LAYOUT = "mosaic_layout"
        /** Channel picker search: at most this many from the main provider, and as many from the others. */
        private const val SEARCH_LIMIT = 100
    }

    private lateinit var binding: ActivityMosaicBinding
    private val viewModel: HomeViewModel by viewModels()
    @javax.inject.Inject lateinit var okHttpClient: okhttp3.OkHttpClient
    @javax.inject.Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    @javax.inject.Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    private val players = mutableListOf<ExoPlayer>()
    // One per tile: reloads that tile alone when it errors, sticks buffering, or freezes.
    private val healers = mutableListOf<com.iptvapp.ui.player.TileHealer>()
    private val cells = mutableListOf<MosaicCell>()
    private var gridSize = 4 // 2, 4, or 6
    private var allMuted = false
    private var focusedCell = -1 // -1 = none focused
    /** Bumped by every grid rebuild, so a layout or favorites load still running can tell it is stale. */
    private var gridGeneration = 0

    // Mosaic's channel picker previously only ever listed primary-provider favorites (viewModel
    // .channels is documented as primary-only) — a merged/extra-provider favorite couldn't be
    // put in a Mosaic tile at all. This wraps whichever kind is actually loaded into a cell so
    // loadChannel/fullscreen-launch can resolve the right stream-URL call for either.
    private sealed class MosaicChannel(val name: String, val streamId: Int) {
        class Primary(val entity: ChannelEntity) : MosaicChannel(entity.name, entity.streamId)
        class Merged(val entity: com.iptvapp.data.local.entities.MergedChannelEntity) :
            MosaicChannel(entity.name, entity.streamId)
    }

    private data class MosaicCell(
        val root: FrameLayout,
        val playerView: PlayerView,
        val tvName: TextView,
        val progress: ProgressBar,
        val focusRing: View,
        var channel: MosaicChannel? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMosaicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMosaicBack.setOnClickListener { finish() }
        binding.btnGridSize.setOnClickListener { cycleGridSize() }
        binding.btnMuteAll.setOnClickListener { toggleMuteAll() }
        binding.btnLayouts.setOnClickListener { showLayoutsMenu() }

        // Opens on a grid handed in (the Sports screen's live games), else the layout used last,
        // if it still exists, else the first favorites. A handed-in grid is not saved anywhere.
        val handedIn = MosaicLayouts.decode(intent.getStringExtra(EXTRA_LAYOUT)).firstOrNull()
        lifecycleScope.launch {
            if (handedIn != null) {
                applyLayout(handedIn)
                return@launch
            }
            val lastName = prefs.mosaicLastLayout.first()
            val last = MosaicLayouts.decode(prefs.mosaicLayouts.first())
                .firstOrNull { it.name.equals(lastName, ignoreCase = true) }
            if (last != null) applyLayout(last) else {
                setupGrid(gridSize)
                loadFavorites()
            }
        }
    }

    private fun loadFavorites() {
        viewModel.showFavoriteChannels()
        val grid = gridGeneration
        lifecycleScope.launch {
            val favs = viewModel.channels.first { it.isNotEmpty() }
            // A layout opened while favorites loaded owns the grid now.
            if (grid != gridGeneration) return@launch
            favs.take(gridSize).forEachIndexed { i, ch ->
                if (i < cells.size) loadChannel(i, MosaicChannel.Primary(ch))
            }
        }
    }

    private fun setupGrid(size: Int) {
        gridGeneration++
        binding.mosaicGrid.removeAllViews()
        healers.forEach { it.release() }
        healers.clear()
        cells.forEach { it.playerView.player = null }
        players.forEach { it.release() }
        players.clear()
        cells.clear()
        focusedCell = -1

        val cols = if (size <= 4) 2 else 3
        val rows = if (size == 2) 1 else 2
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
            // Added once per player. loadChannel used to add these on every channel change,
            // so a tile changed a few times reported each error once per old channel.
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    cell.progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "MOSAIC PLAYER ERROR: cell=$index streamId=${cell.channel?.streamId} " +
                            "errorCode=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName} " +
                            "message=${error.message}"
                    )
                }
            })
            healers.add(
                com.iptvapp.ui.player.TileHealer(
                    player = player,
                    scope = lifecycleScope,
                    tag = "MOSAIC cell=$index",
                    log = { com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, it) },
                    onStatus = { status ->
                        val label = cell.channel?.let { labelOf(it) } ?: ""
                        cell.tvName.text = if (status == null) label else "$label  ·  $status"
                    },
                    onSwitched = { source ->
                        (source.payload as? MosaicChannel)?.let { cell.channel = it }
                    },
                    backupAllowed = { prefs.autoBackupFeedNow() },
                )
            )
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
                    when (ch) {
                        is MosaicChannel.Primary -> {
                            val url = viewModel.getLiveStreamUrl(ch.streamId)
                            startActivity(Intent(this@MosaicActivity, PlayerActivity::class.java).apply {
                                putExtra("stream_url", url)
                                putExtra("stream_title", ch.name)
                                putExtra("stream_id", ch.streamId)
                            })
                        }
                        is MosaicChannel.Merged -> {
                            val url = viewModel.getMergedLiveStreamUrl(ch.entity.serverIndex, ch.entity.streamId)
                            startActivity(Intent(this@MosaicActivity, PlayerActivity::class.java).apply {
                                putExtra("stream_url", url)
                                putExtra("stream_title", "${ch.name} · ${ch.entity.serverNickname}")
                                putExtra("stream_id", -1)
                                putExtra("server_index", ch.entity.serverIndex)
                                putExtra("merged_stream_id", ch.entity.streamId)
                            })
                        }
                    }
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

    private fun loadChannel(index: Int, channel: MosaicChannel) {
        val cell = cells.getOrNull(index) ?: return
        val player = players.getOrNull(index) ?: return
        cell.channel = channel
        cell.tvName.text = labelOf(channel)
        cell.progress.visibility = View.VISIBLE
        healers.getOrNull(index)?.watch(sourceOf(channel)) { tried -> backupFor(cell, tried) }

        lifecycleScope.launch {
            val url = urlOf(channel)
            // The user may have picked another channel for this tile while the URL was built.
            if (cell.channel !== channel) return@launch
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
            player.volume = if (allMuted || (focusedCell != -1 && focusedCell != index)) 0f else 1f
        }
    }

    private fun labelOf(ch: MosaicChannel) = when (ch) {
        is MosaicChannel.Primary -> ch.name
        is MosaicChannel.Merged -> "${ch.name} · ${ch.entity.serverNickname}"
    }

    private fun keyOf(ch: MosaicChannel): Pair<Int, Int> = when (ch) {
        is MosaicChannel.Primary -> -1 to ch.streamId
        is MosaicChannel.Merged -> ch.entity.serverIndex to ch.entity.streamId
    }

    private suspend fun urlOf(ch: MosaicChannel): String = when (ch) {
        is MosaicChannel.Primary -> viewModel.getLiveStreamUrl(ch.streamId)
        is MosaicChannel.Merged -> viewModel.getMergedLiveStreamUrl(ch.entity.serverIndex, ch.entity.streamId)
    }

    private fun sourceOf(ch: MosaicChannel) = com.iptvapp.ui.player.TileHealer.Source(
        key = keyOf(ch),
        label = labelOf(ch),
        payload = ch,
        freshUrl = { urlOf(ch) },
    )

    /** Same pick as the full-screen backup feed: the best-scoring other copy, on any provider. */
    private suspend fun backupFor(cell: MosaicCell, tried: Set<Any>): com.iptvapp.ui.player.TileHealer.Source? {
        val now = cell.channel ?: return null
        val (server, id) = keyOf(now)
        val skip = tried.filterIsInstance<Pair<Int, Int>>().toSet()
        val best = repository.findBackupFeed(now.name, server, id, skip) ?: return null
        val next = resolveChannel(best.serverIndex, best.streamId) ?: return null
        return sourceOf(next)
    }

    private suspend fun resolveChannel(serverIndex: Int, streamId: Int): MosaicChannel? =
        if (serverIndex == -1) repository.getChannelById(streamId)?.let { MosaicChannel.Primary(it) }
        else repository.getMergedChannelByIndexAndId(serverIndex, streamId)?.let { MosaicChannel.Merged(it) }

    // ─── Saved layouts ───────────────────────────────────────────────────────

    private fun gridLabel(size: Int) = when (size) {
        2 -> "1×2"
        4 -> "2×2"
        else -> "2×3"
    }

    private fun showLayoutsMenu() {
        lifecycleScope.launch {
            val saved = MosaicLayouts.decode(prefs.mosaicLayouts.first())
            val items = listOf("Save this layout…") + saved.map { "${it.name}  (${gridLabel(it.gridSize)})" } +
                (if (saved.isNotEmpty()) listOf("Delete a layout…") else emptyList())
            AlertDialog.Builder(this@MosaicActivity)
                .setTitle("Layouts")
                .setItems(items.toTypedArray()) { _, which ->
                    when {
                        which == 0 -> promptSaveLayout(saved)
                        which <= saved.size -> lifecycleScope.launch {
                            val layout = saved[which - 1]
                            prefs.setMosaicLastLayout(layout.name)
                            applyLayout(layout)
                        }
                        else -> showDeleteLayout(saved)
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun promptSaveLayout(saved: List<MosaicLayout>) {
        val input = android.widget.EditText(this).apply {
            hint = "Name, like NFL Sunday"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(MosaicLayouts.MAX_NAME))
        }
        val box = FrameLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Save layout")
            .setMessage("Saves the grid size and the channel in each tile.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val tiles = cells.map { c ->
                    c.channel?.let { ch ->
                        val (server, id) = keyOf(ch)
                        MosaicLayout.Tile(server, id, ch.name)
                    }
                }
                val replacing = saved.any { it.name.equals(name, ignoreCase = true) }
                lifecycleScope.launch {
                    prefs.setMosaicLayouts(MosaicLayouts.encode(MosaicLayouts.upsert(saved, MosaicLayout(name, gridSize, tiles))))
                    prefs.setMosaicLastLayout(name)
                    android.widget.Toast.makeText(
                        this@MosaicActivity,
                        if (replacing) "Updated \"$name\"" else "Saved \"$name\"",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun showDeleteLayout(saved: List<MosaicLayout>) {
        AlertDialog.Builder(this)
            .setTitle("Delete a layout")
            .setItems(saved.map { it.name }.toTypedArray()) { _, which ->
                val name = saved[which].name
                lifecycleScope.launch {
                    prefs.setMosaicLayouts(MosaicLayouts.encode(MosaicLayouts.remove(saved, name)))
                    if (prefs.mosaicLastLayout.first().equals(name, ignoreCase = true)) prefs.setMosaicLastLayout("")
                    android.widget.Toast.makeText(this@MosaicActivity, "Deleted \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Rebuilds the grid at the layout's size and loads each saved tile. A saved copy whose name
     * no longer matches (the provider's login changed and the id now means another channel), or
     * that is gone, is replaced by the best copy of the saved channel name on any provider.
     */
    private suspend fun applyLayout(layout: MosaicLayout) {
        gridSize = layout.gridSize
        binding.btnGridSize.text = gridLabel(gridSize)
        setupGrid(gridSize)
        val grid = gridGeneration
        layout.tiles.forEachIndexed { i, tile ->
            if (tile == null || i >= cells.size) return@forEachIndexed
            val exact = try { resolveChannel(tile.serverIndex, tile.streamId) } catch (_: Exception) { null }
            val ch = exact?.takeIf {
                tile.channelName.isBlank() || com.iptvapp.util.ChannelNameMatcher.matches(it.name, tile.channelName)
            } ?: try {
                // -2 excludes no provider: every copy of the name is a candidate.
                repository.findFailoverChannel(tile.channelName, excludeServerIndex = -2)
                    ?.let { (server, id, _) -> resolveChannel(server, id) }
            } catch (_: Exception) { null }
            // The grid was rebuilt (another layout, or a new size) while this looked: stop here.
            if (grid != gridGeneration) return
            // The rebuilt grid starts empty. A tile with a channel already was picked by hand
            // while this looked, and that pick wins.
            if (cells[i].channel != null) return@forEachIndexed
            if (ch != null) loadChannel(i, ch)
            else cells[i].tvName.text = "${tile.channelName.ifBlank { "Channel" }} is no longer available"
        }
        cells.forEach { if (it.channel == null) it.progress.visibility = View.GONE }
    }

    // A plain AlertDialog.setItems() list of every favorite's name was fine for a handful of
    // channels, but unusable once someone has 50+ favorites with no way to filter — mirrors
    // the search-over-a-ListView pattern RecordingSchedulerActivity's channel picker already
    // uses elsewhere in the app. Now also includes merged/extra-provider favorites, not just
    // primary — previously a favorite from a secondary provider couldn't be put in a tile at all.
    private fun showChannelPicker(cellIndex: Int) {
        lifecycleScope.launch {
            val primaryChannels = viewModel.channels.value.ifEmpty {
                viewModel.showFavoriteChannels()
                viewModel.channels.first { it.isNotEmpty() }
            }
            val mergedChannels = viewModel.getMergedFavoriteChannelsSnapshot()
            val channels: List<MosaicChannel> =
                primaryChannels.map { MosaicChannel.Primary(it) } + mergedChannels.map { MosaicChannel.Merged(it) }
            var filtered = channels

            val layout = android.widget.LinearLayout(this@MosaicActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 16, 32, 0)
            }
            val etSearch = android.widget.EditText(this@MosaicActivity).apply {
                hint = "Search all providers…"
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

            fun labelFor(ch: MosaicChannel) = when (ch) {
                is MosaicChannel.Primary -> ch.name
                is MosaicChannel.Merged -> "${ch.name} (${ch.entity.serverNickname})"
            }
            fun show(list: List<MosaicChannel>) {
                filtered = list
                listView.adapter = android.widget.ArrayAdapter(
                    this@MosaicActivity, android.R.layout.simple_list_item_1, list.map { labelFor(it) }
                )
            }
            // Empty: your favorites. Typed: every channel on every provider that's on (the
            // catalogs' own name search), favorites that match first, then the main provider,
            // then the others. Each key press replaces the search still running.
            var searchJob: kotlinx.coroutines.Job? = null
            fun rebuildList() {
                val q = etSearch.text.toString().trim()
                searchJob?.cancel()
                if (q.length < 2) {
                    show(if (q.isBlank()) channels else channels.filter { it.name.contains(q, ignoreCase = true) })
                    return
                }
                searchJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    val found = try {
                        val enabled = prefs.enabledExtraServerIndices.first()
                        val primary = repository.searchChannels(q).first().filter { !it.isHidden }.take(SEARCH_LIMIT)
                        val others = repository.searchMergedChannels(q).first()
                            .filter { !it.isHidden && it.serverIndex in enabled }.take(SEARCH_LIMIT)
                        primary.map { MosaicChannel.Primary(it) } + others.map { MosaicChannel.Merged(it) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        emptyList()
                    }
                    val favorites = channels.filter { it.name.contains(q, ignoreCase = true) }
                    val favoriteKeys = favorites.map { keyOf(it) }.toSet()
                    show(favorites + found.filter { keyOf(it) !in favoriteKeys })
                }
            }
            dialog.setOnDismissListener { searchJob?.cancel() }
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
        gridSize = when (gridSize) {
            2 -> 4
            4 -> 6
            else -> 2
        }
        binding.btnGridSize.text = gridLabel(gridSize)
        // A new grid is no longer the saved layout. Next time opens on favorites again.
        lifecycleScope.launch { prefs.setMosaicLastLayout("") }
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
        healers.forEach { it.pause() }
        players.forEach { it.pause() }
    }

    override fun onResume() {
        super.onResume()
        players.forEach { it.play() }
        healers.forEach { it.resume() }
    }

    override fun onDestroy() {
        healers.forEach { it.release() }
        healers.clear()
        cells.forEach { it.playerView.player = null }
        players.forEach { it.release() }
        players.clear()
        super.onDestroy()
    }
}
