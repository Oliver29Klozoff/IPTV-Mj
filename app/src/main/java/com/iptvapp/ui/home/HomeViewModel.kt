package com.iptvapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.ui.guide.GuideRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun EpgEntity.startMs() = if (startTimestamp < 100_000_000_000L) startTimestamp * 1000L else startTimestamp
private fun EpgEntity.stopMs()  = if (stopTimestamp  < 100_000_000_000L) stopTimestamp  * 1000L else stopTimestamp
private fun List<EpgEntity>.nowProgram(): EpgEntity? {
    val now = System.currentTimeMillis()
    return firstOrNull { it.startMs() <= now && it.stopMs() > now }
}
private fun List<EpgEntity>.nextProgram(current: EpgEntity?): EpgEntity? {
    if (current == null) return null
    return firstOrNull { it.startMs() > current.stopMs() }
}

// One tap-through result from searching every configured provider (primary + every extraServer)
// at once, across every content type — unlike each tab's own search (which only ever looks at
// whatever provider/content type that tab already had selected). providerLabel is the primary
// provider's nickname (or "Primary" if unset) for primary rows, or the merged row's own
// serverNickname for extraServer rows — always non-blank so results can always show which
// provider they came from, per the original ask.
sealed class GlobalSearchResult {
    abstract val providerLabel: String
    data class Channel(val entity: ChannelEntity, override val providerLabel: String) : GlobalSearchResult()
    data class Vod(val entity: VodEntity, override val providerLabel: String) : GlobalSearchResult()
    data class Series(val entity: SeriesEntity, override val providerLabel: String) : GlobalSearchResult()
    data class MergedChannel(val entity: com.iptvapp.data.local.entities.MergedChannelEntity) : GlobalSearchResult() {
        override val providerLabel get() = entity.serverNickname
    }
    data class MergedVod(val entity: com.iptvapp.data.local.entities.MergedVodEntity) : GlobalSearchResult() {
        override val providerLabel get() = entity.serverNickname
    }
    data class MergedSeries(val entity: com.iptvapp.data.local.entities.MergedSeriesEntity) : GlobalSearchResult() {
        override val providerLabel get() = entity.serverNickname
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val prefs: PreferencesManager
) : ViewModel() {

    // Plain fields, not StateFlow — this only needs to survive HomeActivity being recreated
    // on rotation (the ViewModel outlives that), not to be observed. HomeActivity reads this
    // once in onCreate to restore what was in the mini player, and writes it in onPause
    // before a rotation-triggered recreation would otherwise reset the mini player to
    // whatever channel was last watched.
    data class MiniPlayerState(
        val url: String, val title: String, val streamId: Int, val isVod: Boolean, val positionMs: Long,
        // -1/-1 means "primary provider channel" (same sentinel convention as everywhere else
        // merged-channel identity is tracked) — a merged channel needs both to resolve back to
        // its folder after a rotation-triggered HomeActivity recreation.
        val serverIndex: Int = -1, val mergedStreamId: Int = -1
    )
    var savedMiniPlayerState: MiniPlayerState? = null

    private val _liveCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val liveCategories: StateFlow<List<CategoryEntity>> = _liveCategories

    private val _channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val channels: StateFlow<List<ChannelEntity>> = _channels

    private val _favoriteLiveCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val favoriteLiveCategories: StateFlow<List<CategoryEntity>> = _favoriteLiveCategories

    // Combined Favorites tab: primary-server favorites plus any other configured provider's
    // (Providers/merged-channel) favorites, shown together and auto-classified by genre. Kept as
    // an always-live StateFlow (populated in loadAll(), like liveCategories) rather than a Flow
    // built per-call, so both platforms just collect one ready value instead of each re-deriving
    // the same cold-start timing race that the primary-only genre chips hit before this existed.
    private val _combinedFavorites = MutableStateFlow<List<CombinedFavorite>>(emptyList())
    val combinedFavorites: StateFlow<List<CombinedFavorite>> = _combinedFavorites

    private val _vodCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val vodCategories: StateFlow<List<CategoryEntity>> = _vodCategories

    private val _vod = MutableStateFlow<List<VodEntity>>(emptyList())
    val vod: StateFlow<List<VodEntity>> = _vod

    private val _series = MutableStateFlow<List<SeriesEntity>>(emptyList())
    val series: StateFlow<List<SeriesEntity>> = _series

    private val _guideRows = MutableStateFlow<List<GuideRow>>(emptyList())
    val guideRows: StateFlow<List<GuideRow>> = _guideRows

    private val _continueWatching = MutableStateFlow<List<VodEntity>>(emptyList())
    val continueWatching: StateFlow<List<VodEntity>> = _continueWatching

    private val _inProgressSeries = MutableStateFlow<List<com.iptvapp.data.local.dao.InProgressSeriesRow>>(emptyList())
    val inProgressSeries: StateFlow<List<com.iptvapp.data.local.dao.InProgressSeriesRow>> = _inProgressSeries

    // User-created groups for organizing favorites (e.g. "Sports", "News") — same drill-down
    // shape as Live/Movies categories, but user-named instead of provider-supplied.
    private val _favoriteFolders = MutableStateFlow<List<com.iptvapp.data.local.entities.FavoriteFolderEntity>>(emptyList())
    val favoriteFolders: StateFlow<List<com.iptvapp.data.local.entities.FavoriteFolderEntity>> = _favoriteFolders

    private val _favoriteFolderCounts = MutableStateFlow<List<com.iptvapp.data.local.dao.FavoriteFolderCount>>(emptyList())
    val favoriteFolderCounts: StateFlow<List<com.iptvapp.data.local.dao.FavoriteFolderCount>> = _favoriteFolderCounts

    // null = "All Favorites" (every favorite, unfiltered — the original flat view),
    // -1 = "Unsorted" (favorites not yet assigned to any folder), >=0 = a real folder id.
    var selectedFavoriteFolder: Int? = null; private set

    // Reuses the same channelJob every other Favorites/Live channel-list load already uses
    // (searchFavorites, showFavoriteChannels) so switching between them cancels correctly.
    // Folder-scoped Favorites views (Unsorted / a named folder) used to only ever populate
    // _channels (primary-provider only) — merged/secondary-provider favorites only showed up
    // in the unfiltered "All Favorites" view (loadAll()'s separate combine block into
    // _combinedFavorites). Rebuilt to populate _combinedFavorites here too, since merged
    // channels already support favoriteFolderId the same way primary channels do.
    fun selectFavoriteFolderView(folderId: Int?) {
        inFavoritesMode = true
        selectedFavoriteFolder = folderId
        searchJob?.cancel()
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            val primaryFlow = when (folderId) {
                null -> repository.getFavoriteChannels()
                -1 -> repository.getUnfiledFavorites()
                else -> repository.getFavoritesInFolder(folderId)
            }
            val mergedFlow = when (folderId) {
                null -> repository.getMergedAllFavorites()
                -1 -> repository.getMergedUnfiledFavorites()
                else -> repository.getMergedFavoritesInFolder(folderId)
            }
            combine(primaryFlow, mergedFlow, _liveCategories) { primary, merged, cats ->
                val namesById = cats.associate { it.categoryId to it.categoryName }
                _channels.value = primary
                primary.map { CombinedFavorite.Primary(it, namesById[it.categoryId]) } +
                    merged.map { CombinedFavorite.Merged(it) }
            }.collectLatest { _combinedFavorites.value = it }
        }
    }

    fun createFavoriteFolder(name: String) {
        viewModelScope.launch { repository.createFavoriteFolder(name) }
    }

    suspend fun createFavoriteFolderAndGetId(name: String): Int = repository.createFavoriteFolder(name)

    fun renameFavoriteFolder(id: Int, name: String) {
        viewModelScope.launch { repository.renameFavoriteFolder(id, name) }
    }

    fun deleteFavoriteFolder(id: Int) {
        viewModelScope.launch { repository.deleteFavoriteFolder(id) }
    }

    fun setChannelFavoriteFolder(streamId: Int, folderId: Int?) {
        viewModelScope.launch { repository.setChannelFavoriteFolder(streamId, folderId) }
    }

    fun setChannelsFavoriteFolder(streamIds: List<Int>, folderId: Int?) {
        viewModelScope.launch { streamIds.forEach { repository.setChannelFavoriteFolder(it, folderId) } }
    }

    // "All Providers" is a 3-level drill-down (server -> category -> channels), same shape as
    // Live's category drilldown — a single provider can itself have tens of thousands of
    // channels, so neither a cross-server nor a per-server flat list is usable.
    private val _mergedServers = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedServerSummary>>(emptyList())
    val mergedServers: StateFlow<List<com.iptvapp.data.local.entities.MergedServerSummary>> = _mergedServers

    private val _mergedCategories = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedCategorySummary>>(emptyList())
    val mergedCategories: StateFlow<List<com.iptvapp.data.local.entities.MergedCategorySummary>> = _mergedCategories

    private val _mergedChannels = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedChannelEntity>>(emptyList())
    val mergedChannels: StateFlow<List<com.iptvapp.data.local.entities.MergedChannelEntity>> = _mergedChannels

    private var mergedCategoriesJob: Job? = null
    private var mergedChannelsJob: Job? = null
    var selectedMergedServerIndex: Int? = null; private set
    // Tracks whether selectMergedCategory has actually been called (as opposed to just being
    // on the category-list level for a selected server) — nullable String alone can't
    // distinguish "no category chosen yet" from "chose the null/uncategorized category", so a
    // separate flag is needed for the Providers tab to know whether to restore to the
    // channel-list level or just the category-list level when re-selected.
    var selectedMergedCategoryId: String? = null; private set
    var hasMergedCategorySelected: Boolean = false; private set

    // Guards against two overlapping refreshMergedChannels() calls — e.g. the cold-start auto-
    // refresh (HomeActivity.onCreate, which also re-fires on every orientation-triggered
    // recreation) still in flight when the user impatiently taps the manual refresh button, or
    // rotates again. Each call independently snapshots current favorites (getUserData()) before
    // its network fetch, then clearForServer+upsertAll once done — two overlapping calls race
    // that snapshot-fetch-write cycle, and whichever finishes last silently overwrites the
    // other's more up-to-date favorite state with its own (now-stale) snapshot. This is the
    // confirmed root cause of merged-provider favorites occasionally reverting after an update
    // (the exact moment cold-start refresh and reopening the app coincide). Joining an in-flight
    // call instead of starting a second one removes the race entirely, and avoids a wasted
    // duplicate network fetch as a side benefit.
    private var mergedChannelsRefreshJob: Job? = null

    /** Fetches every configured server's live channels in parallel for the "All Providers"
     * browse-and-play view — called either from a manual Refresh tap, or from HomeActivity's
     * cold-start check (which only calls this when the last refresh is stale, via
     * [PreferencesManager.lastMergedChannelsRefresh]). Stamps the refresh time on completion
     * either way, since both call sites count as "freshened the cache" equally. */
    fun refreshMergedChannels(targetServerIndex: Int? = null) {
        mergedChannelsRefreshJob?.takeIf { it.isActive }?.let { return }
        // _syncProgress drives the same visible blue bar + "N/Total" text the primary
        // provider's first-run sync already shows — refreshing a merged/secondary provider
        // previously only toggled the small spinner (_loading), easy to miss and with no count
        // at all. Each merged-provider fetch is one bulk API call per server (not paginated),
        // so "items" here means running total across servers, and progress advances per-server-
        // completed rather than per-item within a server — coarser than the primary provider's
        // true streamed progress, but still shows real movement instead of a bare spinner.
        mergedChannelsRefreshJob = viewModelScope.launch {
            _loading.value = true
            _lastMergedChannelsRefreshError.value = null
            try {
                val errors = repository.refreshMergedChannels(targetServerIndex) { completed, total, itemsSoFar ->
                    _syncProgress.value = "Loading channels… $completed/$total providers ($itemsSoFar channels)" to (completed * 100 / total.coerceAtLeast(1))
                }
                if (errors.isNotEmpty()) {
                    _lastMergedChannelsRefreshError.value = errors.values.first()
                }
                prefs.setLastMergedChannelsRefresh(System.currentTimeMillis())
            } finally {
                _syncProgress.value = null
                _loading.value = false
            }
        }
    }

