package com.iptvapp.data.smart

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.SmartChannelGroupEntity
import com.iptvapp.data.local.entities.SmartChannelMemberEntity
import com.iptvapp.data.local.entities.SmartChannelRejectionEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.util.FeedScore
import com.iptvapp.util.SmartChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved Smart Channel groups. Rebuild reads channel names and guide ids already in the local
 * catalog. It does not refresh a provider, and it does not write channel, favorite, or recording rows.
 */
@Singleton
class SmartChannelStore @Inject constructor(
    private val db: IptvDatabase,
    private val repository: XtreamRepository,
) {
    private val dao get() = db.smartChannelDao()
    private val gate = Mutex()
    private var rebuildJob: Job? = null
    private var ticket = 0
    private val revision = MutableStateFlow(0)

    /** Bumps after each saved group change so an open channel list can reshape itself. */
    val changes: StateFlow<Int> = revision.asStateFlow()

    private fun bump() {
        revision.value = revision.value + 1
    }

    fun cancelRebuild() {
        ticket++
        rebuildJob?.cancel()
        rebuildJob = null
    }

    /** Reads the catalogs already stored on the device. Safe to call again; the older run stops. */
    fun scheduleRebuild(scope: CoroutineScope) {
        val mine = ++ticket
        rebuildJob?.cancel()
        rebuildJob = scope.launch(Dispatchers.Default) {
            val sources = repository.smartChannelSources()
            if (mine != ticket) return@launch
            rebuild(sources, System.currentTimeMillis(), mine)
        }
    }

    suspend fun rebuild(sources: List<SmartChannels.Source>, nowMs: Long, mine: Int = ticket) {
        val rejectedRows = withContext(Dispatchers.IO) { dao.rejections() }
        if (mine != ticket) return
        val rejected = rejectedRows.map { it.leftKey to it.rightKey }.toSet()
        val proposal = withContext(Dispatchers.Default) { SmartChannels.propose(sources, rejected) }
        if (mine != ticket) return
        withContext(Dispatchers.IO) {
            gate.withLock {
                if (mine != ticket) return@withLock
                db.withTransaction {
                    val existing = dao.groups().associateBy { it.groupId }
                    val existingMembers = dao.members().groupBy { it.groupId }
                    dao.deleteUnverifiedAutoMembers()
                    dao.deleteUnverifiedAutoGroups()
                    val live = sources.associateBy { it.key }
                    val removed = rejectedRows
                        .filter { it.reason == SmartChannels.REMOVED }
                        .groupBy { it.leftKey }
                        .mapValues { (_, rows) -> rows.map { it.rightKey }.toSet() }
                    val reserved = reservedManualKeys(existing.values, existingMembers, live)
                    val verifiedIds = HashSet<String>()
                    for (cluster in proposal.verified) {
                        val kept = SmartChannels.omittingRemoved(
                            cluster,
                            removed[SmartChannels.removalLeft(cluster.id)].orEmpty(),
                        )
                        val prior = existing[cluster.id]
                        if (kept.members.isEmpty()) {
                            deleteAutomatic(prior, cluster.id)
                            continue
                        }
                        if (prior != null && ownedByHand(prior)) {
                            verifiedIds += cluster.id
                            continue
                        }
                        val free = SmartChannels.excludingReserved(kept, reserved)
                        if (free == null) {
                            deleteAutomatic(prior, cluster.id)
                            continue
                        }
                        verifiedIds += free.id
                        writeCluster(free, prior, nowMs, SmartChannels.ORIGIN_AUTO)
                    }
                    for (cluster in proposal.suggested + proposal.ambiguous) {
                        val kept = SmartChannels.omittingRemoved(
                            cluster,
                            removed[SmartChannels.removalLeft(cluster.id)].orEmpty(),
                        )
                        if (kept.members.isEmpty()) continue
                        val prior = existing[cluster.id]
                        if (prior != null && ownedByHand(prior)) continue
                        val free = SmartChannels.excludingReserved(kept, reserved) ?: continue
                        writeCluster(free, prior, nowMs, SmartChannels.ORIGIN_AUTO)
                    }
                    val staleVerified = existing.values.filter {
                        it.origin == SmartChannels.ORIGIN_AUTO &&
                            it.reviewState == SmartChannels.VERIFIED &&
                            it.groupId !in verifiedIds
                    }
                    for (group in staleVerified) {
                        dao.deleteMembers(group.groupId)
                        dao.deleteGroup(group.groupId)
                    }
                    val claimed = HashSet<SmartChannels.Key>()
                    for (group in existing.values) {
                        if (!ownedByHand(group)) continue
                        val rows = existingMembers[group.groupId].orEmpty()
                        val open = rows.filter { SmartChannels.Key(it.serverIndex, it.streamId) !in claimed }
                        if (open.size < rows.size && open.size < 2) {
                            dao.deleteMembers(group.groupId)
                            dao.deleteGroup(group.groupId)
                            continue
                        }
                        refreshManual(group, open, live)
                        for (member in open) {
                            val source = live[SmartChannels.Key(member.serverIndex, member.streamId)] ?: continue
                            if (SmartChannels.isStale(member.epgChannelId, member.nameFingerprint, source)) continue
                            claimed += SmartChannels.Key(member.serverIndex, member.streamId)
                        }
                    }
                }
            }
        }
        bump()
    }

    suspend fun playbackGroups(): List<SmartChannels.PlaybackGroup> = withContext(Dispatchers.IO) {
        val members = dao.members().groupBy { it.groupId }
        dao.groups().map { it.toPlayback(members[it.groupId].orEmpty()) }
    }

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) {
        val members = dao.members().groupBy { it.groupId }
        val groups = dao.groups()
        Snapshot(
            groups = groups.map { group ->
                GroupSummary(
                    group = group,
                    members = members[group.groupId].orEmpty(),
                )
            },
            rejections = dao.rejections(),
        )
    }

    suspend fun playbackGroupId(serverIndex: Int, streamId: Int): String? {
        val key = SmartChannels.Key(serverIndex, streamId)
        return playbackGroups().firstOrNull { group ->
            (group.reviewState == SmartChannels.VERIFIED || group.reviewState == SmartChannels.MANUAL) &&
                group.members.any { it.key == key }
        }?.id
    }

    suspend fun toggleGroupFavorite(groupId: String) = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext
        dao.upsertGroup(group.copy(groupFavorite = !group.groupFavorite, updatedAt = System.currentTimeMillis()))
        bump()
    }

    /** Bulk removal asks for the group star to be off. It does not change the source star. */
    suspend fun clearGroupFavorites(groupIds: Collection<String>) = withContext(Dispatchers.IO) {
        var changed = false
        for (groupId in groupIds) {
            val group = dao.group(groupId) ?: continue
            if (!group.groupFavorite) continue
            dao.upsertGroup(group.copy(groupFavorite = false, updatedAt = System.currentTimeMillis()))
            changed = true
        }
        if (changed) bump()
    }

    suspend fun setPreferred(groupId: String, serverIndex: Int, streamId: Int) = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext
        dao.upsertGroup(
            group.copy(
                hasPreferred = true,
                preferredServerIndex = serverIndex,
                preferredStreamId = streamId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        bump()
    }

    suspend fun setFailover(groupId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext
        dao.upsertGroup(group.copy(failoverEnabled = enabled, updatedAt = System.currentTimeMillis()))
        bump()
    }

    suspend fun setEligible(groupId: String, serverIndex: Int, streamId: Int, eligible: Boolean) =
        withContext(Dispatchers.IO) {
            val members = dao.members().filter { it.groupId == groupId }
            val target = members.firstOrNull { it.serverIndex == serverIndex && it.streamId == streamId } ?: return@withContext
            dao.upsertMembers(listOf(target.copy(eligible = eligible)))
            bump()
        }

    suspend fun removeMember(groupId: String, serverIndex: Int, streamId: Int) = withContext(Dispatchers.IO) {
        val member = dao.members().firstOrNull {
            it.groupId == groupId && it.serverIndex == serverIndex && it.streamId == streamId
        }
        dao.deleteMember(groupId, serverIndex, streamId)
        if (member == null) return@withContext
        dao.upsertRejection(
            SmartChannelRejectionEntity(
                leftKey = SmartChannels.removalLeft(groupId),
                rightKey = SmartChannels.removalToken(member.serverIndex, member.streamId, member.nameFingerprint),
                leftName = member.displayName,
                rightName = "",
                reason = SmartChannels.REMOVED,
                createdAt = System.currentTimeMillis(),
            ),
        )
        bump()
    }

    /** Ungroup keeps the decision. The next catalog rebuild must not recreate the same match. */
    suspend fun ungroup(groupId: String) = rejectSuggestion(groupId)

    /** Drops automatic groups. Manual groups, rejections, channel rows, and recordings stay. */
    suspend fun restoreAutomaticGroups() = withContext(Dispatchers.IO) {
        dao.deleteAutoMembers()
        dao.deleteAutoGroups()
        dao.deleteRejectionsByReason(SmartChannels.REMOVED)
        bump()
    }

    suspend fun rejectSuggestion(groupId: String) = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext
        val members = dao.members().filter { it.groupId == groupId }
        val now = System.currentTimeMillis()
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                val a = members[i].toSource()
                val b = members[j].toSource()
                val pair = SmartChannels.rejectionPair(a, b)
                dao.upsertRejection(
                    SmartChannelRejectionEntity(
                        leftKey = pair.first,
                        rightKey = pair.second,
                        leftName = a.name,
                        rightName = b.name,
                        reason = group.reason,
                        createdAt = now,
                    ),
                )
            }
        }
        dao.deleteMembers(groupId)
        dao.deleteGroup(groupId)
        bump()
    }

    /**
     * Manual groups may include a mismatch only after [acknowledgedMismatch] is true.
     * Returns the reason that still needs a confirmation, or null when the group was saved.
     */
    suspend fun createManual(sources: List<SmartChannels.Source>, acknowledgedMismatch: Boolean): String? =
        withContext(Dispatchers.IO) {
            val distinct = sources.distinctBy { it.key }
            if (distinct.size < 2) return@withContext "Pick at least two channels."
            var worst = SmartChannels.VERIFIED
            var reason = SmartChannels.REASON_VERIFIED
            for (i in distinct.indices) {
                for (j in i + 1 until distinct.size) {
                    val match = SmartChannels.classify(distinct[i], distinct[j])
                    if (match.verdict == SmartChannels.SEPARATE || match.verdict == SmartChannels.AMBIGUOUS) {
                        worst = match.verdict
                        reason = match.reason
                    }
                }
            }
            if (!SmartChannels.manualGroupAllowed(worst, acknowledgedMismatch)) return@withContext reason
            val id = "m:" + distinct.joinToString(",") { "${it.serverIndex}:${it.streamId}:${SmartChannels.fingerprint(it.name)}" }
            val now = System.currentTimeMillis()
            gate.withLock {
                db.withTransaction {
                    dao.upsertGroup(
                        SmartChannelGroupEntity(
                            groupId = id,
                            displayName = distinct.first().name,
                            preferredServerIndex = distinct.first().serverIndex,
                            preferredStreamId = distinct.first().streamId,
                            hasPreferred = false,
                            failoverEnabled = true,
                            origin = SmartChannels.ORIGIN_MANUAL,
                            reviewState = SmartChannels.MANUAL,
                            reason = if (worst == SmartChannels.VERIFIED) "Grouped by hand." else "Grouped by hand after review. $reason",
                            groupFavorite = false,
                            updatedAt = now,
                        ),
                    )
                    dao.deleteMembers(id)
                    dao.upsertMembers(distinct.map { it.toMember(id) })
                    releaseOtherGroups(id, distinct.map { it.key }.toSet())
                }
            }
            bump()
            null
        }

    suspend fun acceptGroup(groupId: String) = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext
        if (group.reviewState == SmartChannels.AMBIGUOUS) return@withContext
        dao.upsertGroup(
            group.copy(
                origin = SmartChannels.ORIGIN_MANUAL,
                reviewState = if (group.reviewState == SmartChannels.VERIFIED) SmartChannels.VERIFIED else SmartChannels.MANUAL,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        bump()
    }

    /**
     * The next source inside the playing group. A channel that is not in a verified or manual
     * group returns [SmartChannels.BackupChoice.Legacy] so the existing name backup can run.
     * A verified group that has no safe backup returns [SmartChannels.BackupChoice.Exhausted]
     * and does not look outside the group.
     */
    suspend fun choose(
        session: SmartChannels.Session,
        qualified: Boolean,
        smartOn: Boolean,
        preferSelected: Boolean,
        nowMs: Long,
    ): SmartChannels.BackupChoice {
        val current = session.current
        val groups = playbackGroups()
        val live = repository.smartSource(current.serverIndex, current.streamId)
        val group = SmartChannels.playbackGroupFor(groups, current, live)
        val candidates = if (group == null) emptyList() else rankedCandidates(group, session, preferSelected)
        val request = SmartChannels.BackupRequest(
            smartOn = smartOn,
            inPlaybackGroup = group != null,
            groupFailoverEnabled = group?.failoverEnabled == true,
            qualifiedFailure = qualified,
            attempts = session.attempts(),
            nowMs = nowMs,
            lastSwitchAtMs = session.lastSwitchAt(),
            generation = session.generation,
            eventGeneration = session.generation,
            candidates = candidates,
        )
        return SmartChannels.decide(request)
    }

    private suspend fun rankedCandidates(
        group: SmartChannels.PlaybackGroup,
        session: SmartChannels.Session,
        preferSelected: Boolean,
    ): List<SmartChannels.Scored> {
        val tried = session.tried()
        val scored = ArrayList<SmartChannels.Scored>()
        for (member in group.members) {
            if (!member.eligible || member.key == session.current || member.key in tried) continue
            val live = repository.smartSource(member.key.serverIndex, member.key.streamId) ?: continue
            if (SmartChannels.isStale(member.epgChannelId, member.nameFingerprint, live)) continue
            val stats = repository.feedQuality(listOf(member.key.serverIndex to member.key.streamId))
            val score = stats[member.key.serverIndex to member.key.streamId]?.score
            scored += SmartChannels.Scored(member.key, live.name, score, member.userPriority, true)
        }
        val ranked = SmartChannels.rank(
            scored,
            preferSelected,
            if (group.hasPreferred) group.preferred else null,
            session.sessionChoice,
        )
        return ranked.filter { it.key != session.current && it.key !in tried }
    }

    private fun ownedByHand(group: SmartChannelGroupEntity): Boolean =
        group.origin == SmartChannels.ORIGIN_MANUAL || group.reviewState == SmartChannels.MANUAL

    /** Live members of a hand-made group. A stale reused stream id is not reserved. */
    private fun reservedManualKeys(
        groups: Collection<SmartChannelGroupEntity>,
        members: Map<String, List<SmartChannelMemberEntity>>,
        live: Map<SmartChannels.Key, SmartChannels.Source>,
    ): Set<SmartChannels.Key> {
        val reserved = HashSet<SmartChannels.Key>()
        for (group in groups) {
            if (!ownedByHand(group)) continue
            for (member in members[group.groupId].orEmpty()) {
                val key = SmartChannels.Key(member.serverIndex, member.streamId)
                val source = live[key] ?: continue
                if (SmartChannels.isStale(member.epgChannelId, member.nameFingerprint, source)) continue
                reserved += key
            }
        }
        return reserved
    }

    private suspend fun deleteAutomatic(prior: SmartChannelGroupEntity?, groupId: String) {
        if (prior != null && ownedByHand(prior)) return
        dao.deleteMembers(groupId)
        dao.deleteGroup(groupId)
    }

    /** A source belongs to one group. Moving it by hand drops it from every other group. */
    private suspend fun releaseOtherGroups(keepGroupId: String, keys: Set<SmartChannels.Key>) {
        if (keys.isEmpty()) return
        val grouped = dao.members().groupBy { it.groupId }
        for ((groupId, rows) in grouped) {
            if (groupId == keepGroupId) continue
            val stay = rows.filter { SmartChannels.Key(it.serverIndex, it.streamId) !in keys }
            if (stay.size == rows.size) continue
            if (stay.size < 2) {
                dao.deleteMembers(groupId)
                dao.deleteGroup(groupId)
            } else {
                for (member in rows) {
                    val key = SmartChannels.Key(member.serverIndex, member.streamId)
                    if (key in keys) dao.deleteMember(groupId, member.serverIndex, member.streamId)
                }
            }
        }
    }

    private suspend fun writeCluster(
        cluster: SmartChannels.Cluster,
        prior: SmartChannelGroupEntity?,
        nowMs: Long,
        origin: String,
    ) {
        val group = SmartChannelGroupEntity(
            groupId = cluster.id,
            displayName = prior?.displayName ?: cluster.displayName,
            preferredServerIndex = prior?.preferredServerIndex ?: 0,
            preferredStreamId = prior?.preferredStreamId ?: 0,
            hasPreferred = prior?.hasPreferred ?: false,
            failoverEnabled = prior?.failoverEnabled ?: true,
            origin = prior?.origin ?: origin,
            reviewState = if (prior?.reviewState == SmartChannels.MANUAL) SmartChannels.MANUAL else cluster.reviewState,
            reason = cluster.reason,
            groupFavorite = prior?.groupFavorite ?: false,
            updatedAt = nowMs,
        )
        dao.upsertGroup(group)
        dao.deleteMembers(cluster.id)
        dao.upsertMembers(cluster.members.map { it.toMember(cluster.id) })
    }

    private suspend fun refreshManual(
        group: SmartChannelGroupEntity,
        members: List<SmartChannelMemberEntity>,
        live: Map<SmartChannels.Key, SmartChannels.Source>,
    ) {
        val kept = ArrayList<SmartChannelMemberEntity>()
        for (member in members) {
            val source = live[SmartChannels.Key(member.serverIndex, member.streamId)]
            if (source == null) continue
            if (SmartChannels.isStale(member.epgChannelId, member.nameFingerprint, source)) continue
            kept += member.copy(
                displayName = source.name,
                epgChannelId = source.epgChannelId,
                nameFingerprint = SmartChannels.fingerprint(source.name),
            )
        }
        dao.deleteMembers(group.groupId)
        if (kept.isEmpty()) {
            dao.deleteGroup(group.groupId)
            return
        }
        dao.upsertMembers(kept)
        val pinGone = group.hasPreferred && kept.none {
            it.serverIndex == group.preferredServerIndex && it.streamId == group.preferredStreamId
        }
        if (pinGone) {
            dao.upsertGroup(group.copy(hasPreferred = false, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun SmartChannelGroupEntity.toPlayback(members: List<SmartChannelMemberEntity>) = SmartChannels.PlaybackGroup(
        id = groupId,
        reviewState = reviewState,
        failoverEnabled = failoverEnabled,
        hasPreferred = hasPreferred,
        preferred = if (hasPreferred) SmartChannels.Key(preferredServerIndex, preferredStreamId) else null,
        groupFavorite = groupFavorite,
        members = members.map {
            SmartChannels.Member(
                SmartChannels.Key(it.serverIndex, it.streamId),
                it.epgChannelId,
                it.nameFingerprint,
                it.displayName,
                it.eligible,
                it.userPriority,
            )
        },
    )

    private fun SmartChannels.Source.toMember(groupId: String) = SmartChannelMemberEntity(
        groupId = groupId,
        serverIndex = serverIndex,
        streamId = streamId,
        epgChannelId = epgChannelId,
        nameFingerprint = SmartChannels.fingerprint(name),
        displayName = name,
        eligible = true,
        userPriority = 0,
    )

    private fun SmartChannelMemberEntity.toSource() = SmartChannels.Source(
        serverIndex, streamId, displayName, epgChannelId, "",
    )

    data class GroupSummary(val group: SmartChannelGroupEntity, val members: List<SmartChannelMemberEntity>)

    data class Snapshot(val groups: List<GroupSummary>, val rejections: List<SmartChannelRejectionEntity>)

    companion object {
        /** Missing feed history is unknown, not a failure. [FeedScore.UNKNOWN] is that value. */
        const val UNMEASURED_SCORE = FeedScore.UNKNOWN
    }
}
