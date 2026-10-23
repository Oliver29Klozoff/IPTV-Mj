package com.iptvapp.util

/**
 * Logical grouping of the same channel across providers. Channel rows, favorites, recordings,
 * and guide mappings stay on their own provider. This file never builds a playback URL and never
 * stores a password, token, or credential-bearing address.
 *
 * A group favorite is separate from a source favorite. Turning grouping off shows the original
 * rows and their original stars. A deep link still names one provider and one stream id.
 */
object SmartChannels {

    const val DISPLAY_OFF = 0
    const val DISPLAY_GROUP = 1
    const val BACKUP_OFF = 0
    const val BACKUP_ON = 1
    const val PREFER_SELECTED = 0
    const val PREFER_HEALTHY = 1

    const val VERIFIED = "VERIFIED"
    const val SUGGESTED = "SUGGESTED"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val MANUAL = "MANUAL"
    const val SEPARATE = "SEPARATE"
    const val ORIGIN_AUTO = "AUTO"
    const val ORIGIN_MANUAL = "MANUAL"

    /** A viewer removed this source from a group. It is not a "not the same channel" rejection. */
    const val REMOVED = "REMOVED"

    const val MAX_FAILOVER_ATTEMPTS = 2
    const val FAILOVER_COOLDOWN_MS = 180_000L

    /** Milliseconds until another backup may start, or null when the cooldown is already over. */
    fun cooldownWaitMs(lastSwitchAtMs: Long, nowMs: Long, cooldownMs: Long = FAILOVER_COOLDOWN_MS): Long? {
        if (lastSwitchAtMs <= 0L) return null
        val wait = lastSwitchAtMs + cooldownMs - nowMs
        return if (wait > 0L) wait else null
    }

    const val REASON_VERIFIED = "Same verified guide identity and compatible region."
    const val REASON_SUGGESTED = "Same channel name after removing quality tags. No shared guide id, so this needs approval."
    const val REASON_AMBIGUOUS = "Some copies name a region or language and some do not. Review before grouping."
    const val REASON_REGION = "Different regions."
    const val REASON_LANGUAGE = "Different languages."
    const val REASON_DELAY = "A delayed feed is a different channel."
    const val REASON_NAME = "Different channel names."
    const val TIMESHIFT_HOLD = "Keeping this source. The rewind buffer stays on this provider."

    fun displayLabel(value: Int): String = if (value == DISPLAY_GROUP) "Group verified duplicates" else "Off"

    fun backupLabel(value: Int): String = if (value == BACKUP_ON) "On for verified groups" else "Off"

    fun preferLabel(value: Int): String =
        if (value == PREFER_HEALTHY) "Automatically choose a healthy source" else "Prefer my selected source"

    fun switchMessage(providerLabel: String): String = "Switched to backup — $providerLabel"

    data class Key(val serverIndex: Int, val streamId: Int)

    /** A catalog row reduced to what grouping is allowed to see. No stream address. */
    data class Source(
        val serverIndex: Int,
        val streamId: Int,
        val name: String,
        val epgChannelId: String = "",
        val categoryName: String = "",
    ) {
        val key: Key get() = Key(serverIndex, streamId)
    }

    data class Traits(
        val core: String,
        val region: String,
        val language: String,
        val delay: String,
        /** Advertised in the name, such as FHD. This is not a measured picture size. */
        val quality: String,
    )

    data class Match(val verdict: String, val reason: String)

    data class Cluster(
        val id: String,
        val displayName: String,
        val reviewState: String,
        val reason: String,
        val members: List<Source>,
    )

    data class Proposal(
        val verified: List<Cluster>,
        val suggested: List<Cluster>,
        val ambiguous: List<Cluster>,
        /** One pass over the catalog, plus the channels that shared a bucket. Never n squared. */
        val inspections: Int,
    )

    data class Member(
        val key: Key,
        val epgChannelId: String,
        val nameFingerprint: String,
        val displayName: String,
        val eligible: Boolean,
        val userPriority: Int,
    )

