package com.iptvapp.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Phone as Shield remote (v7.18), the TV side: a small HTTP server on the home network, announced
 * over mDNS ("_mktvremote._tcp") so the phone finds it without typing an address.
 *
 * Pairing, once per phone, is an ECDH key exchange checked by numeric comparison (as Bluetooth
 * does it): the phone commits to its key (/pair/start, which answers with the TV's key), then
 * reveals it (/pair); both screens show the same 6-digit number, made from both keys, and the
 * person picks Pair on the TV with its own remote. Because the phone committed before seeing the
 * TV's key, someone in the middle can't make the two numbers agree. Both sides work out the token
 * from the exchange (deriveToken) — it is never sent — and every command is then signed with it
 * (see sign); anything else is refused. Tokens are kept in SharedPreferences "remote_control".
 *
 * Commands are handed to the app through [commands]; RemoteControlHooks gives them to whichever
 * MKTV screen is in front.
 */
object RemoteControlServer {
    const val SERVICE_TYPE = "_mktvremote._tcp."
    const val PORT = 9481
    private const val PREFS = "remote_control"
    private const val KEY_TOKENS = "tokens"
    const val PAIRING_TTL_MS = 120_000L

    /** The buttons the phone can press — names it sends, and the TV remote key each one is. */
    val KEYS = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
        "back" to KeyEvent.KEYCODE_BACK,
        "chup" to KeyEvent.KEYCODE_CHANNEL_UP,
        "chdown" to KeyEvent.KEYCODE_CHANNEL_DOWN,
        "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "last" to KeyEvent.KEYCODE_LAST_CHANNEL,
        "guide" to KeyEvent.KEYCODE_GUIDE
    )

    sealed class Command {
        /** Tune a channel by name — the phone and the TV may not share a provider, so ids from the
         * phone mean nothing here; the TV finds its own copy the way voice tune does. */
        data class Tune(val name: String) : Command()
        /** A remote button, pressed in whichever MKTV screen is in front — exactly as if it came
         * from the TV's own remote, so the phone works on every screen. */
        data class Key(val keyCode: Int) : Command()
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 16)
    val commands: SharedFlow<Command> = _commands

    /** The number to confirm on the TV while a phone is pairing (null when none). */
    /** A pairing waiting for the person's answer: which one ([id], its commitment) and its number. */
    data class PairingPrompt(val id: String, val number: String)
    private val _pairingNumber = MutableStateFlow<PairingPrompt?>(null)
    val pairingNumber: StateFlow<PairingPrompt?> = _pairingNumber

    /** One pairing at a time; a new /pair/start replaces it. [accepted] is the person's answer. */
    private class Pairing(val commit: String, val tvKeys: java.security.KeyPair, val startedAt: Long) {
        var token: String? = null
        var accepted: Boolean? = null
    }
    private var pairing: Pairing? = null
    private var failedPairs = 0
    private var lockedUntil = 0L
    private const val MAX_FAILED_PAIRS = 10
    private const val LOCKOUT_MS = 60 * 60_000L
    private var appContext: Context? = null

    /** A channel picked while a screen that can't tune was in front (a movie, Settings…): the TV
     * home screen is brought back and plays it (RemoteControlHooks). */
    @Volatile var pendingTune: String? = null

    /** The person's answer on the TV to the pairing [id] they were shown — ignored if another one has
     * taken its place since. Refusals count toward the hour-long lockout (several in a row look like
     * someone trying their luck); [expired] closes it without counting. */
    @Synchronized
    fun answerPairing(id: String, accept: Boolean, expired: Boolean = false) {
        if (_pairingNumber.value?.id == id) _pairingNumber.value = null
        val p = pairing?.takeIf { it.commit == id } ?: return
        val token = p.token
        if (accept && token != null) {
            appContext?.let { addToken(it, token) }
            p.accepted = true
            failedPairs = 0
        } else {
            p.accepted = false
            if (!expired) countFailure()
        }
    }

    private fun countFailure() {
        if (++failedPairs >= MAX_FAILED_PAIRS) {
            failedPairs = 0
            lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
        }
    }

    /** What's playing, kept current by the TV screens for the phone's "now playing" line. */
    @Volatile var nowPlaying: String = ""

    // Counted, since a recreated TV home screen starts its server before the old one stops it.
    private var users = 0
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var registeredName: String? = null
    private val random = SecureRandom()
    private const val REQUEST_DEADLINE_MS = 10_000L
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        4, 4, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue()
    ) { r -> Thread(r, "RemoteControlWorker").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    @Synchronized
    fun start(context: Context) {
        if (users++ > 0) return
        val app = context.applicationContext
        appContext = app
        RemoteControlHooks.install(app as android.app.Application)
        val socket = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) {
            Log.w("RemoteControl", "server failed to start on $PORT", e)
            return
        }
        serverSocket = socket
        advertise(app)
        Thread {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    // A few at a time; more than that are closed at once, so a flood of
                    // connections can't use up the TV's threads.
                    try {
                        workers.execute { runCatching { handle(app, client) } }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                } catch (e: Exception) {
                    if (!socket.isClosed) Log.w("RemoteControl", "accept failed", e)
                }
            }
        }.apply { isDaemon = true; name = "RemoteControlServer" }.start()
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        try { registration?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registration = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun advertise(context: Context) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nsd
        val info = NsdServiceInfo().apply {
            serviceName = "MKTV on ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            // The name actually announced — Android adds " (2)" when another TV already has it.
            override fun onServiceRegistered(info: NsdServiceInfo) { registeredName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w("RemoteControl", "mDNS register failed: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) { Log.w("RemoteControl", "mDNS", e) }
    }

    private fun tokens(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()

    private fun addToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TOKENS, tokens(context) + token).apply()
    }

    private fun handle(context: Context, socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            // Bytes, not characters: Content-Length counts bytes, and a channel name like "Univisión"
            // has fewer characters than bytes.
            val input = BufferedInputStream(s.getInputStream())
            // The whole request within REQUEST_DEADLINE_MS, so a client trickling bytes can't hold a worker.
            val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
            val requestLine = readLine(input, deadline) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var signedAt = ""
            var signature = ""
            var headers = 0
            while (true) {
                if (++headers > 64) return
                val line = readLine(input, deadline) ?: return
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (name == "content-length") contentLength = value.toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                if (name == "x-mktv-time") signedAt = value
                if (name == "x-mktv-sig") signature = value
            }
            val body = if (contentLength > 0) ByteArray(contentLength).let { buf ->
                var read = 0
                while (read < contentLength) {
                    if (System.currentTimeMillis() > deadline) return
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""
            val (status, json) = route(context, method, path, signedAt, signature, body)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    // ── Request signing ─────────────────────────────────────────────────────
    // After pairing the token itself never crosses the network again: each request carries the
    // time and an HMAC of it keyed by the token (sign), so a device that merely pretends to be the
    // TV learns nothing it can use, and a captured request can't be sent twice (recentSignatures).

    private enum class Auth { OK, CLOCK, BAD }
    const val SIGNATURE_WINDOW_MS = 2 * 60_000L
    private val recentSignatures = LinkedHashMap<String, Long>()

    /** HMAC-SHA256 of the request with [token], in hex. Shared with the phone side. */
    fun sign(token: String, method: String, path: String, signedAt: Long, body: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$method\n$path\n$signedAt\n$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── Pairing key exchange (ECDH, P-256) — shared with the phone side ─────

    fun newKeyPair(): java.security.KeyPair =
        java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    fun encodePublicKey(key: java.security.PublicKey): String =
        android.util.Base64.encodeToString(key.encoded, android.util.Base64.NO_WRAP)

    fun decodePublicKey(encoded: String): java.security.PublicKey =
        java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
        )

    /** The phone's commitment to its key: SHA-256 of the key and a random nonce, in hex. */
    fun commitment(phoneKey: java.security.PublicKey, nonce: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(phoneKey.encoded + nonce)
            .joinToString("") { "%02x".format(it) }

    /** The 6-digit number both screens show, from both keys and the nonce: "482 913". */
    fun comparisonNumber(phoneKey: java.security.PublicKey, tvKey: java.security.PublicKey, nonce: ByteArray): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(phoneKey.encoded + tvKey.encoded + nonce)
        val n = ((h[0].toLong() and 0xff) shl 24 or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)) % 1_000_000
        return "%06d".format(n).let { "${it.substring(0, 3)} ${it.substring(3)}" }
    }

    /** The pairing token both sides arrive at: SHA-256 of the ECDH shared secret, in hex. */
    fun deriveToken(own: java.security.PrivateKey, other: java.security.PublicKey): String {
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(own)
        agreement.doPhase(other, true)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(agreement.generateSecret() + "MKTV remote".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun checkSignature(context: Context, method: String, path: String, signedAt: String, signature: String, body: String): Auth {
        val at = signedAt.toLongOrNull() ?: return Auth.BAD
        // One spelling for checking and for the replay list, so "AB…" can't replay "ab…".
        val sig = signature.lowercase()
        if (sig.isEmpty()) return Auth.BAD
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - at) > SIGNATURE_WINDOW_MS) return Auth.CLOCK
        val valid = tokens(context).any { token ->
            java.security.MessageDigest.isEqual(sign(token, method, path, at, body).toByteArray(), sig.toByteArray())
        }
        if (!valid) return Auth.BAD
        synchronized(recentSignatures) {
            val it = recentSignatures.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > 2 * SIGNATURE_WINDOW_MS) it.remove() else break
            if (recentSignatures.containsKey(sig)) return Auth.BAD
            recentSignatures[sig] = now
        }
        return Auth.OK
    }

    /** One CRLF-terminated header line, read as bytes (null at end of stream, or past [deadline]). */
    private fun readLine(input: BufferedInputStream, deadline: Long): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) break
            if (bytes.size() >= 8_192 || System.currentTimeMillis() > deadline) return null
            bytes.write(b)
        }
        return bytes.toString("UTF-8").trimEnd('\r')
    }

    private fun route(context: Context, method: String, path: String, signedAt: String, signature: String, body: String): Pair<String, JSONObject> {
        when (path) {
            // Step 1: the phone's commitment to its key; the answer is the TV's key.
            "/pair/start" -> {
                if (method != "POST") return "405 Method Not Allowed" to JSONObject().put("error", "POST only")
                val commit = try { JSONObject(body).optString("commit") } catch (_: Exception) { "" }
                if (!commit.matches(Regex("[0-9a-f]{64}"))) {
                    return "400 Bad Request" to JSONObject().put("error", "Update MKTV on this phone")
                }
                val tvKeys = newKeyPair()
                synchronized(this) {
                    if (System.currentTimeMillis() < lockedUntil) {
                        return "429 Too Many Requests" to JSONObject().put("error", "Too many refused pairings — try again in an hour")
                    }
                    pairing = Pairing(commit, tvKeys, System.currentTimeMillis())
                    _pairingNumber.value = null
                }
                return "200 OK" to JSONObject().put("pub", encodePublicKey(tvKeys.public))
            }
            // Step 2: the phone's key, which must match its commitment. The TV then shows the number.
            "/pair" -> {
                if (method != "POST") return "405 Method Not Allowed" to JSONObject().put("error", "POST only")
                val request = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val phoneKey = try { decodePublicKey(request.optString("pub")) } catch (_: Exception) { null }
                val nonce = try { android.util.Base64.decode(request.optString("nonce"), android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
                if (phoneKey == null || nonce == null || nonce.size < 16) {
                    return "400 Bad Request" to JSONObject().put("error", "Update MKTV on this phone")
                }
                synchronized(this) {
                    val p = pairing
                    if (p == null || p.token != null || System.currentTimeMillis() - p.startedAt > PAIRING_TTL_MS) {
                        return "403 Forbidden" to JSONObject().put("error", "Pairing timed out — try again")
                    }
                    if (commitment(phoneKey, nonce) != p.commit) {
                        pairing = null
                        countFailure()
                        return "403 Forbidden" to JSONObject().put("error", "Pairing failed — try again")
                    }
                    // The token is the exchange's shared secret: each side works it out from its own
                    // private key and the other's public one, so it never crosses the network. It is
                    // only kept once the person picks Pair on the TV (answerPairing).
                    p.token = try { deriveToken(p.tvKeys.private, phoneKey) } catch (_: Exception) {
                        pairing = null
                        return "400 Bad Request" to JSONObject().put("error", "Update MKTV on this phone")
                    }
                    _pairingNumber.value = PairingPrompt(p.commit, comparisonNumber(phoneKey, p.tvKeys.public, nonce))
                }
                return "200 OK" to JSONObject().put("name", registeredName ?: "MKTV on ${Build.MODEL}")
            }
            // Step 3: the phone waits for the person's answer on the TV.
            "/pair/status" -> {
                val commit = try { JSONObject(body).optString("commit") } catch (_: Exception) { "" }
                synchronized(this) {
                    val p = pairing
                    val state = when {
                        p == null || p.commit != commit -> "gone"
                        p.accepted == true -> "paired"
                        p.accepted == false -> "refused"
                        System.currentTimeMillis() - p.startedAt > PAIRING_TTL_MS -> "gone"
                        else -> "waiting"
                    }
                    if (state != "waiting" && p?.commit == commit) {
                        pairing = null
                        _pairingNumber.value = null
                    }
                    return "200 OK" to JSONObject().put("state", state)
                }
            }
        }
        when (checkSignature(context, method, path, signedAt, signature, body)) {
            Auth.OK -> {}
            Auth.CLOCK -> return "401 Unauthorized" to JSONObject().put("error", "clock").put("serverTime", System.currentTimeMillis())
            Auth.BAD -> return "401 Unauthorized" to JSONObject().put("error", "Not paired")
        }
        return when (path) {
            "/status" -> "200 OK" to JSONObject().put("nowPlaying", nowPlaying)
            "/command" -> {
                if (method != "POST") return "405 Method Not Allowed" to JSONObject().put("error", "POST only")
                val cmd = try { JSONObject(body) } catch (_: Exception) { null }
                    ?: return "400 Bad Request" to JSONObject().put("error", "Bad JSON")
                val command = when (cmd.optString("cmd")) {
                    "tune" -> cmd.optString("name").takeIf { it.isNotBlank() }?.let { Command.Tune(it) }
                    "key" -> KEYS[cmd.optString("key")]?.let { Command.Key(it) }
                    else -> null
                } ?: return "400 Bad Request" to JSONObject().put("error", "Unknown command")
                if (!RemoteControlHooks.isInFront) {
                    return "409 Conflict" to JSONObject().put("error", "MKTV isn't on screen on the TV — open it there")
                }
                if (!_commands.tryEmit(command)) {
                    return "503 Service Unavailable" to JSONObject().put("error", "The TV is busy — try again")
                }
                "200 OK" to JSONObject().put("ok", true)
            }
            else -> "404 Not Found" to JSONObject().put("error", "Not found")
        }
    }
}
