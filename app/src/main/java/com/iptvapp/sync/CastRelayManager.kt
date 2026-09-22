package com.iptvapp.sync

import com.google.firebase.auth.FirebaseAuth
import com.iptvapp.BuildConfig
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What a receiver ends up with after opening the sealed box (see [send]) — the real stream URL,
 * which is why the box is what travels and this never is.
 *
 * [channels] is the optional pack that lets the receiver change channel on its own. Empty means
 * the sender didn't include one (or predates the feature), in which case the receiver plays the
 * single channel exactly as before. */
data class CastPayload(
    val url: String,
    val title: String,
    val channels: List<CastRelayManager.CastChannel> = emptyList()
)

sealed class CastSendResult {
    object Sent : CastSendResult()
    object InvalidCode : CastSendResult()

    /** The scanned QR carried no session key, so the receiver is running a build from before
     * the sealed box existed. Refused rather than downgraded: the only way to serve such a
     * receiver is to put the credential-bearing URL into Firestore in the clear. */
    object ReceiverTooOld : CastSendResult()

    object SendFailed : CastSendResult()
}

/**
 * One-way "cast a live stream to a device with no IPTV account" relay — the account-less
 * counterpart to WatchPartyManager, which instead syncs a content IDENTITY (streamId, etc.) that
 * each member resolves against their OWN provider credentials. That model doesn't work here: the
 * receiving device (someone else's TV/phone, nothing configured) has no account to resolve an
 * identity against.
 *
 * The naive fix — hand over the sender's own already-resolved stream URL directly — would also
 * hand over their live Xtream username/password to Firestore, since those are embedded in the
 * URL's path. [send] never does that: it encrypts the URL under a one-time key that the RECEIVER
 * generated and displayed in its QR code, so the key reaches this device across the air gap
 * between that screen and this camera and never travels the network at all. Firestore therefore
 * stores nothing but ciphertext (see [CastBox]), and only the device that drew the QR can read it.
 *
 * An earlier design relayed the video itself through a Cloudflare Worker. That is gone, and the
 * reason is worth recording: the IPTV provider refuses traffic from Cloudflare's network, so
 * every proxied stream came back 502 while the identical URL fetched from a home connection
 * returned fine. Nothing proxies video now — the receiver streams straight from the provider, so
 * there is no datacenter in the media path to be blocked, and casting works off-WiFi to a device
 * with no account of its own.
 *
 * Kept as its own Firestore collection/class rather than folding into WatchPartyManager — the
 * lifecycle is different (one fire-and-forget delivery per session, not an ongoing multi-member
 * sync with polls/heartbeats) and so is the trust model (a receiver here is never expected to
 * have its own provider account, unlike every Watch Party member).
 */
@Singleton
class CastRelayManager @Inject constructor() {
    /** The last QR payload this device successfully cast to, so switching to a different channel
     * and casting again can resend to the same receiver without rescanning — kept here (a
     * singleton that outlives any one screen) rather than on PlayerActivity itself, since backing
     * out of one channel and opening another creates a brand new PlayerActivity instance that
     * would otherwise have no memory of what was just cast. Cleared whenever a send turns out to
     * target a session that no longer exists (see PlayerActivity's InvalidCode handling).
     *
     * This holds the WHOLE scanned payload, session key included, not just the code — a resend
     * has to re-seal, and there is nowhere else to recover the key from once the QR is off screen.
     * It therefore stays in memory only; persisting it would put the key on disk for no gain. */
    var lastSentCode: String? = null

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val sessions get() = firestore.collection("cast_sessions")

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    private val codeChars = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** kotlin.random.Random was fine when this code was only a Firestore document id. It is not
     * fine now: a sender who types the code instead of scanning derives the encryption key from
     * it, so the code IS a secret, and a predictable one hands over every session this device
     * will ever show. Rejection sampling keeps all 36 characters equally likely — 256 is not a
     * multiple of 36, so a plain modulo would quietly favour the first four. */
    private val secureRng = java.security.SecureRandom()

    private fun randomCode(): String {
        val sb = StringBuilder(8)
        val buf = ByteArray(1)
        while (sb.length < 8) {
            secureRng.nextBytes(buf)
            val v = buf[0].toInt() and 0xff
            if (v >= 252) continue          // 252 = 36 * 7
            sb.append(codeChars[v % codeChars.length])
        }
        return sb.toString()
    }

