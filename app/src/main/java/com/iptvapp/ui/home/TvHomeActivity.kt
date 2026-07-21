package com.iptvapp.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.databinding.ActivityTvHomeBinding
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.recordings.TvRecordingActivity
import com.iptvapp.ui.series.SeriesDetailActivity
import com.iptvapp.ui.settings.TvSettingsActivity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.ui.guide.ChannelTimerScheduler
import com.iptvapp.tv.TvHomeChannelPublisher
import com.iptvapp.ui.onboarding.FeatureTourDialog
import com.iptvapp.update.UpdateChecker
import com.iptvapp.worker.EpgRefreshWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class TvHomeActivity : AppCompatActivity() {

    // Not private: FeatureTourDialog/SpotlightTourController reads real sidebar button views
    // from this binding to point the spotlight tour at actual on-screen UI.
    lateinit var binding: ActivityTvHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    @javax.inject.Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager
    @javax.inject.Inject lateinit var okHttpClient: okhttp3.OkHttpClient

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var mergedChannelAdapter: MergedChannelAdapter
    private lateinit var combinedFavoriteAdapter: CombinedFavoriteAdapter
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
    // -1 = primary server. Set alongside currentMiniStreamId whenever a merged channel loads
    // into the mini player, so the eventual "go fullscreen" openPlayer() call can pass the right
    // server_index/merged_stream_id extras through to PlayerActivity for live EPG refresh.
    private var currentMiniServerIndex: Int = -1
    // The merged channel's real per-server stream id — currentMiniStreamId itself stays -1 for
    // merged channels, so this carries the id PlayerActivity actually needs for get_short_epg.
    private var currentMiniMergedStreamId: Int = -1
    // Merged (Providers) channels always play with currentMiniStreamId == -1 (no DB-backed
    // identity), so that alone can't drive the combined Favorites list's "now playing"
    // highlight — this tracks the actual CombinedFavorite.id ("primary:<id>" or
    // "<serverIndex>:<id>") of whichever item in that list was last clicked, primary or merged.
    private var currentMiniCombinedFavoriteId: String? = null
    // Explicitly tracked instead of regexing currentMiniUrl for "movie|vod" — that regex
    // missed series episode URLs (which contain "series", not "movie"/"vod"), causing the
    // fullscreen button to treat VOD as live and open with no seek bar/resume (same bug
    // fixed on phone in HomeActivity).
    private var currentMiniIsVod: Boolean = false
    private var miniRetryCount: Int = 0
    private var miniPlayJob: kotlinx.coroutines.Job? = null
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
    private var lastBackPressTime = 0L
    private val DPAD_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
    )

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val sid = result.data?.getIntExtra("stream_id", -1) ?: -1
            val url = result.data?.getStringExtra("stream_url") ?: return@registerForActivityResult
            val title = result.data?.getStringExtra("stream_title") ?: return@registerForActivityResult
            if (sid != -1 && url.isNotEmpty()) {
                currentMiniStreamId = sid
                currentMiniServerIndex = -1
                currentMiniUrl = url
                currentMiniTitle = title
                currentMiniIsVod = false
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

    private enum class Section { LIVE, CATEGORIES, MOVIES, SERIES, FAVORITES, GUIDE, PROVIDERS }
    private var currentSection = Section.FAVORITES

    // Bulk-select-to-favorites, Hide Channel, and Channels Like This were phone-only
    // (HomeActivity's showChannelActionsMenuDialog) — the D-pad long-press here only ever
    // opened the reminder flow. Same fields/Handler-based idle-commit pattern as phone.
    private val bulkSelectedIds = mutableSetOf<Int>()
    private var bulkSelectMode = false
    private val bulkSelectHandler = Handler(Looper.getMainLooper())
    private val bulkSelectIdleRunnable = Runnable {
        if (bulkSelectMode && bulkSelectedIds.isNotEmpty()) {
            viewModel.bulkAddFavorites(bulkSelectedIds.toList())
            Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
            bulkSelectedIds.clear()
            bulkSelectMode = false
            channelAdapter.submitBulkSelection(emptySet())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs)
        setupAdapters()
        setupSidebar()
        setupSearch()
        setupMiniPlayer()
        observeViewModel()
        observeEpgGuide()
        observeSidebarVisibility()
        viewModel.loadAll()
        // Same cold-start auto-refresh as the phone: the merged "All Providers" cache used to
        // sit empty until the user manually hit Refresh, even on a device with providers
        // already configured.
        lifecycleScope.launch {
            if (prefs.getExtraServersWithNick().isNotEmpty()) viewModel.refreshMergedChannels()
        }
        showSidebar()
        handleDeepLink(intent)
        if (intent.getBooleanExtra(FeatureTourDialog.EXTRA_START_TOUR, false)) {
            FeatureTourDialog.show(this)
        } else {
            FeatureTourDialog.showIfNeeded(this)
        }
        UpdateChecker(this).check(lifecycleScope)
        lifecycleScope.launch { applyAccent(android.graphics.Color.parseColor(prefs.accentColor.first())) }
        rescheduleEpgRefreshIfNeeded()
    }

    // Android (and TV-box "clear background apps" utilities especially) can force-stop
    // an app, which silently cancels ALL of its scheduled WorkManager jobs — including the
    // periodic EPG auto-refresh. The phone's HomeActivity already re-asserts this on every
    // launch; TvHomeActivity never did, so once that job got killed on a Shield/TV box, EPG
    // auto-refresh would just stop forever until the user happened to open Settings and
    // re-touch the Auto Refresh option. KEEP means this won't reset the interval clock if
    // the job is already alive — it only revives it if something cancelled it.
    private fun rescheduleEpgRefreshIfNeeded() {
        lifecycleScope.launch {
            val hours = prefs.epgAutoRefreshHours.first()
            if (hours > 0) {
                val req = PeriodicWorkRequestBuilder<EpgRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true))
                    .build()
                WorkManager.getInstance(this@TvHomeActivity).enqueueUniquePeriodicWork(
                    "auto_epg_refresh_work", ExistingPeriodicWorkPolicy.KEEP, req
                )
            }
        }
    }

    // Selecting a sidebar section re-colors the active button (selectSection() below) using
    // this field rather than a hardcoded blue, so the accent survives navigation instead of
    // only showing briefly at launch before the first tap reverts it to the default color.
    private var currentAccent: Int = 0xFF008CFF.toInt()

    /** Recolors the sidebar, header buttons, and progress bars to the accent chosen in
     * Settings → Display, including a matching focus-ring color (not just the hardcoded blue). */
    private fun applyAccent(accent: Int) {
        currentAccent = accent
        listOf(
            binding.btnTvFavorites, binding.btnTvLive, binding.btnTvCategories,
            binding.btnTvMovies, binding.btnTvSeries, binding.btnTvGuide,
            binding.btnTvProviders
        ).forEach { com.iptvapp.util.TvAccentHelper.applyToButton(it, accent) }
        // The currently active section's button should stay accent-colored, not fall back
        // to the dim grey applyToButton would otherwise leave every button in.
        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()) }
        activeSidebarButton().setTextColor(accent)

        binding.tvMktvWordmark.setTextColor(accent)
        binding.tvEpgProgress.progressTintList = android.content.res.ColorStateList.valueOf(accent)
        binding.tvMiniPlayerProgress.indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
        binding.tvProgressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
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
            "home" -> showSidebar()
        }
    }

    override fun onResume() {
        super.onResume()
        com.iptvapp.update.UpdateChecker(this).resumeCheck(lifecycleScope)
        lifecycleScope.launch { applyAccent(android.graphics.Color.parseColor(prefs.accentColor.first())) }
        // Cheap, always-visible reminder of which server/account is currently active.
        lifecycleScope.launch {
            val nickname = prefs.serverNickname.first()
            binding.tvEtSearch.hint = if (nickname.isNotBlank()) "Search ($nickname)…" else "Search…"
        }
        if (currentMiniUrl.isEmpty()) {
            lifecycleScope.launch {
                val recent = viewModel.getRecentChannel()
                if (recent != null) playInMiniPlayer(recent)
            }
        } else if (!currentMiniIsVod) {
            // Re-prepare so ExoPlayer re-fetches the manifest and starts at the real live
            // edge, instead of resuming from whatever position was buffered before pausing.
            miniPlayer?.setMediaItem(MediaItem.fromUri(currentMiniUrl))
            miniPlayer?.prepare()
            miniPlayer?.playWhenReady = true
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
                val now = Date()
                binding.tvClock.text = clockFmt.format(now)
                if (navState == NavState.CHANNELS) viewModel.loadEpgForChannels(viewModel.channels.value)
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
        // See HomeActivity.initMiniPlayer's kdoc — ExoPlayer's default User-Agent gets
        // blocked by some Cloudflare-fronted IPTV CDNs on the stream endpoint specifically;
        // mirror fullscreen playback's custom OkHttpDataSource.Factory here too.
        val upstreamDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${android.os.Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(upstreamDataSourceFactory)
        miniPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also { player ->
            binding.tvMiniPlayerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.tvMiniPlayerProgress.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) miniRetryCount = 0
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "TV MINI PLAYER ERROR: isVod=$currentMiniIsVod streamId=$currentMiniStreamId " +
                            "errorCode=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName} " +
                            "message=${error.message} retryCount=$miniRetryCount url=$currentMiniUrl"
                    )
                    if (miniRetryCount >= 5 || currentMiniUrl.isEmpty()) return
                    miniRetryCount++
                    miniPlayJob?.cancel()
                    miniPlayJob = lifecycleScope.launch {
                        delay(3000L)
                        miniPlayer?.let {
                            it.setMediaItem(MediaItem.fromUri(currentMiniUrl))
                            it.prepare()
                            it.playWhenReady = true
                        }
                    }
                }
            })
        }
        // Reachable via D-pad (Right from sidebar/content lists) since the FULL SCREEN
        // button was removed — focusing this and pressing OK now does what that button did.
        binding.tvMiniPlayerContainer.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val pos = miniPlayer?.currentPosition ?: 0L
                openPlayer(
                    currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = pos,
                    serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                )
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
            currentMiniServerIndex = -1
            currentMiniIsVod = false
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

    // Prefer the same shared channelEpgText/channelEpgProgress source the guide and the
    // channel-focus preview use, so the mini player never disagrees with what the guide
    // shows for "now playing". Only fall back to a fresh single-stream fetch if this
    // channel isn't in that batch (e.g. played from outside the currently browsed list).
    private suspend fun refreshMiniEpg(streamId: Int) {
        val sharedText = viewModel.channelEpgText.value[streamId]
        val sharedProgress = viewModel.channelEpgProgress.value[streamId]
        val epg = sharedText ?: viewModel.getEpgText(streamId)
        val progress = sharedProgress ?: viewModel.getMiniEpgProgress(streamId)

        binding.tvTvEpg.text = epg
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
                var showChannels = true
                when (currentSection) {
                    Section.FAVORITES -> {
                        showFavoriteGenreChannels(cat.categoryId, "FAVORITES")
                        // showFavoriteGenreChannels already handles panel/list state itself.
                        showChannels = false
                    }
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
                    Section.PROVIDERS -> {
                        // 3-level drill (server -> category -> channels): the first tap picks
                        // a server and should show ITS categories next, not channels yet.
                        // Merged favorites are now viewed from the main Favorites tab (combined
                        // with primary favorites, auto genre-classified) instead of a dedicated
                        // "★ Favorites" tile here — favoriting/folder-assignment per channel
                        // still works via each row's star and long-press menu.
                        if (viewModel.selectedMergedServerIndex == null) {
                            viewModel.selectMergedServer(cat.categoryId.toInt())
                            categoryAdapter.submitList(emptyList())
                            lifecycleScope.launch {
                                viewModel.mergedCategories.collect { cats ->
                                    if (viewModel.selectedMergedServerIndex != null) {
                                        categoryAdapter.submitList(mergedCategoriesToSynthetic(cats))
                                    }
                                }
                            }
                            showCategoryPanel("PROVIDERS")
                            showChannels = false
                        } else {
                            val categoryId = if (cat.categoryId == NO_CATEGORY_ID) null else cat.categoryId
                            viewModel.selectMergedCategory(categoryId)
                            binding.tvRvContent.adapter = mergedChannelAdapter
                        }
                    }
                    else -> {}
                }
                if (showChannels) showChannelPanel(cat.categoryName)
            },
            onCategoryLongClick = { cat ->
                if (currentSection == Section.LIVE) {
                    viewModel.toggleLiveCategoryFavorite(cat.categoryId)
                    Toast.makeText(this, "Category favorite updated", Toast.LENGTH_SHORT).show()
                }
                // No rename/delete for Favorites' auto-derived genre tiles.
            }
        )

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                if (bulkSelectMode) {
                    if (!bulkSelectedIds.add(channel.streamId)) bulkSelectedIds.remove(channel.streamId)
                    channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                    Toast.makeText(this, "${bulkSelectedIds.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                    if (bulkSelectedIds.isEmpty()) bulkSelectMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 3000)
                    return@ChannelAdapter
                }
                lifecycleScope.launch {
                    playInMiniPlayer(channel)
                    viewModel.markChannelWatched(channel.streamId)
                    viewModel.setCurrentlyPlaying(channel.streamId)
                }
                scrollGuideToChannel(channel.streamId)
                scheduleTvAutoCollapse()
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
            onChannelLongClick = { channel -> showTvChannelActionsMenu(channel) }
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

        mergedChannelAdapter = MergedChannelAdapter(
            onChannelClick = { channel -> playMergedChannel(channel) },
            onFavoriteClick = { channel ->
                viewModel.setMergedChannelFavorite(channel, !channel.isFavorite)
                Toast.makeText(this, if (channel.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            // The star icon isn't reachable by D-pad — long-press (OK held) is how TV
            // favorites/unfavorites a merged channel, mirroring the primary list's menu.
            onChannelLongClick = { channel ->
                val options = mutableListOf(
                    "Play Fullscreen",
                    if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites"
                )
                if (channel.isFavorite) options.add("Move to Folder")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("${channel.name} · ${channel.serverNickname}")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (options[which]) {
                            "Play Fullscreen" -> lifecycleScope.launch {
                                try {
                                    val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                                    openPlayer(url, "${channel.name} · ${channel.serverNickname}", -1, serverIndex = channel.serverIndex, mergedStreamId = channel.streamId)
                                } catch (_: Exception) {
                                    Toast.makeText(this@TvHomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                                }
                            }
                            "Add to Favorites", "Remove from Favorites" -> {
                                viewModel.setMergedChannelFavorite(channel, !channel.isFavorite)
                                Toast.makeText(this, if (channel.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
                            }
                            "Move to Folder" -> showMoveToFolderDialog(channel)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        combinedFavoriteAdapter = CombinedFavoriteAdapter(
            onChannelClick = { item ->
                currentMiniCombinedFavoriteId = item.id
                combinedFavoriteAdapter.setCurrentlyPlayingId(item.id)
                when (item) {
                    is CombinedFavorite.Primary -> {
                        lifecycleScope.launch {
                            playInMiniPlayer(item.channel)
                            viewModel.markChannelWatched(item.channel.streamId)
                            viewModel.setCurrentlyPlaying(item.channel.streamId)
                        }
                        scheduleTvAutoCollapse()
                    }
                    is CombinedFavorite.Merged -> playMergedChannel(item.channel)
                }
            },
            onFavoriteClick = { item ->
                viewModel.toggleCombinedFavorite(item)
                val wasFavorite = when (item) {
                    is CombinedFavorite.Primary -> item.channel.isFavorite
                    is CombinedFavorite.Merged -> item.channel.isFavorite
                }
                Toast.makeText(this, if (wasFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            onChannelLongClick = { item ->
                when (item) {
                    is CombinedFavorite.Primary -> showTvChannelActionsMenu(item.channel)
                    is CombinedFavorite.Merged -> {
                        val channel = item.channel
                        val options = mutableListOf(
                            "Play Fullscreen",
                            if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites"
                        )
                        if (channel.isFavorite) options.add("Move to Folder")
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("${channel.name} · ${channel.serverNickname}")
                            .setItems(options.toTypedArray()) { _, which ->
                                when (options[which]) {
                                    "Play Fullscreen" -> lifecycleScope.launch {
                                        try {
                                            val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                                            openPlayer(url, "${channel.name} · ${channel.serverNickname}", -1, serverIndex = channel.serverIndex, mergedStreamId = channel.streamId)
                                        } catch (_: Exception) {
                                            Toast.makeText(this@TvHomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    "Add to Favorites", "Remove from Favorites" -> viewModel.toggleCombinedFavorite(item)
                                    "Move to Folder" -> showMoveToFolderDialog(channel)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        )
        combinedFavoriteAdapter.isTvMode = true
        combinedFavoriteAdapter.onChannelFocused = { item ->
            if (item is CombinedFavorite.Primary) {
                if (preWarmEnabled) preWarmChannel(item.channel)
            }
        }

        vodAdapter = VodAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                    currentMiniUrl = url
                    currentMiniTitle = vod.name
                    currentMiniStreamId = vod.streamId
                    currentMiniServerIndex = -1
                    currentMiniIsVod = true
                    binding.tvTvChannelName.text = vod.name
                    miniPlayer?.let {
                        it.setMediaItem(MediaItem.fromUri(url))
                        it.prepare()
                        it.playWhenReady = true
                    }
                }
            },
            onFavoriteClick = {},
            onVodLongClick = { vod ->
                startActivity(Intent(this, com.iptvapp.ui.vod.VodDetailActivity::class.java).apply {
                    putExtra("vod_stream_id", vod.streamId)
                    putExtra("vod_name", vod.name)
                    putExtra("vod_container_extension", vod.containerExtension)
                    putExtra("vod_cover", vod.streamIcon)
                    putExtra("vod_rating", vod.rating)
                })
            }
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
                scheduleTvAutoCollapse()
            },
            onChannelLongClick = { channel -> showTvReminderDialog(channel) }
        )
        binding.btnGuideRefresh.setOnClickListener {
            Toast.makeText(this, "Refreshing guide…", Toast.LENGTH_SHORT).show()
            viewModel.loadEpgForChannels(viewModel.channels.value)
        }

        binding.tvRvCategories.layoutManager = LinearLayoutManager(this)
        binding.tvRvCategories.adapter = categoryAdapter
        binding.tvRvContent.layoutManager = LinearLayoutManager(this)
        binding.tvRvContent.adapter = channelAdapter
        binding.tvRvEpgGuide.layoutManager = LinearLayoutManager(this)
        binding.tvRvEpgGuide.adapter = epgGuideAdapter
    }

    // ── Left panel drill-down ────────────────────────────────────────────────

    // Measured once from the sidebar's own button labels — the left panel used to be a
    // fixed 280dp regardless of how short "GUIDE" or "LIVE" actually are, wasting width the
    // drilled-in category/channel/guide lists could use instead.
    private var sidebarContentWidthPx: Int = 0

    private fun computeSidebarContentWidth(): Int {
        if (sidebarContentWidthPx > 0) return sidebarContentWidthPx
        val buttons = listOf(
            binding.btnTvFavorites, binding.btnTvLive, binding.btnTvCategories, binding.btnTvMovies,
            binding.btnTvSeries, binding.btnTvGuide, binding.btnTvRecordings, binding.btnTvSettings
        )
        val density = resources.displayMetrics.density
        val paint = android.graphics.Paint().apply {
            textSize = 13f * resources.displayMetrics.scaledDensity
        }
        val maxWidth = buttons.maxOf { btn ->
            paint.measureText(btn.text.toString()) + btn.paddingStart + btn.paddingEnd
        }
        sidebarContentWidthPx = (maxWidth + 16 * density).toInt().coerceIn((160 * density).toInt(), (280 * density).toInt())
        return sidebarContentWidthPx
    }

    // Narrow (content-fit) while the sidebar itself is showing; wider once drilled into a
    // category/channel/guide list so that list gets the freed-up space instead of staying
    // pinned to the sidebar's own narrow width.
    private fun resizeLeftPanel(expanded: Boolean) {
        val params = binding.tvLeftPanel.layoutParams
        params.width = if (expanded) (420 * resources.displayMetrics.density).toInt() else computeSidebarContentWidth()
        binding.tvLeftPanel.layoutParams = params
    }

    private val tvAutoCollapseHandler = Handler(Looper.getMainLooper())
    private val tvAutoCollapseRunnable = Runnable { showSidebar() }

    // Mirrors the phone's landscape behavior: after picking a channel to play, the
    // list/category panel auto-collapses back to the sidebar a few seconds later, handing
    // the full right side back to the mini player instead of leaving the list parked open.
    private fun scheduleTvAutoCollapse() {
        tvAutoCollapseHandler.removeCallbacks(tvAutoCollapseRunnable)
        tvAutoCollapseHandler.postDelayed(tvAutoCollapseRunnable, 10_000L)
    }

    private fun cancelTvAutoCollapse() {
        tvAutoCollapseHandler.removeCallbacks(tvAutoCollapseRunnable)
    }

    private fun showSidebar() {
        cancelTvAutoCollapse()
        navState = NavState.SIDEBAR
        navHasCategoryStep = false
        resizeLeftPanel(expanded = false)
        binding.tvSidebar.visibility = View.VISIBLE
        binding.tvCatPanel.visibility = View.GONE
        binding.tvChanPanel.visibility = View.GONE
        binding.tvGuidePanel.visibility = View.GONE
        activeSidebarButton().requestFocus()
        resetMiniPreviewToNowPlaying()
    }

    /** Undoes the scroll-preview text left behind by channelAdapter.onChannelFocused, so the
     * mini-player's name/EPG match what's actually playing once the user leaves the channel list. */
    private fun resetMiniPreviewToNowPlaying() {
        if (currentMiniStreamId == -1) return
        binding.tvTvChannelName.text = currentMiniTitle
        val epgText = viewModel.channelEpgText.value[currentMiniStreamId]
        binding.tvTvEpg.text = epgText ?: ""
        val progress = viewModel.channelEpgProgress.value[currentMiniStreamId] ?: 0
        if (progress > 0) {
            binding.tvEpgProgress.progress = progress
            binding.tvEpgProgress.visibility = View.VISIBLE
        } else {
            binding.tvEpgProgress.visibility = View.GONE
        }
    }

    private fun showCategoryPanel(title: String) {
        // Arm (not just cancel) the auto-collapse here too — previously it only got scheduled
        // once a channel was actually picked, so just browsing categories/channels without
        // ever selecting one left the panel open indefinitely instead of returning to the
        // sidebar after the same 10s of inactivity.
        scheduleTvAutoCollapse()
        navState = NavState.CATEGORIES
        navHasCategoryStep = true
        resizeLeftPanel(expanded = true)
        binding.tvSidebar.visibility = View.GONE
        binding.tvCatPanel.visibility = View.VISIBLE
        binding.tvChanPanel.visibility = View.GONE
        binding.tvGuidePanel.visibility = View.GONE
        binding.tvCatTitle.text = title
        focusAdapterPositionRetrying(binding.tvRvCategories, 0)
        resetMiniPreviewToNowPlaying()
    }

    private fun showChannelPanel(title: String) {
        scheduleTvAutoCollapse()
        navState = NavState.CHANNELS
        resizeLeftPanel(expanded = true)
        binding.tvSidebar.visibility = View.GONE
        binding.tvCatPanel.visibility = View.GONE
        binding.tvChanPanel.visibility = View.VISIBLE
        binding.tvGuidePanel.visibility = View.GONE
        binding.tvChanTitle.text = title
        pendingContentFocus = true
        // Movies' channel-list title is the picked CATEGORY's name (e.g. "Action"), not
        // "MOVIES" — currentSection is the only reliable signal for which content type is
        // actually showing, same reasoning the search-dispatch when(currentSection) block uses.
        binding.tvBtnChanSort.visibility =
            if (title == "LIVE" || currentSection == Section.MOVIES || currentSection == Section.SERIES) View.VISIBLE else View.GONE
        when {
            title == "LIVE" -> updateTvSortButtonLabel()
            currentSection == Section.MOVIES || currentSection == Section.SERIES -> binding.tvBtnChanSort.text = "⇅ Sort"
        }
        binding.tvBtnChanRefresh.visibility = if (title == "PROVIDERS") View.VISIBLE else View.GONE
    }

    private fun updateTvSortButtonLabel() {
        binding.tvBtnChanSort.text = when (viewModel.channelSort.value) {
            HomeViewModel.ChannelSort.DEFAULT -> "⇅ Default"
            HomeViewModel.ChannelSort.NAME_AZ -> "⇅ A-Z"
            HomeViewModel.ChannelSort.MOST_WATCHED -> "⇅ Popular"
            HomeViewModel.ChannelSort.RECENTLY_WATCHED -> "⇅ Recent"
            HomeViewModel.ChannelSort.MOST_RELIABLE -> "⇅ Reliable"
        }
    }

    // Phone's Movies/Series tabs have a dedicated sort button (HomeActivity.showVodSortDialog/
    // showSeriesSortDialog) with no TV equivalent — tvBtnChanSort only ever drove Live's
    // cycleSort(). Reusing the same two dialogs here (they're plain AlertDialogs over
    // viewModel state, portable as-is) rather than duplicating the option lists.
    private fun showTvVodSortDialog() {
        val options = listOf(
            HomeViewModel.VodSort.DEFAULT to "Default",
            HomeViewModel.VodSort.RATING_DESC to "Rating (High to Low)",
            HomeViewModel.VodSort.YEAR_NEWEST to "Year (Newest First)",
            HomeViewModel.VodSort.YEAR_OLDEST to "Year (Oldest First)",
            HomeViewModel.VodSort.RECENTLY_ADDED to "Recently Added"
        )
        val labels = options.map { it.second }.toTypedArray()
        val current = options.indexOfFirst { it.first == viewModel.vodSort.value }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort Movies")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.setVodSort(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTvSeriesSortDialog() {
        val options = listOf(
            HomeViewModel.SeriesSort.DEFAULT to "Default",
            HomeViewModel.SeriesSort.RATING_DESC to "Rating (High to Low)",
            HomeViewModel.SeriesSort.YEAR_NEWEST to "Year (Newest First)",
            HomeViewModel.SeriesSort.YEAR_OLDEST to "Year (Oldest First)",
            HomeViewModel.SeriesSort.RECENTLY_ADDED to "Recently Added"
        )
        val labels = options.map { it.second }.toTypedArray()
        val current = options.indexOfFirst { it.first == viewModel.seriesSort.value }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort Series")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.setSeriesSort(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // The EPG guide used to live permanently under the mini player, always reachable via
    // D-pad Right from the channel list; it's its own sidebar section now instead, shown
    // full-panel the same way the category/channel panels are.
    private fun showGuidePanel() {
        scheduleTvAutoCollapse()
        navState = NavState.CHANNELS
        navHasCategoryStep = false
        resizeLeftPanel(expanded = true)
        binding.tvSidebar.visibility = View.GONE
        binding.tvCatPanel.visibility = View.GONE
        binding.tvChanPanel.visibility = View.GONE
        binding.tvGuidePanel.visibility = View.VISIBLE
        focusAdapterPositionRetrying(binding.tvRvEpgGuide, 0)
        resetMiniPreviewToNowPlaying()
    }

    private fun moveSidebarFocus(up: Boolean) {
        // Order must match the visual top-to-bottom order in activity_tv_home.xml —
        // Providers sits directly under Favorites now.
        val buttons = listOf(
            binding.btnTvFavorites,
            binding.btnTvProviders,
            binding.btnTvLive,
            binding.btnTvCategories,
            binding.btnTvMovies,
            binding.btnTvSeries,
            binding.btnTvGuide,
            binding.btnTvRecordings,
            binding.btnTvSettings
        ).filter { it.visibility == View.VISIBLE }
        val idx = buttons.indexOfFirst { it == currentFocus }
        if (idx < 0) { buttons.firstOrNull()?.requestFocus(); return }
        buttons.getOrNull(if (up) idx - 1 else idx + 1)?.requestFocus()
    }


    // ── Sidebar navigation ───────────────────────────────────────────────────

    private val sectionButtons get() = listOf(
        binding.btnTvFavorites,
        binding.btnTvProviders,
        binding.btnTvLive,
        binding.btnTvCategories,
        binding.btnTvMovies,
        binding.btnTvSeries,
        binding.btnTvGuide
    )

    private fun setupSidebar() {
        binding.btnTvLive.setOnClickListener { selectSection(Section.LIVE) }
        binding.btnTvCategories.setOnClickListener { selectSection(Section.CATEGORIES) }
        binding.btnTvMovies.setOnClickListener { selectSection(Section.MOVIES) }
        binding.btnTvSeries.setOnClickListener { selectSection(Section.SERIES) }
        binding.btnTvFavorites.setOnClickListener { selectSection(Section.FAVORITES) }
        binding.btnTvGuide.setOnClickListener { selectSection(Section.GUIDE) }
        binding.btnTvProviders.setOnClickListener { selectSection(Section.PROVIDERS) }
        // Phone reaches these via a dedicated What's On button's click/long-click — TV has no
        // such button (Guide is a sidebar entry, not a tab), so both live behind one long-press
        // here instead, picked via a chooser rather than trying to split them across gestures.
        binding.btnTvGuide.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(arrayOf("What's On Now", "Up Next — Favorites")) { _, which ->
                    if (which == 0) showTvWhatsOnNow() else showTvUpNextTicker()
                }
                .show()
            true
        }
        binding.btnTvSeries.setOnLongClickListener { showTvContinueSeriesTicker(); true }
        binding.tvBtnChanRefresh.setOnClickListener {
            viewModel.refreshMergedChannels()
            Toast.makeText(this, "Refreshing all providers…", Toast.LENGTH_SHORT).show()
        }
        binding.btnTvRecordings.setOnClickListener {
            startActivity(Intent(this, TvRecordingActivity::class.java))
        }
        binding.btnTvSettings.setOnClickListener {
            startActivity(Intent(this, TvSettingsActivity::class.java))
        }
        binding.tvBtnCatBack.setOnClickListener { showSidebar() }
        binding.tvBtnChanBack.setOnClickListener {
            if (navHasCategoryStep) showCategoryPanel(binding.tvCatTitle.text.toString())
            else showSidebar()
        }
        binding.tvBtnGuideBack.setOnClickListener { showSidebar() }
        binding.tvBtnChanSort.setOnClickListener {
            when (currentSection) {
                Section.MOVIES -> showTvVodSortDialog()
                Section.SERIES -> showTvSeriesSortDialog()
                else -> {
                    viewModel.cycleSort()
                    updateTvSortButtonLabel()
                }
            }
        }
    }

    private fun selectSection(section: Section) {
        currentSection = section
        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()) }
        activeSidebarButton().setTextColor(currentAccent)
        binding.tvGenreChipScroll.visibility = View.GONE

        when (section) {
            // Used to jump straight into whatever category the currently-playing channel
            // belonged to (openLiveOnCurrentChannel/openFavCategoriesOnCurrentChannel) — handy
            // in theory, but in practice it meant tapping Live/Categories from the sidebar
            // rarely showed the actual category list, since something was almost always
            // already playing. Always show the category list first now; picking a category
            // still lands you in that category's channel list same as before.
            Section.LIVE -> { showLive(); showCategoryPanel("LIVE") }
            Section.CATEGORIES -> { showFavCategories(); showCategoryPanel("CATEGORIES") }
            Section.MOVIES -> {
                showMovies()
                showCategoryPanel("MOVIES")
            }
            Section.SERIES -> {
                showSeries()
                showChannelPanel("SERIES")
            }
            // Same reasoning as Live/Categories above: always show the genre-tile picker first
            // instead of jumping straight into a flat "currently playing" list — otherwise the
            // genre tiles themselves would be almost unreachable from the sidebar. The picker
            // decides asynchronously (once favorites are counted) whether to show tiles or skip
            // straight to the channel list, and shows the appropriate panel itself either way.
            Section.FAVORITES -> showFavoriteGenrePicker()
            Section.GUIDE -> showGuidePanel()
            Section.PROVIDERS -> showMergedChannelsPanel()
        }
    }

    private fun activeSidebarButton() = when (currentSection) {
        Section.LIVE       -> binding.btnTvLive
        Section.CATEGORIES -> binding.btnTvCategories
        Section.MOVIES     -> binding.btnTvMovies
        Section.SERIES     -> binding.btnTvSeries
        Section.FAVORITES  -> binding.btnTvFavorites
        Section.GUIDE      -> binding.btnTvGuide
        Section.PROVIDERS  -> binding.btnTvProviders
    }

    // Browse-and-play merged view across every configured server (Settings > Providers).
    // 3-level drill-down (server -> category -> channels), same shape as Live, since a single
    // provider can itself have tens of thousands of channels. Merged favorites are viewed from
    // the main Favorites section now (combined with primary favorites, auto genre-classified)
    // instead of a dedicated "★ Favorites" tile here — favoriting/folder-assignment per channel
    // still works via each row's star and long-press menu.
    private fun showMergedChannelsPanel() {
        viewModel.resetMergedSelection()
        binding.tvRvContent.adapter = categoryAdapter
        categoryAdapter.submitList(mergedServersToSynthetic(viewModel.mergedServers.value))
        showCategoryPanel("PROVIDERS")
    }

    private val NO_CATEGORY_ID = "__uncategorized__"
    private val FAV_GENRE_ALL_ID = "All"

    private fun genreFilterFavorites(genre: String, favorites: List<CombinedFavorite>): List<CombinedFavorite> {
        if (genre == FAV_GENRE_ALL_ID) return favorites
        return favorites.filter { GenreClassifier.matches(genre, it.categoryName) }
    }

    private fun favoriteGenresToSynthetic(favorites: List<CombinedFavorite>): List<CategoryEntity> {
        val favCategoryNames = favorites.mapNotNull { it.categoryName }
        val detected = GenreClassifier.detectGenres(favCategoryNames)
        // Fewer than 2 detected genres means "All" is the only real option — just skip
        // straight to the flat list instead of a picker with a single meaningless tile.
        return if (detected.size <= 1) emptyList() else detected.map { genre ->
            val count = if (genre == FAV_GENRE_ALL_ID) favorites.size else genreFilterFavorites(genre, favorites).size
            CategoryEntity(genre, "$genre ($count)", 0, "fav_genre")
        }
    }

    private var favoritesShowingGenrePicker = false
    // Read by the shared viewModel.combinedFavorites collector too, so a later re-emission of
    // the underlying favorites Flow (e.g. a background sync) keeps applying the same filter
    // instead of the collector's normal unfiltered submission silently overwriting it.
    private var activeFavoriteGenre = FAV_GENRE_ALL_ID

    private fun showFavoriteGenrePicker() {
        lifecycleScope.launch {
            val favorites = viewModel.getCombinedFavoritesSnapshot()
            val tiles = favoriteGenresToSynthetic(favorites)
            if (tiles.isEmpty()) {
                // No real genre variety among current favorites — skip the picker entirely.
                showFavoriteGenreChannels(FAV_GENRE_ALL_ID, "FAVORITES")
                return@launch
            }
            favoritesShowingGenrePicker = true
            binding.tvRvContent.adapter = categoryAdapter
            categoryAdapter.submitList(tiles)
            showCategoryPanel("FAVORITES")
        }
    }

    private fun showFavoriteGenreChannels(genre: String, title: String) {
        favoritesShowingGenrePicker = false
        activeFavoriteGenre = genre
        binding.tvRvContent.adapter = combinedFavoriteAdapter
        viewModel.selectFavoriteFolderView(null)
        viewModel.checkFavoritesHealth()
        viewModel.checkMergedFavoritesHealth()
        showChannelPanel(title)
        // showChannelPanel() just set pendingContentFocus true, expecting the shared
        // viewModel.combinedFavorites collector to focus position 0 on an unfiltered list —
        // that collector now applies activeFavoriteGenre itself (see observeViewModel), but its
        // focus-position-0 default still isn't what we want here: if the current channel is
        // actually in this genre, scroll/focus straight to it instead of always landing at
        // the top after a background re-emission or first entry.
        pendingContentFocus = false
        lifecycleScope.launch {
            val favorites = genreFilterFavorites(genre, viewModel.getCombinedFavoritesSnapshot())
            val match = favorites.firstOrNull { it.id == currentMiniCombinedFavoriteId }
            if (!currentMiniIsVod && match != null) {
                scrollAndFocusCombinedFavorite(favorites, match.id)
            } else if (favorites.isNotEmpty()) {
                scrollAndFocusCombinedFavorite(favorites, favorites.first().id)
            } else {
                combinedFavoriteAdapter.submitList(favorites)
            }
        }
    }

    private fun scrollAndFocusCombinedFavorite(favorites: List<CombinedFavorite>, id: String) {
        combinedFavoriteAdapter.submitList(favorites) {
            val pos = favorites.indexOfFirst { it.id == id }
            if (pos < 0) {
                binding.tvRvContent.requestFocus()
                return@submitList
            }
            (binding.tvRvContent.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            focusAdapterPositionRetrying(binding.tvRvContent, pos)
        }
    }

    private fun mergedServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedServerSummary>): List<CategoryEntity> {
        // serverIndex == -1 is always whichever provider is currently primary/active — its
        // channels are already fully browsable via the normal Live section, so listing it
        // again here was redundant and confusing next to the actually-"extra" providers.
        return list.filter { it.serverIndex != -1 }.map {
            CategoryEntity(
                categoryId = it.serverIndex.toString(),
                categoryName = "${it.serverNickname} (${it.channelCount})",
                parentId = 0,
                type = "merged_server"
            )
        }
    }

    private fun mergedCategoriesToSynthetic(list: List<com.iptvapp.data.local.entities.MergedCategorySummary>): List<CategoryEntity> =
        list.map {
            CategoryEntity(
                categoryId = it.categoryId ?: NO_CATEGORY_ID,
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.channelCount})",
                parentId = 0,
                type = "merged_category"
            )
        }

    // Tapping a merged channel now behaves like every other channel list: starts in the mini
    // player first, and only goes fullscreen if the mini preview itself is pressed next —
    // currentMiniUrl/Title/StreamId/IsVod are plain generic fields already read by
    // tvMiniPlayerContainer's click listener, so setting them here is all that's needed.
    private fun playMergedChannel(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        lifecycleScope.launch {
            try {
                val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                val title = "${channel.name} · ${channel.serverNickname}"
                // streamId = -1: no DB-backed identity for this channel (it lives only in the
                // merged_channels cache, not the primary server's channels table).
                currentMiniUrl = url
                currentMiniTitle = title
                currentMiniStreamId = -1
                currentMiniServerIndex = channel.serverIndex
                currentMiniMergedStreamId = channel.streamId
                currentMiniIsVod = false
                binding.tvTvChannelName.text = title
                miniPlayer?.let {
                    it.setMediaItem(MediaItem.fromUri(url))
                    it.prepare()
                    it.playWhenReady = true
                }
            } catch (e: Exception) {
                Toast.makeText(this@TvHomeActivity, "Couldn't load this channel — tap Refresh and try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key activity while a panel is open resets the auto-collapse idle timer — this
            // used to only reset on D-pad navigation keys specifically, so typing into a search
            // box (letter/number keys, none of which are D-pad keys) never reset it at all,
            // collapsing back to the sidebar 10s after the last D-pad press even while actively
            // still typing a query.
            if (navState != NavState.SIDEBAR) {
                scheduleTvAutoCollapse()
            }
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
            // Classic remote-control channel entry: works from anywhere (sidebar, browsing a
            // list, watching fullscreen-in-mini-player) except while actually typing into the
            // search box. Looks up the provider's real channel number (ChannelEntity.num),
            // not a position within whatever list happens to be on screen right now — typing
            // "105" should always mean channel 105, regardless of what's currently filtered/
            // sorted/displayed.
            if (digit != null && currentFocus !== binding.tvEtSearch) {
                if (channelNumberBuffer.length < 4) channelNumberBuffer += digit
                channelJumpJob?.cancel()
                binding.tvChannelNumberEntry.text = channelNumberBuffer
                binding.tvChannelNumberEntry.visibility = View.VISIBLE
                channelJumpJob = lifecycleScope.launch {
                    delay(1500)
                    val num = channelNumberBuffer.toIntOrNull()
                    channelNumberBuffer = ""
                    binding.tvChannelNumberEntry.visibility = View.GONE
                    if (num == null) return@launch
                    val channel = viewModel.getChannelByNumber(num)
                    if (channel == null) {
                        Toast.makeText(this@TvHomeActivity, "No channel $num", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    playInMiniPlayer(channel)
                    viewModel.markChannelWatched(channel.streamId)
                    viewModel.setCurrentlyPlaying(channel.streamId)
                }
                return true
            }

            when (event.keyCode) {
                // Sidebar: up/down stays within sidebar buttons only
                KeyEvent.KEYCODE_DPAD_UP -> if (navState == NavState.SIDEBAR) {
                    moveSidebarFocus(up = true); return true
                } else if (binding.tvRvContent.hasFocus()) {
                    if (moveChannelListFocus(up = true)) return true
                    // Reached the top row — explicitly hand focus to the header instead of
                    // trusting default focus search to climb out of the RecyclerView, which
                    // was unreliable and could leave Up simply doing nothing.
                    focusTopOfPanel(); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (navState == NavState.SIDEBAR) {
                    moveSidebarFocus(up = false); return true
                } else if (binding.tvRvContent.hasFocus()) {
                    if (moveChannelListFocus(up = false)) return true
                }
                // Right reaches the mini player (FULL SCREEN button removed — focusing the
                // mini player itself and pressing OK does what that button did instead) from
                // wherever the cursor currently is: sidebar, a category/channel list, or the
                // guide list.
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (binding.tvGenreChipContainer.hasFocus()) {
                    // Let default focus search move between sibling genre chips instead of
                    // jumping straight to the mini player.
                } else when (navState) {
                    NavState.SIDEBAR -> {
                        navState = NavState.CHANNELS
                        binding.tvMiniPlayerContainer.requestFocus()
                        return true
                    }
                    NavState.CATEGORIES -> return true
                    NavState.CHANNELS -> {
                        if (currentFocus !== binding.tvMiniPlayerContainer) {
                            binding.tvMiniPlayerContainer.requestFocus()
                            return true
                        }
                    }
                }
                // Left returns from the mini player to whichever list it was reached from;
                // it no longer drills back a level. Use BACK for that.
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentFocus === binding.tvMiniPlayerContainer) {
                        when {
                            binding.tvGuidePanel.visibility == View.VISIBLE -> binding.tvRvEpgGuide.requestFocus()
                            binding.tvChanPanel.visibility == View.VISIBLE -> binding.tvRvContent.requestFocus()
                            else -> showSidebar()
                        }
                        return true
                    } else if (binding.tvRvContent.hasFocus() || binding.tvRvCategories.hasFocus()) {
                        // Deep in a long list, climbing back up to search/refresh/Back one Up
                        // press at a time was slow and unreliable — Left jumps straight there.
                        focusTopOfPanel(); return true
                    } else if (binding.tvGenreChipContainer.hasFocus()) {
                        // Let default focus search move between sibling genre chips instead of
                        // being swallowed by the CATEGORIES/CHANNELS catch-all below.
                    } else if (navState == NavState.CATEGORIES || navState == NavState.CHANNELS) {
                        return true
                    }
                }
                // Back goes up one drill level. From the guide panel or a channel/movie/
                // series list it returns to the sidebar (or the category panel it drilled
                // from). At the top level (sidebar), require a second Back press within 2s
                // to exit.
                KeyEvent.KEYCODE_BACK -> {
                    when (navState) {
                        NavState.CHANNELS -> {
                            if (binding.tvGuidePanel.visibility == View.VISIBLE) {
                                showSidebar()
                            } else if (navHasCategoryStep) {
                                showCategoryPanel(binding.tvCatTitle.text.toString())
                            } else {
                                showSidebar()
                            }
                            return true
                        }
                        NavState.CATEGORIES -> { showSidebar(); return true }
                        NavState.SIDEBAR -> {
                            val now = System.currentTimeMillis()
                            if (now - lastBackPressTime < 2000L) {
                                finishAffinity()
                            } else {
                                lastBackPressTime = now
                                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_GUIDE -> return true
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (currentMiniUrl.isNotEmpty()) {
                        val pos = miniPlayer?.currentPosition ?: 0L
                        openPlayer(
                    currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = pos,
                    serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                )
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

    // submitList's DiffUtil calculation runs off-thread, and even the commit callback + a
    // couple of post{} frames aren't reliably enough time for a large list's layout pass to
    // actually bind the target position's ViewHolder — findViewHolderForAdapterPosition kept
    // returning null, the fallback requestFocus() on the (still effectively empty) RecyclerView
    // was a no-op, and focus silently stayed on the back arrow button instead of the channel.
    // Retrying across several frames instead of a fixed handful of nested posts makes this
    // reliable regardless of list size or how long that layout pass actually takes.
    private fun scrollAndFocusChannel(list: List<ChannelEntity>, streamId: Int) {
        channelAdapter.submitList(list) {
            val pos = list.indexOfFirst { it.streamId == streamId }
            if (pos < 0) {
                binding.tvRvContent.requestFocus()
                return@submitList
            }
            (binding.tvRvContent.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            focusAdapterPositionRetrying(binding.tvRvContent, pos)
        }
    }

    // Generalizes the fix above to every list on this screen — several other spots
    // (category/EPG-guide panel entry, VOD/series first-item focus) used a single-shot
    // findViewHolderForAdapterPosition()?.requestFocus() ?: rv.requestFocus() that was
    // susceptible to the exact same "ViewHolder isn't laid out yet" race this was built to
    // fix for the channel list specifically; consolidating means the next list added to this
    // screen gets the robust version by default instead of needing its own one-off patch.
    private fun focusAdapterPositionRetrying(rv: RecyclerView, pos: Int, attemptsLeft: Int = 15) {
        rv.post {
            val holder = rv.findViewHolderForAdapterPosition(pos)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else if (attemptsLeft > 0) {
                focusAdapterPositionRetrying(rv, pos, attemptsLeft - 1)
            } else {
                rv.requestFocus()
            }
        }
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
        updateTvVodGenreChips(viewModel.vodCategories.value)
        submitFilteredTvVodCategories(viewModel.vodCategories.value)
    }

    private fun showSeries() {
        binding.tvRvContent.adapter = seriesAdapter
        updateTvSeriesGenreChips(viewModel.series.value)
        submitFilteredTvSeries(viewModel.series.value)
    }

    private fun submitFilteredTvSeries(list: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        val genre = activeTvSeriesGenre
        val filtered = if (genre == null) list
            else list.filter { genre in com.iptvapp.util.GenreBuckets.bucketsFor(it.genre?.split(",").orEmpty()) }
        seriesAdapter.submitList(filtered)
    }

    // ── Genre folder chips (Series/Movies) — same bucketing GenreBuckets provides on phone ──

    private var activeTvSeriesGenre: String? = null
    private var activeTvVodGenre: String? = null

    private fun submitFilteredTvVodCategories(cats: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        val genre = activeTvVodGenre
        val filtered = if (genre == null) cats
            else cats.filter { genre in com.iptvapp.util.GenreBuckets.bucketsFor(listOf(it.categoryName)) }
        categoryAdapter.submitList(filtered)
        if (filtered.isNotEmpty()) viewModel.selectVodCategory(filtered.first().categoryId)
    }

    private fun updateTvSeriesGenreChips(list: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        val genres = com.iptvapp.util.GenreBuckets.presentBuckets(list.map { it.genre?.split(",").orEmpty() })
        if (genres.isEmpty()) { binding.tvGenreChipScroll.visibility = View.GONE; return }
        if (activeTvSeriesGenre != null && genres.none { it.equals(activeTvSeriesGenre, ignoreCase = true) }) activeTvSeriesGenre = null
        binding.tvGenreChipScroll.visibility = View.VISIBLE
        val container = binding.tvGenreChipContainer
        container.removeAllViews()
        container.addView(buildTvGenreChip("All", activeTvSeriesGenre == null) {
            activeTvSeriesGenre = null
            updateTvSeriesGenreChips(viewModel.series.value)
            submitFilteredTvSeries(viewModel.series.value)
        })
        for (genre in genres) {
            container.addView(buildTvGenreChip(genre, activeTvSeriesGenre?.equals(genre, ignoreCase = true) == true) {
                activeTvSeriesGenre = genre
                updateTvSeriesGenreChips(viewModel.series.value)
                submitFilteredTvSeries(viewModel.series.value)
            })
        }
    }

    private fun updateTvVodGenreChips(cats: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        val genres = com.iptvapp.util.GenreBuckets.presentBuckets(cats.map { listOf(it.categoryName) })
        if (genres.isEmpty()) { binding.tvGenreChipScroll.visibility = View.GONE; return }
        if (activeTvVodGenre != null && genres.none { it.equals(activeTvVodGenre, ignoreCase = true) }) activeTvVodGenre = null
        binding.tvGenreChipScroll.visibility = View.VISIBLE
        val container = binding.tvGenreChipContainer
        container.removeAllViews()
        container.addView(buildTvGenreChip("All", activeTvVodGenre == null) {
            activeTvVodGenre = null
            updateTvVodGenreChips(viewModel.vodCategories.value)
            submitFilteredTvVodCategories(viewModel.vodCategories.value)
        })
        for (genre in genres) {
            container.addView(buildTvGenreChip(genre, activeTvVodGenre?.equals(genre, ignoreCase = true) == true) {
                activeTvVodGenre = genre
                updateTvVodGenreChips(viewModel.vodCategories.value)
                submitFilteredTvVodCategories(viewModel.vodCategories.value)
            })
        }
    }

    private fun buildTvGenreChip(label: String, selected: Boolean, onClick: () -> Unit): View {
        return android.widget.Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            setBackgroundResource(com.iptvapp.R.drawable.tv_sidebar_focus)
            setPadding(28, 0, 28, 0)
            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * resources.displayMetrics.density).toInt()
            ).also { it.marginEnd = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener { onClick() }
        }
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
                // Many TV soft keyboards commit typed characters straight through the
                // InputConnection without ever generating a KeyEvent, so dispatchKeyEvent's
                // idle-timer reset (which only sees real key events) never fires while typing
                // that way — the panel could still collapse mid-search. TextWatcher fires
                // regardless of how the text got there, so reset the timer here too.
                if (navState != NavState.SIDEBAR) scheduleTvAutoCollapse()
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
            Section.SERIES -> viewModel.searchSeries(query)
            Section.PROVIDERS -> {
                if (query.isBlank()) {
                    showMergedChannelsPanel()
                } else {
                    viewModel.searchMergedChannels(query)
                    binding.tvRvContent.adapter = mergedChannelAdapter
                    showChannelPanel("SEARCH RESULTS")
                }
            }
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
            viewModel.liveCategories.collect { cats ->
                if (currentSection == Section.LIVE) categoryAdapter.submitList(cats)
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
                    if (focusedPos >= 0) focusAdapterPositionRetrying(binding.tvRvContent, focusedPos)
                    else if (wantFocus) focusAdapterPositionRetrying(binding.tvRvContent, 0)
                }
                viewModel.loadEpgForChannels(channels)
            }
        }
        lifecycleScope.launch {
            viewModel.combinedFavorites.collect { favoritesRaw ->
                if (currentSection != Section.FAVORITES) return@collect
                if (binding.tvRvContent.adapter !== combinedFavoriteAdapter) return@collect
                // Underlying Flow always emits every favorite unfiltered; apply the active
                // genre filter here too so a later re-emission (background sync, health check)
                // doesn't silently overwrite showFavoriteGenreChannels()'s filtered submission.
                val favorites = genreFilterFavorites(activeFavoriteGenre, favoritesRaw)

                val focusedChild = binding.tvRvContent.focusedChild
                val focusedPos = if (focusedChild != null)
                    binding.tvRvContent.getChildAdapterPosition(focusedChild) else -1
                val wantFocus = pendingContentFocus
                if (wantFocus) pendingContentFocus = false

                combinedFavoriteAdapter.submitList(favorites) {
                    if (focusedPos >= 0) focusAdapterPositionRetrying(binding.tvRvContent, focusedPos)
                    else if (wantFocus) focusAdapterPositionRetrying(binding.tvRvContent, 0)
                }
                viewModel.loadEpgForChannels(favorites.mapNotNull { (it as? CombinedFavorite.Primary)?.channel })
                viewModel.loadEpgForMergedChannels(favorites.mapNotNull { (it as? CombinedFavorite.Merged)?.channel })
            }
        }
        lifecycleScope.launch {
            viewModel.vod.collect {
                if (currentSection == Section.MOVIES) {
                    val wantFocus = pendingContentFocus
                    if (wantFocus) pendingContentFocus = false
                    vodAdapter.submitList(it) {
                        if (wantFocus) focusAdapterPositionRetrying(binding.tvRvContent, 0)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                if (currentSection == Section.SERIES) {
                    val wantFocus = pendingContentFocus
                    if (wantFocus) pendingContentFocus = false
                    updateTvSeriesGenreChips(it)
                    val genre = activeTvSeriesGenre
                    val filtered = if (genre == null) it
                        else it.filter { s -> genre in com.iptvapp.util.GenreBuckets.bucketsFor(s.genre?.split(",").orEmpty()) }
                    seriesAdapter.submitList(filtered) {
                        if (wantFocus) focusAdapterPositionRetrying(binding.tvRvContent, 0)
                    }
                }
            }
        }
        // combinedFavoriteAdapter's EPG/health maps are string-keyed ("primary:<id>" or
        // "<serverIndex>:<id>") to cover both sources at once — each collector below only owns
        // its half of the key space, so re-derive the union from both StateFlows' latest values
        // rather than trying to patch just the changed half in isolation.
        fun republishCombinedEpgText() {
            combinedFavoriteAdapter.submitEpgText(
                viewModel.channelEpgText.value.mapKeys { (id, _) -> "primary:$id" } + viewModel.mergedEpgText.value
            )
        }
        fun republishCombinedEpgNextText() {
            combinedFavoriteAdapter.submitEpgNextText(viewModel.channelEpgNextText.value.mapKeys { (id, _) -> "primary:$id" })
        }
        fun republishCombinedHealth() {
            combinedFavoriteAdapter.submitHealth(
                viewModel.channelHealth.value.mapKeys { (id, _) -> "primary:$id" } + viewModel.mergedHealth.value
            )
        }
        lifecycleScope.launch {
            viewModel.currentlyPlayingStreamId.collect {
                channelAdapter.setCurrentlyPlayingStreamId(it)
                // A live primary channel takes over the combined highlight; streamId == -1 just
                // means "not a primary channel right now" — don't clobber a merged channel's
                // highlight in that case (see currentMiniCombinedFavoriteId kdoc).
                if (it >= 0) {
                    currentMiniCombinedFavoriteId = "primary:$it"
                    combinedFavoriteAdapter.setCurrentlyPlayingId(currentMiniCombinedFavoriteId)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect { channelAdapter.submitEpgText(it); republishCombinedEpgText() }
        }
        lifecycleScope.launch {
            viewModel.channelEpgNextText.collect { channelAdapter.submitEpgNextText(it); republishCombinedEpgNextText() }
        }
        lifecycleScope.launch {
            viewModel.channelEpgProgress.collect { channelAdapter.submitEpgProgress(it) }
        }
        lifecycleScope.launch {
            viewModel.channelHealth.collect { channelAdapter.submitHealth(it); republishCombinedHealth() }
        }
        lifecycleScope.launch {
            viewModel.mergedChannels.collect {
                if (currentSection == Section.PROVIDERS) {
                    mergedChannelAdapter.submitList(it)
                    viewModel.loadEpgForMergedChannels(it)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedEpgText.collect { mergedChannelAdapter.submitEpgText(it); republishCombinedEpgText() }
        }
        lifecycleScope.launch {
            viewModel.mergedHealth.collect { mergedChannelAdapter.submitHealth(it); republishCombinedHealth() }
        }
        lifecycleScope.launch {
            viewModel.vodCategories.collect {
                if (currentSection == Section.MOVIES) {
                    updateTvVodGenreChips(it)
                    submitFilteredTvVodCategories(it)
                }
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

    private var allEpgChannels: List<ChannelEntity> = emptyList()

    private fun observeEpgGuide() {
        lifecycleScope.launch {
            viewModel.channels.collect { channels ->
                allEpgChannels = channels
                val epgText = viewModel.channelEpgText.value
                epgGuideAdapter.submitList(channels.filter { hasRealEpg(epgText, it.streamId) })
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect { epgText ->
                epgGuideAdapter.submitEpgText(epgText)
                epgGuideAdapter.submitList(allEpgChannels.filter { hasRealEpg(epgText, it.streamId) })
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgNextText.collect { epgGuideAdapter.submitEpgNextText(it) }
        }
        lifecycleScope.launch {
            viewModel.channelEpgProgress.collect { epgGuideAdapter.submitEpgProgress(it) }
        }
    }


    /** Android's default focus search can fail to find the next/previous item while fast
     * D-pad scrolling outruns RecyclerView's layout of not-yet-created views — the same
     * failure mode already fixed for the EPG guide. When that happens it doesn't just stop;
     * it escapes the RecyclerView entirely and lands on the nearest focusable view outside
     * it (the back button above the list), which then requires scrolling back down into the
     * list to recover — repeating every time the same layout race reoccurs. Moving focus by
     * adapter position explicitly, the same fix used for the guide, sidesteps the race
     * entirely instead of depending on whichever views happen to be attached at that instant. */
    /** Returns true if it moved focus within the list — false means the caller should let the
     * key event fall through to default focus search instead of swallowing it. Previously this
     * always consumed the key even at the top row, which meant Up could never escape the list
     * to reach the genre chips, search box, or Back button above it. */
    private fun moveChannelListFocus(up: Boolean): Boolean {
        val rv = binding.tvRvContent
        val focusedDescendant = rv.findFocus() ?: return false
        // getChildAdapterPosition requires a direct child of the RecyclerView — findFocus()
        // can return a nested view inside the row (e.g. a favorite-star button), which throws
        // IllegalArgumentException instead of just failing gracefully. Walk up to the actual
        // item view first.
        var itemView: View? = focusedDescendant
        while (itemView != null && itemView.parent !== rv) {
            itemView = itemView.parent as? View
        }
        val focused = itemView ?: return false
        val pos = rv.getChildAdapterPosition(focused)
        if (pos == RecyclerView.NO_POSITION) return false
        val itemCount = rv.adapter?.itemCount ?: 0
        val target = if (up) pos - 1 else pos + 1
        if (target < 0 || target >= itemCount) return false
        val holder = rv.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            rv.scrollToPosition(target)
            rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
        return true
    }

    /** Explicit escape hatch to the header row above whichever panel is open (genre chips if
     * visible, else the search box, else that panel's own Back button) — deep in a long
     * channel list, relying on Android's default focus search to climb all the way back up
     * there via Up presses was unreliable, and there was no faster way to reach search/refresh/
     * Back than repeatedly pressing Up. Bound to D-pad Left as a direct shortcut, and also used
     * to make Up reliably escape the list instead of just being swallowed at the top row. */
    private fun focusTopOfPanel() {
        when {
            binding.tvGuidePanel.visibility == View.VISIBLE -> binding.tvBtnGuideBack.requestFocus()
            binding.tvChanPanel.visibility == View.VISIBLE -> {
                if (binding.tvGenreChipScroll.visibility == View.VISIBLE && binding.tvGenreChipContainer.childCount > 0) {
                    binding.tvGenreChipContainer.getChildAt(0).requestFocus()
                } else {
                    binding.tvEtSearch.requestFocus()
                }
            }
            binding.tvCatPanel.visibility == View.VISIBLE -> binding.tvBtnCatBack.requestFocus()
        }
    }

    private fun hasRealEpg(epgText: Map<Int, String>, streamId: Int): Boolean {
        val text = epgText[streamId] ?: return false
        return text.isNotBlank() && text != "—"
    }

    /** Brings the just-selected channel's row into view in the EPG guide, so you can see
     * what it's playing without having to manually scroll the right panel to find it. */
    private fun scrollGuideToChannel(streamId: Int) {
        val idx = epgGuideAdapter.currentList.indexOfFirst { it.streamId == streamId }
        if (idx >= 0) {
            binding.tvRvEpgGuide.smoothScrollToPosition(idx)
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
        resumeMs: Long = 0L,
        serverIndex: Int = -1,
        mergedStreamId: Int = -1
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
                putExtra("server_index", serverIndex)
                putExtra("merged_stream_id", mergedStreamId)
            })
        }
    }

    // TV equivalent of HomeActivity.showMoveToFolderDialog — TV's merged-channel long-press
    // dialog never offered this, unlike phone's showMergedChannelActionsMenu.
    private fun showMoveToFolderDialog(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        showMoveToFolderDialog("Move \"${channel.name}\" to", onCancel = {}) { folderId ->
            viewModel.setMergedChannelFolder(channel, folderId)
            Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoveToFolderDialog(title: String, onCancel: () -> Unit, onPicked: (Int?) -> Unit) {
        val folders = viewModel.favoriteFolders.value
        val labels = mutableListOf("Unsorted") + folders.map { it.name } + "+ New Folder"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                when (i) {
                    0 -> onPicked(null)
                    labels.size - 1 -> {
                        val et = android.widget.EditText(this).apply { hint = "Folder name" }
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("New Folder")
                            .setView(et)
                            .setPositiveButton("Create") { _, _ ->
                                val name = et.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    lifecycleScope.launch {
                                        val newId = viewModel.createFavoriteFolderAndGetId(name)
                                        onPicked(newId)
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    else -> onPicked(folders[i - 1].id)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .show()
    }

    // TV equivalent of HomeActivity.showChannelActionsMenuDialog — the long-press menu used to
    // jump straight to the reminder flow (showTvReminderDialog), with no way to bulk-select
    // favorites, hide a channel, or see similar channels on TV at all.
    private fun showTvChannelActionsMenu(channel: ChannelEntity) {
        val options = mutableListOf(
            "Set Reminder",
            if (bulkSelectedIds.contains(channel.streamId)) "Deselect (bulk)" else "Select (bulk add to favorites)",
            "Hide Channel",
            "Channels Like This"
        )
        if (bulkSelectMode && bulkSelectedIds.isNotEmpty()) {
            options.add(0, "✓ Add ${bulkSelectedIds.size} selected to favorites")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(options.toTypedArray()) { _, i ->
                when (options[i]) {
                    "Set Reminder" -> showTvReminderDialog(channel)
                    "Select (bulk add to favorites)" -> {
                        bulkSelectMode = true
                        bulkSelectedIds.add(channel.streamId)
                        channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                        Toast.makeText(this, "${bulkSelectedIds.size} selected — select more, or wait to add them", Toast.LENGTH_SHORT).show()
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 3000)
                    }
                    "Deselect (bulk)" -> {
                        bulkSelectedIds.remove(channel.streamId)
                        channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                        if (bulkSelectedIds.isEmpty()) {
                            bulkSelectMode = false
                            bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        }
                    }
                    "Hide Channel" -> {
                        viewModel.hideChannel(channel.streamId)
                        Toast.makeText(this, "${channel.name} hidden. Unhide in Settings → Display.", Toast.LENGTH_SHORT).show()
                    }
                    "Channels Like This" -> showTvSimilarChannelsSheet(channel)
                    else -> if (options[i].startsWith("✓ Add")) {
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                        Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                        bulkSelectedIds.clear()
                        bulkSelectMode = false
                        channelAdapter.submitBulkSelection(emptySet())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTvSimilarChannelsSheet(channel: ChannelEntity) {
        viewModel.loadSimilarChannels(channel)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvHomeActivity)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Channels like ${channel.name}")
            .setView(rv)
            .setNegativeButton("Close") { _, _ -> viewModel.clearSimilarChannels() }
            .create()
        val similarAdapter = ChannelAdapter(
            onChannelClick = { similar ->
                dialog.dismiss()
                viewModel.clearSimilarChannels()
                lifecycleScope.launch { playInMiniPlayer(similar) }
            },
            onFavoriteClick = { similar -> viewModel.toggleChannelFavorite(similar.streamId) }
        )
        rv.adapter = similarAdapter
        lifecycleScope.launch {
            viewModel.similarChannels.collect { list ->
                similarAdapter.submitList(list)
            }
        }
        dialog.show()
    }

    // TV equivalents of HomeActivity's showWhatsOnNow/showUpNextTicker/showContinueSeriesTicker —
    // phone-only long-press tickers with no TV trigger at all. Triggered by long-pressing the
    // Guide/Series sidebar buttons instead of a tab (TV has no tabs) — see setupSidebar.
    private fun showTvWhatsOnNow() {
        val channels = viewModel.channels.value.ifEmpty { return }
        val epgTextMap = viewModel.channelEpgText.value
        val epgProgressMap = viewModel.channelEpgProgress.value
        val withProgram = channels.filter { epgTextMap[it.streamId]?.isNotBlank() == true }.ifEmpty { channels }
        val inflater = layoutInflater
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvHomeActivity)
            setPadding(0, 8, 0, 8)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("What's On Now")
            .setView(rv)
            .setNegativeButton("Close", null)
            .create()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(val v: View) : RecyclerView.ViewHolder(v)
            override fun getItemCount() = withProgram.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
                VH(inflater.inflate(com.iptvapp.R.layout.item_whats_on, parent, false))
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val ch = withProgram[position]
                val v = holder.itemView
                v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonChannel).text = ch.name
                v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonProgram).text = epgTextMap[ch.streamId] ?: ""
                val progress = epgProgressMap[ch.streamId] ?: 0
                val pb = v.findViewById<android.widget.ProgressBar>(com.iptvapp.R.id.pbWonProgress)
                pb.progress = progress
                pb.visibility = if (progress > 0) View.VISIBLE else View.INVISIBLE
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

    private fun showTvUpNextTicker() {
        lifecycleScope.launch {
            val entries = viewModel.getUpNextTicker()
            if (entries.isEmpty()) {
                Toast.makeText(this@TvHomeActivity, "No upcoming EPG data for your favorites", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val inflater = layoutInflater
            val rv = RecyclerView(this@TvHomeActivity).apply {
                layoutManager = LinearLayoutManager(this@TvHomeActivity)
                setPadding(0, 8, 0, 8)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@TvHomeActivity)
                .setTitle("Up Next — Favorites")
                .setView(rv)
                .setNegativeButton("Close", null)
                .create()
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                inner class VH(val v: View) : RecyclerView.ViewHolder(v)
                override fun getItemCount() = entries.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
                    VH(inflater.inflate(com.iptvapp.R.layout.item_whats_on, parent, false))
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val entry = entries[position]
                    val v = holder.itemView
                    v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonChannel).text = entry.channel.name
                    v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonProgram).text =
                        "${timeFmt.format(Date(entry.startTimestamp))} · ${entry.title}"
                    v.findViewById<android.widget.ProgressBar>(com.iptvapp.R.id.pbWonProgress).visibility = View.INVISIBLE
                    com.bumptech.glide.Glide.with(v)
                        .load(entry.channel.streamIcon)
                        .placeholder(android.R.drawable.ic_media_play)
                        .into(v.findViewById(com.iptvapp.R.id.ivWonLogo))
                    v.setOnClickListener {
                        dialog.dismiss()
                        lifecycleScope.launch {
                            playInMiniPlayer(entry.channel)
                            viewModel.markChannelWatched(entry.channel.streamId)
                            viewModel.setCurrentlyPlaying(entry.channel.streamId)
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

    private fun showTvContinueSeriesTicker() {
        lifecycleScope.launch {
            val entries = viewModel.getContinueSeriesTicker()
            if (entries.isEmpty()) {
                Toast.makeText(this@TvHomeActivity, "No in-progress series yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val inflater = layoutInflater
            val rv = RecyclerView(this@TvHomeActivity).apply {
                layoutManager = LinearLayoutManager(this@TvHomeActivity)
                setPadding(0, 8, 0, 8)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@TvHomeActivity)
                .setTitle("Continue Watching")
                .setView(rv)
                .setNegativeButton("Close", null)
                .create()
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                inner class VH(val v: View) : RecyclerView.ViewHolder(v)
                override fun getItemCount() = entries.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
                    VH(inflater.inflate(com.iptvapp.R.layout.item_whats_on, parent, false))
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val entry = entries[position]
                    val v = holder.itemView
                    v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonChannel).text = entry.series.name
                    v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonProgram).text =
                        "S${entry.nextSeason}E${entry.nextEpisode} — ${entry.nextEpisodeTitle}"
                    v.findViewById<android.widget.ProgressBar>(com.iptvapp.R.id.pbWonProgress).visibility = View.INVISIBLE
                    com.bumptech.glide.Glide.with(v)
                        .load(entry.series.cover)
                        .placeholder(android.R.drawable.ic_media_play)
                        .into(v.findViewById(com.iptvapp.R.id.ivWonLogo))
                    v.setOnClickListener {
                        dialog.dismiss()
                        lifecycleScope.launch {
                            val url = viewModel.getSeriesEpisodeUrl(entry.episodeId, entry.containerExtension)
                            startActivity(Intent(this@TvHomeActivity, PlayerActivity::class.java).apply {
                                putExtra("stream_url", url)
                                putExtra("stream_title", "S${entry.nextSeason}E${entry.nextEpisode} ${entry.nextEpisodeTitle}")
                                putExtra("stream_id", entry.episodeId.hashCode())
                                putExtra("is_vod", true)
                                putExtra("series_id", entry.series.seriesId)
                                putExtra("series_name", entry.series.name)
                                putExtra("season_num", entry.nextSeason)
                                putExtra("episode_num", entry.nextEpisode)
                                putExtra("resume_ms", entry.resumeMs)
                            })
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
                        val startMs = System.currentTimeMillis() + deltas[i]
                        showTvReminderOrRecordChoice(channel, channel.name, startMs, 60 * 60_000L, options[i])
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
                    val stopMs = if (epg.stopTimestamp > 1_000_000_000_000L) epg.stopTimestamp else epg.stopTimestamp * 1000L
                    val durationMs = (stopMs - startMs).takeIf { it > 0 } ?: 60 * 60_000L
                    showTvReminderOrRecordChoice(channel, epg.title, startMs, durationMs, epg.title)
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun showTvReminderOrRecordChoice(
        channel: ChannelEntity, programTitle: String, startMs: Long, durationMs: Long, label: String
    ) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(programTitle)
            .setItems(arrayOf("Remind Me", "Record This")) { _, which ->
                when (which) {
                    0 -> {
                        ChannelTimerScheduler.schedule(this, channel.streamId, channel.name, programTitle, startMs)
                        Toast.makeText(this, "Reminder set for $label", Toast.LENGTH_SHORT).show()
                    }
                    1 -> startActivity(Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java).apply {
                        putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, channel.streamId)
                        putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, startMs)
                        putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, durationMs)
                    })
                }
            }
            .show()
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
