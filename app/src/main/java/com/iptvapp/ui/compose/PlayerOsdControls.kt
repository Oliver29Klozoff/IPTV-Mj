package com.iptvapp.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptvapp.ui.compose.theme.CyanGlow
import com.iptvapp.ui.compose.theme.CyanPrimary
import com.iptvapp.ui.compose.theme.SurfaceContainerHighest
import com.iptvapp.ui.compose.theme.SurfaceContainerLow
import com.iptvapp.ui.compose.theme.TextPrimary
import com.iptvapp.ui.compose.theme.TextSecondary

/** [dvrProgress]/[onSeek] are a simplified live position bar (currentPosition / duration on the
 * real player, dragging calls player.seekTo) — real, but not the same back-buffer/timeshift
 * system the production player uses (see PlayerActivity.buildPlayer's DefaultLoadControl). Good
 * enough for this comparison screen, not a claim of full parity. */
@Composable
fun PlayerOsdControls(
    channelName: String,
    qualityBadge: String?,
    currentProgramTitle: String?,
    isPlaying: Boolean,
    telemetry: PlayerTelemetry,
    dvrProgress: Float,
    onPlayPauseToggle: () -> Unit,
    onBackClick: () -> Unit,
    onRewind10s: () -> Unit,
    onGoLive: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.85f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.92f)
                    )
                )
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (qualityBadge != null) "$channelName • $qualityBadge" else channelName,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentProgramTitle ?: "Live Broadcast",
                    color = CyanGlow,
                    fontSize = 13.sp
                )
            }
            // Real audio channel layout (player.audioFormat?.channelCount) — absent, not a
            // hardcoded "5.1 SURROUND", until the player actually reports one.
            if (telemetry.audioChannels != null) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = SurfaceContainerHighest) {
                    Text(
                        telemetry.audioChannels,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            IconButton(
                onClick = onRewind10s,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(SurfaceContainerLow)
            ) {
                Icon(Icons.Default.Replay10, contentDescription = "Back 10s", tint = TextPrimary)
            }

            FilledIconButton(
                onClick = onPlayPauseToggle,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = CyanPrimary)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(
                onClick = onGoLive,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(SurfaceContainerLow)
            ) {
                Icon(Icons.Default.Forward10, contentDescription = "Go to Live", tint = TextPrimary)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("LIVE POSITION", color = CyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                // Real telemetry off the actual player (resolution/frame rate/bitrate) — each
                // piece just omitted from the line until the player has actually reported it,
                // instead of a fixed "60 FPS • 8.4 Mbps • ExoPlayer v2" string.
                val parts = listOfNotNull(
                    telemetry.resolution,
                    telemetry.frameRate?.let { "$it fps" },
                    telemetry.bitrateKbps?.let { "${it / 1000f} Mbps" }
                )
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString(" • "), color = TextSecondary, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Slider(
                value = dvrProgress.coerceIn(0f, 1f),
                onValueChange = onSeek,
                colors = SliderDefaults.colors(
                    thumbColor = CyanPrimary,
                    activeTrackColor = CyanPrimary,
                    inactiveTrackColor = SurfaceContainerHighest
                )
            )
        }
    }
}