    /** Receiver side: creates a fresh, empty session and returns the payload to show as a QR —
     * same collision-retry shape WatchPartyManager.startParty uses.
     *
     * The returned string is "<code>.<base64url key>", not a bare code: the key half is generated
     * here and must reach the sender ONLY by being photographed off this screen. Callers should
     * put it in the QR and pass it back to [listen] and [endSession] unchanged — never display it
     * as text for someone to read out, which would defeat the point of not transmitting it. */
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
                "box" to null,
                "url" to null,
                "title" to null
            )
        ).await()

        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        "$code.${CastBox.b64uEncode(key)}"
    }

    /** One entry in the channel pack that travels with a cast — see [send]. */
    data class CastChannel(val name: String, val url: String)

    companion object {
        /** The pack has to cross an Intent to reach PlayerActivity, which can't carry a typed
         * list — so it travels as the same {n,u} JSON used on the wire. Shared here so the
         * encode and decode can't drift apart in two Activities. */
        fun encodePack(channels: List<CastChannel>): String {
            val arr = org.json.JSONArray()
            channels.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url)) }
            return arr.toString()
        }

        fun decodePack(json: String?): List<CastChannel> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(json)
                val out = mutableListOf<CastChannel>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("u").takeIf { it.isNotBlank() } ?: continue
                    out += CastChannel(o.optString("n").ifBlank { "Channel ${i + 1}" }, u)
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Sender side: seals [rawUrl] under the session key from the scanned QR and pushes ONLY the
     * ciphertext into that session — [rawUrl] itself never leaves this device.
     *
     * [scanned] is the raw QR contents, "<code>.<base64url key>"; pass the same string back on a
     * resend so the key comes with it (that is why [lastSentCode] holds the whole payload rather
     * than the bare code). Distinguishes "code doesn't exist" from "receiver predates encryption"
     * from "the write failed" so the caller can say something specific. */
    suspend fun send(
        scanned: String,
        rawUrl: String,
        title: String,
        channels: List<CastChannel> = emptyList()
    ): CastSendResult = withContext(Dispatchers.IO) {
        val target = CastBox.parseScanned(scanned)
        val key = target.key ?: return@withContext CastSendResult.ReceiverTooOld

        signInIfNeeded()
        val doc = sessions.document(target.code).get().await()
        if (!doc.exists()) return@withContext CastSendResult.InvalidCode

        // The title rides inside the box too — on its own it would leak what is being watched to
        // anyone who could read the session document.
        //
        // `channels` is the optional pack that lets the receiver change channel on its own.
        // It has to be a list of ready-made URLs rather than ids: the receiving device has no
        // account to resolve an id against, which is the whole premise of casting. Everything is
        // inside the sealed box, so the extra URLs are no more exposed than the one being played
        // — but it IS more credential-bearing material on that device, which is why the pack is
        // opt-in per cast rather than always sent.
        val box = try {
            val payload = JSONObject().put("url", rawUrl).put("title", title)
            if (channels.isNotEmpty()) {
                val arr = org.json.JSONArray()
                channels.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url)) }
                payload.put("channels", arr)
            }
            CastBox.seal(key, payload.toString())
        } catch (_: Exception) {
            return@withContext CastSendResult.SendFailed
        }

        try {
            sessions.document(target.code).set(
                hashMapOf(
                    "box" to box,
                    // Plain, deliberately: "did the sender include a channel pack, and what
                    // version sent it" is the question that comes up every time a cast
                    // misbehaves, and it cannot be answered from the outside once everything
                    // interesting is encrypted. Neither field is sensitive — the APK and its
                    // version are public — and the stream URL stays inside the box.
                    "senderVersion" to BuildConfig.VERSION_NAME,
                    "packSize" to channels.size,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (_: Exception) {
            return@withContext CastSendResult.SendFailed
        }
        CastSendResult.Sent
    }

    /** Receiver side: listens for a sealed box to arrive and opens it. [scanned] is the payload
     * [createSession] returned. Same pending-write echo-filter WatchPartyManager.listen uses, for
     * the same reason. */
    fun listen(scanned: String, onReceived: (CastPayload) -> Unit): ListenerRegistration {
        val target = CastBox.parseScanned(scanned)
        return sessions.document(target.code).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener

            val box = snap.getString("box")
            if (box != null && target.key != null) {
                // A box that won't open is a stale QR or someone writing junk into the session.
                // Drop it silently; never fall through to the unauthenticated fields below.
                // Two keys can open this: the 256-bit one from the QR, and the one stretched
                // from the typed code. A sender who typed the code instead of scanning sealed
                // with the second, and this used to try only the first — so a manual cast to an
                // Android receiver silently never arrived. The MAC decides which is right, so
                // trying both is safe. The TV receiver has always done this.
                val plain = CastBox.open(target.key, box)
                    ?: CastBox.open(CastBox.keyFromPhrase(target.code), box)
                    ?: return@addSnapshotListener
                val obj = try { JSONObject(plain) } catch (_: Exception) { return@addSnapshotListener }
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@addSnapshotListener

                // The optional channel pack. A malformed entry is skipped rather than failing
                // the whole cast — arriving with one playable channel beats arriving with none.
                val pack = mutableListOf<CastChannel>()
                obj.optJSONArray("channels")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val u = o.optString("u").takeIf { it.isNotBlank() } ?: continue
                        pack += CastChannel(o.optString("n").ifBlank { "Channel ${i + 1}" }, u)
                    }
                }

                onReceived(CastPayload(url, obj.optString("title").ifBlank { "Cast" }, pack))
                return@addSnapshotListener
            }

            // Plaintext shape from a sender that predates the sealed box.
            val url = snap.getString("url") ?: return@addSnapshotListener
            onReceived(CastPayload(url, snap.getString("title") ?: "Cast"))
        }
    }

    /** Sessions are single-use — deletes the doc once consumed (receiver started playback) or
     * abandoned (receiver closed the waiting screen), so Firestore doesn't accumulate dead
     * sessions from every cast anyone's ever done. */
    suspend fun endSession(scanned: String) = withContext(Dispatchers.IO) {
        try { sessions.document(CastBox.parseScanned(scanned).code).delete().await() } catch (_: Exception) {}
    }
}
