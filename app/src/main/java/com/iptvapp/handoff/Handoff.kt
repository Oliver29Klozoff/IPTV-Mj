package com.iptvapp.handoff

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.remote.RemoteControlServer
import com.iptvapp.util.isLargeScreenDevice
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Playback handoff between paired MKTV devices on the home network (docs/PLAYBACK_HANDOFF.md).
 *
 * Not casting: only a descriptor travels (HandoffProtocol), sealed under the secret the devices made
 * when they paired (HandoffEnvelope). The receiver finds the content in its own catalog, asks the
 * person, and plays it with its own provider and player; the sender keeps playing until that player
 * really plays (HandoffSender / HandoffReceiver).
 *
 * The TV answers on the remote-control server (RemoteControlServer, /handoff/ paths); a phone runs
 * HandoffPhoneServer while MKTV's process is alive and it has a paired TV.
 */
object Handoff {
    private const val TAG = "MKTV_HANDOFF"
    private const val SELF_PREFS = "handoff_self"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): IptvDatabase
        fun repository(): XtreamRepository
        fun preferences(): PreferencesManager
    }

    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun ctx(context: Context? = null): Context = app ?: context!!.applicationContext.also { app = it }

    fun deps(context: Context): Deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    // ── This device ─────────────────────────────────────────────────────────

    /** A random id made once per install. Not secret: it only names this device to its peers. */
    @Synchronized
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(SELF_PREFS, Context.MODE_PRIVATE)
        prefs.getString("id", null)?.takeIf { HandoffProtocol.isDeviceId(it) }?.let { return it }
        val id = HandoffCrypto.randomHex(16)
        prefs.edit().putString("id", id).apply()
        return id
    }

    fun isTv(context: Context) = context.isLargeScreenDevice()

    fun selfName(context: Context): String {
        val name = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return name.filterNot { it.isISOControl() }.take(60).let { if (HandoffProtocol.looksLikeLink(it)) Build.MODEL.take(60) else it }
    }

    fun selfHello(context: Context) = HandoffProtocol.Hello(
        deviceId(context), selfName(context), if (isTv(context)) "tv" else "phone",
        if (isTv(context)) RemoteControlServer.PORT else HandoffProtocol.PHONE_PORT,
    )

    // ── Peers ───────────────────────────────────────────────────────────────

    @Volatile private var registryInstance: PeerRegistry? = null

    fun registry(context: Context): PeerRegistry = registryInstance ?: synchronized(this) {
        registryInstance ?: PeerRegistry(PrefsStore(context.applicationContext), KeystoreSealer()).also { registryInstance = it }
    }

    /** Secrets a message naming [fromId] may be sealed with: that peer's, or (an unknown id) every unclaimed one. */
    private fun secretsFor(context: Context, fromId: String): List<String> {
        val reg = registry(context)
        val claimed = reg.all().filter { it.deviceId.isNotEmpty() }.map { it.secret }.toSet()
        val unclaimed = reg.all().filter { it.deviceId.isEmpty() }.map { it.secret }.toMutableSet()
        if (isTv(context)) unclaimed += RemoteControlServer.pairedTokens(context)
        // A known device first; then secrets no device has claimed yet, which is how a device that
        // paired again (a new secret, same id) reaches its hello. Only a hello may use such a secret
        // for a known id (answer() checks), and it then replaces the old one.
        return listOfNotNull(reg.byId(fromId)?.secret) + unclaimed.filterNot { it in claimed }
    }

    /** Forget [peer] here, tell it to forget this device (best effort), and stop its remote token. */
    fun forget(context: Context, peer: Peer) {
        if (peer.deviceId.isNotEmpty()) registry(context).remove(peer.deviceId) else registry(context).removeBySecret(peer.secret)
        dropLegacyPairing(context, peer.secret)
        scope.launch { runCatching { client(context).send(peer, "forget", JSONObject().put("t", "forget")) } }
    }

    // ── Receiving ───────────────────────────────────────────────────────────

    private val replay = ReplayGuard()

    @Volatile private var receiverInstance: HandoffReceiver? = null

    fun receiver(context: Context): HandoffReceiver = receiverInstance ?: synchronized(this) {
        receiverInstance ?: run {
            val c = context.applicationContext
            val d = deps(c)
            HandoffReceiver(
                ContentMatcher(AppHandoffCatalog(d.database(), d.repository(), d.preferences())),
                AppReceiverUi(c),
                isSending = { outgoing != null },
                ownKind = if (isTv(c)) "TV" else "phone",
            ).also { receiverInstance = it }
        }
    }

    /** One request to a /handoff/ path, on a server worker thread. */
    fun handleRequest(context: Context, method: String, path: String, body: String, remoteHost: String?): Pair<String, JSONObject> {
        val c = ctx(context)
        // Home network only: a request from a public address is refused before anything is read.
        if (!isLocalAddress(remoteHost)) return "403 Forbidden" to JSONObject().put("error", "refused")
        if (method != "POST") return "405 Method Not Allowed" to JSONObject().put("error", "POST only")
        val type = path.removePrefix("/handoff/")
        if (type !in HandoffProtocol.MESSAGE_TYPES) return "404 Not Found" to JSONObject().put("error", "Not found")
        val ownId = deviceId(c)
        val now = System.currentTimeMillis()
        val opened = when (val r = HandoffEnvelope.open(body, ownId, method, path, now, { secretsFor(c, it) }, replay)) {
            is HandoffEnvelope.OpenResult.Clock -> return "401 Unauthorized" to JSONObject().put("error", "clock").put("serverTime", r.nowMs)
            is HandoffEnvelope.OpenResult.Refused -> return "401 Unauthorized" to JSONObject().put("error", "refused")
            is HandoffEnvelope.OpenResult.Ok -> r.opened
        }
        val m = opened.message
        val reply = try {
            if (m.optString("t") != type) throw HandoffRejected("type")
            answer(c, type, m, opened, remoteHost, now)
        } catch (e: HandoffRejected) {
            Log.w(TAG, "refused a $type: ${e.reason}")
            JSONObject().put("state", "failed").put("reason", "The request wasn't valid")
        }
        val sealed = HandoffEnvelope.seal(opened.secret, ownId, opened.fromId, method, path, reply, System.currentTimeMillis(), opened.nonceHex)
        return "200 OK" to sealed
    }

    private fun answer(c: Context, type: String, m: JSONObject, opened: HandoffEnvelope.Opened, remoteHost: String?, now: Long): JSONObject {
        val reg = registry(c)
        if (type == "hello") {
            val h = HandoffProtocol.decodeHello(m)
            if (h.deviceId != opened.fromId) throw HandoffRejected("id")
            val old = reg.byId(h.deviceId) ?: reg.bySecret(opened.secret)
            // Paired again: the previous pairing's secret is retired everywhere.
            if (old != null && !HandoffCrypto.constantTimeEquals(old.secret, opened.secret)) dropLegacyPairing(c, old.secret)
            reg.upsert(
                Peer(
                    deviceId = h.deviceId, name = h.name, type = h.type, secret = opened.secret,
                    lastHost = remoteHost, lastPort = h.port, lastSeenMs = now, handoff = true,
                    providerMap = old?.providerMap.orEmpty(),
                )
            )
            return HandoffProtocol.hello(selfHello(c))
        }
        // Everything else needs a peer that has said who it is.
        val peer = reg.byId(opened.fromId)?.takeIf { HandoffCrypto.constantTimeEquals(it.secret, opened.secret) }
            ?: return JSONObject().put("state", "failed").put("reason", "Pair the devices again")
        if (remoteHost != null && (remoteHost != peer.lastHost || now - peer.lastSeenMs > 60_000L)) {
            reg.update(peer.deviceId) { it.copy(lastHost = remoteHost, lastSeenMs = now) }
        }
        val rx = receiver(c)
        return when (type) {
            "ping" -> JSONObject().put("ok", true).put("busy", rx.current() != null)
            "offer" -> {
                val d = HandoffProtocol.decode(m.optJSONObject("d") ?: throw HandoffRejected("descriptor"))
                if (HandoffProtocol.txOf(m) != d.txId) throw HandoffRejected("tx")
                // Catalog lookups (and maybe the provider's series list): bounded.
                val r = runBlocking { withTimeoutOrNull(12_000L) { rx.offer(peer, d) } }
                    ?: JSONObject().put("state", "failed").put("reason", "This device took too long to check its catalog")
                startSweeper(c)
                r
            }
            "status" -> rx.status(peer, HandoffProtocol.txOf(m), HandoffProtocol.decodeSourceState(m))
            "complete" -> rx.complete(peer, HandoffProtocol.txOf(m))
            "cancel" -> rx.cancel(peer, HandoffProtocol.txOf(m))
            "forget" -> {
                reg.remove(peer.deviceId)
                dropLegacyPairing(c, peer.secret)
                JSONObject().put("ok", true)
            }
            else -> throw HandoffRejected("type")
        }
    }

    /**
     * The older stores of the same pairing: on a TV its remote-control token; on a phone the phone
     * remote's saved TV (otherwise migrateRemoteTv would bring a forgotten TV back on next start).
     */
    private fun dropLegacyPairing(c: Context, secret: String) {
        if (isTv(c)) {
            RemoteControlServer.removeToken(c, secret)
            return
        }
        val store = c.getSharedPreferences("remote_phone", Context.MODE_PRIVATE)
        val token = store.getString("token", null) ?: return
        if (HandoffCrypto.constantTimeEquals(token, secret)) {
            store.edit().remove("token").remove("name").remove("host").remove("port").apply()
        }
    }

    @Volatile private var sweeping = false

    /** Deadlines run on this device's own clock while a request is active (see HandoffReceiver.sweep). */
    private fun startSweeper(c: Context) {
        synchronized(this) { if (sweeping) return; sweeping = true }
        scope.launch {
            try {
                while (true) {
                    kotlinx.coroutines.delay(2_000L)
                    if (!receiver(c).sweep()) break
                }
            } finally {
                synchronized(this@Handoff) { sweeping = false }
            }
        }
    }

    /** Private, link-local and loopback addresses: the home network. */
    fun isLocalAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val addr = runCatching { InetAddress.getByName(host.substringBefore('%')) }.getOrNull() ?: return false
        if (addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress) return true
        // IPv6 unique local (fc00::/7) isn't "site local" to Java.
        val b = addr.address
        return b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /** The handoff this device is sending now; at most one. */
    @Volatile var outgoing: HandoffSender? = null
        internal set

    private val clockOffsets = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun client(context: Context): HandoffTransport = HttpTransport(context.applicationContext)

    /** Over HTTP to the peer's last known address, sealed both ways; null on any failure. */
    private class HttpTransport(private val c: Context) : HandoffTransport {
        override suspend fun send(peer: Peer, type: String, message: JSONObject): JSONObject? =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val host = peer.lastHost ?: return@withContext null
                val port = peer.lastPort.takeIf { it in 1024..65535 }
                    ?: if (peer.isTv) RemoteControlServer.PORT else HandoffProtocol.PHONE_PORT
                for (attempt in 0 until 2) {
                    val r = once(peer, host, port, type, message) ?: return@withContext null
                    if (r.first == 401 && r.second?.optString("error") == "clock" && attempt == 0) {
                        val serverTime = r.second?.optLong("serverTime") ?: 0L
                        if (serverTime > 0) {
                            clockOffsets[peer.deviceId.ifEmpty { peer.secret.take(8) }] = serverTime - System.currentTimeMillis()
                            continue
                        }
                    }
                    return@withContext r.third
                }
                null
            }

        /** (HTTP code, plain error body if any, opened reply). */
        private fun once(peer: Peer, host: String, port: Int, type: String, message: JSONObject): Triple<Int, JSONObject?, JSONObject?>? {
            val path = "/handoff/$type"
            val own = deviceId(c)
            val offset = clockOffsets[peer.deviceId.ifEmpty { peer.secret.take(8) }] ?: 0L
            val at = System.currentTimeMillis() + offset
            val envelope = HandoffEnvelope.seal(peer.secret, own, peer.deviceId, "POST", path, message, at)
            return try {
                val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
                val conn = URL("http://$h:$port$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 6_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > HandoffProtocol.MAX_ENVELOPE_BYTES) return@use null
                    }
                    buf.toString("UTF-8")
                }
                conn.disconnect()
                if (code != 200 || text == null) {
                    return Triple(code, text?.let { runCatching { JSONObject(it) }.getOrNull() }, null)
                }
                val opened = HandoffEnvelope.open(
                    // The reply is stamped with the peer's clock: judged by the same corrected time.
                    text, own, "POST", path, System.currentTimeMillis() + offset, { listOf(peer.secret) }, null, envelope.getString("n"),
                ) as? HandoffEnvelope.OpenResult.Ok ?: return null
                // A known peer must answer as itself; a first hello learns the id from the answer.
                if (peer.deviceId.isNotEmpty() && opened.opened.fromId != peer.deviceId) return null
                Triple(200, null, opened.opened.message.also { it.put("_from", opened.opened.fromId) })
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Says hello to [peer] (learning its id on the first one) and records that it answered. */
    suspend fun hello(context: Context, peer: Peer): Peer? {
        val c = context.applicationContext
        val reply = client(c).send(peer, "hello", HandoffProtocol.hello(selfHello(c))) ?: return null
        val h = try { HandoffProtocol.decodeHello(reply) } catch (_: HandoffRejected) { return null }
        if (h.deviceId != reply.optString("_from")) return null
        val updated = peer.copy(deviceId = h.deviceId, name = h.name, type = h.type, lastSeenMs = System.currentTimeMillis(), handoff = true,
            lastPort = h.port.takeIf { it > 0 } ?: peer.lastPort)
        val reg = registry(c)
        reg.removeBySecret(peer.secret)
        reg.upsert(updated)
        return updated
    }

    /** True if [peer] answers now (an authenticated ping). */
    suspend fun reachable(context: Context, peer: Peer): Boolean =
        client(context).send(peer, "ping", JSONObject().put("t", "ping"))?.optBoolean("ok") == true

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Volatile private var resumed = 0
    @Volatile private var players = 0

    /** True while one of MKTV's screens is in front. */
    val inForeground get() = resumed > 0

    /** True while a player screen exists (an incoming handoff would replace it). */
    val playerActive get() = players > 0

    fun playerCreated() { players++ }
    fun playerDestroyed() { players = (players - 1).coerceAtLeast(0) }

    private val _closePlayers = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** "*" closes every player (an accepted handoff takes over); a tx id closes that handoff's player. */
    val closePlayers: SharedFlow<String> = _closePlayers
    fun requestClosePlayers(which: String) { _closePlayers.tryEmit(which) }

    private val _dismissPrompts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val dismissPrompts: SharedFlow<String> = _dismissPrompts
    fun dismissPrompt(txId: String) { _dismissPrompts.tryEmit(txId) }

    /** From IptvApplication.onCreate. Cheap: nothing listens until there is a paired device. */
    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed++ }
            override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        if (isTv(application)) return
        scope.launch {
            migrateRemoteTv(application)
            if (registry(application).all().any { it.isTv }) startPhoneSide(application)
        }
    }

    /** The phone side: listen for TVs, and tell paired TVs where this phone is (now and on network changes). */
    fun startPhoneSide(context: Context) {
        val c = context.applicationContext
        HandoffPhoneServer.start(c)
        greetTvs(c)
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { greetTvs(c) }
            })
        }
    }

    @Volatile private var networkCallbackRegistered = false
    @Volatile private var lastGreetMs = 0L

    private fun greetTvs(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastGreetMs < 10_000L) return
        lastGreetMs = now
        scope.launch {
            HandoffDiscovery.refresh(c, 3_000L)
            for (tv in registry(c).all().filter { it.isTv }) runCatching { hello(c, tv) }
        }
    }

    /** A TV paired with the phone remote before handoff existed becomes a handoff peer (it says who it is on hello). */
    private fun migrateRemoteTv(c: Context) {
        val store = c.getSharedPreferences("remote_phone", Context.MODE_PRIVATE)
        val token = store.getString("token", null) ?: return
        val reg = registry(c)
        if (reg.bySecret(token) != null) return
        reg.upsert(
            Peer(
                deviceId = "", name = store.getString("name", null) ?: "MKTV TV", type = "tv", secret = token,
                lastHost = store.getString("host", null), lastPort = store.getInt("port", RemoteControlServer.PORT),
            )
        )
    }

    /** The phone remote just paired with a TV: it is a handoff peer too. */
    fun onPhonePairedWithTv(context: Context, name: String, host: String, port: Int, token: String) {
        val c = context.applicationContext
        registry(c).upsert(Peer("", name, "tv", token, host, port))
        startPhoneSide(c)
        scope.launch { registry(c).bySecret(token)?.let { hello(c, it) } }
    }

    fun runAsync(block: suspend () -> Unit) { scope.launch { block() } }

    /** Work that outlives the screen that started it, on the main thread. */
    fun runOnMain(block: suspend () -> Unit) { scope.launch(Dispatchers.Main) { block() } }

    /** Opens the accept prompt for [txId] (the app is in front, or the person tapped the notification). */
    fun promptIntent(context: Context, txId: String): Intent =
        Intent(context, com.iptvapp.ui.handoff.HandoffAcceptActivity::class.java)
            .putExtra(com.iptvapp.ui.handoff.HandoffAcceptActivity.EXTRA_TX, txId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
