package com.iptvapp.handoff

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.iptvapp.remote.RemoteControlServer
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The registry's storage: its own SharedPreferences file. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("handoff_peers", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
}

/** Peer secrets sealed with an AES-GCM key that lives in the Android Keystore and can't be exported. */
class KeystoreSealer : SecretSealer {
    private val alias = "mktv_handoff_peers_v1"

    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
    } catch (e: Exception) {
        Log.w("MKTV_HANDOFF", "keystore unavailable: ${e.javaClass.simpleName}")
        null
    }

    override fun seal(plain: String): String? {
        val k = key() ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            HandoffCrypto.bytesToHex(c.iv) + ":" + HandoffCrypto.bytesToHex(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    override fun open(sealed: String): String? {
        val k = key() ?: return null
        return try {
            val iv = HandoffCrypto.hexToBytes(sealed.substringBefore(':'))
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
            String(c.doFinal(HandoffCrypto.hexToBytes(sealed.substringAfter(':'))), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The phone's receiver for handoff from a TV: /handoff/ paths only, on [HandoffProtocol.PHONE_PORT],
 * read with the remote server's bounded request reader. Not announced on the network: a TV knows
 * the phone's address because the phone told it (an authenticated hello).
 */
object HandoffPhoneServer {
    @Volatile private var socket: ServerSocket? = null
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    ) { r -> Thread(r, "HandoffPhoneWorker").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    @Synchronized
    fun start(context: Context) {
        if (socket?.isClosed == false) return
        val app = context.applicationContext
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(HandoffProtocol.PHONE_PORT)) }
        } catch (e: Exception) {
            Log.w("MKTV_HANDOFF", "phone receiver failed to start: ${e.javaClass.simpleName}")
            return
        }
        socket = s
        Thread {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    try {
                        workers.execute {
                            runCatching {
                                RemoteControlServer.serve(client) { method, path, _, _, body, host ->
                                    if (path.startsWith("/handoff/")) Handoff.handleRequest(app, method, path, body, host)
                                    else "404 Not Found" to JSONObject().put("error", "Not found")
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; name = "HandoffPhoneServer" }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }
}

/**
 * Finds paired TVs' current addresses with one short NSD browse (no continuous scanning). A TV
 * announces its device id; an announcement only updates an address — trust comes from the envelope.
 */
object HandoffDiscovery {
    @Suppress("DEPRECATION")
    suspend fun refresh(context: Context, forMs: Long) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val reg = Handoff.registry(context)
        if (reg.all().none { it.isTv }) return
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        fun next() {
            if (resolving) return
            val info = synchronized(queue) { queue.removeFirstOrNull() } ?: return
            resolving = true
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolving = false; next() }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes?.get("id")?.let { String(it, Charsets.UTF_8) }
                        val host = info.host?.hostAddress
                        if (id != null && HandoffProtocol.isDeviceId(id) && host != null && Handoff.isLocalAddress(host)) {
                            reg.update(id) { it.copy(lastHost = host, lastPort = info.port) }
                        }
                        resolving = false
                        next()
                    }
                })
            }.onFailure { resolving = false }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(queue) { queue.addLast(info) }; next() }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices(RemoteControlServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return
        }
        try {
            delay(forMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}

/**
 * How the receiver asks the person: the prompt screen when MKTV is in front (allowed, no background
 * start); on a phone in the background, a notification that opens it; otherwise it can't ask, and
 * the sender is told to open MKTV here.
 */
class AppReceiverUi(private val context: Context) : ReceiverUi {
    override fun present(incoming: Incoming): Boolean {
        if (Handoff.inForeground) {
            return runCatching { context.startActivity(Handoff.promptIntent(context, incoming.txId)); true }.getOrDefault(false)
        }
        if (Handoff.isTv(context)) return false
        return notify(incoming)
    }

    override fun dismiss(txId: String) {
        Handoff.dismissPrompt(txId)
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, txId.hashCode()) }
    }

    override fun closePlayer(txId: String) {
        Handoff.requestClosePlayers(txId)
    }

    private fun notify(incoming: Incoming): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = context.getSystemService(NotificationManager::class.java)
            if (sys.getNotificationChannel(CHANNEL) == null) {
                sys.createNotificationChannel(NotificationChannel(CHANNEL, "Continue watching from another device", NotificationManager.IMPORTANCE_HIGH))
            }
        }
        val tap = PendingIntent.getActivity(
            context, incoming.txId.hashCode(), Handoff.promptIntent(context, incoming.txId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.iptvapp.R.drawable.ic_notification)
            .setContentTitle("Continue watching from ${incoming.peerName}?")
            .setContentText(HandoffText.summary(incoming.descriptor))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(HandoffProtocol.APPROVAL_TTL_MS)
            .setContentIntent(tap)
            .build()
        return try {
            nm.notify(NOTIFICATION_TAG, incoming.txId.hashCode(), n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL = "handoff_incoming"
        const val NOTIFICATION_TAG = "handoff"
    }
}

/** Words for the prompts and status lines. */
object HandoffText {
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun summary(d: Descriptor): String = when (d.kind) {
        ContentKind.LIVE -> "Live · ${d.title}"
        ContentKind.EPISODE -> "${d.seriesTitle.orEmpty()} S${d.season}E${d.episode}" + if (d.positionMs > 0) " · ${clock(d.positionMs)}" else ""
        ContentKind.MOVIE -> d.title + if (d.positionMs > 0) " · ${clock(d.positionMs)}" else ""
    }
}
