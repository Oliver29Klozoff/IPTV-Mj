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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        val url: String, val title: String, val streamId: Int, val isVod: Boolean, val positionMs: Long
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
    fun selectFavoriteFolderView(folderId: Int?) {
        inFavoritesMode = true
        selectedFavoriteFolder = folderId
        searchJob?.cancel()
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            val flow = when (folderId) {
                null -> repository.getFavoriteChannels()
                -1 -> repository.getUnfiledFavorites()
                else -> repository.getFavoritesInFolder(folderId)
            }
            flow.collectLatest { _channels.value = it }
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

    /** Manual refresh only — fetches every configured server's live channels in parallel for
     * the "All Providers" browse-and-play view. Not part of the automatic background sync. */
    fun refreshMergedChannels() {
        viewModelScope.launch { repository.refreshMergedChannels() }
    }

    fun resetMergedSelection() {
        selectedMergedServerIndex = null
        selectedMergedFavoriteFolder = null
        mergedCategoriesJob?.cancel()
        mergedChannelsJob?.cancel()
        _mergedCategories.value = emptyList()
        _mergedChannels.value = emptyList()
    }

    fun selectMergedServer(serverIndex: Int) {
        selectedMergedServerIndex = serverIndex
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

    /** Now/next text per merged channel, fetched from each channel's own server. Same bounded
     * window + pacing as the primary list's loadEpgForChannels — one get_short_epg call per
     * channel with a small delay so a big category can't trip a provider's rate limiting. */
    fun loadEpgForMergedChannels(channels: List<com.iptvapp.data.local.entities.MergedChannelEntity>) {
        mergedEpgJob?.cancel()
        if (channels.isEmpty()) return
        mergedEpgJob = viewModelScope.launch {
            channels.take(50).forEach { ch ->
                val key = "${ch.serverIndex}:${ch.streamId}"
                if (!_mergedEpgText.value.containsKey(key)) {
                    val text = repository.fetchMergedShortEpgText(ch.serverIndex, ch.streamId)
                    if (text != null) _mergedEpgText.value = _mergedEpgText.value + (key to text)
                    kotlinx.coroutines.delay(150)
                }
            }
        }
    }

    /** Live HEAD-check of every favorited merged channel, mirroring checkFavoritesHealth() —
     * grey dot while checking, then green/red. Runs when the merged ★ Favorites view opens. */
    fun checkMergedFavoritesHealth() {
        viewModelScope.launch {
            val favorites = repository.getMergedAllFavorites().first()
            _mergedHealth.value = favorites.associate { "${it.serverIndex}:${it.streamId}" to null }
            favorites.forEach { ch ->
                launch {
                    val url = repository.getMergedLiveStreamUrl(ch.serverIndex, ch.streamId)
                    val alive = repository.checkStreamHealth(url)
                    _mergedHealth.value = _mergedHealth.value + ("${ch.serverIndex}:${ch.streamId}" to alive)
                }
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

    fun checkFavoritesHealth() {
        viewModelScope.launch {
            val favorites = repository.getFavoriteChannels().first()
            // Reset to null (checking) for all favorites
            _channelHealth.value = favorites.associate { it.streamId to null }
            favorites.forEach { channel ->
                launch {
                    val url = repository.getLiveStreamUrl(channel.streamId)
                    val alive = repository.checkStreamHealth(url)
                    _channelHealth.value = _channelHealth.value + (channel.streamId to alive)
                    repository.recordChannelOutcome(channel.streamId, alive)
                }
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

    fun refreshNow() {
        viewModelScope.launch {
            _loading.value = true
            try {
                coroutineScope {
                    launch { repository.fetchLiveCategories() }
                    launch { repository.fetchLiveStreams() }
                    launch { repository.fetchVodCategories() }
                    launch { repository.fetchSeriesCategories() }
                }
                repository.fetchVodStreams { saved, total ->
                    _syncProgress.value = "Loading movies… $saved/$total" to (saved * 100 / total.coerceAtLeast(1))
                }
                repository.fetchSeries { saved, total ->
                    _syncProgress.value = "Loading series… $saved/$total" to (saved * 100 / total.coerceAtLeast(1))
                }
                _syncProgress.value = null
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

    /** Best-effort year parse from a title like "Jurassic Park (1993)" — providers embed it
     * in the name; there's no separate year field. Returns null if the title has none. */
    fun yearFromTitle(name: String): Int? =
        Regex("""\((\d{4})\)\s*$""").find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun applyVodSort(list: List<VodEntity>): List<VodEntity> = when (_vodSort.value) {
        VodSort.DEFAULT -> list
        VodSort.RATING_DESC -> list.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
        VodSort.YEAR_NEWEST -> list.sortedByDescending { yearFromTitle(it.name) ?: -1 }
        VodSort.YEAR_OLDEST -> list.sortedBy { yearFromTitle(it.name) ?: Int.MAX_VALUE }
        VodSort.RECENTLY_ADDED -> list.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
    }

    enum class SeriesSort { DEFAULT, RATING_DESC, YEAR_NEWEST, YEAR_OLDEST, RECENTLY_ADDED }

    private val _seriesSort = MutableStateFlow(SeriesSort.DEFAULT)
    val seriesSort: StateFlow<SeriesSort> = _seriesSort

    fun setSeriesSort(mode: SeriesSort) { _seriesSort.value = mode }

    // Series has no "added" timestamp field from the provider (unlike VOD) — cachedAt (when
    // this app synced the row) is the closest available proxy for "recently added".
    fun applySeriesSort(list: List<SeriesEntity>): List<SeriesEntity> = when (_seriesSort.value) {
        SeriesSort.DEFAULT -> list
        SeriesSort.RATING_DESC -> list.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
        SeriesSort.YEAR_NEWEST -> list.sortedByDescending { yearFromTitle(it.name) ?: -1 }
        SeriesSort.YEAR_OLDEST -> list.sortedBy { yearFromTitle(it.name) ?: Int.MAX_VALUE }
        SeriesSort.RECENTLY_ADDED -> list.sortedByDescending { it.cachedAt }
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

    /** Same one-shot-read reasoning as [getFavoriteChannelsSnapshot] but for a specific live
     * category — lets a caller scroll to a channel right after selecting its category without
     * racing the shared `channels` StateFlow's async collectLatest emission. */
    suspend fun getChannelsByCategorySnapshot(categoryId: String): List<ChannelEntity> =
        repository.getChannelsByCategory(categoryId).first()

    fun showFavoriteChannels() = selectFavoriteFolderView(null)

    private fun favoriteFlowForSelection() = when (selectedFavoriteFolder) {
        null -> repository.getFavoriteChannels()
        -1 -> repository.getUnfiledFavorites()
        else -> repository.getFavoritesInFolder(selectedFavoriteFolder!!)
    }

    /** Filters within whichever favorites view (All/Unsorted/folder) is currently selected,
     * rather than searching the whole favorites list — so the Favorites tab's search box
     * stays scoped to what's actually shown there. */
    fun searchFavorites(query: String) {
        inFavoritesMode = true
        searchJob?.cancel()
        channelJob?.cancel()
        if (query.isBlank()) {
            selectFavoriteFolderView(selectedFavoriteFolder)
            return
        }
        searchJob = viewModelScope.launch {
            favoriteFlowForSelection().collectLatest { favorites ->
                _channels.value = favorites.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        it.streamId.toString().contains(query)
                }
            }
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

            fun buildRows(epgEntries: List<com.iptvapp.data.local.entities.EpgEntity>) =
                allChannels
                    .map { ch -> GuideRow(channel = ch, programs = epgEntries.filter { it.streamId == ch.streamId }) }
                    .filter { it.programs.isNotEmpty() }

            // Show whatever is cached in the DB immediately — no spinner
            val cached = if (ids.isEmpty()) emptyList() else repository.getEpgForStreams(ids).first()
            if (cached.isNotEmpty()) _guideRows.value = buildRows(cached)

            // Check DB freshness: newest EPG stop timestamp (already in seconds or ms)
            val newestStop = repository.getNewestEpgStop()
            val newestStopMs = if (newestStop != null && newestStop < 100_000_000_000L)
                newestStop * 1000L else newestStop ?: 0L
            val stale = newestStopMs < System.currentTimeMillis() + 30 * 60 * 1000L

            if (stale) {
                if (cached.isEmpty()) _loading.value = true
                try {
                    coroutineScope {
                        allChannels.forEach { ch -> launch { repository.fetchEpg(ch.streamId) } }
                    }
                } finally {
                    _loading.value = false
                }
                // Reload from DB after network fetch and update rows
                val fresh = if (ids.isEmpty()) emptyList() else repository.getEpgForStreams(ids).first()
                if (fresh.isNotEmpty()) _guideRows.value = buildRows(fresh)
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

    fun setLiveCategoryFavorite(categoryId: String, isFavorite: Boolean) {
        viewModelScope.launch { repository.setLiveCategoryFavorite(categoryId, isFavorite) }
    }

    fun toggleLiveCategoryFavorite(categoryId: String) {
        viewModelScope.launch {
            val favoriteIds = repository.getFavoriteLiveCategoryIds().first()
            repository.setLiveCategoryFavorite(categoryId, categoryId !in favoriteIds)
            updateFavoriteCategories(_liveCategories.value)
        }
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

    // ─── Bulk Favorites ──────────────────────────────────────────────────────

    fun bulkAddFavorites(streamIds: List<Int>) {
        viewModelScope.launch { repository.bulkSetFavorite(streamIds) }
    }

    fun bulkRemoveFavorites(streamIds: List<Int>) {
        viewModelScope.launch { repository.bulkClearFavorite(streamIds) }
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