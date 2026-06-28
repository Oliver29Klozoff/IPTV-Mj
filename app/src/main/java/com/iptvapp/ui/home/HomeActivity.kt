package com.iptvapp.ui.home

import com.iptvapp.R
import com.iptvapp.util.enableTvFocusHighlight
import com.iptvapp.util.isLargeScreenDevice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.iptvapp.databinding.ActivityHomeBinding
import com.iptvapp.ui.guide.GuideAdapter
import com.iptvapp.ui.player.MultiViewActivity
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.settings.SettingsActivity
import com.iptvapp.ui.settings.TvSettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.iptvapp.update.UpdateChecker
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.ui.guide.ChannelTimerScheduler
import com.iptvapp.ui.series.SeriesDetailActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    private var searchDebounceJob: kotlinx.coroutines.Job? = null
    private lateinit var binding: ActivityHomeBinding

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
            binding.etSearch.setText(text)
            dispatchSearch(text)
        }
    }
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (result.data?.getStringExtra("action")) {
                "whats_on" -> showWhatsOnNow()
            }
        }
        // Sync sort mode in case it changed in settings
        viewModel.setSortMode(
            when (viewModel.channelSort.value) {
                HomeViewModel.ChannelSort.DEFAULT -> 0
                HomeViewModel.ChannelSort.NAME_AZ -> 1
                HomeViewModel.ChannelSort.MOST_WATCHED -> 2
                HomeViewModel.ChannelSort.RECENTLY_WATCHED -> 3
            }
        )
    }
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var guideAdapter: GuideAdapter

    private var miniPlayer: ExoPlayer? = null
    private var currentMiniStreamId: Int = -1
    private var currentMiniUrl: String = ""
    private var currentMiniTitle: String = ""
    private var epgRefreshJob: kotlinx.coroutines.Job? = null
    private var isPipMode = false
    private var externalPlayerChoice = "internal"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        UpdateChecker(this).check(lifecycleScope)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (isLargeScreenDevice()) {
            binding.root.enableTvFocusHighlight()
        }
        setupRecyclerViews()
        setupTabs()
        setupSearch()
        setupMenu()
        observeViewModel()
        binding.rvChannels.visibility = android.view.View.INVISIBLE
        binding.rvCategories.visibility = android.view.View.INVISIBLE
        viewModel.loadAll()
        observeTabVisibility()
        binding.tabLayout.getTabAt(5)?.select()
        setupLandscapeSidebar()
    }

    private fun setupLandscapeSidebar() {
        val root = binding.root
        fun btn(id: Int) = root.findViewById<android.widget.Button?>(id)
        val tabs = listOf(
            btn(R.id.landBtnLive) to 0,
            btn(R.id.landBtnCategories) to 1,
            btn(R.id.landBtnMovies) to 2,
            btn(R.id.landBtnSeries) to 3,
            btn(R.id.landBtnWatching) to 4,
            btn(R.id.landBtnFavorites) to 5,
            btn(R.id.landBtnGuide) to 6
        )
        tabs.forEach { (button, index) ->
            button?.setOnClickListener {
                binding.tabLayout.getTabAt(index)?.select()
                tabs.forEach { (b, _) -> b?.setTextColor(0xFFAAAAAA.toInt()) }
                button.setTextColor(0xFF008CFF.toInt())
            }
        }
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                val idx = tab?.position ?: return
                tabs.forEach { (b, _) -> b?.setTextColor(0xFFAAAAAA.toInt()) }
                tabs.firstOrNull { it.second == idx }?.first?.setTextColor(0xFF008CFF.toInt())
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        // Sync initial highlight to tab 5 (Favorites)
        btn(R.id.landBtnFavorites)?.setTextColor(0xFF008CFF.toInt())
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val recent = viewModel.getRecentChannel()
            val isLive = currentMiniUrl.isNotEmpty() &&
                !currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
            when {
                recent != null && recent.streamId != currentMiniStreamId -> playInMiniPlayer(recent)
                isLive -> {
                    // Re-prepare so ExoPlayer re-fetches the manifest and starts at the real live edge
                    miniPlayer?.setMediaItem(androidx.media3.common.MediaItem.fromUri(currentMiniUrl))
                    miniPlayer?.prepare()
                    miniPlayer?.playWhenReady = true
                }
                else -> miniPlayer?.play()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (miniPlayer == null) {
            initMiniPlayer()
        } else {
            val isLive = currentMiniUrl.isNotEmpty() &&
                !currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
            if (!isLive) {
                // VOD: resume from current position; live streams handled in onResume
                miniPlayer?.play()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        miniPlayer?.release()
        miniPlayer = null
    }

    override fun onStop() {
        super.onStop()
        // Only pause if truly going to background, not when opening another activity
        if (!isChangingConfigurations) {
            miniPlayer?.pause()
        }
    }

    private fun initMiniPlayer() {
        miniPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.miniPlayerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.miniPlayerProgress.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
            })
        }
        binding.miniPlayerView.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId)
            }
        }
        binding.btnFullscreen.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                val isVodStream = currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = isVodStream, resumeMs = currentPos)
            }
        }
                loadLastWatchedChannel()
    }

    private fun loadLastWatchedChannel() {
        lifecycleScope.launch {
            val recent = viewModel.getRecentChannel()
            if (recent != null) {
                playInMiniPlayer(recent)
            }
        }
    }

    private fun playInMiniPlayer(channel: ChannelEntity) {
        lifecycleScope.launch {
            val url = viewModel.getLiveStreamUrl(channel.streamId)
            currentMiniUrl = url
            currentMiniTitle = channel.name
            currentMiniStreamId = channel.streamId
            binding.tvMiniChannelName.text = channel.name
            binding.tvPipChannelName?.text = channel.name

            if (!channel.streamIcon.isNullOrBlank()) {
                binding.ivHeroChannelLogo?.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(this@HomeActivity)
                    .load(channel.streamIcon)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .into(binding.ivHeroChannelLogo!!)
            } else {
                binding.ivHeroChannelLogo?.visibility = View.GONE
            }

            binding.btnHeroWatch?.setOnClickListener {
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId)
            }

            miniPlayer?.let {
                it.setMediaItem(MediaItem.fromUri(url))
                it.prepare()
                it.playWhenReady = true
            }
            refreshMiniEpg(channel.streamId)
            startEpgRefreshLoop(channel.streamId)
        }
    }

    private suspend fun refreshMiniEpg(streamId: Int) {
        val epg = viewModel.getEpgText(streamId)
        binding.tvMiniEpg.text = epg
        val desc = viewModel.getMiniEpgDescription(streamId)
        if (desc.isNotBlank()) {
            binding.tvHeroDescription?.text = desc
            binding.tvHeroDescription?.visibility = View.VISIBLE
        } else {
            binding.tvHeroDescription?.visibility = View.GONE
        }
        val progress = viewModel.getMiniEpgProgress(streamId)
        if (progress > 0) {
            binding.miniEpgProgress?.progress = progress
            binding.miniEpgProgress?.visibility = View.VISIBLE
        } else {
            binding.miniEpgProgress?.visibility = View.GONE
        }
    }

    private fun startEpgRefreshLoop(streamId: Int) {
        epgRefreshJob?.cancel()
        epgRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                if (currentMiniStreamId == streamId) refreshMiniEpg(streamId)
                else break
            }
        }
    }

    private fun setupMenu() {
        binding.btnWhatsOn?.setOnClickListener { showWhatsOnNow() }
        binding.btnSort?.setOnClickListener {
            viewModel.cycleSort()
            val label = when (viewModel.channelSort.value) {
                HomeViewModel.ChannelSort.DEFAULT -> "⇅ Default"
                HomeViewModel.ChannelSort.NAME_AZ -> "⇅ A-Z"
                HomeViewModel.ChannelSort.MOST_WATCHED -> "⇅ Popular"
                HomeViewModel.ChannelSort.RECENTLY_WATCHED -> "⇅ Recent"
            }
            binding.btnSort?.text = label
            Toast.makeText(this, "Sort: ${label.drop(2).trim()}", Toast.LENGTH_SHORT).show()
        }
        binding.btnMenu.setOnClickListener {
            val settingsClass = if (isLargeScreenDevice()) {
                TvSettingsActivity::class.java
            } else {
                SettingsActivity::class.java
            }
            settingsLauncher.launch(Intent(this, settingsClass))
        }
        binding.btnMultiView?.setOnClickListener {
            startActivity(Intent(this, MultiViewActivity::class.java))
        }
        binding.btnMosaic?.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java))
        }
        binding.btnCollapsePip?.setOnClickListener { togglePipMode() }
        binding.root.findViewById<android.widget.TextView?>(R.id.btnPipRestore)
            ?.setOnClickListener { togglePipMode() }
        binding.pipCorner?.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId)
            }
        }
    }

    private fun togglePipMode() {
        if (isPipMode) {
            binding.pipCornerView?.player = null
            binding.miniPlayerView?.player = miniPlayer
            binding.miniPlayerContainer?.visibility = View.VISIBLE
            binding.pipCorner?.visibility = View.GONE
            binding.btnCollapsePip?.text = "PiP ▼"
            isPipMode = false
        } else {
            binding.miniPlayerView?.player = null
            binding.pipCornerView?.player = miniPlayer
            binding.miniPlayerContainer?.visibility = View.GONE
            binding.pipCorner?.visibility = View.VISIBLE
            binding.tvPipChannelName?.text = currentMiniTitle
            isPipMode = true
        }
    }

    private fun setupRecyclerViews() {
        categoryAdapter = CategoryAdapter(
            onCategoryClick = { category ->
                when (binding.tabLayout.selectedTabPosition) {
                    0 -> viewModel.selectLiveCategory(category.categoryId)
                    1 -> viewModel.selectFavCategory(category.categoryId)
                    2 -> viewModel.selectVodCategory(category.categoryId)
                }
            },
            onCategoryLongClick = { category ->
                if (binding.tabLayout.selectedTabPosition == 0) {
                    viewModel.toggleLiveCategoryFavorite(category.categoryId)
                    Toast.makeText(this, "Category favorite updated", Toast.LENGTH_SHORT).show()
                }
            }
        )

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                lifecycleScope.launch {
                    playInMiniPlayer(channel)
                    viewModel.markChannelWatched(channel.streamId)
                    viewModel.setCurrentlyPlaying(channel.streamId)
                }
            },
            onChannelDoubleClick = { channel ->
                val currentIds = viewModel.channels.value.map { it.streamId }.toIntArray()
                lifecycleScope.launch {
                    val url = viewModel.getLiveStreamUrl(channel.streamId)
                    openPlayer(url, channel.name, channel.streamId, currentIds)
                }
            },
            onFavoriteClick = { channel ->
                viewModel.toggleChannelFavorite(channel.streamId)
                val msg = if (channel.isFavorite) "Removed from favorites" else "Added to favorites"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
            onChannelLongClick = { channel -> showReminderDialog(channel) }
        )

        vodAdapter = VodAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                    val progress = viewModel.getVodProgress(vod.streamId)
                    currentMiniUrl = url
                    currentMiniTitle = vod.name
                    currentMiniStreamId = vod.streamId
                    binding.tvMiniChannelName.text = vod.name
                    miniPlayer?.let {
                        it.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                        it.prepare()
                        it.playWhenReady = true
                    }
                    // Store VOD info for fullscreen button
                    binding.btnFullscreen.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                val isVodStream = currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = isVodStream, resumeMs = currentPos)
            }
        }
                }
            },
            onFavoriteClick = {}
        )

        seriesAdapter = SeriesAdapter(
            onSeriesClick = { series ->
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra("series_id", series.seriesId)
                    putExtra("series_name", series.name)
                    putExtra("series_cover", series.cover)
                    putExtra("series_genre", series.genre)
                    putExtra("series_rating", series.rating)
                    putExtra("series_plot", series.plot)
                })
            }
        )

        guideAdapter = GuideAdapter(
            onChannelClick = { row ->
                lifecycleScope.launch {
                    playInMiniPlayer(row.channel)
                    val url = viewModel.getLiveStreamUrl(row.channel.streamId)
                    openPlayer(url, row.channel.name, row.channel.streamId)
                }
            },
            onReplayClick = { row, program ->
                lifecycleScope.launch {
                    val startSec = if (program.startTimestamp < 100000000000L)
                        program.startTimestamp
                    else
                        program.startTimestamp / 1000L
                    val stopSec = if (program.stopTimestamp < 100000000000L)
                        program.stopTimestamp
                    else
                        program.stopTimestamp / 1000L
                    val durationMin = ((stopSec - startSec) / 60).toInt().coerceAtLeast(1)
                    val url = viewModel.getTimeshiftUrl(row.channel.streamId, startSec, durationMin)
                    val title = "${row.channel.name} — ${program.title}"
                    openPlayer(url, title, row.channel.streamId)
                }
            }
        )

        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = channelAdapter
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showLive()
                    1 -> showFavCategories()
                    2 -> showVod()
                    3 -> showSeries()
                    4 -> showWatching()
                    5 -> { showFavorites(); viewModel.checkFavoritesHealth() }
                    6 -> showGuide()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {
                if (tab?.position == 5) detachFavDrag()
                if (tab?.position == 6) binding.btnTimelineView?.visibility = View.GONE
            }
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            dispatchSearch(binding.etSearch.text.toString())
            true
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s.toString()
                binding.btnClearSearch?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                if (query.length >= 2 || query.isEmpty()) {
                    searchDebounceJob?.cancel()
                    searchDebounceJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(300)
                        dispatchSearch(query)
                    }
                }
            }
        })
        binding.btnClearSearch?.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.clearFocus()
        }
        binding.btnVoiceSearch?.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Search channels...")
            }
            try { voiceLauncher.launch(intent) } catch (e: Exception) {
                Toast.makeText(this, "Voice search not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dispatchSearch(query: String) {
        when (binding.tabLayout.selectedTabPosition) {
            2 -> viewModel.searchVod(query)
            else -> viewModel.searchChannels(query)
        }
    }
        private fun showLive() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        val cats = viewModel.liveCategories.value
        categoryAdapter.resetSelection()
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) {
            viewModel.selectLiveCategory(cats.first().categoryId)
        } else {
            viewModel.reloadCurrentLiveCategory()
        }
    }

    private fun showFavCategories() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        val favCats = viewModel.favoriteLiveCategories.value
        categoryAdapter.submitList(favCats)
        if (favCats.isNotEmpty()) {
            viewModel.selectFavCategory(favCats.first().categoryId)
        } else {
            channelAdapter.submitList(emptyList())
        }
    }

    private fun showVod() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = vodAdapter
        val cats = viewModel.vodCategories.value
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) viewModel.selectVodCategory(cats.first().categoryId)
    }

    private fun showSeries() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = seriesAdapter
        seriesAdapter.submitList(viewModel.series.value)
    }

    private fun showWatching() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = vodAdapter
        viewModel.showContinueWatching()
    }

    private var favItemTouchHelper: ItemTouchHelper? = null

    private fun showFavorites() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = channelAdapter
        viewModel.showFavoriteChannels()

        channelAdapter.showDragHandles = true
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private val dragList = mutableListOf<ChannelEntity>()

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val fromPos = from.bindingAdapterPosition
                val toPos = to.bindingAdapterPosition
                if (dragList.isEmpty()) dragList.addAll(channelAdapter.currentList)
                dragList.add(toPos, dragList.removeAt(fromPos))
                channelAdapter.submitList(dragList.toList())
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                if (dragList.isNotEmpty()) {
                    viewModel.saveFavOrder(dragList.map { it.streamId })
                    dragList.clear()
                }
            }
        }
        favItemTouchHelper = ItemTouchHelper(callback).also {
            channelAdapter.itemTouchHelper = it
            it.attachToRecyclerView(binding.rvChannels)
        }
    }

    private fun detachFavDrag() {
        channelAdapter.showDragHandles = false
        channelAdapter.itemTouchHelper = null
        favItemTouchHelper?.attachToRecyclerView(null)
        favItemTouchHelper = null
    }

    private fun showGuide() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = guideAdapter
        viewModel.loadGuide()
        binding.btnTimelineView?.visibility = View.VISIBLE
        binding.btnTimelineView?.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.guide.EpgTimelineActivity::class.java))
        }
    }

    private fun openPlayer(url: String, title: String, streamId: Int, streamIds: IntArray = viewModel.channels.value.map { it.streamId }.toIntArray(), isVod: Boolean = false, resumeMs: Long = 0L) {
        if (externalPlayerChoice != "internal") {
            launchExternalPlayer(url, title, externalPlayerChoice)
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("stream_title", title)
            putExtra("stream_id", streamId)
            putExtra("stream_ids", streamIds)
            putExtra("is_vod", isVod)
            putExtra("resume_ms", resumeMs)
        })
    }

    private fun launchExternalPlayer(url: String, title: String, player: String) {
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(url), "video/*")
            putExtra("title", title)
        }
        val pkg = when (player) {
            "vlc"      -> "org.videolan.vlc"
            "mxplayer" -> "com.mxtech.videoplayer.ad"
            else       -> null
        }
        try {
            startActivity(if (pkg != null) Intent(base).setPackage(pkg) else base)
        } catch (e: android.content.ActivityNotFoundException) {
            try { startActivity(base) } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No video player found", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeTabVisibility() {
        lifecycleScope.launch {
            viewModel.showMovies.collect { show: Boolean ->
                binding.tabLayout.getTabAt(2)?.view?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.showSeries.collect { show: Boolean ->
                binding.tabLayout.getTabAt(3)?.view?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.showWatching.collect { show: Boolean ->
                binding.tabLayout.getTabAt(4)?.view?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.loading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                if (!isLoading) {
                    binding.rvChannels.visibility = View.VISIBLE
                    // Only show categories panel on tabs that actually use it (Live=0, FavCat=1, VOD=2)
                    val tab = binding.tabLayout.selectedTabPosition
                    if (tab == 0 || tab == 1 || tab == 2) {
                        binding.rvCategories.visibility = View.VISIBLE
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.liveCategories.collect {
                if (binding.tabLayout.selectedTabPosition == 0) categoryAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteLiveCategories.collect { favs ->
                categoryAdapter.submitFavoriteCategoryIds(favs.map { it.categoryId }.toSet())
                if (binding.tabLayout.selectedTabPosition == 1) {
                    categoryAdapter.submitList(favs)
                    if (favs.isNotEmpty()) viewModel.selectFavCategory(favs.first().categoryId)
                    else channelAdapter.submitList(emptyList())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collect {
                channelAdapter.submitList(it)
                viewModel.loadEpgForChannels(it)
            }
        }
        lifecycleScope.launch {
            viewModel.vod.collect {
                if (binding.tabLayout.selectedTabPosition == 2) vodAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                if (binding.tabLayout.selectedTabPosition == 3) seriesAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.guideRows.collect {
                guideAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.currentlyPlayingStreamId.collect { streamId ->
                channelAdapter.setCurrentlyPlayingStreamId(streamId)
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect {
                channelAdapter.submitEpgText(it)
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgProgress.collect {
                channelAdapter.submitEpgProgress(it)
            }
        }
        lifecycleScope.launch {
            viewModel.vodCategories.collect {
                if (binding.tabLayout.selectedTabPosition == 2) categoryAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.continueWatching.collect { list ->
                if (binding.tabLayout.selectedTabPosition == 4) vodAdapter.submitList(list)
            }
        }
        lifecycleScope.launch {
            viewModel.channelHealth.collect { channelAdapter.submitHealth(it) }
        }
        lifecycleScope.launch {
            viewModel.externalPlayer.collect { externalPlayerChoice = it }
        }
    }

    private fun showReminderDialog(channel: ChannelEntity) {
        lifecycleScope.launch {
            val nowSec = System.currentTimeMillis() / 1000
            val epgList = try {
                viewModel.getUpcomingEpg(channel.streamId)
            } catch (_: Exception) { emptyList() }

            if (epgList.isEmpty()) {
                val options = arrayOf("In 15 minutes", "In 30 minutes", "In 1 hour", "In 2 hours")
                val deltas = longArrayOf(15 * 60 * 1000L, 30 * 60 * 1000L, 60 * 60 * 1000L, 120 * 60 * 1000L)
                androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                    .setTitle("Remind me about ${channel.name}")
                    .setItems(options) { _, i ->
                        ChannelTimerScheduler.schedule(
                            this@HomeActivity, channel.streamId, channel.name,
                            channel.name, System.currentTimeMillis() + deltas[i]
                        )
                        Toast.makeText(this@HomeActivity, "Reminder set for ${options[i]}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
                return@launch
            }

            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val labels = epgList.map { epg ->
                val startMs = if (epg.startTimestamp > 1_000_000_000_000L) epg.startTimestamp else epg.startTimestamp * 1000L
                val minUntil = ((startMs - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                val timeStr = if (minUntil == 0L) "Now" else "in ${minUntil}min"
                "${epg.title} (${fmt.format(Date(startMs))} — $timeStr)"
            }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("Remind me — ${channel.name}")
                .setItems(labels) { _, i ->
                    val epg = epgList[i]
                    val startMs = if (epg.startTimestamp > 1_000_000_000_000L) epg.startTimestamp else epg.startTimestamp * 1000L
                    ChannelTimerScheduler.schedule(
                        this@HomeActivity, channel.streamId, channel.name, epg.title, startMs
                    )
                    Toast.makeText(this@HomeActivity, "Reminder set for ${epg.title}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun showWhatsOnNow() {
        val channels = viewModel.channels.value.ifEmpty { return }
        val epgTextMap = viewModel.channelEpgText.value
        val epgProgressMap = viewModel.channelEpgProgress.value

        val withProgram = channels.filter { epgTextMap[it.streamId]?.isNotBlank() == true }
            .ifEmpty { channels }

        val inflater = layoutInflater
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@HomeActivity)
            setPadding(0, 8, 0, 8)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("What's On Now")
            .setView(rv)
            .setNegativeButton("Close", null)
            .create()

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class VH(val v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
            override fun getItemCount() = withProgram.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                val view = inflater.inflate(com.iptvapp.R.layout.item_whats_on, parent, false)
                return VH(view)
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val ch = withProgram[position]
                val v = holder.itemView
                v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonChannel).text = ch.name
                v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonProgram).text = epgTextMap[ch.streamId] ?: ""
                val progress = epgProgressMap[ch.streamId] ?: 0
                val pb = v.findViewById<android.widget.ProgressBar>(com.iptvapp.R.id.pbWonProgress)
                pb.progress = progress
                pb.visibility = if (progress > 0) android.view.View.VISIBLE else android.view.View.INVISIBLE
                com.bumptech.glide.Glide.with(v)
                    .load(ch.streamIcon)
                    .placeholder(android.R.drawable.ic_media_play)
                    .into(v.findViewById(com.iptvapp.R.id.ivWonLogo))
                v.setOnClickListener {
                    dialog.dismiss()
                    lifecycleScope.launch {
                        playInMiniPlayer(ch)
                        viewModel.markChannelWatched(ch.streamId)
                        viewModel.setCurrentlyPlaying(ch.streamId)
                    }
                }
            }
        }
        rv.adapter = adapter
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.75).toInt()
        )
    }
}