package com.iptvapp.ui.mosaic

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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ActivityMosaicBinding
import com.iptvapp.ui.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive

@AndroidEntryPoint
class MosaicActivity : AppCompatActivity() {

    companion object {
        /** A MosaicLayouts JSON list; its first layout opens as a one-off grid. */
        const val EXTRA_LAYOUT = "mosaic_layout"
        const val EXTRA_STANDARD_LAYOUT = "standard_multiview_layout"
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
    @javax.inject.Inject lateinit var smartChannels: com.iptvapp.data.smart.SmartChannelStore
    private val connections by lazy { MultiViewConnections(repository) }
    private var gridSize = 2
    private var tileLimit = 2
    private var screenActive = false
    private var screenResumed = false
    private var layoutJob: kotlinx.coroutines.Job? = null
    private var resumeLayout: MosaicLayout? = null
    private var enlargedTile = -1
    private var pickerDialog: AlertDialog? = null
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
        val logo: android.widget.ImageView,
        var channel: MosaicChannel? = null,
        val session: com.iptvapp.util.SmartChannels.Session = com.iptvapp.util.SmartChannels.Session()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMosaicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMosaicBack.setOnClickListener { finish() }
        binding.btnGridSize.setOnClickListener { cycleGridSize() }
        binding.btnMuteAll.setOnClickListener { toggleMuteAll() }
        binding.btnLayouts.setOnClickListener { showLayoutsMenu() }

        tileLimit = MultiViewPolicy.deviceLimit(this)
        resumeLayout = MosaicLayouts.decode(savedInstanceState?.getString("grid_state") ?: intent.getStringExtra(EXTRA_LAYOUT)).firstOrNull()
        binding.btnMosaicBack.setOnClickListener { if (enlargedTile >= 0) restoreGrid() else finish() }
        binding.btnTileMenu.setOnClickListener { showTileMenu(focusedCell.coerceAtLeast(0)) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (enlargedTile >= 0) restoreGrid() else finish() }
        })
    }
    private fun loadFavorites() {
        val grid = gridGeneration
        lifecycleScope.launch {
            val enabled = prefs.enabledExtraServerIndices.first()
            val favs = repository.getFavoriteChannels().first().map { MosaicChannel.Primary(it) } +
                viewModel.getMergedFavoriteChannelsSnapshot().filter { it.serverIndex in enabled }.map { MosaicChannel.Merged(it) }
            if (!isActive || grid != gridGeneration || !screenActive) return@launch
            favs.take(gridSize).forEachIndexed { i, ch -> if (cells[i].channel == null) loadChannel(i, ch) }
            cells.filter { it.channel == null }.forEach { it.tvName.text = "Select channel"; it.progress.visibility = View.GONE }
        }
    }
    private fun setupGrid(requestedSize: Int) {
        val size = minOf(requestedSize, tileLimit)
        gridSize = size
        binding.btnGridSize.text = gridLabel(size)
        enlargedTile = -1
        pickerDialog?.dismiss()
        connections.clear()
        gridGeneration++
        binding.mosaicGrid.removeAllViews()
        healers.forEach { it.release() }
        healers.clear()
        cells.forEach { it.playerView.player = null }
        players.forEach { it.release() }
        players.clear()
        cells.clear()
        focusedCell = -1
        tileOrder.clear(); tileOrder.addAll(0 until size)

        val cols = if (size == 1) 1 else 2
        val rows = (size + cols - 1) / cols
        binding.mosaicGrid.columnCount = cols
        binding.mosaicGrid.rowCount = rows

        // See HomeActivity.initMiniPlayer's kdoc — ExoPlayer's default User-Agent gets
        // blocked by some Cloudflare-fronted IPTV CDNs on the stream endpoint specifically.
        val upstreamDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${android.os.Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(upstreamDataSourceFactory)
        repeat(size) { index ->
            val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
                setParameters(buildUponParameters().setMaxVideoSize(1920, 1080)
                    .setMaxVideoBitrate(8_000_000).setExceedVideoConstraintsIfNecessary(false))
            }
            val player = ExoPlayer.Builder(this).setTrackSelector(selector).setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3_000, 10_000, 750, 2_000)
                    .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
                .build().apply {
                    volume = 0f
                }
            players.add(player)
            val cell = buildCell(index, player, cols)
            cells.add(cell)
            binding.mosaicGrid.addView(cell.root)
            // Added once per player. loadChannel used to add these on every channel change,
            // so a tile changed a few times reported each error once per old channel.
            val playerGeneration = gridGeneration
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    cell.progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) cell.root.post { if (gridGeneration == playerGeneration) reduceCapacity() }
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "MOSAIC PLAYER ERROR: cell=$index streamId=${cell.channel?.streamId} " +
                            "errorCode=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName} " +
                            "detail=redacted"
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
                        cell.progress.visibility = if (status?.startsWith("Connecting") == true || status?.startsWith("Reconnecting") == true) View.VISIBLE else View.GONE
                    },
                    onSwitched = { source ->
                        (source.payload as? MosaicChannel)?.let { cell.channel = it }
                    },
                    backupAllowed = { prefs.smartAutoBackupNow() == com.iptvapp.util.SmartChannels.BACKUP_ON },
                )
            )
            if (!screenResumed) healers.last().pause()
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

        val logo = android.widget.ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp2px(48f), dp2px(32f), Gravity.TOP or Gravity.START)
            contentDescription = "Channel logo"
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        root.addView(logo)
        val cell = MosaicCell(root, playerView, tvName, progress, focusRing, logo)

        root.setOnClickListener {
            if (cell.channel == null) showChannelPicker(index)
            else if (focusedCell == index) enlarge(index) else setFocus(index)
        }
        root.setOnLongClickListener { showTileMenu(index); true }
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
        // Mute all first: moving from a later tile to an earlier tile never overlaps audio.
        MultiViewAudio.select(players, index, allMuted)
        focusedCell = index
        cells.forEachIndexed { i, cell -> cell.focusRing.visibility = if (i == index) View.VISIBLE else View.GONE }
    }

    private fun loadChannel(index: Int, channel: MosaicChannel) {
        val cell = cells.getOrNull(index) ?: return
        if (!screenActive) return
        cell.channel = channel
        val key = keyOf(channel)
        cell.session.userTuned(com.iptvapp.util.SmartChannels.Key(key.first, key.second))
        cell.tvName.text = labelOf(channel)
        cell.progress.visibility = View.VISIBLE
        val icon = when (channel) {
            is MosaicChannel.Primary -> channel.entity.streamIcon
            is MosaicChannel.Merged -> channel.entity.streamIcon
        }
        com.bumptech.glide.Glide.with(this).load(icon).into(cell.logo)
        healers.getOrNull(index)?.open(sourceOf(channel, index)) { tried -> backupFor(cell, index, tried) }
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

    private fun sourceOf(ch: MosaicChannel, index: Int) = com.iptvapp.ui.player.TileHealer.Source(
        key = keyOf(ch),
        label = labelOf(ch),
        payload = ch,
        freshUrl = {
            val server = keyOf(ch).first
            if (server != -1 && server !in prefs.enabledExtraServerIndices.first()) throw com.iptvapp.ui.player.TileHealer.AdmissionDenied("Provider disabled — choose another channel")
            else if (!connections.acquire(index, server)) throw com.iptvapp.ui.player.TileHealer.AdmissionDenied("Provider connection limit — choose another provider or retry later")
            else urlOf(ch)
        },
    )

    /** Only verified/manual Smart Channel members may replace a tile automatically. */
    private suspend fun backupFor(cell: MosaicCell, index: Int, tried: Set<Any>): com.iptvapp.ui.player.TileHealer.Source? {
        if (cell.channel == null) return null
        val choice = smartChannels.choose(cell.session, true, true,
            prefs.smartPreferHealthyNow() == com.iptvapp.util.SmartChannels.PREFER_SELECTED, System.currentTimeMillis())
        if (choice !is com.iptvapp.util.SmartChannels.BackupChoice.Switch || (choice.serverIndex to choice.streamId) in tried) return null
        val next = resolveChannel(choice.serverIndex, choice.streamId) ?: return null
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        cell.session.noteSwitch(com.iptvapp.util.SmartChannels.Key(choice.serverIndex, choice.streamId), System.currentTimeMillis())
        return sourceOf(next, index)
    }
    private suspend fun resolveChannel(serverIndex: Int, streamId: Int): MosaicChannel? =
        if (serverIndex == -1) repository.getChannelById(streamId)?.let { MosaicChannel.Primary(it) }
        else repository.getMergedChannelByIndexAndId(serverIndex, streamId)?.let { MosaicChannel.Merged(it) }

    // ─── Saved layouts ───────────────────────────────────────────────────────

    private fun gridLabel(size: Int) = when (size) {
        1 -> "1 tile"
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
                        which <= saved.size -> {
                            val layout = saved[which - 1]
                            layoutJob?.cancel()
                            layoutJob = lifecycleScope.launch {
                                prefs.setMosaicLastLayout(layout.name)
                                if (screenActive) applyLayout(layout)
                            }
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
                val layout = snapshot(name)
                val replacing = saved.any { it.name.equals(name, ignoreCase = true) }
                lifecycleScope.launch {
                    prefs.setMosaicLayouts(MosaicLayouts.encode(MosaicLayouts.upsert(saved, layout)))
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
     * that is gone, stays empty for the user to choose a replacement.
     */
    private suspend fun applyLayout(layout: MosaicLayout) {
        allMuted = layout.muted
        gridSize = layout.gridSize
        binding.btnGridSize.text = gridLabel(gridSize)
        setupGrid(gridSize)
        setFocus(layout.selectedTile.coerceIn(0, cells.lastIndex))
        cells[focusedCell].root.requestFocus()
        val grid = gridGeneration
        layout.tiles.forEachIndexed { i, tile ->
            if (tile == null || i >= cells.size) return@forEachIndexed
            val exact = try { resolveChannel(tile.serverIndex, tile.streamId) } catch (_: Exception) { null }
            val enabled = prefs.enabledExtraServerIndices.first()
            val ch = exact?.takeIf {
                (tile.serverIndex == -1 || tile.serverIndex in enabled) &&
                    (tile.channelName.isBlank() || com.iptvapp.util.ChannelNameMatcher.matches(it.name, tile.channelName))
            }
            // The grid was rebuilt (another layout, or a new size) while this looked: stop here.
            if (!kotlinx.coroutines.currentCoroutineContext().isActive || grid != gridGeneration || !screenActive) return
            // The rebuilt grid starts empty. A tile with a channel already was picked by hand
            // while this looked, and that pick wins.
            if (cells[i].channel != null) return@forEachIndexed
            if (ch != null) loadChannel(i, ch)
            else cells[i].tvName.text = "${tile.channelName.ifBlank { "Channel" }} is no longer available"
        }
        allMuted = layout.muted
        setFocus(layout.selectedTile.coerceIn(0, cells.lastIndex))
        cells[focusedCell].root.requestFocus()
        cells.forEach { if (it.channel == null) it.progress.visibility = View.GONE }
    }

    // A plain AlertDialog.setItems() list of every favorite's name was fine for a handful of
    // channels, but unusable once someone has 50+ favorites with no way to filter — mirrors
    // the search-over-a-ListView pattern RecordingSchedulerActivity's channel picker already
    // uses elsewhere in the app. Now also includes merged/extra-provider favorites, not just
    // primary — previously a favorite from a secondary provider couldn't be put in a tile at all.
    private fun showChannelPicker(cellIndex: Int) {
        lifecycleScope.launch {
            val grid = gridGeneration
            val primaryChannels = repository.getFavoriteChannels().first()
            val enabled = prefs.enabledExtraServerIndices.first()
            val mergedChannels = viewModel.getMergedFavoriteChannelsSnapshot().filter { it.serverIndex in enabled }
            if (!isActive || !screenActive || grid != gridGeneration) return@launch
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
                is MosaicChannel.Primary -> "${ch.name} (Primary)"
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
                        val others = repository.searchMergedChannelsOn(q, enabled, SEARCH_LIMIT)
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
                if (screenActive && grid == gridGeneration) filtered.getOrNull(pos)?.let { loadChannel(cellIndex, it) }
                dialog.dismiss()
            }
            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { rebuildList() }
            })
            rebuildList()
            pickerDialog?.dismiss()
            pickerDialog = dialog
            dialog.show()
        }
    }

    private val tileOrder = mutableListOf<Int>()

    private fun snapshot(name: String = "Current"): MosaicLayout = MosaicLayout(
        name, gridSize, tileOrder.map { index -> cells.getOrNull(index)?.channel?.let { ch ->
            val (server, id) = keyOf(ch)
            MosaicLayout.Tile(server, id, ch.name)
        } }, tileOrder.indexOf(focusedCell).coerceAtLeast(0), allMuted
    )

    private fun arrange() {
        val cols = if (gridSize == 1) 1 else 2
        // Remove/re-add only the views. Their players, streams and admission reservations survive.
        binding.mosaicGrid.removeAllViews()
        binding.mosaicGrid.columnCount = if (enlargedTile >= 0) 1 else cols
        binding.mosaicGrid.rowCount = if (enlargedTile >= 0) 1 else (gridSize + cols - 1) / cols
        val visible = if (enlargedTile >= 0) listOf(enlargedTile) else tileOrder
        for ((position, index) in visible.withIndex()) {
            val cell = cells[index]
            cell.root.layoutParams = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(if (enlargedTile >= 0) 0 else position % cols, 1, GridLayout.FILL, 1f)
                rowSpec = GridLayout.spec(if (enlargedTile >= 0) 0 else position / cols, 1, GridLayout.FILL, 1f)
                width = 0; height = 0; setMargins(2, 2, 2, 2)
            }
            binding.mosaicGrid.addView(cell.root)
        }
        cells.getOrNull(focusedCell)?.root?.requestFocus()
    }

    private fun enlarge(index: Int) {
        if (enlargedTile == index) { restoreGrid(); return }
        setFocus(index)
        enlargedTile = index
        healers.forEachIndexed { i, healer -> if (i == index) healer.resume() else healer.pause() }
        arrange()
    }

    private fun restoreGrid() { enlargedTile = -1; healers.forEach { it.resume() }; arrange() }

    private fun showTileMenu(index: Int) {
        if (index !in cells.indices) return
        setFocus(index)
        AlertDialog.Builder(this).setTitle("Tile ${tileOrder.indexOf(index) + 1}")
            .setItems(arrayOf("Choose channel / provider", "Listen to this tile", "Enlarge / restore", "Swap with another tile", "Save / open layouts", "Retry this tile")) { _, action ->
                if (!screenActive || index !in cells.indices) return@setItems
                when (action) {
                    0 -> showChannelPicker(index)
                    1 -> { allMuted = false; setFocus(index) }
                    2 -> enlarge(index)
                    3 -> {
                        val others = tileOrder.filter { it != index }
                        AlertDialog.Builder(this).setTitle("Swap tile positions")
                            .setItems(others.map { "Tile ${tileOrder.indexOf(it) + 1}" }.toTypedArray()) { _, picked ->
                                val from = tileOrder.indexOf(index)
                                val to = tileOrder.indexOf(others[picked])
                                if (from >= 0 && to >= 0 && screenActive) {
                                    java.util.Collections.swap(tileOrder, from, to)
                                    restoreGrid()
                                }
                            }.show()
                    }
                    4 -> showLayoutsMenu()
                    5 -> cells[index].channel?.let { loadChannel(index, it) }
                }
            }.setNegativeButton("Close", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) { showTileMenu(focusedCell.coerceAtLeast(0)); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun cycleGridSize() {
        val next = if (gridSize < minOf(4, tileLimit)) minOf(4, tileLimit) else minOf(2, tileLimit)
        val current = snapshot().copy(gridSize = next)
        layoutJob?.cancel()
        layoutJob = lifecycleScope.launch { prefs.setMosaicLastLayout(""); applyLayout(current) }
    }

    private fun toggleMuteAll() {
        allMuted = !allMuted
        binding.btnMuteAll.text = if (allMuted) "Unmute" else "Mute"
        setFocus(focusedCell)
    }

    private fun releaseGrid() {
        gridGeneration++
        pickerDialog?.dismiss()
        healers.forEach { it.release() }
        cells.forEach { it.playerView.player = null }
        players.forEach { it.release() }
        healers.clear(); players.clear(); cells.clear(); tileOrder.clear()
        binding.mosaicGrid.removeAllViews()
        connections.clear()
    }

    override fun onStart() {
        super.onStart()
        screenActive = true
        layoutJob = lifecycleScope.launch {
            val restore = resumeLayout
            resumeLayout = null
            val last = restore ?: if (intent.getBooleanExtra(EXTRA_STANDARD_LAYOUT, false)) null
                else MosaicLayouts.decode(prefs.mosaicLayouts.first())
                    .firstOrNull { it.name.equals(prefs.mosaicLastLayout.first(), ignoreCase = true) }
            if (!isActive || !screenActive) return@launch
            if (last != null) applyLayout(last) else { setupGrid(gridSize); loadFavorites() }
        }
    }

    override fun onPause() {
        screenResumed = false
        healers.forEach { it.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        screenResumed = true
        healers.forEachIndexed { index, healer -> if (enlargedTile < 0 || enlargedTile == index) healer.resume() }
    }

    override fun onStop() {
        screenActive = false
        if (cells.isNotEmpty()) resumeLayout = snapshot()
        layoutJob?.cancel()
        releaseGrid()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = if (cells.isNotEmpty()) snapshot() else resumeLayout
        state?.let { outState.putString("grid_state", MosaicLayouts.encode(listOf(it))) }
        super.onSaveInstanceState(outState)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) reduceCapacity()
    }

    private fun reduceCapacity() {
        if (!screenActive || players.size <= 1) return
        tileLimit = if (players.size > 2) 2 else 1
        restoreGrid()
        while (players.size > tileLimit) {
            val last = players.lastIndex
            healers.removeAt(last).release()
            cells.removeAt(last).let { it.playerView.player = null; binding.mosaicGrid.removeView(it.root) }
            players.removeAt(last).release()
            connections.release(last)
        }
        tileOrder.removeAll { it >= tileLimit }
        gridSize = tileLimit
        setFocus(focusedCell.coerceAtMost(tileLimit - 1))
        arrange()
        binding.btnGridSize.text = gridLabel(gridSize)
        android.widget.Toast.makeText(this, "Multi-View limited to $tileLimit tiles on this device", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        layoutJob?.cancel()
        releaseGrid()
        super.onDestroy()
    }
}
