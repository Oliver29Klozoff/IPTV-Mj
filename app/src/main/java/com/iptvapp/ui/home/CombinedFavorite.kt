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
    abstract val serverNickname: String?  // null = tag hidden entirely
    abstract val id: String               // stable string key for DiffUtil / EPG+health maps
    // Shared flat ordering across primary AND merged favorites — see
    // MergedChannelEntity.favOrder kdoc for why this needed to exist on both underlying tables.
    abstract val favOrder: Int
    // Manual genre-chip pin — see ChannelEntity.manualGenre kdoc.
    abstract val manualGenre: String?
    // User-created Favorite Folder this channel is filed under (see FavoriteFolderEntity) — now
    // also browsable as its own chip on the Favorites tab (HomeActivity's folder-chip row),
    // alongside the fixed genre chips. Null = not in a folder.
    abstract val favoriteFolderId: Int?

    data class Primary(
        val channel: ChannelEntity,
        override val categoryName: String?,
        override val serverNickname: String? = null
    ) : CombinedFavorite() {
        override val name get() = channel.name
        override val streamIcon get() = channel.streamIcon
        override val id get() = "primary:${channel.streamId}"
        override val favOrder get() = channel.favOrder
        override val manualGenre get() = channel.manualGenre
        override val favoriteFolderId get() = channel.favoriteFolderId
    }

    // nicknameOverride defaults to the entity's own (possibly stale) column so every existing
    // call site keeps working unchanged — HomeViewModel's combine() passes the current live
    // nickname explicitly (see its kdoc) since channel.serverNickname is only a snapshot from
    // whenever that row was last refreshed, and goes stale the moment a provider is renamed or
    // reassigned between primary/secondary (a synced-back favorite keeps the OLD nickname
    // forever otherwise, even though its star color — computed live from serverIndex — is
    // already correct).
    data class Merged(val channel: MergedChannelEntity, private val nicknameOverride: String? = null) : CombinedFavorite() {
        override val name get() = channel.name
        override val streamIcon get() = channel.streamIcon
        override val categoryName get() = channel.categoryName
        override val serverNickname get() = nicknameOverride ?: channel.serverNickname
        override val id get() = "${channel.serverIndex}:${channel.streamId}"
        override val favOrder get() = channel.favOrder
        override val manualGenre get() = channel.manualGenre
        override val favoriteFolderId get() = channel.favoriteFolderId
    }
}