    data class PlaybackGroup(
        val id: String,
        val reviewState: String,
        val failoverEnabled: Boolean,
        val hasPreferred: Boolean,
        val preferred: Key?,
        val groupFavorite: Boolean,
        val members: List<Member>,
    )

    data class Scored(
        val key: Key,
        val displayName: String,
        val score: Int?,
        val userPriority: Int,
        val eligible: Boolean,
    )

    sealed class BackupChoice {
        data object Hold : BackupChoice()
        data object Legacy : BackupChoice()
        data object Exhausted : BackupChoice()
        data class Switch(val serverIndex: Int, val streamId: Int, val displayName: String) : BackupChoice()
    }

    data class BackupRequest(
        val smartOn: Boolean,
        val inPlaybackGroup: Boolean,
        val groupFailoverEnabled: Boolean,
        val qualifiedFailure: Boolean,
        val attempts: Int,
        val maxAttempts: Int = MAX_FAILOVER_ATTEMPTS,
        val nowMs: Long,
        val lastSwitchAtMs: Long,
        val cooldownMs: Long = FAILOVER_COOLDOWN_MS,
        val generation: Long,
        val eventGeneration: Long,
        val candidates: List<Scored>,
    )

    /** What a scheduled recording is allowed to remember. The group id is ignored on purpose. */
    data class RecordingIdentity(val serverIndex: Int, val streamId: Int, val programTitle: String)

