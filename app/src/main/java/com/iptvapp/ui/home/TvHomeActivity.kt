package com.iptvapp.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptvapp.databinding.ActivityTvHomeBinding
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.series.SeriesDetailActivity
import com.iptvapp.ui.settings.TvSettingsActivity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.ui.guide.ChannelTimerScheduler
import com.iptvapp.tv.TvHomeChannelPublisher
import com.iptvapp.ui.onboarding.FeatureTourDialog
import com.iptvapp.update.UpdateChecker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class TvHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var epgGuideAdapter: TvEpgGuideAdapter

    private var preWarmEnabled = true
    private var preWarmJob: kotlinx.coroutines.Job? = null
    private var channelNumberBuffer = ""
    private var channelJumpJob: kotlinx.coroutines.Job? = null

    private var miniPlayer: ExoPlayer? = null
    private var currentMiniUrl: String = ""
    private var currentMiniTitle: String = ""
    private var currentMiniStreamId: Int = -1
    private var epgRefreshJob: kotlinx.coroutines.Job? = null
    private var searchDebounceJob: kotlinx.coroutines.Job? = null
    private var openPlayerJob: kotlinx.coroutines.Job? = null
    private var clockJob: kotlinx.coroutines.Job? = null
    private var autoPreviewJob: kotlinx.coroutines.Job? = null
    private var externalPlayerChoice = "internal"
    private var pendingContentFocus = false

    // Left-panel drill-down state
    private enum class NavState { SIDEBAR, CATEGORIES, CHANNELS }
    private var navState = NavState.SIDEBAR
    private var navHasCategoryStep = false

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val sid = result.data?.getIntExtra("stream_id", -1) ?: -1
            val url = result.data?.getStringExtra("stream_url") ?: return@registerForActivityResult
            val title = result.data?.getStringExtra("stream_title") ?: return@registerForActivityResult
            if (sid != -1 && url.isNotEmpty()) {
                currentMiniStreamId = sid
                currentMiniUrl = url
                currentMiniTitle = title
                binding.tvTvChannelName.text = title
                miniPlayer?.let {
                    it.setMediaItem(MediaItem.fromUri(url))
                    it.prepare()
                    it.playWhenReady = true
                }
                lifecycleScope.launch { refreshMiniEpg(sid) }
                startEpgRefreshLoop(sid)
            }
        }
    }

    private enum class Section { LIVE, CATEGORIES, MOVIES, SERIES, FAVORITES }
    private var currentSection = Section.FAVORITES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupAdapters()
        setupSidebar()
        setupSearch()
        setupMiniPlayer()
        observeViewModel()
        observeEpgGuide()
        observeSidebarVisibility()
        viewModel.loadAll()
        selectSection(Section.FAVORITES)
        handleDeepLink(intent)
        FeatureTourDialog.showIfNeeded(this)
        UpdateChecker(this).check(lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "mktv") return
        when (uri.host) {
            "play" -> {
                val streamId = uri.lastPathSegment?.toIntOrNull() ?: return
                lifecycleScope.launch {
                    val channel = viewModel.getChannelById(streamId) ?: return@launch
                    val url = channel.streamUrl ?: return@launch
                    openPlayer(url, channel.name, channel.streamId)
                }
            }
            "home" -> selectSection(Section.FAVORITES)
        }
    }

    override fun onResume() {
        super.onResume()
        com.iptvapp.update.UpdateChecker(this).resumeCheck(lifecycleScope)
        if (currentMiniUrl.isEmpty()) {
            lifecycleScope.launch {
                val recent = viewModel.getRecentChannel()
                if (recent != null) playInMiniPlayer(recent)
            }
        } else if (miniPlayer?.isPlaying == false) {
            if (miniPlayer?.playbackState == Player.STATE_IDLE) {
                miniPlayer?.setMediaItem(MediaItem.fromUri(currentMiniUrl))
                miniPlayer?.prepare()
            }
            miniPlayer?.play()
        }
        val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        clockJob = lifecycleScope.launch {
            while (true) {
                binding.tvClock.text = clockFmt.format(Date())
                delay(30_000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        clockJob?.cancel()
        autoPreviewJob?.cancel()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) miniPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        miniPlayer?.release()
        miniPlayer = null
    }

    // ── Mini player ──────────────────────────────────────────────────────────

    private fun setupMiniPlayer() {
        miniPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.tvMiniPlayerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.tvMiniPlayerProgress.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
            })
        }
        binding.tvMiniPlayerContainer.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId)
        }
        binding.btnTvFullscreen.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val pos = miniPlayer?.currentPosition ?: 0L
                val isVod = currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = isVod, resumeMs = pos)
            }
        }
        lifecycleScope.launch {
            val recent = viewModel.getRecentChannel()
            if (recent != null) playInMiniPlayer(recent)
        }
    }

    private fun playInMiniPlayer(channel: ChannelEntity) {
        lifecycleScope.launch {
            val url = viewModel.getLiveStreamUrl(channel.streamId)
            currentMiniUrl = url
            currentMiniTitle = channel.name
            currentMiniStreamId = channel.streamId
            binding.tvTvChannelName.text = channel.name
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
        binding.tvTvEpg.text = epg
        val progress = viewModel.getMiniEpgProgress(streamId)
        if (progress > 0) {
            binding.tvEpgProgress.progress = progress
            binding.tvEpgProgress.visibility = View.VISIBLE
        } else {
            binding.tvEpgProgress.visibility = View.GONE
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

    private fun preWarmChannel(channel: ChannelEntity) {
        preWarmJob?.cancel()
        preWarmJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = viewModel.getLiveStreamUrl(channel.streamId)
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 3000
                conn.readTimeout = 2000
                conn.instanceFollowRedirects = true
                conn.connect()
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    // ── Adapters ─────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        categoryAdapter = CategoryAdapter(
            onCategoryClick = { cat ->
                when (currentSection) {
                    Section.LIVE -> {
                        binding.tvRvContent.adapter = channelAdapter
                        viewModel.selectLiveCategory(cat.categoryId)
                    }
                    Section.CATEGORIES -> {
                        binding.tvRvContent.adapter = channelAdapter
                        viewModel.selectFavCategory(cat.categoryId)
                    }
                    Section.MOVIES -> {
                        binding.tvRvContent.adapter = vodAdapter
                        viewModel.selectVodCategory(cat.categoryId)
                    }
                    else -> {}
                }
                showChannelPanel(cat.categoryName)
            },
            onCategoryLongClick = { cat ->
                if (currentSection == Section.LIVE) {
                    viewModel.toggleLiveCategoryFavorite(cat.categoryId)
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
                val ids = viewModel.channels.value.map { it.streamId }.toIntArray()
                lifecycleScope.launch {
                    val url = viewModel.getLiveStreamUrl(channel.streamId)
                    openPlayer(url, channel.name, channel.streamId, ids)
                }
            },
            onFavoriteClick = { channel ->
                viewModel.toggleChannelFavorite(channel.streamId)
                val msg = if (channel.isFavorite) "Removed from favorites" else "Added to favorites"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
            onChannelLongClick = { channel -> showTvReminderDialog(channel) }
        )
        channelAdapter.isTvMode = true
        channelAdapter.onChannelFocused = { channel ->
            binding.tvTvChannelName.text = channel.name
            val epgText = viewModel.channelEpgText.value[channel.streamId]
            binding.tvTvEpg.text = epgText ?: ""
            val progress = viewModel.channelEpgProgress.value[channel.streamId] ?: 0
            if (progress > 0) {
                binding.tvEpgProgress.progress = progress
                binding.tvEpgProgress.visibility = View.VISIBLE
            } else {
                binding.tvEpgProgress.visibility = View.GONE
            }
            if (preWarmEnabled) preWarmChannel(channel)
        }

        vodAdapter = VodAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                    currentMiniUrl = url
                    currentMiniTitle = vod.name
                    currentMiniStreamId = vod.streamId
                    binding.tvTvChannelName.text = vod.name
                    miniPlayer?.let {
                        it.setMediaItem(MediaItem.fromUri(url))
                        it.prepare()
                        it.playWhenReady = true
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

        epgGuideAdapter = TvEpgGuideAdapter(
            onChannelClick = { channel ->
                lifecycleScope.launch {
                    playInMiniPlayer(channel)
                    viewModel.markChannelWatched(channel.streamId)
                    viewModel.setCurrentlyPlaying(channel.streamId)
                }
            }
        )

        binding.tvRvCategories.layoutManager = LinearLayoutManager(this)
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.layoutManager = LinearLayoutManager(this)
        binding.tvRvContent.adapter = channelAdapter
        binding.tvRvEpgGuide.layoutManager = LinearLayoutManager(this)
        binding.tvRvEpgGuide.adapter = epgGuideAdapter
    }

    // ── Left panel drill-down ────────────────────────────────────────────────

    private fun showSidebar() {
        navState = NavState.SIDEBAR
        navHasCategoryStep = false
        binding.tvSidebar.visibility = View.VISIBLE
        binding.tvCatPanel.visibility = View.GONE
        binding.tvChanPanel.visibility = View.GONE
        activeSidebarButton().requestFocus()
    }

    private fun showCategoryPanel(title: String) {
        navState = NavState.CATEGORIES
        navHasCategoryStep = true
        binding.tvSidebar.visibility = View.GONE
        binding.tvCatPanel.visibility = View.VISIBLE
        binding.tvChanPanel.visibility = View.GONE
        binding.tvCatTitle.text = title
        binding.tvRvCategories.post {
            binding.tvRvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: binding.tvRvCategories.requestFocus()
        }
    }

    private fun showChannelPanel(title: String) {
        navState = NavState.CHANNELS
        binding.tvSidebar.visibility = View.GONE
        binding.tvCatPanel.visibility = View.GONE
        binding.tvChanPanel.visibility = View.VISIBLE
        binding.tvChanTitle.text = title
        pendingContentFocus = true
    }

    private fun moveSidebarFocus(up: Boolean) {
        val buttons = listOf(
            binding.btnTvLive,
            binding.btnTvCategories,
            binding.btnTvMovies,
            binding.btnTvSeries,
            binding.btnTvFavorites,
            binding.btnTvSettings
        ).filter { it.visibility == View.VISIBLE }
        val idx = buttons.indexOfFirst { it == currentFocus }
        if (idx < 0) { buttons.firstOrNull()?.requestFocus(); return }
        buttons.getOrNull(if (up) idx - 1 else idx + 1)?.requestFocus()
    }

    private fun drillInFromFocusedButton() {
        when (currentFocus) {
            binding.btnTvLive       -> selectSection(Section.LIVE)
            binding.btnTvCategories -> selectSection(Section.CATEGORIES)
            binding.btnTvMovies     -> selectSection(Section.MOVIES)
            binding.btnTvSeries     -> selectSection(Section.SERIES)
            binding.btnTvFavorites  -> selectSection(Section.FAVORITES)
            binding.btnTvSettings   -> startActivity(Intent(this, TvSettingsActivity::class.java))
        }
    }

    // ── Sidebar navigation ───────────────────────────────────────────────────

    private val sectionButtons get() = listOf(
        binding.btnTvLive,
        binding.btnTvCategories,
        binding.btnTvMovies,
        binding.btnTvSeries,
        binding.btnTvFavorites
    )

    private fun setupSidebar() {
        binding.btnTvLive.setOnClickListener { selectSection(Section.LIVE) }
        binding.btnTvCategories.setOnClickListener { selectSection(Section.CATEGORIES) }
        binding.btnTvMovies.setOnClickListener { selectSection(Section.MOVIES) }
        binding.btnTvSeries.setOnClickListener { selectSection(Section.SERIES) }
        binding.btnTvFavorites.setOnClickListener { selectSection(Section.FAVORITES) }
        binding.btnTvSettings.setOnClickListener {
            startActivity(Intent(this, TvSettingsActivity::class.java))
        }
        binding.tvBtnCatBack.setOnClickListener { showSidebar() }
        binding.tvBtnChanBack.setOnClickListener {
            if (navHasCategoryStep) showCategoryPanel(binding.tvCatTitle.text.toString())
            else showSidebar()
        }
    }

    private fun selectSection(section: Section) {
        currentSection = section
        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()) }
        activeSidebarButton().setTextColor(0xFF008CFF.toInt())

        when (section) {
            Section.LIVE -> {
                showLive()
                showCategoryPanel("LIVE")
            }
            Section.CATEGORIES -> {
                showFavCategories()
                showCategoryPanel("CATEGORIES")
            }
            Section.MOVIES -> {
                showMovies()
                showCategoryPanel("MOVIES")
            }
            Section.SERIES -> {
                showSeries()
                showChannelPanel("SERIES")
            }
            Section.FAVORITES -> {
                showFavorites()
                showChannelPanel("FAVORITES")
            }
        }
    }

    private fun activeSidebarButton() = when (currentSection) {
        Section.LIVE       -> binding.btnTvLive
        Section.CATEGORIES -> binding.btnTvCategories
        Section.MOVIES     -> binding.btnTvMovies
        Section.SERIES     -> binding.btnTvSeries
        Section.FAVORITES  -> binding.btnTvFavorites
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Numeric channel jump (only in channel panel showing live/fav channels)
            val digit = when (event.keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
                else -> null
            }
            if (digit != null && navState == NavState.CHANNELS &&
                currentSection in setOf(Section.LIVE, Section.FAVORITES, Section.CATEGORIES)) {
                if (channelNumberBuffer.length < 3) channelNumberBuffer += digit
                channelJumpJob?.cancel()
                Toast.makeText(this, "Ch: $channelNumberBuffer", Toast.LENGTH_SHORT).show()
                channelJumpJob = lifecycleScope.launch {
                    delay(1500)
                    val target = (channelNumberBuffer.toIntOrNull() ?: 0) - 1
                    channelNumberBuffer = ""
                    if (target < 0) return@launch
                    val list = viewModel.channels.value
                    if (target >= list.size) return@launch
                    val lm = binding.tvRvContent.layoutManager as? LinearLayoutManager ?: return@launch
                    lm.scrollToPositionWithOffset(target, 0)
                    binding.tvRvContent.post {
                        binding.tvRvContent.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                    }
                }
                return true
            }

            when (event.keyCode) {
                // Sidebar: up/down stays within sidebar buttons only
                KeyEvent.KEYCODE_DPAD_UP -> if (navState == NavState.SIDEBAR) {
                    moveSidebarFocus(up = true); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (navState == NavState.SIDEBAR) {
                    moveSidebarFocus(up = false); return true
                }
                // Right only drills in from sidebar; blocks going to EPG guide from other panels
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (navState) {
                    NavState.SIDEBAR -> { drillInFromFocusedButton(); return true }
                    NavState.CATEGORIES, NavState.CHANNELS -> return true
                }
                // Left goes back one level
                KeyEvent.KEYCODE_DPAD_LEFT -> when (navState) {
                    NavState.CATEGORIES -> { showSidebar(); return true }
                    NavState.CHANNELS -> {
                        if (navHasCategoryStep) showCategoryPanel(binding.tvCatTitle.text.toString())
                        else showSidebar()
                        return true
                    }
                    NavState.SIDEBAR -> {}
                }
                // Back goes up one drill level
                KeyEvent.KEYCODE_BACK -> when (navState) {
                    NavState.CHANNELS -> {
                        if (navHasCategoryStep) showCategoryPanel(binding.tvCatTitle.text.toString())
                        else showSidebar()
                        return true
                    }
                    NavState.CATEGORIES -> { showSidebar(); return true }
                    NavState.SIDEBAR -> {}
                }
                KeyEvent.KEYCODE_GUIDE -> return true
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (currentMiniUrl.isNotEmpty()) {
                        val pos = miniPlayer?.currentPosition ?: 0L
                        val isVod = currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
                        openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = isVod, resumeMs = pos)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showLive() {
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.adapter = channelAdapter
        val cats = viewModel.liveCategories.value
        categoryAdapter.resetSelection()
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) viewModel.selectLiveCategory(cats.first().categoryId)
        else viewModel.reloadCurrentLiveCategory()
    }

    private fun showFavCategories() {
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.adapter = channelAdapter
        val favCats = viewModel.favoriteLiveCategories.value
        categoryAdapter.submitList(favCats)
        if (favCats.isNotEmpty()) viewModel.selectFavCategory(favCats.first().categoryId)
        else channelAdapter.submitList(emptyList())
    }

    private fun showMovies() {
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.adapter = vodAdapter
        val cats = viewModel.vodCategories.value
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) viewModel.selectVodCategory(cats.first().categoryId)
    }

    private fun showSeries() {
        binding.tvRvContent.adapter = seriesAdapter
        seriesAdapter.submitList(viewModel.series.value)
    }

    private fun showFavorites() {
        binding.tvRvContent.adapter = channelAdapter
        viewModel.showFavoriteChannels()
        viewModel.checkFavoritesHealth()
    }

    // ── Search ───────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.tvEtSearch.setOnEditorActionListener { _, _, _ ->
            dispatchSearch(binding.tvEtSearch.text.toString()); true
        }
        binding.tvEtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString()
                binding.tvBtnClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                if (q.length >= 2 || q.isEmpty()) {
                    searchDebounceJob?.cancel()
                    searchDebounceJob = lifecycleScope.launch {
                        delay(300)
                        dispatchSearch(q)
                    }
                }
            }
        })
        binding.tvBtnClearSearch.setOnClickListener {
            binding.tvEtSearch.setText("")
            binding.tvEtSearch.clearFocus()
        }
    }

    private fun dispatchSearch(query: String) {
        when (currentSection) {
            Section.MOVIES -> viewModel.searchVod(query)
            else -> viewModel.searchChannels(query)
        }
    }

    // ── ViewModel observers ──────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.loading.collect { isLoading ->
                binding.tvProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                if (!isLoading) binding.tvRvContent.visibility = View.VISIBLE
            }
        }
        lifecycleScope.launch {
            viewModel.liveCategories.collect {
                if (currentSection == Section.LIVE) categoryAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteLiveCategories.collect { favs ->
                categoryAdapter.submitFavoriteCategoryIds(favs.map { it.categoryId }.toSet())
                if (currentSection == Section.CATEGORIES) {
                    categoryAdapter.submitList(favs)
                    if (favs.isNotEmpty()) viewModel.selectFavCategory(favs.first().categoryId)
                    else channelAdapter.submitList(emptyList())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collect { channels ->
                if (currentSection == Section.MOVIES || currentSection == Section.SERIES) return@collect
                if (binding.tvRvContent.adapter !== channelAdapter) return@collect

                val focusedChild = binding.tvRvContent.focusedChild
                val focusedPos = if (focusedChild != null)
                    binding.tvRvContent.getChildAdapterPosition(focusedChild) else -1
                val wantFocus = pendingContentFocus
                if (wantFocus) pendingContentFocus = false

                channelAdapter.submitList(channels) {
                    binding.tvRvContent.post {
                        when {
                            focusedPos >= 0 ->
                                binding.tvRvContent.findViewHolderForAdapterPosition(focusedPos)
                                    ?.itemView?.requestFocus()
                            wantFocus ->
                                binding.tvRvContent.findViewHolderForAdapterPosition(0)
                                    ?.itemView?.requestFocus()
                                    ?: binding.tvRvContent.requestFocus()
                        }
                    }
                }
                viewModel.loadEpgForChannels(channels)
            }
        }
        lifecycleScope.launch {
            viewModel.vod.collect {
                if (currentSection == Section.MOVIES) {
                    val wantFocus = pendingContentFocus
                    if (wantFocus) pendingContentFocus = false
                    vodAdapter.submitList(it) {
                        if (wantFocus) binding.tvRvContent.post {
                            binding.tvRvContent.findViewHolderForAdapterPosition(0)
                                ?.itemView?.requestFocus() ?: binding.tvRvContent.requestFocus()
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                if (currentSection == Section.SERIES) {
                    val wantFocus = pendingContentFocus
                    if (wantFocus) pendingContentFocus = false
                    seriesAdapter.submitList(it) {
                        if (wantFocus) binding.tvRvContent.post {
                            binding.tvRvContent.findViewHolderForAdapterPosition(0)
                                ?.itemView?.requestFocus() ?: binding.tvRvContent.requestFocus()
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.currentlyPlayingStreamId.collect { channelAdapter.setCurrentlyPlayingStreamId(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect { channelAdapter.submitEpgText(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgNextText.collect { channelAdapter.submitEpgNextText(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgProgress.collect { channelAdapter.submitEpgProgress(it) }
        }
        lifecycleScope.launch {
            viewModel.channelHealth.collect { channelAdapter.submitHealth(it) }
        }
        lifecycleScope.launch {
            viewModel.vodCategories.collect {
                if (currentSection == Section.MOVIES) categoryAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.externalPlayer.collect { externalPlayerChoice = it }
        }
        lifecycleScope.launch {
            viewModel.preWarmOnFocus.collect { preWarmEnabled = it }
        }
        lifecycleScope.launch {
            viewModel.loading.collect { isLoading ->
                if (!isLoading) {
                    val favorites = viewModel.channels.value.filter { it.isFavorite }
                    if (favorites.isNotEmpty()) {
                        TvHomeChannelPublisher.publishFavorites(applicationContext, favorites)
                    }
                }
            }
        }
    }

    private fun observeEpgGuide() {
        lifecycleScope.launch {
            viewModel.channels.collect { epgGuideAdapter.submitList(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect { epgGuideAdapter.submitEpgText(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgNextText.collect { epgGuideAdapter.submitEpgNextText(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgProgress.collect { epgGuideAdapter.submitEpgProgress(it) }
        }
    }

    private fun observeSidebarVisibility() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.showMovies.collect { show ->
                        binding.btnTvMovies.visibility = if (show) View.VISIBLE else View.GONE
                        if (!show && currentSection == Section.MOVIES) selectSection(Section.LIVE)
                    }
                }
                launch {
                    viewModel.showSeries.collect { show ->
                        binding.btnTvSeries.visibility = if (show) View.VISIBLE else View.GONE
                        if (!show && currentSection == Section.SERIES) selectSection(Section.LIVE)
                    }
                }
            }
        }
    }

    // ── Player launcher ──────────────────────────────────────────────────────

    private fun openPlayer(
        url: String,
        title: String,
        streamId: Int,
        streamIds: IntArray = viewModel.channels.value.map { it.streamId }.toIntArray(),
        isVod: Boolean = false,
        resumeMs: Long = 0L
    ) {
        if (externalPlayerChoice != "internal") {
            launchExternalPlayer(url, title, externalPlayerChoice)
            return
        }
        miniPlayer?.stop()
        miniPlayer?.clearMediaItems()
        openPlayerJob?.cancel()
        openPlayerJob = lifecycleScope.launch {
            delay(1200)
            playerLauncher.launch(Intent(this@TvHomeActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", title)
                putExtra("stream_id", streamId)
                putExtra("stream_ids", streamIds)
                putExtra("is_vod", isVod)
                putExtra("resume_ms", resumeMs)
            })
        }
    }

    private fun showTvReminderDialog(channel: ChannelEntity) {
        lifecycleScope.launch {
            val epgList = try {
                viewModel.getUpcomingEpg(channel.streamId)
            } catch (_: Exception) { emptyList() }

            if (epgList.isEmpty()) {
                val options = arrayOf("In 15 minutes", "In 30 minutes", "In 1 hour", "In 2 hours")
                val deltas = longArrayOf(15 * 60 * 1000L, 30 * 60 * 1000L, 60 * 60 * 1000L, 120 * 60 * 1000L)
                androidx.appcompat.app.AlertDialog.Builder(this@TvHomeActivity)
                    .setTitle("Remind me about ${channel.name}")
                    .setItems(options) { _, i ->
                        ChannelTimerScheduler.schedule(
                            this@TvHomeActivity, channel.streamId, channel.name,
                            channel.name, System.currentTimeMillis() + deltas[i]
                        )
                        Toast.makeText(this@TvHomeActivity, "Reminder set for ${options[i]}", Toast.LENGTH_SHORT).show()
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

            androidx.appcompat.app.AlertDialog.Builder(this@TvHomeActivity)
                .setTitle("Remind me — ${channel.name}")
                .setItems(labels) { _, i ->
                    val epg = epgList[i]
                    val startMs = if (epg.startTimestamp > 1_000_000_000_000L) epg.startTimestamp else epg.startTimestamp * 1000L
                    ChannelTimerScheduler.schedule(
                        this@TvHomeActivity, channel.streamId, channel.name, epg.title, startMs
                    )
                    Toast.makeText(this@TvHomeActivity, "Reminder set for ${epg.title}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }
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
                Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
