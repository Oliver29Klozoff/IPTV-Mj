package com.iptvapp.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptvapp.databinding.ActivityTvHomeBinding
import com.iptvapp.ui.guide.GuideAdapter
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.series.SeriesDetailActivity
import com.iptvapp.ui.settings.TvSettingsActivity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.ui.guide.ChannelTimerScheduler
import com.iptvapp.tv.TvHomeChannelPublisher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    private lateinit var guideAdapter: GuideAdapter

    private var miniPlayer: ExoPlayer? = null
    private var currentMiniUrl: String = ""
    private var currentMiniTitle: String = ""
    private var currentMiniStreamId: Int = -1
    private var epgRefreshJob: kotlinx.coroutines.Job? = null
    private var searchDebounceJob: kotlinx.coroutines.Job? = null
    private var externalPlayerChoice = "internal"

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
            binding.tvEtSearch.setText(text)
            dispatchSearch(text)
        }
    }

    private enum class Section { LIVE, CATEGORIES, MOVIES, SERIES, FAVORITES, GUIDE }
    private var currentSection = Section.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupAdapters()
        setupSidebar()
        setupSearch()
        setupMiniPlayer()
        observeViewModel()
        viewModel.loadAll()
        selectSection(Section.LIVE)
        handleDeepLink(intent)
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
        lifecycleScope.launch {
            val recent = viewModel.getRecentChannel()
            when {
                recent != null && recent.streamId != currentMiniStreamId -> playInMiniPlayer(recent)
                currentMiniUrl.isNotEmpty() -> miniPlayer?.play()
            }
        }
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

    // ── Adapters ─────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        categoryAdapter = CategoryAdapter(
            onCategoryClick = { cat ->
                when (currentSection) {
                    Section.LIVE -> viewModel.selectLiveCategory(cat.categoryId)
                    Section.CATEGORIES -> viewModel.selectFavCategory(cat.categoryId)
                    Section.MOVIES -> viewModel.selectVodCategory(cat.categoryId)
                    else -> {}
                }
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
                // Double-click / long-press → fullscreen
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
                        program.startTimestamp else program.startTimestamp / 1000L
                    val stopSec = if (program.stopTimestamp < 100000000000L)
                        program.stopTimestamp else program.stopTimestamp / 1000L
                    val durationMin = ((stopSec - startSec) / 60).toInt().coerceAtLeast(1)
                    val url = viewModel.getTimeshiftUrl(row.channel.streamId, startSec, durationMin)
                    openPlayer(url, "${row.channel.name} — ${program.title}", row.channel.streamId)
                }
            }
        )

        binding.tvRvCategories.layoutManager = LinearLayoutManager(this)
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.layoutManager = LinearLayoutManager(this)
        binding.tvRvContent.adapter = channelAdapter
    }

    // ── Sidebar navigation ───────────────────────────────────────────────────

    private val sectionButtons get() = listOf(
        binding.btnTvLive,
        binding.btnTvCategories,
        binding.btnTvMovies,
        binding.btnTvSeries,
        binding.btnTvFavorites,
        binding.btnTvGuide
    )

    private fun setupSidebar() {
        binding.btnTvLive.setOnClickListener { selectSection(Section.LIVE) }
        binding.btnTvCategories.setOnClickListener { selectSection(Section.CATEGORIES) }
        binding.btnTvMovies.setOnClickListener { selectSection(Section.MOVIES) }
        binding.btnTvSeries.setOnClickListener { selectSection(Section.SERIES) }
        binding.btnTvFavorites.setOnClickListener { selectSection(Section.FAVORITES) }
        binding.btnTvGuide.setOnClickListener { selectSection(Section.GUIDE) }
        binding.btnTvSettings.setOnClickListener {
            startActivity(Intent(this, TvSettingsActivity::class.java))
        }
    }

    private fun selectSection(section: Section) {
        currentSection = section

        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()) }
        activeSidebarButton().setTextColor(0xFF008CFF.toInt())

        when (section) {
            Section.LIVE -> showLive()
            Section.CATEGORIES -> showFavCategories()
            Section.MOVIES -> showMovies()
            Section.SERIES -> showSeries()
            Section.FAVORITES -> showFavorites()
            Section.GUIDE -> showGuide()
        }

        // Move D-pad focus into content list after section switch
        binding.tvRvContent.post {
            val lm = binding.tvRvContent.layoutManager
            val first = lm?.findViewByPosition(0)
            if (first != null) first.requestFocus()
            else binding.tvRvContent.requestFocus()
        }
    }

    private fun activeSidebarButton() = when (currentSection) {
        Section.LIVE -> binding.btnTvLive
        Section.CATEGORIES -> binding.btnTvCategories
        Section.MOVIES -> binding.btnTvMovies
        Section.SERIES -> binding.btnTvSeries
        Section.FAVORITES -> binding.btnTvFavorites
        Section.GUIDE -> binding.btnTvGuide
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val inContent = focused != null &&
                (binding.tvRvContent.hasFocus() || binding.tvRvCategories.hasFocus())
            val inSidebar = focused != null && binding.tvSidebar.hasFocus()

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (inContent) {
                    if (binding.tvRvContent.hasFocus() &&
                        binding.tvRvCategories.visibility == View.VISIBLE) {
                        // channels → categories
                        binding.tvRvCategories.requestFocus()
                    } else {
                        // categories (or content with no categories) → sidebar
                        activeSidebarButton().requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (inSidebar) {
                    val target = if (binding.tvRvCategories.visibility == View.VISIBLE)
                        binding.tvRvCategories else binding.tvRvContent
                    val lm = target.layoutManager
                    val first = lm?.findViewByPosition(0)
                    if (first != null) first.requestFocus() else target.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> if (inContent) {
                    activeSidebarButton().requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                    // MENU key on any focused channel → open fullscreen
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
        binding.tvRvCategories.visibility = View.VISIBLE
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.adapter = channelAdapter
        val cats = viewModel.liveCategories.value
        categoryAdapter.resetSelection()
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) viewModel.selectLiveCategory(cats.first().categoryId)
        else viewModel.reloadCurrentLiveCategory()
    }

    private fun showFavCategories() {
        binding.tvRvCategories.visibility = View.VISIBLE
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.adapter = channelAdapter
        val favCats = viewModel.favoriteLiveCategories.value
        categoryAdapter.submitList(favCats)
        if (favCats.isNotEmpty()) viewModel.selectFavCategory(favCats.first().categoryId)
        else channelAdapter.submitList(emptyList())
    }

    private fun showMovies() {
        binding.tvRvCategories.visibility = View.VISIBLE
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.adapter = vodAdapter
        val cats = viewModel.vodCategories.value
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) viewModel.selectVodCategory(cats.first().categoryId)
    }

    private fun showSeries() {
        binding.tvRvCategories.visibility = View.GONE
        binding.tvRvContent.adapter = seriesAdapter
        seriesAdapter.submitList(viewModel.series.value)
    }

    private fun showFavorites() {
        binding.tvRvCategories.visibility = View.GONE
        binding.tvRvContent.adapter = channelAdapter
        viewModel.showFavoriteChannels()
        viewModel.checkFavoritesHealth()
    }

    private fun showGuide() {
        binding.tvRvCategories.visibility = View.GONE
        binding.tvRvContent.adapter = guideAdapter
        viewModel.loadGuide()
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
        binding.tvBtnVoiceSearch.setOnClickListener {
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
                channelAdapter.submitList(channels)
                viewModel.loadEpgForChannels(channels)
            }
        }
        lifecycleScope.launch {
            viewModel.vod.collect {
                if (currentSection == Section.MOVIES) vodAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                if (currentSection == Section.SERIES) seriesAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.guideRows.collect { guideAdapter.submitList(it) }
        }
        lifecycleScope.launch {
            viewModel.currentlyPlayingStreamId.collect { channelAdapter.setCurrentlyPlayingStreamId(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect { channelAdapter.submitEpgText(it) }
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
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("stream_title", title)
            putExtra("stream_id", streamId)
            putExtra("stream_ids", streamIds)
            putExtra("is_vod", isVod)
            putExtra("resume_ms", resumeMs)
        })
    }

    private fun showTvReminderDialog(channel: ChannelEntity) {
        lifecycleScope.launch {
            val nowSec = System.currentTimeMillis() / 1000
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
                android.widget.Toast.makeText(this, "No video player found", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
