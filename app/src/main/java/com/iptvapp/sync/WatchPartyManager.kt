package com.iptvapp.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Content identity for a watch-party's shared playback target — mirrors the same stable
 * identity fields PlayerActivity already uses for Trakt scrobbling / progress saving (streamId,
 * series/season/episode, server/merged fields), NOT a raw stream URL (URLs are provider-specific
 * per user and won't resolve on a member's own account). */
data class WatchPartyContent(
    val contentType: String,       // "LIVE", "VOD", or "EPISODE"
    val streamId: Int = -1,
    val serverIndex: Int = -1,
    val mergedStreamId: Int = -1,
    val seriesId: Int = -1,
    val seasonNum: Int = -1,
    val episodeNum: Int = -1,
    val title: String = "",
    // For EPISODE content, [title] is the episode's own title (e.g. "The One With..."), NOT the
    // series name — PlayerActivity.currentWatchPartyContent() sets [title] to streamTitle, which
    // is episode-specific. This is the actual show name (PlayerActivity's traktSeriesName),
    // needed to search a joining member's own catalog by series when the host's exact seriesId
    // doesn't exist there (see XtreamRepository.findWatchPartyEpisodeMatch).
    val seriesName: String = "",
    // Container extension (mp4/mkv/m3u8/etc, NOT a URL) needed to rebuild a VOD/episode stream
    // URL via XtreamRepository's per-user URL builder — a raw stream URL itself is provider/
    // account-specific and useless to a different member's own credentials.
    val containerExtension: String = "",
    // Episode's own catalog id (e.g. Xtream's episode.id string) — required by
    // getSeriesEpisodeUrl/getMergedSeriesEpisodeUrl; season/episode numbers alone aren't enough.
    val episodeId: String = ""
)

data class WatchPartyState(
    val code: String,
    val hostUid: String,
    val content: WatchPartyContent,
    val isPlaying: Boolean,
    val positionMs: Long,
    val updatedAtMs: Long,   // resolved from FieldValue.serverTimestamp() at read time
    val memberCount: Int,
    val active: Boolean
)

/** One "propose what's on your screen" option in a Group Watch poll. */
data class PollOption(
    val proposedBy: String,
    val content: WatchPartyContent
)

data class PollState(
    val pollId: String,
    val options: List<PollOption>,
    /** uid -> option index voted for. */
    val votes: Map<String, Int>,
    val createdBy: String,
    val createdAtMs: Long,
    val active: Boolean
)

