package com.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptvapp.data.local.entities.SmartChannelGroupEntity
import com.iptvapp.data.local.entities.SmartChannelMemberEntity
import com.iptvapp.data.local.entities.SmartChannelRejectionEntity

@Dao
interface SmartChannelDao {
    @Query("SELECT * FROM smart_channel_groups")
    suspend fun groups(): List<SmartChannelGroupEntity>

    @Query("SELECT * FROM smart_channel_members")
    suspend fun members(): List<SmartChannelMemberEntity>

    @Query("SELECT * FROM smart_channel_rejections")
    suspend fun rejections(): List<SmartChannelRejectionEntity>

    @Query("SELECT * FROM smart_channel_groups WHERE groupId = :id")
    suspend fun group(id: String): SmartChannelGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: SmartChannelGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<SmartChannelMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRejection(row: SmartChannelRejectionEntity)

    @Query("DELETE FROM smart_channel_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: String)

    @Query("DELETE FROM smart_channel_groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM smart_channel_members WHERE groupId = :groupId AND serverIndex = :serverIndex AND streamId = :streamId")
    suspend fun deleteMember(groupId: String, serverIndex: Int, streamId: Int)

    @Query("DELETE FROM smart_channel_members WHERE groupId IN (SELECT groupId FROM smart_channel_groups WHERE origin = 'AUTO' AND reviewState != 'VERIFIED')")
    suspend fun deleteUnverifiedAutoMembers()

    @Query("DELETE FROM smart_channel_groups WHERE origin = 'AUTO' AND reviewState != 'VERIFIED'")
    suspend fun deleteUnverifiedAutoGroups()

    @Query("DELETE FROM smart_channel_members WHERE groupId IN (SELECT groupId FROM smart_channel_groups WHERE origin = 'AUTO')")
    suspend fun deleteAutoMembers()

    @Query("DELETE FROM smart_channel_groups WHERE origin = 'AUTO'")
    suspend fun deleteAutoGroups()

    @Query("DELETE FROM smart_channel_rejections WHERE reason = :reason")
    suspend fun deleteRejectionsByReason(reason: String)
}
