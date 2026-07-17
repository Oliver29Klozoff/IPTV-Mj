package com.iptvapp.ui.home

import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity

// Display-only union so the Favorites tab's genre classification/list can show primary-server
// favorites and Providers-tab (any other configured server) favorites together, without
// unifying their underlying entities/DAOs — those stay exactly as they are (see
// MergedChannelEntity kdoc for why: composite serverIndex+streamId key, no recording/Trakt
// eligibility, its own folder-assignment API). categoryName is resolved/denormalized onto the
// wrapper at combine time so genre classification is one function over CombinedFavorite instead
// of two parallel implementations.
sealed class CombinedFavorite {
    abstract val name: String
    abstract val streamIcon: String?
    abstract val categoryName: String?
    abstract val serverNickname: String?  // null = primary server, no tag shown
    abstract val id: String               // stable string key for DiffUtil / EPG+health maps

    data class Primary(
        val channel: ChannelEntity,
        override val categoryName: String?
    ) : CombinedFavorite() {
        override val name get() = channel.name
        override val streamIcon get() = channel.streamIcon
        override val serverNickname: String? = null
        override val id get() = "primary:${channel.streamId}"
    }

    data class Merged(val channel: MergedChannelEntity) : CombinedFavorite() {
        override val name get() = channel.name
        override val streamIcon get() = channel.streamIcon
        override val categoryName get() = channel.categoryName
        override val serverNickname get() = channel.serverNickname
        override val id get() = "${channel.serverIndex}:${channel.streamId}"
    }
}
