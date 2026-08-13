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
     * (that one just never collides since it's a UID slice — a random per-party code actually can). */
    suspend fun startParty(content: WatchPartyContent): String = withContext(Dispatchers.IO) {
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
            "isPlaying" to true,
            "positionMs" to 0L,
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
                episodeId = doc.getString("episodeId") ?: ""
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
}
