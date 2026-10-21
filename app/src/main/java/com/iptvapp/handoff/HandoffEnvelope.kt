package com.iptvapp.handoff

import org.json.JSONObject

/**
 * A sealed request or reply on the wire:
 * `{"v":1,"from":<device id>,"to":<device id or "">,"at":<ms>,"n":<nonce hex>,"c":<ciphertext hex>}`.
 *
 * `to` is empty only for the first hello of a pairing made before devices had ids: the receiver then
 * tries each secret it has (few), and the one that opens it says who sent it.
 */
object HandoffEnvelope {

    class Opened(val message: JSONObject, val fromId: String, val secret: String, val nonceHex: String, val sentAtMs: Long)

    sealed class OpenResult {
        class Ok(val opened: Opened) : OpenResult()
        /** The sender's clock is too far from ours; [nowMs] lets it correct and retry. */
        class Clock(val nowMs: Long) : OpenResult()
        /** Unknown sender, wrong key, tampered, replayed, too big or malformed — all refused alike. */
        object Refused : OpenResult()
    }

    fun seal(
        secret: String, fromId: String, toId: String, method: String, path: String,
        message: JSONObject, sentAtMs: Long, requestNonceHex: String? = null,
    ): JSONObject {
        val plain = message.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= HandoffProtocol.MAX_MESSAGE_BYTES) { "message too big" }
        val key = HandoffCrypto.directionKey(secret, fromId, toId)
        val sealed = HandoffCrypto.seal(key, plain, HandoffCrypto.aad(method, path, fromId, toId, sentAtMs, requestNonceHex))
        return JSONObject()
            .put("v", HandoffProtocol.VERSION)
            .put("from", fromId)
            .put("to", toId)
            .put("at", sentAtMs)
            .put("n", sealed.nonceHex)
            .put("c", sealed.cipherHex)
    }

    /**
     * Opens [body] addressed to [ownId]. [secretsFor] gives the secrets that may have sealed it for
     * the sender id it names (one for a known peer; for an unknown id, every unclaimed secret).
     * [requestNonceHex] is set when opening a reply. Replays are refused through [replay].
     */
    fun open(
        body: String, ownId: String, method: String, path: String, nowMs: Long,
        secretsFor: (fromId: String) -> List<String>, replay: ReplayGuard?, requestNonceHex: String? = null,
    ): OpenResult {
        if (body.length > HandoffProtocol.MAX_ENVELOPE_BYTES) return OpenResult.Refused
        val o = try { JSONObject(body) } catch (_: Exception) { return OpenResult.Refused }
        if (o.optInt("v", -1) != HandoffProtocol.VERSION) return OpenResult.Refused
        val from = o.optString("from")
        val to = o.optString("to")
        val at = (o.opt("at") as? Number)?.toLong() ?: return OpenResult.Refused
        val nonce = o.optString("n")
        val cipher = o.optString("c")
        if (!HandoffProtocol.isDeviceId(from) || from == ownId) return OpenResult.Refused
        if (to.isNotEmpty() && to != ownId) return OpenResult.Refused
        if (!Regex("[0-9a-f]{24}").matches(nonce) || cipher.isEmpty() || cipher.length % 2 != 0) return OpenResult.Refused
        if (kotlin.math.abs(nowMs - at) > HandoffProtocol.TIME_WINDOW_MS) return OpenResult.Clock(nowMs)
        val aad = HandoffCrypto.aad(method, path, from, to, at, requestNonceHex)
        for (secret in secretsFor(from)) {
            val plain = HandoffCrypto.open(HandoffCrypto.directionKey(secret, from, to), HandoffCrypto.Sealed(nonce, cipher), aad)
                ?: continue
            if (plain.size > HandoffProtocol.MAX_MESSAGE_BYTES) return OpenResult.Refused
            // Only an authentic message can use up a nonce, so nobody can block one in advance.
            if (replay != null && !replay.firstUse(from, nonce, nowMs)) return OpenResult.Refused
            val message = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (_: Exception) { return OpenResult.Refused }
            return OpenResult.Ok(Opened(message, from, secret, nonce, at))
        }
        return OpenResult.Refused
    }
}

/** Nonces seen inside the time window, per sender; a second use is a replay. Bounded. */
class ReplayGuard(private val keepMs: Long = 2 * HandoffProtocol.TIME_WINDOW_MS, private val maxEntries: Int = 4_096) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun firstUse(fromId: String, nonceHex: String, nowMs: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value > keepMs || seen.size > maxEntries) it.remove() else break
        }
        val key = "$fromId:$nonceHex"
        if (seen.containsKey(key)) return false
        seen[key] = nowMs
        return true
    }
}
