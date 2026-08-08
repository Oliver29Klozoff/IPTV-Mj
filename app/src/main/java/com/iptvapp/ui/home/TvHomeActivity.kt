package com.iptvapp.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import androidx.recyclerview.widget.GridLayoutManager
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
    private lateinit var mergedVodAdapter: MergedVodAdapter
    private lateinit var mergedSeriesAdapter: MergedSeriesAdapter
    private lateinit var combinedFavoriteAdapter: CombinedFavoriteAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var tvVodPosterAdapter: TvVodPosterAdapter
    private lateinit var tvSeriesPosterAdapter: TvSeriesPosterAdapter
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
    // Guards onResume()'s "nothing playing yet, resume the primary recent channel" fallback
    // against racing ahead of onCreate's own (async, up to ~3s) cold-boot resume lookup — see
    // that block's comment for the full story.
    private var coldBootResumeInProgress = false

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

    // Providers' own Channels/Movies/Series mode, independent of the top-level Section enum
    // above (which primary Live/Movies/Series tabs use) — mirrors phone's ProvidersMode in
    // HomeActivity. Resets to CHANNELS whenever Providers is freshly entered from the sidebar,
    // same "fresh visit" reasoning as phone's providersTabVisitedSinceTabSwitch.
    private enum class ProvidersMode { CHANNELS, MOVIES, SERIES }
    private var providersMode = ProvidersMode.CHANNELS

    private fun setProvidersModeButtonHighlight() {
        val active = "#008CFF"; val inactive = "#888888"
        binding.tvBtnProvidersModeChannels.setTextColor(android.graphics.Color.parseColor(if (providersMode == ProvidersMode.CHANNELS) active else inactive))
        binding.tvBtnProvidersModeMovies.setTextColor(android.graphics.Color.parseColor(if (providersMode == ProvidersMode.MOVIES) active else inactive))
        binding.tvBtnProvidersModeSeries.setTextColor(android.graphics.Color.parseColor(if (providersMode == ProvidersMode.SERIES) active else inactive))
    }

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
            clearTvBulkSelection()
        }
    }

    private fun clearTvBulkSelection() {
        bulkSelectedIds.clear()
        bulkSelectMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
        channelAdapter.submitBulkSelection(emptySet())
        updateTvBulkSelectUi()
    }

    // Merged-channel equivalent of the bulk-select state above — same shape as phone's
    // bulkSelectedMergedKeys/bulkSelectMergedMode in HomeActivity.kt.
    private val bulkSelectedMergedKeys = mutableSetOf<String>()
    private var bulkSelectMergedMode = false
    private val bulkSelectMergedIdleRunnable = Runnable {
        if (bulkSelectMergedMode && bulkSelectedMergedKeys.isNotEmpty()) {
            viewModel.bulkAddMergedFavorites(bulkSelectedMergedKeys.toSet())
            Toast.makeText(this, "Added ${bulkSelectedMergedKeys.size} channels to favorites", Toast.LENGTH_SHORT).show()
            clearTvBulkSelectionMerged()
        }
    }

    private fun clearTvBulkSelectionMerged() {
        bulkSelectedMergedKeys.clear()
        bulkSelectMergedMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
        mergedChannelAdapter.submitBulkSelection(emptySet())
        updateTvBulkSelectUi()
    }

    // Merged VOD/Series bulk-select is bulk-HIDE, not bulk-favorite (matches phone — see
    // HomeActivity's bulkSelectedMergedVodKeys/bulkSelectedMergedSeriesKeys). No idle-timeout
    // auto-commit dialog on TV, unlike phone — the persistent bulk-select bar's explicit "Done"
    // button covers that same "commit the selection" step instead.
    private val bulkSelectedMergedVodKeys = mutableSetOf<String>()
    private var bulkSelectMergedVodMode = false

    private fun clearTvBulkSelectionMergedVod() {
        bulkSelectedMergedVodKeys.clear()
        bulkSelectMergedVodMode = false
        mergedVodAdapter.submitBulkSelection(emptySet())
        updateTvBulkSelectUi()
    }

    private val bulkSelectedMergedSeriesKeys = mutableSetOf<String>()
    private var bulkSelectMergedSeriesMode = false

    private fun clearTvBulkSelectionMergedSeries() {
        bulkSelectedMergedSeriesKeys.clear()
        bulkSelectMergedSeriesMode = false
        mergedSeriesAdapter.submitBulkSelection(emptySet())
        updateTvBulkSelectUi()
    }

    private data class TvBulkState(
        val count: Int,
        val doneLabel: String = "Done",
        val selectAll: () -> Unit,
        val done: () -> Unit,
        // Only channel modes (primary/merged) set this — VOD/Series modes leave it null since
        // Done already means Hide for those (no separate Favorite concept to disambiguate from).
        val hide: (() -> Unit)? = null,
        val cancel: () -> Unit
    )

    // Central bar updater for TV bulk-select — mirrors phone's updateBulkSelectUi in
    // HomeActivity.kt. Checks each mode's flag in priority order, same "only one active at a
    // time in practice" assumption phone's version makes. Extend this `when` as merged
    // movies/series and primary series get their own bulk-select state added.
    private fun updateTvBulkSelectUi() {
        val state: TvBulkState? = when {
            bulkSelectMode && bulkSelectedIds.isNotEmpty() -> TvBulkState(
                bulkSelectedIds.size,
                selectAll = {
                    bulkSelectedIds.addAll(viewModel.channels.value.map { it.streamId })
                    channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                    bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                    bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 3000)
                },
                done = {
                    viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                    Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                    clearTvBulkSelection()
                },
                hide = {
                    val count = bulkSelectedIds.size
                    viewModel.bulkHideChannels(bulkSelectedIds.toList())
                    Toast.makeText(this, "$count channels hidden", Toast.LENGTH_SHORT).show()
                    clearTvBulkSelection()
                },
                cancel = { clearTvBulkSelection() }
            )
            bulkSelectMergedMode && bulkSelectedMergedKeys.isNotEmpty() -> TvBulkState(
                bulkSelectedMergedKeys.size,
                selectAll = {
                    bulkSelectedMergedKeys.addAll(viewModel.mergedChannels.value.map { "${it.serverIndex}:${it.streamId}" })
                    mergedChannelAdapter.submitBulkSelection(bulkSelectedMergedKeys.toSet())
                    bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                    bulkSelectHandler.postDelayed(bulkSelectMergedIdleRunnable, 3000)
                },
                done = {
                    viewModel.bulkAddMergedFavorites(bulkSelectedMergedKeys.toSet())
                    Toast.makeText(this, "Added ${bulkSelectedMergedKeys.size} channels to favorites", Toast.LENGTH_SHORT).show()
                    clearTvBulkSelectionMerged()
                },
                hide = {
                    val count = bulkSelectedMergedKeys.size
                    val items = bulkSelectedMergedKeys.mapNotNull { key ->
                        val (serverIndex, streamId) = key.split(":", limit = 2)
                        viewModel.mergedChannels.value.firstOrNull { it.serverIndex == serverIndex.toInt() && it.streamId == streamId.toInt() }
                    }
                    viewModel.bulkHideMergedChannels(items)
                    Toast.makeText(this, "$count channels hidden", Toast.LENGTH_SHORT).show()
                    clearTvBulkSelectionMerged()
                },
                cancel = { clearTvBulkSelectionMerged() }
            )
            bulkSelectMergedVodMode && bulkSelectedMergedVodKeys.isNotEmpty() -> TvBulkState(
                bulkSelectedMergedVodKeys.size,
                doneLabel = "Hide Selected",
                selectAll = {
                    bulkSelectedMergedVodKeys.addAll(viewModel.mergedVod.value.map { "${it.serverIndex}:${it.streamId}" })
                    mergedVodAdapter.submitBulkSelection(bulkSelectedMergedVodKeys.toSet())
                },
                done = {
                    val items = bulkSelectedMergedVodKeys.mapNotNull { key ->
                        val (serverIndex, streamId) = key.split(":", limit = 2)
                        viewModel.mergedVod.value.firstOrNull { it.serverIndex == serverIndex.toInt() && it.streamId == streamId.toInt() }
                    }
                    viewModel.bulkHideMergedVod(items)
                    Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} movies hidden", Toast.LENGTH_SHORT).show()
                    clearTvBulkSelectionMergedVod()
                },
                cancel = { clearTvBulkSelectionMergedVod() }
            )
            bulkSelectMergedSeriesMode && bulkSelectedMergedSeriesKeys.isNotEmpty() -> TvBulkState(
                bulkSelectedMergedSeriesKeys.size,
                doneLabel = "Hide Selected",
                selectAll = {
                    bulkSelectedMergedSeriesKeys.addAll(viewModel.mergedSeries.value.map { "${it.serverIndex}:${it.seriesId}" })
                    mergedSeriesAdapter.submitBulkSelection(bulkSelectedMergedSeriesKeys.toSet())
                },
                done = {
                    val items = bulkSelectedMergedSeriesKeys.mapNotNull { key ->
                        val (serverIndex, seriesId) = key.split(":", limit = 2)
                        viewModel.mergedSeries.value.firstOrNull { it.serverIndex == serverIndex.toInt() && it.seriesId == seriesId.toInt() }
                    }
                    viewModel.bulkHideMergedSeries(items)
                    Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} shows hidden", Toast.LENGTH_SHORT).show()
                    clearTvBulkSelectionMergedSeries()
                },
                cancel = { clearTvBulkSelectionMergedSeries() }
            )
            else -> null
        }
        if (state == null) {
            binding.tvBulkSelectBar.visibility = View.GONE
            return
        }
        binding.tvBulkSelectBar.visibility = View.VISIBLE
        binding.tvBulkSelectCount.text = "${state.count} selected"
        binding.tvBtnBulkSelectDone.text = state.doneLabel
        binding.tvBtnBulkSelectAll.setOnClickListener {
            state.selectAll()
            Toast.makeText(this, "Selected all", Toast.LENGTH_SHORT).show()
            updateTvBulkSelectUi()
        }
        binding.tvBtnBulkSelectDone.setOnClickListener { state.done() }
        if (state.hide != null) {
            binding.tvBtnBulkSelectHide.visibility = View.VISIBLE
            binding.tvBtnBulkSelectHide.setOnClickListener { state.hide.invoke() }
            binding.tvBtnBulkSelectDone.nextFocusRightId = com.iptvapp.R.id.tvBtnBulkSelectHide
            binding.tvBtnBulkSelectCancel.nextFocusLeftId = com.iptvapp.R.id.tvBtnBulkSelectHide
        } else {
            binding.tvBtnBulkSelectHide.visibility = View.GONE
            binding.tvBtnBulkSelectDone.nextFocusRightId = com.iptvapp.R.id.tvBtnBulkSelectCancel
            binding.tvBtnBulkSelectCancel.nextFocusLeftId = com.iptvapp.R.id.tvBtnBulkSelectDone
        }
        binding.tvBtnBulkSelectCancel.setOnClickListener { state.cancel() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // This screen's mini player is playing live TV essentially the entire time it's on
        // screen, same as the fullscreen PlayerActivity — without this, Fire TV's screensaver
        // kicks in on its own idle timer regardless of active playback, since nothing here was
        // telling Android the screen should stay awake (PlayerActivity/MultiViewActivity already
        // had this; this screen didn't).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs) }
        setupAdapters()
        setupSidebar()
        setupSearch()
        setupMoviesFullScreen()
        setupSeriesFullScreen()
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
        // Same LAST_PLAYED_* cold-boot resume phone's HomeActivity does — TV previously always
        // cold-booted straight to an empty sidebar with nothing auto-playing at all, unlike
        // phone which restores whatever was last playing (primary or merged) and, for a merged
        // channel, jumps the Providers list straight to it too. coldBootResumeInProgress guards
        // onResume()'s own separate "nothing playing yet, resume something" fallback, which
        // otherwise fires immediately (synchronously ahead of this async retry loop) and wins
        // the race with a primary channel every time, even when this block is about to resolve
        // to the actual last-played merged channel a moment later.
        coldBootResumeInProgress = true
        lifecycleScope.launch {
            try {
                val lastServerIndex = prefs.lastPlayedServerIndex.first()
                val lastStreamId = prefs.lastPlayedStreamId.first()
                if (lastServerIndex != -1 && lastStreamId != -1) {
                    var channel: com.iptvapp.data.local.entities.MergedChannelEntity? = null
                    for (attempt in 1..10) {
                        channel = viewModel.getMergedChannelByIndexAndId(lastServerIndex, lastStreamId)
                        if (channel != null) break
                        delay(300)
                    }
                    if (channel != null) {
                        playMergedChannel(channel)
                        selectSection(Section.PROVIDERS)
                        return@launch
                    }
                }
                val recent = viewModel.getRecentChannel()
                if (recent != null) playInMiniPlayer(recent)
                showSidebar()
            } finally {
                coldBootResumeInProgress = false
            }
        }
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
        // The currently active section's button should stay accent-colored and keep its
        // left-accent-bar marker (isSelected — see TvAccentHelper.buildFocusDrawable), not fall
        // back to the dim grey/unselected state applyToButton's fresh drawable would otherwise
        // leave every button in.
        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()); it.isSelected = false }
        activeSidebarButton().setTextColor(accent)
        activeSidebarButton().isSelected = true
        updateProvidersHealthBadge(lastProvidersDownCount)

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
        if (currentMiniUrl.isEmpty() && !coldBootResumeInProgress) {
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
        // Cold-boot resume (primary or merged) is now handled once, centrally, in onCreate —
        // this used to unconditionally resume the primary "recent channel" here too, racing
        // ahead of onCreate's merged-channel lookup (which needs a retry loop and so can take
        // up to ~3s) since setupMiniPlayer() runs synchronously before that async resume even
        // starts. It won that race essentially every time, which is why cold-booting into a
        // merged channel kept showing a primary channel instead.
    }

    // "5 · CBS Miami" when the channel has a user-assigned custom number (see
    // ChannelEntity.customNum's kdoc — auto-assigned on favoriting a US channel), otherwise just
    // the plain name. Deliberately customNum only, not the provider's raw num — those are often
    // huge/meaningless internal IDs on non-US channels too, not something worth surfacing.
    // Shared by the mini player's now-playing footer and its scroll-preview so the number shows
    // whether you're actively watching or just browsing past a numbered favorite.
    private fun miniPlayerTitleFor(channel: ChannelEntity): String =
        channel.customNum?.let { "$it · ${channel.name}" } ?: channel.name

    private fun playInMiniPlayer(channel: ChannelEntity) {
        lifecycleScope.launch {
            val url = viewModel.getLiveStreamUrl(channel.streamId)
            currentMiniUrl = url
            currentMiniTitle = miniPlayerTitleFor(channel)
            currentMiniStreamId = channel.streamId
            currentMiniServerIndex = -1
            currentMiniIsVod = false
            binding.tvTvChannelName.text = currentMiniTitle
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
                        setContentAdapter(channelAdapter, isGrid = false)
                        viewModel.selectLiveCategory(cat.categoryId)
                    }
                    Section.CATEGORIES -> {
                        setContentAdapter(channelAdapter, isGrid = false)
                        viewModel.selectFavCategory(cat.categoryId)
                    }
                    Section.MOVIES -> {
                        setContentAdapter(vodAdapter, isGrid = false)
                        viewModel.selectVodCategory(cat.categoryId)
                    }
                    Section.PROVIDERS -> when (providersMode) {
                        ProvidersMode.CHANNELS -> {
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
                                setContentAdapter(mergedChannelAdapter, isGrid = false)
                            }
                        }
                        ProvidersMode.MOVIES -> {
                            if (viewModel.selectedMergedVodServerIndex == null) {
                                viewModel.selectMergedVodServer(cat.categoryId.toInt())
                                categoryAdapter.submitList(emptyList())
                                lifecycleScope.launch {
                                    viewModel.mergedVodCategories.collect { cats ->
                                        if (viewModel.selectedMergedVodServerIndex != null) {
                                            categoryAdapter.submitList(mergedVodCategoriesToSynthetic(cats))
                                        }
                                    }
                                }
                                showCategoryPanel("MOVIES")
                                showChannels = false
                            } else {
                                val categoryId = if (cat.categoryId == NO_CATEGORY_ID) null else cat.categoryId
                                viewModel.selectMergedVodCategory(categoryId)
                                setContentAdapter(mergedVodAdapter, isGrid = false)
                            }
                        }
                        ProvidersMode.SERIES -> {
                            if (viewModel.selectedMergedSeriesServerIndex == null) {
                                viewModel.selectMergedSeriesServer(cat.categoryId.toInt())
                                categoryAdapter.submitList(emptyList())
                                lifecycleScope.launch {
                                    viewModel.mergedSeriesCategories.collect { cats ->
                                        if (viewModel.selectedMergedSeriesServerIndex != null) {
                                            categoryAdapter.submitList(mergedSeriesCategoriesToSynthetic(cats))
                                        }
                                    }
                                }
                                showCategoryPanel("SERIES")
                                showChannels = false
                            } else {
                                val categoryId = if (cat.categoryId == NO_CATEGORY_ID) null else cat.categoryId
                                viewModel.selectMergedSeriesCategory(categoryId)
                                setContentAdapter(mergedSeriesAdapter, isGrid = false)
                            }
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
                    updateTvBulkSelectUi()
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
            binding.tvTvChannelName.text = miniPlayerTitleFor(channel)
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
            onChannelClick = onChannelClick@{ channel ->
                if (bulkSelectMergedMode) {
                    val key = "${channel.serverIndex}:${channel.streamId}"
                    if (!bulkSelectedMergedKeys.add(key)) bulkSelectedMergedKeys.remove(key)
                    mergedChannelAdapter.submitBulkSelection(bulkSelectedMergedKeys.toSet())
                    Toast.makeText(this, "${bulkSelectedMergedKeys.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                    if (bulkSelectedMergedKeys.isEmpty()) bulkSelectMergedMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectMergedIdleRunnable, 3000)
                    updateTvBulkSelectUi()
                    return@onChannelClick
                }
                playMergedChannel(channel)
            },
            onFavoriteClick = { channel ->
                viewModel.setMergedChannelFavorite(channel, !channel.isFavorite)
                Toast.makeText(this, if (channel.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            // Star is now D-pad-reachable (see isTvMode below) — long-press still works too,
            // as an alternate path into the fuller actions menu (Play Fullscreen/Move to
            // Folder/bulk-select), same shape as phone's showMergedChannelActionsMenu.
            onChannelLongClick = { channel ->
                val key = "${channel.serverIndex}:${channel.streamId}"
                val options = mutableListOf(
                    "Play Fullscreen",
                    if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    if (bulkSelectedMergedKeys.contains(key)) "Deselect (bulk)" else "Select (bulk add to favorites)"
                )
                if (channel.isFavorite) options.add("Move to Folder")
                if (bulkSelectMergedMode && bulkSelectedMergedKeys.isNotEmpty()) {
                    options.add(0, "✓ Hide ${bulkSelectedMergedKeys.size} selected")
                    options.add(0, "✓ Add ${bulkSelectedMergedKeys.size} selected to favorites")
                }
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
                            "Select (bulk add to favorites)" -> {
                                bulkSelectMergedMode = true
                                bulkSelectedMergedKeys.add(key)
                                mergedChannelAdapter.submitBulkSelection(bulkSelectedMergedKeys.toSet())
                                updateTvBulkSelectUi()
                                Toast.makeText(this, "${bulkSelectedMergedKeys.size} selected — select more, or wait to add them", Toast.LENGTH_SHORT).show()
                                bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                                bulkSelectHandler.postDelayed(bulkSelectMergedIdleRunnable, 3000)
                            }
                            "Deselect (bulk)" -> {
                                bulkSelectedMergedKeys.remove(key)
                                mergedChannelAdapter.submitBulkSelection(bulkSelectedMergedKeys.toSet())
                                if (bulkSelectedMergedKeys.isEmpty()) {
                                    bulkSelectMergedMode = false
                                    bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                                }
                                updateTvBulkSelectUi()
                            }
                            "Move to Folder" -> showMoveToFolderDialog(channel)
                            else -> if (options[which].startsWith("✓ Add")) {
                                bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                                viewModel.bulkAddMergedFavorites(bulkSelectedMergedKeys.toSet())
                                Toast.makeText(this, "Added ${bulkSelectedMergedKeys.size} channels to favorites", Toast.LENGTH_SHORT).show()
                                clearTvBulkSelectionMerged()
                            } else if (options[which].startsWith("✓ Hide")) {
                                bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                                val count = bulkSelectedMergedKeys.size
                                val items = bulkSelectedMergedKeys.mapNotNull { k ->
                                    val (serverIndex, streamId) = k.split(":", limit = 2)
                                    viewModel.mergedChannels.value.firstOrNull { it.serverIndex == serverIndex.toInt() && it.streamId == streamId.toInt() }
                                }
                                viewModel.bulkHideMergedChannels(items)
                                Toast.makeText(this, "$count channels hidden", Toast.LENGTH_SHORT).show()
                                clearTvBulkSelectionMerged()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        mergedChannelAdapter.isTvMode = true

        // Providers' Movies mode — same shape as mergedChannelAdapter above, but plays directly
        // via MergedVodDetailActivity instead of the mini player (a movie is a single stream,
        // not something to zap through). Favorite/long-press menu ported from the phone's
        // equivalent (HomeActivity's Providers Movies long-press).
        mergedVodAdapter = MergedVodAdapter(
            onItemClick = onItemClick@{ vod ->
                if (bulkSelectMergedVodMode) {
                    val key = "${vod.serverIndex}:${vod.streamId}"
                    if (!bulkSelectedMergedVodKeys.add(key)) bulkSelectedMergedVodKeys.remove(key)
                    mergedVodAdapter.submitBulkSelection(bulkSelectedMergedVodKeys.toSet())
                    Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} selected", Toast.LENGTH_SHORT).show()
                    if (bulkSelectedMergedVodKeys.isEmpty()) bulkSelectMergedVodMode = false
                    updateTvBulkSelectUi()
                    return@onItemClick
                }
                startActivity(Intent(this, com.iptvapp.ui.vod.MergedVodDetailActivity::class.java).apply {
                    putExtra("server_index", vod.serverIndex)
                    putExtra("vod_stream_id", vod.streamId)
                    putExtra("vod_name", vod.name)
                    putExtra("vod_container_extension", vod.containerExtension)
                    putExtra("vod_cover", vod.streamIcon)
                    putExtra("vod_rating", vod.rating)
                    putExtra("vod_is_favorite", vod.isFavorite)
                    putExtra("server_nickname", vod.serverNickname)
                })
            },
            onFavoriteClick = { vod ->
                viewModel.setMergedVodFavorite(vod, !vod.isFavorite)
                Toast.makeText(this, if (vod.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            // Second long-press while already selecting offers Select All/Deselect All, same
            // "nowhere else in a tap-to-toggle flow to reach select-everything" reasoning as
            // phone's showBulkSelectAllMenu.
            onItemLongClick = { vod ->
                if (bulkSelectMergedVodMode) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setItems(arrayOf("Select All movies", "Deselect All", "Cancel")) { _, which ->
                            when (which) {
                                0 -> {
                                    bulkSelectedMergedVodKeys.addAll(viewModel.mergedVod.value.map { "${it.serverIndex}:${it.streamId}" })
                                    mergedVodAdapter.submitBulkSelection(bulkSelectedMergedVodKeys.toSet())
                                    Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} selected", Toast.LENGTH_SHORT).show()
                                    updateTvBulkSelectUi()
                                }
                                1 -> clearTvBulkSelectionMergedVod()
                            }
                        }
                        .show()
                    return@MergedVodAdapter
                }
                val options = mutableListOf(
                    if (vod.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    "Select (bulk hide)"
                )
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("${vod.name} · ${vod.serverNickname}")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (options[which]) {
                            "Add to Favorites", "Remove from Favorites" -> {
                                viewModel.setMergedVodFavorite(vod, !vod.isFavorite)
                                Toast.makeText(this, if (vod.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
                            }
                            "Select (bulk hide)" -> {
                                bulkSelectMergedVodMode = true
                                bulkSelectedMergedVodKeys.add("${vod.serverIndex}:${vod.streamId}")
                                mergedVodAdapter.submitBulkSelection(bulkSelectedMergedVodKeys.toSet())
                                updateTvBulkSelectUi()
                                Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} selected — select more to hide", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        mergedVodAdapter.isTvMode = true

        // Providers' Series mode — opens SeriesDetailActivity per tap, same as the phone's
        // equivalent (a series is never itself a single playable stream).
        mergedSeriesAdapter = MergedSeriesAdapter(
            onItemClick = onItemClick@{ series ->
                if (bulkSelectMergedSeriesMode) {
                    val key = "${series.serverIndex}:${series.seriesId}"
                    if (!bulkSelectedMergedSeriesKeys.add(key)) bulkSelectedMergedSeriesKeys.remove(key)
                    mergedSeriesAdapter.submitBulkSelection(bulkSelectedMergedSeriesKeys.toSet())
                    Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} selected", Toast.LENGTH_SHORT).show()
                    if (bulkSelectedMergedSeriesKeys.isEmpty()) bulkSelectMergedSeriesMode = false
                    updateTvBulkSelectUi()
                    return@onItemClick
                }
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra("series_id", series.seriesId)
                    putExtra("series_name", series.name)
                    putExtra("series_cover", series.cover)
                    putExtra("series_genre", series.genre)
                    putExtra("series_rating", series.rating)
                    putExtra("series_plot", series.plot)
                    putExtra("server_index", series.serverIndex)
                })
            },
            onFavoriteClick = { series ->
                viewModel.setMergedSeriesFavorite(series, !series.isFavorite)
                Toast.makeText(this, if (series.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            onItemLongClick = { series ->
                if (bulkSelectMergedSeriesMode) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setItems(arrayOf("Select All shows", "Deselect All", "Cancel")) { _, which ->
                            when (which) {
                                0 -> {
                                    bulkSelectedMergedSeriesKeys.addAll(viewModel.mergedSeries.value.map { "${it.serverIndex}:${it.seriesId}" })
                                    mergedSeriesAdapter.submitBulkSelection(bulkSelectedMergedSeriesKeys.toSet())
                                    Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} selected", Toast.LENGTH_SHORT).show()
                                    updateTvBulkSelectUi()
                                }
                                1 -> clearTvBulkSelectionMergedSeries()
                            }
                        }
                        .show()
                    return@MergedSeriesAdapter
                }
                val options = mutableListOf(
                    if (series.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    "Select (bulk hide)"
                )
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("${series.name} · ${series.serverNickname}")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (options[which]) {
                            "Add to Favorites", "Remove from Favorites" -> {
                                viewModel.setMergedSeriesFavorite(series, !series.isFavorite)
                                Toast.makeText(this, if (series.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
                            }
                            "Select (bulk hide)" -> {
                                bulkSelectMergedSeriesMode = true
                                bulkSelectedMergedSeriesKeys.add("${series.serverIndex}:${series.seriesId}")
                                mergedSeriesAdapter.submitBulkSelection(bulkSelectedMergedSeriesKeys.toSet())
                                updateTvBulkSelectUi()
                                Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} selected — select more to hide", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        mergedSeriesAdapter.isTvMode = true

        combinedFavoriteAdapter = CombinedFavoriteAdapter(
            onChannelClick = ::onCombinedFavoriteClick,
            onFavoriteClick = ::onCombinedFavoriteStarClick,
            onChannelLongClick = ::onCombinedFavoriteLongClick
        )
        combinedFavoriteAdapter.isTvMode = true
        combinedFavoriteAdapter.onChannelFocused = ::onCombinedFavoriteFocused

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

        // Full-screen Movies browse grid (see showMoviesFullScreen) — unlike vodAdapter above
        // (which plays into the mini player, since it lives alongside one), this view has no
        // mini player visible at all, so a tap plays straight into fullscreen PlayerActivity
        // instead — the Netflix-style "tap a poster, it plays" pattern this screen is going for.
        // Long-press still opens the same detail screen; short-press on the star still favorites
        // without opening/playing anything.
        tvVodPosterAdapter = TvVodPosterAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    try {
                        val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                        startActivity(Intent(this@TvHomeActivity, com.iptvapp.ui.player.PlayerActivity::class.java).apply {
                            putExtra("stream_url", url)
                            putExtra("stream_title", vod.name)
                            putExtra("stream_id", vod.streamId)
                            putExtra("is_vod", true)
                            putExtra("resume_ms", vod.watchedMs)
                        })
                    } catch (_: Exception) {
                        Toast.makeText(this@TvHomeActivity, "Couldn't load this title", Toast.LENGTH_SHORT).show()
                    }
                }
            },
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

        // Full-screen Series browse grid (see showSeriesFullScreen) — unlike seriesAdapter above,
        // a series has no direct "play" the way a movie does (season/episode selection always
        // comes first), so both a short click AND long-press here open the same detail screen —
        // there's no separate destination to distinguish between, unlike Movies' click-plays/
        // long-press-opens-detail split.
        tvSeriesPosterAdapter = TvSeriesPosterAdapter(
            onSeriesClick = { series ->
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra("series_id", series.seriesId)
                    putExtra("series_name", series.name)
                    putExtra("series_cover", series.cover)
                    putExtra("series_genre", series.genre)
                    putExtra("series_rating", series.rating)
                    putExtra("series_plot", series.plot)
                })
            },
            onSeriesLongClick = { series ->
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
            onChannelClick = { row ->
                val primaryChannel = row.channel
                if (primaryChannel != null) {
                    lifecycleScope.launch {
                        playInMiniPlayer(primaryChannel)
                        viewModel.markChannelWatched(primaryChannel.streamId)
                        viewModel.setCurrentlyPlaying(primaryChannel.streamId)
                    }
                } else {
                    row.mergedChannel?.let { playMergedChannel(it) }
                }
                scheduleTvAutoCollapse()
            },
            onChannelLongClick = { row -> showTvReminderDialogForRow(row) }
        )
        binding.btnGuideRefresh.setOnClickListener {
            Toast.makeText(this, "Refreshing guide…", Toast.LENGTH_SHORT).show()
            viewModel.loadGuide(forceRefresh = true)
        }

        binding.tvRvCategories.layoutManager = LinearLayoutManager(this)
        binding.tvRvCategories.adapter = categoryAdapter
        setContentAdapter(channelAdapter, isGrid = false)
        binding.tvRvEpgGuide.layoutManager = LinearLayoutManager(this)
        binding.tvRvEpgGuide.adapter = epgGuideAdapter

        binding.tvRvMoviesFsGrid.layoutManager = GridLayoutManager(this, 5)
        binding.tvRvMoviesFsGrid.adapter = tvVodPosterAdapter

        binding.tvRvSeriesFsGrid.layoutManager = GridLayoutManager(this, 5)
        binding.tvRvSeriesFsGrid.adapter = tvSeriesPosterAdapter
    }

    // tvRvContent is shared across every TV section (Live, Providers channels/movies/series,
    // Categories, Favorites) — every section swaps in its own adapter here. A grid layout was
    // tried for Favorites and rejected (too small/cramped either way tiles were sized, and the
    // user just wanted a list with genre chips like the phone has) — plain LinearLayoutManager
    // for every section, no per-section branching needed.
    private fun setContentAdapter(adapter: RecyclerView.Adapter<*>, isGrid: Boolean = false) {
        binding.tvRvContent.layoutManager = LinearLayoutManager(this)
        binding.tvRvContent.adapter = adapter
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

    // Screensaver-style idle expand: 1 minute with no D-pad input while at rest on the sidebar
    // (navState == SIDEBAR — never fires mid-browse in a category/channel/guide list, only
    // resetIdleExpandTimer's own gate below cares about that) hides the sidebar entirely and
    // lets the mini player's column fill the whole screen, no controls/footer, just video —
    // explicitly asked for as a "less TV chrome sitting on screen while just watching" mode. Any
    // D-pad press (see dispatchKeyEvent) restores the sidebar and re-arms this same timer.
    private val idleExpandHandler = Handler(Looper.getMainLooper())
    private val idleExpandRunnable = Runnable { expandMiniPlayerFullScreen() }
    private var miniPlayerExpanded = false

    private fun resetIdleExpandTimer() {
        idleExpandHandler.removeCallbacks(idleExpandRunnable)
        if (miniPlayerExpanded) {
            collapseMiniPlayerFromFullScreen()
        }
        if (navState == NavState.SIDEBAR) {
            idleExpandHandler.postDelayed(idleExpandRunnable, 60_000L)
        }
    }

    private fun expandMiniPlayerFullScreen() {
        if (navState != NavState.SIDEBAR) return
        miniPlayerExpanded = true
        binding.tvLeftPanel.visibility = View.GONE
        binding.tvMiniPlayerFooter.visibility = View.GONE
        binding.tvEpgProgress.visibility = View.GONE
    }

    private fun collapseMiniPlayerFromFullScreen() {
        miniPlayerExpanded = false
        binding.tvLeftPanel.visibility = View.VISIBLE
        binding.tvMiniPlayerFooter.visibility = View.VISIBLE
        resetMiniPreviewToNowPlaying()
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
        resetIdleExpandTimer()
    }

    // Genuinely idle sidebar — no section highlighted, no content panel shown, just the nav list
    // and the mini player. Deliberately does NOT touch currentSection (every other codepath here
    // assumes it always names a real, currently-loaded section) — this is a purely visual "at
    // rest" state that only ever gets left by the user picking a real section, at which point
    // the normal selectSection(...) click handlers take over exactly as they always have. Used
    // by hideMoviesFullScreen/hideSeriesFullScreen: Back from Movies/Series lands here instead of
    // auto-selecting whichever section was active before, so the app doesn't presume what the
    // user wants next.
    private fun showBareSidebar() {
        cancelTvAutoCollapse()
        navState = NavState.SIDEBAR
        navHasCategoryStep = false
        resizeLeftPanel(expanded = false)
        binding.tvSidebar.visibility = View.VISIBLE
        binding.tvCatPanel.visibility = View.GONE
        binding.tvChanPanel.visibility = View.GONE
        binding.tvGuidePanel.visibility = View.GONE
        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()); it.isSelected = false }
        binding.btnTvFavorites.requestFocus()
        resetMiniPreviewToNowPlaying()
        resetIdleExpandTimer()
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
        // tvProvidersModeRow lives inside this shared panel (every section's server/category
        // picker routes through showCategoryPanel) — it was only ever set VISIBLE once, in
        // showMergedChannelsPanel(), and never set back to GONE for any other section, so once
        // Providers had been opened even once, the row stayed visible on every other sidebar tab
        // too (Live, Categories, Movies, Series, Favorites all share this same panel).
        binding.tvProvidersModeRow.visibility = if (currentSection == Section.PROVIDERS) View.VISIBLE else View.GONE
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
        binding.tvBtnChanRefresh.setOnClickListener { refreshCurrentProvidersMode() }
        // Same refresh, reachable right in the mode row (server-picker level) — tvBtnChanRefresh
        // above only ever appears after drilling into a category's leaf list, which for Movies/
        // Series (and, it turns out, Channels too — a pre-existing gap) never actually showed
        // since its visibility check only matched the literal title "PROVIDERS", not the real
        // category name passed to showChannelPanel. This one is always reachable instead.
        binding.tvBtnProvidersModeRefresh.setOnClickListener { refreshCurrentProvidersMode() }
        // Providers' own Channels/Movies/Series mode row — see ProvidersMode kdoc. Switching
        // always resets to that mode's top level (server picker), same as phone.
        binding.tvBtnProvidersModeChannels.setOnClickListener {
            providersMode = ProvidersMode.CHANNELS
            setProvidersModeButtonHighlight()
            viewModel.resetMergedSelection()
            setContentAdapter(categoryAdapter, isGrid = false)
            categoryAdapter.submitList(mergedServersToSynthetic(viewModel.mergedServers.value))
            showCategoryPanel("PROVIDERS")
        }
        binding.tvBtnProvidersModeMovies.setOnClickListener {
            providersMode = ProvidersMode.MOVIES
            setProvidersModeButtonHighlight()
            viewModel.startObservingMergedVodServers()
            showMergedVodPanel()
        }
        binding.tvBtnProvidersModeSeries.setOnClickListener {
            providersMode = ProvidersMode.SERIES
            setProvidersModeButtonHighlight()
            viewModel.startObservingMergedSeriesServers()
            showMergedSeriesPanel()
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

    // Appends a count to a sidebar button's label (e.g. "FAVORITES · 12") so the sidebar is
    // scannable without opening each section — omitted entirely at 0 rather than showing "· 0",
    // since an empty section reads more clearly from a bare label than a zero count.
    private fun updateSidebarLabel(button: Button, baseLabel: String, count: Int) {
        val newText = if (count > 0) "$baseLabel ($count)" else baseLabel
        if (button.text == newText) return
        button.text = newText
        // computeSidebarContentWidth() measures button labels once and caches the result — a
        // count arriving after that first measurement (favorites/categories load shortly after
        // the sidebar itself) would otherwise leave the panel too narrow for the longer label.
        sidebarContentWidthPx = 0
        if (navState == NavState.SIDEBAR) resizeLeftPanel(expanded = false)
    }

    // Distinct from updateSidebarLabel's neutral "(count)" — a down provider is a warning, not
    // just information, so it gets a "⚠" prefix and a red tint instead of the section's normal
    // accent color, and clears back to the plain label + accent color once every provider is up
    // again (see HomeViewModel.providersDownCount's kdoc for what "0" means here). Stored so
    // selectSection()/applyAccent() — which otherwise unconditionally recolor every sidebar
    // button, including this one — can re-run this instead of stomping the warning color with
    // their own plain grey/accent when the active section or accent theme changes.
    private var lastProvidersDownCount: Int = 0

    private fun updateProvidersHealthBadge(downCount: Int) {
        lastProvidersDownCount = downCount
        val button = binding.btnTvProviders
        val newText = if (downCount > 0) "⚠ PROVIDERS ($downCount)" else "PROVIDERS"
        if (button.text != newText) {
            button.text = newText
            sidebarContentWidthPx = 0
            if (navState == NavState.SIDEBAR) resizeLeftPanel(expanded = false)
        }
        val color = if (downCount > 0) 0xFFFF5252.toInt()
            else if (currentSection == Section.PROVIDERS) currentAccent
            else 0xFF888888.toInt()
        button.setTextColor(color)
    }

    private fun selectSection(section: Section) {
        currentSection = section
        sectionButtons.forEach { it.setTextColor(0xFF888888.toInt()); it.isSelected = false }
        activeSidebarButton().setTextColor(currentAccent)
        activeSidebarButton().isSelected = true
        updateProvidersHealthBadge(lastProvidersDownCount)
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
            Section.MOVIES -> showMoviesFullScreen()
            Section.SERIES -> showSeriesFullScreen()
            // Goes straight to every favorited channel, no genre-picker step — explicitly asked
            // for over the previous "always show genre tiles first" behavior, which added an
            // extra screen the user didn't want between the sidebar and their actual favorites.
            Section.FAVORITES -> showFavoriteGenreChannels(FAV_GENRE_ALL_ID, "FAVORITES")
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
    // Previously only jumped once per fresh visit to Providers, then fell back to the plain
    // top-level browse on every re-entry — this broke the common flow of playing a channel from
    // Providers (which never leaves the PROVIDERS section, so the guard was already tripped) and
    // then going back into Providers to pick another: it never landed on what was actually
    // playing. Now jumps every time, unconditionally.
    private fun showMergedChannelsPanel() {
        // Entering PROVIDERS fresh from the sidebar always resets to Channels mode, same as
        // phone's Providers tab resetting to Live on a fresh tab switch — avoids landing back
        // on whatever mode was last used with no way to tell without re-checking the buttons.
        providersMode = ProvidersMode.CHANNELS
        setProvidersModeButtonHighlight()
        val playingServerIndex = currentMiniServerIndex
        val playingStreamId = currentMiniMergedStreamId
        if (playingServerIndex != -1 && playingStreamId != -1) {
            lifecycleScope.launch {
                // Same cold-start-refresh race as phone's showAllProviders()/
                // loadLastWatchedChannel() — the merged-channel auto-refresh can still be
                // clearing/re-inserting this exact server's rows when Providers is opened right
                // after a cold boot, so a single-shot lookup can land in that gap and miss a
                // channel that's genuinely there moments later. Same short retry window.
                var channel: com.iptvapp.data.local.entities.MergedChannelEntity? = null
                for (attempt in 1..10) {
                    channel = viewModel.getMergedChannelByIndexAndId(playingServerIndex, playingStreamId)
                    if (channel != null) break
                    delay(300)
                }
                if (channel != null) {
                    jumpToPlayingMergedChannelInProviders(channel)
                } else {
                    showMergedChannelsPanelFromTop()
                }
            }
        } else {
            showMergedChannelsPanelFromTop()
        }
    }

    private fun showMergedChannelsPanelFromTop() {
        viewModel.resetMergedSelection()
        setContentAdapter(categoryAdapter, isGrid = false)
        categoryAdapter.submitList(mergedServersToSynthetic(viewModel.mergedServers.value))
        showCategoryPanel("PROVIDERS")
    }

    // Shared click/favorite/long-click/focus handlers for Favorites.
    private fun onCombinedFavoriteClick(item: CombinedFavorite) {
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
    }

    private fun onCombinedFavoriteStarClick(item: CombinedFavorite) {
        viewModel.toggleCombinedFavorite(item)
        val wasFavorite = when (item) {
            is CombinedFavorite.Primary -> item.channel.isFavorite
            is CombinedFavorite.Merged -> item.channel.isFavorite
        }
        Toast.makeText(this, if (wasFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
    }

    private fun onCombinedFavoriteLongClick(item: CombinedFavorite) {
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

    private fun onCombinedFavoriteFocused(item: CombinedFavorite) {
        if (item is CombinedFavorite.Primary) {
            if (preWarmEnabled) preWarmChannel(item.channel)
        }
    }

    private fun jumpToPlayingMergedChannelInProviders(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        setContentAdapter(mergedChannelAdapter, isGrid = false)
        viewModel.selectMergedServer(channel.serverIndex)
        viewModel.selectMergedCategory(channel.categoryId)
        showChannelPanel("PROVIDERS")
        // showChannelPanel() alone doesn't imply a category step was ever shown — normally that's
        // only set by actually visiting showCategoryPanel() first, but this jump skips straight
        // to the channel list. A Providers channel list is always one level below the provider's
        // category list by construction, so Left/Back from here must go there next, not straight
        // to the sidebar (which is what a stale/false navHasCategoryStep would otherwise do).
        // tvCatTitle is set explicitly too — goBackOneLevel()/Back re-show whatever title is
        // already sitting in it, which without this line would still be whatever the last REAL
        // category visit left there (a different section entirely, or blank on a cold jump).
        navHasCategoryStep = true
        binding.tvCatTitle.text = "PROVIDERS"
        // categoryAdapter's underlying list is shared across every section's picker (Movies
        // categories, Series categories, the provider list, etc.) — normally set once when the
        // user actually clicks into that picker, which this jump skips entirely. Without
        // resubmitting the provider list here, a later Left/Back into the category panel would
        // show whatever unrelated list happened to be loaded last, not the provider picker.
        binding.tvRvCategories.adapter = categoryAdapter
        categoryAdapter.submitList(mergedServersToSynthetic(viewModel.mergedServers.value))
        var scrolled = false
        lifecycleScope.launch {
            viewModel.mergedChannels.collect { list ->
                if (scrolled || viewModel.selectedMergedServerIndex != channel.serverIndex) return@collect
                mergedChannelAdapter.submitList(list)
                val pos = list.indexOfFirst { it.streamId == channel.streamId }
                if (pos >= 0) {
                    scrolled = true
                    focusAdapterPositionRetrying(binding.tvRvContent, pos)
                }
            }
        }
    }

    private fun showMergedVodPanel() {
        viewModel.resetMergedVodSelection()
        setContentAdapter(categoryAdapter, isGrid = false)
        // startObservingMergedVodServers() (called right before this) only just started an
        // async collector — reading viewModel.mergedVodServers.value synchronously here was
        // still the pre-refresh snapshot (empty on a fresh app session), so the very first tap
        // on Movies submitted an empty list. focusAdapterPositionRetrying then couldn't find a
        // view holder at position 0, exhausted its retries, and fell back to focusing the
        // RecyclerView itself — which isn't focusable when empty, so default focus search
        // landed on the nearest focusable view instead (the ← back button), which is exactly
        // the "jumps to the back button" bug. Collecting the flow (like showMergedChannelsPanel
        // effectively gets for free from loadAll()'s always-running collector) instead of a
        // one-shot snapshot fixes it at the source.
        lifecycleScope.launch {
            viewModel.mergedVodServers.collect { servers ->
                if (viewModel.selectedMergedVodServerIndex == null && currentSection == Section.PROVIDERS && providersMode == ProvidersMode.MOVIES) {
                    categoryAdapter.submitList(mergedVodServersToSynthetic(servers))
                }
            }
        }
        showCategoryPanel("MOVIES")
    }

    private fun showMergedSeriesPanel() {
        viewModel.resetMergedSeriesSelection()
        setContentAdapter(categoryAdapter, isGrid = false)
        lifecycleScope.launch {
            viewModel.mergedSeriesServers.collect { servers ->
                if (viewModel.selectedMergedSeriesServerIndex == null && currentSection == Section.PROVIDERS && providersMode == ProvidersMode.SERIES) {
                    categoryAdapter.submitList(mergedSeriesServersToSynthetic(servers))
                }
            }
        }
        showCategoryPanel("SERIES")
    }

    private val NO_CATEGORY_ID = "__uncategorized__"
    private val FAV_GENRE_ALL_ID = "All"

    private fun genreFilterFavorites(genre: String, favorites: List<CombinedFavorite>): List<CombinedFavorite> {
        if (genre == FAV_GENRE_ALL_ID) return favorites
        return favorites.filter { GenreClassifier.matches(genre, it.categoryName) }
    }

    // Read by the shared viewModel.combinedFavorites collector too, so a later re-emission of
    // the underlying favorites Flow (e.g. a background sync) keeps applying the same filter
    // instead of the collector's normal unfiltered submission silently overwriting it.
    private var activeFavoriteGenre = FAV_GENRE_ALL_ID

    private fun showFavoriteGenreChannels(genre: String, title: String) {
        activeFavoriteGenre = genre
        setContentAdapter(combinedFavoriteAdapter)
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
            val allFavorites = viewModel.getCombinedFavoritesSnapshot()
            updateFavoriteGenreChips(allFavorites)
            val favorites = genreFilterFavorites(genre, allFavorites)
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

    // Genre chips above the Favorites list — same GenreClassifier keyword bucketing the phone's
    // Favorites tab uses, reusing the existing tvGenreChipContainer row (built for Series/Movies
    // genre folders) instead of a separate picker screen the user explicitly didn't want.
    private fun updateFavoriteGenreChips(favorites: List<CombinedFavorite>) {
        val favCategoryNames = favorites.mapNotNull { it.categoryName }
        val detected = GenreClassifier.detectGenres(favCategoryNames)
        if (detected.size <= 1) { binding.tvGenreChipScroll.visibility = View.GONE; return }
        binding.tvGenreChipScroll.visibility = View.VISIBLE
        val container = binding.tvGenreChipContainer
        container.removeAllViews()
        for (genre in detected) {
            container.addView(buildTvGenreChip(genre, genre == activeFavoriteGenre) {
                showFavoriteGenreChannels(genre, binding.tvCatTitle.text.toString().ifBlank { "FAVORITES" })
            })
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

    // Movies/Series equivalents of the two functions above, for tvProvidersModeRow's Movies/
    // Series buttons — same simple shape (no server-prefix scoping on categoryId, since TV's
    // categoryAdapter is only ever showing one server's categories at a time here, unlike
    // phone's shared cross-mode adapter). Hidden-categories show/hide toggle intentionally not
    // ported yet — a category hidden on phone just doesn't show here either, matching how
    // primary Movies/Series already behave on TV (no hidden-category toggle there either).
    private fun mergedVodServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedVodServerSummary>): List<CategoryEntity> =
        list.filter { it.serverIndex != -1 }.map {
            CategoryEntity(
                categoryId = it.serverIndex.toString(),
                categoryName = "${it.serverNickname} (${it.vodCount})",
                parentId = 0,
                type = "merged_vod_server"
            )
        }

    private fun mergedVodCategoriesToSynthetic(list: List<com.iptvapp.data.local.entities.MergedVodCategorySummary>): List<CategoryEntity> =
        list.map {
            CategoryEntity(
                categoryId = it.categoryId ?: NO_CATEGORY_ID,
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.vodCount})",
                parentId = 0,
                type = "merged_vod_category"
            )
        }

    private fun mergedSeriesServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedSeriesServerSummary>): List<CategoryEntity> =
        list.filter { it.serverIndex != -1 }.map {
            CategoryEntity(
                categoryId = it.serverIndex.toString(),
                categoryName = "${it.serverNickname} (${it.seriesCount})",
                parentId = 0,
                type = "merged_series_server"
            )
        }

    private fun mergedSeriesCategoriesToSynthetic(list: List<com.iptvapp.data.local.entities.MergedSeriesCategorySummary>): List<CategoryEntity> =
        list.map {
            CategoryEntity(
                categoryId = it.categoryId ?: NO_CATEGORY_ID,
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.seriesCount})",
                parentId = 0,
                type = "merged_series_category"
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
        // Movies full-screen browse (tvMoviesFullScreen) is a separate, simpler screen that
        // doesn't participate in navState/tvLeftPanel/tvChanPanel at all — it's just a search
        // bar, a chip row, and a grid, all of which default Android focus search already handles
        // correctly (confirmed live: Up/Down/Left/Right between the grid's cells and up into the
        // chips/search bar all work with zero custom handling). Only Back needs an explicit
        // override here, to close the overlay instead of falling through to every other branch
        // below (which all assume navState/tvChanPanel state this screen never sets).
        if (binding.tvMoviesFullScreen.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideMoviesFullScreen()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Series full-screen browse (tvSeriesFullScreen) — exact mirror of the Movies guard above.
        if (binding.tvSeriesFullScreen.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideSeriesFullScreen()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key activity while a panel is open resets the auto-collapse idle timer — this
            // used to only reset on D-pad navigation keys specifically, so typing into a search
            // box (letter/number keys, none of which are D-pad keys) never reset it at all,
            // collapsing back to the sidebar 10s after the last D-pad press even while actively
            // still typing a query.
            if (navState != NavState.SIDEBAR) {
                scheduleTvAutoCollapse()
            }
            // Screensaver-style idle expand — see resetIdleExpandTimer's own kdoc. Resets (and
            // re-collapses an already-expanded mini player) on every key, same "any activity
            // counts" reasoning as scheduleTvAutoCollapse above; the function itself only
            // actually arms a new timer when at rest on the sidebar.
            resetIdleExpandTimer()
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
                // 5 digits, not 4 — this provider's US channels are numbered 34783-46555 (well
                // past 9999), so a 4-digit cap made them entirely unreachable by number entry.
                if (channelNumberBuffer.length < 5) channelNumberBuffer += digit
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
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (binding.tvGenreChipContainer.hasFocus() || isProvidersModeRowFocused() || isTvBulkSelectBarFocused()) {
                    // Let default focus search move between sibling genre chips / Providers
                    // Channels-Movies-Series buttons / bulk-select bar buttons instead of jumping
                    // straight to the mini player or being swallowed by the CATEGORIES catch-all
                    // below — these rows previously had every Left/Right press unconditionally
                    // intercepted before it could reach their own buttons.
                } else when (navState) {
                    NavState.SIDEBAR -> {
                        navState = NavState.CHANNELS
                        binding.tvMiniPlayerContainer.requestFocus()
                        return true
                    }
                    NavState.CATEGORIES -> return true
                    NavState.CHANNELS -> {
                        // A channel row's own star sits to its right via nextFocusRightId (see
                        // ChannelAdapter/MergedChannelAdapter's isTvMode wiring) — RIGHT used to
                        // skip straight past it to the mini player unconditionally, so the star
                        // was only reachable via a second, separate RIGHT press from a state that
                        // was never actually reachable first. Try the row's own focus search
                        // first; only fall through to the mini-player shortcut once that's
                        // exhausted (i.e. focus is already on the star, or not on a row at all).
                        val focused = currentFocus
                        val rightTarget = focused?.focusSearch(View.FOCUS_RIGHT)
                        if (focused != null && rightTarget != null && rightTarget !== focused &&
                            binding.tvRvContent.let { it.hasFocus() || focused === it } &&
                            rightTarget !== binding.tvMiniPlayerContainer
                        ) {
                            rightTarget.requestFocus()
                            return true
                        }
                        if (currentFocus !== binding.tvMiniPlayerContainer) {
                            binding.tvMiniPlayerContainer.requestFocus()
                            return true
                        }
                    }
                }
                // Left returns from the mini player to whichever list it was reached from; it
                // does not drill back a level — that ended up conflicting with too many other
                // uses of Left across different screens. Use BACK for drilling back a level.
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentFocus === binding.tvMiniPlayerContainer) {
                        when {
                            binding.tvGuidePanel.visibility == View.VISIBLE -> binding.tvRvEpgGuide.requestFocus()
                            binding.tvChanPanel.visibility == View.VISIBLE -> binding.tvRvContent.requestFocus()
                            else -> showSidebar()
                        }
                        return true
                    } else if (isProvidersModeRowFocused() || isTvBulkSelectBarFocused()) {
                        // See the RIGHT-key comment above — let default focus search move
                        // between Channels/Movies/Series (or the bulk-select bar's own buttons)
                        // instead of any of the branches below.
                    } else if (binding.tvRvContent.hasFocus() || binding.tvRvCategories.hasFocus()) {
                        // Deep in a long list, climbing back up to search/refresh/Back one Up
                        // press at a time was slow and unreliable — Left jumps straight there.
                        focusTopOfPanel(); return true
                    } else if (binding.tvGenreChipContainer.hasFocus()) {
                        // Let default focus search move between sibling genre chips instead of
                        // being swallowed by the CATEGORIES/CHANNELS catch-all below.
                    } else if (navState == NavState.CATEGORIES || navState == NavState.CHANNELS) {
                        return true
                    } else if (navState == NavState.SIDEBAR && binding.tvSidebar.hasFocus()) {
                        // Focus is already on a sidebar button (e.g. right after tapping
                        // Providers/Live/etc., before drilling into anything) — there is nothing
                        // further left to move to, so this must be swallowed here. Previously it
                        // fell through to super.dispatchKeyEvent(), whose default focus search
                        // found nothing further left within the activity and escaped to the
                        // launcher/Home screen instead of just doing nothing, which read as "Left
                        // takes me back a whole screen" instead of "Left does nothing, I'm
                        // already at the leftmost column."
                        return true
                    }
                }
                // Back goes up one drill level. From the guide panel or a channel/movie/
                // series list it returns to the sidebar (or the category panel it drilled
                // from). At the top level (sidebar), require a second Back press within 2s
                // to exit.
                KeyEvent.KEYCODE_BACK -> {
                    when (navState) {
                        NavState.CHANNELS, NavState.CATEGORIES -> { goBackOneLevel(); return true }
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
        setContentAdapter(channelAdapter, isGrid = false)
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
        setContentAdapter(channelAdapter, isGrid = false)
        val favCats = viewModel.favoriteLiveCategories.value
        categoryAdapter.submitList(favCats)
        if (favCats.isNotEmpty()) viewModel.selectFavCategory(favCats.first().categoryId)
        else channelAdapter.submitList(emptyList())
    }

    // ── Movies: full-screen Netflix-style browse (tvMoviesFullScreen) ─────────
    //
    // Replaces the old sidebar-drilldown Movies section (categories -> pick one -> flat list)
    // with a single screen: search bar, genre filter chips, and one big poster grid across every
    // movie at once — viewModel.vod (repository.getAllVod(), already English-only filtered by
    // HomeViewModel) rather than category-by-category browsing via vodAdapter/tvChanPanel.
    // Genre filtering here works directly off each VodEntity's own categoryId instead of
    // GenreBuckets.bucketsFor(categoryName) + a separate "selected category" step, since there's
    // no per-category drill-in left to select — see moviesFsGenreBucketFor.
    private var activeMoviesFsGenre: String? = null
    // "Favorites" chip — a separate axis from genre (mutually exclusive with it, like a second
    // "folder" next to All/Comedy/Drama/... rather than a filter that combines with them), so
    // picking Favorites clears any active genre and vice versa.
    private var moviesFsFavoritesOnly: Boolean = false
    private var moviesFsSearchQuery: String = ""
    private var moviesFsSearchDebounceJob: kotlinx.coroutines.Job? = null

    private fun setupMoviesFullScreen() {
        binding.tvBtnMoviesFsBack.setOnClickListener { hideMoviesFullScreen() }
        binding.tvBtnMoviesFsRefresh.setOnClickListener {
            binding.tvBtnMoviesFsRefresh.isEnabled = false
            binding.tvBtnMoviesFsRefresh.text = "…"
            viewModel.refreshMovies { message ->
                binding.tvBtnMoviesFsRefresh.isEnabled = true
                binding.tvBtnMoviesFsRefresh.text = "⟳"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        binding.tvBtnMoviesFsClearSearch.setOnClickListener {
            binding.tvEtMoviesFsSearch.setText("")
        }
        // Search already filters live as you type — this button/action just skips the debounce
        // and confirms "yes, searching" for remotes whose Next/search key doesn't reliably send
        // the EditText's own actionSearch IME action (see tvBtnMoviesFsSearch's own kdoc).
        binding.tvBtnMoviesFsSearch.setOnClickListener { commitMoviesFsSearchNow() }
        binding.tvEtMoviesFsSearch.setOnEditorActionListener { _, _, _ -> commitMoviesFsSearchNow(); true }
        binding.tvEtMoviesFsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString()
                binding.tvBtnMoviesFsClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                // Same "wait for 2+ characters, or clearing back to empty" gate as the drilled-in
                // search (setupSearch) — a single typed letter against ~176k titles matches too
                // broadly to be useful and just churns the grid; skip filtering (and the debounce
                // delay below) until there's enough to actually narrow results, but still react
                // immediately to clearing the box back to empty (q.isEmpty()) so search doesn't
                // stay stuck on a stale filter after a full delete.
                //
                // Backspacing a multi-character query down to exactly 1 character used to hit
                // this same early-return WITHOUT clearing moviesFsSearchQuery or resubmitting —
                // the grid stayed frozen on the previous (longer) query's filtered results with
                // no way to clear it short of deleting that last character too (confirmed live:
                // "hunger" backspaced to "h" left the Hunger Games results on screen
                // indefinitely). Below 2 characters the search should behave exactly like empty:
                // no active filter, full grid restored.
                if (q.length < 2) {
                    moviesFsSearchDebounceJob?.cancel()
                    moviesFsSearchQuery = ""
                    submitFilteredMoviesFs(viewModel.vod.value)
                    return
                }
                // Same debounce shape as the drilled-in search (setupSearch) — filtering the
                // full movie list on every keystroke is cheap enough not to strictly need this,
                // but debouncing avoids re-filtering+re-diffing a few hundred items per
                // keystroke while the user is still mid-word.
                moviesFsSearchDebounceJob?.cancel()
                moviesFsSearchDebounceJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(200)
                    moviesFsSearchQuery = q
                    submitFilteredMoviesFs(viewModel.vod.value)
                }
            }
        })
    }

    // Bypasses the debounce and applies whatever's currently typed immediately — used by both
    // the explicit Search button and the keyboard's Next/search action.
    private fun commitMoviesFsSearchNow() {
        moviesFsSearchDebounceJob?.cancel()
        moviesFsSearchQuery = binding.tvEtMoviesFsSearch.text.toString()
        submitFilteredMoviesFs(viewModel.vod.value)
    }

    private fun showMoviesFullScreen() {
        binding.tvMainContent.visibility = View.GONE
        binding.tvMoviesFullScreen.visibility = View.VISIBLE
        // Movies is a full-screen takeover with no mini player visible at all — pausing here
        // (not stop/clearMediaItems, which would lose the resume position and the "jump back to
        // what's playing" state Favorites/Live's own auto-focus relies on) avoids audio from a
        // channel still playing behind a screen that gives no indication it's still going.
        miniPlayer?.pause()
        // viewModel.vod.value can still be empty this early on a large catalog (loading the full
        // list takes real time — see VodDao.getVodFirstPage's kdoc) — fall back to the fast first
        // page so the grid isn't blank on first open. The vod.collect observer (observeViewModel)
        // submits over this the moment the full list actually arrives; harmless no-op if vod is
        // already populated (first page is a subset, diffs to the same visible top rows).
        val initial = viewModel.vod.value.ifEmpty { viewModel.vodFirstPage.value }
        updateMoviesFsGenreChips(initial)
        submitFilteredMoviesFs(initial)
        binding.tvEtMoviesFsSearch.requestFocus()
    }

    private fun hideMoviesFullScreen() {
        binding.tvMoviesFullScreen.visibility = View.GONE
        binding.tvMainContent.visibility = View.VISIBLE
        binding.tvEtMoviesFsSearch.setText("")
        moviesFsSearchQuery = ""
        // Undo showMoviesFullScreen's pause() — Movies/Series are views inside this same
        // Activity, not separate Activities, so onResume() (which already handles this exact
        // "resume the mini player" job on a real app foreground) never fires just from hiding
        // this overlay; has to be done explicitly here instead.
        if (currentMiniUrl.isNotEmpty()) miniPlayer?.play()
        // Back from Movies lands on the bare sidebar now — no section auto-selected/no content
        // panel shown, just the nav list itself. Explicitly asked for over re-selecting whatever
        // was active before: picking a real section (e.g. tapping Favorites) still jumps to and
        // focuses the currently-playing channel exactly as it always has, this just means Back
        // itself doesn't force that jump or any other section's content to load first.
        showBareSidebar()
    }

    // categoryId -> genre bucket, built fresh from the current vodCategories snapshot each time
    // rather than cached — vodCategories is small (tens of rows) and can change (provider
    // refresh, USA/English-only toggle), so a stale cache would silently mis-bucket movies whose
    // category got renamed or removed since the last build.
    private fun moviesFsCategoryGenreMap(): Map<String, String?> {
        val cats = viewModel.vodCategories.value
        return cats.associate { cat ->
            val buckets = com.iptvapp.util.GenreBuckets.bucketsFor(listOf(cat.categoryName))
            cat.categoryId to buckets.firstOrNull()
        }
    }

    // Guards against two overlapping filter passes racing (e.g. a genre chip clicked mid-search-
    // debounce) — each call cancels whatever filter pass is still running before starting its
    // own, so only the most recent filter state ever reaches submitList.
    private var moviesFsFilterJob: kotlinx.coroutines.Job? = null

    private fun submitFilteredMoviesFs(list: List<com.iptvapp.data.local.entities.VodEntity>) {
        // Filtering/sorting a ~176k-row catalog (a large merged-provider account, observed
        // during testing) on the main thread was the actual cause of "picking a genre/typing a
        // search takes forever to render" — four sequential full-list passes (language/
        // favorites/genre/search) plus applyVodSort's partition+sort, all synchronous, blocking
        // the UI thread for every keystroke and every chip tap. Moved to Dispatchers.Default so
        // the heavy work happens off the main thread; only the final submitList (cheap) touches
        // UI. moviesFsFilterJob cancellation keeps a stale in-flight pass (from the previous
        // keystroke/chip) from clobbering a newer one once both finish.
        moviesFsFilterJob?.cancel()
        moviesFsFilterJob = lifecycleScope.launch {
            val genre = activeMoviesFsGenre
            val favoritesOnly = moviesFsFavoritesOnly
            val query = moviesFsSearchQuery
            val allowedCategoryIds = viewModel.vodCategories.value.map { it.categoryId }.toSet()
            val genreMap = if (genre != null) moviesFsCategoryGenreMap() else null
            val sorted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                // viewModel.vod (list here) is every movie across every provider/category
                // unfiltered — viewModel.vodCategories, by contrast, already respects the
                // English-only-movies Settings toggle (see HomeViewModel.isEnglishCategory/
                // loadAll's englishOnlyMovies combine). Movies whose categoryId isn't in that
                // already-filtered set are foreign-language categories (e.g. "FR - ...",
                // "GR - ...") that were slipping through here even with the toggle on, since
                // this grid reads from the flat vod list instead of category-by-category like
                // the old sidebar Movies section did. "NF" (Netflix, a source label, not a
                // language) is unaffected since it's not one of isEnglishCategory's language
                // tokens to begin with.
                val languageFiltered = if (allowedCategoryIds.isEmpty()) list
                    else list.filter { it.categoryId in allowedCategoryIds }
                // Favorites and genre are mutually exclusive (see moviesFsFavoritesOnly's kdoc)
                // — only one of these two filters is ever active at a time.
                val favoritesFiltered = if (favoritesOnly) languageFiltered.filter { it.isFavorite } else languageFiltered
                val genreFiltered = if (genre == null) favoritesFiltered
                    else favoritesFiltered.filter { genreMap?.get(it.categoryId) == genre }
                val searchFiltered = if (query.isBlank()) genreFiltered
                    else genreFiltered.filter { it.name.contains(query, ignoreCase = true) }
                viewModel.applyVodSort(searchFiltered)
            }
            tvVodPosterAdapter.submitList(sorted)
        }
    }

    private fun updateMoviesFsGenreChips(list: List<com.iptvapp.data.local.entities.VodEntity>) {
        val cats = viewModel.vodCategories.value
        val genres = com.iptvapp.util.GenreBuckets.presentBuckets(cats.map { listOf(it.categoryName) })
        if (activeMoviesFsGenre != null && genres.none { it.equals(activeMoviesFsGenre, ignoreCase = true) }) activeMoviesFsGenre = null
        // A click rebuilds every chip from scratch below (removeAllViews + fresh addView calls),
        // which destroys the View the user just clicked/focused — if nothing re-focuses the new
        // one afterward, Android's focus system falls back to the nearest other focusable (the
        // Back button, confirmed live), reading as "selecting a genre kicks focus away from the
        // chip row entirely." Remember whether the row itself had focus before the rebuild so it
        // can be restored after.
        val hadFocus = binding.tvGenreChipContainerFs.hasFocus()
        val container = binding.tvGenreChipContainerFs
        container.removeAllViews()
        var activeChip: View? = null
        val allChip = buildTvGenreChip("All", !moviesFsFavoritesOnly && activeMoviesFsGenre == null) {
            activeMoviesFsGenre = null
            moviesFsFavoritesOnly = false
            updateMoviesFsGenreChips(viewModel.vod.value)
            submitFilteredMoviesFs(viewModel.vod.value)
        }
        container.addView(allChip)
        if (!moviesFsFavoritesOnly && activeMoviesFsGenre == null) activeChip = allChip
        val favoritesChip = buildTvGenreChip("★ Favorites", moviesFsFavoritesOnly) {
            moviesFsFavoritesOnly = true
            activeMoviesFsGenre = null
            updateMoviesFsGenreChips(viewModel.vod.value)
            submitFilteredMoviesFs(viewModel.vod.value)
        }
        container.addView(favoritesChip)
        if (moviesFsFavoritesOnly) activeChip = favoritesChip
        for (genre in genres) {
            val selected = !moviesFsFavoritesOnly && activeMoviesFsGenre?.equals(genre, ignoreCase = true) == true
            val chip = buildTvGenreChip(genre, selected) {
                activeMoviesFsGenre = genre
                moviesFsFavoritesOnly = false
                updateMoviesFsGenreChips(viewModel.vod.value)
                submitFilteredMoviesFs(viewModel.vod.value)
            }
            container.addView(chip)
            if (selected) activeChip = chip
        }
        // XML's android:nextFocusDown can't target a dynamically-built child of
        // tvGenreChipContainerFs (only real, statically-declared view IDs work there) — wired
        // here instead, once the "All" chip (container.getChildAt(0)) actually exists. Without
        // this, Down from the search bar fell through to default focus search, which picked an
        // unrelated target (confirmed live: it jumped back up to the Back button) instead of
        // dropping into the chip row right below.
        allChip.id.let { firstChipId -> binding.tvEtMoviesFsSearch.nextFocusDownId = firstChipId }
        if (hadFocus) activeChip?.requestFocus()
    }

    // ── Series: full-screen Netflix-style browse (tvSeriesFullScreen) ─────────
    //
    // Exact mirror of the Movies full-screen block above — see its own kdoc for the full
    // rationale. One simplification: unlike Movies, viewModel.series is ALREADY English-only
    // filtered at the source (see HomeViewModel.loadAll's series-filtering combine, which
    // filters directly against getSeriesCategories() rather than needing a separate
    // categoryId cross-reference here), and genre bucketing works off each SeriesEntity's own
    // genre string field (comma-separated tags) instead of a category-name lookup — so there's
    // no seriesFsCategoryGenreMap equivalent to moviesFsCategoryGenreMap needed.
    private var activeSeriesFsGenre: String? = null
    private var seriesFsFavoritesOnly: Boolean = false
    private var seriesFsSearchQuery: String = ""
    private var seriesFsSearchDebounceJob: kotlinx.coroutines.Job? = null

    private fun setupSeriesFullScreen() {
        binding.tvBtnSeriesFsBack.setOnClickListener { hideSeriesFullScreen() }
        binding.tvBtnSeriesFsRefresh.setOnClickListener {
            binding.tvBtnSeriesFsRefresh.isEnabled = false
            binding.tvBtnSeriesFsRefresh.text = "…"
            viewModel.refreshSeries { message ->
                binding.tvBtnSeriesFsRefresh.isEnabled = true
                binding.tvBtnSeriesFsRefresh.text = "⟳"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        binding.tvBtnSeriesFsClearSearch.setOnClickListener {
            binding.tvEtSeriesFsSearch.setText("")
        }
        binding.tvBtnSeriesFsSearch.setOnClickListener { commitSeriesFsSearchNow() }
        binding.tvEtSeriesFsSearch.setOnEditorActionListener { _, _, _ -> commitSeriesFsSearchNow(); true }
        binding.tvEtSeriesFsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString()
                binding.tvBtnSeriesFsClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                if (q.length < 2) {
                    seriesFsSearchDebounceJob?.cancel()
                    seriesFsSearchQuery = ""
                    submitFilteredSeriesFs(viewModel.series.value)
                    return
                }
                seriesFsSearchDebounceJob?.cancel()
                seriesFsSearchDebounceJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(200)
                    seriesFsSearchQuery = q
                    submitFilteredSeriesFs(viewModel.series.value)
                }
            }
        })
    }

    private fun commitSeriesFsSearchNow() {
        seriesFsSearchDebounceJob?.cancel()
        seriesFsSearchQuery = binding.tvEtSeriesFsSearch.text.toString()
        submitFilteredSeriesFs(viewModel.series.value)
    }

    private fun showSeriesFullScreen() {
        binding.tvMainContent.visibility = View.GONE
        binding.tvSeriesFullScreen.visibility = View.VISIBLE
        // See showMoviesFullScreen's identical call for why.
        miniPlayer?.pause()
        val initial = viewModel.series.value
        updateSeriesFsGenreChips(initial)
        submitFilteredSeriesFs(initial)
        binding.tvEtSeriesFsSearch.requestFocus()
    }

    private fun hideSeriesFullScreen() {
        binding.tvSeriesFullScreen.visibility = View.GONE
        binding.tvMainContent.visibility = View.VISIBLE
        binding.tvEtSeriesFsSearch.setText("")
        seriesFsSearchQuery = ""
        // See hideMoviesFullScreen's identical logic for both of these.
        if (currentMiniUrl.isNotEmpty()) miniPlayer?.play()
        showBareSidebar()
    }

    private var seriesFsFilterJob: kotlinx.coroutines.Job? = null

    private fun submitFilteredSeriesFs(list: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        seriesFsFilterJob?.cancel()
        seriesFsFilterJob = lifecycleScope.launch {
            val genre = activeSeriesFsGenre
            val favoritesOnly = seriesFsFavoritesOnly
            val query = seriesFsSearchQuery
            val sorted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val favoritesFiltered = if (favoritesOnly) list.filter { it.isFavorite } else list
                val genreFiltered = if (genre == null) favoritesFiltered
                    else favoritesFiltered.filter { genre in com.iptvapp.util.GenreBuckets.bucketsFor(it.genre?.split(",").orEmpty()) }
                val searchFiltered = if (query.isBlank()) genreFiltered
                    else genreFiltered.filter { it.name.contains(query, ignoreCase = true) }
                viewModel.applySeriesSort(searchFiltered)
            }
            tvSeriesPosterAdapter.submitList(sorted)
        }
    }

    private fun updateSeriesFsGenreChips(list: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        val genres = com.iptvapp.util.GenreBuckets.presentBuckets(list.map { it.genre?.split(",").orEmpty() })
        if (activeSeriesFsGenre != null && genres.none { it.equals(activeSeriesFsGenre, ignoreCase = true) }) activeSeriesFsGenre = null
        val hadFocus = binding.tvGenreChipContainerFsSeries.hasFocus()
        val container = binding.tvGenreChipContainerFsSeries
        container.removeAllViews()
        var activeChip: View? = null
        val allChip = buildTvGenreChip("All", !seriesFsFavoritesOnly && activeSeriesFsGenre == null) {
            activeSeriesFsGenre = null
            seriesFsFavoritesOnly = false
            updateSeriesFsGenreChips(viewModel.series.value)
            submitFilteredSeriesFs(viewModel.series.value)
        }
        container.addView(allChip)
        if (!seriesFsFavoritesOnly && activeSeriesFsGenre == null) activeChip = allChip
        val favoritesChip = buildTvGenreChip("★ Favorites", seriesFsFavoritesOnly) {
            seriesFsFavoritesOnly = true
            activeSeriesFsGenre = null
            updateSeriesFsGenreChips(viewModel.series.value)
            submitFilteredSeriesFs(viewModel.series.value)
        }
        container.addView(favoritesChip)
        if (seriesFsFavoritesOnly) activeChip = favoritesChip
        for (genre in genres) {
            val selected = !seriesFsFavoritesOnly && activeSeriesFsGenre?.equals(genre, ignoreCase = true) == true
            val chip = buildTvGenreChip(genre, selected) {
                activeSeriesFsGenre = genre
                seriesFsFavoritesOnly = false
                updateSeriesFsGenreChips(viewModel.series.value)
                submitFilteredSeriesFs(viewModel.series.value)
            }
            container.addView(chip)
            if (selected) activeChip = chip
        }
        allChip.id.let { firstChipId -> binding.tvEtSeriesFsSearch.nextFocusDownId = firstChipId }
        if (hadFocus) activeChip?.requestFocus()
    }

    private fun showSeries() {
        setContentAdapter(seriesAdapter, isGrid = false)
        updateTvSeriesGenreChips(viewModel.series.value)
        submitFilteredTvSeries(viewModel.series.value)
    }

    private fun submitFilteredTvSeries(list: List<com.iptvapp.data.local.entities.SeriesEntity>) {
        val genre = activeTvSeriesGenre
        val filtered = if (genre == null) list
            else list.filter { genre in com.iptvapp.util.GenreBuckets.bucketsFor(it.genre?.split(",").orEmpty()) }
        seriesAdapter.submitPlainList(viewModel.applySeriesSort(filtered))
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
            // Generated IDs let code elsewhere (see updateMoviesFsGenreChips's nextFocusDownId
            // wiring) target a specific dynamically-built chip — these buttons have no static
            // XML id to reference otherwise.
            id = View.generateViewId()
            text = label
            textSize = 12f
            isAllCaps = false
            // isSelected drives the drawable's state_selected branch — the same persistent
            // left-accent-bar + tint treatment the sidebar's active section uses
            // (TvHomeActivity.selectSection), so a chosen genre reads as clearly "on" even once
            // D-pad focus moves elsewhere, not just a slightly brighter text color. Built via
            // TvAccentHelper (not the static tv_sidebar_focus drawable resource) so the accent
            // bar/tint actually matches the user's chosen accent color from Settings, same as
            // every sidebar button already does — sharing the static resource would leave every
            // genre chip hardcoded blue regardless of theme.
            isSelected = selected
            setTextColor(if (selected) currentAccent else 0xFF888888.toInt())
            background = com.iptvapp.util.TvAccentHelper.buildFocusDrawable(this@TvHomeActivity, currentAccent)
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
                    setContentAdapter(mergedChannelAdapter, isGrid = false)
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
            viewModel.providersDownCount.collect { downCount -> updateProvidersHealthBadge(downCount) }
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
                updateSidebarLabel(binding.btnTvCategories, "CATEGORIES", favs.size)
            }
        }
        // Sidebar item counts (Favorites/Categories) — independent of currentSection so the
        // count stays current even while looking at a different section, unlike the two
        // collectors above whose submitList()/adapter work is gated on being the active section.
        lifecycleScope.launch {
            viewModel.combinedFavorites.collect { favs ->
                updateSidebarLabel(binding.btnTvFavorites, "FAVORITES", favs.size)
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
                updateFavoriteGenreChips(favoritesRaw)
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
                // Movies is the full-screen browse grid now (tvMoviesFullScreen, see
                // showMoviesFullScreen) — re-filter+resubmit on every emission the same way
                // submitFilteredMoviesFs already does for genre/search changes, so a background
                // catalog refresh or a favorite/watch-progress change updates the grid live.
                if (currentSection == Section.MOVIES) submitFilteredMoviesFs(it)
            }
        }
        lifecycleScope.launch {
            viewModel.vodCategories.collect {
                if (currentSection == Section.MOVIES) updateMoviesFsGenreChips(viewModel.vod.value)
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                // Series is the full-screen browse grid now (tvSeriesFullScreen, see
                // showSeriesFullScreen) — the old sidebar-list branch below is unreachable via
                // selectSection anymore (Section.SERIES always routes to showSeriesFullScreen)
                // but left in place since nothing else references currentSection == SERIES in a
                // way that would make it actively wrong to keep.
                //
                // updateSeriesFsGenreChips was previously only ever called from
                // showSeriesFullScreen's own initial call and from inside the chip click
                // handlers — if viewModel.series was still empty at that exact moment (cold-boot
                // load race, same shape as Movies' vod/vodCategories race), presentBuckets on an
                // empty list found no genres and the chip row was left permanently stuck on just
                // All/Favorites, with nothing ever rebuilding it once the real data arrived
                // (confirmed live: only All and Favorites showed, no Comedy/Drama/etc.). Movies
                // has an equivalent vodCategories.collect{} observer for exactly this reason —
                // Series never got one.
                if (currentSection == Section.SERIES) {
                    updateSeriesFsGenreChips(it)
                    submitFilteredSeriesFs(it)
                }
            }
        }
        // combinedFavoriteAdapter's EPG/health maps are string-keyed ("primary:<id>" or
        // "<serverIndex>:<id>") to cover both sources at once — each collector below only owns
        // its half of the key space, so re-derive the union from both StateFlows' latest values
        // rather than trying to patch just the changed half in isolation.
        fun republishCombinedEpgText() {
            val merged = viewModel.channelEpgText.value.mapKeys { (id, _) -> "primary:$id" } + viewModel.mergedEpgText.value
            combinedFavoriteAdapter.submitEpgText(merged)
        }
        fun republishCombinedEpgNextText() {
            combinedFavoriteAdapter.submitEpgNextText(viewModel.channelEpgNextText.value.mapKeys { (id, _) -> "primary:$id" })
        }
        fun republishCombinedHealth() {
            val merged = viewModel.channelHealth.value.mapKeys { (id, _) -> "primary:$id" } + viewModel.mergedHealth.value
            combinedFavoriteAdapter.submitHealth(merged)
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
            viewModel.syncProgress.collect { progress ->
                if (currentSection != Section.PROVIDERS || progress == null) {
                    binding.tvProvidersSyncProgressContainer.visibility = View.GONE
                    return@collect
                }
                binding.tvProvidersSyncProgressContainer.visibility = View.VISIBLE
                binding.tvProvidersSyncStatus.text = progress.first
                binding.tvProvidersSyncProgressBar.progress = progress.second
            }
        }
        lifecycleScope.launch {
            viewModel.mergedChannels.collect {
                if (currentSection == Section.PROVIDERS && providersMode == ProvidersMode.CHANNELS) {
                    // Previously a plain submitList with no focus preservation at all — favoriting
                    // a channel (or any other re-emission of this Flow, e.g. the periodic health
                    // check) re-diffs the list and RecyclerView doesn't keep D-pad focus pinned
                    // to the same row across that swap on its own, so the cursor visibly jumped
                    // (usually to wherever default focus search happened to land) on every tap.
                    // Same capture-before/restore-after pattern the primary channel/favorites
                    // collectors above already use.
                    val focusedChild = binding.tvRvContent.focusedChild
                    val focusedPos = if (focusedChild != null)
                        binding.tvRvContent.getChildAdapterPosition(focusedChild) else -1
                    mergedChannelAdapter.submitList(it) {
                        if (focusedPos >= 0) focusAdapterPositionRetrying(binding.tvRvContent, focusedPos)
                    }
                    viewModel.loadEpgForMergedChannels(it)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedVod.collect {
                if (currentSection == Section.PROVIDERS && providersMode == ProvidersMode.MOVIES) {
                    val focusedChild = binding.tvRvContent.focusedChild
                    val focusedPos = if (focusedChild != null)
                        binding.tvRvContent.getChildAdapterPosition(focusedChild) else -1
                    mergedVodAdapter.submitPlainList(it) {
                        if (focusedPos >= 0) focusAdapterPositionRetrying(binding.tvRvContent, focusedPos)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedSeries.collect {
                if (currentSection == Section.PROVIDERS && providersMode == ProvidersMode.SERIES) {
                    val focusedChild = binding.tvRvContent.focusedChild
                    val focusedPos = if (focusedChild != null)
                        binding.tvRvContent.getChildAdapterPosition(focusedChild) else -1
                    mergedSeriesAdapter.submitPlainList(it) {
                        if (focusedPos >= 0) focusAdapterPositionRetrying(binding.tvRvContent, focusedPos)
                    }
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

    private fun observeEpgGuide() {
        // guideRows already merges primary + favorited merged-provider channels (see
        // HomeViewModel.loadGuide) and is pre-filtered to rows that actually have programs —
        // previously this only ever observed viewModel.channels (primary-only), so a favorited
        // merged/secondary-provider channel's guide data never showed on TV at all, unlike the
        // phone's full-screen Guide which already used this same source.
        lifecycleScope.launch {
            viewModel.guideRows.collect { rows -> epgGuideAdapter.submitList(rows) }
        }
        viewModel.loadGuide()
        // NOW/progress in TvEpgGuideAdapter is computed from wall-clock time against each row's
        // static program list at bind time — nothing re-triggers a rebind as time passes on its
        // own, so without this the progress bar/NOW text would freeze at whatever it showed when
        // guideRows last emitted. A plain per-minute full rebind is cheap (rows only number in
        // the dozens) and matches the ~30-60min granularity of real program blocks.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                epgGuideAdapter.notifyItemRangeChanged(0, epgGuideAdapter.itemCount)
            }
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
    private fun refreshCurrentProvidersMode() {
        when (providersMode) {
            ProvidersMode.CHANNELS -> {
                viewModel.refreshMergedChannels()
                Toast.makeText(this, "Refreshing all providers…", Toast.LENGTH_SHORT).show()
            }
            ProvidersMode.MOVIES -> {
                viewModel.refreshMergedVod()
                Toast.makeText(this, "Refreshing all providers' movies…", Toast.LENGTH_SHORT).show()
            }
            ProvidersMode.SERIES -> {
                viewModel.refreshMergedSeries()
                Toast.makeText(this, "Refreshing all providers' series…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isProvidersModeRowFocused(): Boolean =
        binding.tvBtnProvidersModeChannels.hasFocus() ||
        binding.tvBtnProvidersModeMovies.hasFocus() ||
        binding.tvBtnProvidersModeSeries.hasFocus() ||
        binding.tvBtnProvidersModeRefresh.hasFocus()

    private fun isTvBulkSelectBarFocused(): Boolean =
        binding.tvBtnBulkSelectAll.hasFocus() ||
        binding.tvBtnBulkSelectDone.hasFocus() ||
        binding.tvBtnBulkSelectHide.hasFocus() ||
        binding.tvBtnBulkSelectCancel.hasFocus()

    // Shared by both the Left key (pressed from within a category/channel list) and the Back key
    // — drills up one level: a channel list returns to the sidebar if it came from the Guide
    // panel, or to the category list it was drilled from (Providers' 3-level server->category->
    // channels drill, or Favorites/Movies/Series' 2-level equivalents), otherwise to the sidebar;
    // a category list (top of a drill, e.g. the Providers server picker) always returns to the
    // sidebar.
    private fun goBackOneLevel() {
        when (navState) {
            NavState.CHANNELS -> {
                if (binding.tvGuidePanel.visibility == View.VISIBLE) {
                    showSidebar()
                } else if (navHasCategoryStep) {
                    showCategoryPanel(binding.tvCatTitle.text.toString())
                } else {
                    showSidebar()
                }
            }
            // Providers > Channels is a genuine 3-level drill (provider picker -> that provider's
            // categories -> channels), but both the provider picker AND a specific provider's
            // category list render through the same showCategoryPanel("PROVIDERS")/CATEGORIES
            // state — the only signal telling them apart is whether a server is still selected.
            // Without this check, going back from a provider's own category list skipped straight
            // past the provider picker to the sidebar, silently collapsing what should be 3 steps
            // into 2. Movies/Series' own 3-level drills have the identical shape.
            NavState.CATEGORIES -> {
                if (currentSection != Section.PROVIDERS) {
                    showSidebar()
                } else when (providersMode) {
                    ProvidersMode.CHANNELS -> if (viewModel.selectedMergedServerIndex != null) showMergedChannelsPanelFromTop() else showSidebar()
                    ProvidersMode.MOVIES -> if (viewModel.selectedMergedVodServerIndex != null) showMergedVodPanel() else showSidebar()
                    ProvidersMode.SERIES -> if (viewModel.selectedMergedSeriesServerIndex != null) showMergedSeriesPanel() else showSidebar()
                }
            }
            NavState.SIDEBAR -> {}
        }
    }

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
        // Custom numbering only offered for US channels — see ChannelEntity.customNum's kdoc for
        // why (this provider's US block is numbered in the tens of thousands, out of reach of
        // the 5-digit remote entry and awkward to keep straight by number at all).
        val isUs = viewModel.isUsCategory(
            viewModel.liveCategories.value.find { it.categoryId == channel.categoryId }?.categoryName
        )
        val options = mutableListOf(
            "Set Reminder",
            if (bulkSelectedIds.contains(channel.streamId)) "Deselect (bulk)" else "Select (bulk add to favorites)",
            "Hide Channel",
            "Channels Like This"
        )
        if (isUs) {
            options.add(if (channel.customNum != null) "Change Channel Number (${channel.customNum})" else "Set Channel Number")
        }
        if (bulkSelectMode && bulkSelectedIds.isNotEmpty()) {
            options.add(0, "✓ Hide ${bulkSelectedIds.size} selected")
            options.add(0, "✓ Add ${bulkSelectedIds.size} selected to favorites")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(options.toTypedArray()) { _, i ->
                when {
                    options[i] == "Set Reminder" -> showTvReminderDialog(channel)
                    options[i].startsWith("Set Channel Number") || options[i].startsWith("Change Channel Number") ->
                        showTvSetChannelNumberDialog(channel)
                    options[i] == "Select (bulk add to favorites)" -> {
                        bulkSelectMode = true
                        bulkSelectedIds.add(channel.streamId)
                        channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                        Toast.makeText(this, "${bulkSelectedIds.size} selected — select more, or wait to add them", Toast.LENGTH_SHORT).show()
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 3000)
                        updateTvBulkSelectUi()
                    }
                    options[i] == "Deselect (bulk)" -> {
                        bulkSelectedIds.remove(channel.streamId)
                        channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                        if (bulkSelectedIds.isEmpty()) {
                            bulkSelectMode = false
                            bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        }
                        updateTvBulkSelectUi()
                    }
                    options[i] == "Hide Channel" -> {
                        viewModel.hideChannel(channel.streamId)
                        Toast.makeText(this, "${channel.name} hidden. Unhide in Settings → Display.", Toast.LENGTH_SHORT).show()
                    }
                    options[i] == "Channels Like This" -> showTvSimilarChannelsSheet(channel)
                    else -> if (options[i].startsWith("✓ Add")) {
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                        Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                        clearTvBulkSelection()
                    } else if (options[i].startsWith("✓ Hide")) {
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        val count = bulkSelectedIds.size
                        viewModel.bulkHideChannels(bulkSelectedIds.toList())
                        Toast.makeText(this, "$count channels hidden", Toast.LENGTH_SHORT).show()
                        clearTvBulkSelection()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // US channels only (see ChannelEntity.customNum's kdoc) — lets a channel be given its own
    // number, overriding the provider's raw num for sorting and for the remote's number-jump.
    private fun showTvSetChannelNumberDialog(channel: ChannelEntity) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(channel.customNum?.toString() ?: "")
            hint = "Channel number"
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Channel Number — ${channel.name}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val num = input.text.toString().toIntOrNull()
                viewModel.setCustomChannelNumber(channel.streamId, num)
                Toast.makeText(
                    this,
                    if (num != null) "${channel.name} set to channel $num" else "Number cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Clear") { _, _ ->
                viewModel.setCustomChannelNumber(channel.streamId, null)
                Toast.makeText(this, "Number cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        input.requestFocus()
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

    // Reminders/recording scheduling only exist for primary-provider channels today
    // (ChannelTimerScheduler/RecordingSchedulerActivity have no serverIndex concept, same
    // reasoning as the phone Guide's GuideAdapter.showTimerDialog) — a merged/secondary-provider
    // row just gets the Remind Me option via row.programs directly (no getUpcomingEpg() call,
    // since that DAO query is primary-channels-only; GuideRow already carries the programs).
    private fun showTvReminderDialogForRow(row: com.iptvapp.ui.guide.GuideRow) {
        val primaryChannel = row.channel
        if (primaryChannel != null) {
            showTvReminderDialog(primaryChannel)
            return
        }
        val nowMs = System.currentTimeMillis()
        fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
        val upcoming = row.programs.filter { toMs(it.startTimestamp) > nowMs }.sortedBy { it.startTimestamp }
        if (upcoming.isEmpty()) {
            Toast.makeText(this, "No upcoming guide data for ${row.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val labels = upcoming.map { epg ->
            val startMs = toMs(epg.startTimestamp)
            val minUntil = ((startMs - nowMs) / 60000).coerceAtLeast(0)
            val timeStr = if (minUntil == 0L) "Now" else "in ${minUntil}min"
            "${epg.title} (${fmt.format(Date(startMs))} — $timeStr)"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remind me — ${row.name}")
            .setItems(labels) { _, i ->
                val epg = upcoming[i]
                val startMs = toMs(epg.startTimestamp)
                ChannelTimerScheduler.schedule(this, row.streamId, row.name, epg.title, startMs)
                Toast.makeText(this, "Reminder set for ${fmt.format(Date(startMs))}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
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
