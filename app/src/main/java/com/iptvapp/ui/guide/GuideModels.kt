package com.iptvapp.ui.guide

import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.MergedChannelEntity

// Exactly one of channel/mergedChannel is non-null — primary-provider rows keep the original
// ChannelEntity (so existing timeshift/replay/recording features, which are primary-provider-
// only concepts via tvArchive/ChannelTimerScheduler/RecordingSchedulerActivity, work completely
// unchanged) while merged/secondary-provider rows carry a MergedChannelEntity instead. Common
// display fields are exposed as properties so GuideAdapter doesn't need to branch on which one
// it has for the parts that are shared (name, streamId, icon).
data class GuideRow(
    val channel: ChannelEntity? = null,
    val mergedChannel: MergedChannelEntity? = null,
    val programs: List<EpgEntity>
) {
    /** -1 for a primary-provider row, 0..N-1 for a merged/secondary-provider row — matches the
     * serverIndex convention used everywhere else (RecordingEntity, MergedChannelEntity, and
     * now EpgEntity). */
    val serverIndex: Int get() = mergedChannel?.serverIndex ?: -1
    val streamId: Int get() = channel?.streamId ?: mergedChannel!!.streamId
    val name: String get() = channel?.name ?: mergedChannel!!.name
    val streamIcon: String? get() = channel?.streamIcon ?: mergedChannel?.streamIcon
    /** Timeshift/replay only exists for primary-provider channels right now — merged channels
     * have no tvArchive flag at all (MergedChannelEntity doesn't track it). */
    val supportsReplay: Boolean get() = channel?.tvArchive == 1
    /** Display label for the provider color legend — "Primary" or the configured nickname. */
    val providerLabel: String get() = mergedChannel?.serverNickname ?: "Primary"
}

// One fixed accent color per serverIndex, shared by both the Guide list (GuideAdapter) and the
// Grid/Timeline view (EpgTimelineActivity/TimelineAdapter) so a given provider always reads as
// the same color in both places. -1 (primary) is deliberately neutral/no-stripe — a provider
// color palette every row shares would drown out the existing NOW/replay program-text coloring
// otherwise, so only merged rows get an accent stripe; primary rows stay exactly as before.
private val PROVIDER_COLORS = listOf(
    0xFF00AAFF.toInt(), // server 0
    0xFFFF6B6B.toInt(), // server 1
    0xFFFFC107.toInt(), // server 2
    0xFF9C6BFF.toInt(), // server 3
    0xFF4CD964.toInt(), // server 4
    0xFFFF8A65.toInt()  // server 5+ (repeats via modulo beyond this)
)

fun providerColorFor(serverIndex: Int): Int? =
    if (serverIndex < 0) null else PROVIDER_COLORS[serverIndex % PROVIDER_COLORS.size]
