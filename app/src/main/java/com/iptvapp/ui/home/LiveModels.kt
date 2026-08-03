package com.iptvapp.ui.home

import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.MergedCategorySummary

// Same shape as GuideRow (ui/guide/GuideModels.kt) and CombinedFavorite — exactly one of
// channel/mergedChannel is non-null, common display fields exposed as properties so
// LiveCategoryAdapter/LiveChannelAdapter never need to branch on which one they have. Used to
// merge the primary provider's Live tab with every configured secondary/"other" provider into
// one combined list, color-coded by provider, instead of keeping them in separate tabs.
data class LiveCategoryRow(
    val category: CategoryEntity? = null,
    val serverIndex: Int = -1,
    val mergedCategoryId: String? = null,
    val mergedCategoryName: String? = null,
    val mergedChannelCount: Int = 0,
    val mergedServerNickname: String? = null
) {
    /** Unique across the whole combined list — primary categoryId is already globally unique
     * (one provider), merged categories are scoped "$serverIndex:$categoryId" since two
     * providers can reuse the same category id string (same convention as the old Providers
     * tab's synthetic CategoryEntity rows). */
    val id: String get() = category?.categoryId ?: "$serverIndex:${mergedCategoryId ?: "__uncategorized__"}"
    val name: String get() = category?.categoryName ?: "${mergedCategoryName ?: "Uncategorized"} (${mergedChannelCount})"
    /** Key into the favorite-category-star lookup — matches whichever prefs set
     * (FAVORITE_LIVE_CATEGORY_IDS vs FAVORITE_MERGED_CATEGORY_IDS) this row's provider uses. */
    val favoriteKey: String get() = category?.categoryId ?: "$serverIndex:${mergedCategoryId ?: "__uncategorized__"}"

    companion object {
        fun fromPrimary(category: CategoryEntity) = LiveCategoryRow(category = category)
        fun fromMerged(serverIndex: Int, summary: MergedCategorySummary) = LiveCategoryRow(
            serverIndex = serverIndex,
            mergedCategoryId = summary.categoryId,
            mergedCategoryName = summary.categoryName,
            mergedChannelCount = summary.channelCount
        )
    }
}

data class LiveChannelRow(
    val channel: ChannelEntity? = null,
    val mergedChannel: MergedChannelEntity? = null,
    // Every row in a given list belongs to the same one category being browsed, so this is set
    // once by selectCombinedCategory from the LiveCategoryRow that was tapped, not resolved
    // per-channel — ChannelEntity only carries categoryId, not the display name.
    val categoryName: String? = null
) {
    val serverIndex: Int get() = mergedChannel?.serverIndex ?: -1
    val streamId: Int get() = channel?.streamId ?: mergedChannel!!.streamId
    val name: String get() = channel?.name ?: mergedChannel!!.name
    val streamIcon: String? get() = channel?.streamIcon ?: mergedChannel?.streamIcon
    val isFavorite: Boolean get() = channel?.isFavorite ?: mergedChannel!!.isFavorite
    /** Matches ChannelAdapter/MergedChannelAdapter/CombinedFavorite's existing id conventions
     * ("primary:$streamId" vs "$serverIndex:$streamId") — reusing the same string lets EPG/
     * health/currently-playing maps built for those other lists key identically here. */
    val id: String get() = channel?.let { "primary:${it.streamId}" } ?: "${mergedChannel!!.serverIndex}:${mergedChannel.streamId}"
}
