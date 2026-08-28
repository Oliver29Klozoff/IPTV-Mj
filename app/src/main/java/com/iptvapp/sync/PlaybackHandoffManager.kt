package com.iptvapp.sync

import android.content.Context
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.iptvapp.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A live channel this device is (or very recently was) tuned to, for cross-device "continue
 * watching on another device" — the personal-use counterpart to WatchPartyContent, but only ever
 * has one participant (no code, no members, no host concept). */
data class HandoffSession(
    val streamId: Int,
    val serverIndex: Int,
    val mergedStreamId: Int,
    val title: String,
    val deviceName: String,
    val updatedAtMs: Long
)

/** Cross-device live-TV handoff — "start watching on the Shield, pick it up on your phone."
 * Piggybacks on the SAME pairing SyncManager already established for favorites sync (the sync
 * code/getSyncGistId() target UID) rather than inventing a second pairing flow: if this device
 * has already paired with another for favorites sync, it's already paired for handoff too, with
 * no separate setup step.
 *
 * Each device writes its OWN live-channel session under its OWN Firebase UID (same anonymous-auth
 * identity SyncManager/WatchPartyManager already use) whenever it tunes to a live channel, and
 * reads its PAIRED PARTNER's session (the UID stored in prefs.getSyncGistId()) on cold launch to
 * offer a resume banner. This is intentionally a single stored partner, not a device group —
 * matching the existing one-target-at-a-time pairing model, not a new multi-device concept. */
@Singleton
class PlaybackHandoffManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val sessions get() = firestore.collection("playback_sessions")

    // A handoff prompt for something watched this long ago is almost certainly not "I just
    // walked to another room" — it's yesterday's viewing, or a session the writing device never
    // cleared (backgrounded without a clean stop). Keeping this short means the banner only ever
    // reflects a genuinely-just-happened, still-relevant moment.
    private val staleAfterMs = 5 * 60 * 1000L

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    /** Call whenever this device starts/switches to a live channel. Best-effort, fire-and-forget
     * from the caller's perspective — a failed write just means no handoff prompt appears
     * elsewhere, never affects this device's own playback. */
    suspend fun reportLiveChannel(streamId: Int, serverIndex: Int, mergedStreamId: Int, title: String) {
        try {
            val user = signInIfNeeded()
            sessions.document(user.uid).set(
                hashMapOf(
                    "streamId" to streamId,
                    "serverIndex" to serverIndex,
                    "mergedStreamId" to mergedStreamId,
                    "title" to title,
                    "deviceName" to Build.MODEL,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (_: Exception) {
            // Best-effort — see kdoc above.
        }
    }

    /** Call when live playback ends cleanly (back button, app backgrounded, channel changed to
     * something else) so a stopped session doesn't linger showing "still watching" on another
     * device until the staleness window happens to expire on its own. */
    suspend fun clearOwnSession() {
        try {
            signInIfNeeded()
            sessions.document(auth.currentUser?.uid ?: return).delete().await()
        } catch (_: Exception) {
        }
    }

    /** Checks this device's paired partner (the sync-code target, if one is set) for a fresh
     * (within staleAfterMs) live-channel session, for the cold-launch "Continue watching on your
     * [device]?" banner. Returns null if unpaired, no session, or the session is stale — the
     * caller doesn't need to distinguish those cases, they all mean "don't show the banner". */
    suspend fun checkPartnerSession(): HandoffSession? = withContext(Dispatchers.IO) {
        try {
            signInIfNeeded()
            val partnerUid = prefs.getSyncGistId().takeIf { it.length > 8 } ?: return@withContext null
            // Never show a handoff prompt from a device's own session — getSyncGistId() defaults
            // to this device's own uid on first sync (see SyncManager.syncUp's kdoc), so without
            // this a solo device with sync enabled would see its own last channel as a "resume on
            // another device" prompt.
            if (partnerUid == auth.currentUser?.uid) return@withContext null
            val doc = sessions.document(partnerUid).get().await()
            if (!doc.exists()) return@withContext null
            val updatedAtMs = doc.getTimestamp("updatedAt")?.toDate()?.time ?: return@withContext null
            if (System.currentTimeMillis() - updatedAtMs > staleAfterMs) return@withContext null
            HandoffSession(
                streamId = (doc.getLong("streamId") ?: -1L).toInt(),
                serverIndex = (doc.getLong("serverIndex") ?: -1L).toInt(),
                mergedStreamId = (doc.getLong("mergedStreamId") ?: -1L).toInt(),
                title = doc.getString("title") ?: "",
                deviceName = doc.getString("deviceName") ?: "another device",
                updatedAtMs = updatedAtMs
            )
        } catch (_: Exception) {
            null
        }
    }
}
