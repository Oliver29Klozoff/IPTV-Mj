package com.iptvapp.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.iptvapp.BuildConfig
import com.iptvapp.data.api.CastProxyApiService
import com.iptvapp.data.api.CastWrapRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What actually gets handed to the receiving device — an opaque proxied URL (see [send]), not
 * the sender's real Xtream stream URL. */
data class CastPayload(val url: String, val title: String)

sealed class CastSendResult {
    object Sent : CastSendResult()
    object InvalidCode : CastSendResult()
    object ProxyNotConfigured : CastSendResult()
    object ProxyError : CastSendResult()
}

/**
 * One-way "cast a live stream to a device with no IPTV account" relay — the account-less
 * counterpart to WatchPartyManager, which instead syncs a content IDENTITY (streamId, etc.) that
 * each member resolves against their OWN provider credentials. That model doesn't work here: the
 * receiving device (someone else's TV/phone, nothing configured) has no account to resolve an
 * identity against.
 *
 * The naive fix — hand over the sender's own already-resolved stream URL directly — would also
 * hand over their live Xtream username/password, since those are embedded in the URL's path.
 * [send] never does that: it first asks the user's own Cloudflare Worker (see
 * cloudflare/cast-proxy-worker.js) to swap the real URL for an opaque, encrypted, time-limited
 * token, and only THAT proxied URL is what reaches Firestore and, from there, the receiving
 * device. The real URL is sent to exactly one place — the sender's own self-hosted worker over
 * HTTPS — never to Firestore, never to the receiver.
 *
 * Kept as its own Firestore collection/class rather than folding into WatchPartyManager — the
 * lifecycle is different (one fire-and-forget delivery per session, not an ongoing multi-member
 * sync with polls/heartbeats) and so is the trust model (a receiver here is never expected to
 * have its own provider account, unlike every Watch Party member).
 */
@Singleton
class CastRelayManager @Inject constructor(
    private val castProxyApi: CastProxyApiService
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val sessions get() = firestore.collection("cast_sessions")

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    private val codeChars = "abcdefghijklmnopqrstuvwxyz0123456789"
    private fun randomCode(): String = (1..8).map { codeChars[Random.nextInt(codeChars.length)] }.joinToString("")

    /** Receiver side: creates a fresh, empty session and returns its code to show as a QR —
     * same collision-retry shape WatchPartyManager.startParty uses. */
    suspend fun createSession(): String = withContext(Dispatchers.IO) {
        signInIfNeeded()
        var code = ""
        repeat(6) {
            val candidate = randomCode()
            if (!sessions.document(candidate).get().await().exists()) {
                code = candidate
                return@repeat
            }
        }
        if (code.isEmpty()) code = randomCode() + Random.nextInt(0, 9)
        sessions.document(code).set(
            hashMapOf(
                "createdAt" to FieldValue.serverTimestamp(),
                "url" to null,
                "title" to null
            )
        ).await()
        code
    }

    /** Sender side: wraps [rawUrl] into an opaque proxied link via the user's own Cloudflare
     * Worker, then pushes ONLY that proxied link into the scanned session code — [rawUrl] itself
     * never goes anywhere else. Distinguishes "code doesn't exist" from "proxy isn't set up" from
     * "proxy call failed" so the caller can show a specific, actionable message instead of a
     * generic failure. */
    suspend fun send(code: String, rawUrl: String, title: String): CastSendResult = withContext(Dispatchers.IO) {
        if (BuildConfig.CAST_PROXY_URL.isBlank() || BuildConfig.CAST_APP_KEY.isBlank()) {
            return@withContext CastSendResult.ProxyNotConfigured
        }
        signInIfNeeded()
        val normalized = code.trim().lowercase()
        val doc = sessions.document(normalized).get().await()
        if (!doc.exists()) return@withContext CastSendResult.InvalidCode

        val proxiedUrl = try {
            val response = castProxyApi.wrap(BuildConfig.CAST_APP_KEY, CastWrapRequest(rawUrl))
            val token = response.takeIf { it.isSuccessful }?.body()?.token ?: return@withContext CastSendResult.ProxyError
            val base = BuildConfig.CAST_PROXY_URL.trimEnd('/')
            "$base/stream/$token"
        } catch (_: Exception) {
            return@withContext CastSendResult.ProxyError
        }

        sessions.document(normalized).set(
            hashMapOf(
                "url" to proxiedUrl,
                "title" to title,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        CastSendResult.Sent
    }

    /** Receiver side: listens for the sender's (already-proxied) URL to arrive. Same
     * pending-write echo-filter WatchPartyManager.listen uses, for the same reason. */
    fun listen(code: String, onReceived: (CastPayload) -> Unit): ListenerRegistration {
        return sessions.document(code).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            val url = snap.getString("url") ?: return@addSnapshotListener
            val title = snap.getString("title") ?: "Cast"
            onReceived(CastPayload(url, title))
        }
    }

    /** Sessions are single-use — deletes the doc once consumed (receiver started playback) or
     * abandoned (receiver closed the waiting screen), so Firestore doesn't accumulate dead
     * sessions from every cast anyone's ever done. */
    suspend fun endSession(code: String) = withContext(Dispatchers.IO) {
        try { sessions.document(code).delete().await() } catch (_: Exception) {}
    }
}