    // ─── Combined Live tab (primary + every configured secondary provider) ──────────────────
    // Merges the primary provider's categories with every OTHER configured provider's
    // categories into one flat list (each category stays its own row, tagged by provider for
    // color-coding — categories are never combined across providers even if same-named).
    private val _combinedLiveCategories = MutableStateFlow<List<LiveCategoryRow>>(emptyList())
    val combinedLiveCategories: StateFlow<List<LiveCategoryRow>> = _combinedLiveCategories

    private val _combinedFavoriteLiveCategories = MutableStateFlow<List<LiveCategoryRow>>(emptyList())
    val combinedFavoriteLiveCategories: StateFlow<List<LiveCategoryRow>> = _combinedFavoriteLiveCategories

    private var combinedLiveCategoriesJob: Job? = null

    /** Rebuilds the combined category list whenever the primary's categories, the set of
     * configured servers, or any individual server's categories change. Re-launched (not just
     * combine()'d once) each time the server list itself changes, since the number of merged
     * category flows to combine is dynamic — simplest correct way to handle "combine N flows
     * where N itself changes" without a custom Flow operator. */
    private fun startCombinedLiveCategories() {
        combinedLiveCategoriesJob?.cancel()
        combinedLiveCategoriesJob = viewModelScope.launch {
            repository.getMergedServerSummaries().collectLatest { allServers ->
                // A disabled provider's merged_channels rows are deliberately left untouched on
                // disable (see SettingsActivity's provider-enable toggle) so its favorites/
                // folders survive being re-enabled later — this is the one merged-data read that
                // doesn't already go through XtreamRepository.allConfiguredServers()'s enabled-
                // only filter, so it has to filter here instead of relying on the rows being gone.
                val enabledIndices = prefs.getExtraServersWithNick()
                    .mapIndexedNotNull { i, s -> if (s.getOrElse(5) { "true" }.toBoolean()) i else null }
                    .toSet()
                val servers = allServers.filter { it.serverIndex in enabledIndices }
                val mergedCategoryFlows = servers.map { server ->
                    repository.getMergedCategorySummaries(server.serverIndex)
                        .combine(prefs.usaOnlyChannels) { cats, usaOnly ->
                            (if (usaOnly) cats.filter { isUsCategory(it.categoryName) } else cats)
                                .map { LiveCategoryRow.fromMerged(server.serverIndex, it) }
                        }
                }
                if (mergedCategoryFlows.isEmpty()) {
                    _liveCategories.collectLatest { primary ->
                        val rows = primary.map { LiveCategoryRow.fromPrimary(it) }
                        _combinedLiveCategories.value = sortCombinedCategories(rows)
                        _combinedFavoriteLiveCategories.value = filterFavoriteCombinedCategories(rows)
                    }
                } else {
                    combine(mergedCategoryFlows) { arrays -> arrays.toList().flatten() }
                        .combine(_liveCategories) { mergedRows, primaryCats ->
                            primaryCats.map { LiveCategoryRow.fromPrimary(it) } + mergedRows
                        }
                        .collectLatest { rows ->
                            _combinedLiveCategories.value = sortCombinedCategories(rows)
                            _combinedFavoriteLiveCategories.value = filterFavoriteCombinedCategories(rows)
                        }
                }
            }
        }
    }

    private suspend fun sortCombinedCategories(rows: List<LiveCategoryRow>): List<LiveCategoryRow> {
        val favPrimary = repository.getFavoriteLiveCategoryIds().first()
        val favMerged = repository.getFavoriteMergedCategoryIds().first()
        return rows.sortedByDescending { it.favoriteKey in favPrimary || it.favoriteKey in favMerged }
    }

    private suspend fun filterFavoriteCombinedCategories(rows: List<LiveCategoryRow>): List<LiveCategoryRow> {
        val favPrimary = repository.getFavoriteLiveCategoryIds().first()
        val favMerged = repository.getFavoriteMergedCategoryIds().first()
        return rows.filter { it.favoriteKey in favPrimary || it.favoriteKey in favMerged }
    }

    private var selectedCombinedCategory: LiveCategoryRow? = null
    private val _combinedLiveChannels = MutableStateFlow<List<LiveChannelRow>>(emptyList())
    val combinedLiveChannels: StateFlow<List<LiveChannelRow>> = _combinedLiveChannels
    private var combinedLiveChannelsJob: Job? = null

    fun hasSelectedCombinedCategory(): Boolean = selectedCombinedCategory != null
    fun selectedCombinedCategoryRow(): LiveCategoryRow? = selectedCombinedCategory

    /** Selecting a combined-list category row resolves to that ONE provider's channels —
     * categories are never merged across providers (see LiveCategoryRow kdoc), so this is just
     * a dispatch to the right existing per-provider channel query, not new fetch logic. */
    fun selectCombinedCategory(row: LiveCategoryRow) {
        selectedCombinedCategory = row
        // Keep the plain primary-only _channels/selectedLiveCategoryId in sync too — a handful
        // of other call sites (fullscreen multi-channel streamIds, showWhatsOnNow's live-guide
        // dialog) still read viewModel.channels.value directly and are out of scope for this
        // merge; this keeps them working exactly as before without duplicating their logic here.
        if (row.category != null) selectedLiveCategoryId = row.category.categoryId
        combinedLiveChannelsJob?.cancel()
        combinedLiveChannelsJob = viewModelScope.launch {
            val flow = if (row.category != null) {
                repository.getChannelsByCategory(row.category.categoryId)
                    .map { list -> list.map { LiveChannelRow(channel = it) } }
            } else {
                repository.getMergedChannelsByCategory(row.serverIndex, row.mergedCategoryId)
                    .map { list -> list.map { LiveChannelRow(mergedChannel = it) } }
            }
            flow.collectLatest { rows ->
                _combinedLiveChannels.value = applySortToLiveRows(rows)
                if (row.category != null) _channels.value = applySortToChannels(rows.mapNotNull { it.channel })
            }
        }
    }

    private fun applySortToLiveRows(rows: List<LiveChannelRow>): List<LiveChannelRow> {
        // Only meaningful for primary rows today (viewCount/lastWatched/reliability are all
        // primary-only tracked data) — merged rows just keep provider order within the list,
        // same as the old Providers tab did.
        return when (_channelSort.value) {
            ChannelSort.DEFAULT -> rows
            ChannelSort.NAME_AZ -> rows.sortedBy { it.name.lowercase() }
            ChannelSort.MOST_WATCHED -> rows.sortedByDescending { it.channel?.viewCount ?: -1 }
            ChannelSort.RECENTLY_WATCHED -> rows.sortedByDescending { it.channel?.lastWatched ?: 0L }
            ChannelSort.MOST_RELIABLE -> rows.sortedByDescending { r -> r.channel?.let { reliabilityCache[it.streamId] } ?: 100 }
        }
    }

    fun toggleCombinedCategoryFavorite(row: LiveCategoryRow) {
        if (row.category != null) {
            toggleLiveCategoryFavorite(row.category.categoryId)
        } else {
            toggleMergedCategoryFavorite(row.favoriteKey)
        }
    }

    // True while the Providers tab (Live mode) is showing the aggregate "★ Favorites" view —
    // every merged-favorited live channel across ALL configured secondary providers at once,
    // reached by tapping the Favorites entry at the top of the server picker rather than
    // picking a specific provider. See selectMergedAllFavoritesAcrossServers.
    var isViewingMergedFavorites: Boolean = false; private set

    fun resetMergedSelection() {
        selectedMergedServerIndex = null
        selectedMergedCategoryId = null
        hasMergedCategorySelected = false
        selectedMergedFavoriteFolder = null
        isViewingMergedFavorites = false
        mergedCategoriesJob?.cancel()
        mergedChannelsJob?.cancel()
        _mergedCategories.value = emptyList()
        _mergedChannels.value = emptyList()
    }

    // Aggregate favorites across every configured secondary provider at once — no category
    // level (there's nothing to drill into), straight to the flat item list. Reuses the same
    // repository query the Favorites tab's "All Favorites" merged-channel view already uses.
    fun selectMergedAllFavoritesAcrossServers() {
        isViewingMergedFavorites = true
        selectedMergedServerIndex = null
        selectedMergedCategoryId = null
        hasMergedCategorySelected = false
        mergedCategoriesJob?.cancel()
        mergedChannelsJob?.cancel()
        _mergedCategories.value = emptyList()
        mergedChannelsJob = viewModelScope.launch {
            repository.getMergedAllFavorites().collectLatest { _mergedChannels.value = it }
        }
    }

    fun selectMergedServer(serverIndex: Int) {
        isViewingMergedFavorites = false
        selectedMergedServerIndex = serverIndex
        selectedMergedCategoryId = null
        hasMergedCategorySelected = false
        mergedCategoriesJob?.cancel()
        mergedCategoriesJob = viewModelScope.launch {
            repository.getMergedCategorySummaries(serverIndex)
                .combine(prefs.usaOnlyChannels) { categories, usaOnly ->
                    if (usaOnly) categories.filter { isUsCategory(it.categoryName) } else categories
                }
                .collectLatest { _mergedCategories.value = it }
        }
    }

    fun selectMergedCategory(categoryId: String?) {
        val serverIndex = selectedMergedServerIndex ?: return
        selectedMergedCategoryId = categoryId
        hasMergedCategorySelected = true
        mergedChannelsJob?.cancel()
        mergedChannelsJob = viewModelScope.launch {
            repository.getMergedChannelsByCategory(serverIndex, categoryId).collectLatest { _mergedChannels.value = it }
        }
    }

    // Searches across every configured server at once, ignoring whatever server/category is
    // currently drilled into — matches how search already works on every other tab. Also
    // respects "USA Only" so a search doesn't resurface channels the filter is meant to hide.
    fun searchMergedChannels(query: String) {
        mergedChannelsJob?.cancel()
        mergedChannelsJob = viewModelScope.launch {
            repository.searchMergedChannels(query)
                .combine(prefs.usaOnlyChannels) { channels, usaOnly ->
                    if (usaOnly) channels.filter { isUsCategory(it.categoryName) } else channels
                }
                .collectLatest { _mergedChannels.value = it }
        }
    }

    suspend fun getMergedLiveStreamUrl(serverIndex: Int, streamId: Int): String =
        repository.getMergedLiveStreamUrl(serverIndex, streamId)

    // Merged-channel favorites — a separate browse view from the primary Favorites tab (see
    // MergedChannelEntity kdoc), but sharing the same FavoriteFolderEntity rows/counts already
    // exposed above (favoriteFolders). null = "All Favorites", -1 = "Unsorted", >=0 = a folder.
    private val _mergedFavoriteFolderCounts = MutableStateFlow<List<com.iptvapp.data.local.dao.FavoriteFolderCount>>(emptyList())
    val mergedFavoriteFolderCounts: StateFlow<List<com.iptvapp.data.local.dao.FavoriteFolderCount>> = _mergedFavoriteFolderCounts
    var selectedMergedFavoriteFolder: Int? = null; private set

