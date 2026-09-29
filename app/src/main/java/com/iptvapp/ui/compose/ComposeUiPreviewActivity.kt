package com.iptvapp.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.iptvapp.ui.compose.theme.IptvComposeTheme
import com.iptvapp.ui.compose.theme.SurfaceBackground
import com.iptvapp.ui.home.CombinedFavorite
import com.iptvapp.ui.home.HomeViewModel
import com.iptvapp.util.ChannelQualityTag
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Experimental Compose UI preview — reworks the favorites list and live player OSD in the OLED/
 * cyan look from a Stitch-generated spec, but bound to REAL data via the existing HomeViewModel/
 * XtreamRepository rather than the spec's fabricated model (see ChannelItemRow/PlayerOsdControls
 * kdoc for what got dropped and why: no LCN, no per-row Mbps, no decoder-engine picker, no
 * WireGuard toggle — none of those exist in this app).
 *
 * Deliberately NOT wired into any real navigation flow — reachable only via Settings > Backup &
 * Restore > "Compose UI Preview (Experimental)" so it can be compared side-by-side with the real
 * (View/XML) screens without risking anything currently shipping. This is the only screen in the
 * app using Jetpack Compose; everything else stays View/XML + ViewBinding.
 *
 * Scope for this first pass: primary-server favorites only (CombinedFavorite.Primary) — merged/
 * secondary-provider favorites resolve their stream URL and favorite-toggle differently and
 * aren't wired up here yet.
 */
@AndroidEntryPoint
class ComposeUiPreviewActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadAll()
        setContent {
            IptvComposeTheme {
                Surface(color = SurfaceBackground, modifier = Modifier.fillMaxSize()) {
                    ComposeUiPreviewScreen(viewModel, repository)
                }
            }
        }
    }
}

@Composable
private fun ComposeUiPreviewScreen(
    viewModel: HomeViewModel,
    repository: com.iptvapp.data.repository.XtreamRepository
) {
    val favorites by viewModel.combinedFavorites.collectAsStateWithLifecycle()
    val epgText by viewModel.channelEpgText.collectAsStateWithLifecycle()
    val epgProgress by viewModel.channelEpgProgress.collectAsStateWithLifecycle()

    val primaryChannels = favorites.filterIsInstance<CombinedFavorite.Primary>().map { it.channel }

    LaunchedEffect(primaryChannels.map { it.streamId }) {
        if (primaryChannels.isNotEmpty()) viewModel.loadEpgForChannels(primaryChannels)
    }

    val channelStates = primaryChannels.map { ch ->
        ComposeChannelUiState(
            streamId = ch.streamId,
            name = ch.name,
            logoUrl = ch.streamIcon,
            qualityBadge = ChannelQualityTag.labelFor(ch.name),
            currentProgramTitle = epgText[ch.streamId],
            currentProgramProgress = (epgProgress[ch.streamId] ?: 0) / 100f,
            isFavorite = true // this screen only ever lists favorites
        )
    }

    var selected by remember { mutableStateOf<ComposeChannelUiState?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val current = selected
    if (current == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            items(channelStates, key = { it.streamId }) { channel ->
                ChannelItemRow(
                    channel = channel,
                    onSelectChannel = { selected = it },
                    onToggleFavorite = { viewModel.toggleChannelFavorite(it.streamId) }
                )
            }
        }
    } else {
        ComposePlayerScreen(
            channel = current,
            repository = repository,
            onBack = { selected = null }
        )
    }
}

@Composable
private fun ComposePlayerScreen(
    channel: ComposeChannelUiState,
    repository: com.iptvapp.data.repository.XtreamRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(true) }
    var telemetry by remember { mutableStateOf(PlayerTelemetry()) }
    var dvrProgress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val v = exoPlayer.videoFormat
                val a = exoPlayer.audioFormat
                telemetry = PlayerTelemetry(
                    resolution = v?.let { "${it.width}x${it.height}" },
                    frameRate = v?.frameRate?.takeIf { it > 0 }?.toInt(),
                    bitrateKbps = v?.bitrate?.takeIf { it > 0 }?.let { it / 1000 },
                    audioChannels = a?.channelCount?.let {
                        when (it) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> "${it}CH" }
                    }
                )
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(channel.streamId) {
        val url = repository.getLiveStreamUrl(channel.streamId)
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Simplified live-position readout — polls the real player rather than the production
    // DVR/back-buffer system (see PlayerOsdControls kdoc).
    LaunchedEffect(exoPlayer) {
        while (true) {
            val duration = exoPlayer.duration
            dvrProgress = if (duration > 0) exoPlayer.currentPosition / duration.toFloat() else 0f
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    useController = false
                    player = exoPlayer
                }
            }
        )
        PlayerOsdControls(
            channelName = channel.name,
            qualityBadge = channel.qualityBadge,
            currentProgramTitle = channel.currentProgramTitle,
            isPlaying = isPlaying,
            telemetry = telemetry,
            dvrProgress = dvrProgress,
            onPlayPauseToggle = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady },
            onBackClick = onBack,
            onRewind10s = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0)) },
            onGoLive = { exoPlayer.seekToDefaultPosition() },
            onSeek = { fraction ->
                scope.launch {
                    val duration = exoPlayer.duration
                    if (duration > 0) exoPlayer.seekTo((fraction * duration).toLong())
                }
            }
        )
    }
}
