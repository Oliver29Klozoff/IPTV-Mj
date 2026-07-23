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
import android.view.ViewGroup
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
    // Not private: FeatureTourDialog/SpotlightTourController reads real tab/button views from
    // this binding to point the spotlight tour at actual on-screen UI.
    lateinit var binding: ActivityHomeBinding

    // Kept in sync by a dedicated collector below — mergedCategoriesToSynthetic() reads this
    // synchronously (it's called from inside onCategoryClick's own collector, not a suspend
    // context) to sort favorited merged categories to the top, same as Live categories.
    private var mergedFavoriteCategoryKeys: Set<String> = emptySet()
    // Hidden categories in Providers > Movies/Series — kept in sync by a collector the same
    // way mergedFavoriteCategoryKeys is, for the two modes independently.
    private var hiddenMergedVodCategoryKeys: Set<String> = emptySet()
    private var hiddenMergedSeriesCategoryKeys: Set<String> = emptySet()
    // Pure display state (which mode is currently revealing its hidden categories) — not in
    // HomeViewModel since, unlike isViewingMergedVodFavorites, this changes zero data-fetching,
    // only which already-fetched rows get rendered.
    private var showingHiddenVodCategories = false
    private var showingHiddenSeriesCategories = false

    // ─── Bulk-select state ───────────────────────────────────────────────────
    private val bulkSelectedIds = mutableSetOf<Int>()
    private var bulkSelectMode = false
    // Category bulk-hide (Providers > Movies/Series) — same checkbox-per-row shape as channel
    // bulk-select above, scoped "$serverIndex:$categoryId" keys instead of streamIds. Same 8s
    // idle popup as channels too (Hide Selected / Unselect All), not an auto-commit — hiding
    // removes categories from the list entirely, so nothing happens without an explicit choice.
    private val bulkSelectedCategoryIds = mutableSetOf<String>()
    private var categoryBulkSelectMode = false
    private var categoryBulkSelectIsSeries = false
    private val bulkSelectCategoryIdleRunnable = Runnable {
        if (categoryBulkSelectMode && bulkSelectedCategoryIds.isNotEmpty()) {
            val isSeries = categoryBulkSelectIsSeries
            showBulkSelectIdlePrompt(
                count = bulkSelectedCategoryIds.size,
                onMoveToFavorites = {
                    val keys = bulkSelectedCategoryIds.toSet()
                    if (isSeries) viewModel.bulkHideMergedSeriesCategories(keys)
                    else viewModel.bulkHideMergedVodCategories(keys)
                    Toast.makeText(this, "${keys.size} categories hidden", Toast.LENGTH_SHORT).show()
                    clearBulkSelectionCategories(isSeries)
                },
                onUnselectAll = { clearBulkSelectionCategories(isSeries) },
                itemLabel = "category",
                actionLabel = "Hide Selected"
            )
        }
    }

    private fun clearBulkSelectionCategories(isSeries: Boolean) {
        bulkSelectedCategoryIds.clear()
        categoryBulkSelectMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectCategoryIdleRunnable)
        categoryAdapter.submitBulkSelection(emptySet())
        if (isSeries) {
            categoryAdapter.submitList(mergedSeriesCategoriesToSynthetic(viewModel.mergedSeriesCategories.value))
        } else {
            categoryAdapter.submitList(mergedVodCategoriesToSynthetic(viewModel.mergedVodCategories.value))
        }
    }
    // Once bulk-select is on (long-press one channel to start), a plain tap on any other
    // channel just adds/removes it from the selection instead of playing it — no more
    // long-pressing every single one. 8s of no further taps prompts with a Snackbar
    // (Move to Favorites / Unselect All) rather than silently auto-committing — the user
    // wants to review the selection before it's acted on, not have it happen automatically.
    private val bulkSelectHandler = Handler(Looper.getMainLooper())
    private val bulkSelectIdleRunnable = Runnable {
        if (bulkSelectMode && bulkSelectedIds.isNotEmpty()) {
            showBulkSelectIdlePrompt(
                count = bulkSelectedIds.size,
                onMoveToFavorites = {
                    viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                    Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                    clearBulkSelection()
                },
                onUnselectAll = { clearBulkSelection() }
            )
        }
    }
    // Merged-channel equivalent of the bulk-select state above — same shape, "$serverIndex:
    // $streamId" keys instead of bare streamIds (see MergedChannelAdapter.keyOf).
    private val bulkSelectedMergedKeys = mutableSetOf<String>()
    private var bulkSelectMergedMode = false
    private val bulkSelectMergedIdleRunnable = Runnable {
        if (bulkSelectMergedMode && bulkSelectedMergedKeys.isNotEmpty()) {
            showBulkSelectIdlePrompt(
                count = bulkSelectedMergedKeys.size,
                onMoveToFavorites = {
                    viewModel.bulkAddMergedFavorites(bulkSelectedMergedKeys.toSet())
                    Toast.makeText(this, "Added ${bulkSelectedMergedKeys.size} channels to favorites", Toast.LENGTH_SHORT).show()
                    clearBulkSelectionMerged()
                },
                onUnselectAll = { clearBulkSelectionMerged() }
            )
        }
    }

    private fun clearBulkSelectionMerged() {
        bulkSelectedMergedKeys.clear()
        bulkSelectMergedMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
        mergedChannelAdapter.submitBulkSelection(emptySet())
    }

    // Live tab bulk-select (LiveChannelAdapter combines primary + every merged provider into
    // one list) — keyed by LiveChannelRow.id ("primary:$streamId" or "$serverIndex:$streamId"),
    // dispatched to the right favorite call per row based on which one is actually selected.
    private val bulkSelectedLiveIds = mutableSetOf<String>()
    private var bulkSelectLiveMode = false
    private val bulkSelectLiveIdleRunnable = Runnable {
        if (bulkSelectLiveMode && bulkSelectedLiveIds.isNotEmpty()) {
            showBulkSelectIdlePrompt(
                count = bulkSelectedLiveIds.size,
                onMoveToFavorites = { commitBulkLiveFavorites() },
                onUnselectAll = { clearBulkSelectionLive() }
            )
        }
    }

    // Shown 8s after the last selection tap in any bulk-select mode (primary/merged/unified
    // Live) — two explicit buttons rather than an auto-commit or a swipe-to-dismiss-means-
    // something gesture, so nothing happens to the selection until the user actually picks one.
    // Tapping outside just dismisses the dialog without touching the selection, same as
    // showMoveToFolderDialog's existing "cancel-by-tap-outside keeps selection" precedent —
    // the idle timer simply re-fires after another 8s of inactivity.
    private fun showBulkSelectIdlePrompt(
        count: Int, onMoveToFavorites: () -> Unit, onUnselectAll: () -> Unit,
        itemLabel: String = "channel", actionLabel: String = "Move to Favorites"
    ) {
        AlertDialog.Builder(this)
            .setTitle("$count $itemLabel${if (count == 1) "" else "s"} selected")
            .setPositiveButton(actionLabel) { _, _ -> onMoveToFavorites() }
            .setNegativeButton("Unselect All") { _, _ -> onUnselectAll() }
            .setNeutralButton("Keep Selecting", null)
            .show()
    }

    private fun commitBulkLiveFavorites() {
        val primaryIds = bulkSelectedLiveIds
            .filter { it.startsWith("primary:") }
            .mapNotNull { it.substringAfter("primary:").toIntOrNull() }
        val mergedKeys = bulkSelectedLiveIds.filterNot { it.startsWith("primary:") }.toSet()
        if (primaryIds.isNotEmpty()) viewModel.bulkAddFavorites(primaryIds)
        if (mergedKeys.isNotEmpty()) viewModel.bulkAddMergedFavorites(mergedKeys)
        Toast.makeText(this, "Added ${bulkSelectedLiveIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
        clearBulkSelectionLive()
    }

    private fun clearBulkSelectionLive() {
        bulkSelectedLiveIds.clear()
        bulkSelectLiveMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectLiveIdleRunnable)
        liveChannelAdapter.submitBulkSelection(emptySet())
    }

    // Series bulk-hide (checkbox mode) — primary and merged tracked separately since they're
    // different adapters/entities, but share the same 8s-idle-popup shape as channel bulk-hide.
    private val bulkSelectedSeriesIds = mutableSetOf<Int>()
    private var bulkSelectSeriesMode = false
    private val bulkSelectSeriesIdleRunnable = Runnable {
        if (bulkSelectSeriesMode && bulkSelectedSeriesIds.isNotEmpty()) {
            showBulkSelectIdlePrompt(
                count = bulkSelectedSeriesIds.size,
                onMoveToFavorites = {
                    viewModel.bulkHideSeries(bulkSelectedSeriesIds.toList())
                    Toast.makeText(this, "${bulkSelectedSeriesIds.size} shows hidden", Toast.LENGTH_SHORT).show()
                    clearBulkSelectionSeries()
                },
                onUnselectAll = { clearBulkSelectionSeries() },
                itemLabel = "show",
                actionLabel = "Hide Selected"
            )
        }
    }

    private fun clearBulkSelectionSeries() {
        bulkSelectedSeriesIds.clear()
        bulkSelectSeriesMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectSeriesIdleRunnable)
        seriesAdapter.submitBulkSelection(emptySet())
    }

    private val bulkSelectedMergedSeriesKeys = mutableSetOf<String>()
    private var bulkSelectMergedSeriesMode = false
    private val bulkSelectMergedSeriesIdleRunnable = Runnable {
        if (bulkSelectMergedSeriesMode && bulkSelectedMergedSeriesKeys.isNotEmpty()) {
            showBulkSelectIdlePrompt(
                count = bulkSelectedMergedSeriesKeys.size,
                onMoveToFavorites = {
                    val items = bulkSelectedMergedSeriesKeys.mapNotNull { key ->
                        val (serverIndex, seriesId) = key.split(":", limit = 2)
                        viewModel.mergedSeries.value.firstOrNull { it.serverIndex == serverIndex.toInt() && it.seriesId == seriesId.toInt() }
                    }
                    viewModel.bulkHideMergedSeries(items)
                    Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} shows hidden", Toast.LENGTH_SHORT).show()
                    clearBulkSelectionMergedSeries()
                },
                onUnselectAll = { clearBulkSelectionMergedSeries() },
                itemLabel = "show",
                actionLabel = "Hide Selected"
            )
        }
    }

    private fun clearBulkSelectionMergedSeries() {
        bulkSelectedMergedSeriesKeys.clear()
        bulkSelectMergedSeriesMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectMergedSeriesIdleRunnable)
        mergedSeriesAdapter.submitBulkSelection(emptySet())
    }

    // Merged VOD bulk-hide — same shape as merged Series' above.
    private val bulkSelectedMergedVodKeys = mutableSetOf<String>()
    private var bulkSelectMergedVodMode = false
    private val bulkSelectMergedVodIdleRunnable = Runnable {
        if (bulkSelectMergedVodMode && bulkSelectedMergedVodKeys.isNotEmpty()) {
            showBulkSelectIdlePrompt(
                count = bulkSelectedMergedVodKeys.size,
                onMoveToFavorites = {
                    val items = bulkSelectedMergedVodKeys.mapNotNull { key ->
                        val (serverIndex, streamId) = key.split(":", limit = 2)
                        viewModel.mergedVod.value.firstOrNull { it.serverIndex == serverIndex.toInt() && it.streamId == streamId.toInt() }
                    }
                    viewModel.bulkHideMergedVod(items)
                    Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} movies hidden", Toast.LENGTH_SHORT).show()
                    clearBulkSelectionMergedVod()
                },
                onUnselectAll = { clearBulkSelectionMergedVod() },
                itemLabel = "movie",
                actionLabel = "Hide Selected"
            )
        }
    }

    private fun clearBulkSelectionMergedVod() {
        bulkSelectedMergedVodKeys.clear()
        bulkSelectMergedVodMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectMergedVodIdleRunnable)
        mergedVodAdapter.submitBulkSelection(emptySet())
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
        // A merged-provider channel's fullscreen exit used to always land on Favorites, same as
        // a primary channel — PlayerActivity never returned server_index/merged_stream_id at
        // all, so there was no way to tell the two apart here. Now that it does, route back to
        // wherever the channel actually lives: Providers tab (jumping straight to its
        // folder/category, same "go to where I'm watching" behavior Favorites already gets) for
        // a merged channel, Favorites for a primary one.
        val returnedServerIndex = result.data?.getIntExtra("server_index", -1) ?: -1
        val returnedMergedStreamId = result.data?.getIntExtra("merged_stream_id", -1) ?: -1
        if (result.resultCode == Activity.RESULT_OK && returnedServerIndex != -1 && returnedMergedStreamId != -1) {
            suppressMiniAutoResume = true
            currentMiniServerIndex = returnedServerIndex
            currentMiniMergedStreamId = returnedMergedStreamId
            currentMiniStreamId = -1
            currentMiniIsVod = false
            // Selecting the tab fires onTabSelected -> showAllProviders() synchronously, which
            // reads currentMiniServerIndex/currentMiniMergedStreamId (already set above) and
            // does the "jump straight to this channel's folder" itself — no need to duplicate
            // that logic here, just make sure it's treated as a fresh visit to the tab.
            // TabLayout.select() is a no-op (doesn't fire onTabSelected) if that tab was already
            // selected before opening fullscreen — call showAllProviders() directly in that case.
            providersTabVisitedSinceTabSwitch = false
            if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS) {
                showAllProviders()
            } else {
                binding.tabLayout.getTabAt(TAB_PROVIDERS)?.select()
            }
            return@registerForActivityResult
        }

        // Primary-provider channel (or nothing returned) — always return to Favorites, same as
        // before.
        binding.tabLayout.getTabAt(TAB_FAVORITES)?.select()
        showFavorites()
        if (result.resultCode == Activity.RESULT_OK) {
            val returnedId  = result.data?.getIntExtra("stream_id", -1) ?: -1
            val returnedUrl = result.data?.getStringExtra("stream_url") ?: ""
            val returnedTitle = result.data?.getStringExtra("stream_title") ?: ""
            if (returnedId != -1 && returnedUrl.isNotEmpty()) {
                suppressMiniAutoResume = true
                currentMiniStreamId = returnedId
                currentMiniServerIndex = -1
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
    // True once the Providers tab has jumped-to/been browsed since the last time a DIFFERENT
    // tab was selected — the "jump straight to the playing channel" behavior only applies once
    // per visit to this tab; every tap after that steps back one level instead of re-jumping.
    private var providersTabVisitedSinceTabSwitch = false
    // Providers tab now has three independent browse modes — Live (the original behavior),
    // Movies (merged VOD, see MergedVodEntity), and Series (merged series, see
    // MergedSeriesEntity) — picked directly via three side-by-side buttons (providersModeRow).
    // Resets to LIVE whenever a different tab is selected, matching
    // providersTabVisitedSinceTabSwitch's own "fresh visit" reset just above.
    private enum class ProvidersMode { LIVE, MOVIES, SERIES }

    private fun setProvidersModeButtonHighlight() {
        val active = "#008CFF"; val inactive = "#888888"
        binding.btnProvidersModeLive?.setTextColor(android.graphics.Color.parseColor(if (providersMode == ProvidersMode.LIVE) active else inactive))
        binding.btnProvidersModeMovies?.setTextColor(android.graphics.Color.parseColor(if (providersMode == ProvidersMode.MOVIES) active else inactive))
        binding.btnProvidersModeSeries?.setTextColor(android.graphics.Color.parseColor(if (providersMode == ProvidersMode.SERIES) active else inactive))
    }
    private var providersMode = ProvidersMode.LIVE
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
            val serverIndex = data.getIntExtra("server_index", -1)
            val mergedStreamId = data.getIntExtra("merged_stream_id", -1)
            // Merged/secondary-provider rows send stream_id=-1 alongside a real
            // server_index/merged_stream_id pair (see EpgTimelineActivity.playChannel) — only
            // bail if NEITHER identity is present at all.
            if (streamId == -1 && (serverIndex == -1 || mergedStreamId == -1)) return@registerForActivityResult
            // Set synchronously — onResume fires after this callback and reads this flag
            suppressMiniAutoResume = true
            if (streamId == -1) {
                lifecycleScope.launch {
                    val channel = viewModel.getMergedChannelByIndexAndId(serverIndex, mergedStreamId) ?: return@launch
                    playMergedChannel(channel)
                }
                return@registerForActivityResult
            }
            val timeshiftUrl = data.getStringExtra("timeshift_url")
            val timeshiftTitle = data.getStringExtra("timeshift_title")
            lifecycleScope.launch {
                val channel = viewModel.getChannelById(streamId) ?: return@launch
                if (timeshiftUrl != null && timeshiftTitle != null) {
                    currentMiniUrl = timeshiftUrl
                    currentMiniTitle = timeshiftTitle
                    currentMiniStreamId = streamId
                    currentMiniServerIndex = -1
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
    private lateinit var mergedVodAdapter: MergedVodAdapter
    private lateinit var mergedSeriesAdapter: MergedSeriesAdapter
    private lateinit var combinedFavoriteAdapter: CombinedFavoriteAdapter
    // Live tab now merges the primary provider with every configured secondary provider, same
    // shape as the Favorites tab's combinedFavoriteAdapter. categoryAdapter/channelAdapter stay
    // in use elsewhere (Categories/Movies reuse categoryAdapter; channelAdapter still backs the
    // Providers tab's per-server drill-down), just no longer for the Live tab itself.
    private lateinit var liveCategoryAdapter: LiveCategoryAdapter
    private lateinit var liveChannelAdapter: LiveChannelAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var guideAdapter: GuideAdapter

    private var miniPlayer: ExoPlayer? = null
    private var currentMiniStreamId: Int = -1
    // -1 = primary server. Set alongside currentMiniStreamId whenever a merged channel loads
    // into the mini player, so the eventual "go fullscreen" openPlayer() call can pass the right
    // server_index/merged_stream_id extras through to PlayerActivity for live EPG refresh.
    private var currentMiniServerIndex: Int = -1
    // The merged channel's real per-server stream id — currentMiniStreamId itself stays -1 for
    // merged channels (see playMergedChannel), so this carries the id PlayerActivity actually
    // needs for get_short_epg calls.
    private var currentMiniMergedStreamId: Int = -1
    // Merged (Providers) channels always play with currentMiniStreamId == -1 (no DB-backed
    // identity), so that alone can't drive the combined Favorites list's "now playing"
    // highlight — this tracks the actual CombinedFavorite.id ("primary:<id>" or
    // "<serverIndex>:<id>") of whichever item in that list was last clicked, primary or merged.
    private var currentMiniCombinedFavoriteId: String? = null
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
    // Favorites' own genre filter — kept separate from Live's activeGenre so switching tabs
    // doesn't cross-contaminate the two (same sticky-filter bug already fixed once for Live).
    private var activeFavoriteGenre: String? = null
    private val GENRE_KEYWORDS = GenreClassifier.GENRE_KEYWORDS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.savedMiniPlayerState?.let { state ->
            viewModel.savedMiniPlayerState = null
            restoredMiniState = state
            currentMiniUrl = state.url
            currentMiniTitle = state.title
            currentMiniStreamId = state.streamId
            currentMiniIsVod = state.isVod
            currentMiniServerIndex = state.serverIndex
            currentMiniMergedStreamId = state.mergedStreamId
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
        // Hiding system bars above doesn't move a front-camera punch-hole/notch cutout — that's
        // WindowInsets.Type.displayCutout, a separate inset present regardless of immersive
        // mode. On this device the cutout is a narrow TOP-CENTER punch-hole (~x 462-545 of a
        // 1008-wide screen), and topBar runs edge-to-edge starting at y=0 — its controls
        // (Providers-tab toggle, search box) land in that same horizontal band. Rather than
        // growing the whole 56dp bar taller (which left dead space across the rest of the row),
        // reserve exactly the cutout's own width as a spacer inserted before the search box —
        // everything after it (search, and anything the tab visibility logic shows further
        // right) shifts right, clear of the camera, with zero vertical/height change anywhere.
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { _, insets ->
            val cutoutRect = insets.displayCutout?.boundingRects?.firstOrNull()
            binding.cutoutSpacer?.updateLayoutParams<ViewGroup.LayoutParams> {
                width = cutoutRect?.width() ?: 0
            }
            insets
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
        // HomeActivity has no configChanges for orientation (unlike PlayerActivity), so
        // rotating the phone fully destroys and recreates it — this used to always force
        // Favorites regardless of what was showing before, which is why rotating while
        // watching a merged-provider channel (or right after exiting fullscreen for one)
        // kicked back to Favorites instead of staying on Providers. viewModel.lastTabPosition
        // survives rotation (it's a plain ViewModel field, not Activity state) — a true fresh
        // cold launch has it at its default of 0 (Favorites), so restoring to it is safe for
        // both cases and only changes behavior for the rotation-recreation one. Call
        // showFavorites()/select() explicitly rather than relying on TabLayout's own saved
        // instance state (which onTabSelected may not fire for), since that would leave
        // _channels showing stale data from the previous session otherwise.
        if (restoredMiniState == null) {
            // A true cold boot (process was killed, not just rotated) — viewModel is fresh, so
            // lastTabPosition is useless here. LAST_PLAYED_* survives process death (DataStore),
            // so route straight to wherever the last-played channel actually lives: Providers
            // (jumping to its server+category, same "go to where I'm watching" behavior as the
            // rotation/fullscreen-exit cases) for a merged channel, Favorites for a primary one
            // or if nothing was ever played.
            lifecycleScope.launch {
                val lastServerIndex = prefs.lastPlayedServerIndex.first()
                val lastStreamId = prefs.lastPlayedStreamId.first()
                if (lastServerIndex != -1 && lastStreamId != -1) {
                    currentMiniServerIndex = lastServerIndex
                    currentMiniMergedStreamId = lastStreamId
                    providersTabVisitedSinceTabSwitch = false
                    binding.tabLayout.getTabAt(TAB_PROVIDERS)?.select()
                } else {
                    binding.tabLayout.getTabAt(TAB_FAVORITES)?.select()
                    showFavorites()
                }
            }
        } else {
            val startTab = viewModel.lastTabPosition
            binding.tabLayout.getTabAt(startTab)?.select()
            if (startTab == TAB_FAVORITES) showFavorites()
        }
        // Landscape: land on the plain sidebar + mini player view (last-playing channel
        // loads into it via the existing initMiniPlayer()/restoredMiniState flow either
        // way) instead of immediately opening Favorites' channel list on every launch.
        collapseContentColumn()
        setupLandscapeSidebar()
        lifecycleScope.launch {
            applyAccent(android.graphics.Color.parseColor(prefs.accentColor.first()))
        }
        if (intent.getBooleanExtra(FeatureTourDialog.EXTRA_START_TOUR, false)) {
            FeatureTourDialog.show(this)
        } else {
            FeatureTourDialog.showIfNeeded(this)
        }
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
        // Mode-aware to match btnRefreshProviders' own handler — this used to always refresh
        // Live channels even while landscape was showing Movies/Series (before the
        // Live/Movies/Series mode row existed here at all).
        val refreshProviders = {
            when (providersMode) {
                ProvidersMode.MOVIES -> {
                    viewModel.refreshMergedVod()
                    Toast.makeText(this, "Refreshing all providers' movies…", Toast.LENGTH_SHORT).show()
                }
                ProvidersMode.SERIES -> {
                    viewModel.refreshMergedSeries()
                    Toast.makeText(this, "Refreshing all providers' series…", Toast.LENGTH_SHORT).show()
                }
                ProvidersMode.LIVE -> {
                    viewModel.refreshMergedChannels()
                    Toast.makeText(this, "Refreshing all providers…", Toast.LENGTH_SHORT).show()
                }
            }
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
                    tab?.position == TAB_PROVIDERS -> stepBackProvidersOneLevel()
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
        binding.btnTimelineViewRow?.setTextColor(colorInt)
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
            // Cold-boot merged-channel resume is handled by loadLastWatchedChannel() (called
            // from onStart, which always runs before this), so currentMiniUrl is already
            // populated by the time this coroutine runs — no need to duplicate that check here.
            val recent = viewModel.getRecentChannel()
            val isLive = currentMiniUrl.isNotEmpty() && !currentMiniIsVod
            // currentMiniStreamId == -1 alone doesn't mean "no primary channel is playing" — a
            // merged channel legitimately uses -1 too (see LiveChannelRow/MergedChannelEntity
            // kdoc), so without the currentMiniServerIndex == -1 check this would treat an
            // already-resumed merged channel as "different from recent" and overwrite it here.
            when {
                recent != null && recent.streamId != currentMiniStreamId && currentMiniServerIndex == -1 -> playInMiniPlayer(recent)
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
                positionMs = miniPlayer?.currentPosition ?: 0L,
                serverIndex = currentMiniServerIndex,
                mergedStreamId = currentMiniMergedStreamId
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
                openPlayer(
                    currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos,
                    serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                )
            }
        }
        binding.btnFullscreen?.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                openPlayer(
                    currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos,
                    serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                )
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
            // Cold-boot merged-channel resume takes priority — getRecentChannel() below only
            // ever reads the primary provider's ChannelEntity table, so a merged channel was
            // always losing this race (initMiniPlayer/onStart runs before onResume's own
            // cold-boot-restore coroutine gets a chance to call playMergedChannel, so this
            // unconditional fallback silently won every time and stomped it with the primary
            // "last watched" channel instead). Same LAST_PLAYED_* DataStore keys onResume reads.
            val lastServerIndex = prefs.lastPlayedServerIndex.first()
            val lastStreamId = prefs.lastPlayedStreamId.first()
            if (lastServerIndex != -1 && lastStreamId != -1) {
                val channel = viewModel.getMergedChannelByIndexAndId(lastServerIndex, lastStreamId)
                if (channel != null) {
                    playMergedChannel(channel)
                    return@launch
                }
            }
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
            currentMiniServerIndex = -1
            currentMiniIsVod = false
            viewModel.setCurrentlyPlaying(channel.streamId)
            // Persisted (DataStore, survives process death) separately from ViewModel state —
            // lets a true cold boot route back to wherever this channel actually lives, not
            // just a rotation-triggered recreation (see PreferencesManager.LAST_PLAYED_* kdoc).
            lifecycleScope.launch { prefs.setLastPlayedChannel(-1, channel.streamId) }
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
                openPlayer(
                    currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos,
                    serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                )
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
        binding.btnWhatsOnRow?.setOnClickListener { showWhatsOnNow() }
        binding.btnWhatsOnRow?.setOnLongClickListener { showUpNextTicker(); true }
        binding.btnRefresh?.setOnClickListener {
            viewModel.refreshNow()
            Toast.makeText(this, "Refreshing channels…", Toast.LENGTH_SHORT).show()
        }
        binding.btnRefreshProviders?.setOnClickListener {
            when (providersMode) {
                ProvidersMode.MOVIES -> {
                    viewModel.refreshMergedVod()
                    Toast.makeText(this, "Refreshing all providers' movies…", Toast.LENGTH_SHORT).show()
                }
                ProvidersMode.SERIES -> {
                    viewModel.refreshMergedSeries()
                    Toast.makeText(this, "Refreshing all providers' series…", Toast.LENGTH_SHORT).show()
                }
                ProvidersMode.LIVE -> {
                    viewModel.refreshMergedChannels()
                    Toast.makeText(this, "Refreshing all providers…", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Providers tab has three independent browse modes (see ProvidersMode kdoc), selected
        // directly via three side-by-side buttons (providersModeRow) rather than one cycling
        // toggle — switching always resets to that mode's top level, same as re-entering the
        // tab fresh.
        binding.btnProvidersModeLive?.setOnClickListener {
            providersMode = ProvidersMode.LIVE
            setProvidersModeButtonHighlight()
            showAllProvidersFromTop()
        }
        binding.btnProvidersModeMovies?.setOnClickListener {
            providersMode = ProvidersMode.MOVIES
            setProvidersModeButtonHighlight()
            viewModel.startObservingMergedVodServers()
            showAllProvidersMoviesFromTop()
        }
        binding.btnProvidersModeSeries?.setOnClickListener {
            providersMode = ProvidersMode.SERIES
            setProvidersModeButtonHighlight()
            viewModel.startObservingMergedSeriesServers()
            showAllProvidersSeriesFromTop()
        }
        setProvidersModeButtonHighlight()
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
            onCategoryClick = onCategoryClick@{ category ->
                if (categoryBulkSelectMode &&
                    category.categoryId != SHOW_HIDDEN_CATEGORIES_SENTINEL &&
                    category.categoryId !in hiddenMergedVodCategoryKeys &&
                    category.categoryId !in hiddenMergedSeriesCategoryKeys) {
                    // Bulk-hide mode active — a plain tap toggles the checkbox instead of
                    // drilling into the category, same shape as ChannelAdapter's bulk-select.
                    if (!bulkSelectedCategoryIds.add(category.categoryId)) bulkSelectedCategoryIds.remove(category.categoryId)
                    categoryAdapter.submitBulkSelection(bulkSelectedCategoryIds.toSet())
                    Toast.makeText(this, "${bulkSelectedCategoryIds.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectCategoryIdleRunnable)
                    if (bulkSelectedCategoryIds.isEmpty()) categoryBulkSelectMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectCategoryIdleRunnable, 8000)
                    return@onCategoryClick
                }
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.MOVIES) {
                    // Movies-mode equivalent of the Live-mode 3-level drill just below —
                    // same server -> category -> items shape, mergedVodAdapter as the leaf list.
                    if (viewModel.selectedMergedVodServerIndex == null && category.categoryId == FAVORITES_SERVER_SENTINEL) {
                        // Aggregate "★ Favorites" — no category level, straight to the flat
                        // cross-provider favorites list.
                        viewModel.selectMergedVodAllFavoritesAcrossServers()
                        landscapeShowChannelsMode()
                        binding.rvCategories.visibility = View.GONE
                        binding.rvChannels.adapter = mergedVodAdapter
                    } else if (viewModel.selectedMergedVodServerIndex == null) {
                        viewModel.selectMergedVodServer(category.categoryId.toInt())
                        categoryAdapter.submitList(emptyList())
                        lifecycleScope.launch {
                            viewModel.mergedVodCategories.collect { cats ->
                                if (viewModel.selectedMergedVodServerIndex != null) {
                                    categoryAdapter.submitList(mergedVodCategoriesToSynthetic(cats))
                                }
                            }
                        }
                    } else if (category.categoryId == SHOW_HIDDEN_CATEGORIES_SENTINEL) {
                        // Toggle reveal — re-render the same (already-fetched) category list
                        // with hidden rows included/excluded, no new fetch needed.
                        showingHiddenVodCategories = !showingHiddenVodCategories
                        categoryAdapter.submitList(mergedVodCategoriesToSynthetic(viewModel.mergedVodCategories.value))
                    } else if (category.categoryId in hiddenMergedVodCategoryKeys) {
                        // Tapping a revealed hidden row unhides it directly, rather than
                        // drilling into it like a normal category tap would.
                        viewModel.unhideMergedVodCategory(category.categoryId)
                        Toast.makeText(this, "Category unhidden", Toast.LENGTH_SHORT).show()
                    } else {
                        val rawCategoryId = category.categoryId.substringAfter(':', category.categoryId)
                        val categoryId = if (rawCategoryId == NO_CATEGORY_ID) null else rawCategoryId
                        viewModel.selectMergedVodCategory(categoryId)
                        landscapeShowChannelsMode()
                        binding.rvChannels.adapter = mergedVodAdapter
                    }
                } else if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.SERIES) {
                    // Series-mode equivalent — same server -> category -> items drill, but the
                    // leaf list opens SeriesDetailActivity per tap instead of playing directly
                    // (see mergedSeriesAdapter's onItemClick wiring), since a series item isn't
                    // itself a single playable stream.
                    if (viewModel.selectedMergedSeriesServerIndex == null && category.categoryId == FAVORITES_SERVER_SENTINEL) {
                        viewModel.selectMergedSeriesAllFavoritesAcrossServers()
                        landscapeShowChannelsMode()
                        binding.rvCategories.visibility = View.GONE
                        binding.rvChannels.adapter = mergedSeriesAdapter
                    } else if (viewModel.selectedMergedSeriesServerIndex == null) {
                        viewModel.selectMergedSeriesServer(category.categoryId.toInt())
                        categoryAdapter.submitList(emptyList())
                        lifecycleScope.launch {
                            viewModel.mergedSeriesCategories.collect { cats ->
                                if (viewModel.selectedMergedSeriesServerIndex != null) {
                                    categoryAdapter.submitList(mergedSeriesCategoriesToSynthetic(cats))
                                }
                            }
                        }
                    } else if (category.categoryId == SHOW_HIDDEN_CATEGORIES_SENTINEL) {
                        showingHiddenSeriesCategories = !showingHiddenSeriesCategories
                        categoryAdapter.submitList(mergedSeriesCategoriesToSynthetic(viewModel.mergedSeriesCategories.value))
                    } else if (category.categoryId in hiddenMergedSeriesCategoryKeys) {
                        viewModel.unhideMergedSeriesCategory(category.categoryId)
                        Toast.makeText(this, "Category unhidden", Toast.LENGTH_SHORT).show()
                    } else {
                        val rawCategoryId = category.categoryId.substringAfter(':', category.categoryId)
                        val categoryId = if (rawCategoryId == NO_CATEGORY_ID) null else rawCategoryId
                        viewModel.selectMergedSeriesCategory(categoryId)
                        landscapeShowChannelsMode()
                        binding.rvChannels.adapter = mergedSeriesAdapter
                    }
                } else if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS) {
                    // 3-level drill (server -> category -> channels): the first tap picks a
                    // server and should show ITS categories next, not jump to channels yet.
                    // A "★ Favorites" entry above the real provider list aggregates that mode's
                    // favorites across every configured secondary provider at once — no category
                    // level, straight to the flat list, same shape as the other two modes above.
                    if (viewModel.selectedMergedServerIndex == null && category.categoryId == FAVORITES_SERVER_SENTINEL) {
                        viewModel.selectMergedAllFavoritesAcrossServers()
                        landscapeShowChannelsMode()
                        binding.rvCategories.visibility = View.GONE
                        binding.rvChannels.adapter = mergedChannelAdapter
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
                        // category.categoryId here is the scoped "serverIndex:categoryId" key
                        // mergedCategoriesToSynthetic builds (so the shared star-favorite check
                        // works without collisions across servers) — strip the prefix back off
                        // to get the real category id selectMergedCategory expects.
                        val rawCategoryId = category.categoryId.substringAfter(':', category.categoryId)
                        val categoryId = if (rawCategoryId == NO_CATEGORY_ID) null else rawCategoryId
                        viewModel.selectMergedCategory(categoryId)
                        landscapeShowChannelsMode()
                        binding.rvChannels.adapter = mergedChannelAdapter
                    }
                } else {
                    // Live and Categories now use liveCategoryAdapter/LiveCategoryRow (see
                    // setupRecyclerViews' construction of that adapter) — only Movies still
                    // routes a plain CategoryEntity click through here.
                    when (binding.tabLayout.selectedTabPosition) {
                        TAB_MOVIES -> viewModel.selectVodCategory(category.categoryId)
                    }
                    landscapeShowChannelsMode()
                }
            },
            onCategoryLongClick = { category ->
                // Category-favoriting/pinning is a LIVE-only concept (matches primary Movies/
                // Series, which have no category-level favorite at all) — this handler used to
                // fire for Movies/Series categories too whenever a merged server was selected,
                // incorrectly toggling a "category favorite" that Movies/Series don't have a
                // concept of, against a Movies/Series category id string it wasn't meant to see.
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS &&
                    providersMode == ProvidersMode.LIVE && viewModel.selectedMergedServerIndex != null) {
                    // Only meaningful one level in (after a server is picked) — at the
                    // server-picker level category.categoryId actually holds the serverIndex
                    // string (see onCategoryClick above), not a real category id.
                    viewModel.toggleMergedCategoryFavorite(category.categoryId)
                    Toast.makeText(this, "Category favorite updated", Toast.LENGTH_SHORT).show()
                } else if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS &&
                    providersMode == ProvidersMode.MOVIES && viewModel.selectedMergedVodServerIndex != null &&
                    category.categoryId != SHOW_HIDDEN_CATEGORIES_SENTINEL) {
                    showCategoryBulkHideDialog(category, isSeries = false)
                } else if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS &&
                    providersMode == ProvidersMode.SERIES && viewModel.selectedMergedSeriesServerIndex != null &&
                    category.categoryId != SHOW_HIDDEN_CATEGORIES_SENTINEL) {
                    showCategoryBulkHideDialog(category, isSeries = true)
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
                    else bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 8000)
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
            onChannelClick = { channel ->
                if (bulkSelectMergedMode) {
                    val key = "${channel.serverIndex}:${channel.streamId}"
                    if (!bulkSelectedMergedKeys.add(key)) bulkSelectedMergedKeys.remove(key)
                    mergedChannelAdapter.submitBulkSelection(bulkSelectedMergedKeys.toSet())
                    Toast.makeText(this, "${bulkSelectedMergedKeys.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                    if (bulkSelectedMergedKeys.isEmpty()) bulkSelectMergedMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectMergedIdleRunnable, 8000)
                } else {
                    playMergedChannel(channel)
                }
            },
            onFavoriteClick = { channel ->
                viewModel.setMergedChannelFavorite(channel, !channel.isFavorite)
                Toast.makeText(this, if (channel.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            onChannelLongClick = { channel -> showMergedChannelActionsMenu(channel) },
            onChannelDoubleClick = { channel ->
                lifecycleScope.launch {
                    try {
                        val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                        openPlayer(url, "${channel.name} · ${channel.serverNickname}", -1, serverIndex = channel.serverIndex, mergedStreamId = channel.streamId)
                    } catch (_: Exception) {
                        Toast.makeText(this@HomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        mergedVodAdapter = MergedVodAdapter(
            onItemClick = onItemClick@{ vod ->
                if (bulkSelectMergedVodMode) {
                    val key = "${vod.serverIndex}:${vod.streamId}"
                    if (!bulkSelectedMergedVodKeys.add(key)) bulkSelectedMergedVodKeys.remove(key)
                    mergedVodAdapter.submitBulkSelection(bulkSelectedMergedVodKeys.toSet())
                    Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectMergedVodIdleRunnable)
                    if (bulkSelectedMergedVodKeys.isEmpty()) bulkSelectMergedVodMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectMergedVodIdleRunnable, 8000)
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
            onItemLongClick = { vod ->
                bulkSelectMergedVodMode = true
                val key = "${vod.serverIndex}:${vod.streamId}"
                bulkSelectedMergedVodKeys.add(key)
                mergedVodAdapter.submitBulkSelection(bulkSelectedMergedVodKeys.toSet())
                Toast.makeText(this, "${bulkSelectedMergedVodKeys.size} selected — tap more movies to hide", Toast.LENGTH_SHORT).show()
                bulkSelectHandler.removeCallbacks(bulkSelectMergedVodIdleRunnable)
                bulkSelectHandler.postDelayed(bulkSelectMergedVodIdleRunnable, 8000)
            }
        )

        mergedSeriesAdapter = MergedSeriesAdapter(
            onItemClick = onItemClick@{ series ->
                if (bulkSelectMergedSeriesMode) {
                    val key = "${series.serverIndex}:${series.seriesId}"
                    if (!bulkSelectedMergedSeriesKeys.add(key)) bulkSelectedMergedSeriesKeys.remove(key)
                    mergedSeriesAdapter.submitBulkSelection(bulkSelectedMergedSeriesKeys.toSet())
                    Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectMergedSeriesIdleRunnable)
                    if (bulkSelectedMergedSeriesKeys.isEmpty()) bulkSelectMergedSeriesMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectMergedSeriesIdleRunnable, 8000)
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
                bulkSelectMergedSeriesMode = true
                val key = "${series.serverIndex}:${series.seriesId}"
                bulkSelectedMergedSeriesKeys.add(key)
                mergedSeriesAdapter.submitBulkSelection(bulkSelectedMergedSeriesKeys.toSet())
                Toast.makeText(this, "${bulkSelectedMergedSeriesKeys.size} selected — tap more shows to hide", Toast.LENGTH_SHORT).show()
                bulkSelectHandler.removeCallbacks(bulkSelectMergedSeriesIdleRunnable)
                bulkSelectHandler.postDelayed(bulkSelectMergedSeriesIdleRunnable, 8000)
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
                        scheduleContentAutoCollapse()
                    }
                    is CombinedFavorite.Merged -> playMergedChannel(item.channel)
                }
            },
            onChannelDoubleClick = { item ->
                when (item) {
                    is CombinedFavorite.Primary -> {
                        val currentIds = viewModel.combinedFavorites.value.mapNotNull { (it as? CombinedFavorite.Primary)?.channel?.streamId }.toIntArray()
                        lifecycleScope.launch {
                            val url = viewModel.getLiveStreamUrl(item.channel.streamId)
                            openPlayer(url, item.channel.name, item.channel.streamId, currentIds)
                        }
                    }
                    is CombinedFavorite.Merged -> {
                        lifecycleScope.launch {
                            try {
                                val url = viewModel.getMergedLiveStreamUrl(item.channel.serverIndex, item.channel.streamId)
                                openPlayer(
                                    url, "${item.channel.name} · ${item.channel.serverNickname}", -1,
                                    serverIndex = item.channel.serverIndex, mergedStreamId = item.channel.streamId
                                )
                            } catch (_: Exception) {
                                Toast.makeText(this@HomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
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
                    is CombinedFavorite.Primary -> showChannelActionsMenu(item.channel)
                    is CombinedFavorite.Merged -> showMergedChannelActionsMenu(item.channel)
                }
            }
        )

        liveCategoryAdapter = LiveCategoryAdapter(
            onCategoryClick = { row ->
                viewModel.selectCombinedCategory(row)
                liveCategoryAdapter.setSelectedId(row.id)
                landscapeShowChannelsMode()
            },
            onCategoryLongClick = { row ->
                viewModel.toggleCombinedCategoryFavorite(row)
                Toast.makeText(this, "Category favorite updated", Toast.LENGTH_SHORT).show()
            }
        )

        liveChannelAdapter = LiveChannelAdapter(
            onChannelClick = onChannelClick@{ row ->
                if (bulkSelectLiveMode) {
                    if (!bulkSelectedLiveIds.add(row.id)) bulkSelectedLiveIds.remove(row.id)
                    liveChannelAdapter.submitBulkSelection(bulkSelectedLiveIds.toSet())
                    Toast.makeText(this, "${bulkSelectedLiveIds.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectLiveIdleRunnable)
                    if (bulkSelectedLiveIds.isEmpty()) bulkSelectLiveMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectLiveIdleRunnable, 8000)
                    return@onChannelClick
                }
                val ch = row.channel
                val merged = row.mergedChannel
                if (ch != null) {
                    currentMiniCombinedFavoriteId = row.id
                    lifecycleScope.launch {
                        playInMiniPlayer(ch)
                        viewModel.markChannelWatched(ch.streamId)
                        viewModel.setCurrentlyPlaying(ch.streamId)
                    }
                    scheduleContentAutoCollapse()
                } else if (merged != null) {
                    playMergedChannel(merged)
                }
            },
            onChannelDoubleClick = { row ->
                val ch = row.channel
                val merged = row.mergedChannel
                if (ch != null) {
                    val currentIds = viewModel.combinedLiveChannels.value.mapNotNull { it.channel?.streamId }.toIntArray()
                    lifecycleScope.launch {
                        val url = viewModel.getLiveStreamUrl(ch.streamId)
                        openPlayer(url, ch.name, ch.streamId, currentIds)
                    }
                } else if (merged != null) {
                    lifecycleScope.launch {
                        try {
                            val url = viewModel.getMergedLiveStreamUrl(merged.serverIndex, merged.streamId)
                            openPlayer(
                                url, "${merged.name} · ${merged.serverNickname}", -1,
                                serverIndex = merged.serverIndex, mergedStreamId = merged.streamId
                            )
                        } catch (_: Exception) {
                            Toast.makeText(this@HomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onFavoriteClick = { row ->
                viewModel.toggleLiveRowFavorite(row)
                Toast.makeText(this, if (row.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
            },
            onChannelLongClick = { row -> showLiveRowActionsMenu(row) }
        )

        vodAdapter = VodAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                    val progress = viewModel.getVodProgress(vod.streamId)
                    currentMiniUrl = url
                    currentMiniTitle = vod.name
                    currentMiniStreamId = vod.streamId
                    currentMiniServerIndex = -1
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
                openPlayer(
                    currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos,
                    serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                )
            }
        }
                }
            },
            onFavoriteClick = { vod -> viewModel.toggleVodFavorite(vod) },
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
            onSeriesClick = onSeriesClick@{ series ->
                if (bulkSelectSeriesMode) {
                    if (!bulkSelectedSeriesIds.add(series.seriesId)) bulkSelectedSeriesIds.remove(series.seriesId)
                    seriesAdapter.submitBulkSelection(bulkSelectedSeriesIds.toSet())
                    Toast.makeText(this, "${bulkSelectedSeriesIds.size} selected", Toast.LENGTH_SHORT).show()
                    bulkSelectHandler.removeCallbacks(bulkSelectSeriesIdleRunnable)
                    if (bulkSelectedSeriesIds.isEmpty()) bulkSelectSeriesMode = false
                    else bulkSelectHandler.postDelayed(bulkSelectSeriesIdleRunnable, 8000)
                    return@onSeriesClick
                }
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra("series_id", series.seriesId)
                    putExtra("series_name", series.name)
                    putExtra("series_cover", series.cover)
                    putExtra("series_genre", series.genre)
                    putExtra("series_rating", series.rating)
                    putExtra("series_plot", series.plot)
                })
            },
            onFavoriteClick = { series -> viewModel.toggleSeriesFavorite(series) },
            onSeriesLongClick = { series ->
                bulkSelectSeriesMode = true
                bulkSelectedSeriesIds.add(series.seriesId)
                seriesAdapter.submitBulkSelection(bulkSelectedSeriesIds.toSet())
                Toast.makeText(this, "${bulkSelectedSeriesIds.size} selected — tap more shows to hide", Toast.LENGTH_SHORT).show()
                bulkSelectHandler.removeCallbacks(bulkSelectSeriesIdleRunnable)
                bulkSelectHandler.postDelayed(bulkSelectSeriesIdleRunnable, 8000)
            }
        )

        guideAdapter = GuideAdapter(
            onChannelClick = { row ->
                val mergedCh = row.mergedChannel
                if (mergedCh != null) {
                    // Merged/secondary-provider row — no DB-backed ChannelEntity to open a
                    // fullscreen player against directly, so this mirrors exactly what tapping
                    // a merged channel does everywhere else in the app (mini player first,
                    // fullscreen via the hero "Watch" button using playMergedChannel's own
                    // openPlayer wiring with serverIndex/mergedStreamId).
                    playMergedChannel(mergedCh)
                } else {
                    row.channel?.let { ch ->
                        lifecycleScope.launch {
                            playInMiniPlayer(ch)
                            val url = viewModel.getLiveStreamUrl(ch.streamId)
                            openPlayer(url, ch.name, ch.streamId)
                        }
                    }
                }
            },
            onReplayClick = { row, program ->
                // Timeshift/replay is a primary-provider-only feature (row.supportsReplay is
                // false for merged rows, so onReplayClick is never reachable for them — see
                // GuideAdapter's isReplay check), so row.channel is always non-null here.
                row.channel?.let { ch ->
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
                        val url = viewModel.getTimeshiftUrl(ch.streamId, startSec, durationMin)
                        val title = "${ch.name} — ${program.title}"
                        openPlayer(url, title, ch.streamId)
                    }
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
                binding.providersModeRow?.visibility = if (tab?.position == TAB_PROVIDERS) View.VISIBLE else View.GONE
                binding.guideModeRow?.visibility = if (tab?.position == TAB_GUIDE) View.VISIBLE else View.GONE
                // Switching TO Providers from a different tab counts as a fresh visit — the
                // "jump to the playing channel" behavior gets one shot; every tap after that
                // (via onTabReselected below) just steps back one level instead. Also always
                // reset back to Live mode on a fresh visit, same reasoning.
                if (tab?.position != TAB_PROVIDERS) {
                    providersTabVisitedSinceTabSwitch = false
                    providersMode = ProvidersMode.LIVE
                    setProvidersModeButtonHighlight()
                }
                when (tab?.position) {
                    TAB_FAVORITES -> showFavorites()
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
                if (tab?.position == TAB_GUIDE) {
                    binding.guideModeRow?.visibility = View.GONE
                }
            }
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        // Long-press the Series tab for a "Continue Watching" ticker — same discoverability
        // pattern as long-pressing "What's On" for the live-channel up-next ticker, rather than
        // adding a new always-visible button.
        binding.tabLayout.getTabAt(TAB_SERIES)?.view?.setOnLongClickListener {
            showContinueSeriesTicker()
            true
        }
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
            // clearFocus() alone only removes the visual focus ring — the keyboard/cursor stays
            // up until the IME is explicitly told to hide.
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        }
    }

    private fun dispatchSearch(query: String) {
        when (binding.tabLayout.selectedTabPosition) {
            TAB_MOVIES -> viewModel.searchVod(query)
            TAB_SERIES -> viewModel.searchSeries(query)
            TAB_FAVORITES -> {
                if (query.isBlank()) {
                    // Back to the genre-filtered view, not a dead end.
                    showFavorites()
                } else {
                    // searchFavorites() writes into combinedFavorites (primary + merged), same
                    // source the existing combinedFavorites collector renders via
                    // combinedFavoriteAdapter — no adapter swap needed here, unlike before when
                    // search wrote into the primary-only _channels and needed channelAdapter.
                    viewModel.searchFavorites(query)
                    landscapeShowChannelsMode()
                    binding.rvCategories.visibility = View.GONE
                    binding.rvChannels.adapter = combinedFavoriteAdapter
                }
            }
            TAB_PROVIDERS -> {
                if (query.isBlank()) {
                    // Back to wherever the server/category drill-down was, not a dead end.
                    when (providersMode) {
                        ProvidersMode.MOVIES -> showAllProvidersMoviesFromTop()
                        ProvidersMode.SERIES -> showAllProvidersSeriesFromTop()
                        ProvidersMode.LIVE -> showAllProviders()
                    }
                } else {
                    // searchMergedVod/searchMergedSeries existed in the ViewModel since those
                    // features shipped but were never actually called from here — search only
                    // ever worked for Live mode in the Providers tab.
                    when (providersMode) {
                        ProvidersMode.MOVIES -> {
                            viewModel.searchMergedVod(query)
                            landscapeShowChannelsMode()
                            binding.rvCategories.visibility = View.GONE
                            binding.rvChannels.adapter = mergedVodAdapter
                            mergedVodAdapter.submitList(viewModel.mergedVod.value)
                        }
                        ProvidersMode.SERIES -> {
                            viewModel.searchMergedSeries(query)
                            landscapeShowChannelsMode()
                            binding.rvCategories.visibility = View.GONE
                            binding.rvChannels.adapter = mergedSeriesAdapter
                            mergedSeriesAdapter.submitList(viewModel.mergedSeries.value)
                        }
                        ProvidersMode.LIVE -> {
                            viewModel.searchMergedChannels(query)
                            landscapeShowChannelsMode()
                            binding.rvCategories.visibility = View.GONE
                            binding.rvChannels.adapter = mergedChannelAdapter
                            mergedChannelAdapter.submitList(viewModel.mergedChannels.value)
                        }
                    }
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
        binding.rvCategories.adapter = liveCategoryAdapter
        binding.rvChannels.adapter = liveChannelAdapter
        // In portrait, categories and channels are two always-visible side-by-side panes (not
        // toggled like in landscape) — without this, switching here from Favorites left the
        // right-hand pane showing that folder's channels until the new category's load
        // happened to land, making it look like a category/folder was already selected.
        liveChannelAdapter.submitList(emptyList())
        val rows = viewModel.combinedLiveCategories.value
        // updateGenreChips only ever reads categoryName for keyword-matching (see
        // GENRE_KEYWORDS detection below) — synthesizing bare CategoryEntity stand-ins for
        // merged rows lets it stay unchanged rather than genericizing it for one caller.
        updateGenreChips(rows.map { row ->
            row.category ?: com.iptvapp.data.local.entities.CategoryEntity(
                categoryId = row.id, categoryName = row.mergedCategoryName ?: "", parentId = 0, type = "merged_category"
            )
        })
        val filtered = genreFilterLiveRows(rows)
        liveCategoryAdapter.submitList(filtered)
        if (filtered.isNotEmpty()) {
            val current = viewModel.selectedCombinedCategoryRow()
            if (viewModel.hasSelectedCombinedCategory() && current != null) {
                viewModel.selectCombinedCategory(current)
                liveCategoryAdapter.setSelectedId(current.id)
            } else {
                viewModel.selectCombinedCategory(filtered.first())
                liveCategoryAdapter.setSelectedId(filtered.first().id)
            }
        }
    }

    private fun genreFilter(cats: List<com.iptvapp.data.local.entities.CategoryEntity>): List<com.iptvapp.data.local.entities.CategoryEntity> {
        val genre = activeGenre ?: return cats
        val keywords = GENRE_KEYWORDS[genre] ?: return cats
        return cats.filter { cat -> keywords.any { kw -> cat.categoryName.contains(kw, ignoreCase = true) } }
    }

    // Genre chips only ever came from provider-supplied category NAMES (see GENRE_KEYWORDS) —
    // applies identically to merged-provider category rows, matching by the same name text.
    private fun genreFilterLiveRows(rows: List<LiveCategoryRow>): List<LiveCategoryRow> {
        val genre = activeGenre ?: return rows
        val keywords = GENRE_KEYWORDS[genre] ?: return rows
        return rows.filter { row ->
            val name = row.category?.categoryName ?: row.mergedCategoryName ?: return@filter false
            keywords.any { kw -> name.contains(kw, ignoreCase = true) }
        }
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

    // Now shows pinned/favorite categories from EVERY configured provider (primary + merged),
    // color-coded the same way the Live tab is, instead of only the primary provider's.
    private fun showFavCategories() {
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = liveCategoryAdapter
        binding.rvChannels.adapter = liveChannelAdapter
        liveChannelAdapter.submitList(emptyList())
        val favRows = viewModel.combinedFavoriteLiveCategories.value
        liveCategoryAdapter.submitList(favRows)
        if (favRows.isNotEmpty()) {
            viewModel.selectCombinedCategory(favRows.first())
            liveCategoryAdapter.setSelectedId(favRows.first().id)
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
    // provider can itself have tens of thousands of channels. Merged favorites are viewed from
    // the main Favorites tab now (combined with primary favorites, auto genre-classified) —
    // this tab is purely a server/category browser; favoriting/folder-assignment per channel
    // still works via each row's star and long-press menu.
    // First switch INTO the Providers tab (onTabSelected, not a re-tap) — if a merged channel
    // is currently playing, jump straight to its server+category+row, matching the same
    // "go to where I'm actually watching" behavior Favorites already has for its own channels.
    // Every re-tap after this (onTabReselected -> stepBackProvidersOneLevel) just steps back
    // one level instead of re-jumping — this only fires once per visit to the tab.
    private fun showAllProviders() {
        if (providersTabVisitedSinceTabSwitch) {
            android.util.Log.d("ProvidersJump", "showAllProviders: skipped, already visited since tab switch")
            return
        }
        providersTabVisitedSinceTabSwitch = true
        val playingServerIndex = currentMiniServerIndex
        val playingStreamId = currentMiniMergedStreamId
        android.util.Log.d("ProvidersJump", "showAllProviders: serverIndex=$playingServerIndex streamId=$playingStreamId")
        if (playingServerIndex != -1 && playingStreamId != -1) {
            lifecycleScope.launch {
                val channel = viewModel.getMergedChannelByIndexAndId(playingServerIndex, playingStreamId)
                android.util.Log.d("ProvidersJump", "getMergedChannelByIndexAndId result: $channel")
                if (channel != null) {
                    jumpToPlayingMergedChannel(channel)
                    return@launch
                }
                showAllProvidersFromTop()
            }
        } else {
            showAllProvidersFromTop()
        }
    }

    // Re-tapping Providers while already on it — plain one-level-back navigation, same shape
    // as every other tab's onTabReselected handler: channel list -> that server's category
    // list -> the top-level server picker. Never re-jumps to a playing channel.
    private fun stepBackProvidersOneLevel() {
        when (providersMode) {
            ProvidersMode.MOVIES -> { stepBackProvidersMoviesOneLevel(); return }
            ProvidersMode.SERIES -> { stepBackProvidersSeriesOneLevel(); return }
            ProvidersMode.LIVE -> {}
        }
        when {
            viewModel.isViewingMergedFavorites -> showAllProvidersFromTop()
            viewModel.hasMergedCategorySelected -> {
                val serverIndex = viewModel.selectedMergedServerIndex
                if (serverIndex != null) viewModel.selectMergedServer(serverIndex) // re-selecting clears category, keeps server
                landscapeShowCategoriesMode()
                setGenreFilterVisible(false)
                binding.rvCategories.visibility = View.VISIBLE
                binding.rvCategories.adapter = categoryAdapter
                binding.rvChannels.adapter = mergedChannelAdapter
                categoryAdapter.submitList(mergedCategoriesToSynthetic(viewModel.mergedCategories.value))
                lifecycleScope.launch {
                    viewModel.mergedCategories.collect { cats ->
                        if (viewModel.hasMergedCategorySelected) return@collect
                        categoryAdapter.submitList(mergedCategoriesToSynthetic(cats))
                    }
                }
            }
            viewModel.selectedMergedServerIndex != null -> showAllProvidersFromTop()
            else -> showAllProvidersFromTop()
        }
    }

    private fun showAllProvidersFromTop() {
        viewModel.resetMergedSelection()
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = mergedChannelAdapter
        categoryAdapter.submitList(mergedServersToSynthetic(viewModel.mergedServers.value))
    }

    // Movies-mode equivalents of the three functions above — same drill-down shape (server ->
    // category -> items), but no "jump to currently playing" (merged VOD has no mini-player
    // resume state to jump back into in v1) and using mergedVodAdapter as the leaf list.
    private fun stepBackProvidersMoviesOneLevel() {
        when {
            viewModel.isViewingMergedVodFavorites -> showAllProvidersMoviesFromTop()
            viewModel.selectedMergedVodCategoryId != null || viewModel.mergedVod.value.isNotEmpty() -> {
                val serverIndex = viewModel.selectedMergedVodServerIndex
                if (serverIndex != null) viewModel.selectMergedVodServer(serverIndex)
                landscapeShowCategoriesMode()
                setGenreFilterVisible(false)
                binding.rvCategories.visibility = View.VISIBLE
                binding.rvCategories.adapter = categoryAdapter
                binding.rvChannels.adapter = mergedVodAdapter
                categoryAdapter.submitList(mergedVodCategoriesToSynthetic(viewModel.mergedVodCategories.value))
            }
            viewModel.selectedMergedVodServerIndex != null -> showAllProvidersMoviesFromTop()
            else -> showAllProvidersMoviesFromTop()
        }
    }

    private fun showAllProvidersMoviesFromTop() {
        viewModel.resetMergedVodSelection()
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = mergedVodAdapter
        categoryAdapter.submitList(mergedVodServersToSynthetic(viewModel.mergedVodServers.value))
    }

    // Series-mode equivalents of the Movies-mode functions above — same drill-down shape,
    // mergedSeriesAdapter as the leaf list (opens SeriesDetailActivity per tap).
    private fun stepBackProvidersSeriesOneLevel() {
        when {
            viewModel.isViewingMergedSeriesFavorites -> showAllProvidersSeriesFromTop()
            viewModel.selectedMergedSeriesCategoryId != null || viewModel.mergedSeries.value.isNotEmpty() -> {
                val serverIndex = viewModel.selectedMergedSeriesServerIndex
                if (serverIndex != null) viewModel.selectMergedSeriesServer(serverIndex)
                landscapeShowCategoriesMode()
                setGenreFilterVisible(false)
                binding.rvCategories.visibility = View.VISIBLE
                binding.rvCategories.adapter = categoryAdapter
                binding.rvChannels.adapter = mergedSeriesAdapter
                categoryAdapter.submitList(mergedSeriesCategoriesToSynthetic(viewModel.mergedSeriesCategories.value))
            }
            viewModel.selectedMergedSeriesServerIndex != null -> showAllProvidersSeriesFromTop()
            else -> showAllProvidersSeriesFromTop()
        }
    }

    private fun showAllProvidersSeriesFromTop() {
        viewModel.resetMergedSeriesSelection()
        landscapeShowCategoriesMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = mergedSeriesAdapter
        categoryAdapter.submitList(mergedSeriesServersToSynthetic(viewModel.mergedSeriesServers.value))
    }

    private fun jumpToPlayingMergedChannel(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        landscapeShowChannelsMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = mergedChannelAdapter
        viewModel.selectMergedServer(channel.serverIndex)
        viewModel.selectMergedCategory(channel.categoryId)
        var scrolled = false
        lifecycleScope.launch {
            viewModel.mergedChannels.collect { list ->
                if (scrolled || viewModel.selectedMergedServerIndex != channel.serverIndex) return@collect
                mergedChannelAdapter.submitList(list)
                val pos = list.indexOfFirst { it.streamId == channel.streamId }
                if (pos >= 0) {
                    scrolled = true
                    binding.rvChannels.post { binding.rvChannels.scrollToPosition(pos) }
                }
            }
        }
    }

    private val NO_CATEGORY_ID = "__uncategorized__"
    // Sentinel categoryId for the "★ Favorites" row prepended to each mode's server picker —
    // aggregates that mode's favorites across every configured secondary provider at once, see
    // HomeViewModel.selectMergedAllFavoritesAcrossServers/selectMergedVodAllFavoritesAcrossServers/
    // selectMergedSeriesAllFavoritesAcrossServers. Checked before the normal `.toInt()` parse in
    // onCategoryClick's server-picker branch for all three modes.
    private val FAVORITES_SERVER_SENTINEL = "__favorites__"
    // Sentinel categoryId for the "Show/Hide Hidden Categories" toggle row prepended to a
    // Movies/Series category list whenever that provider has at least one hidden category —
    // tapping it flips showingHiddenVodCategories/showingHiddenSeriesCategories and re-renders.
    private val SHOW_HIDDEN_CATEGORIES_SENTINEL = "__show_hidden_categories__"

    private fun mergedServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedServerSummary>): List<CategoryEntity> {
        val favoritesRow = CategoryEntity(
            categoryId = FAVORITES_SERVER_SENTINEL,
            categoryName = "★ Favorites",
            parentId = 0,
            type = "merged_server"
        )
        // serverIndex == -1 is always whichever provider is currently primary/active — its
        // channels are already fully browsable via the normal Live tab, so listing it again
        // here was redundant and confusing next to the other, actually-"extra" providers.
        return listOf(favoritesRow) + list.filter { it.serverIndex != -1 }.map {
            CategoryEntity(
                categoryId = it.serverIndex.toString(),
                categoryName = "${it.serverNickname} (${it.channelCount})",
                parentId = 0,
                type = "merged_server"
            )
        }
    }

    private fun mergedCategoriesToSynthetic(list: List<com.iptvapp.data.local.entities.MergedCategorySummary>): List<CategoryEntity> {
        val serverIndex = viewModel.selectedMergedServerIndex ?: -1
        val entities = list.map {
            CategoryEntity(
                // Scoped "$serverIndex:$categoryId" — plain categoryId can collide across
                // servers, and this is also what makes CategoryAdapter's shared
                // `categoryId in favoriteCategoryIds` star check collision-safe with zero
                // adapter changes. onCategoryClick strips this prefix back off before calling
                // selectMergedCategory.
                categoryId = "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}",
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.channelCount})",
                parentId = 0,
                type = "merged_category"
            )
        }
        val favoriteKeys = mergedFavoriteCategoryKeys
        return entities.sortedByDescending { it.categoryId in favoriteKeys }
    }

    // Movies-mode equivalents of the two synthetic-category converters above — same
    // CategoryAdapter-reuse trick (categoryAdapter is generic over CategoryEntity, so the
    // existing server-picker/category-picker UI works unchanged for VOD too), same
    // "$serverIndex:$categoryId" scoping to avoid cross-server id collisions. No favorite-star
    // sort here in v1 — merged VOD categories aren't pinnable the way merged channel categories
    // are (no equivalent of favoriteMergedCategoryKeys for VOD yet).
    private fun mergedVodServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedVodServerSummary>): List<CategoryEntity> {
        val favoritesRow = CategoryEntity(
            categoryId = FAVORITES_SERVER_SENTINEL,
            categoryName = "★ Favorites",
            parentId = 0,
            type = "merged_vod_server"
        )
        return listOf(favoritesRow) + list.filter { it.serverIndex != -1 }.map {
            CategoryEntity(
                categoryId = it.serverIndex.toString(),
                categoryName = "${it.serverNickname} (${it.vodCount})",
                parentId = 0,
                type = "merged_vod_server"
            )
        }
    }

    private fun mergedVodCategoriesToSynthetic(list: List<com.iptvapp.data.local.entities.MergedVodCategorySummary>): List<CategoryEntity> {
        val serverIndex = viewModel.selectedMergedVodServerIndex ?: -1
        val hiddenKeys = hiddenMergedVodCategoryKeys
        val (hidden, visible) = list.partition { "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}" in hiddenKeys }
        val visibleRows = visible.map {
            CategoryEntity(
                categoryId = "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}",
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.vodCount})",
                parentId = 0,
                type = "merged_vod_category"
            )
        }
        val hiddenRows = if (showingHiddenVodCategories) hidden.map {
            CategoryEntity(
                categoryId = "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}",
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.vodCount})",
                parentId = 0,
                type = "merged_vod_category"
            )
        } else emptyList()
        val toggleRow = if (hidden.isNotEmpty()) listOf(
            CategoryEntity(
                categoryId = SHOW_HIDDEN_CATEGORIES_SENTINEL,
                categoryName = if (showingHiddenVodCategories) "▲ Hide Hidden Categories" else "👁 Show Hidden Categories (${hidden.size})",
                parentId = 0,
                type = "merged_vod_category"
            )
        ) else emptyList()
        return visibleRows + toggleRow + hiddenRows
    }

    // Series-mode equivalents of the two Movies-mode synthetic-category converters above.
    private fun mergedSeriesServersToSynthetic(list: List<com.iptvapp.data.local.entities.MergedSeriesServerSummary>): List<CategoryEntity> {
        val favoritesRow = CategoryEntity(
            categoryId = FAVORITES_SERVER_SENTINEL,
            categoryName = "★ Favorites",
            parentId = 0,
            type = "merged_series_server"
        )
        return listOf(favoritesRow) + list.filter { it.serverIndex != -1 }.map {
            CategoryEntity(
                categoryId = it.serverIndex.toString(),
                categoryName = "${it.serverNickname} (${it.seriesCount})",
                parentId = 0,
                type = "merged_series_server"
            )
        }
    }

    private fun mergedSeriesCategoriesToSynthetic(list: List<com.iptvapp.data.local.entities.MergedSeriesCategorySummary>): List<CategoryEntity> {
        val serverIndex = viewModel.selectedMergedSeriesServerIndex ?: -1
        val hiddenKeys = hiddenMergedSeriesCategoryKeys
        val (hidden, visible) = list.partition { "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}" in hiddenKeys }
        val visibleRows = visible.map {
            CategoryEntity(
                categoryId = "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}",
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.seriesCount})",
                parentId = 0,
                type = "merged_series_category"
            )
        }
        val hiddenRows = if (showingHiddenSeriesCategories) hidden.map {
            CategoryEntity(
                categoryId = "$serverIndex:${it.categoryId ?: NO_CATEGORY_ID}",
                categoryName = "${it.categoryName ?: "Uncategorized"} (${it.seriesCount})",
                parentId = 0,
                type = "merged_series_category"
            )
        } else emptyList()
        val toggleRow = if (hidden.isNotEmpty()) listOf(
            CategoryEntity(
                categoryId = SHOW_HIDDEN_CATEGORIES_SENTINEL,
                categoryName = if (showingHiddenSeriesCategories) "▲ Hide Hidden Categories" else "👁 Show Hidden Categories (${hidden.size})",
                parentId = 0,
                type = "merged_series_category"
            )
        ) else emptyList()
        return visibleRows + toggleRow + hiddenRows
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
                currentMiniServerIndex = channel.serverIndex
                currentMiniMergedStreamId = channel.streamId
                currentMiniIsVod = false
                // Lets the Favorites tab's scroll-to-current-channel work for merged channels
                // too, the same way it already does for primary ones — this was only ever set
                // when playing FROM the Favorites list itself, so playing a merged channel from
                // Providers/Guide/anywhere else left Favorites unable to find it afterward.
                currentMiniCombinedFavoriteId = "${channel.serverIndex}:${channel.streamId}"
                combinedFavoriteAdapter.setCurrentlyPlayingId(currentMiniCombinedFavoriteId)
                mergedChannelAdapter.setCurrentlyPlayingKey("${channel.serverIndex}:${channel.streamId}")
                liveChannelAdapter.setCurrentlyPlayingId(currentMiniCombinedFavoriteId)
                // Persisted (DataStore, survives process death) — same cold-boot-routing
                // reasoning as playInMiniPlayer's setLastPlayedChannel call.
                lifecycleScope.launch { prefs.setLastPlayedChannel(channel.serverIndex, channel.streamId) }
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
                    openPlayer(
                        currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = currentMiniIsVod, resumeMs = currentPos,
                        serverIndex = currentMiniServerIndex, mergedStreamId = currentMiniMergedStreamId
                    )
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

    // Favorites used to require picking "All Favorites"/"Unsorted"/a named folder first
    // (folders assigned manually via long-press "Move to Folder") before seeing any channels
    // — every favorite not manually filed landed in "Unsorted". Replaced with genre chips
    // (GenreClassifier keyword match): every favorite — primary-server or any other configured
    // provider (Providers tab) — is auto-classified by its OWN category name and shown together
    // here, tagged with its server name when it isn't the primary. Combining the two sources
    // means favOrder-based drag-reorder no longer applies (meaningless across servers) — this
    // tab is browse/play only now, same as the Providers tab always was for merged channels.
    private fun showFavorites() {
        activeFavoriteGenre = null
        landscapeShowChannelsMode()
        setGenreFilterVisible(true)
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = combinedFavoriteAdapter
        viewModel.selectFavoriteFolderView(null)
        // Moved in here (rather than left to each call site to remember) since several entry
        // points into this tab — cold boot, returning from PlayerActivity — were calling
        // showFavorites() directly without ever pairing it with a health check, leaving the
        // health dots permanently unpopulated (GONE, not just gray) on those paths.
        viewModel.checkFavoritesHealth()
        viewModel.checkMergedFavoritesHealth()
        pendingScrollToCurrent = true
        lifecycleScope.launch {
            val favorites = viewModel.getCombinedFavoritesSnapshot()
            if (!pendingScrollToCurrent) return@launch
            updateFavoriteGenreChips(favorites)
            combinedFavoriteAdapter.submitList(genreFilterFavorites(favorites))
            pendingScrollToCurrent = false
            if (binding.tabLayout.selectedTabPosition != TAB_FAVORITES) return@launch
            currentMiniCombinedFavoriteId?.let { scrollFavoritesToCombinedId(it) }
        }
    }

    private fun genreFilterFavorites(favorites: List<CombinedFavorite>): List<CombinedFavorite> {
        val genre = activeFavoriteGenre ?: return favorites
        return favorites.filter { GenreClassifier.matches(genre, it.categoryName) }
    }

    private fun updateFavoriteGenreChips(favorites: List<CombinedFavorite>) {
        val favCategoryNames = favorites.mapNotNull { it.categoryName }
        val detected = GenreClassifier.detectGenres(favCategoryNames)
        val horizontalContainer = binding.genreChipContainer
        val verticalContainer = binding.root.findViewById<android.widget.LinearLayout?>(R.id.genreChipContainerVertical)
        horizontalContainer?.removeAllViews()
        verticalContainer?.removeAllViews()
        if (detected.size <= 1) {
            setGenreFilterVisible(false)
            return
        }
        setGenreFilterVisible(true)
        val selectedGenre = activeFavoriteGenre ?: "All"
        for (genre in detected) {
            val selected = (genre == selectedGenre)
            horizontalContainer?.addView(buildFavoriteGenreChip(genre, selected))
            verticalContainer?.addView(buildFavoriteGenreChip(genre, selected, vertical = true))
        }
    }

    private fun buildFavoriteGenreChip(genre: String, selected: Boolean, vertical: Boolean = false): View =
        buildGenreChipView(genre, selected, vertical) {
            activeFavoriteGenre = if (genre == "All") null else genre
            lifecycleScope.launch {
                val favorites = viewModel.getCombinedFavoritesSnapshot()
                updateFavoriteGenreChips(favorites)
                combinedFavoriteAdapter.submitList(genreFilterFavorites(favorites))
            }
        }

    private fun scrollFavoritesToStreamId(streamId: Int) = scrollFavoritesToCombinedId("primary:$streamId")

    private fun scrollFavoritesToCombinedId(id: String) {
        binding.rvChannels.post {
            val pos = combinedFavoriteAdapter.currentList.indexOfFirst { it.id == id }
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

    private fun showGuide() {
        landscapeShowChannelsMode()
        setGenreFilterVisible(false)
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = guideAdapter
        viewModel.loadGuide()
        binding.guideModeRow?.visibility = View.VISIBLE
        binding.btnTimelineViewRow?.setOnClickListener {
            timelineLauncher.launch(Intent(this, com.iptvapp.ui.guide.EpgTimelineActivity::class.java))
        }
    }

    private fun openPlayer(
        url: String, title: String, streamId: Int,
        streamIds: IntArray = viewModel.channels.value.map { it.streamId }.toIntArray(),
        isVod: Boolean = false, resumeMs: Long = 0L,
        serverIndex: Int = -1, mergedStreamId: Int = -1
    ) {
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
                putExtra("server_index", serverIndex)
                putExtra("merged_stream_id", mergedStreamId)
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
            viewModel.combinedLiveCategories.collect { rows ->
                if (binding.tabLayout.selectedTabPosition == TAB_LIVE) {
                    updateGenreChips(rows.map { row ->
                        row.category ?: com.iptvapp.data.local.entities.CategoryEntity(
                            categoryId = row.id, categoryName = row.mergedCategoryName ?: "", parentId = 0, type = "merged_category"
                        )
                    })
                    val filtered = genreFilterLiveRows(rows)
                    liveCategoryAdapter.submitList(filtered)
                    if (filtered.isNotEmpty() && !viewModel.hasSelectedCombinedCategory()) {
                        viewModel.selectCombinedCategory(filtered.first())
                        liveCategoryAdapter.setSelectedId(filtered.first().id)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.combinedFavoriteLiveCategories.collect { favRows ->
                liveCategoryAdapter.submitFavoriteKeys(favRows.map { it.favoriteKey }.toSet())
                if (binding.tabLayout.selectedTabPosition == TAB_CATEGORIES) {
                    liveCategoryAdapter.submitList(favRows)
                    if (favRows.isNotEmpty()) {
                        viewModel.selectCombinedCategory(favRows.first())
                        liveCategoryAdapter.setSelectedId(favRows.first().id)
                    } else {
                        liveChannelAdapter.submitList(emptyList())
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteMergedCategoryKeys.collect { keys ->
                mergedFavoriteCategoryKeys = keys
                // categoryAdapter's own star rendering reads its internal favoriteCategoryIds
                // set (CategoryAdapter.submitFavoriteCategoryIds), separate from the sort-order
                // read of mergedFavoriteCategoryKeys just above — both need feeding or the
                // Providers tab's category stars go dark.
                categoryAdapter.submitFavoriteCategoryIds(keys)
                // If we're currently drilled into a merged server's category list, resort/
                // re-render it now so a newly (un)favorited category moves immediately instead
                // of waiting for the next unrelated recomposition.
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && viewModel.selectedMergedServerIndex != null) {
                    categoryAdapter.submitList(mergedCategoriesToSynthetic(viewModel.mergedCategories.value))
                }
            }
        }
        lifecycleScope.launch {
            viewModel.hiddenMergedVodCategoryKeys.collect { keys ->
                hiddenMergedVodCategoryKeys = keys
                categoryAdapter.submitHiddenCategoryIds(keys)
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.MOVIES &&
                    viewModel.selectedMergedVodServerIndex != null) {
                    categoryAdapter.submitList(mergedVodCategoriesToSynthetic(viewModel.mergedVodCategories.value))
                }
            }
        }
        lifecycleScope.launch {
            viewModel.hiddenMergedSeriesCategoryKeys.collect { keys ->
                hiddenMergedSeriesCategoryKeys = keys
                categoryAdapter.submitHiddenCategoryIds(keys)
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.SERIES &&
                    viewModel.selectedMergedSeriesServerIndex != null) {
                    categoryAdapter.submitList(mergedSeriesCategoriesToSynthetic(viewModel.mergedSeriesCategories.value))
                }
            }
        }
        lifecycleScope.launch {
            // Live/Categories no longer feed channelAdapter (they use liveChannelAdapter/
            // combinedLiveChannels now) — this only still exists to keep EPG text loading for
            // whatever primary channels are in the currently-selected category.
            viewModel.channels.collect { list ->
                if (binding.tabLayout.selectedTabPosition == TAB_FAVORITES) return@collect
                viewModel.loadEpgForChannels(list)
            }
        }
        lifecycleScope.launch {
            viewModel.combinedLiveChannels.collect { rows ->
                if (binding.tabLayout.selectedTabPosition != TAB_LIVE && binding.tabLayout.selectedTabPosition != TAB_CATEGORIES) return@collect
                liveChannelAdapter.submitList(rows)
                viewModel.loadEpgForMergedChannels(rows.mapNotNull { it.mergedChannel })
            }
        }
        lifecycleScope.launch {
            viewModel.combinedFavorites.collect { favorites ->
                updateFavoriteGenreChips(favorites)
                if (binding.tabLayout.selectedTabPosition != TAB_FAVORITES) return@collect
                val filtered = genreFilterFavorites(favorites)
                combinedFavoriteAdapter.submitList(filtered)
                viewModel.loadEpgForChannels(filtered.mapNotNull { (it as? CombinedFavorite.Primary)?.channel })
                viewModel.loadEpgForMergedChannels(filtered.mapNotNull { (it as? CombinedFavorite.Merged)?.channel })
                if (pendingScrollToCurrent && filtered.isNotEmpty()) {
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
                // A live primary channel takes over the combined highlight; streamId == -1 just
                // means "not a primary channel right now" (could be VOD, or nothing playing yet)
                // — don't clobber a merged channel's highlight in that case, since playing a
                // merged channel is exactly how currentMiniCombinedFavoriteId last got set.
                if (streamId >= 0) {
                    currentMiniCombinedFavoriteId = "primary:$streamId"
                    combinedFavoriteAdapter.setCurrentlyPlayingId(currentMiniCombinedFavoriteId)
                    mergedChannelAdapter.setCurrentlyPlayingKey(null)
                    liveChannelAdapter.setCurrentlyPlayingId(currentMiniCombinedFavoriteId)
                }
                if (pendingScrollToCurrent && streamId >= 0 && combinedFavoriteAdapter.currentList.isNotEmpty() &&
                    binding.tabLayout.selectedTabPosition == TAB_FAVORITES) {
                    pendingScrollToCurrent = false
                    scrollFavoritesToStreamId(streamId)
                }
            }
        }
        // combinedFavoriteAdapter's EPG/health maps are string-keyed ("primary:<id>" or
        // "<serverIndex>:<id>") to cover both sources at once — each collector below only owns
        // its half of the key space, so re-derive the union from both StateFlows' latest values
        // rather than trying to patch just the changed half in isolation.
        fun republishCombinedEpgText() {
            val merged = viewModel.channelEpgText.value.mapKeys { (id, _) -> "primary:$id" } +
                viewModel.mergedEpgText.value
            combinedFavoriteAdapter.submitEpgText(merged)
        }
        fun republishCombinedEpgNextText() {
            val merged = viewModel.channelEpgNextText.value.mapKeys { (id, _) -> "primary:$id" }
            combinedFavoriteAdapter.submitEpgNextText(merged)
        }
        fun republishCombinedHealth() {
            val merged = viewModel.channelHealth.value.mapKeys { (id, _) -> "primary:$id" } +
                viewModel.mergedHealth.value
            combinedFavoriteAdapter.submitHealth(merged)
        }
        // Same "primary:<id>" / "<serverIndex>:<id>" key-union pattern as combinedFavoriteAdapter
        // above, for the Live tab's own combined list.
        fun republishLiveEpgText() {
            val merged = viewModel.channelEpgText.value.mapKeys { (id, _) -> "primary:$id" } +
                viewModel.mergedEpgText.value
            liveChannelAdapter.submitEpgText(merged)
        }
        fun republishLiveEpgNextText() {
            val merged = viewModel.channelEpgNextText.value.mapKeys { (id, _) -> "primary:$id" }
            liveChannelAdapter.submitEpgNextText(merged)
        }
        fun republishLiveHealth() {
            val merged = viewModel.channelHealth.value.mapKeys { (id, _) -> "primary:$id" } +
                viewModel.mergedHealth.value
            liveChannelAdapter.submitHealth(merged)
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect {
                channelAdapter.submitEpgText(it)
                republishCombinedEpgText()
                republishLiveEpgText()
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgProgress.collect {
                channelAdapter.submitEpgProgress(it)
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgNextText.collect {
                republishCombinedEpgNextText()
                republishLiveEpgNextText()
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
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.LIVE) {
                    mergedChannelAdapter.submitList(list)
                    viewModel.loadEpgForMergedChannels(list)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedVod.collect { list ->
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.MOVIES) {
                    mergedVodAdapter.submitList(list)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedSeries.collect { list ->
                if (binding.tabLayout.selectedTabPosition == TAB_PROVIDERS && providersMode == ProvidersMode.SERIES) {
                    mergedSeriesAdapter.submitList(list)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.mergedEpgText.collect {
                mergedChannelAdapter.submitEpgText(it)
                republishCombinedEpgText()
            }
        }
        lifecycleScope.launch {
            viewModel.mergedHealth.collect {
                mergedChannelAdapter.submitHealth(it)
                republishCombinedHealth()
            }
        }
        lifecycleScope.launch {
            viewModel.channelHealth.collect {
                channelAdapter.submitHealth(it)
                republishCombinedHealth()
            }
        }
        lifecycleScope.launch {
            viewModel.externalPlayer.collect { externalPlayerChoice = it }
        }
        // Auto-play the most recent channel as soon as watch history is available.
        // This handles the case where getRecentChannel() returned null during initMiniPlayer
        // because the DB hadn't emitted yet (e.g. after a fresh channel sync).
        // currentMiniStreamId == -1 alone isn't enough to mean "nothing playing yet" — a
        // merged-provider channel legitimately uses -1 too (it has no row in the primary
        // channels table), so this used to stomp a cold-boot-restored merged channel with the
        // wrong primary one the moment recentChannels next emitted. Also require
        // currentMiniServerIndex == -1 (the merged-channel sentinel) so a merged channel
        // already resumed into the mini player is left alone.
        lifecycleScope.launch {
            viewModel.recentChannels.collect { channels ->
                if (currentMiniStreamId == -1 && currentMiniServerIndex == -1 && channels.isNotEmpty() && miniPlayer != null) {
                    playInMiniPlayer(channels.first())
                }
            }
        }
    }

    // Live tab's unified long-press menu (LiveChannelAdapter combines primary + every merged
    // provider into one list) — same bulk-select shape as showChannelActionsMenuDialog/
    // showMergedChannelActionsMenu, but sharing one selection set (bulkSelectedLiveIds) keyed
    // by LiveChannelRow.id so a bulk-favorite pass can mix primary and merged channels at once.
    private fun showLiveRowActionsMenu(row: LiveChannelRow) {
        val ch = row.channel
        val merged = row.mergedChannel
        val title = if (ch != null) ch.name else "${merged!!.name} · ${merged.serverNickname}"
        val options = mutableListOf(
            "Add to Favorites".let { if (row.isFavorite) "Remove from Favorites" else it },
            if (bulkSelectedLiveIds.contains(row.id)) "Deselect (bulk)" else "Select (bulk add to favorites)"
        )
        if (ch != null) options.add("Hide Channel")
        if (bulkSelectLiveMode && bulkSelectedLiveIds.isNotEmpty()) {
            options.add(0, "✓ Add ${bulkSelectedLiveIds.size} selected to favorites")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, i ->
                when (options[i]) {
                    "Add to Favorites", "Remove from Favorites" -> {
                        viewModel.toggleLiveRowFavorite(row)
                        Toast.makeText(this, if (row.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
                    }
                    "Select (bulk add to favorites)" -> {
                        bulkSelectLiveMode = true
                        bulkSelectedLiveIds.add(row.id)
                        liveChannelAdapter.submitBulkSelection(bulkSelectedLiveIds.toSet())
                        Toast.makeText(this, "${bulkSelectedLiveIds.size} selected — tap more channels, or wait to add them", Toast.LENGTH_SHORT).show()
                        bulkSelectHandler.removeCallbacks(bulkSelectLiveIdleRunnable)
                        bulkSelectHandler.postDelayed(bulkSelectLiveIdleRunnable, 8000)
                    }
                    "Deselect (bulk)" -> {
                        bulkSelectedLiveIds.remove(row.id)
                        liveChannelAdapter.submitBulkSelection(bulkSelectedLiveIds.toSet())
                        if (bulkSelectedLiveIds.isEmpty()) {
                            bulkSelectLiveMode = false
                            bulkSelectHandler.removeCallbacks(bulkSelectLiveIdleRunnable)
                        }
                    }
                    "Hide Channel" -> if (ch != null) {
                        viewModel.hideChannel(ch.streamId)
                        Toast.makeText(this, "${ch.name} hidden. Unhide in Settings → Display.", Toast.LENGTH_SHORT).show()
                    }
                    else -> if (options[i].startsWith("✓ Add")) {
                        commitBulkLiveFavorites()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                        channelAdapter.submitBulkSelection(bulkSelectedIds.toSet())
                        Toast.makeText(this, "${bulkSelectedIds.size} selected — tap more channels, or wait to move/add them", Toast.LENGTH_SHORT).show()
                        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
                        bulkSelectHandler.postDelayed(bulkSelectIdleRunnable, 8000)
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
                    "Channels Like This" -> showSimilarChannelsSheet(channel)
                    else -> if (options[i].startsWith("✓ Add")) {
                        viewModel.bulkAddFavorites(bulkSelectedIds.toList())
                        Toast.makeText(this, "Added ${bulkSelectedIds.size} channels to favorites", Toast.LENGTH_SHORT).show()
                        clearBulkSelection()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearBulkSelection() {
        bulkSelectedIds.clear()
        bulkSelectMode = false
        bulkSelectHandler.removeCallbacks(bulkSelectIdleRunnable)
        channelAdapter.submitBulkSelection(emptySet())
    }

    // Long-press entry point for Movies/Series category bulk-hide — turns on checkbox mode
    // immediately (same as channel bulk-select's long-press), rather than opening a dialog per
    // tap. Plain taps on other categories toggle their checkbox (see onCategoryClick's bulk
    // branch); an 8s idle timer then prompts Hide Selected / Unselect All.
    private fun showCategoryBulkHideDialog(category: CategoryEntity, isSeries: Boolean) {
        categoryBulkSelectMode = true
        categoryBulkSelectIsSeries = isSeries
        bulkSelectedCategoryIds.add(category.categoryId)
        categoryAdapter.submitBulkSelection(bulkSelectedCategoryIds.toSet())
        Toast.makeText(this, "${bulkSelectedCategoryIds.size} selected — tap more categories to hide", Toast.LENGTH_SHORT).show()
        bulkSelectHandler.removeCallbacks(bulkSelectCategoryIdleRunnable)
        bulkSelectHandler.postDelayed(bulkSelectCategoryIdleRunnable, 8000)
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
        val key = "${channel.serverIndex}:${channel.streamId}"
        val options = mutableListOf(
            "Play Fullscreen",
            if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites",
            if (bulkSelectedMergedKeys.contains(key)) "Deselect (bulk)" else "Select (bulk add to favorites)"
        )
        if (channel.isFavorite) options.add("Move to Folder")
        if (bulkSelectMergedMode && bulkSelectedMergedKeys.isNotEmpty()) {
            options.add(0, "✓ Add ${bulkSelectedMergedKeys.size} selected to favorites")
        }
        AlertDialog.Builder(this)
            .setTitle("${channel.name} · ${channel.serverNickname}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Play Fullscreen" -> lifecycleScope.launch {
                        try {
                            val url = viewModel.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                            openPlayer(url, "${channel.name} · ${channel.serverNickname}", -1, serverIndex = channel.serverIndex, mergedStreamId = channel.streamId)
                        } catch (_: Exception) {
                            Toast.makeText(this@HomeActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "${bulkSelectedMergedKeys.size} selected — tap more channels, or wait to add them", Toast.LENGTH_SHORT).show()
                        bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                        bulkSelectHandler.postDelayed(bulkSelectMergedIdleRunnable, 8000)
                    }
                    "Deselect (bulk)" -> {
                        bulkSelectedMergedKeys.remove(key)
                        mergedChannelAdapter.submitBulkSelection(bulkSelectedMergedKeys.toSet())
                        if (bulkSelectedMergedKeys.isEmpty()) {
                            bulkSelectMergedMode = false
                            bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                        }
                    }
                    "Move to Folder" -> showMoveToFolderDialog(channel)
                    else -> if (options[which].startsWith("✓ Add")) {
                        bulkSelectHandler.removeCallbacks(bulkSelectMergedIdleRunnable)
                        viewModel.bulkAddMergedFavorites(bulkSelectedMergedKeys.toSet())
                        Toast.makeText(this, "Added ${bulkSelectedMergedKeys.size} channels to favorites", Toast.LENGTH_SHORT).show()
                        clearBulkSelectionMerged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    // Long-press "Series" tab — the series equivalent of showUpNextTicker(): a single feed of
    // the next unwatched/in-progress episode across every series with any watch history,
    // instead of opening each show individually to check where you left off.
    private fun showContinueSeriesTicker() {
        lifecycleScope.launch {
            val entries = viewModel.getContinueSeriesTicker()
            if (entries.isEmpty()) {
                Toast.makeText(this@HomeActivity, "No in-progress series yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val inflater = layoutInflater
            val rv = androidx.recyclerview.widget.RecyclerView(this@HomeActivity).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@HomeActivity)
                setPadding(0, 8, 0, 8)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("Continue Watching")
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
                            startActivity(Intent(this@HomeActivity, PlayerActivity::class.java).apply {
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
}