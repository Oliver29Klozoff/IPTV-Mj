package com.iptvapp.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Community Stream Health Feed (opt-in, default OFF — see PreferencesManager.
 * communityHealthSharingEnabled, the single gate every write below must pass through first).
 *
 * Anonymized, aggregated crowd-sourced signal layered on top of the purely-local Ghost Channel
 * Radar / Provider Health Weather Map: "X other users reported issues with this channel/provider
 * recently". NEVER uploads a raw provider URL or credentials — only a one-way SHA-256 hash of the
 * provider's host (e.g. "cf.shop4uu.xyz"), never the full URL, username, or password. No identity
 * beyond Firestore's anonymous auth uid the rest of this app's sync features already rely on.
 *
 * Firestore shape: community_health/{providerHostHash}/events/{eventId}, write-only from the
 * reporting device (fire-and-forget — see PlayerActivity's call site, never awaited/blocking on
 * playback), read via a simple time-windowed count query for the "recent issues" banner. */
@Singleton
class CommunityHealthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val healthRoot get() = firestore.collection("community_health")

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    /** One-way, non-reversible identifier for a provider — derived from the host only (e.g.
     * "cf.shop4uu.xyz"), never the scheme/port/path/query, and absolutely never the username or
     * password embedded in an Xtream panel URL. Same java.net.URI(url).host idiom SettingsActivity
     * already uses for its (non-Firestore) debug-info display. */
    fun hashProviderHost(serverUrl: String): String? {
        val host = try {
            java.net.URI(serverUrl).host
        } catch (_: Exception) {
            null
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(host.lowercase().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Fire-and-forget report of a single playback error/rebuffer for this provider+channel.
     * Caller (PlayerActivity) must check communityHealthSharingEnabled itself before calling —
     * this function does not re-check the toggle, so every call site is required to gate first;
     * see PlayerActivity.reportCommunityHealthEventIfEnabled for the one call site in this app. */
    suspend fun reportEvent(providerHostHash: String, channelName: String, errorType: String) {
        withContext(Dispatchers.IO) {
            try {
                val user = signInIfNeeded()
                val data = hashMapOf(
                    "authorUid" to user.uid,
                    "channelName" to channelName,
                    "errorType" to errorType,
                    "timestamp" to FieldValue.serverTimestamp()
                )
                healthRoot.document(providerHostHash).collection("events").add(data).await()
            } catch (_: Exception) {
                // Fire-and-forget — never surface a failure here, this must not affect playback.
            }
        }
    }

    /** Count of events for this provider+channel in the last [windowMs] (default 1 hour) — backs
     * the "N other users reported issues" banner. Excludes this device's own uid so a single
     * user's own retries don't inflate their own banner. Returns 0 on any failure (offline, no
     * data yet, etc) rather than throwing — this is purely informational. */
    suspend fun recentEventCount(
        providerHostHash: String,
        channelName: String,
        windowMs: Long = 60L * 60 * 1000
    ): Int = withContext(Dispatchers.IO) {
        try {
            val user = signInIfNeeded()
            val cutoff = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - windowMs))
            val snap = healthRoot.document(providerHostHash).collection("events")
                .whereEqualTo("channelName", channelName)
                .whereGreaterThan("timestamp", cutoff)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()
            snap.documents.count { it.getString("authorUid") != user.uid }
        } catch (_: Exception) {
            0
        }
    }
}
