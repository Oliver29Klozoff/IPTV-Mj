package com.iptvapp.ui.home

import com.iptvapp.R
import com.iptvapp.util.enableTvFocusHighlight
import com.iptvapp.util.isLargeScreenDevice

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.iptvapp.ui.onboarding.FeatureTourDialog
import com.iptvapp.update.UpdateChecker
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.worker.EpgRefreshWorker
import kotlinx.coroutines.flow.first
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.iptvapp.ui.guide.ChannelTimerScheduler
import com.iptvapp.ui.series.SeriesDetailActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    private var searchDebounceJob: kotlinx.coroutines.Job? = null
    private var openPlayerJob: kotlinx.coroutines.Job? = null
    private lateinit var binding: ActivityHomeBinding

    // ─── Bulk-select state ───────────────────────────────────────────────────
    private val bulkSelectedIds = mutableSetOf<Int>()
    private var bulkSelectMode = false

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        // Sync sort mode in case it changed in settings
        viewModel.setSortMode(
            when (viewModel.channelSort.value) {
                HomeViewModel.ChannelSort.DEFAULT -> 0
                HomeViewModel.ChannelSort.NAME_AZ -> 1
                HomeViewModel.ChannelSort.MOST_WATCHED -> 2
                HomeViewModel.ChannelSort.RECENTLY_WATCHED -> 3
                HomeViewModel.ChannelSort.MOST_RELIABLE -> 4
            }
        )
    }
    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Always return to Favorites on fullscreen exit
        binding.tabLayout.getTabAt(2)?.select()
        showFavorites()
        if (result.resultCode == Activity.RESULT_OK) {
            val returnedId  = result.data?.getIntExtra("stream_id", -1) ?: -1
            val returnedUrl = result.data?.getStringExtra("stream_url") ?: ""
            val returnedTitle = result.data?.getStringExtra("stream_title") ?: ""
            if (returnedId != -1 && returnedUrl.isNotEmpty()) {
                suppressMiniAutoResume = true
                currentMiniStreamId = returnedId
                currentMiniUrl = returnedUrl
                currentMiniTitle = returnedTitle
                currentMiniIsVod = false
                binding.tvMiniChannelName.text = returnedTitle
                viewModel.setCurrentlyPlaying(returnedId)
                binding.rvChannels.post {
                    val pos = channelAdapter.currentList.indexOfFirst { it.streamId == returnedId }
                    if (pos >= 0) {
                        (binding.rvChannels.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(pos, 0)
                    }
                }
                binding.tvPipChannelName?.text = returnedTitle
                miniPlayer?.setMediaItem(androidx.media3.common.MediaItem.fromUri(returnedUrl))
                miniPlayer?.prepare()
                miniPlayer?.playWhenReady = true
            }
        }
    }

    // Prevents onResume from auto-resuming "recent" channel when we just picked one from the grid
    private var suppressMiniAutoResume = false
    private var tabPositionBeforePlayer: Int = -1
    private var pendingScrollToCurrent = false
    // Populated in onCreate from the ViewModel (which survives activity recreation) when
    // this instance is being recreated for a rotation — consumed once by initMiniPlayer()
    // to resume exactly what was playing instead of initMiniPlayer()'s normal fallback of
    // loading the last-watched *live* channel, which would otherwise silently replace
    // whatever movie/show was actually in the mini player.
    private var restoredMiniState: HomeViewModel.MiniPlayerState? = null

    private val timelineLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val streamId = data.getIntExtra("stream_id", -1)
            if (streamId == -1) return@registerForActivityResult
            // Set synchronously — onResume fires after this callback and reads this flag
            suppressMiniAutoResume = true
            val timeshiftUrl = data.getStringExtra("timeshift_url")
            val timeshiftTitle = data.getStringExtra("timeshift_title")
            lifecycleScope.launch {
                val channel = viewModel.getChannelById(streamId) ?: return@launch
                if (timeshiftUrl != null && timeshiftTitle != null) {
                    currentMiniUrl = timeshiftUrl
                    currentMiniTitle = timeshiftTitle
                    currentMiniStreamId = streamId
                    currentMiniIsVod = false
                    binding.tvMiniChannelName.text = timeshiftTitle
                    binding.tvPipChannelName?.text = timeshiftTitle
                    miniPlayer?.let {
                        it.setMediaItem(androidx.media3.common.MediaItem.fromUri(timeshiftUrl))
                        it.prepare()
                        it.playWhenReady = true
                    }
                } else {
                    playInMiniPlayer(channel)
                }
            }
        }
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
    // Explicitly tracked instead of regexing currentMiniUrl for "movie|vod" — that regex
    // missed series episode URLs (which contain "series", not "movie"/"vod"), causing the
    // fullscreen button to treat series as live and skip resume-position entirely.
    private var currentMiniIsVod: Boolean = false
    private var miniRetryCount: Int = 0
    // Guards against recording a reliability outcome more than once per channel selection —
    // reset by playInMiniPlayer() whenever a *new* channel is chosen.
    private var lastReliabilityOutcomeStreamId: Int = -1
    private var miniPlayJob: kotlinx.coroutines.Job? = null
    private var epgRefreshJob: kotlinx.coroutines.Job? = null
    private var isPipMode = false
    private var externalPlayerChoice = "internal"
    private var currentAccent: Int = android.graphics.Color.parseColor("#008CFF")

    @javax.inject.Inject lateinit var prefs: PreferencesManager

    private var activeGenre: String? = null
    private val GENRE_KEYWORDS = linkedMapOf(
        "All"           to emptyList<String>(),
        "Sports"        to listOf("sport", "espn", "nfl", "nba", "mlb", "nhl", "nascar", "tennis", "golf", "soccer", "football"),
        "News"          to listOf("news", "cnn", "cnbc", "msnbc", "bbc", "fox news", "abc news", "nbc news"),
        "Movies"        to listOf("movie", "film", "cinema", "hbo", "showtime", "starz", "amc", "fx movie"),
        "Kids"          to listOf("kid", "children", "child", "disney", "nickelodeon", "nick", "cartoon", "toon"),
        "Entertainment" to listOf("entertainment", "comedy", "drama", "tnt", "tbs", "bravo", "mtv", "vh1")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.savedMiniPlayerState?.let { state ->
            viewModel.savedMiniPlayerState = null
            restoredMiniState = state
            currentMiniUrl = state.url
            currentMiniTitle = state.title
            currentMiniStreamId = state.streamId
            currentMiniIsVod = state.isVod
            suppressMiniAutoResume = true
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        UpdateChecker(this).check(lifecycleScope)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (isLargeScreenDevice()) {
            binding.root.enableTvFocusHighlight()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        rescheduleEpgRefreshIfNeeded()
        setupRecyclerViews()
        setupTabs()
        setupSearch()
        setupMenu()
        observeViewModel()
        viewModel.loadAll()
        observeTabVisibility()
        // Always start on FAVORITES. Call showFavorites() explicitly because onTabSelected
        // may not fire if TabLayout restores to tab 2 from its own saved instance state,
        // which would leave _channels showing stale data from the previous session.
        binding.tabLayout.getTabAt(2)?.select()
        showFavorites()
        // Landscape: land on the plain sidebar + mini player view (last-playing channel
        // loads into it via the existing initMiniPlayer()/restoredMiniState flow either
        // way) instead of immediately opening Favorites' channel list on every launch.
        collapseContentColumn()
        setupLandscapeSidebar()
        lifecycleScope.launch {
            applyAccent(android.graphics.Color.parseColor(prefs.accentColor.first()))
        }
        FeatureTourDialog.showIfNeeded(this)
    }

    private fun rescheduleEpgRefreshIfNeeded() {
        lifecycleScope.launch {
            val hours = prefs.epgAutoRefreshHours.first()
            if (hours > 0) {
                val req = PeriodicWorkRequestBuilder<EpgRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true))
                    .build()
                WorkManager.getInstance(this@HomeActivity).enqueueUniquePeriodicWork(
                    "auto_epg_refresh_work", ExistingPeriodicWorkPolicy.KEEP, req
                )
            }
        }
    }

    private fun setupLandscapeSidebar() {
        val root = binding.root
        fun btn(id: Int) = root.findViewById<android.widget.Button?>(id)
        val tabs = listOf(
            btn(R.id.landBtnLive) to 0,
            btn(R.id.landBtnCategories) to 1,
            btn(R.id.landBtnFavorites) to 2,
            btn(R.id.landBtnGuide) to 3,
            btn(R.id.landBtnWatching) to 4,
            btn(R.id.landBtnMovies) to 5,
            btn(R.id.landBtnSeries) to 6
        )
        tabs.forEach { (button, index) ->
            button?.setOnClickListener {
                binding.tabLayout.getTabAt(index)?.select()
                tabs.forEach { (b, _) -> b?.setTextColor(0xFFAAAAAA.toInt()) }
                button.setTextColor(currentAccent)
            }
        }
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                val idx = tab?.position ?: return
                tabs.forEach { (b, _) -> b?.setTextColor(0xFFAAAAAA.toInt()) }
                tabs.firstOrNull { it.second == idx }?.first?.setTextColor(currentAccent)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when {
                    // Auto-collapsed after picking something to play — bring the channel
                    // list straight back (not categories), scrolled to what's playing.
                    contentColumnCollapsed -> expandContentColumnToChannels()
                    // Otherwise it's "go back" from a drilled-into category's channel list
                    // up to that tab's category list. Tabs with no categories (Series/
                    // History/Favorites/Guide) have nothing to go back to.
                    tab?.position in listOf(0, 1, 5) -> landscapeShowCategoriesMode()
                }
            }
        })
        // Sync initial highlight to tab 2 (Favorites)
        btn(R.id.landBtnFavorites)?.setTextColor(currentAccent)
    }

    private fun isLandscapeMode() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Live/Categories/Movies (tabs 0-2) drill down: tap a category to replace rvCategories
    // with rvChannels in the same column; tap the sidebar tab again to come back. Series/
    // History/Favorites/Guide have no categories, so they go straight to "channels" mode.
    private fun landscapeShowCategoriesMode() {
        if (!isLandscapeMode()) return
        cancelContentAutoCollapse()
        contentColumnCollapsed = false
        binding.root.findViewById<View?>(R.id.categoriesColumn)?.visibility = View.VISIBLE
        binding.root.findViewById<View?>(R.id.categoriesDivider)?.visibility = View.VISIBLE
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvChannels.visibility = View.GONE
        val cats = when (binding.tabLayout.selectedTabPosition) {
            0 -> viewModel.liveCategories.value
            1 -> viewModel.favoriteLiveCategories.value
            5 -> viewModel.vodCategories.value
            else -> emptyList()
        }
        resizeCategoriesColumnToContent(cats)
    }

    private fun landscapeShowChannelsMode() {
        if (!isLandscapeMode()) return
        contentColumnCollapsed = false
        binding.root.findViewById<View?>(R.id.categoriesColumn)?.visibility = View.VISIBLE
        binding.root.findViewById<View?>(R.id.categoriesDivider)?.visibility = View.VISIBLE
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.visibility = View.VISIBLE
        val col = binding.root.findViewById<View?>(R.id.categoriesColumn) ?: return
        val params = col.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        params.width = 0
        // Was weight=2 (matching the categories column's own default) — narrower now so the
        // mini player (weight=3, unchanged) doesn't lose any of its own share of the row.
        params.weight = 1f
        col.layoutParams = params
    }

    private var contentColumnCollapsed = false
    private val contentAutoCollapseHandler = Handler(Looper.getMainLooper())
    private val contentAutoCollapseRunnable = Runnable { collapseContentColumn() }

    // The inline channel list auto-collapses 10s after picking something to play, giving
    // the mini player the full row width — tapping the (already-selected) sidebar tab again
    // brings it straight back to the channel list (not categories), scrolled to whatever's
    // currently playing, since that's what the user just came from.
    private fun scheduleContentAutoCollapse() {
        contentAutoCollapseHandler.removeCallbacks(contentAutoCollapseRunnable)
        contentAutoCollapseHandler.postDelayed(contentAutoCollapseRunnable, 10_000L)
    }

    private fun cancelContentAutoCollapse() {
        contentAutoCollapseHandler.removeCallbacks(contentAutoCollapseRunnable)
    }

    private fun collapseContentColumn() {
        if (!isLandscapeMode()) return
        binding.root.findViewById<View?>(R.id.categoriesColumn)?.visibility = View.GONE
        binding.root.findViewById<View?>(R.id.categoriesDivider)?.visibility = View.GONE
        contentColumnCollapsed = true
    }

    private fun expandContentColumnToChannels() {
        landscapeShowChannelsMode()
        if (binding.rvChannels.adapter === channelAdapter) {
            binding.rvChannels.post {
                val pos = channelAdapter.currentList.indexOfFirst { it.streamId == currentMiniStreamId }
                if (pos >= 0) (binding.rvChannels.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, 0)
            }
        }
        scheduleContentAutoCollapse()
    }

    private fun applyAccent(colorInt: Int) {
        currentAccent = colorInt
        binding.tabLayout.setSelectedTabIndicatorColor(colorInt)
        val csl = android.content.res.ColorStateList.valueOf(colorInt)
        binding.miniPlayerProgress?.indeterminateTintList = csl
        binding.progressBar?.indeterminateTintList = csl
        binding.miniEpgProgress?.progressTintList = csl
        binding.tvMiniEpg?.setTextColor(colorInt)
        binding.btnTimelineView?.setTextColor(colorInt)
        // Re-highlight the active sidebar button (landscape layouts only)
        val tabIdx = binding.tabLayout.selectedTabPosition
        val sidebarMap = listOf(
            R.id.landBtnLive to 0, R.id.landBtnCategories to 1,
            R.id.landBtnFavorites to 2, R.id.landBtnGuide to 3,
            R.id.landBtnWatching to 4, R.id.landBtnMovies to 5,
            R.id.landBtnSeries to 6
        )
        sidebarMap.forEach { (id, idx) ->
            binding.root.findViewById<android.widget.Button?>(id)?.setTextColor(
                if (idx == tabIdx) colorInt else 0xFFAAAAAA.toInt()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            applyAccent(android.graphics.Color.parseColor(prefs.accentColor.first()))
        }
        com.iptvapp.update.UpdateChecker(this).resumeCheck(lifecycleScope)
        if (suppressMiniAutoResume) {
            // Returning from the guide grid with an explicit channel choice — don't override it
            suppressMiniAutoResume = false
            return
        }
        lifecycleScope.launch {
            val recent = viewModel.getRecentChannel()
            val isLive = currentMiniUrl.isNotEmpty() && !currentMiniIsVod
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
            val isLive = currentMiniUrl.isNotEmpty() && !currentMiniIsVod
            if (!isLive) {
                // VOD: resume from current position; live streams handled in onResume
                miniPlayer?.play()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelContentAutoCollapse()
        // Survives into the next HomeActivity instance via the ViewModel when this destroy
        // is a rotation-triggered recreation (a true app exit just leaves this unread, since
        // a fresh launch gets a brand new ViewModel with this field null by default).
        if (currentMiniUrl.isNotEmpty()) {
            viewModel.savedMiniPlayerState = HomeViewModel.MiniPlayerState(
                url = currentMiniUrl,
                title = currentMiniTitle,
                streamId = currentMiniStreamId,
                isVod = currentMiniIsVod,
                positionMs = miniPlayer?.currentPosition ?: 0L
            )
        }
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
                    // One reliability outcome per channel selection (guarded below) — reflects
                    // real usage, not just an explicit "check favorites health" ping.
                    if (state == Player.STATE_READY && !currentMiniIsVod &&
                        lastReliabilityOutcomeStreamId != currentMiniStreamId
                    ) {
                        lastReliabilityOutcomeStreamId = currentMiniStreamId
                        val streamId = currentMiniStreamId
                        lifecycleScope.launch { viewModel.recordChannelOutcome(streamId, true) }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "MINI PLAYER ERROR: isVod=$currentMiniIsVod streamId=$currentMiniStreamId " +
                            "errorCode=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName} " +
                            "message=${error.message} retryCount=$miniRetryCount url=$currentMiniUrl"
                    )
                    if (!currentMiniIsVod && lastReliabilityOutcomeStreamId != currentMiniStreamId) {
                        lastReliabilityOutcomeStreamId = currentMiniStreamId
                        val streamId = currentMiniStreamId
                        lifecycleScope.launch { viewModel.recordChannelOutcome(streamId, false) }
                    }
                    if (miniRetryCount >= 5 || currentMiniUrl.isEmpty()) return
                    miniRetryCount++
                    miniPlayJob?.cancel()
                    miniPlayJob = lifecycleScope.launch {
                        delay(3000L)
                        miniPlayer?.let {
                            it.setMediaItem(androidx.media3.common.MediaItem.fromUri(currentMiniUrl))
                            it.prepare()
                            it.playWhenReady = true
                        }
                    }
                }
            })
        }
        binding.miniPlayerView.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos)
            }
        }
        binding.btnFullscreen?.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos)
            }
        }
        restoredMiniState?.let { state ->
            restoredMiniState = null
            binding.tvMiniChannelName.text = state.title
            miniPlayer?.setMediaItem(
                androidx.media3.common.MediaItem.fromUri(state.url),
                if (state.isVod) state.positionMs else 0L
            )
            miniPlayer?.prepare()
            miniPlayer?.playWhenReady = true
        } ?: loadLastWatchedChannel()
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
        miniPlayJob?.cancel()
        miniRetryCount = 0
        miniPlayJob = lifecycleScope.launch {
            val url = viewModel.getLiveStreamUrl(channel.streamId)
            currentMiniUrl = url
            currentMiniTitle = channel.name
            currentMiniStreamId = channel.streamId
            currentMiniIsVod = false
            viewModel.setCurrentlyPlaying(channel.streamId)
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
                val currentPos = miniPlayer?.currentPosition ?: 0L
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos)
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

    private fun showVodSortDialog() {
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

    private fun showSeriesSortDialog() {
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

    private fun setupMenu() {
        binding.btnWhatsOn?.setOnClickListener { showWhatsOnNow() }
        binding.btnWhatsOn?.setOnLongClickListener { showUpNextTicker(); true }
        binding.btnRefresh?.setOnClickListener {
            viewModel.refreshNow()
            Toast.makeText(this, "Refreshing channels…", Toast.LENGTH_SHORT).show()
        }
        binding.btnVodSort?.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 6) showSeriesSortDialog() else showVodSortDialog()
        }
        binding.btnSort?.setOnClickListener {
            viewModel.cycleSort()
            val label = when (viewModel.channelSort.value) {
                HomeViewModel.ChannelSort.DEFAULT -> "⇅ Default"
                HomeViewModel.ChannelSort.NAME_AZ -> "⇅ A-Z"
                HomeViewModel.ChannelSort.MOST_WATCHED -> "⇅ Popular"
                HomeViewModel.ChannelSort.RECENTLY_WATCHED -> "⇅ Recent"
                HomeViewModel.ChannelSort.MOST_RELIABLE -> "⇅ Reliable"
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
        setupPipCornerDragResize()
    }

    private var pipCornerWidthPx = 0
    private var pipCornerHeightPx = 0

    /** The floating PiP corner box: tap restores the inline mini player, drag moves it
     * anywhere on screen, and pinch resizes it — like any other floating video window. */
    private fun setupPipCornerDragResize() {
        val pip = binding.pipCorner ?: return
        val density = resources.displayMetrics.density
        val minW = (140 * density).toInt()
        val minH = (80 * density).toInt()
        val maxW = (420 * density).toInt()
        val maxH = (260 * density).toInt()

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                togglePipMode()
                return true
            }
        })
        val scaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                if (pipCornerWidthPx == 0) {
                    pipCornerWidthPx = pip.layoutParams.width.takeIf { it > 0 } ?: (220 * density).toInt()
                    pipCornerHeightPx = pip.layoutParams.height.takeIf { it > 0 } ?: (130 * density).toInt()
                }
                pipCornerWidthPx = (pipCornerWidthPx * detector.scaleFactor).toInt().coerceIn(minW, maxW)
                pipCornerHeightPx = (pipCornerHeightPx * detector.scaleFactor).toInt().coerceIn(minH, maxH)
                pip.layoutParams = pip.layoutParams.apply {
                    width = pipCornerWidthPx
                    height = pipCornerHeightPx
                }
                return true
            }
        })

        var downRawX = 0f; var downRawY = 0f
        var startTranslationX = 0f; var startTranslationY = 0f
        pip.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startTranslationX = view.translationX; startTranslationY = view.translationY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        view.translationX = startTranslationX + (event.rawX - downRawX)
                        view.translationY = startTranslationY + (event.rawY - downRawY)
                    }
                }
            }
            true
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
                    5 -> viewModel.selectVodCategory(category.categoryId)
                }
                landscapeShowChannelsMode()
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
                scheduleContentAutoCollapse()
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
            onChannelLongClick = { channel -> showChannelActionsMenu(channel) }
        )

        vodAdapter = VodAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                    val progress = viewModel.getVodProgress(vod.streamId)
                    currentMiniUrl = url
                    currentMiniTitle = vod.name
                    currentMiniStreamId = vod.streamId
                    currentMiniIsVod = true
                    binding.tvMiniChannelName.text = vod.name
                    miniPlayer?.let {
                        it.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                        it.prepare()
                        it.playWhenReady = true
                    }
                    // Store VOD info for fullscreen button
                    binding.btnFullscreen?.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos)
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
                viewModel.lastTabPosition = tab?.position ?: 2
                binding.btnVodSort?.visibility = if (tab?.position == 5 || tab?.position == 6) View.VISIBLE else View.GONE
                when (tab?.position) {
                    0 -> showLive()
                    1 -> showFavCategories()
                    2 -> { showFavorites(); viewModel.checkFavoritesHealth() }
                    3 -> showGuide()
                    4 -> showWatching()
                    5 -> showVod()
                    6 -> showSeries()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {
                if (tab?.position == 2) detachFavDrag()
                if (tab?.position == 3) binding.btnTimelineView?.visibility = View.GONE
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
    }

    private fun dispatchSearch(query: String) {
        when (binding.tabLayout.selectedTabPosition) {
            5 -> viewModel.searchVod(query)
            6 -> viewModel.searchSeries(query)
            2 -> viewModel.searchFavorites(query)
            else -> viewModel.searchChannels(query)
        }
    }
        private fun showLive() {
        landscapeShowCategoriesMode()
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        val cats = viewModel.liveCategories.value
        updateGenreChips(cats)
        val filtered = genreFilter(cats)
        categoryAdapter.resetSelection()
        submitCategories(filtered)
        if (filtered.isNotEmpty()) {
            if (viewModel.hasSelectedCategory()) viewModel.reloadCurrentLiveCategory()
            else viewModel.selectLiveCategory(filtered.first().categoryId)
        }
    }

    private fun genreFilter(cats: List<com.iptvapp.data.local.entities.CategoryEntity>): List<com.iptvapp.data.local.entities.CategoryEntity> {
        val genre = activeGenre ?: return cats
        val keywords = GENRE_KEYWORDS[genre] ?: return cats
        return cats.filter { cat -> keywords.any { kw -> cat.categoryName.contains(kw, ignoreCase = true) } }
    }

    // Portrait shows these in a horizontal scrolling row above the category list; landscape
    // shows them in a vertical column to the right of the channel list instead (so they
    // don't eat into the categories column's height there) — both containers are filled
    // from the same detected-genre list, just laid out differently per orientation.
    private fun setGenreFilterVisible(visible: Boolean) {
        binding.genreFilterScroll?.visibility = if (visible) View.VISIBLE else View.GONE
        binding.root.findViewById<View?>(R.id.genreFilterColumn)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    private fun buildGenreChip(genre: String, selected: Boolean, vertical: Boolean): View {
        return buildGenreChipView(genre, selected, vertical) {
            activeGenre = if (genre == "All") null else genre
            val filtered = genreFilter(viewModel.liveCategories.value)
            categoryAdapter.resetSelection()
            submitCategories(filtered)
            if (filtered.isNotEmpty()) viewModel.selectLiveCategory(filtered.first().categoryId)
            updateGenreChips(viewModel.liveCategories.value)
        }
    }

    /** Shared visual builder for the pill-shaped genre/category filter chips — Live channels
     * filter by keyword-matched categories, Series folders filter by the provider's own genre
     * tag, but both are the same chip look-and-feel with a different click action. */
    private fun buildGenreChipView(label: String, selected: Boolean, vertical: Boolean, onClick: () -> Unit): View {
        return android.widget.TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
            gravity = android.view.Gravity.CENTER
            typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 32f
                if (selected) {
                    setColor(currentAccent)
                } else {
                    setColor(0xFF2A2A2A.toInt())
                    setStroke(2, 0xFF555555.toInt())
                }
            }
            if (vertical) {
                setPadding(20, 16, 20, 16)
                layoutParams = android.view.ViewGroup.MarginLayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8 }
            } else {
                setPadding(24, 0, 24, 0)
                layoutParams = android.view.ViewGroup.MarginLayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 72
                ).also { it.marginEnd = 8 }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateGenreChips(allCats: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        val horizontalContainer = binding.genreChipContainer
        val verticalContainer = binding.root.findViewById<android.widget.LinearLayout?>(R.id.genreChipContainerVertical)
        horizontalContainer?.removeAllViews()
        verticalContainer?.removeAllViews()
        val detected = GENRE_KEYWORDS.keys.filter { genre ->
            val keywords = GENRE_KEYWORDS[genre]!!
            keywords.isEmpty() || allCats.any { cat -> keywords.any { kw -> cat.categoryName.contains(kw, ignoreCase = true) } }
        }
        if (detected.size <= 1) {
            setGenreFilterVisible(false)
            return
        }
        setGenreFilterVisible(true)
        val selectedGenre = activeGenre ?: "All"
        for (genre in detected) {
            val selected = (genre == selectedGenre)
            horizontalContainer?.addView(buildGenreChip(genre, selected, vertical = false))
            verticalContainer?.addView(buildGenreChip(genre, selected, vertical = true))
        }
    }

    private fun showFavCategories() {
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        val favCats = viewModel.favoriteLiveCategories.value
        submitCategories(favCats)
        if (favCats.isNotEmpty()) {
            viewModel.selectFavCategory(favCats.first().categoryId)
        } else {
            channelAdapter.submitList(emptyList())
        }
    }

    private fun showVod() {
        landscapeShowCategoriesMode()
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = vodAdapter
        val cats = viewModel.vodCategories.value
        updateVodGenreChips(cats)
        submitFilteredVodCategories(cats)
    }

    private fun showSeries() {
        landscapeShowChannelsMode()
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = seriesAdapter
        updateSeriesGenreChips(viewModel.series.value)
        submitFilteredSeries(viewModel.series.value)
    }

    private var activeSeriesGenre: String? = null

    // Providers' raw `genre` tags are all over the place — dozens of near-duplicate or
    // one-off strings ("Dramedy", "Crime Drama", "Sci-Fi & Fantasy", ...). Bucketing them into
    // a handful of broad folders by keyword (same approach as the Live tab's genre chips)
    // gives a usable set of folders instead of one chip per raw tag. Order here is also the
    // display order. "Other" (any show matching none of these) is appended only if non-empty.
    private val GENRE_BUCKETS = linkedMapOf(
        "Comedy" to listOf("comedy"),
        "Drama" to listOf("drama"),
        "Action & Adventure" to listOf("action", "adventure"),
        "Sci-Fi & Fantasy" to listOf("sci-fi", "scifi", "science fiction", "fantasy"),
        "Crime & Mystery" to listOf("crime", "mystery", "detective"),
        "Horror & Thriller" to listOf("horror", "thriller", "suspense"),
        "Animation" to listOf("animation", "anime", "cartoon"),
        "Documentary" to listOf("documentary", "docu"),
        "Kids & Family" to listOf("kids", "family", "children"),
        "Reality" to listOf("reality", "game show", "talk show"),
        "Romance" to listOf("romance", "romantic"),
        "War & History" to listOf("war", "history", "historical"),
        "Music" to listOf("music", "musical")
    )

    private fun seriesBuckets(series: com.iptvapp.data.local.entities.SeriesEntity): List<String> {
        val tags = series.genre?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) return emptyList()
        val matched = GENRE_BUCKETS.filter { (_, keywords) ->
            tags.any { tag -> keywords.any { kw -> tag.contains(kw, ignoreCase = true) } }
        }.keys.toList()
        return matched.ifEmpty { listOf("Other") }
    }

    private fun seriesGenres(list: List<com.iptvapp.data.local.entities.SeriesEntity>): List<String> {
        val present = list.flatMap { seriesBuckets(it) }.toSet()
        val ordered = GENRE_BUCKETS.keys.filter { it in present }
        return if ("Other" in present) ordered + "Other" else ordered
    }

    private fun seriesGenreFilter(list: List<com.iptvapp.data.local.entities.SeriesEntity>): List<com.iptvapp.data.local.entities.SeriesEntity> {
        val genre = activeSeriesGenre ?: return list
        return list.filter { genre in seriesBuckets(it) }
    }

    private fun submitFilteredSeries(list: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        seriesAdapter.submitList(viewModel.applySeriesSort(seriesGenreFilter(list)))
    }

    private fun updateSeriesGenreChips(allSeries: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        val horizontalContainer = binding.genreChipContainer
        val verticalContainer = binding.root.findViewById<android.widget.LinearLayout?>(R.id.genreChipContainerVertical)
        horizontalContainer?.removeAllViews()
        verticalContainer?.removeAllViews()
        val genres = seriesGenres(allSeries)
        if (genres.isEmpty()) {
            setGenreFilterVisible(false)
            return
        }
        if (activeSeriesGenre != null && genres.none { it.equals(activeSeriesGenre, ignoreCase = true) }) {
            activeSeriesGenre = null
        }
        setGenreFilterVisible(true)
        val allChip = buildSeriesGenreChip("All", activeSeriesGenre == null)
        horizontalContainer?.addView(allChip)
        verticalContainer?.addView(buildSeriesGenreChip("All", activeSeriesGenre == null, vertical = true))
        for (genre in genres) {
            val selected = activeSeriesGenre?.equals(genre, ignoreCase = true) == true
            horizontalContainer?.addView(buildSeriesGenreChip(genre, selected))
            verticalContainer?.addView(buildSeriesGenreChip(genre, selected, vertical = true))
        }
    }

    private fun buildSeriesGenreChip(genre: String, selected: Boolean, vertical: Boolean = false): View {
        return buildGenreChipView(genre, selected, vertical) {
            activeSeriesGenre = if (genre == "All") null else genre
            updateSeriesGenreChips(viewModel.series.value)
            submitFilteredSeries(viewModel.series.value)
        }
    }

    private var activeVodGenre: String? = null

    // Movies already have real provider categories (unlike Series), but those raw category
    // names are just as messy ("4K NEW MOVIES 2024", "ACTION MOVIES USA", ...) — bucketing
    // them by the same keyword folders turns dozens of one-off categories into a handful of
    // genre folders, same treatment as the Series tab.
    private fun vodCategoryBuckets(cat: com.iptvapp.data.local.entities.CategoryEntity): List<String> {
        val matched = GENRE_BUCKETS.filter { (_, keywords) ->
            keywords.any { kw -> cat.categoryName.contains(kw, ignoreCase = true) }
        }.keys.toList()
        return matched.ifEmpty { listOf("Other") }
    }

    private fun vodGenres(cats: List<com.iptvapp.data.local.entities.CategoryEntity>): List<String> {
        val present = cats.flatMap { vodCategoryBuckets(it) }.toSet()
        val ordered = GENRE_BUCKETS.keys.filter { it in present }
        return if ("Other" in present) ordered + "Other" else ordered
    }

    private fun vodCategoryFilter(cats: List<com.iptvapp.data.local.entities.CategoryEntity>): List<com.iptvapp.data.local.entities.CategoryEntity> {
        val genre = activeVodGenre ?: return cats
        return cats.filter { genre in vodCategoryBuckets(it) }
    }

    private fun submitFilteredVodCategories(cats: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        val filtered = vodCategoryFilter(cats)
        submitCategories(filtered)
        if (filtered.isNotEmpty()) viewModel.selectVodCategory(filtered.first().categoryId)
    }

    private fun updateVodGenreChips(allCats: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        val horizontalContainer = binding.genreChipContainer
        val verticalContainer = binding.root.findViewById<android.widget.LinearLayout?>(R.id.genreChipContainerVertical)
        horizontalContainer?.removeAllViews()
        verticalContainer?.removeAllViews()
        val genres = vodGenres(allCats)
        if (genres.isEmpty()) {
            setGenreFilterVisible(false)
            return
        }
        if (activeVodGenre != null && genres.none { it.equals(activeVodGenre, ignoreCase = true) }) {
            activeVodGenre = null
        }
        setGenreFilterVisible(true)
        horizontalContainer?.addView(buildVodGenreChip("All", activeVodGenre == null))
        verticalContainer?.addView(buildVodGenreChip("All", activeVodGenre == null, vertical = true))
        for (genre in genres) {
            val selected = activeVodGenre?.equals(genre, ignoreCase = true) == true
            horizontalContainer?.addView(buildVodGenreChip(genre, selected))
            verticalContainer?.addView(buildVodGenreChip(genre, selected, vertical = true))
        }
    }

    private fun buildVodGenreChip(genre: String, selected: Boolean, vertical: Boolean = false): View {
        return buildGenreChipView(genre, selected, vertical) {
            activeVodGenre = if (genre == "All") null else genre
            categoryAdapter.resetSelection()
            updateVodGenreChips(viewModel.vodCategories.value)
            submitFilteredVodCategories(viewModel.vodCategories.value)
        }
    }

    private fun showWatching() {
        landscapeShowChannelsMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = channelAdapter
        channelAdapter.showDragHandles = false
        // Submit snapshot on entry — StateFlow won't re-emit if value is unchanged, so the
        // adapter would otherwise keep showing whatever the previous tab's list was
        channelAdapter.submitList(viewModel.recentChannels.value.toList())
    }

    private var favItemTouchHelper: ItemTouchHelper? = null

    private fun showFavorites() {
        landscapeShowChannelsMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = channelAdapter
        viewModel.showFavoriteChannels()
        pendingScrollToCurrent = true
        lifecycleScope.launch {
            val favorites = viewModel.getFavoriteChannelsSnapshot()
            if (!pendingScrollToCurrent) return@launch
            pendingScrollToCurrent = false
            if (binding.tabLayout.selectedTabPosition != 2) return@launch
            channelAdapter.submitList(favorites)
            val streamId = viewModel.currentlyPlayingStreamId.value
            if (streamId >= 0) scrollFavoritesToStreamId(streamId)
        }

        channelAdapter.showDragHandles = true
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private val dragList = mutableListOf<ChannelEntity>()

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val fromPos = from.bindingAdapterPosition
                val toPos = to.bindingAdapterPosition
                if (dragList.isEmpty()) dragList.addAll(channelAdapter.currentList)
                // channelAdapter is shared across tabs (Live/Favorites/History) — if a
                // background update swaps in a differently-sized list mid-drag, these
                // positions can point outside dragList's bounds. Reject rather than crash.
                if (fromPos !in dragList.indices || toPos !in dragList.indices) return false
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

    private fun scrollFavoritesToStreamId(streamId: Int) {
        binding.rvChannels.post {
            val pos = channelAdapter.currentList.indexOfFirst { it.streamId == streamId }
            if (pos >= 0) {
                (binding.rvChannels.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, 0)
            }
        }
    }

    private fun submitCategories(list: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        categoryAdapter.submitList(list)
        resizeCategoriesColumnToContent(list)
    }

    // Landscape's categories column used to always take a fixed proportional share of the
    // screen (layout_weight) regardless of how short the category names actually were,
    // wasting horizontal space that could go to the channel list/mini player instead. This
    // shrinks it to fit the longest visible category name (matching item_category.xml's
    // 13sp text + 12dp horizontal padding on each side), within sane min/max bounds.
    private fun resizeCategoriesColumnToContent(categories: List<com.iptvapp.data.local.entities.CategoryEntity>) {
        val col = binding.root.findViewById<View?>(R.id.categoriesColumn) ?: return
        val params = col.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        if (categories.isEmpty()) return
        val density = resources.displayMetrics.density
        val paint = android.graphics.Paint().apply {
            textSize = 13f * resources.displayMetrics.scaledDensity
        }
        val maxTextWidth = categories.maxOf { paint.measureText(it.categoryName ?: "") }
        val horizontalPadding = 24 * density // 12dp each side, from item_category.xml
        val starIconAllowance = 24 * density // 18dp icon + 6dp margin, shown for favorites
        val newWidth = (maxTextWidth + horizontalPadding + starIconAllowance).toInt()
            .coerceIn((120 * density).toInt(), (320 * density).toInt())
        if (params.width != newWidth) {
            params.width = newWidth
            params.weight = 0f
            col.layoutParams = params
        }
    }

    private fun detachFavDrag() {
        channelAdapter.showDragHandles = false
        channelAdapter.itemTouchHelper = null
        favItemTouchHelper?.attachToRecyclerView(null)
        favItemTouchHelper = null
    }

    private fun showGuide() {
        landscapeShowChannelsMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = guideAdapter
        viewModel.loadGuide()
        binding.btnTimelineView?.visibility = View.VISIBLE
        binding.btnTimelineView?.setOnClickListener {
            timelineLauncher.launch(Intent(this, com.iptvapp.ui.guide.EpgTimelineActivity::class.java))
        }
    }

    private fun openPlayer(url: String, title: String, streamId: Int, streamIds: IntArray = viewModel.channels.value.map { it.streamId }.toIntArray(), isVod: Boolean = false, resumeMs: Long = 0L) {
        if (externalPlayerChoice != "internal") {
            launchExternalPlayer(url, title, externalPlayerChoice, isVod)
            return
        }
        tabPositionBeforePlayer = binding.tabLayout.selectedTabPosition
        // Stop + clear the mini player so the server releases the stream slot
        // before PlayerActivity claims it — prevents concurrent-stream rejections.
        miniPlayer?.stop()
        miniPlayer?.clearMediaItems()
        openPlayerJob?.cancel()
        openPlayerJob = lifecycleScope.launch {
            delay(1200)
            playerLauncher.launch(Intent(this@HomeActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", title)
                putExtra("stream_id", streamId)
                putExtra("stream_ids", streamIds)
                putExtra("is_vod", isVod)
                putExtra("resume_ms", resumeMs)
            })
        }
    }

    private fun launchExternalPlayer(url: String, title: String, player: String, isVod: Boolean = false) {
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
            if (pkg != null) {
                // Specific player not installed — fall back to built-in silently
                lifecycleScope.launch { prefs.setExternalPlayer("internal") }
                externalPlayerChoice = "internal"
                android.widget.Toast.makeText(this, "${player.uppercase()} not installed — using built-in player", android.widget.Toast.LENGTH_SHORT).show()
                openPlayer(url, title, -1, isVod = isVod)
            } else {
                android.widget.Toast.makeText(this, "No video player found", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyTabVisibility(index: Int, show: Boolean) {
        val tabView = binding.tabLayout.getTabAt(index)?.view ?: return
        tabView.visibility = if (show) View.VISIBLE else View.GONE
        tabView.layoutParams?.width = if (show) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else 0
        binding.tabLayout.requestLayout()
    }

    private fun observeTabVisibility() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.showMovies.collect { show: Boolean ->
                        applyTabVisibility(5, show)
                        binding.root.findViewById<android.widget.Button?>(R.id.landBtnMovies)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.showSeries.collect { show: Boolean ->
                        applyTabVisibility(6, show)
                        binding.root.findViewById<android.widget.Button?>(R.id.landBtnSeries)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.showWatching.collect { show: Boolean ->
                        applyTabVisibility(4, show)
                        binding.root.findViewById<android.widget.Button?>(R.id.landBtnWatching)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.loading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.syncProgress.collect { progress ->
                if (progress == null) {
                    binding.syncProgressContainer?.visibility = View.GONE
                } else {
                    val (text, percent) = progress
                    binding.syncProgressContainer?.visibility = View.VISIBLE
                    binding.tvSyncStatus?.text = text
                    binding.syncProgressBar?.progress = percent
                }
            }
        }
        lifecycleScope.launch {
            viewModel.liveCategories.collect { cats ->
                if (binding.tabLayout.selectedTabPosition == 0) {
                    updateGenreChips(cats)
                    val filtered = genreFilter(cats)
                    submitCategories(filtered)
                    if (filtered.isNotEmpty() && !viewModel.hasSelectedCategory()) {
                        viewModel.selectLiveCategory(filtered.first().categoryId)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteLiveCategories.collect { favs ->
                categoryAdapter.submitFavoriteCategoryIds(favs.map { it.categoryId }.toSet())
                if (binding.tabLayout.selectedTabPosition == 1) {
                    submitCategories(favs)
                    if (favs.isNotEmpty()) viewModel.selectFavCategory(favs.first().categoryId)
                    else channelAdapter.submitList(emptyList())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collect { list ->
                // Guard: never let live-category channels bleed onto the Favorites tab.
                // inFavoritesMode is false whenever selectLiveCategory was the last call;
                // if that happens to race with showFavorites(), we drop the stale update.
                if (binding.tabLayout.selectedTabPosition == 2 && !viewModel.inFavoritesMode) return@collect
                channelAdapter.submitList(list)
                viewModel.loadEpgForChannels(list)
                if (pendingScrollToCurrent && list.isNotEmpty()) {
                    // The currently-playing channel is only known once the mini player's
                    // async cold-start resume (loadLastWatchedChannel) sets it — which can
                    // land after this list first emits. Don't consume the flag on a miss;
                    // let the currentlyPlayingStreamId collector below finish the job.
                    val streamId = viewModel.currentlyPlayingStreamId.value
                    if (streamId >= 0) {
                        pendingScrollToCurrent = false
                        scrollFavoritesToStreamId(streamId)
                    }
                }
            }
        }
        var lastVodList: List<com.iptvapp.data.local.entities.VodEntity> = emptyList()
        lifecycleScope.launch {
            viewModel.vod.collect {
                lastVodList = it
                if (binding.tabLayout.selectedTabPosition == 5) vodAdapter.submitList(viewModel.applyVodSort(it))
            }
        }
        lifecycleScope.launch {
            viewModel.vodSort.collect {
                if (binding.tabLayout.selectedTabPosition == 5) vodAdapter.submitList(viewModel.applyVodSort(lastVodList))
            }
        }
        var lastSeriesList: List<com.iptvapp.data.local.entities.SeriesEntity> = emptyList()
        lifecycleScope.launch {
            viewModel.series.collect {
                lastSeriesList = it
                if (binding.tabLayout.selectedTabPosition == 6) {
                    updateSeriesGenreChips(it)
                    submitFilteredSeries(it)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.seriesSort.collect {
                if (binding.tabLayout.selectedTabPosition == 6) submitFilteredSeries(lastSeriesList)
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
                if (pendingScrollToCurrent && streamId >= 0 && channelAdapter.currentList.isNotEmpty() &&
                    binding.tabLayout.selectedTabPosition == 2) {
                    pendingScrollToCurrent = false
                    scrollFavoritesToStreamId(streamId)
                }
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
                if (binding.tabLayout.selectedTabPosition == 5) {
                    updateVodGenreChips(it)
                    submitFilteredVodCategories(it)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.continueWatching.collect { list ->
                if (binding.tabLayout.selectedTabPosition == 4) vodAdapter.submitList(list)
            }
        }
        lifecycleScope.launch {
            viewModel.recentChannels.collect { /* snapshot submitted in showWatching() on tab entry */ }
        }
        lifecycleScope.launch {
            viewModel.channelHealth.collect { channelAdapter.submitHealth(it) }
        }
        lifecycleScope.launch {
            viewModel.externalPlayer.collect { externalPlayerChoice = it }
        }
        // Auto-play the most recent channel as soon as watch history is available.
        // This handles the case where getRecentChannel() returned null during initMiniPlayer
        // because the DB hadn't emitted yet (e.g. after a fresh channel sync).
        lifecycleScope.launch {
            viewModel.recentChannels.collect { channels ->
                if (currentMiniStreamId == -1 && channels.isNotEmpty() && miniPlayer != null) {
                    playInMiniPlayer(channels.first())
                }
            }
        }
    }

    private fun showChannelActionsMenu(channel: ChannelEntity) {
        lifecycleScope.launch {
            val label = viewModel.getReliabilityLabel(channel.streamId)
            showChannelActionsMenuDialog(channel, label)
        }
    }

    private fun showChannelActionsMenuDialog(channel: ChannelEntity, reliabilityLabel: String?) {
        val title = if (reliabilityLabel != null) "${channel.name}\n$reliabilityLabel" else channel.name
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
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, i ->
                when (options[i]) {
                    "Set Reminder" -> showReminderDialog(channel)
                    "Select (bulk add to favorites)" -> {
                        bulkSelectMode = true
                        bulkSelectedIds.add(channel.streamId)
                        Toast.makeText(this, "${bulkSelectedIds.size} selected — long-press another or tap '✓ Add' to confirm", Toast.LENGTH_SHORT).show()
                    }
                    "Deselect (bulk)" -> {
                        bulkSelectedIds.remove(channel.streamId)
                        if (bulkSelectedIds.isEmpty()) bulkSelectMode = false
                    }
                    "Hide Channel" -> {
                        viewModel.hideChannel(channel.streamId)
                        Toast.makeText(this, "${channel.name} hidden. Unhide in Settings → Display.", Toast.LENGTH_SHORT).show()
                    }
                    "Channels Like This" -> showSimilarChannelsSheet(channel)
                    else -> if (options[i].startsWith("✓ Add")) {
                        viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                        Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                        bulkSelectedIds.clear()
                        bulkSelectMode = false
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSimilarChannelsSheet(channel: ChannelEntity) {
        viewModel.loadSimilarChannels(channel)
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
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

    // Long-press "What's On" — a single chronological feed of what's airing next across every
    // favorite channel, instead of checking channel-by-channel. Reuses the same row layout as
    // showWhatsOnNow() but shows a start-time instead of a live progress bar (nothing has
    // started yet for these entries by definition).
    private fun showUpNextTicker() {
        lifecycleScope.launch {
            val entries = viewModel.getUpNextTicker()
            if (entries.isEmpty()) {
                Toast.makeText(this@HomeActivity, "No upcoming EPG data for your favorites", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            val inflater = layoutInflater
            val rv = androidx.recyclerview.widget.RecyclerView(this@HomeActivity).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@HomeActivity)
                setPadding(0, 8, 0, 8)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("Up Next — Favorites")
                .setView(rv)
                .setNegativeButton("Close", null)
                .create()
            val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                inner class VH(val v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
                override fun getItemCount() = entries.size
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                    val view = inflater.inflate(com.iptvapp.R.layout.item_whats_on, parent, false)
                    return VH(view)
                }
                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    val entry = entries[position]
                    val v = holder.itemView
                    v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonChannel).text = entry.channel.name
                    v.findViewById<android.widget.TextView>(com.iptvapp.R.id.tvWonProgram).text =
                        "${timeFmt.format(java.util.Date(entry.startTimestamp))} · ${entry.title}"
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
}