    fun selectMergedFavoriteFolderView(folderId: Int?) {
        selectedMergedFavoriteFolder = folderId
        mergedChannelsJob?.cancel()
        val flow = when (folderId) {
            null -> repository.getMergedAllFavorites()
            -1 -> repository.getMergedUnfiledFavorites()
            else -> repository.getMergedFavoritesInFolder(folderId)
        }
        mergedChannelsJob = viewModelScope.launch {
            flow.collectLatest { _mergedChannels.value = it }
        }
    }

    // Movies-tab equivalent of the merged-channel browse state above — same server -> category
    // -> items drill-down shape, but a separate "mode" within the Providers tab (see
    // HomeActivity's Live/Movies toggle) rather than merged into the primary Movies tab, since
    // VOD catalogs are large enough that combining them the way Live already does would risk
    // real perf/complexity cost for comparatively little benefit at this stage.
    private val _mergedVodServers = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedVodServerSummary>>(emptyList())
    val mergedVodServers: StateFlow<List<com.iptvapp.data.local.entities.MergedVodServerSummary>> = _mergedVodServers

    private val _mergedVodCategories = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedVodCategorySummary>>(emptyList())
    val mergedVodCategories: StateFlow<List<com.iptvapp.data.local.entities.MergedVodCategorySummary>> = _mergedVodCategories

    private val _mergedVod = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedVodEntity>>(emptyList())
    val mergedVod: StateFlow<List<com.iptvapp.data.local.entities.MergedVodEntity>> = _mergedVod

    private var mergedVodCategoriesJob: Job? = null
    private var mergedVodItemsJob: Job? = null
    var selectedMergedVodServerIndex: Int? = null; private set
    var selectedMergedVodCategoryId: String? = null; private set
    private var mergedVodServersJob: Job? = null
    // Same aggregate-favorites-view flag as isViewingMergedFavorites, for Movies mode.
    var isViewingMergedVodFavorites: Boolean = false; private set

    fun startObservingMergedVodServers() {
        if (mergedVodServersJob != null) return
        mergedVodServersJob = viewModelScope.launch {
            repository.getMergedVodServerSummaries().collectLatest { _mergedVodServers.value = it }
        }
    }

    fun resetMergedVodSelection() {
        selectedMergedVodServerIndex = null
        selectedMergedVodCategoryId = null
        isViewingMergedVodFavorites = false
        mergedVodCategoriesJob?.cancel()
        mergedVodItemsJob?.cancel()
        _mergedVodCategories.value = emptyList()
        _mergedVod.value = emptyList()
    }

    fun selectMergedVodAllFavoritesAcrossServers() {
        isViewingMergedVodFavorites = true
        selectedMergedVodServerIndex = null
        selectedMergedVodCategoryId = null
        mergedVodCategoriesJob?.cancel()
        mergedVodItemsJob?.cancel()
        _mergedVodCategories.value = emptyList()
        mergedVodItemsJob = viewModelScope.launch {
            repository.getMergedVodAllFavorites()
                .combine(prefs.englishOnlyMovies) { vod, englishOnly ->
                    if (englishOnly) vod.filter { isEnglishCategory(it.categoryName) } else vod
                }
                .combine(_mergedVodSort) { vod, _ -> applyMergedVodSort(vod) }
                .collectLatest { _mergedVod.value = it }
        }
    }

    fun selectMergedVodServer(serverIndex: Int) {
        isViewingMergedVodFavorites = false
        selectedMergedVodServerIndex = serverIndex
        selectedMergedVodCategoryId = null
        mergedVodCategoriesJob?.cancel()
        mergedVodCategoriesJob = viewModelScope.launch {
            // Same "English Movies & Series Only" setting the primary Movies tab already uses —
            // merged/Providers Movies had no such filter at all before this.
            repository.getMergedVodCategorySummaries(serverIndex)
                .combine(prefs.englishOnlyMovies) { categories, englishOnly ->
                    if (englishOnly) categories.filter { isEnglishCategory(it.categoryName) } else categories
                }
                .collectLatest { _mergedVodCategories.value = it }
        }
    }

    fun selectMergedVodCategory(categoryId: String?) {
        val serverIndex = selectedMergedVodServerIndex ?: return
        selectedMergedVodCategoryId = categoryId
        mergedVodItemsJob?.cancel()
        mergedVodItemsJob = viewModelScope.launch {
            repository.getMergedVodByCategory(serverIndex, categoryId)
                .combine(prefs.englishOnlyMovies) { vod, englishOnly ->
                    if (englishOnly) vod.filter { isEnglishCategory(it.categoryName) } else vod
                }
                .combine(_mergedVodSort) { vod, _ -> applyMergedVodSort(vod) }
                .collectLatest { _mergedVod.value = it }
        }
    }

    fun searchMergedVod(query: String) {
        mergedVodItemsJob?.cancel()
        mergedVodItemsJob = viewModelScope.launch {
            repository.searchMergedVod(query)
                .combine(prefs.englishOnlyMovies) { vod, englishOnly ->
                    if (englishOnly) vod.filter { isEnglishCategory(it.categoryName) } else vod
                }
                .combine(_mergedVodSort) { vod, _ -> applyMergedVodSort(vod) }
                .collectLatest { _mergedVod.value = it }
        }
    }

    fun setMergedVodFavorite(vod: com.iptvapp.data.local.entities.MergedVodEntity, favorite: Boolean) {
        viewModelScope.launch { repository.setMergedVodFavorite(vod, favorite) }
    }

    suspend fun getMergedVodStreamUrl(serverIndex: Int, streamId: Int, containerExtension: String): String =
        repository.getMergedVodStreamUrl(serverIndex, streamId, containerExtension)

    // Same overlapping-refresh race as mergedChannelsRefreshJob above, same fix.
    private var mergedVodRefreshJob: Job? = null

    fun refreshMergedVod(serverIndex: Int? = null, onDone: (errors: Map<Int, String>) -> Unit = {}) {
        mergedVodRefreshJob?.takeIf { it.isActive }?.let { return }
        mergedVodRefreshJob = viewModelScope.launch {
            _loading.value = true
            try {
                val errors = repository.refreshMergedVod(serverIndex) { completed, total, itemsSoFar ->
                    _syncProgress.value = "Loading movies… $completed/$total providers ($itemsSoFar movies)" to (completed * 100 / total.coerceAtLeast(1))
                }
                onDone(errors)
            } finally {
                _syncProgress.value = null
                _loading.value = false
            }
        }
    }

    // Series-mode equivalent of the merged-VOD browse state above — same shape, see
    // MergedSeriesEntity kdoc. Episode browsing itself (season/episode list, playback) happens
    // in SeriesDetailActivity, not here — this ViewModel only owns the server/category/series
    // list drill-down, same division of responsibility as the primary Series tab.
    private val _mergedSeriesServers = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedSeriesServerSummary>>(emptyList())
    val mergedSeriesServers: StateFlow<List<com.iptvapp.data.local.entities.MergedSeriesServerSummary>> = _mergedSeriesServers

    private val _mergedSeriesCategories = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedSeriesCategorySummary>>(emptyList())
    val mergedSeriesCategories: StateFlow<List<com.iptvapp.data.local.entities.MergedSeriesCategorySummary>> = _mergedSeriesCategories

    private val _mergedSeries = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedSeriesEntity>>(emptyList())
    val mergedSeries: StateFlow<List<com.iptvapp.data.local.entities.MergedSeriesEntity>> = _mergedSeries

    private var mergedSeriesCategoriesJob: Job? = null
    private var mergedSeriesItemsJob: Job? = null
    var selectedMergedSeriesServerIndex: Int? = null; private set
    var selectedMergedSeriesCategoryId: String? = null; private set
    private var mergedSeriesServersJob: Job? = null
    // Same aggregate-favorites-view flag as isViewingMergedFavorites, for Series mode.
    var isViewingMergedSeriesFavorites: Boolean = false; private set

    fun startObservingMergedSeriesServers() {
        if (mergedSeriesServersJob != null) return
        mergedSeriesServersJob = viewModelScope.launch {
            repository.getMergedSeriesServerSummaries().collectLatest { _mergedSeriesServers.value = it }
        }
    }

    fun resetMergedSeriesSelection() {
        selectedMergedSeriesServerIndex = null
        selectedMergedSeriesCategoryId = null
        isViewingMergedSeriesFavorites = false
        mergedSeriesCategoriesJob?.cancel()
        mergedSeriesItemsJob?.cancel()
        _mergedSeriesCategories.value = emptyList()
        _mergedSeries.value = emptyList()
    }

    fun selectMergedSeriesAllFavoritesAcrossServers() {
        isViewingMergedSeriesFavorites = true
        selectedMergedSeriesServerIndex = null
        selectedMergedSeriesCategoryId = null
        mergedSeriesCategoriesJob?.cancel()
        mergedSeriesItemsJob?.cancel()
        _mergedSeriesCategories.value = emptyList()
        mergedSeriesItemsJob = viewModelScope.launch {
            repository.getMergedSeriesAllFavorites()
                .combine(prefs.englishOnlyMovies) { series, englishOnly ->
                    if (englishOnly) series.filter { isEnglishCategory(it.categoryName) } else series
                }
                .collectLatest { _mergedSeries.value = it }
        }
    }

    fun selectMergedSeriesServer(serverIndex: Int) {
        isViewingMergedSeriesFavorites = false
        selectedMergedSeriesServerIndex = serverIndex
        selectedMergedSeriesCategoryId = null
        mergedSeriesCategoriesJob?.cancel()
        mergedSeriesCategoriesJob = viewModelScope.launch {
            // Same "English Movies & Series Only" setting the primary Series tab already uses —
            // merged/Providers Series had no such filter at all before this, even though the
            // toggle's own label promised "Series" everywhere.
            repository.getMergedSeriesCategorySummaries(serverIndex)
                .combine(prefs.englishOnlyMovies) { categories, englishOnly ->
                    if (englishOnly) categories.filter { isEnglishCategory(it.categoryName) } else categories
                }
                .collectLatest { _mergedSeriesCategories.value = it }
        }
    }

    fun selectMergedSeriesCategory(categoryId: String?) {
        val serverIndex = selectedMergedSeriesServerIndex ?: return
        selectedMergedSeriesCategoryId = categoryId
        mergedSeriesItemsJob?.cancel()
        mergedSeriesItemsJob = viewModelScope.launch {
            repository.getMergedSeriesByCategory(serverIndex, categoryId)
                .combine(prefs.englishOnlyMovies) { series, englishOnly ->
                    if (englishOnly) series.filter { isEnglishCategory(it.categoryName) } else series
                }
                .collectLatest { _mergedSeries.value = it.sortedByDescending { s -> s.isFavorite } }
        }
    }

    fun searchMergedSeries(query: String) {
        mergedSeriesItemsJob?.cancel()
        mergedSeriesItemsJob = viewModelScope.launch {
            repository.searchMergedSeries(query)
                .combine(prefs.englishOnlyMovies) { series, englishOnly ->
                    if (englishOnly) series.filter { isEnglishCategory(it.categoryName) } else series
                }
                .collectLatest { _mergedSeries.value = it.sortedByDescending { s -> s.isFavorite } }
        }
    }

    fun setMergedSeriesFavorite(series: com.iptvapp.data.local.entities.MergedSeriesEntity, favorite: Boolean) {
        viewModelScope.launch { repository.setMergedSeriesFavorite(series, favorite) }
    }

    // Same overlapping-refresh race as mergedChannelsRefreshJob above, same fix.
    private var mergedSeriesRefreshJob: Job? = null

