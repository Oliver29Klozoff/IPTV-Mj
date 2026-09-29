package com.iptvapp.ui.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val IptvDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = SurfaceBackground,
    background = SurfaceBackground,
    onBackground = TextPrimary,
    surface = SurfaceBackground,
    onSurface = TextPrimary,
    surfaceContainer = SurfaceContainerLow,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = OutlineStroke,
    onSurfaceVariant = TextSecondary
)

/** OLED dark theme for the experimental Compose UI preview screens (ChannelItemRow,
 * PlayerOsdControls) — deliberately separate from the rest of the app, which is View/XML with its
 * own drawables/colors.xml and isn't touched by this. */
@Composable
fun IptvComposeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IptvDarkColorScheme,
        content = content
    )
}
