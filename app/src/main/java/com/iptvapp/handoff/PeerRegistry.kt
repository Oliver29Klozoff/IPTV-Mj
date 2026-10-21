package com.iptvapp.handoff

import org.json.JSONArray
import org.json.JSONObject

/** A paired MKTV device. [secret] is the pairing secret (never sent); [deviceId] is empty until it says hello. */
data class Peer(
    val deviceId: String,
    val name: String,
    val type: String,
    val secret: String,
    val lastHost: String? = null,
    val lastPort: Int = 0,
    val lastSeenMs: Long = 0L,
    /** The receiver understands handoff (it has said hello with this protocol). */
    val handoff: Boolean = false,
    /** The sender's provider tag → local provider slot, chosen on this device. */
    val providerMap: Map<String, Int> = emptyMap(),
) {
    val isTv get() = type == "tv"
    val label get() = name.ifBlank { if (isTv) "MKTV TV" else "MKTV phone" }
}

/** Plain storage for the registry (SharedPreferences in the app, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Seals a secret for storage (an Android Keystore key in the app); null when it can't. */
interface SecretSealer {
    fun seal(plain: String): String?
    fun open(sealed: String): String?
}

/**
 * The devices this one is paired with, for handoff. Secrets are stored sealed ([SecretSealer]).
 * A peer whose secret can no longer be opened (keystore reset) is dropped: it has to pair again.
 */
class PeerRegistry(private val store: KeyValueStore, private val sealer: SecretSealer) {
    private val key = "handoff_peers_v1"
    private var cache: MutableList<Peer>? = null

    @Synchronized
    fun all(): List<Peer> = load().toList()

    @Synchronized
    fun byId(deviceId: String): Peer? = load().firstOrNull { it.deviceId.isNotEmpty() && it.deviceId == deviceId }

    @Synchronized
    fun bySecret(secret: String): Peer? = load().firstOrNull { HandoffCrypto.constantTimeEquals(it.secret, secret) }

    /** Adds or replaces the peer with this secret (or this id). */
    @Synchronized
    fun upsert(peer: Peer) {
        val list = load()
        list.removeAll { HandoffCrypto.constantTimeEquals(it.secret, peer.secret) || (peer.deviceId.isNotEmpty() && it.deviceId == peer.deviceId) }
        list.add(peer)
        save(list)
    }

    @Synchronized
    fun update(deviceId: String, change: (Peer) -> Peer) {
        val list = load()
        val i = list.indexOfFirst { it.deviceId == deviceId }
        if (i < 0) return
        list[i] = change(list[i])
        save(list)
    }

    /** Forgets a peer; returns its secret so callers can drop it from other stores too. */
    @Synchronized
    fun remove(deviceId: String): Peer? {
        val list = load()
        val p = list.firstOrNull { it.deviceId == deviceId } ?: return null
        list.remove(p)
        save(list)
        return p
    }

    @Synchronized
    fun removeBySecret(secret: String) {
        val list = load()
        if (list.removeAll { HandoffCrypto.constantTimeEquals(it.secret, secret) }) save(list)
    }

    private fun load(): MutableList<Peer> {
        cache?.let { return it }
        val list = mutableListOf<Peer>()
        val raw = store.get(key)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val secret = sealer.open(o.optString("s")) ?: continue
                    val map = mutableMapOf<String, Int>()
                    o.optJSONObject("pm")?.let { pm -> pm.keys().forEach { k -> map[k] = pm.optInt(k) } }
                    list.add(
                        Peer(
                            deviceId = o.optString("id"),
                            name = o.optString("name"),
                            type = o.optString("type", "tv"),
                            secret = secret,
                            lastHost = o.optString("host").takeIf { it.isNotEmpty() },
                            lastPort = o.optInt("port"),
                            lastSeenMs = o.optLong("seen"),
                            handoff = o.optBoolean("ho"),
                            providerMap = map,
                        )
                    )
                }
            } catch (_: Exception) {
                // A damaged list is as if empty: devices pair again.
            }
        }
        cache = list
        return list
    }

    private fun save(list: MutableList<Peer>) {
        cache = list
        val arr = JSONArray()
        for (p in list) {
            val sealed = sealer.seal(p.secret) ?: continue
            arr.put(
                JSONObject()
                    .put("id", p.deviceId)
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("s", sealed)
                    .put("host", p.lastHost ?: "")
                    .put("port", p.lastPort)
                    .put("seen", p.lastSeenMs)
                    .put("ho", p.handoff)
                    .put("pm", JSONObject().apply { p.providerMap.forEach { (k, v) -> put(k, v) } })
            )
        }
        store.put(key, arr.toString())
    }
}