    private val QUALITY = Regex(
        """\b(4K|UHD|FHD|FULL\s?HD|1080P?|720P?|HD|SD|HEVC|H\.?265|H\.?264)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DELAY = Regex(
        """(?:\+\s*(\d{1,2})\b|\bPLUS\s*(\d{1,2})\b|\b(\d{1,2})\s*H(?:OUR)?\s*DELAY\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val REGION = Regex(
        """\b(EAST\s*COAST|WEST\s*COAST|EAST|WEST|USA|UK|GB|US|NYC|NY|LA|LOS\s*ANGELES|LOCAL)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LANGUAGE = Regex(
        """\b(ENGLISH|SPANISH|FRENCH|GERMAN|PORTUGUESE|ENG|SPA|EN|ES|FR|DE|PT)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING = Regex("""^\s*([A-Za-z]{2,4})\s*[|:\-]+\s*""")

    fun traits(name: String): Traits {
        val leading = LEADING.find(name.trim())?.groupValues?.get(1).orEmpty()
        val delay = delayOf(name)
        val region = regionOf(leading).ifEmpty { canonicalRegion(REGION.find(name)?.value.orEmpty()) }
        val language = canonicalLanguage(LANGUAGE.find(name)?.value.orEmpty())
        val quality = QUALITY.find(name)?.value?.uppercase()?.replace(" ", "").orEmpty()
        var stripped = LEADING.replace(name, " ")
        stripped = DELAY.replace(stripped, " ")
        stripped = REGION.replace(stripped, " ")
        stripped = LANGUAGE.replace(stripped, " ")
        return Traits(ChannelNameMatcher.normalize(stripped), region, language, delay, quality)
    }

    /** Quality tags are left out, so HD and FHD stay the same member across a catalog refresh. */
    fun fingerprint(name: String): String {
        val t = traits(name)
        return "${t.core}|${t.region}|${t.language}|${t.delay}"
    }

    /** Left key of a removal mark. It is not a channel identity, so it cannot reject a whole group. */
    fun removalLeft(groupId: String): String = "removed:$groupId"

    fun removalToken(serverIndex: Int, streamId: Int, nameFingerprint: String): String =
        "$serverIndex:$streamId:$nameFingerprint"

    /** Drops sources the viewer removed. The rest of the group stays. */
    fun omittingRemoved(cluster: Cluster, tokens: Set<String>): Cluster {
        if (tokens.isEmpty()) return cluster
        val kept = cluster.members.filter { source ->
            removalToken(source.serverIndex, source.streamId, fingerprint(source.name)) !in tokens
        }
        return if (kept.size == cluster.members.size) cluster else cluster.copy(members = kept)
    }

    /**
     * A source the viewer already grouped by hand is not also put in an automatic group.
     * Returns null when fewer than two automatic members would remain, so the caller deletes
     * the automatic row instead of keeping a second overlapping group.
     */
    fun excludingReserved(cluster: Cluster, reserved: Set<Key>): Cluster? {
        if (reserved.isEmpty()) return cluster
        val kept = cluster.members.filter { it.key !in reserved }
        if (kept.size == cluster.members.size) return cluster
        if (kept.size < 2) return null
        return cluster.copy(members = kept)
    }

    /**
     * The saved group that may fail over for [current]. A reused stream id whose live row no
     * longer matches the stored member is not that group. A missing live row still is: the
     * source disappeared, and another saved member of the same channel may take over.
     */
    fun playbackGroupFor(groups: List<PlaybackGroup>, current: Key, live: Source?): PlaybackGroup? {
        for (group in groups) {
            if (group.reviewState != VERIFIED && group.reviewState != MANUAL) continue
            val member = group.members.firstOrNull { it.key == current } ?: continue
            if (live != null && isStale(member.epgChannelId, member.nameFingerprint, live)) continue
            return group
        }
        return null
    }

    fun classify(a: Source, b: Source): Match {
        if (a.key == b.key) return Match(SEPARATE, "Same source.")
        val ta = traits(a.name)
        val tb = traits(b.name)
        if (ta.core.isEmpty() || tb.core.isEmpty() || ta.core != tb.core) return Match(SEPARATE, REASON_NAME)
        if (ta.delay != tb.delay) return Match(SEPARATE, REASON_DELAY)
        if (ta.region.isNotEmpty() && tb.region.isNotEmpty() && ta.region != tb.region) return Match(SEPARATE, REASON_REGION)
        if (ta.language.isNotEmpty() && tb.language.isNotEmpty() && ta.language != tb.language) return Match(SEPARATE, REASON_LANGUAGE)
        if (ta.region != tb.region || ta.language != tb.language) return Match(AMBIGUOUS, REASON_AMBIGUOUS)
        val epgA = a.epgChannelId.trim()
        val epgB = b.epgChannelId.trim()
        if (epgA.isNotEmpty() && epgA.equals(epgB, ignoreCase = true)) return Match(VERIFIED, REASON_VERIFIED)
        return Match(SUGGESTED, REASON_SUGGESTED)
    }

    /**
     * Buckets by guide id and by normalized core. Channels that share neither are never compared.
     * [rejected] holds canonical rejection pairs from [rejectionPair]. A reused stream id changes
     * the fingerprint, so it does not revive a rejected pair and does not inherit it.
     */
    fun propose(sources: List<Source>, rejected: Set<Pair<String, String>> = emptySet()): Proposal {
        val parsed = sources.map { it to traits(it.name) }
        val verified = ArrayList<Cluster>()
        val ambiguous = ArrayList<Cluster>()
        val verifiedKeys = HashSet<Key>()
        val byEpg = parsed.filter { it.first.epgChannelId.isNotBlank() }
            .groupBy { it.first.epgChannelId.trim().lowercase() }
        for ((epg, members) in byEpg) {
            if (members.size < 2) continue
            for ((core, coreMembers) in members.groupBy { it.second.core }) {
                if (core.isEmpty()) continue
                for ((_, delayMembers) in coreMembers.groupBy { it.second.delay }) {
                    if (delayMembers.size < 2) continue
                    val regions = delayMembers.map { it.second.region }.toSet()
                    val languages = delayMembers.map { it.second.language }.toSet()
                    val mixed = (regions.any { it.isEmpty() } && regions.any { it.isNotEmpty() }) ||
                        (languages.any { it.isEmpty() } && languages.any { it.isNotEmpty() })
                    if (mixed) {
                        if (!anyRejected(delayMembers, rejected)) {
                            ambiguous += cluster(
                                "a:$epg|$core|${delayMembers.first().second.delay}",
                                delayMembers,
                                AMBIGUOUS,
                                REASON_AMBIGUOUS,
                            )
                        }
                        continue
                    }
                    val buckets = delayMembers.groupBy { it.second.region to it.second.language }
                    for (bucket in buckets.values) {
                        if (bucket.size < 2 || anyRejected(bucket, rejected)) continue
                        val sample = bucket.first()
                        val id = verifiedId(epg, core, sample.second)
                        verified += cluster(id, bucket, VERIFIED, REASON_VERIFIED)
                        bucket.forEach { verifiedKeys += it.first.key }
                    }
                }
            }
        }
        val suggested = ArrayList<Cluster>()
        val bySignature = parsed.filter { it.second.core.isNotEmpty() }
            .groupBy { listOf(it.second.core, it.second.region, it.second.language, it.second.delay).joinToString("|") }
        for ((signature, bucket) in bySignature) {
            if (bucket.size < 2) continue
            val fresh = bucket.filter { it.first.key !in verifiedKeys }
            if (fresh.size < 2 || anyRejected(fresh, rejected)) continue
            suggested += cluster("s:$signature", fresh, SUGGESTED, REASON_SUGGESTED)
        }
        val ambiguousIds = ambiguous.map { it.id }.toMutableSet()
        val byCoreDelay = parsed.filter { it.second.core.isNotEmpty() }
            .groupBy { it.second.core to it.second.delay }
        for ((_, rows) in byCoreDelay) {
            if (rows.size < 2) continue
            addTraitAmbiguity(rows, ambiguous, ambiguousIds, rejected)
        }
        return Proposal(verified, suggested, ambiguous, inspections = sources.size)
    }

    /**
     * A blank region or language next to a named one is a review item. East and West stay in
     * different items, because two named regions are a conflict, not a question.
     */
    private fun addTraitAmbiguity(
        rows: List<Pair<Source, Traits>>,
        ambiguous: MutableList<Cluster>,
        ambiguousIds: MutableSet<String>,
        rejected: Set<Pair<String, String>>,
    ) {
        fun add(id: String, bucket: List<Pair<Source, Traits>>) {
            val distinct = bucket.distinctBy { it.first.key }
            if (distinct.size < 2 || id in ambiguousIds || anyRejected(distinct, rejected)) return
            ambiguousIds += id
            ambiguous += cluster(id, distinct, AMBIGUOUS, REASON_AMBIGUOUS)
        }
        val core = rows.first().second.core
        val delay = rows.first().second.delay
        val blankRegion = rows.filter { it.second.region.isEmpty() }
        if (blankRegion.isNotEmpty()) {
            val byRegion = rows.filter { it.second.region.isNotEmpty() }.groupBy { it.second.region }
            for ((region, specified) in byRegion) {
                val bucket = blankRegion + specified
                val languages = bucket.map { it.second.language }.filter { it.isNotEmpty() }.toSet()
                if (languages.size > 1) continue
                add("a:$core|$region|${languages.singleOrNull().orEmpty()}|$delay", bucket)
            }
        }
        val blankLanguage = rows.filter { it.second.language.isEmpty() }
        if (blankLanguage.isNotEmpty()) {
            val byLanguage = rows.filter { it.second.language.isNotEmpty() }.groupBy { it.second.language }
            for ((language, specified) in byLanguage) {
                val bucket = blankLanguage + specified
                val regions = bucket.map { it.second.region }.filter { it.isNotEmpty() }.toSet()
                if (regions.size > 1) continue
                add("a:$core|${regions.singleOrNull().orEmpty()}|$language|$delay", bucket)
            }
        }
    }

    fun rejectionKey(source: Source): String =
        "${source.serverIndex}:${source.streamId}:${fingerprint(source.name)}:${source.epgChannelId.trim().lowercase()}"

    fun rejectionPair(a: Source, b: Source): Pair<String, String> {
        val left = rejectionKey(a)
        val right = rejectionKey(b)
        return if (left <= right) left to right else right to left
    }

    /**
     * A stored member is stale when the live row's guide id changed, or when there was no guide
     * id and the name no longer fingerprints the same. A reused numeric stream id must not play
     * the new channel under the old membership.
     */
    fun isStale(storedEpg: String, storedFingerprint: String, live: Source): Boolean {
        val liveEpg = live.epgChannelId.trim()
        val stored = storedEpg.trim()
        if (stored.isNotEmpty() && liveEpg.isNotEmpty() && !stored.equals(liveEpg, ignoreCase = true)) return true
        if (stored.isEmpty() || liveEpg.isEmpty()) return fingerprint(live.name) != storedFingerprint
        return false
    }

    fun <T> present(
        items: List<T>,
        groups: List<PlaybackGroup>,
        keyOf: (T) -> Key,
        scoreOf: (T) -> Int?,
        preferSelected: Boolean,
        liveOf: (T) -> Source? = { null },
    ): List<Shown<T>> {
        if (items.isEmpty() || groups.isEmpty()) return items.map { Shown(it, 0, null, false) }
        val byKey = HashMap<Key, PlaybackGroup>(groups.sumOf { it.members.size })
        for (group in groups) {
            if (group.reviewState != VERIFIED && group.reviewState != MANUAL) continue
            for (member in group.members) byKey[member.key] = group
        }
        if (byKey.isEmpty()) return items.map { Shown(it, 0, null, false) }
        val memberByKey = HashMap<Key, Member>(byKey.size)
        for (group in groups) {
            if (group.reviewState != VERIFIED && group.reviewState != MANUAL) continue
            for (member in group.members) memberByKey[member.key] = member
        }
        val bucketItems = HashMap<String, ArrayList<T>>()
        val bucketKeys = HashMap<String, ArrayList<Key>>()
        val eligibleCount = HashMap<String, Int>()
        for (item in items) {
            val key = keyOf(item)
            val group = byKey[key] ?: continue
            val member = memberByKey[key] ?: continue
            if (!member.eligible || !membershipMatches(member, liveOf(item))) continue
            bucketItems.getOrPut(group.id) { ArrayList() }.add(item)
            bucketKeys.getOrPut(group.id) { ArrayList() }.add(key)
            if (group.id !in eligibleCount) eligibleCount[group.id] = group.members.count { it.eligible }
        }
        val consumed = HashSet<Key>()
        val out = ArrayList<Shown<T>>(items.size)
        for (item in items) {
            val key = keyOf(item)
            if (key in consumed) continue
            val group = byKey[key]
            val member = memberByKey[key]
            if (group == null || member == null || !member.eligible || !membershipMatches(member, liveOf(item))) {
                out += Shown(item, 0, null, false)
                continue
            }
            val live = bucketItems[group.id].orEmpty()
            val liveKeys = bucketKeys[group.id].orEmpty()
            val groupLive = eligibleCount[group.id] ?: 0
            if (live.size < 2 || groupLive < 2) {
                out += Shown(item, 0, null, false)
                continue
            }
            val chosen = chooseDisplayed(live, liveKeys, group, scoreOf, preferSelected)
            liveKeys.forEach { consumed += it }
            out += Shown(chosen, groupLive - 1, group.id, group.groupFavorite)
        }
        return out
    }

    /** A missing live row is not treated as a mismatch. A reused stream id is. */
    private fun membershipMatches(member: Member, live: Source?): Boolean {
        if (live == null) return true
        return !isStale(member.epgChannelId, member.nameFingerprint, live)
    }

    data class Shown<T>(val item: T, val extraSources: Int, val groupId: String?, val groupFavorite: Boolean)

    fun rank(members: List<Scored>, preferSelected: Boolean, pinned: Key?, session: Key?): List<Scored> {
        val eligible = members.filter { it.eligible }
        return eligible.sortedWith(
            compareByDescending<Scored> { session != null && it.key == session }
                .thenByDescending { preferSelected && pinned != null && it.key == pinned }
                .thenByDescending { it.userPriority }
                .thenByDescending { it.score ?: FeedScore.UNKNOWN }
                .thenBy { it.key.serverIndex }
                .thenBy { it.key.streamId },
        )
    }

    fun decide(request: BackupRequest): BackupChoice {
        if (request.eventGeneration != request.generation) return BackupChoice.Hold
        if (!request.qualifiedFailure) return BackupChoice.Hold
        if (!request.smartOn || !request.inPlaybackGroup) return BackupChoice.Legacy
        if (!request.groupFailoverEnabled) return BackupChoice.Exhausted
        if (request.attempts >= request.maxAttempts) return BackupChoice.Exhausted
        if (request.lastSwitchAtMs > 0L && request.nowMs - request.lastSwitchAtMs < request.cooldownMs) {
            return BackupChoice.Hold
        }
        val next = request.candidates.firstOrNull { it.eligible } ?: return BackupChoice.Exhausted
        return BackupChoice.Switch(next.key.serverIndex, next.key.streamId, next.displayName)
    }

    /** A playback URL is never part of a switch. Callers resolve the id through the existing player. */
    fun playbackHandle(choice: BackupChoice.Switch): Key = Key(choice.serverIndex, choice.streamId)

    fun timeshiftAllowsSwitch(atLiveEdge: Boolean): Boolean = atLiveEdge

    enum class PlaySignal { READY, BUFFERING, SEEK, PAUSE, FAILURE, DECODER }

    /** Tune time counts only after a real picture, not when a buffer or a seek is reported. */
    fun tuneSample(signal: PlaySignal, tuneMs: Long): Long? =
        if (signal == PlaySignal.READY && tuneMs > 0L) tuneMs else null

    fun qualifiedForBackup(signal: PlaySignal): Boolean =
        signal == PlaySignal.FAILURE || signal == PlaySignal.DECODER

    fun recordingIdentity(serverIndex: Int, streamId: Int, programTitle: String, groupId: String?): RecordingIdentity {
        // groupId is accepted so a caller cannot "forget" it into the only stored field.
        if (groupId != null && groupId.isEmpty()) return RecordingIdentity(serverIndex, streamId, programTitle)
        return RecordingIdentity(serverIndex, streamId, programTitle)
    }

    fun allowAutomaticRecordingMove(inProgress: Boolean): Boolean = !inProgress && false

    /** One history row: the source that actually started, not a second row for the group. */
    fun historyEntryAfterSwitch(to: Key): Key = to

    /** Launcher and reminder links keep the stream they named. */
    fun deepLinkTarget(serverIndex: Int, streamId: Int): Key = Key(serverIndex, streamId)

    fun manualGroupAllowed(verdict: String, acknowledgedMismatch: Boolean): Boolean =
        verdict == VERIFIED || verdict == SUGGESTED || acknowledgedMismatch

    fun bulkAccept(clusters: List<Cluster>): List<Cluster> = clusters.filter { it.reviewState == VERIFIED }

    /** Source stars are copied through unchanged. The group star is a different flag. */
    data class FavoriteSnapshot(val sourceFavorites: Map<Key, Boolean>, val groupFavorite: Boolean)

    fun afterGroupFavoriteToggle(before: FavoriteSnapshot): FavoriteSnapshot =
        before.copy(groupFavorite = !before.groupFavorite)

    fun afterUngroup(sourceFavorites: Map<Key, Boolean>): Map<Key, Boolean> = sourceFavorites

    fun survivingMembers(members: List<Member>, liveKeys: Set<Key>): List<Member> =
        members.filter { it.key in liveKeys }

    fun exposesCredential(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("password=")) return true
        if (lower.contains("username=")) return true
        if (Regex("""https?://[^/\s:]+:[^/\s@]+@""").containsMatchIn(lower)) return true
        if (lower.contains("authorization:")) return true
        return false
    }

    fun storedText(displayName: String, reason: String, epg: String, fingerprint: String): String =
        "$displayName $reason $epg $fingerprint"

    /** Phone and TV share these actions. The screen only lists them. */
    fun sourceActions(): List<String> = listOf(
        "Play this source",
        "Set as preferred",
        "Automatic backup",
        "Review source information",
    )

    /**
     * Session for one playback. A channel change bumps [generation] so a late player callback
     * cannot retune the channel the viewer has already left.
     */
    class Session {
        var generation: Long = 0
            private set
        private var attempts: Int = 0
        private var lastSwitchAt: Long = 0L
        private val tried = LinkedHashSet<Key>()
        var current: Key = Key(-1, -1)
            private set
        var sessionChoice: Key? = null
            private set

        fun userTuned(to: Key) {
            generation++
            attempts = 0
            lastSwitchAt = 0L
            tried.clear()
            current = to
            sessionChoice = to
        }

        fun attempts(): Int = attempts

        fun lastSwitchAt(): Long = lastSwitchAt

        fun tried(): Set<Key> = tried.toSet()

        fun noteSwitch(to: Key, nowMs: Long) {
            tried.add(current)
            current = to
            attempts++
            lastSwitchAt = nowMs
            sessionChoice = null
        }
    }

    private fun <T> chooseDisplayed(
        live: List<T>,
        keys: List<Key>,
        group: PlaybackGroup,
        scoreOf: (T) -> Int?,
        preferSelected: Boolean,
    ): T {
        val priority = group.members.associate { it.key to it.userPriority }
        val scored = live.indices.map { i ->
            Scored(keys[i], "", scoreOf(live[i]), priority[keys[i]] ?: 0, true)
        }
        val ordered = rank(scored, preferSelected, if (group.hasPreferred) group.preferred else null, session = null)
        val best = ordered.first().key
        return live[keys.indexOf(best)]
    }

    private fun cluster(id: String, members: List<Pair<Source, Traits>>, state: String, reason: String): Cluster {
        val sources = members.map { it.first }.distinctBy { it.key }
        return Cluster(id, sources.first().name, state, reason, sources)
    }

    private fun verifiedId(epg: String, core: String, traits: Traits): String =
        "v:${epg.lowercase()}|$core|${traits.region}|${traits.language}|${traits.delay}"

    private fun anyRejected(bucket: List<Pair<Source, Traits>>, rejected: Set<Pair<String, String>>): Boolean {
        if (rejected.isEmpty() || bucket.size < 2) return false
        val keys = bucket.map { rejectionKey(it.first) }.toSet()
        for (pair in rejected) {
            if (pair.first in keys && pair.second in keys) return true
        }
        return false
    }

    private fun delayOf(name: String): String {
        val match = DELAY.find(name) ?: return ""
        val hours = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return ""
        return "+$hours"
    }

    /** A known alias, or the prefix itself when it is not one. CA and AU must not both become blank. */
    private fun regionOf(leading: String): String {
        val known = canonicalRegion(leading)
        if (known.isNotEmpty()) return known
        val token = leading.trim().uppercase()
        return if (token.length in 2..4 && token.all { it.isLetter() }) token else ""
    }

    private fun canonicalRegion(raw: String): String {
        val token = raw.trim().uppercase().replace(Regex("\\s+"), " ")
        return when (token) {
            "EAST", "EAST COAST" -> "EAST"
            "WEST", "WEST COAST" -> "WEST"
            "US", "USA" -> "US"
            "UK", "GB" -> "UK"
            "NY", "NYC" -> "NY"
            "LA", "LOS ANGELES" -> "LA"
            "LOCAL" -> "LOCAL"
            else -> ""
        }
    }

    private fun canonicalLanguage(raw: String): String = when (raw.trim().uppercase()) {
        "EN", "ENG", "ENGLISH" -> "EN"
        "ES", "SPA", "SPANISH" -> "ES"
        "FR", "FRENCH" -> "FR"
        "DE", "GERMAN" -> "DE"
        "PT", "PORTUGUESE" -> "PT"
        else -> ""
    }
}
