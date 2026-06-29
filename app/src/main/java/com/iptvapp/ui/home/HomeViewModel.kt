package com.iptvapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.ChannelEntity
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val prefs: PreferencesManager
) : ViewModel() {

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

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    val showMovies = prefs.showMovies
    val showSeries = prefs.showSeries
    val showWatching = prefs.showWatching
    val externalPlayer = prefs.externalPlayer

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

    private val _currentlyPlayingStreamId = MutableStateFlow<Int>(-1)
    val currentlyPlayingStreamId: StateFlow<Int> = _currentlyPlayingStreamId

    fun setCurrentlyPlaying(streamId: Int) {
        _currentlyPlayingStreamId.value = streamId
    }

    private var channelJob: Job? = null
    private var vodJob: Job? = null
    private var searchJob: Job? = null
    private var guideJob: Job? = null
    private var observerJob: Job? = null

    private fun isUsCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim().uppercase()
        return n.startsWith("US|") || n.contains("|US|")
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
                repository.getVodCategories()
                    .combine(prefs.usaOnlyChannels) { cats, usaOnly ->
                        if (usaOnly) cats.filter { isUsCategory(it.categoryName) } else cats
                    }
                    .collectLatest { _vodCategories.value = it }
            }
            launch {
                repository.getAllVod().collectLatest { _vod.value = it }
            }
            launch {
                repository.getAllSeries().collectLatest { _series.value = it }
            }
            launch {
                repository.getInProgressVod().collectLatest { _continueWatching.value = it }
            }
        }

        // Background network sync — show spinner only when DB is empty (first install)
        viewModelScope.launch {
            val hasCache = repository.getChannelCount() > 0
            if (!hasCache) _loading.value = true
            try {
                coroutineScope {
                    launch { repository.fetchLiveCategories() }
                    launch { repository.fetchLiveStreams() }
                    launch { repository.fetchVodCategories() }
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
            val visibleChannels = channels.take(50)
            visibleChannels.forEach { repository.fetchEpg(it.streamId) }
            val ids = visibleChannels.map { it.streamId }
            if (ids.isEmpty()) {
                _channelEpgText.value = emptyMap()
                _channelEpgProgress.value = emptyMap()
                return@launch
            }
            val epgEntries = repository.getEpgForStreams(ids).first()
            val epgByStream = epgEntries.groupBy { it.streamId }
            val nowSecs = System.currentTimeMillis() / 1000
            val progressMap = mutableMapOf<Int, Int>()
            _channelEpgText.value = visibleChannels.associate { channel ->
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

                val text = when {
                    now != null && next != null -> "NOW: ${now.title}$timeStr  •  NEXT: ${next.title}"
                    now != null -> "NOW: ${now.title}$timeStr"
                    else -> "No guide data"
                }
                channel.streamId to text
            }
            _channelEpgProgress.value = progressMap
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
            _loading.value = true

            // Get both individually favorited channels AND channels from favorited categories
            val favChannels = repository.getFavoriteChannels().first()
            val favCategoryIds = repository.getFavoriteLiveCategoryIds().first()
            val catChannels = favCategoryIds.flatMap { categoryId ->
                repository.getChannelsByCategory(categoryId).first()
            }

            // Combine and deduplicate
            val allChannels = (favChannels + catChannels).distinctBy { it.streamId }

            allChannels.forEach { repository.fetchEpg(it.streamId) }

            val ids = allChannels.map { it.streamId }
            val epgEntries = if (ids.isEmpty()) emptyList()
            else repository.getEpgForStreams(ids).first()

            val epgByStream = epgEntries.groupBy { it.streamId }

            _guideRows.value = allChannels
                .map { channel -> GuideRow(channel = channel, programs = epgByStream[channel.streamId].orEmpty()) }
                .filter { it.programs.isNotEmpty() }

            _loading.value = false
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
        val now = epg.firstOrNull()
        val next = epg.drop(1).firstOrNull()
        return when {
            now != null && next != null -> "NOW: ${now.title}   NEXT: ${next.title}"
            now != null -> "NOW: ${now.title}"
            else -> ""
        }
    }

    suspend fun getMiniEpgDescription(streamId: Int): String {
        val epg = repository.getEpgForStream(streamId).first()
        return epg.firstOrNull()?.description?.takeIf { it.isNotBlank() } ?: ""
    }

    suspend fun getMiniEpgProgress(streamId: Int): Int {
        val epg = repository.getEpgForStream(streamId).first()
        val now = epg.firstOrNull() ?: return 0
        val start = if (now.startTimestamp < 100000000000L) now.startTimestamp * 1000 else now.startTimestamp
        val stop = if (now.stopTimestamp < 100000000000L) now.stopTimestamp * 1000 else now.stopTimestamp
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