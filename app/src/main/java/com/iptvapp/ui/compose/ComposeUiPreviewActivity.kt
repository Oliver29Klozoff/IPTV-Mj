package com.iptvapp.ui.compose

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.iptvapp.ui.compose.theme.IptvComposeTheme
import com.iptvapp.ui.compose.theme.SurfaceBackground
import com.iptvapp.ui.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Experimental Compose UI preview — reworks the favorites/live channel list and player OSD in
 * the OLED/cyan look from a Stitch-generated spec (see MainLiveTvScreen/ChannelItemRow/
 * PlayerOsdControls kdoc for exactly what's real vs. what got dropped as fabricated: no LCN
 * numbers, no per-row Mbps, no live sports score/clock, no HEVC/Catch-up/5.1-Audio filter chips,
 * no decoder-engine picker, no WireGuard toggle — none of those exist in this app).
 *
 * Deliberately NOT wired into any real navigation flow — reachable only via Settings > Backup &
 * Restore > "Compose UI Preview (Experimental)" so it can be compared side-by-side with the real
 * (View/XML) screens without risking anything currently shipping. This is the only screen in the
 * app using Jetpack Compose; everything else stays View/XML + ViewBinding.
 */
@AndroidEntryPoint
class ComposeUiPreviewActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    @Inject lateinit var db: com.iptvapp.data.local.IptvDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadAll()
        setContent {
            IptvComposeTheme {
                Surface(color = SurfaceBackground, modifier = Modifier.fillMaxSize()) {
                    var fullScreenChannel by remember { mutableStateOf<ComposeChannelUiState?>(null) }
                    BackHandler(enabled = fullScreenChannel != null) { fullScreenChannel = null }

                    val channel = fullScreenChannel
                    if (channel == null) {
                        MainLiveTvScreen(
                            viewModel = viewModel,
                            repository = repository,
                            db = db,
                            onExpandFullScreen = { fullScreenChannel = it },
                            onEnterPip = { enterRealPictureInPicture() }
                        )
                    } else {
                        ComposePlayerScreen(
                            channel = channel,
                            repository = repository,
                            onBack = { fullScreenChannel = null }
                        )
                    }
                }
            }
        }
    }

    // Real system PiP (same API PlayerActivity's own buildPipParams/enterPictureInPictureMode
    // uses), just without that screen's extra polish (a custom play/pause PiP action icon,
    // live-tracked aspect ratio) — this is a comparison screen, not a claim of full parity.
    private fun enterRealPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
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
