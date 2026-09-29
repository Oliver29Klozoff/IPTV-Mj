package com.iptvapp.ui.compose

/** What ChannelItemRow/PlayerOsdControls actually need, mapped from real app data
 * (ChannelEntity + HomeViewModel's channelEpgText/channelEpgProgress/channelEpgNextText maps) —
 * kept separate from the Room entity so the Compose layer doesn't depend on the DB schema.
 *
 * Deliberately drops a few fields the original Stitch-generated spec had that don't correspond
 * to anything this app tracks: LCN (channel numbering here is provider order, not a broadcast
 * LCN), a per-row Mbps figure (bitrate is only knowable once a stream is actually playing, not
 * for a row sitting in a list), and a decoder-engine/WireGuard config (this app is Media3/
 * ExoPlayer only, no VLC fallback or VPN tunnel). See ComposeUiPreviewActivity kdoc for the full
 * reasoning.
 */
data class ComposeChannelUiState(
    val streamId: Int,
    val name: String,
    val logoUrl: String?,
    val qualityBadge: String?,
    val currentProgramTitle: String?,
    val currentProgramProgress: Float,
    val isFavorite: Boolean
)

/** Real, live telemetry read off the actual ExoPlayer instance (see
 * ComposeUiPreviewActivity.attachTelemetry) — never hardcoded, absent (null) rather than faked
 * when the player hasn't reported a value yet. */
data class PlayerTelemetry(
    val resolution: String? = null,
    val frameRate: Int? = null,
    val bitrateKbps: Int? = null,
    val audioChannels: String? = null
)