@Singleton
class WatchPartyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val parties get() = firestore.collection("watch_parties")

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    private val codeChars = "abcdefghijklmnopqrstuvwxyz0123456789"
    private fun randomCode(): String = (1..8).map { codeChars[Random.nextInt(codeChars.length)] }.joinToString("")

    /** Creates a new party doc under a fresh, collision-checked 8-char code and returns it. This
     * device becomes the host. Same collision-retry shape SyncManager's short-code write uses
     * (that one just never collides since it's a UID slice — a random per-party code actually can).
     * [startPositionMs] is the host's own current playback position for VOD/EPISODE content (0 for
     * LIVE, where a seek position is meaningless) — a party started partway through a movie
     * previously always began everyone at position 0 regardless of where the host actually was. */
    suspend fun startParty(content: WatchPartyContent, startPositionMs: Long = 0L): String = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        var code = ""
        repeat(6) { attempt ->
            val candidate = randomCode()
            val existing = parties.document(candidate).get().await()
            if (!existing.exists()) {
                code = candidate
                return@repeat
            }
        }
        if (code.isEmpty()) code = randomCode() + Random.nextInt(0, 9) // extremely unlikely fallback

        val data = hashMapOf(
            "hostUid" to user.uid,
            "active" to true,
            "contentType" to content.contentType,
            "streamId" to content.streamId,
            "serverIndex" to content.serverIndex,
            "mergedStreamId" to content.mergedStreamId,
            "seriesId" to content.seriesId,
            "seasonNum" to content.seasonNum,
            "episodeNum" to content.episodeNum,
            "title" to content.title,
            "containerExtension" to content.containerExtension,
            "episodeId" to content.episodeId,
            "seriesName" to content.seriesName,
            // Starts paused, not playing — a party begun mid-scene with nobody else in it yet
            // would already be minutes ahead by the time the host shares the code and anyone
            // joins. The host presses play themselves once people are actually ready (see
            // PlayerActivity.startWatchParty, which pauses the local player to match this at the
            // same moment this doc is written).
            "isPlaying" to false,
            "positionMs" to startPositionMs,
            "updatedAt" to FieldValue.serverTimestamp(),
            "memberUids" to listOf(user.uid)
        )
        parties.document(code).set(data).await()
        code
    }

    /** Resolves a party code and, if valid/active, adds this device's uid to memberUids. */
    suspend fun joinParty(code: String): WatchPartyState? = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        val normalized = code.trim().lowercase()
        val doc = parties.document(normalized).get().await()
        if (!doc.exists()) return@withContext null
        if (doc.getBoolean("active") != true) return@withContext null
        parties.document(normalized).update("memberUids", FieldValue.arrayUnion(user.uid)).await()
        toState(normalized, doc)
    }

    private fun toState(code: String, doc: com.google.firebase.firestore.DocumentSnapshot): WatchPartyState {
        @Suppress("UNCHECKED_CAST")
        val members = (doc.get("memberUids") as? List<String>) ?: emptyList()
        val updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
        return WatchPartyState(
            code = code,
            hostUid = doc.getString("hostUid") ?: "",
            content = WatchPartyContent(
                contentType = doc.getString("contentType") ?: "LIVE",
                streamId = (doc.getLong("streamId") ?: -1L).toInt(),
                serverIndex = (doc.getLong("serverIndex") ?: -1L).toInt(),
                mergedStreamId = (doc.getLong("mergedStreamId") ?: -1L).toInt(),
                seriesId = (doc.getLong("seriesId") ?: -1L).toInt(),
                seasonNum = (doc.getLong("seasonNum") ?: -1L).toInt(),
                episodeNum = (doc.getLong("episodeNum") ?: -1L).toInt(),
                title = doc.getString("title") ?: "",
                containerExtension = doc.getString("containerExtension") ?: "",
                episodeId = doc.getString("episodeId") ?: "",
                seriesName = doc.getString("seriesName") ?: ""
            ),
            isPlaying = doc.getBoolean("isPlaying") ?: true,
            positionMs = doc.getLong("positionMs") ?: 0L,
            updatedAtMs = updatedAt,
            memberCount = members.size,
            active = doc.getBoolean("active") ?: false
        )
    }

    /** Host-only: writes a real state change (play/pause toggle or a real seek). Also used for
     * the periodic heartbeat while playing. Never called for a member's own local playback
     * (guarded by the caller checking isPartyHost && !isApplyingRemoteUpdate). */
    fun writeState(code: String, isPlaying: Boolean, positionMs: Long) {
        parties.document(code).set(
            hashMapOf(
                "isPlaying" to isPlaying,
                "positionMs" to positionMs,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
    }

    /** Host-only: propagates a channel change for a LIVE party — resets position math (no
     * positionMs relevance for a fresh channel) and updates content identity. */
    fun writeChannelChange(code: String, content: WatchPartyContent) {
        parties.document(code).set(
            hashMapOf(
                "contentType" to content.contentType,
                "streamId" to content.streamId,
                "serverIndex" to content.serverIndex,
                "mergedStreamId" to content.mergedStreamId,
                "title" to content.title,
                "isPlaying" to true,
                "positionMs" to 0L,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
    }

    /** Attaches a live listener for members (and the host's own UI, e.g. to show member count).
     * Caller is responsible for remove()ing the returned registration. */
    fun listen(code: String, onUpdate: (WatchPartyState?) -> Unit): ListenerRegistration {
        return parties.document(code).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) {
                onUpdate(null)
                return@addSnapshotListener
            }
            // Skip snapshots from a write's local-cache echo — updatedAt (FieldValue.serverTimestamp())
            // hasn't resolved yet on those, so toState() would fall back to this device's own clock
            // for drift math instead of the server clock, causing a one-off bad correction. The
            // server-acknowledged snapshot for the same write follows right behind with the real
            // timestamp, so nothing is lost by ignoring the pending one.
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            onUpdate(toState(code, snap))
        }
    }

    /** Host ending the party — marks it inactive and deletes the doc so it doesn't orphan. */
    suspend fun endParty(code: String) = withContext(Dispatchers.IO) {
        try { parties.document(code).delete().await() } catch (_: Exception) {}
    }

    /** Member leaving — just removes their uid from the member list, doc/party continues. */
    suspend fun leaveParty(code: String) = withContext(Dispatchers.IO) {
        try {
            val uid = auth.currentUser?.uid ?: return@withContext
            parties.document(code).update("memberUids", FieldValue.arrayRemove(uid)).await()
        } catch (_: Exception) {}
    }

    // ─── Group Watch Voting ─────────────────────────────────────────────────
    // Stored as a single "activePoll" map field directly on the party doc (not a subcollection) —
    // a poll is short-lived (one fixed-timer round) and always 1:1 with its party, so a nested
    // map keeps every poll read/write inside the same document the rest of Watch Party already
    // uses instead of adding a second listener/collection round-trip for something this small.

    private fun optionToMap(opt: PollOption): Map<String, Any> = hashMapOf(
        "proposedBy" to opt.proposedBy,
        "contentType" to opt.content.contentType,
        "streamId" to opt.content.streamId,
        "serverIndex" to opt.content.serverIndex,
        "mergedStreamId" to opt.content.mergedStreamId,
        "seriesId" to opt.content.seriesId,
        "seasonNum" to opt.content.seasonNum,
        "episodeNum" to opt.content.episodeNum,
        "title" to opt.content.title,
        "containerExtension" to opt.content.containerExtension,
        "episodeId" to opt.content.episodeId,
        "seriesName" to opt.content.seriesName
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapToOption(m: Map<String, Any?>): PollOption = PollOption(
        proposedBy = m["proposedBy"] as? String ?: "",
        content = WatchPartyContent(
            contentType = m["contentType"] as? String ?: "LIVE",
            streamId = ((m["streamId"] as? Long) ?: -1L).toInt(),
            serverIndex = ((m["serverIndex"] as? Long) ?: -1L).toInt(),
            mergedStreamId = ((m["mergedStreamId"] as? Long) ?: -1L).toInt(),
            seriesId = ((m["seriesId"] as? Long) ?: -1L).toInt(),
            seasonNum = ((m["seasonNum"] as? Long) ?: -1L).toInt(),
            episodeNum = ((m["episodeNum"] as? Long) ?: -1L).toInt(),
            title = m["title"] as? String ?: "",
            containerExtension = m["containerExtension"] as? String ?: "",
            episodeId = m["episodeId"] as? String ?: "",
            seriesName = m["seriesName"] as? String ?: ""
        )
    )

    @Suppress("UNCHECKED_CAST")
    private fun toPollState(doc: com.google.firebase.firestore.DocumentSnapshot): PollState? {
        val poll = doc.get("activePoll") as? Map<String, Any?> ?: return null
        if (poll["active"] != true) return null
        val optionMaps = poll["options"] as? List<Map<String, Any?>> ?: emptyList()
        val options = optionMaps.map { mapToOption(it) }
        val votesRaw = poll["votes"] as? Map<String, Any?> ?: emptyMap()
        val votes = votesRaw.mapValues { (_, v) -> ((v as? Long) ?: -1L).toInt() }
        val createdAt = (poll["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time
            ?: System.currentTimeMillis()
        return PollState(
            pollId = poll["pollId"] as? String ?: "",
            options = options,
            votes = votes,
            createdBy = poll["createdBy"] as? String ?: "",
            createdAtMs = createdAt,
            active = true
        )
    }

    /** Any party member (not just host) can start a poll, seeded with their own current content
     * as the first option — "propose what's on your screen" is the whole option-picking UI, so
     * no separate content-browser dialog is needed. Overwrites any prior poll on this party. */
    suspend fun startPoll(code: String, proposerContent: WatchPartyContent): String = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        val pollId = randomCode()
        val poll = hashMapOf(
            "pollId" to pollId,
            "active" to true,
            "createdBy" to user.uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "options" to listOf(optionToMap(PollOption(user.uid, proposerContent))),
            "votes" to emptyMap<String, Int>()
        )
        parties.document(code).set(hashMapOf("activePoll" to poll), SetOptions.merge()).await()
        pollId
    }

    /** Any member adds their own current-screen content as another option, if not already
     * proposing (one option per uid keeps this simple and avoids duplicate/spam entries). */
    suspend fun proposeOption(code: String, content: WatchPartyContent) = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        val doc = parties.document(code).get().await()
        val state = toPollState(doc) ?: return@withContext
        if (state.options.any { it.proposedBy == user.uid }) return@withContext
        val updated = state.options + PollOption(user.uid, content)
        parties.document(code).update(
            "activePoll.options", updated.map { optionToMap(it) }
        ).await()
    }

    /** Casts/changes this device's vote for option index [optionIndex]. */
    suspend fun castVote(code: String, optionIndex: Int) = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        parties.document(code).update("activePoll.votes.${user.uid}", optionIndex).await()
    }

    /** Listens for poll changes on this party, same hasPendingWrites() echo-filter as listen(). */
    fun listenPoll(code: String, onUpdate: (PollState?) -> Unit): ListenerRegistration {
        return parties.document(code).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) {
                onUpdate(null)
                return@addSnapshotListener
            }
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            onUpdate(toPollState(snap))
        }
    }

    /** Closes the poll (marks inactive) and, if it had at least one option, returns the winning
     * option's content so the caller can feed it straight into writeChannelChange/writeState —
     * same content-switch mechanism a host's manual channel change already uses, so members'
     * players auto-tune with no separate switch logic needed. Ties broken by whichever option
     * was proposed first (i.e. lowest index — options list is append-only in proposal order). */
    suspend fun closePoll(code: String): WatchPartyContent? = withContext(Dispatchers.IO) {
        val doc = parties.document(code).get().await()
        val state = toPollState(doc) ?: return@withContext null
        parties.document(code).update("activePoll.active", false).await()
        if (state.options.isEmpty()) return@withContext null
        val tally = IntArray(state.options.size)
        state.votes.values.forEach { idx -> if (idx in tally.indices) tally[idx]++ }
        var winner = 0
        for (i in 1 until tally.size) if (tally[i] > tally[winner]) winner = i
        state.options[winner].content
    }
}
