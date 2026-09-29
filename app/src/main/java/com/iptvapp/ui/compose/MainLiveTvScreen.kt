package com.iptvapp.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.ui.compose.theme.CyanGlow
import com.iptvapp.ui.compose.theme.CyanPrimary
import com.iptvapp.ui.compose.theme.LiveBadgeRed
import com.iptvapp.ui.compose.theme.SurfaceContainerHighest
import com.iptvapp.ui.compose.theme.SurfaceContainerLow
import com.iptvapp.ui.compose.theme.TextMuted
import com.iptvapp.ui.compose.theme.TextPrimary
import com.iptvapp.ui.compose.theme.TextSecondary
import com.iptvapp.ui.home.CombinedFavorite
import com.iptvapp.ui.home.HomeViewModel
import com.iptvapp.util.ChannelQualityTag
import kotlinx.coroutines.delay

internal const val FAVORITES_FILTER_ID = "__favorites__"

/**
 * The "Main Live TV" scaffold: a persistent mini-player pinned above a filterable/searchable
 * channel list. Built against real data throughout — see kdoc on the pieces below for exactly
 * what that means and what got dropped from the original design spec as fabricated (a
 * "CH 104"-style logical-channel-number pill, a live sports score/clock overlay, and three
 * sub-filter chips — HEVC/H.265, Catch-up, 5.1 Audio — for per-channel attributes this app
 * doesn't actually track). [onExpandFullScreen] hands off to the caller's own full-screen player
 * (ComposePlayerScreen in ComposeUiPreviewActivity) rather than owning that itself, so the
 * mini-player's ExoPlayer here and the full-screen one are two independent, sequential instances
 * — never both holding the decoder at once.
 *
 * [activeChannel]/[selectedFilterId]/[searchQuery] are hoisted up to the caller (rather than
 * `remember`ed locally) because this composable gets removed from composition whenever the
 * caller shows the full-screen player instead — see ComposeUiPreviewActivity. [isInPip] switches
 * to a video-only layout, since Android shrinks whatever this function composes into the PiP
 * window rather than hiding any of it automatically.
 */