    fun refreshMergedSeries(serverIndex: Int? = null, onDone: (errors: Map<Int, String>) -> Unit = {}) {
        mergedSeriesRefreshJob?.takeIf { it.isActive }?.let { return }
        mergedSeriesRefreshJob = viewModelScope.launch {
            _loading.value = true
            try {
                val errors = repository.refreshMergedSeries(serverIndex) { completed, total, itemsSoFar ->
                    _syncProgress.value = "Loading series… $completed/$total providers ($itemsSoFar shows)" to (completed * 100 / total.coerceAtLeast(1))
                }
                onDone(errors)
            } finally {
                _syncProgress.value = null
                _loading.value = false
            }
        }
    }

    fun setMergedChannelFavorite(channel: com.iptvapp.data.local.entities.MergedChannelEntity, favorite: Boolean) {
        viewModelScope.launch { repository.setMergedChannelFavorite(channel.serverIndex, channel.streamId, favorite) }
    }

    fun setMergedChannelFolder(channel: com.iptvapp.data.local.entities.MergedChannelEntity, folderId: Int?) {
        viewModelScope.launch {
            repository.setMergedChannelFolder(channel.serverIndex, channel.streamId, folderId)
            // If a favorites folder view is open, re-select it AFTER the write lands so the
            // moved channel visibly leaves/joins the list immediately — the Room flow does
            // re-emit on its own, but re-selecting guarantees it even when the flow conflates
            // an unchanged-list emission, and makes the movement feel instant.
            selectedMergedFavoriteFolder?.let { if (mergedChannelsJob?.isActive == true) selectMergedFavoriteFolderView(it) }
        }
    }

    // ── Merged-channel EPG + health (Providers tab parity with the primary channel list) ──
    // Keyed by "serverIndex:streamId" since bare streamIds collide across servers.
    private val _mergedEpgText = MutableStateFlow<Map<String, String>>(emptyMap())
    val mergedEpgText: StateFlow<Map<String, String>> = _mergedEpgText

    private val _mergedHealth = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val mergedHealth: StateFlow<Map<String, Boolean?>> = _mergedHealth

    private var mergedEpgJob: Job? = null

    /** Now/next text per merged channel. Previously fired one get_short_epg network call per
     * channel (paced 150ms apart) with nothing shown until each one landed — a category of 30+
     * channels could take 10-20+ seconds to finish populating, which read as "Providers Live
     * takes forever to load" even though the channel list itself rendered instantly. Providers
     * Live never used fetchXmltvEpgForMergedServer/the persisted epg_entries cache at all
     * (only the Guide tab did) despite both already existing — this now shows whatever's
     * already cached immediately (same "instant from DB, refresh in background" shape as the
     * primary list's loadEpgForChannels/publishEpgDisplay), then does ONE bulk XMLTV request
     * per distinct server instead of one request per channel. The old per-channel
     * fetchMergedShortEpgText loop is kept as a fallback afterward, still paced, only for
     * whatever channels XMLTV didn't cover — so the rate-limit safety margin is unchanged. */
    fun loadEpgForMergedChannels(channels: List<com.iptvapp.data.local.entities.MergedChannelEntity>) {
        mergedEpgJob?.cancel()
        if (channels.isEmpty()) return
        mergedEpgJob = viewModelScope.launch {
            val pairs = channels.map { it.serverIndex to it.streamId }
            val cached = repository.getEpgForServerStreams(pairs).first()
            if (cached.isNotEmpty()) {
                publishMergedEpgDisplay(channels, cached)
            }

            channels.map { it.serverIndex }.distinct().forEach { serverIndex ->
                launch { repository.fetchXmltvEpgForMergedServer(serverIndex) }
            }
            val refreshed = repository.getEpgForServerStreams(pairs).first()
            if (refreshed.isNotEmpty()) {
                publishMergedEpgDisplay(channels, refreshed)
            }

            // Fallback for whatever channels XMLTV had no match for — same pacing as before.
            val coveredKeys = refreshed.map { "${it.serverIndex}:${it.streamId}" }.toSet()
            channels.take(50).forEach { ch ->
                val key = "${ch.serverIndex}:${ch.streamId}"
                if (key !in coveredKeys && !_mergedEpgText.value.containsKey(key)) {
                    val text = repository.fetchMergedShortEpgText(ch.serverIndex, ch.streamId)
                    if (text != null) _mergedEpgText.value = _mergedEpgText.value + (key to text)
                    kotlinx.coroutines.delay(150)
                }
            }
        }
    }

    /** Formats persisted EpgEntity rows into the same "NOW: x (Ym)  •  NEXT: y" text
     * publishEpgDisplay produces for the primary list, keyed "serverIndex:streamId". */
    private fun publishMergedEpgDisplay(
        channels: List<com.iptvapp.data.local.entities.MergedChannelEntity>,
        epgEntries: List<com.iptvapp.data.local.entities.EpgEntity>
    ) {
        val nowSecs = System.currentTimeMillis() / 1000
        val byKey = epgEntries.groupBy { "${it.serverIndex}:${it.streamId}" }
        val updates = channels.mapNotNull { ch ->
            val key = "${ch.serverIndex}:${ch.streamId}"
            val programs = byKey[key].orEmpty().sortedBy { it.startTimestamp }
            val now = programs.firstOrNull { it.stopTimestamp > nowSecs }
            val next = programs.firstOrNull { it.startTimestamp > (now?.stopTimestamp ?: Long.MAX_VALUE) }
            if (now == null) return@mapNotNull null
            val minutesLeft = ((now.stopTimestamp - nowSecs) / 60).coerceAtLeast(0)
            val timeStr = if (minutesLeft > 0) " (${minutesLeft}m)" else ""
            val text = if (next != null) "NOW: ${now.title}$timeStr  •  NEXT: ${next.title}" else "NOW: ${now.title}$timeStr"
            key to text
        }
        if (updates.isNotEmpty()) _mergedEpgText.value = _mergedEpgText.value + updates
    }

    /** Live HEAD-check of every favorited merged channel, mirroring checkFavoritesHealth() —
     * grey dot while checking, then green/red. Runs when the merged ★ Favorites view opens. */
    fun checkMergedFavoritesHealth() {
        viewModelScope.launch {
            val favorites = repository.getMergedAllFavorites().first()
            _mergedHealth.value = favorites.associate { "${it.serverIndex}:${it.streamId}" to null }
            // Same rate-limit-avoidance pacing as checkFavoritesHealth — see its comment.
            favorites.forEach { ch ->
                launch {
                    val url = repository.getMergedLiveStreamUrl(ch.serverIndex, ch.streamId)
                    val alive = repository.checkStreamHealth(url)
                    _mergedHealth.value = _mergedHealth.value + ("${ch.serverIndex}:${ch.streamId}" to alive)
                }
                kotlinx.coroutines.delay(150)
            }
        }
    }

    private val _channelEpgText = MutableStateFlow<Map<Int, String>>(emptyMap())
    val channelEpgText: StateFlow<Map<Int, String>> = _channelEpgText

    private val _channelEpgProgress = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val channelEpgProgress: StateFlow<Map<Int, Int>> = _channelEpgProgress

    private val _channelEpgNextText = MutableStateFlow<Map<Int, String>>(emptyMap())
    val channelEpgNextText: StateFlow<Map<Int, String>> = _channelEpgNextText

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // refreshMergedChannels() used to just discard the per-server error map it gets back —
    // every call site showed a generic "Refreshing all providers…" toast with no way to tell
    // a real failure (bad/expired credentials, provider returning HTML instead of JSON, a
    // timeout) from success. Exposed here so HomeActivity's refresh toast can show the real
    // reason instead. Null means "no error to show" (either still loading, or the last refresh
    // succeeded for every configured provider).
    private val _lastMergedChannelsRefreshError = MutableStateFlow<String?>(null)
    val lastMergedChannelsRefreshError: StateFlow<String?> = _lastMergedChannelsRefreshError

    /** Null when hidden. Pair of (status text, 0-100 percent) while a large catalog syncs. */
    private val _syncProgress = MutableStateFlow<Pair<String, Int>?>(null)
    val syncProgress: StateFlow<Pair<String, Int>?> = _syncProgress

    val showMovies = prefs.showMovies
    val showSeries = prefs.showSeries
    val showWatching = prefs.showWatching
    val externalPlayer = prefs.externalPlayer
    val preWarmOnFocus = prefs.preWarmOnFocus

    private val _channelHealth = MutableStateFlow<Map<Int, Boolean?>>(emptyMap())
    val channelHealth: StateFlow<Map<Int, Boolean?>> = _channelHealth

    suspend fun recordChannelOutcome(streamId: Int, success: Boolean) =
        repository.recordChannelOutcome(streamId, success)

    suspend fun getReliabilityLabel(streamId: Int): String? =
        repository.getReliabilityLabel(streamId)

    data class UpNextEntry(val channel: ChannelEntity, val title: String, val startTimestamp: Long)

