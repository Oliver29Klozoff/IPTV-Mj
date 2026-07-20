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
}
