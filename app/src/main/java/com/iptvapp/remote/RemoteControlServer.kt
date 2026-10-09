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
 * Pairing, once per phone: the phone asks (/pair/start), the TV shows a 4-digit code for two
 * minutes, the phone sends it back (/pair) and gets a token. Every command then carries that
 * token; unpaired requests are refused. Tokens are kept in SharedPreferences "remote_control".
 *
 * Commands are handed to the app through [commands]; RemoteControlHooks gives them to whichever
 * MKTV screen is in front.
 */
object RemoteControlServer {
    const val SERVICE_TYPE = "_mktvremote._tcp."
    const val PORT = 9481
    private const val PREFS = "remote_control"
    private const val KEY_TOKENS = "tokens"
    const val CODE_TTL_MS = 120_000L
    private const val MAX_CODE_TRIES = 3

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

    /** The code to show on the TV while a phone is pairing (null when none). */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode
    private var codeExpiresAt = 0L
    private var codeTries = 0
    private var failedPairs = 0
    private var lockedUntil = 0L
    private const val MAX_FAILED_PAIRS = 10
    private const val LOCKOUT_MS = 60 * 60_000L

    /** A channel picked while a screen that can't tune was in front (a movie, Settings…): the TV
     * home screen is brought back and plays it (RemoteControlHooks). */
    @Volatile var pendingTune: String? = null

    fun cancelPairing() { _pairingCode.value = null }

    /** What's playing, kept current by the TV screens for the phone's "now playing" line. */
    @Volatile var nowPlaying: String = ""

    // Counted, since a recreated TV home screen starts its server before the old one stops it.
    private var users = 0
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val random = SecureRandom()

    @Synchronized
    fun start(context: Context) {
        if (users++ > 0) return
        val app = context.applicationContext
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
                    Thread { runCatching { handle(app, client) } }.apply { isDaemon = true }.start()
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
            override fun onServiceRegistered(info: NsdServiceInfo) {}
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
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var token = ""
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (name == "content-length") contentLength = value.toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                if (name == "x-mktv-token") token = value
            }
            val body = if (contentLength > 0) ByteArray(contentLength).let { buf ->
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""
            val (status, json) = route(context, method, path, token, body)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    /** One CRLF-terminated header line, read as bytes (null at end of stream). */
    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) break
            if (bytes.size() >= 8_192) return null
            bytes.write(b)
        }
        return bytes.toString("UTF-8").trimEnd('\r')
    }

    private fun route(context: Context, method: String, path: String, token: String, body: String): Pair<String, JSONObject> {
        when (path) {
            "/pair/start" -> {
                if (method != "POST") return "405 Method Not Allowed" to JSONObject().put("error", "POST only")
                synchronized(this) {
                    val now = System.currentTimeMillis()
                    if (now < lockedUntil) {
                        return "429 Too Many Requests" to JSONObject().put("error", "Too many wrong codes — try again in an hour")
                    }
                    // Asking again while a code is showing keeps that code (and its tries used), so
                    // asking over and over can't buy more guesses.
                    if (_pairingCode.value == null || now > codeExpiresAt) {
                        codeExpiresAt = now + CODE_TTL_MS
                        codeTries = 0
                        _pairingCode.value = (1000 + random.nextInt(9000)).toString()
                    }
                }
                return "200 OK" to JSONObject().put("ok", true)
            }
            "/pair" -> {
                if (method != "POST") return "405 Method Not Allowed" to JSONObject().put("error", "POST only")
                val sent = try { JSONObject(body).optString("code") } catch (_: Exception) { "" }
                // A few tries per code, then it's gone; and after MAX_FAILED_PAIRS wrong codes in all,
                // pairing stops for an hour — so 4 digits can't be found by trying them all.
                synchronized(this) {
                    val expected = _pairingCode.value
                    if (expected == null || System.currentTimeMillis() > codeExpiresAt) {
                        return "403 Forbidden" to JSONObject().put("error", "The code expired — pair again")
                    }
                    if (sent != expected) {
                        if (++codeTries >= MAX_CODE_TRIES) _pairingCode.value = null
                        if (++failedPairs >= MAX_FAILED_PAIRS) {
                            failedPairs = 0
                            lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
                            _pairingCode.value = null
                        }
                        return "403 Forbidden" to JSONObject().put("error", "Wrong code")
                    }
                    failedPairs = 0
                    _pairingCode.value = null
                }
                val newToken = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                addToken(context, newToken)
                return "200 OK" to JSONObject().put("token", newToken).put("name", "MKTV on ${Build.MODEL}")
            }
        }
        if (token.isEmpty() || token !in tokens(context)) {
            return "401 Unauthorized" to JSONObject().put("error", "Not paired")
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
                _commands.tryEmit(command)
                "200 OK" to JSONObject().put("ok", true)
            }
            else -> "404 Not Found" to JSONObject().put("error", "Not found")
        }
    }
}