@Composable
fun MainLiveTvScreen(
    viewModel: HomeViewModel,
    repository: XtreamRepository,
    db: IptvDatabase,
    prefs: PreferencesManager,
    activeChannel: ComposeChannelUiState?,
    onActiveChannelChange: (ComposeChannelUiState?) -> Unit,
    selectedFilterId: String,
    onSelectedFilterIdChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isInPip: Boolean,
    onExpandFullScreen: (ComposeChannelUiState) -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current

    val favorites by viewModel.combinedFavorites.collectAsStateWithLifecycle()
    val categories by viewModel.liveCategories.collectAsStateWithLifecycle()
    val categoryChannels by viewModel.channels.collectAsStateWithLifecycle()
    val epgText by viewModel.channelEpgText.collectAsStateWithLifecycle()
    val epgProgress by viewModel.channelEpgProgress.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    // Real persisted last-successful-fetch time (only ever set when fetchLiveStreams() actually
    // succeeds — see XtreamRepository.fetchLiveStreams/isChannelCacheStale), not a guess from
    // watching `loading` flip back to false, which also happened after a *failed* refresh (the
    // ViewModel resets loading in a `finally`) and read "just now" from the moment this screen
    // opened even when loadAll() found the cache already fresh and skipped the network entirely.
    val lastSyncedAtMs by prefs.lastChannelsFetchTime.collectAsStateWithLifecycle(initialValue = 0L)

    var totalChannelCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        totalChannelCount = try { db.channelDao().getCount() } catch (_: Exception) { 0 }
    }
    LaunchedEffect(selectedFilterId) {
        if (selectedFilterId != FAVORITES_FILTER_ID) viewModel.selectLiveCategory(selectedFilterId)
    }

    val favoriteChannels = favorites.filterIsInstance<CombinedFavorite.Primary>().map { it.channel }
    val baseList = if (selectedFilterId == FAVORITES_FILTER_ID) favoriteChannels else categoryChannels
    val favoriteIds = favoriteChannels.map { it.streamId }.toSet()
    val filteredList = if (searchQuery.isBlank()) baseList
        else baseList.filter { it.name.contains(searchQuery, ignoreCase = true) }

    // Keyed off baseList (the category/favorites selection), not filteredList — EPG text/
    // progress is per-channel data that doesn't depend on the search text, and
    // loadEpgForChannels launches its own uncancellable viewModelScope job on every call (a
    // 150ms-paced per-channel fetch loop — see HomeViewModel kdoc on the 429 storm that pacing
    // exists to avoid). Keying this off the search-filtered list instead fired a fresh
    // overlapping fetch loop on every keystroke, racing past ones and risking that same
    // provider-side rate limiting. Once loaded for the base list, search just filters the
    // already-cached epgText/epgProgress maps below — no re-fetch needed.
    LaunchedEffect(baseList.map { it.streamId }) {
        if (baseList.isNotEmpty()) viewModel.loadEpgForChannels(baseList)
    }

    val channelStates = filteredList.map { ch ->
        ComposeChannelUiState(
            streamId = ch.streamId,
            name = ch.name,
            logoUrl = ch.streamIcon,
            qualityBadge = ChannelQualityTag.labelFor(ch.name),
            currentProgramTitle = epgText[ch.streamId],
            currentProgramProgress = (epgProgress[ch.streamId] ?: 0) / 100f,
            isFavorite = ch.streamId in favoriteIds
        )
    }

    // The mini player's own ExoPlayer — a separate, shorter-lived instance from the full-screen
    // one ComposeUiPreviewActivity creates on expand; never both alive at once (see class kdoc).
    val miniPlayer = remember { ExoPlayer.Builder(context).build() }
    var telemetry by remember { mutableStateOf(PlayerTelemetry()) }

    DisposableEffect(miniPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val v = miniPlayer.videoFormat
                telemetry = PlayerTelemetry(
                    resolution = v?.let { if (it.height >= 2000) "4K UHD" else "${it.height}p" },
                    frameRate = v?.frameRate?.takeIf { it > 0 }?.toInt(),
                    bitrateKbps = v?.bitrate?.takeIf { it > 0 }?.let { it / 1000 }
                )
            }
        }
        miniPlayer.addListener(listener)
        onDispose {
            miniPlayer.removeListener(listener)
            miniPlayer.release()
        }
    }

    LaunchedEffect(activeChannel?.streamId) {
        val ch = activeChannel ?: return@LaunchedEffect
        val url = repository.getLiveStreamUrl(ch.streamId)
        miniPlayer.setMediaItem(MediaItem.fromUri(url))
        miniPlayer.prepare()
        miniPlayer.playWhenReady = true
    }

    if (isInPip) {
        // Android still composes and shrinks this function's entire output into the small PiP
        // window rather than hiding anything on its own — without this branch the channel list,
        // filter bar and sync status bar all got squeezed down alongside the video instead of
        // only the video showing. The PiP button is only enabled once a channel is already
        // active (see MiniPlayerHeader), so the placeholder below is a defensive fallback, not
        // the expected path.
        Box(modifier = Modifier.fillMaxSize()) {
            if (activeChannel != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { PlayerView(it).apply { useController = false; player = miniPlayer } }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(SurfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Hold a channel to preview it here", color = TextMuted, fontSize = 13.sp)
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MiniPlayerHeader(
            channel = activeChannel,
            player = miniPlayer,
            telemetry = telemetry,
            onExpand = { activeChannel?.let(onExpandFullScreen) },
            onPip = onEnterPip
        )

        FilterBar(
            favoritesCount = favoriteChannels.size,
            categories = categories,
            selectedFilterId = selectedFilterId,
            onSelectFilter = onSelectedFilterIdChange,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(channelStates, key = { it.streamId }) { channel ->
                    ChannelItemRow(
                        channel = channel,
                        onSelectChannel = onActiveChannelChange,
                        onToggleFavorite = { viewModel.toggleChannelFavorite(it.streamId) }
                    )
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }
        }

        SyncStatusBar(
            lastSyncedAtMs = lastSyncedAtMs,
            isSyncing = loading,
            onResync = {
                viewModel.refreshNow()
            }
        )
    }
}

@Composable
private fun MiniPlayerHeader(
    channel: ComposeChannelUiState?,
    player: ExoPlayer,
    telemetry: PlayerTelemetry,
    onExpand: () -> Unit,
    onPip: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(SurfaceContainerLow)
    ) {
        if (channel != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { PlayerView(it).apply { useController = false; this.player = player } }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Hold a channel to preview it here", color = TextMuted, fontSize = 13.sp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.85f)
                    )
                )
        )

        // Top-left: LIVE badge + real resolution/fps pill (blank until the player has actually
        // reported a format — never a hardcoded "4K UHD • 60 FPS").
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (channel != null) {
                Surface(shape = RoundedCornerShape(4.dp), color = LiveBadgeRed) {
                    Text(
                        "● LIVE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                val telemetryLabel = listOfNotNull(
                    telemetry.resolution,
                    telemetry.frameRate?.let { "$it FPS" }
                ).joinToString(" • ")
                if (telemetryLabel.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = Color.Black.copy(alpha = 0.5f)) {
                        Text(
                            telemetryLabel,
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Top-right: PiP + full-screen. PiP calls the Activity's real
        // enterPictureInPictureMode() (see ComposeUiPreviewActivity) — genuine system PiP, the
        // same capability PlayerActivity already offers, not a decorative button.
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            IconButton(onClick = onPip, enabled = channel != null) {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = TextPrimary)
            }
            IconButton(onClick = onExpand, enabled = channel != null) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Full screen", tint = TextPrimary)
            }
        }

        // Bottom overlay: real channel name + real EPG "now" title (no fabricated score/clock —
        // this app has no sports-score data source at all) + real progress.
        if (channel != null) {
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).fillMaxWidth()) {
                Text(
                    channel.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (channel.currentProgramTitle != null) {
                    Text(channel.currentProgramTitle, color = CyanGlow, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { channel.currentProgramProgress },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = CyanPrimary,
                        trackColor = SurfaceContainerHighest
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    favoritesCount: Int,
    categories: List<com.iptvapp.data.local.entities.CategoryEntity>,
    selectedFilterId: String,
    onSelectFilter: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    label = "★ Favorites ($favoritesCount)",
                    selected = selectedFilterId == FAVORITES_FILTER_ID,
                    onClick = { onSelectFilter(FAVORITES_FILTER_ID) }
                )
            }
            items(categories, key = { it.categoryId }) { cat ->
                FilterChip(
                    label = cat.categoryName,
                    selected = selectedFilterId == cat.categoryId,
                    onClick = { onSelectFilter(cat.categoryId) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text("Search this list...", color = TextMuted, fontSize = 13.sp)
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(CyanPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) CyanPrimary else SurfaceContainerLow,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (selected) Color.Black else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SyncStatusBar(lastSyncedAtMs: Long, isSyncing: Boolean, onResync: () -> Unit) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); nowMs = System.currentTimeMillis() }
    }
    val elapsedMin = ((nowMs - lastSyncedAtMs) / 60_000).coerceAtLeast(0)
    val label = when {
        isSyncing -> "Syncing..."
        lastSyncedAtMs <= 0L -> "Not yet synced"
        elapsedMin < 1 -> "Live channels synced • just now"
        else -> "Live channels synced • ${elapsedMin}m ago"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(
            "RE-SYNC",
            color = CyanPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = !isSyncing, onClick = onResync)
        )
    }
}
