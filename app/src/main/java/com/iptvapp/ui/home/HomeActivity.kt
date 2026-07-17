package com.iptvapp.ui.home

import com.iptvapp.R
import com.iptvapp.util.enableTvFocusHighlight
import com.iptvapp.util.isLargeScreenDevice
import androidx.appcompat.app.AlertDialog
import javax.inject.Inject

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
import com.iptvapp.data.local.entities.CategoryEntity
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
    // Once bulk-select is on (long-press one channel to start), a plain tap on any other
    // channel just adds/removes it from the selection instead of playing it — no more
    // long-pressing every single one. 3s of no further taps auto-opens "Move to Folder"
    // instead of requiring a long-press + menu tap to confirm.
    private val bulkSelectHandler = Handler(Looper.getMainLooper())
    private val bulkSelectIdleRunnable = Runnable {
        if (bulkSelectMode && bulkSelectedIds.isNotEmpty()) {
            showMoveToFolderDialog(bulkSelectedIds.toList())
        }
    }

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
        binding.tabLayout.getTabAt(0)?.select()
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
    @Inject lateinit var okHttpClient: okhttp3.OkHttpClient
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var mergedChannelAdapter: MergedChannelAdapter
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
        // The "All Providers" merged channel cache previously only ever refreshed when the
        // user manually tapped Refresh — meaning a freshly-installed app (or one that hasn't
        // opened that tab yet) showed nothing at all until a manual action. Auto-refresh it
        // once per cold start when at least one extra provider is actually configured, so
        // it's already populated by the time the user checks that tab.
        lifecycleScope.launch {
            if (prefs.getExtraServersWithNick().isNotEmpty()) viewModel.refreshMergedChannels()
        }
        observeTabVisibility()
        // Always start on FAVORITES. Call showFavorites() explicitly because onTabSelected
        // may not fire if TabLayout restores to tab 2 from its own saved instance state,
        // which would leave _channels showing stale data from the previous session.
        binding.tabLayout.getTabAt(0)?.select()
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
        handleJumpToChannelExtra()
    }

    // Lets other screens (currently: Settings' Provider Health "Play This Channel" action)
    // hand off to a specific channel without duplicating playback/navigation logic there.
    private fun handleJumpToChannelExtra() {
        val streamId = intent.getIntExtra(EXTRA_JUMP_TO_STREAM_ID, -1)
        if (streamId < 0) return
        lifecycleScope.launch {
            val channel = viewModel.getChannelById(streamId) ?: return@launch
            playInMiniPlayer(channel)
            viewModel.markChannelWatched(channel.streamId)
            viewModel.setCurrentlyPlaying(channel.streamId)
        }
    }

    companion object {
        const val EXTRA_JUMP_TO_STREAM_ID = "jump_to_stream_id"

        // Tab positions — must match the TabItem order in activity_home.xml (portrait AND
        // landscape). Every position check goes through these; scattering raw indices is what
        // made the last reorder (Providers moving next to Favorites) so error-prone, and had
        // already left observeTabVisibility hiding the WRONG tabs after an earlier reorder.
        const val TAB_FAVORITES = 0
        const val TAB_PROVIDERS = 1
        const val TAB_LIVE = 2
        const val TAB_CATEGORIES = 3
        const val TAB_MOVIES = 4
        const val TAB_SERIES = 5
        const val TAB_GUIDE = 6
        const val TAB_HISTORY = 7
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
            btn(R.id.landBtnFavorites) to TAB_FAVORITES,
            btn(R.id.landBtnProviders) to TAB_PROVIDERS,
            btn(R.id.landBtnLive) to TAB_LIVE,
            btn(R.id.landBtnCategories) to TAB_CATEGORIES,
            btn(R.id.landBtnMovies) to TAB_MOVIES,
            btn(R.id.landBtnSeries) to TAB_SERIES,
            btn(R.id.landBtnGuide) to TAB_GUIDE,
            btn(R.id.landBtnWatching) to TAB_HISTORY
        )
        tabs.forEach { (button, index) ->
            button?.setOnClickListener {
                binding.tabLayout.getTabAt(index)?.select()
                tabs.forEach { (b, _) -> b?.setTextColor(0xFFAAAAAA.toInt()) }
                button.setTextColor(currentAccent)
            }
        }
        // Landscape previously only had an undiscoverable long-press-the-sidebar-entry
        // gesture to refresh (no visible icon at all, unlike portrait's btnRefreshProviders)
        // — landBtnRefreshProviders is a real visible icon button now. Long-press is kept too.
        val refreshProviders = {
            viewModel.refreshMergedChannels()
            Toast.makeText(this, "Refreshing all providers…", Toast.LENGTH_SHORT).show()
        }
        binding.root.findViewById<android.widget.ImageButton?>(R.id.landBtnRefreshProviders)?.setOnClickListener {
            refreshProviders()
        }
        binding.root.findViewById<android.widget.Button?>(R.id.landBtnProviders)?.setOnLongClickListener {
            refreshProviders()
            true
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
                    // History/Guide) have nothing to go back to.
                    tab?.position in listOf(TAB_LIVE, TAB_CATEGORIES, TAB_MOVIES) -> landscapeShowCategoriesMode()
                    tab?.position == TAB_FAVORITES -> showFavorites()
                    tab?.position == TAB_PROVIDERS -> showAllProviders()
                }
            }
        })
        // Sync initial highlight to tab 0 (Favorites)
        btn(R.id.landBtnFavorites)?.setTextColor(currentAccent)
    }

    private fun isLandscapeMode() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Live/Categories/Movies (tabs 1-3) drill down: tap a category to replace rvCategories
    // with rvChannels in the same column; tap the sidebar tab again to come back. Series/
    // History/Favorites/Guide have no categories, so they go straight to "channels" mode.
    private fun landscapeShowCategoriesMode() {
        if (!isLandscapeMode()) return
        scheduleContentAutoCollapse()
        contentColumnCollapsed = false
        binding.root.findViewById<View?>(R.id.categoriesColumn)?.visibility = View.VISIBLE
        binding.root.findViewById<View?>(R.id.categoriesDivider)?.visibility = View.VISIBLE
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvChannels.visibility = View.GONE
        val cats = when (binding.tabLayout.selectedTabPosition) {
            TAB_LIVE -> viewModel.liveCategories.value
            TAB_CATEGORIES -> viewModel.favoriteLiveCategories.value
            TAB_MOVIES -> viewModel.vodCategories.value
            else -> emptyList()
        }
        resizeCategoriesColumnToContent(cats)
    }

    private fun landscapeShowChannelsMode() {
        if (!isLandscapeMode()) return
        scheduleContentAutoCollapse()
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
            R.id.landBtnFavorites to TAB_FAVORITES, R.id.landBtnProviders to TAB_PROVIDERS,
            R.id.landBtnLive to TAB_LIVE, R.id.landBtnCategories to TAB_CATEGORIES,
            R.id.landBtnMovies to TAB_MOVIES, R.id.landBtnSeries to TAB_SERIES,
            R.id.landBtnGuide to TAB_GUIDE, R.id.landBtnWatching to TAB_HISTORY
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
        // Cheap, always-visible reminder of which server/account is currently active — useful
        // after adding/switching between multiple servers, where otherwise nothing in the
        // main channel list surfaces which one you're actually on.
        lifecycleScope.launch {
            val nickname = prefs.serverNickname.first()
            binding.etSearch.hint = if (nickname.isNotBlank()) "Search ($nickname)…" else "Search…"
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
        // Without an explicit User-Agent, ExoPlayer's built-in HTTP stack sends its own
        // default ("ExoPlayerLib/x.x.x") — some Cloudflare-fronted IPTV CDNs block/reject
        // that on the stream endpoint specifically (even while the API endpoint works fine
        // with the same UA), which surfaced as PARSING_MANIFEST_MALFORMED then a network-
        // error retry loop for one provider's channels. Fullscreen playback (PlayerActivity)
        // already sets a browser-like UA via a custom OkHttpDataSource.Factory; mirror that
        // here so the mini player (which merged-provider channels now launch into first)
        // gets the same treatment.
        val upstreamDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${android.os.Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(upstreamDataSourceFactory)
        miniPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also { player ->
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
        binding.btnRefreshProviders?.setOnClickListener {
            viewModel.refreshMergedChannels()
            Toast.makeText(this, "Refreshing all providers…", Toast.LENGTH_SHORT).show()
        }
        binding.btnVodSort?.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == TAB_SERIES) showSeriesSortDialog() else showVodSortDialog()
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
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS) {
                    // 3-level drill (server -> category -> channels): the first tap picks a
                    // server and should show ITS categories next, not jump to channels yet.
                    // A synthetic "★ Favorites" entry sits alongside the real servers and drills
                    // into a folder picker (shared FavoriteFolderEntity rows) instead.
                    if (category.categoryId == MERGED_FAV_ROOT_ID) {
                        mergedFavoritesShowingFolderPicker = true
                        categoryAdapter.submitList(mergedFavoriteFoldersToSynthetic())
                    } else if (mergedFavoritesShowingFolderPicker) {
                        when (category.categoryId) {
                            MERGED_FAV_NEW_FOLDER_ID -> showCreateMergedFavoriteFolderDialog()
                            else -> {
                                val folderId = when (category.categoryId) {
                                    MERGED_FAV_ALL_ID -> null
                                    MERGED_FAV_UNSORTED_ID -> -1
                                    else -> category.categoryId.toInt()
                                }
                                mergedFavoritesShowingFolderPicker = false
                                viewModel.selectMergedFavoriteFolderView(folderId)
                                viewModel.checkMergedFavoritesHealth()
                                landscapeShowChannelsMode()
                                binding.rvChannels.adapter = mergedChannelAdapter
                            }
                        }
                    } else if (viewModel.selectedMergedServerIndex == null) {
                        viewModel.selectMergedServer(category.categoryId.toInt())
                        categoryAdapter.submitList(emptyList())
                        lifecycleScope.launch {
                            viewModel.mergedCategories.collect { cats ->
                                if (viewModel.selectedMergedServerIndex != null) {
                                    categoryAdapter.submitList(mergedCategoriesToSynthetic(cats))
                                }
                            }
                        }
                    } else {
                        val categoryId = if (category.categoryId == NO_CATEGORY_ID) null else category.categoryId
                        viewModel.selectMergedCategory(categoryId)
                        landscapeShowChannelsMode()
                        binding.rvChannels.adapter = mergedChannelAdapter
                    }
                } else if (binding.tabLayout.selectedTabPosition == TAB_FAVORITES) {
                    when (category.categoryId) {
                        FAV_NEW_FOLDER_ID -> showCreateFavoriteFolderDialog()
                        FAV_ALL_ID -> showFavoriteFolderChannels(null)
                        FAV_UNSORTED_ID -> showFavoriteFolderChannels(-1)
                        else -> showFavoriteFolderChannels(category.categoryId.toInt())
                    }
                } else {
                    when (binding.tabLayout.selectedTabPosition) {
                        TAB_LIVE -> viewModel.selectLiveCategory(category.categoryId)
                        TAB_CATEGORIES -> viewModel.selectFavCategory(category.categoryId)
                        TAB_MOVIES -> viewModel.selectVodCategory(category.categoryId)
                    }
                    landscapeShowChannelsMode()
                }
            },
            onCategoryLongClick = { category ->
                if (binding.tabLayout.selectedTabPosition == TAB_LIVE) {
                    viewModel.toggleLiveCategoryFavorite(category.categoryId)
                    Toast.makeText(this, "Category favorite updated", Toast.LENGTH_SHORT).show()
                } else if (binding.tabLayout.selectedTabPosition == TAB_FAVORITES &&
                    category.categoryId !in listOf(FAV_ALL_ID, FAV_UNSORTED_ID, FAV_NEW_FOLDER_ID)
                ) {
                    showFolderOptionsDialog(category.categoryId.toInt())
                } else if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && mergedFavoritesShowingFolderPicker &&
                    category.categoryId !in listOf(MERGED_FAV_ALL_ID, MERGED_FAV_UNSORTED_ID, MERGED_FAV_NEW_FOLDER_ID)
                ) {
                    showFolderOptionsDialog(category.categoryId.toInt())
                }
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
                } else {
                    lifecycleScope.launch {
                        playInMiniPlayer(channel)
                        viewModel.markChannelWatched(channel.streamId)
                        viewModel.setCurrentlyPlaying(channel.streamId)
                    }
                    scheduleContentAutoCollapse()
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
            onChannelLongClick = { channel -> showChannelActionsMenu(channel) }
        )

        mergedChannelAdapter = MergedChannelAdapter(
            onChannelClick = { channel -> playMergedChannel(channel) },
            onFavoriteClick = { channel ->
                viewModel.setMergedChannelFavorite(channel, !channel.isFavorite)
                Toast.makeText(this, if (channel.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            onChannelLongClick = { channel -> showMergedChannelActionsMenu(channel) },
            onChannelDoubleClick = { channel ->
                lifecycleScope.launch {
                    try {
                        val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                        openPlayer(url, "${channel.name} · ${channel.serverNickname}", -1)
                    } catch (_: Exception) {
                        Toast.makeText(this@HomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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

        // Scrolling either list while it's showing (landscape) counts as active browsing —
        // resets the same idle timer picking a channel does, so actively scrolling a long
        // list for more than 10s doesn't get the column yanked away mid-browse.
        val resetOnScroll = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isLandscapeMode() && !contentColumnCollapsed) scheduleContentAutoCollapse()
            }
        }
        binding.rvCategories.addOnScrollListener(resetOnScroll)
        binding.rvChannels.addOnScrollListener(resetOnScroll)
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewModel.lastTabPosition = tab?.position ?: 0
                binding.btnVodSort?.visibility = if (tab?.position == TAB_MOVIES || tab?.position == TAB_SERIES) View.VISIBLE else View.GONE
                binding.btnRefreshProviders?.visibility = if (tab?.position == TAB_PROVIDERS) View.VISIBLE else View.GONE
                when (tab?.position) {
                    TAB_FAVORITES -> { showFavorites(); viewModel.checkFavoritesHealth() }
                    TAB_PROVIDERS -> showAllProviders()
                    TAB_LIVE -> showLive()
                    TAB_CATEGORIES -> showFavCategories()
                    TAB_MOVIES -> showVod()
                    TAB_SERIES -> showSeries()
                    TAB_GUIDE -> showGuide()
                    TAB_HISTORY -> showWatching()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {
                if (tab?.position == TAB_FAVORITES) detachFavDrag()
                if (tab?.position == TAB_GUIDE) binding.btnTimelineView?.visibility = View.GONE
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
            TAB_MOVIES -> viewModel.searchVod(query)
            TAB_SERIES -> viewModel.searchSeries(query)
            TAB_FAVORITES -> {
                if (query.isBlank()) {
                    // Back to the folder picker, not a dead end.
                    showFavorites()
                } else {
                    viewModel.searchFavorites(query)
                    landscapeShowChannelsMode()
                    binding.rvCategories.visibility = View.GONE
                    binding.rvChannels.adapter = channelAdapter
                    channelAdapter.showDragHandles = false
                }
            }
            TAB_PROVIDERS -> {
                if (query.isBlank()) {
                    // Back to wherever the server/category drill-down was, not a dead end.
                    showAllProviders()
                } else {
                    viewModel.searchMergedChannels(query)
                    landscapeShowChannelsMode()
                    binding.rvCategories.visibility = View.GONE
                    binding.rvChannels.adapter = mergedChannelAdapter
                    mergedChannelAdapter.submitList(viewModel.mergedChannels.value)
                }
            }
            else -> viewModel.searchChannels(query)
        }
    }
        private fun showLive() {
        // activeGenre used to stay set forever once you tapped a genre chip (e.g. "Movies"),
        // since nothing ever cleared it — leaving Live and coming back later (even a whole app
        // session later) kept silently re-applying that old filter, showing only that genre's
        // categories instead of the full list, with no visible indication why. Genre filtering
        // is meant to be a within-session shortcut, not a sticky setting.
        activeGenre = null
        landscapeShowCategoriesMode()
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        // In portrait, categories and channels are two always-visible side-by-side panes (not
        // toggled like in landscape) — without this, switching here from Favorites left the
        // right-hand pane showing that folder's channels until the new category's load
        // happened to land, making it look like a category/folder was already selected.
        channelAdapter.submitList(emptyList())
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
        channelAdapter.submitList(emptyList())
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

    private fun seriesBuckets(series: com.iptvapp.data.local.entities.SeriesEntity): List<String> =
        com.iptvapp.util.GenreBuckets.bucketsFor(series.genre?.split(",").orEmpty())

    private fun seriesGenres(list: List<com.iptvapp.data.local.entities.SeriesEntity>): List<String> =
        com.iptvapp.util.GenreBuckets.presentBuckets(list.map { it.genre?.split(",").orEmpty() })

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
    private fun vodCategoryBuckets(cat: com.iptvapp.data.local.entities.CategoryEntity): List<String> =
        com.iptvapp.util.GenreBuckets.bucketsFor(listOf(cat.categoryName))

    private fun vodGenres(cats: List<com.iptvapp.data.local.entities.CategoryEntity>): List<String> =
        com.iptvapp.util.GenreBuckets.presentBuckets(cats.map { listOf(it.categoryName) })

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

    // Browse-and-play merged view across every configured server (Settings > Providers).
    // 3-level drill-down (server -> category -> channels), same shape as Live, since a single
    // provider can itself have tens of thousands of channels. A synthetic "★ Favorites" entry
    // alongside the real servers drills into its own folder picker instead (see
    // MergedChannelEntity kdoc — favorites/folders are supported here, sharing the same
    // FavoriteFolderEntity rows as the primary provider's Favorites tab, just browsed
    // separately rather than mixed into that tab).
    private fun showAllProviders() {
        viewModel.resetMergedSelection()
        mergedFavoritesShowingFolderPicker = false
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = mergedChannelAdapter
        categoryAdapter.submitList(mergedServersToSynthetic(viewModel.mergedServers.value))
    }

    private val NO_CATEGORY_ID = "__uncategorized__"
    private val MERGED_FAV_ROOT_ID = "__merged_fav_root__"
    private val MERGED_FAV_ALL_ID = "__merged_fav_all__"
    private val MERGED_FAV_UNSORTED_ID = "__merged_fav_unsorted__"
    private val MERGED_FAV_NEW_FOLDER_ID = "__merged_fav_new_folder__"
    private var mergedFavoritesShowingFolderPicker = false

    private fun mergedFavoriteFoldersToSynthetic(): List<CategoryEntity> {
        val counts = viewModel.mergedFavoriteFolderCounts.value.associate { it.favoriteFolderId to it.channelCount }
        val totalCount = counts.values.sum()
        val unsortedCount = counts[null] ?: 0
        val list = mutableListOf(
            CategoryEntity(MERGED_FAV_ALL_ID, "All Favorites ($totalCount)", 0, "merged_fav_folder")
        )
        if (unsortedCount > 0) {
            list.add(CategoryEntity(MERGED_FAV_UNSORTED_ID, "Unsorted ($unsortedCount)", 0, "merged_fav_folder"))
        }
        viewModel.favoriteFolders.value.forEach { folder ->
            val count = counts[folder.id] ?: 0
            list.add(CategoryEntity(folder.id.toString(), "${folder.name} ($count)", 0, "merged_fav_folder"))
        }
        list.add(CategoryEntity(MERGED_FAV_NEW_FOLDER_ID, "+ New Folder", 0, "merged_fav_folder"))
        return list
    }

    private fun showCreateMergedFavoriteFolderDialog() {
        val et = android.widget.EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createFavoriteFolder(name)
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(150)
                        categoryAdapter.submitList(mergedFavoriteFoldersToSynthetic())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mergedServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedServerSummary>): List<CategoryEntity> {
        val favEntry = CategoryEntity(MERGED_FAV_ROOT_ID, "★ Favorites", 0, "merged_fav_root")
        // serverIndex == -1 is always whichever provider is currently primary/active — its
        // channels are already fully browsable via the normal Live tab, so listing it again
        // here was redundant and confusing next to the other, actually-"extra" providers.
        return listOf(favEntry) + list.filter { it.serverIndex != -1 }.map {
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
    // player first, and only goes fullscreen if the mini player itself or its fullscreen
    // button is tapped next. currentMiniUrl/Title/StreamId/IsVod are plain generic fields (not
    // tied to ChannelEntity), and the existing miniPlayerView/btnFullscreen click listeners
    // already just read those fields — so setting them here is all that's needed for both to
    // work correctly with zero extra wiring.
    private fun playMergedChannel(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        miniPlayJob?.cancel()
        miniRetryCount = 0
        miniPlayJob = lifecycleScope.launch {
            try {
                val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                android.util.Log.d("MergedChannels", "playMergedChannel: serverIndex=${channel.serverIndex} streamId=${channel.streamId} resolvedUrl=${com.iptvapp.util.LogSanitizer.redactCredentials(url)}")
                val title = "${channel.name} · ${channel.serverNickname}"
                // streamId = -1: no DB-backed identity for this channel (it lives only in the
                // merged_channels cache, not the primary server's channels table) — same
                // convention already used by the external-player-fallback path.
                currentMiniUrl = url
                currentMiniTitle = title
                currentMiniStreamId = -1
                currentMiniIsVod = false
                binding.tvMiniChannelName.text = title
                binding.tvPipChannelName?.text = title
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
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Couldn't load this channel — tap Refresh and try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var favItemTouchHelper: ItemTouchHelper? = null

    private val FAV_ALL_ID = "__all__"
    private val FAV_UNSORTED_ID = "__unsorted__"
    private val FAV_NEW_FOLDER_ID = "__new_folder__"

    // showFavorites() reads favoriteFolders/favoriteFolderCounts as a one-shot snapshot — at
    // cold app launch (the very first tab shown) those StateFlows haven't finished their first
    // DB read yet, so the folder picker rendered "All Favorites (0)" forever with no way to
    // refresh itself until some unrelated navigation happened to call showFavorites() again
    // later, by which point the data had already arrived. This flag lets the reactive
    // collectors below know it's safe to re-render the picker as that data trickles in.
    private var favoritesShowingFolderPicker = false

    // Favorites now drills down the same way Movies/Live do: pick "All Favorites", "Unsorted",
    // or a named folder first, then see that group's channels. Folders are user-created (long-
    // press a favorite -> "Move to Folder"), not provider-supplied.
    private fun showFavorites() {
        favoritesShowingFolderPicker = true
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        channelAdapter.submitList(emptyList())
        submitCategories(favoriteFoldersToSynthetic())
    }

    private fun favoriteFoldersToSynthetic(): List<CategoryEntity> {
        val counts = viewModel.favoriteFolderCounts.value.associate { it.favoriteFolderId to it.channelCount }
        val totalCount = counts.values.sum()
        val unsortedCount = counts[null] ?: 0
        val list = mutableListOf(
            CategoryEntity(FAV_ALL_ID, "All Favorites ($totalCount)", 0, "fav_folder"),
        )
        if (unsortedCount > 0) {
            list.add(CategoryEntity(FAV_UNSORTED_ID, "Unsorted ($unsortedCount)", 0, "fav_folder"))
        }
        viewModel.favoriteFolders.value.forEach { folder ->
            val count = counts[folder.id] ?: 0
            list.add(CategoryEntity(folder.id.toString(), "${folder.name} ($count)", 0, "fav_folder"))
        }
        list.add(CategoryEntity(FAV_NEW_FOLDER_ID, "+ New Folder", 0, "fav_folder"))
        return list
    }

    private fun showFavoriteFolderChannels(folderId: Int?) {
        favoritesShowingFolderPicker = false
        landscapeShowChannelsMode()
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = channelAdapter
        viewModel.selectFavoriteFolderView(folderId)
        pendingScrollToCurrent = true
        lifecycleScope.launch {
            val favorites = viewModel.getFavoriteChannelsSnapshot()
            if (!pendingScrollToCurrent) return@launch
            pendingScrollToCurrent = false
            if (binding.tabLayout.selectedTabPosition != TAB_FAVORITES) return@launch
            channelAdapter.submitList(favorites)
            val streamId = viewModel.currentlyPlayingStreamId.value
            if (streamId >= 0) scrollFavoritesToStreamId(streamId)
        }

        channelAdapter.showDragHandles = true
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private val dragList = mutableListOf<ChannelEntity>()

            // Defaults to true, which starts a drag on a long-press ANYWHERE on the row —
            // that was swallowing the long-press before ChannelAdapter's own
            // setOnLongClickListener (which opens "Move to Folder" etc.) ever got a chance to
            // fire. Dragging already has its own dedicated trigger — touching ivDragHandle
            // (see ChannelAdapter's ivDragHandle touch listener) — so long-press-to-drag
            // anywhere on the row isn't needed and was actively breaking the folder menu.
            override fun isLongPressDragEnabled(): Boolean = false

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

    private fun showCreateFavoriteFolderDialog() {
        val et = android.widget.EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createFavoriteFolder(name)
                    lifecycleScope.launch {
                        // Give the DB write + StateFlow re-emission a beat before re-reading
                        // the list, otherwise the new folder wouldn't show until the next
                        // unrelated recomposition of this screen.
                        kotlinx.coroutines.delay(150)
                        submitCategories(favoriteFoldersToSynthetic())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderOptionsDialog(folderId: Int) {
        val folder = viewModel.favoriteFolders.value.firstOrNull { it.id == folderId } ?: return
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setPositiveButton("Rename") { _, _ ->
                val et = android.widget.EditText(this).apply { setText(folder.name) }
                AlertDialog.Builder(this)
                    .setTitle("Rename Folder")
                    .setView(et)
                    .setPositiveButton("Save") { _, _ ->
                        val name = et.text.toString().trim()
                        if (name.isNotEmpty()) viewModel.renameFavoriteFolder(folderId, name)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Delete") { _, _ ->
                viewModel.deleteFavoriteFolder(folderId)
                Toast.makeText(this, "Folder deleted — its channels moved to Unsorted", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel", null)
            .show()
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
                // These indices had drifted out of sync with the tab order after an earlier
                // reorder — hiding "Movies" was actually hiding GUIDE, "Series" hid HISTORY,
                // and "History" hid SERIES. The named constants keep them honest now.
                launch {
                    viewModel.showMovies.collect { show: Boolean ->
                        applyTabVisibility(TAB_MOVIES, show)
                        binding.root.findViewById<android.widget.Button?>(R.id.landBtnMovies)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.showSeries.collect { show: Boolean ->
                        applyTabVisibility(TAB_SERIES, show)
                        binding.root.findViewById<android.widget.Button?>(R.id.landBtnSeries)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.showWatching.collect { show: Boolean ->
                        applyTabVisibility(TAB_HISTORY, show)
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
                if (binding.tabLayout.selectedTabPosition == TAB_LIVE) {
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
            viewModel.favoriteFolders.collect {
                if (binding.tabLayout.selectedTabPosition == TAB_FAVORITES && favoritesShowingFolderPicker) {
                    submitCategories(favoriteFoldersToSynthetic())
                }
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && mergedFavoritesShowingFolderPicker) {
                    submitCategories(mergedFavoriteFoldersToSynthetic())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteFolderCounts.collect {
                if (binding.tabLayout.selectedTabPosition == TAB_FAVORITES && favoritesShowingFolderPicker) {
                    submitCategories(favoriteFoldersToSynthetic())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedFavoriteFolderCounts.collect {
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && mergedFavoritesShowingFolderPicker) {
                    submitCategories(mergedFavoriteFoldersToSynthetic())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteLiveCategories.collect { favs ->
                categoryAdapter.submitFavoriteCategoryIds(favs.map { it.categoryId }.toSet())
                if (binding.tabLayout.selectedTabPosition == TAB_CATEGORIES) {
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
                if (binding.tabLayout.selectedTabPosition == TAB_FAVORITES && !viewModel.inFavoritesMode) return@collect
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
                if (binding.tabLayout.selectedTabPosition == TAB_MOVIES) vodAdapter.submitList(viewModel.applyVodSort(it))
            }
        }
        lifecycleScope.launch {
            viewModel.vodSort.collect {
                if (binding.tabLayout.selectedTabPosition == TAB_MOVIES) vodAdapter.submitList(viewModel.applyVodSort(lastVodList))
            }
        }
        var lastSeriesList: List<com.iptvapp.data.local.entities.SeriesEntity> = emptyList()
        lifecycleScope.launch {
            viewModel.series.collect {
                lastSeriesList = it
                if (binding.tabLayout.selectedTabPosition == TAB_SERIES) {
                    updateSeriesGenreChips(it)
                    submitFilteredSeries(it)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.seriesSort.collect {
                if (binding.tabLayout.selectedTabPosition == TAB_SERIES) submitFilteredSeries(lastSeriesList)
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
                    binding.tabLayout.selectedTabPosition == TAB_FAVORITES) {
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
                if (binding.tabLayout.selectedTabPosition == TAB_MOVIES) {
                    updateVodGenreChips(it)
                    submitFilteredVodCategories(it)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.continueWatching.collect { list ->
                if (binding.tabLayout.selectedTabPosition == TAB_HISTORY) vodAdapter.submitList(list)
            }
        }
        lifecycleScope.launch {
            viewModel.recentChannels.collect { /* snapshot submitted in showWatching() on tab entry */ }
        }
        lifecycleScope.launch {
            viewModel.mergedChannels.collect { list ->
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS) {
                    mergedChannelAdapter.submitList(list)
                    viewModel.loadEpgForMergedChannels(list)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedEpgText.collect { mergedChannelAdapter.submitEpgText(it) }
        }
        lifecycleScope.launch {
            viewModel.mergedHealth.collect { mergedChannelAdapter.submitHealth(it) }
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
        if (channel.isFavorite) options.add("Move to Folder")
        if (bulkSelectMode && bulkSelectedIds.isNotEmpty()) {
            options.add(0, "✓ Add ${bulkSelectedIds.size} selected to favorites")
            options.add(1, "Move ${bulkSelectedIds.size} selected to folder")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, i ->
                when (options[i]) {
                    "Set Reminder" -> showReminderDialog(channel)
                    "Select (bulk add to favorites)" -> {
                        bulkSelectMode = true
                        bulkSelectedIds.add(channel.streamId)
                        Toast.makeText(this, "${bulkSelectedIds.size} selected — tap more channels, or wait to move/add them", Toast.LENGTH_SHORT).show()
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 3000)
                    }
                    "Deselect (bulk)" -> {
                        bulkSelectedIds.remove(channel.streamId)
                        if (bulkSelectedIds.isEmpty()) {
                            bulkSelectMode = false
                            bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        }
                    }
                    "Hide Channel" -> {
                        viewModel.hideChannel(channel.streamId)
                        Toast.makeText(this, "${channel.name} hidden. Unhide in Settings → Display.", Toast.LENGTH_SHORT).show()
                    }
                    "Channels Like This" -> showSimilarChannelsSheet(channel)
                    "Move to Folder" -> showMoveToFolderDialog(channel)
                    else -> when {
                        options[i].startsWith("✓ Add") -> {
                            bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                            viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                            Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                            bulkSelectedIds.clear()
                            bulkSelectMode = false
                        }
                        options[i].startsWith("Move") && options[i].endsWith("to folder") -> {
                            bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                            showMoveToFolderDialog(bulkSelectedIds.toList())
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveToFolderDialog(channel: ChannelEntity) {
        showMoveToFolderDialog("Move \"${channel.name}\" to", onCancel = {}) { folderId ->
            viewModel.setChannelFavoriteFolder(channel.streamId, folderId)
        }
    }

    private fun clearBulkSelection() {
        bulkSelectedIds.clear()
        bulkSelectMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
        channelAdapter.submitBulkSelection(emptySet())
    }

    private fun showMoveToFolderDialog(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        showMoveToFolderDialog("Move \"${channel.name}\" to", onCancel = {}) { folderId ->
            viewModel.setMergedChannelFolder(channel, folderId)
            Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show()
        }
    }

    // Long-press menu for merged/Providers channels — the applicable subset of the primary
    // list's channel actions (no Record/Hide: those are wired to the primary channels table).
    private fun showMergedChannelActionsMenu(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        val options = mutableListOf("Play Fullscreen", if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites")
        if (channel.isFavorite) options.add("Move to Folder")
        AlertDialog.Builder(this)
            .setTitle("${channel.name} · ${channel.serverNickname}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Play Fullscreen" -> lifecycleScope.launch {
                        try {
                            val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                            openPlayer(url, "${channel.name} · ${channel.serverNickname}", -1)
                        } catch (_: Exception) {
                            Toast.makeText(this@HomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
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

    private fun showMoveToFolderDialog(streamIds: List<Int>) {
        showMoveToFolderDialog("Move ${streamIds.size} channels to", onCancel = { clearBulkSelection() }) { folderId ->
            viewModel.setChannelsFavoriteFolder(streamIds, folderId)
            Toast.makeText(this, "Moved ${streamIds.size} channels", Toast.LENGTH_SHORT).show()
            clearBulkSelection()
        }
    }

    private fun showMoveToFolderDialog(title: String, onCancel: () -> Unit, onPicked: (Int?) -> Unit) {
        val folders = viewModel.favoriteFolders.value
        val labels = mutableListOf("Unsorted") + folders.map { it.name } + "+ New Folder"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                when (i) {
                    0 -> onPicked(null)
                    labels.size - 1 -> {
                        val et = android.widget.EditText(this).apply { hint = "Folder name" }
                        AlertDialog.Builder(this)
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
            // Only the explicit Cancel button clears the selection — tapping outside just
            // dismisses this dialog so you can keep tapping more channels to add to it (the
            // AlertDialog default of cancelable-by-tap-outside is left alone; onCancel is
            // deliberately NOT wired to setOnCancelListener, which fires for that case too).
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
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
                        val startMs = System.currentTimeMillis() + deltas[i]
                        showReminderOrRecordChoice(channel, channel.name, startMs, 60 * 60_000L, options[i])
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
                    val stopMs = if (epg.stopTimestamp > 1_000_000_000_000L) epg.stopTimestamp else epg.stopTimestamp * 1000L
                    val durationMs = (stopMs - startMs).takeIf { it > 0 } ?: 60 * 60_000L
                    showReminderOrRecordChoice(channel, epg.title, startMs, durationMs, epg.title)
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** "Remind me" already existed; recording a program used to require separately opening
     * Recordings and re-entering the channel/time/duration by hand even though this dialog
     * already knows all three — this lets either action reuse the same picked program/time. */
    private fun showReminderOrRecordChoice(
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