package com.iptvapp.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One logical channel. [groupFavorite] is the group's own star. It is not ChannelEntity.isFavorite
 * and it is not MergedChannelEntity.isFavorite. Nothing in this table is a playback URL.
 */
@Entity(tableName = "smart_channel_groups")
data class SmartChannelGroupEntity(
    @PrimaryKey val groupId: String,
    val displayName: String,
    val preferredServerIndex: Int,
    val preferredStreamId: Int,
    val hasPreferred: Boolean,
    val failoverEnabled: Boolean,
    val origin: String,
    val reviewState: String,
    val reason: String,
    val groupFavorite: Boolean,
    val updatedAt: Long,
)

/**
 * One provider's copy inside a group. [epgChannelId] and [nameFingerprint] are the identity that
 * was grouped. A later catalog row that reuses [streamId] for a different channel does not match.
 */
@Entity(
    tableName = "smart_channel_members",
    primaryKeys = ["groupId", "serverIndex", "streamId"],
    indices = [Index(value = ["serverIndex", "streamId"])],
)
data class SmartChannelMemberEntity(
    val groupId: String,
    val serverIndex: Int,
    val streamId: Int,
    val epgChannelId: String,
    val nameFingerprint: String,
    val displayName: String,
    val eligible: Boolean,
    val userPriority: Int,
)

/** A pair the viewer refused. Keys include the name fingerprint, so a reused stream id stays free. */
@Entity(
    tableName = "smart_channel_rejections",
    primaryKeys = ["leftKey", "rightKey"],
)
data class SmartChannelRejectionEntity(
    val leftKey: String,
    val rightKey: String,
    val leftName: String,
    val rightName: String,
    val reason: String,
    val createdAt: Long,
)
