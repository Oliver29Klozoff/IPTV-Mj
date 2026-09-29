package com.iptvapp.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.iptvapp.ui.compose.theme.CyanGlow
import com.iptvapp.ui.compose.theme.CyanPrimary
import com.iptvapp.ui.compose.theme.FavoriteGold
import com.iptvapp.ui.compose.theme.OutlineStroke
import com.iptvapp.ui.compose.theme.SurfaceContainerHighest
import com.iptvapp.ui.compose.theme.SurfaceContainerLow
import com.iptvapp.ui.compose.theme.TextMuted
import com.iptvapp.ui.compose.theme.TextPrimary
import com.iptvapp.ui.compose.theme.TextSecondary

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ChannelItemRow(
    channel: ComposeChannelUiState,
    onSelectChannel: (ComposeChannelUiState) -> Unit,
    onToggleFavorite: (ComposeChannelUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelectChannel(channel) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(OutlineStroke))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Real channel logo — same artwork item_channel.xml shows via Glide, not a
                // fabricated "LCN" number (this app has no logical-channel-number concept).
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceContainerHighest)
                ) {
                    if (channel.logoUrl != null) {
                        GlideImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channel.name,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Real quality tag parsed from the channel name (see ChannelQualityTag) —
                        // not shown at all when the name doesn't carry one, rather than a fake
                        // "1080p" default every row would otherwise get.
                        if (channel.qualityBadge != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = SurfaceContainerHighest
                            ) {
                                Text(
                                    text = channel.qualityBadge,
                                    color = CyanGlow,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = channel.currentProgramTitle ?: "Guide loading...",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { onToggleFavorite(channel) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (channel.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (channel.isFavorite) FavoriteGold else TextMuted
                    )
                }
            }

            if (channel.currentProgramTitle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { channel.currentProgramProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CyanPrimary,
                    trackColor = SurfaceContainerHighest
                )
            }
        }
    }
}