    // A single chronological feed of what's coming up next across ALL favorite channels —
    // most IPTV apps only show "now/next" per channel one at a time, requiring you to check
    // each favorite individually. This merges them so you can see what's worth switching to
    // without hunting channel by channel.
    // Some rows store startTimestamp in seconds, others in milliseconds (a pre-existing
    // inconsistency elsewhere in this codebase's EPG data) — normalize to ms before comparing.
    private fun epgStartMs(e: EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp

    suspend fun getUpNextTicker(): List<UpNextEntry> {
        val favorites = repository.getFavoriteChannels().first()
        if (favorites.isEmpty()) return emptyList()
        val ids = favorites.map { it.streamId }
        val epg = repository.getEpgForStreams(ids).first()
        val nowMs = System.currentTimeMillis()
        val byChannel = favorites.associateBy { it.streamId }
        return epg
            .filter { epgStartMs(it) > nowMs && byChannel.containsKey(it.streamId) }
            .groupBy { it.streamId }
            // Nearest upcoming program per channel only — otherwise one channel with a full
            // day of EPG data would flood the feed and bury every other channel's "next".
            .mapNotNull { (streamId, programs) ->
                val soonest = programs.minByOrNull { epgStartMs(it) } ?: return@mapNotNull null
                UpNextEntry(byChannel.getValue(streamId), soonest.title, epgStartMs(soonest))
            }
            .sortedBy { it.startTimestamp }
    }

    data class ContinueSeriesEntry(
        val series: SeriesEntity,
        val nextSeason: Int,
        val nextEpisode: Int,
        val nextEpisodeTitle: String,
        val episodeId: String,
        val containerExtension: String,
        val resumeMs: Long
    )

    // Companion to getUpNextTicker (live channels) but for series — "what unwatched/in-progress
    // episode should I pick up next" across every series with any watch history, instead of
    // opening each show individually to check. Needs a live per-series episode-list fetch (not
    // just local watch-progress data) to know season/episode ordering, so this is a separate,
    // on-demand entry point rather than folded into the always-available live-channel ticker.
    suspend fun getSeriesEpisodeUrl(episodeId: String, containerExtension: String): String =
        repository.getSeriesEpisodeUrl(episodeId, containerExtension)

    suspend fun getMergedChannelByIndexAndId(serverIndex: Int, streamId: Int) =
        repository.getMergedChannelByIndexAndId(serverIndex, streamId)

    suspend fun getContinueSeriesTicker(): List<ContinueSeriesEntry> = coroutineScope {
        val seriesIds = repository.getSeriesIdsWithProgress().first()
        if (seriesIds.isEmpty()) return@coroutineScope emptyList()

        seriesIds.map { seriesId ->
            async {
                val series = repository.getSeriesById(seriesId) ?: return@async null
                val progress = repository.getEpisodeWatchedForSeries(seriesId)
                if (progress.isEmpty()) return@async null
                // Furthest point reached so far — highest (season, episode) with either a
                // completed-watch marker or any resume position, whichever is further along.
                val furthest = progress
                    .filter { it.watchedAt > 0 || it.watchedMs > 0 }
                    .maxWithOrNull(compareBy({ it.season }, { it.episode }))
                    ?: return@async null

                val info = repository.fetchSeriesInfo(seriesId)
                val episodesBySeason = (info as? com.iptvapp.util.Resource.Success)?.data?.episodes ?: return@async null
                val allEpisodes = episodesBySeason.values.flatten()
                    .sortedWith(compareBy({ it.season }, { it.episodeNum }))
                if (allEpisodes.isEmpty()) return@async null

                val furthestIdx = allEpisodes.indexOfFirst { it.season == furthest.season && it.episodeNum == furthest.episode }
                if (furthestIdx < 0) return@async null
                val furthestIsFinished = furthest.watchedAt > 0
                // Finished the furthest episode reached — pick up at the next one in the
                // series; otherwise resume that same episode where it left off.
                val target = (if (furthestIsFinished) allEpisodes.getOrNull(furthestIdx + 1) else allEpisodes[furthestIdx])
                    ?: return@async null
                val resumeMs = if (furthestIsFinished) 0L else furthest.watchedMs

                ContinueSeriesEntry(
                    series = series,
                    nextSeason = target.season,
                    nextEpisode = target.episodeNum,
                    nextEpisodeTitle = target.title,
                    episodeId = target.id,
                    containerExtension = target.containerExtension,
                    resumeMs = resumeMs
                )
            }
        }.awaitAll().filterNotNull()
    }

    fun checkFavoritesHealth() {
        viewModelScope.launch {
            val favorites = repository.getFavoriteChannels().first()
            // Reset to null (checking) for all favorites
            _channelHealth.value = favorites.associate { it.streamId to null }
            // Firing one check per favorite all at once used to blow through some providers'
            // per-window rate limit (observed: a Cloudflare-fronted provider returning
            // x-ratelimit-remaining: 0 after a burst of requests), after which further requests
            // on that connection got dropped/reset — read by checkStreamHealth as "unhealthy",
            // turning every dot red even though the channels themselves play fine. Same small
            // pacing delay already used for merged-channel EPG fetches (loadEpgForMergedChannels)
            // avoids tripping the limit in the first place.
            favorites.forEach { channel ->
                launch {
                    val url = repository.getLiveStreamUrl(channel.streamId)
                    val alive = repository.checkStreamHealth(url)
                    _channelHealth.value = _channelHealth.value + (channel.streamId to alive)
                    repository.recordChannelOutcome(channel.streamId, alive)
                }
                kotlinx.coroutines.delay(150)
            }
        }
    }

    private var selectedLiveCategoryId: String? = null
    private var selectedVodCategoryId: String? = null
    var inFavoritesMode: Boolean = true
    var lastTabPosition: Int = 0

    private val _currentlyPlayingStreamId = MutableStateFlow<Int>(-1)
    val currentlyPlayingStreamId: StateFlow<Int> = _currentlyPlayingStreamId

    fun setCurrentlyPlaying(streamId: Int) {
        _currentlyPlayingStreamId.value = streamId
    }

    private var channelJob: Job? = null
    private var vodJob: Job? = null
    private var searchJob: Job? = null
    private var seriesSearchJob: Job? = null
    private var guideJob: Job? = null
    private var observerJob: Job? = null

    private fun isUsCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        // Different providers format the same "US" tag differently — some use "US|..." with
        // no spacing, others "US | ..." with spaces around the pipe. Collapsing whitespace
        // around every "|" before matching makes this work across both conventions instead of
        // only the first provider's exact style.
        val n = name.trim().uppercase().replace(Regex("\\s*\\|\\s*"), "|")
        return n.startsWith("US|") || n.contains("|US|")
    }

