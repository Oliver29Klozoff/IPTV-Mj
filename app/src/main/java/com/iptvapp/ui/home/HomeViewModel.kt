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
                }
            }
        }
    }

    private var selectedLiveCategoryId: String? = null
    private var selectedVodCategoryId: String? = null
    var inFavoritesMode: Boolean = true
    var lastTabPosition: Int = 5

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
        val n = name.trim().uppercase()
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

    enum class ChannelSort { DEFAULT, NAME_AZ, MOST_WATCHED, RECENTLY_WATCHED }

    private val _channelSort = MutableStateFlow(ChannelSort.DEFAULT)
    val channelSort: StateFlow<ChannelSort> = _channelSort

    init {
        viewModelScope.launch {
            val saved = prefs.channelSortMode.first()
            _channelSort.value = ChannelSort.values().getOrElse(saved) { ChannelSort.DEFAULT }
        }
    }

    fun cycleSort() {
        val next = ChannelSort.values().let { it[(it.indexOf(_channelSort.value) + 1) % it.size] }
        _channelSort.value = next
        viewModelScope.launch { prefs.setChannelSortMode(ChannelSort.values().indexOf(next)) }
        reloadCurrentLiveCategory()
    }

    fun setSortMode(index: Int) {
        _channelSort.value = ChannelSort.values().getOrElse(index) { ChannelSort.DEFAULT }
        reloadCurrentLiveCategory()
    }

    private fun applySortToChannels(list: List<ChannelEntity>): List<ChannelEntity> = when (_channelSort.value) {
        ChannelSort.DEFAULT -> list
        ChannelSort.NAME_AZ -> list.sortedBy { it.name.lowercase() }
        ChannelSort.MOST_WATCHED -> list.sortedByDescending { it.viewCount }
        ChannelSort.RECENTLY_WATCHED -> list.sortedByDescending { it.lastWatched ?: 0L }
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

            // Only trigger a fresh per-channel network fetch for a bounded window — firing
            // one API call per channel for an entire large category would flood the server.
            // The periodic full-catalog XMLTV refresh already keeps everything else
            // reasonably fresh in the background. But the DISPLAYED now/next text below is
            // computed for every channel in the list from whatever's already in the DB —
            // capping that too meant channels past the fetch window never showed a guide
            // entry at all, even once their EPG data existed, cutting the list short.
            channels.take(50).forEach { repository.fetchEpg(it.streamId) }

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

    fun showFavoriteChannels() {
        inFavoritesMode = true
        searchJob?.cancel()
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            repository.getFavoriteChannels().collectLatest { favorites ->
                _channels.value = favorites
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
}