    // Matched as a whole token (not just a prefix) — avoids matching category names that
    // merely start with those letters, like "ENTERTAINMENT", "ENCORE", or "USA NETWORK".
    // Experimental: depends entirely on the provider's own naming convention, which varies
    // a lot — "US" added after "EN" alone didn't match anything on this provider's catalog.
    private fun isEnglishCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim().uppercase()
        return n.split(Regex("[|\\-\\s:]+")).any {
            it == "EN" || it == "ENG" || it == "ENGLISH" || it == "US" || it == "USA"
        }
    }

    fun loadAll() {
        // Start DB observers immediately so cached data shows at once
        observerJob?.cancel()
        startCombinedLiveCategories()
        observerJob = viewModelScope.launch {
            launch {
                repository.getLiveCategories()
                    .combine(prefs.usaOnlyChannels) { categories, usaOnly ->
                        if (usaOnly) categories.filter { isUsCategory(it.categoryName) }
                        else categories
                    }
                    .collectLatest { filtered ->
                        val favCategoryIds = repository.getFavoriteLiveCategoryIds().first()
                        val sorted = filtered.sortedWith(compareByDescending { it.categoryId in favCategoryIds })
                        _liveCategories.value = sorted
                        updateFavoriteCategories(filtered)
                        if (!inFavoritesMode && filtered.isNotEmpty()) {
                            val currentValid = filtered.any { it.categoryId == selectedLiveCategoryId }
                            if (!currentValid) selectLiveCategory(filtered.first().categoryId)
                        }
                    }
            }
            launch {
                combine(
                    repository.getFavoriteChannels(),
                    repository.getMergedAllFavorites(),
                    _liveCategories
                ) { primary, merged, cats ->
                    val namesById = cats.associate { it.categoryId to it.categoryName }
                    primary.map { CombinedFavorite.Primary(it, namesById[it.categoryId]) } +
                        merged.map { CombinedFavorite.Merged(it) }
                }.collectLatest { _combinedFavorites.value = it }
            }
            launch {
                // "USA Channels Only" is a live-TV concept (categories tagged "US|..." by the
                // provider) — movie/series categories aren't tagged that way at all, so
                // applying the same filter here was wiping out the entire VOD category list
                // whenever the toggle was on.
                repository.getVodCategories()
                    .combine(prefs.englishOnlyMovies) { categories, englishOnly ->
                        if (englishOnly) categories.filter { isEnglishCategory(it.categoryName) }
                        else categories
                    }
                    .collectLatest { _vodCategories.value = it }
            }
            launch {
                repository.getAllVod().collectLatest { _vod.value = it }
            }
            launch {
                // Series has no per-category browsing UI (unlike VOD), so unlike the VOD
                // categories filter above, this filters the series list directly using each
                // series' own categoryId looked up against series categories' names.
                combine(
                    repository.getAllSeries(), repository.getSeriesCategories(), prefs.englishOnlyMovies
                ) { series, categories, englishOnly ->
                    if (!englishOnly) return@combine series
                    val englishCategoryIds = categories.filter { isEnglishCategory(it.categoryName) }
                        .map { it.categoryId }.toSet()
                    series.filter { it.categoryId in englishCategoryIds }
                }.collectLatest { _series.value = it }
            }
            launch {
                repository.getInProgressVod().collectLatest { _continueWatching.value = it }
            }
            launch {
                repository.getInProgressSeries().collectLatest { _inProgressSeries.value = it }
            }
            launch {
                repository.getSeriesIdsWithProgress().collectLatest { seriesIdsWithProgress = it.toSet() }
            }
            launch {
                repository.getRecentChannels().collectLatest { _recentChannels.value = it }
            }
            launch {
                repository.getMergedServerSummaries().collectLatest { _mergedServers.value = it }
            }
            launch {
                repository.getFavoriteFolders().collectLatest { _favoriteFolders.value = it }
            }
            launch {
                repository.getFavoriteCountsByFolder().collectLatest { _favoriteFolderCounts.value = it }
            }
            launch {
                repository.getMergedFavoriteCountsByFolder().collectLatest { _mergedFavoriteFolderCounts.value = it }
            }
        }

        // Network sync: always fetch if cache is empty; skip if fetched within last 4 hours.
        // VOD/series catalogs can be huge (100k+ items on some providers) — only auto-fetch
        // those on first run (table empty), never repeatedly in the background. Manual
        // refresh (refreshNow) always re-fetches everything since that's an explicit ask.
        viewModelScope.launch {
            val isEmpty = repository.getChannelCount() == 0
            val isStale = repository.isChannelCacheStale()
            if (!isEmpty && !isStale) return@launch
            if (isEmpty) _loading.value = true
            try {
                coroutineScope {
                    launch { repository.fetchLiveCategories() }
                    launch { repository.fetchLiveStreams() }
                    launch { repository.fetchVodCategories() }
                    launch { repository.fetchSeriesCategories() }
                }
                // Run sequentially, after the smaller fetches above finish and their memory
                // is freed — parsing a 100k+ item catalog concurrently with everything else
                // spikes peak memory and can OOM-crash the whole app.
                if (repository.getVodCount() == 0) {
                    repository.fetchVodStreams { saved, total ->
                        _syncProgress.value = "Loading movies… $saved/$total" to (saved * 100 / total.coerceAtLeast(1))
                    }
                }
                if (repository.getSeriesCount() == 0) {
                    repository.fetchSeries { saved, total ->
                        _syncProgress.value = "Loading series… $saved/$total" to (saved * 100 / total.coerceAtLeast(1))
                    }
                }
                _syncProgress.value = null
            } finally {
                _loading.value = false
            }
        }
    }

    // Manual "↻ Refresh" on the Live tab — deliberately live-channels-only. Movies/Series have
    // their own dedicated refresh buttons (Settings' btnRefreshMovies/btnRefreshSeries, and TV's
    // equivalents); bundling a full VOD/series re-fetch into every live-channel refresh made
    // this button silently do far more than its label/toast ("Refreshing channels…") implied.
    fun refreshNow() {
        viewModelScope.launch {
            _loading.value = true
            try {
                coroutineScope {
                    launch { repository.fetchLiveCategories() }
                    launch { repository.fetchLiveStreams() }
                }
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun updateFavoriteCategories(categories: List<CategoryEntity>) {
        val favoriteIds = repository.getFavoriteLiveCategoryIds().first()
        _favoriteLiveCategories.value = categories.filter { it.categoryId in favoriteIds }
    }

    enum class ChannelSort { DEFAULT, NAME_AZ, MOST_WATCHED, RECENTLY_WATCHED, MOST_RELIABLE }

    private val _channelSort = MutableStateFlow(ChannelSort.DEFAULT)
    val channelSort: StateFlow<ChannelSort> = _channelSort

    // Cached synchronously so applySortToChannels (called inline from collectLatest blocks)
    // doesn't need to suspend — refreshed whenever reliability sort is selected and whenever
    // new outcome data is recorded, so it doesn't need a full app restart to catch up.
    private var reliabilityCache: Map<Int, Int> = emptyMap()

    private suspend fun refreshReliabilityCache() {
        reliabilityCache = repository.getAllReliabilityPercents()
    }

    init {
        viewModelScope.launch {
            val saved = prefs.channelSortMode.first()
            _channelSort.value = ChannelSort.values().getOrElse(saved) { ChannelSort.DEFAULT }
            if (_channelSort.value == ChannelSort.MOST_RELIABLE) refreshReliabilityCache()
        }
    }

    fun cycleSort() {
        val next = ChannelSort.values().let { it[(it.indexOf(_channelSort.value) + 1) % it.size] }
        _channelSort.value = next
        viewModelScope.launch {
            prefs.setChannelSortMode(ChannelSort.values().indexOf(next))
            if (next == ChannelSort.MOST_RELIABLE) refreshReliabilityCache()
            reloadCurrentLiveCategory()
        }
    }

    fun setSortMode(index: Int) {
        _channelSort.value = ChannelSort.values().getOrElse(index) { ChannelSort.DEFAULT }
        viewModelScope.launch {
            if (_channelSort.value == ChannelSort.MOST_RELIABLE) refreshReliabilityCache()
            reloadCurrentLiveCategory()
        }
    }

    private fun applySortToChannels(list: List<ChannelEntity>): List<ChannelEntity> = when (_channelSort.value) {
        ChannelSort.DEFAULT -> list
        ChannelSort.NAME_AZ -> list.sortedBy { it.name.lowercase() }
        ChannelSort.MOST_WATCHED -> list.sortedByDescending { it.viewCount }
        ChannelSort.RECENTLY_WATCHED -> list.sortedByDescending { it.lastWatched ?: 0L }
        // Channels with no recorded outcomes yet default to 100 (treated as good) so a
        // channel simply hasn't been tried isn't unfairly buried below ones proven flaky.
        ChannelSort.MOST_RELIABLE -> list.sortedByDescending { reliabilityCache[it.streamId] ?: 100 }
    }

    enum class VodSort { DEFAULT, RATING_DESC, YEAR_NEWEST, YEAR_OLDEST, RECENTLY_ADDED }

    private val _vodSort = MutableStateFlow(VodSort.DEFAULT)
    val vodSort: StateFlow<VodSort> = _vodSort

    fun setVodSort(mode: VodSort) { _vodSort.value = mode }

    // Merged/Providers Movies had no sort concept at all before this — same VodSort enum/options
    // (Rating/Year Newest/Year Oldest/Recently Added), kept as a separate state flow from
    // primary Movies' _vodSort since they're independent lists a user could want sorted
    // differently.
    private val _mergedVodSort = MutableStateFlow(VodSort.DEFAULT)
    val mergedVodSort: StateFlow<VodSort> = _mergedVodSort

    fun setMergedVodSort(mode: VodSort) { _mergedVodSort.value = mode }

    fun applyMergedVodSort(list: List<com.iptvapp.data.local.entities.MergedVodEntity>): List<com.iptvapp.data.local.entities.MergedVodEntity> {
        val (favorited, rest) = list.partition { it.isFavorite }
        val (started, untouched) = rest.partition { it.watchedMs > 0 }
        val sortedFavorited = favorited.sortedByDescending { it.watchedMs }
        val sortedStarted = started.sortedByDescending { it.watchedMs }
        val sortedRest = when (_mergedVodSort.value) {
            VodSort.DEFAULT -> untouched
            VodSort.RATING_DESC -> untouched.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
            VodSort.YEAR_NEWEST -> untouched.sortedByDescending { yearFromTitle(it.name) ?: -1 }
            VodSort.YEAR_OLDEST -> untouched.sortedBy { yearFromTitle(it.name) ?: Int.MAX_VALUE }
            VodSort.RECENTLY_ADDED -> untouched.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
        }
        return sortedFavorited + sortedStarted + sortedRest
    }

    /** Best-effort year parse from a title like "Jurassic Park (1993)" — providers embed it
     * in the name; there's no separate year field. Returns null if the title has none. */
    fun yearFromTitle(name: String): Int? =
        Regex("""\((\d{4})\)\s*$""").find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    // Favorited movies float to the very top (a favorite is a deliberate bookmark, so it
    // outranks "you happened to start watching this"), then movies with any watch progress
    // (started or finished), then everything else — the chosen sort only orders within each
    // bucket. Most recently watched comes first within the "in progress" bucket so a movie
    // picked up again stays near the top instead of jumping around. Favoriting previously did
    // nothing visible anywhere in the list — this was the missing half of that feature.
    fun applyVodSort(list: List<VodEntity>): List<VodEntity> {
        val (favorited, rest) = list.partition { it.isFavorite }
        val (started, untouched) = rest.partition { it.watchedMs > 0 }
        val sortedFavorited = favorited.sortedByDescending { it.watchedMs }
        val sortedStarted = started.sortedByDescending { it.watchedMs }
        val sortedRest = when (_vodSort.value) {
            VodSort.DEFAULT -> untouched
            VodSort.RATING_DESC -> untouched.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
            VodSort.YEAR_NEWEST -> untouched.sortedByDescending { yearFromTitle(it.name) ?: -1 }
            VodSort.YEAR_OLDEST -> untouched.sortedBy { yearFromTitle(it.name) ?: Int.MAX_VALUE }
            VodSort.RECENTLY_ADDED -> untouched.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
        }
        return sortedFavorited + sortedStarted + sortedRest
    }

    enum class SeriesSort { DEFAULT, RATING_DESC, YEAR_NEWEST, YEAR_OLDEST, RECENTLY_ADDED }

    private val _seriesSort = MutableStateFlow(SeriesSort.DEFAULT)
    val seriesSort: StateFlow<SeriesSort> = _seriesSort

    fun setSeriesSort(mode: SeriesSort) { _seriesSort.value = mode }

    // Populated from EpisodeWatchedDao.getSeriesIdsWithProgress() — series-level watch progress
    // now lives per-episode (episode_watched), not on SeriesEntity itself, so this cache is
    // what applySeriesSort uses to float watching/watched shows to the top.
    private var seriesIdsWithProgress: Set<Int> = emptySet()

    // Series has no "added" timestamp field from the provider (unlike VOD) — cachedAt (when
    // this app synced the row) is the closest available proxy for "recently added".
    // Same favorites-first priority as applyVodSort — favoriting previously did nothing visible
    // here either.
    fun applySeriesSort(list: List<SeriesEntity>): List<SeriesEntity> {
        val (favorited, rest) = list.partition { it.isFavorite }
        val (started, untouched) = rest.partition { it.seriesId in seriesIdsWithProgress }
        val sortedRest = when (_seriesSort.value) {
            SeriesSort.DEFAULT -> untouched
            SeriesSort.RATING_DESC -> untouched.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
            SeriesSort.YEAR_NEWEST -> untouched.sortedByDescending { yearFromTitle(it.name) ?: -1 }
            SeriesSort.YEAR_OLDEST -> untouched.sortedBy { yearFromTitle(it.name) ?: Int.MAX_VALUE }
            SeriesSort.RECENTLY_ADDED -> untouched.sortedByDescending { it.cachedAt }
        }
        return favorited + started + sortedRest
    }

    fun hasSelectedCategory(): Boolean = selectedLiveCategoryId != null

    fun selectLiveCategory(categoryId: String) {
        inFavoritesMode = false
        selectedLiveCategoryId = categoryId
        searchJob?.cancel()
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            repository.getChannelsByCategory(categoryId).collectLatest {
                _channels.value = applySortToChannels(it)
            }
        }
    }

    fun selectFavCategory(categoryId: String) {
        inFavoritesMode = true
        searchJob?.cancel()
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            repository.getChannelsByCategory(categoryId).collectLatest {
                _channels.value = it
            }
        }
    }

    fun selectVodCategory(categoryId: String) {
        selectedVodCategoryId = categoryId
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            repository.getVodByCategory(categoryId).collectLatest {
                _vod.value = it
            }
        }
    }

    // Pinned "★ Favorites" entry at the top of the primary Movies category list — same concept
    // as merged/Providers Movies' own top-level Favorites row (selectMergedVodAllFavoritesAcrossServers),
    // just adapted for primary Movies' flat category-list UI instead of a server picker.
    fun selectVodFavorites() {
        selectedVodCategoryId = null
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            repository.getFavoriteVod().collectLatest {
                _vod.value = it
            }
        }
    }

    fun loadEpgForChannels(channels: List<ChannelEntity>) {
        viewModelScope.launch {
            if (channels.isEmpty()) {
                _channelEpgText.value = emptyMap()
                _channelEpgProgress.value = emptyMap()
                _channelEpgNextText.value = emptyMap()
                return@launch
            }

            // Show whatever's already in the DB immediately — don't make the guide text wait
            // on the network prefetch below, which is now deliberately paced and can take
            // several seconds on a slow or rate-limit-sensitive provider.
            publishEpgDisplay(channels)

            // Only trigger a fresh per-channel network fetch for a bounded window — firing
            // one API call per channel for an entire large category would flood the server.
            // The periodic full-catalog XMLTV refresh already keeps everything else
            // reasonably fresh in the background. But the DISPLAYED now/next text above is
            // computed for every channel in the list from whatever's already in the DB —
            // capping that too meant channels past the fetch window never showed a guide
            // entry at all, even once their EPG data existed, cutting the list short.
            //
            // A 429 storm on a real provider (tv.media4u.top) showed these 50 back-to-back
            // calls with zero pacing was enough on its own to trip Cloudflare rate-limiting —
            // and that rate-limit then also blocked the actual channel-playback request that
            // happened to land moments later. A small delay between each call keeps this well
            // under any reasonable per-IP burst limit.
            channels.take(50).forEach {
                repository.fetchEpg(it.streamId)
                kotlinx.coroutines.delay(150)
            }
            publishEpgDisplay(channels)
        }
    }

    private suspend fun publishEpgDisplay(channels: List<ChannelEntity>) {
        val ids = channels.map { it.streamId }
        // Chunked to stay well under SQLite's bound-parameter limit for large categories.
        val epgEntries = ids.chunked(500).flatMap { chunk -> repository.getEpgForStreams(chunk).first() }
        val epgByStream = epgEntries.groupBy { it.streamId }
        val nowSecs = System.currentTimeMillis() / 1000
        val progressMap = mutableMapOf<Int, Int>()
        val nextTextMap = mutableMapOf<Int, String>()
        _channelEpgText.value = channels.associate { channel ->
            val programs = epgByStream[channel.streamId].orEmpty()
            val now = programs.firstOrNull()
            val next = programs.drop(1).firstOrNull()

            // Compute progress 0-100 for the current program
            val prog = if (now != null && now.stopTimestamp > now.startTimestamp) {
                val elapsed = (nowSecs - now.startTimestamp).coerceAtLeast(0)
                val total = now.stopTimestamp - now.startTimestamp
                ((elapsed * 100L) / total).coerceIn(0, 100).toInt()
            } else 0
            progressMap[channel.streamId] = prog

            // Time remaining suffix
            val minutesLeft = if (now != null) ((now.stopTimestamp - nowSecs) / 60).coerceAtLeast(0) else 0L
            val timeStr = if (now != null && minutesLeft > 0) " (${minutesLeft}m)" else ""

            if (next != null) nextTextMap[channel.streamId] = next.title

            val text = when {
                now != null && next != null -> "NOW: ${now.title}$timeStr  •  NEXT: ${next.title}"
                now != null -> "NOW: ${now.title}$timeStr"
                else -> "—"
            }
            channel.streamId to text
        }
        _channelEpgProgress.value = progressMap
        _channelEpgNextText.value = nextTextMap
    }

    fun reloadCurrentLiveCategory() {
        val current = selectedLiveCategoryId
        if (current != null) {
            selectLiveCategory(current)
        } else {
            channelJob?.cancel()
            channelJob = viewModelScope.launch {
                repository.getAllChannels().collectLatest { _channels.value = it }
            }
        }
    }

    /** One-shot favorites read, independent of the shared `channels` StateFlow. That flow
     * conflates equal consecutive values, so re-entering Favorites without the list actually
     * changing (e.g. after just browsing Live/Categories/Guide) never redelivers it to
     * collectors — this always re-queries so the Activity can reliably scroll to what's
     * playing on every return to the tab, not just the first time. */
    suspend fun getFavoriteChannelsSnapshot(): List<ChannelEntity> = favoriteFlowForSelection().first()

    /** Same one-shot-read reasoning as [getFavoriteChannelsSnapshot], but for the combined
     * (primary + Providers) favorites list shown in the genre-classified Favorites tab. */
    suspend fun getCombinedFavoritesSnapshot(): List<CombinedFavorite> = combinedFavorites.first()

    /** Un-favoriting from the combined list needs to route to whichever underlying table the
     * item actually came from — primary channels vs. a specific server's merged_channels row. */
    fun toggleCombinedFavorite(item: CombinedFavorite) {
        when (item) {
            is CombinedFavorite.Primary -> toggleChannelFavorite(item.channel.streamId)
            is CombinedFavorite.Merged -> setMergedChannelFavorite(item.channel, !item.channel.isFavorite)
        }
    }

    fun toggleLiveRowFavorite(row: LiveChannelRow) {
        val ch = row.channel
        val merged = row.mergedChannel
        if (ch != null) toggleChannelFavorite(ch.streamId)
        else if (merged != null) setMergedChannelFavorite(merged, !merged.isFavorite)
    }

    /** Same one-shot-read reasoning as [getFavoriteChannelsSnapshot] but for a specific live
     * category — lets a caller scroll to a channel right after selecting its category without
     * racing the shared `channels` StateFlow's async collectLatest emission. */
    suspend fun getChannelsByCategorySnapshot(categoryId: String): List<ChannelEntity> =
        repository.getChannelsByCategory(categoryId).first()

    fun showFavoriteChannels() = selectFavoriteFolderView(null)

    // One-shot snapshot of every merged/extra-provider favorite channel, across all providers —
    // used by Mosaic's channel picker so a favorite from a secondary provider can be put in a
    // tile too, not just primary-provider favorites.
    suspend fun getMergedFavoriteChannelsSnapshot(): List<com.iptvapp.data.local.entities.MergedChannelEntity> =
        repository.getMergedAllFavorites().first()

    private fun favoriteFlowForSelection() = when (selectedFavoriteFolder) {
        null -> repository.getFavoriteChannels()
        -1 -> repository.getUnfiledFavorites()
        else -> repository.getFavoritesInFolder(selectedFavoriteFolder!!)
    }

    private fun mergedFavoriteFlowForSelection() = when (selectedFavoriteFolder) {
        null -> repository.getMergedAllFavorites()
        -1 -> repository.getMergedUnfiledFavorites()
        else -> repository.getMergedFavoritesInFolder(selectedFavoriteFolder!!)
    }

    /** Filters within whichever favorites view (All/Unsorted/folder) is currently selected,
     * across BOTH primary and merged/secondary-provider favorites — matches
     * selectFavoriteFolderView's combined source so search never drops channels that were
     * visible a moment before typing. */
    fun searchFavorites(query: String) {
        inFavoritesMode = true
        searchJob?.cancel()
        channelJob?.cancel()
        if (query.isBlank()) {
            selectFavoriteFolderView(selectedFavoriteFolder)
            return
        }
        searchJob = viewModelScope.launch {
            combine(favoriteFlowForSelection(), mergedFavoriteFlowForSelection(), _liveCategories) { primary, merged, cats ->
                val namesById = cats.associate { it.categoryId to it.categoryName }
                val q = query
                _channels.value = primary.filter {
                    it.name.contains(q, ignoreCase = true) || it.streamId.toString().contains(q)
                }
                primary.filter { it.name.contains(q, ignoreCase = true) || it.streamId.toString().contains(q) }
                    .map { CombinedFavorite.Primary(it, namesById[it.categoryId]) } +
                    merged.filter { it.name.contains(q, ignoreCase = true) || it.streamId.toString().contains(q) }
                        .map { CombinedFavorite.Merged(it) }
            }.collectLatest { _combinedFavorites.value = it }
        }
    }

    fun loadGuide() {
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            val favChannels = repository.getFavoriteChannels().first()
            val favCategoryIds = repository.getFavoriteLiveCategoryIds().first()
            val catChannels = favCategoryIds.flatMap { categoryId ->
                repository.getChannelsByCategory(categoryId).first()
            }
            val allChannels = (favChannels + catChannels).distinctBy { it.streamId }
            val ids = allChannels.map { it.streamId }

            // Merged/secondary-provider favorites — categories aren't a concept there yet, so
            // just favorited channels, same as the merged "★ Favorites" view elsewhere.
            val mergedFavorites = repository.getMergedAllFavorites().first()
            val serverPairs = mergedFavorites.map { it.serverIndex to it.streamId }

            fun buildRows(
                epgEntries: List<com.iptvapp.data.local.entities.EpgEntity>,
                mergedEpgEntries: List<com.iptvapp.data.local.entities.EpgEntity>
            ): List<GuideRow> {
                val primaryRows = allChannels
                    .map { ch -> GuideRow(channel = ch, programs = epgEntries.filter { it.streamId == ch.streamId }) }
                    .filter { it.programs.isNotEmpty() }
                val mergedRows = mergedFavorites
                    .map { ch ->
                        GuideRow(
                            mergedChannel = ch,
                            programs = mergedEpgEntries.filter { it.serverIndex == ch.serverIndex && it.streamId == ch.streamId }
                        )
                    }
                    .filter { it.programs.isNotEmpty() }
                return primaryRows + mergedRows
            }

            // Show whatever is cached in the DB immediately — no spinner
            val cached = if (ids.isEmpty()) emptyList() else repository.getEpgForStreams(ids).first()
            val mergedCached = if (serverPairs.isEmpty()) emptyList() else repository.getEpgForServerStreams(serverPairs).first()
            if (cached.isNotEmpty() || mergedCached.isNotEmpty()) _guideRows.value = buildRows(cached, mergedCached)

            // Check DB freshness: newest EPG stop timestamp (already in seconds or ms)
            val newestStop = repository.getNewestEpgStop()
            val newestStopMs = if (newestStop != null && newestStop < 100_000_000_000L)
                newestStop * 1000L else newestStop ?: 0L
            val stale = newestStopMs < System.currentTimeMillis() + 30 * 60 * 1000L

            if (stale) {
                if (cached.isEmpty() && mergedCached.isEmpty()) _loading.value = true
                try {
                    coroutineScope {
                        // Paced (150ms between launches), matching loadEpgForMergedChannels/
                        // checkFavoritesHealth elsewhere — an unpaced parallel burst here
                        // previously caused provider rate-limiting incidents (see HomeViewModel
                        // history), so both the primary and merged-provider fetch loops below
                        // deliberately space requests out instead of firing all at once.
                        allChannels.forEach { ch ->
                            launch { repository.fetchEpg(ch.streamId) }
                            kotlinx.coroutines.delay(150)
                        }
                        mergedFavorites.map { it.serverIndex }.distinct().forEach { serverIndex ->
                            launch { repository.fetchXmltvEpgForMergedServer(serverIndex) }
                        }
                        mergedFavorites.forEach { ch ->
                            launch { repository.fetchMergedEpg(ch.serverIndex, ch.streamId) }
                            kotlinx.coroutines.delay(150)
                        }
                    }
                } finally {
                    _loading.value = false
                }
                // Reload from DB after network fetch and update rows
                val fresh = if (ids.isEmpty()) emptyList() else repository.getEpgForStreams(ids).first()
                val mergedFresh = if (serverPairs.isEmpty()) emptyList() else repository.getEpgForServerStreams(serverPairs).first()
                if (fresh.isNotEmpty() || mergedFresh.isNotEmpty()) _guideRows.value = buildRows(fresh, mergedFresh)
            }
        }
    }
        fun searchChannels(query: String) {
        searchJob?.cancel()
        channelJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isBlank()) {
                selectedLiveCategoryId?.let { selectLiveCategory(it) }
            } else {
                repository.searchChannels(query).collectLatest { _channels.value = it }
            }
        }
    }

    fun searchVod(query: String) {
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            if (query.isBlank()) {
                selectedVodCategoryId?.let { selectVodCategory(it) }
            } else {
                repository.searchVod(query).collectLatest { _vod.value = it }
            }
        }
    }

    fun searchSeries(query: String) {
        seriesSearchJob?.cancel()
        seriesSearchJob = viewModelScope.launch {
            if (query.isBlank()) {
                repository.getAllSeries().collectLatest { _series.value = it }
            } else {
                repository.searchSeries(query).collectLatest { _series.value = it }
            }
        }
    }

    private val _globalSearchResults = MutableStateFlow<List<GlobalSearchResult>>(emptyList())
    val globalSearchResults: StateFlow<List<GlobalSearchResult>> = _globalSearchResults
    private var globalSearchJob: Job? = null

    // Searches primary + every extraServer at once, across channels/movies/series, unlike every
    // other search entry point in the app (each of those only searches whatever single
    // provider/content-type tab is currently open). Deliberately reads the raw repository Flows
    // directly rather than reusing searchVod()/searchMergedChannels()/etc. above — those write
    // into the shared _vod/_mergedChannels/... StateFlows that back the normal tabs, and this
    // must not disturb whatever the user had open on another tab while this runs alongside it.
    fun searchAllProviders(query: String) {
        globalSearchJob?.cancel()
        if (query.isBlank()) {
            _globalSearchResults.value = emptyList()
            return
        }
        globalSearchJob = viewModelScope.launch {
            val primaryResults = combine(
                repository.searchChannels(query),
                repository.searchVod(query),
                repository.searchSeries(query),
                prefs.serverNickname
            ) { channels, vod, series, primaryNick ->
                val label = primaryNick.ifBlank { "Primary" }
                buildList<GlobalSearchResult> {
                    addAll(channels.map { GlobalSearchResult.Channel(it, label) })
                    addAll(vod.map { GlobalSearchResult.Vod(it, label) })
                    addAll(series.map { GlobalSearchResult.Series(it, label) })
                }
            }
            val mergedResults = combine(
                repository.searchMergedChannels(query),
                repository.searchMergedVod(query),
                repository.searchMergedSeries(query)
            ) { mergedChannels, mergedVod, mergedSeries ->
                buildList<GlobalSearchResult> {
                    addAll(mergedChannels.map { GlobalSearchResult.MergedChannel(it) })
                    addAll(mergedVod.map { GlobalSearchResult.MergedVod(it) })
                    addAll(mergedSeries.map { GlobalSearchResult.MergedSeries(it) })
                }
            }
            combine(primaryResults, mergedResults) { primary, merged -> primary + merged }
                .collectLatest { _globalSearchResults.value = it }
        }
    }

    fun clearGlobalSearch() {
        globalSearchJob?.cancel()
        _globalSearchResults.value = emptyList()
    }

    fun showContinueWatching() {
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            repository.getInProgressVod().collectLatest { _continueWatching.value = it }
        }
    }

    fun toggleChannelFavorite(streamId: Int) {
        viewModelScope.launch {
            val wasAlreadyFavorite = repository.isChannelFavorite(streamId)
            repository.toggleChannelFavorite(streamId)
            if (!wasAlreadyFavorite) {
                repository.fetchEpg(streamId)
            }
        }
    }

    // The star icon on Movies/Series rows rendered isFavorite correctly but had no click
    // handler wired at all (VodAdapter's onFavoriteClick was a bare {} no-op, SeriesAdapter had
    // no favorite click plumbing whatsoever) — these two make the star actually toggle.
    fun toggleVodFavorite(vod: VodEntity) {
        viewModelScope.launch { repository.setVodFavorite(vod.streamId, !vod.isFavorite) }
    }

    fun toggleSeriesFavorite(series: SeriesEntity) {
        viewModelScope.launch { repository.setSeriesFavorite(series.seriesId, !series.isFavorite) }
    }

    fun setLiveCategoryFavorite(categoryId: String, isFavorite: Boolean) {
        viewModelScope.launch { repository.setLiveCategoryFavorite(categoryId, isFavorite) }
    }

    // Dismiss just hides the row from Continue Watching — resume position is preserved, and the
    // dismissal is cleared automatically the next time progress is saved (see
    // VodDao.updateWatchProgress / EpisodeWatchedDao.saveProgress), so resuming playback brings
    // the item right back.
    fun dismissVodFromContinueWatching(streamId: Int) {
        viewModelScope.launch { repository.dismissVodFromContinueWatching(streamId) }
    }

    fun dismissSeriesFromContinueWatching(seriesId: Int) {
        viewModelScope.launch { repository.dismissSeriesFromContinueWatching(seriesId) }
    }

    fun toggleLiveCategoryFavorite(categoryId: String) {
        viewModelScope.launch {
            val favoriteIds = repository.getFavoriteLiveCategoryIds().first()
            repository.setLiveCategoryFavorite(categoryId, categoryId !in favoriteIds)
            updateFavoriteCategories(_liveCategories.value)
        }
    }

    // key is "$serverIndex:$categoryId" (see CategoryAdapter/mergedCategoriesToSynthetic in
    // HomeActivity, which builds the synthetic CategoryEntity.categoryId as this same key so
    // the shared star-rendering `categoryId in favoriteCategoryIds` check works unmodified).
    fun toggleMergedCategoryFavorite(key: String) {
        viewModelScope.launch {
            val favoriteKeys = repository.getFavoriteMergedCategoryIds().first()
            repository.setMergedCategoryFavorite(key, key !in favoriteKeys)
        }
    }

    val favoriteMergedCategoryKeys: kotlinx.coroutines.flow.Flow<Set<String>> = repository.getFavoriteMergedCategoryIds()

    /** Long-press a merged-series category to favorite every series in it at once (into an
     * optional folder), rather than one show at a time. A different concept from
     * toggleMergedCategoryFavorite above (which just pins a LIVE category to the top of its own
     * list) — series has no such "pin category" concept, so long-press does something more
     * useful there: actually adds every show to your Favorites. */
    fun favoriteMergedSeriesCategory(serverIndex: Int, categoryId: String?, folderId: Int?) {
        viewModelScope.launch {
            repository.setMergedSeriesFavoriteForCategory(serverIndex, categoryId, folderId)
        }
    }

    // Hidden categories in Providers > Movies/Series — a separate concept from the favorite/pin
    // one above (hiding removes from the list entirely; favoriting just reorders to the top).
    // Independent per mode, same "$serverIndex:$categoryId" key shape.
    val hiddenMergedVodCategoryKeys: kotlinx.coroutines.flow.Flow<Set<String>> = repository.getHiddenMergedVodCategoryIds()
    val hiddenMergedSeriesCategoryKeys: kotlinx.coroutines.flow.Flow<Set<String>> = repository.getHiddenMergedSeriesCategoryIds()

    fun bulkHideMergedVodCategories(keys: Set<String>) {
        viewModelScope.launch { repository.addHiddenMergedVodCategoryIds(keys) }
    }

    fun unhideMergedVodCategory(key: String) {
        viewModelScope.launch { repository.removeHiddenMergedVodCategoryId(key) }
    }

    fun bulkHideMergedSeriesCategories(keys: Set<String>) {
        viewModelScope.launch { repository.addHiddenMergedSeriesCategoryIds(keys) }
    }

    fun unhideMergedSeriesCategory(key: String) {
        viewModelScope.launch { repository.removeHiddenMergedSeriesCategoryId(key) }
    }

    suspend fun getRecentChannel(): com.iptvapp.data.local.entities.ChannelEntity? {
        return repository.getRecentChannels().first().firstOrNull()
    }

    suspend fun markChannelWatched(streamId: Int) {
        repository.markChannelWatched(streamId)
    }

    suspend fun getEpgText(streamId: Int): String {
        repository.fetchEpg(streamId)
        val epg = repository.getEpgForStream(streamId).first()
        val now = epg.nowProgram()
        val next = epg.nextProgram(now)
        return when {
            now != null && next != null -> "NOW: ${now.title}   NEXT: ${next.title}"
            now != null -> "NOW: ${now.title}"
            else -> ""
        }
    }

    suspend fun getMiniEpgDescription(streamId: Int): String {
        val epg = repository.getEpgForStream(streamId).first()
        return epg.nowProgram()?.description?.takeIf { it.isNotBlank() } ?: ""
    }

    suspend fun getMiniEpgProgress(streamId: Int): Int {
        val epg = repository.getEpgForStream(streamId).first()
        val now = epg.nowProgram() ?: return 0
        val start = now.startMs()
        val stop = now.stopMs()
        val current = System.currentTimeMillis()
        if (stop <= start) return 0
        return ((current - start) * 100 / (stop - start)).toInt().coerceIn(0, 100)
    }

    suspend fun getVodProgress(streamId: Int): Pair<Long, Long> = repository.getVodProgress(streamId)

    suspend fun getLiveStreamUrl(streamId: Int): String = repository.getLiveStreamUrl(streamId)

    suspend fun getVodStreamUrl(streamId: Int, extension: String): String =
        repository.getVodStreamUrl(streamId, extension)

    suspend fun getTimeshiftUrl(streamId: Int, startTimestampSec: Long, durationMinutes: Int): String =
        repository.getTimeshiftUrl(streamId, startTimestampSec, durationMinutes)

    fun saveFavOrder(orderedIds: List<Int>) {
        viewModelScope.launch { repository.saveFavOrder(orderedIds) }
    }

    suspend fun getUpcomingEpg(streamId: Int): List<com.iptvapp.data.local.entities.EpgEntity> {
        val nowSec = System.currentTimeMillis() / 1000
        return repository.getEpgForStream(streamId).first()
            .filter { it.stopTimestamp > nowSec }
            .sortedBy { it.startTimestamp }
            .take(6)
    }

    // ─── Watch History ───────────────────────────────────────────────────────

    private val _recentChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val recentChannels: StateFlow<List<ChannelEntity>> = _recentChannels

    fun observeRecentChannels() {
        viewModelScope.launch {
            repository.getRecentChannels().collectLatest { _recentChannels.value = it }
        }
    }

    fun trackChannelPlay(streamId: Int) {
        viewModelScope.launch { repository.markChannelWatched(streamId) }
    }

    // ─── Channel Hide ────────────────────────────────────────────────────────

    private val _hiddenChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val hiddenChannels: StateFlow<List<ChannelEntity>> = _hiddenChannels

    fun observeHiddenChannels() {
        viewModelScope.launch {
            repository.getHiddenChannels().collectLatest { _hiddenChannels.value = it }
        }
    }

    fun hideChannel(streamId: Int) {
        viewModelScope.launch { repository.setChannelHidden(streamId, true) }
    }

    fun unhideChannel(streamId: Int) {
        viewModelScope.launch { repository.setChannelHidden(streamId, false) }
    }

    // ─── Series Hide (bulk checkbox select, primary + merged) ───────────────────

    private val _hiddenSeries = MutableStateFlow<List<SeriesEntity>>(emptyList())
    val hiddenSeries: StateFlow<List<SeriesEntity>> = _hiddenSeries

    fun observeHiddenSeries() {
        viewModelScope.launch {
            repository.getHiddenSeries().collectLatest { _hiddenSeries.value = it }
        }
    }

    fun bulkHideSeries(seriesIds: List<Int>) {
        viewModelScope.launch { repository.bulkHideSeries(seriesIds) }
    }

    fun unhideSeries(seriesId: Int) {
        viewModelScope.launch { repository.unhideSeries(seriesId) }
    }

    private val _hiddenMergedSeries = MutableStateFlow<List<com.iptvapp.data.local.entities.MergedSeriesEntity>>(emptyList())
    val hiddenMergedSeries: StateFlow<List<com.iptvapp.data.local.entities.MergedSeriesEntity>> = _hiddenMergedSeries

    fun observeHiddenMergedSeries() {
        viewModelScope.launch {
            repository.getHiddenMergedSeries().collectLatest { _hiddenMergedSeries.value = it }
        }
    }

    // Grouped by serverIndex since bulkHideMergedSeries is a per-server DAO call (composite
    // key, same reasoning as bulkSetMergedChannelFavorite) — a bulk selection can span
    // multiple merged providers' shows at once if the user selected across categories.
    fun bulkHideMergedSeries(items: List<com.iptvapp.data.local.entities.MergedSeriesEntity>) {
        viewModelScope.launch {
            items.groupBy { it.serverIndex }.forEach { (serverIndex, group) ->
                repository.bulkHideMergedSeries(serverIndex, group.map { it.seriesId })
            }
        }
    }

    fun unhideMergedSeries(serverIndex: Int, seriesId: Int) {
        viewModelScope.launch { repository.unhideMergedSeries(serverIndex, seriesId) }
    }

    // ─── Merged VOD hide (bulk checkbox select) + watch progress ────────────

    // Grouped by serverIndex — same reasoning as bulkHideMergedSeries (composite-key DAO call,
    // a bulk selection can span multiple merged providers at once).
    fun bulkHideMergedVod(items: List<com.iptvapp.data.local.entities.MergedVodEntity>) {
        viewModelScope.launch {
            items.groupBy { it.serverIndex }.forEach { (serverIndex, group) ->
                repository.bulkHideMergedVod(serverIndex, group.map { it.streamId })
            }
        }
    }

    suspend fun getMergedVodProgress(serverIndex: Int, streamId: Int): Pair<Long, Long> =
        repository.getMergedVodProgress(serverIndex, streamId)

    fun saveMergedVodProgress(serverIndex: Int, streamId: Int, watchedMs: Long, durationMs: Long) {
        viewModelScope.launch { repository.saveMergedVodProgress(serverIndex, streamId, watchedMs, durationMs) }
    }

    // ─── Bulk Favorites ──────────────────────────────────────────────────────

    fun bulkAddFavorites(streamIds: List<Int>) {
        viewModelScope.launch { repository.bulkSetFavorite(streamIds) }
    }

    fun bulkRemoveFavorites(streamIds: List<Int>) {
        viewModelScope.launch { repository.bulkClearFavorite(streamIds) }
    }

    // Merged-channel equivalent, keyed by "$serverIndex:$streamId" — see
    // XtreamRepository.bulkSetMergedChannelFavorite kdoc.
    fun bulkAddMergedFavorites(keys: Set<String>) {
        viewModelScope.launch { repository.bulkSetMergedChannelFavorite(keys, true) }
    }

    // ─── Channels Like This ──────────────────────────────────────────────────

    private val _similarChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val similarChannels: StateFlow<List<ChannelEntity>> = _similarChannels

    fun loadSimilarChannels(channel: ChannelEntity) {
        val categoryId = channel.categoryId ?: return
        viewModelScope.launch {
            repository.getSimilarChannels(categoryId, channel.streamId)
                .first()
                .let { _similarChannels.value = it }
        }
    }

    fun clearSimilarChannels() { _similarChannels.value = emptyList() }

    suspend fun getChannelById(streamId: Int): ChannelEntity? =
        repository.getChannelById(streamId)

    suspend fun getChannelByNumber(num: Int): ChannelEntity? =
        repository.getChannelByNumber(num)
}