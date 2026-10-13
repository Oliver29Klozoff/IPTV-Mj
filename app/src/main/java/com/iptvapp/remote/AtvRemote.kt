package com.iptvapp.remote

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Sleeps the Shield the way its own power button does, from the phone.
 *
 * An app cannot inject that key itself. The Android TV remote service, which is already on the
 * Shield and is what the Google TV phone app talks to, can — but only for another device: it
 * pairs with a client on the Shield itself and then refuses that client's commands. So the phone
 * remote talks to it directly, at the TV's address. The first press shows a code on the TV; after
 * that code is entered on the phone, later presses just send power. A short press sleeps the box.
 * The TV turns off with it when the Shield's HDMI power control is on.
 */
internal object AtvRemote {

    private const val TAG = "RemoteControl"
    // "_tls": a key made before DIGEST_NONE was allowed (see keyStore) can't sign the handshake.
    private const val ALIAS = "mktv_atv_remote_tls"
    private const val COMMAND_PORT = 6466
    private const val PAIR_PORT = 6467
    private const val CLIENT_NAME = "MKTV"
    private const val CODE_TTL_MS = 120_000L
    // Power right after the session starts is ignored; and how long to wait for the screen to report off.
    private const val POWER_SETTLE_MS = 500L
    private const val POWER_CONFIRM_MS = 4_000L

    sealed class Outcome {
        /** The power key was sent. The Shield sleeps; the connection usually drops as it does. */
        object Asleep : Outcome()
        /** The remote service says the screen is already off, so power was not sent (it would wake it). */
        object AlreadyOff : Outcome()
        /** The TV is showing a 6-character code. Send it back with the next press. */
        object NeedCode : Outcome()
        class Refused(val reason: String) : Outcome()
    }

    // One press at a time, without holding a lock across network I/O: an overlapping press is
    // refused at once, and cancel() (the remote screen's onDestroy, on the main thread) only
    // closes sockets, which ends any blocked read in the running press.
    private val busy = AtomicBoolean(false)
    private val pending = AtomicReference<Pending?>(null)
    private val openSockets = Collections.newSetFromMap(ConcurrentHashMap<SSLSocket, Boolean>())
    private val expiry = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mktv-atv-remote").apply { isDaemon = true } }

    private class Pending(
        val host: String, val socket: SSLSocket, val serverCert: X509Certificate, val clientCert: X509Certificate, val startedAt: Long
    )

    private fun now() = System.nanoTime() / 1_000_000

    // cancel() moves this on. A press started before that may still be running (its socket was
    // just closed under it): it must not then open another connection or leave a pairing behind.
    private val generation = AtomicInteger(0)
    @Volatile private var pressGeneration = -1

    private fun cancelled() = pressGeneration != generation.get()

    private class CancelledPress : IOException("cancelled")

    private val CANCELLED = Outcome.Refused("The remote closed. Press Power again.")

    // Off from cancel() (the remote screen closing) until it opens again: a press already on its
    // way must not press power or start a pairing after the screen is gone.
    @Volatile private var accepting = true

    fun resume() {
        accepting = true
    }

    fun cancel() {
        accepting = false
        generation.incrementAndGet()
        dropPending()
        openSockets.toList().forEach { close(it) }
    }

    /** Blocking network work: call off the main thread. [host] is the TV's address. */
    fun sleep(host: String, code: String?): Outcome {
        if (!busy.compareAndSet(false, true)) return Outcome.Refused("Still working on the last press. Try again in a moment.")
        try {
            pressGeneration = generation.get()
            if (!accepting) return CANCELLED
            val cleaned = AtvRemoteProto.cleanCode(code)
            if (code?.isNotBlank() == true && cleaned == null) {
                return Outcome.Refused("The code is the 6 characters shown on the TV.")
            }
            if (cleaned != null) return finish(host, cleaned)
            return when (val attempt = sendPower(host)) {
                Attempt.Asleep -> Outcome.Asleep
                Attempt.AlreadyOff -> Outcome.AlreadyOff
                Attempt.Cancelled -> CANCELLED
                Attempt.Ignored -> Outcome.Refused("The Shield didn't turn off. Press Power again.")
                Attempt.NoService -> Outcome.Refused("Can't reach the Shield's remote service. Is the phone on the same Wi-Fi?")
                is Attempt.NeedsPairing -> begin(attempt.host)
            }
        } finally {
            busy.set(false)
        }
    }

    private fun finish(host: String, code: String): Outcome {
        val session = pending.get()
        // A code typed for another TV (the user switched TVs meanwhile) must not reach this one.
        if (session == null || session.host != host || now() - session.startedAt > CODE_TTL_MS) {
            dropPending()
            return Outcome.Refused("The code on the TV expired. Press Power again.")
        }
        val secret = AtvRemoteProto.secret(
            session.clientCert.modulus(), session.clientCert.exponent(),
            session.serverCert.modulus(), session.serverCert.exponent(), code
        )
        if (!AtvRemoteProto.codeMatches(secret, code)) {
            return Outcome.Refused("That code doesn't match the one on the TV.")
        }
        return try {
            writeFrame(session.socket, AtvRemoteProto.pairingSecret(secret))
            val reply = parseFrame(readFrame(session.socket))
            val status = AtvRemoteProto.num(reply, 2)
            dropPending()
            if (status != 200L || AtvRemoteProto.blob(reply, 41) == null) {
                Outcome.Refused("That code was refused. Press Power and use the new code on the TV.")
            } else {
                when (val attempt = sendPower(session.host)) {
                    Attempt.Asleep -> Outcome.Asleep
                    Attempt.AlreadyOff -> Outcome.AlreadyOff
                    Attempt.Cancelled -> CANCELLED
                    Attempt.Ignored -> Outcome.Refused("The Shield didn't turn off. Press Power again.")
                    Attempt.NoService, is Attempt.NeedsPairing ->
                        Outcome.Refused("The Shield didn't accept the power button. Press Power again.")
                }
            }
        } catch (e: Exception) {
            dropPending()
            if (cancelled()) return CANCELLED
            Log.w(TAG, "power code failed")
            Outcome.Refused("The code on the TV expired. Press Power again.")
        }
    }

    private fun begin(host: String): Outcome {
        val current = pending.get()
        if (current != null && current.host == host && now() - current.startedAt <= CODE_TTL_MS) return Outcome.NeedCode
        dropPending()
        val socket = try {
            open(host, PAIR_PORT)
        } catch (e: Exception) {
            if (cancelled()) return CANCELLED
            Log.w(TAG, "power pairing couldn't start")
            return Outcome.Refused("The Shield didn't show a code. Press Power again.")
        }
        return try {
            val serverCert = socket.session.peerCertificates.first() as X509Certificate
            val clientCert = clientCertificate()
            writeFrame(socket, AtvRemoteProto.pairingRequest(CLIENT_NAME))
            var ready = false
            repeat(6) {
                if (ready) return@repeat
                val fields = parseFrame(readFrame(socket))
                if (AtvRemoteProto.num(fields, 2) != 200L) throw IOException("status")
                when {
                    AtvRemoteProto.blob(fields, 11) != null -> writeFrame(socket, AtvRemoteProto.pairingOptions())
                    AtvRemoteProto.blob(fields, 20) != null -> writeFrame(socket, AtvRemoteProto.pairingConfiguration())
                    AtvRemoteProto.blob(fields, 31) != null -> ready = true
                    else -> throw IOException("pairing")
                }
            }
            if (!ready) throw IOException("pairing")
            val session = Pending(host, socket, serverCert, clientCert, now())
            pending.set(session)
            // A cancel that came in meanwhile wins: drop the session it could not see yet.
            if (cancelled()) {
                if (pending.compareAndSet(session, null)) close(socket)
                return CANCELLED
            }
            // Nobody may come back with the code: close the TV's pairing session when it expires.
            expiry.schedule({ if (pending.compareAndSet(session, null)) close(session.socket) }, CODE_TTL_MS, TimeUnit.MILLISECONDS)
            Outcome.NeedCode
        } catch (e: Exception) {
            close(socket)
            if (cancelled()) return CANCELLED
            Log.w(TAG, "power pairing failed")
            Outcome.Refused("The Shield didn't show a code. Press Power again.")
        }
    }

    private sealed class Attempt {
        object Asleep : Attempt()
        object AlreadyOff : Attempt()
        object NoService : Attempt()
        object Cancelled : Attempt()
        /** Power was sent but the Shield still reports its screen on. */
        object Ignored : Attempt()
        class NeedsPairing(val host: String) : Attempt()
    }

    private fun sendPower(host: String): Attempt {
        if (cancelled()) return Attempt.Cancelled
        var socket: SSLSocket? = null
        return try {
            socket = open(host, COMMAND_PORT)
            command(socket)
        } catch (_: NotPaired) {
            if (cancelled()) Attempt.Cancelled else Attempt.NeedsPairing(host)
        } catch (_: ConnectException) {
            if (cancelled()) Attempt.Cancelled else Attempt.NoService
        } catch (_: SocketTimeoutException) {
            if (cancelled()) Attempt.Cancelled else Attempt.NoService
        } catch (_: Exception) {
            // A socket closed by cancel() also looks connected: that is not "unpaired".
            if (cancelled()) return Attempt.Cancelled
            // The service accepted the connection, then dropped a client it has not paired.
            if (socket?.isConnected == true) Attempt.NeedsPairing(host) else Attempt.NoService
        } finally {
            close(socket)
        }
    }

    /**
     * The remote session: configure, set active, then wait for the service's "started" message
     * (its screen state). Power goes only to a screen that is on — it would wake one that is off.
     * The service ignores a key sent the moment it says it has started, so power waits a moment,
     * then this waits for the screen to report off (or the connection to drop as the box sleeps).
     * Throws when the service drops a client it hasn't paired.
     */
    private fun command(socket: SSLSocket): Attempt {
        var features = AtvRemoteProto.FEATURES
        var sentAt = 0L
        repeat(40) {
            if (sentAt != 0L) {
                // One deadline for the whole confirmation: a timed-out read may have consumed part
                // of a frame, so the stream is never read again after one.
                val left = POWER_CONFIRM_MS - (now() - sentAt)
                if (left <= 0) return Attempt.Ignored
                socket.soTimeout = left.toInt()
            }
            val fields = try {
                parseFrame(readFrame(socket))
            } catch (e: SocketTimeoutException) {
                if (sentAt == 0L) throw e
                return Attempt.Ignored
            } catch (e: EOFException) {
                // After power, the box going to sleep may end the connection.
                if (sentAt != 0L && !cancelled()) return Attempt.Asleep
                throw e
            } catch (e: SocketException) {
                if (sentAt != 0L && !cancelled()) return Attempt.Asleep
                throw e
            } catch (e: javax.net.ssl.SSLException) {
                // A reset under TLS surfaces as this rather than SocketException.
                if (sentAt != 0L && !cancelled()) return Attempt.Asleep
                throw e
            } catch (e: IOException) {
                // A garbled frame after power says nothing about the screen.
                if (sentAt != 0L && !cancelled()) return Attempt.Ignored
                throw e
            }
            if (sentAt != 0L) {
                // Power is out: only the screen state matters now, and nothing here may turn a
                // garbled message into a new pairing.
                val ping = AtvRemoteProto.blob(fields, 8)
                if (ping != null) {
                    // Unanswered, the service would hang up — which would look like sleeping.
                    val value = runCatching { AtvRemoteProto.num(parseFrame(ping), 1) }.getOrNull() ?: 0L
                    try {
                        writeFrame(socket, AtvRemoteProto.pingResponse(value))
                    } catch (_: IOException) {
                        return Attempt.Ignored
                    }
                    return@repeat
                }
                val started = AtvRemoteProto.blob(fields, 40) ?: return@repeat
                val state = try {
                    parseFrame(started)
                } catch (_: IOException) {
                    return Attempt.Ignored
                }
                if (AtvRemoteProto.num(state, 1) != 1L) return Attempt.Asleep
                return@repeat
            }
            when {
                AtvRemoteProto.blob(fields, 1) != null -> {
                    val offered = AtvRemoteProto.blob(fields, 1)?.let { AtvRemoteProto.num(parseFrame(it), 1) }?.toInt()
                    if (offered != null) features = offered and AtvRemoteProto.FEATURES
                    if (features == 0) features = AtvRemoteProto.FEATURES
                    writeFrame(socket, AtvRemoteProto.configureReply(features))
                }
                AtvRemoteProto.blob(fields, 2) != null -> writeFrame(socket, AtvRemoteProto.setActive(features))
                AtvRemoteProto.blob(fields, 8) != null -> {
                    val ping = AtvRemoteProto.blob(fields, 8)?.let { AtvRemoteProto.num(parseFrame(it), 1) } ?: 0L
                    writeFrame(socket, AtvRemoteProto.pingResponse(ping))
                }
                AtvRemoteProto.blob(fields, 40) != null -> {
                    // Absent means off.
                    val on = AtvRemoteProto.blob(fields, 40)?.let { AtvRemoteProto.num(parseFrame(it), 1) } == 1L
                    if (!on) {
                        return Attempt.AlreadyOff
                    } else {
                        Thread.sleep(POWER_SETTLE_MS)
                        if (cancelled()) return Attempt.Cancelled
                        writeFrame(socket, AtvRemoteProto.powerKey())
                        sentAt = now()
                    }
                }
                AtvRemoteProto.blob(fields, 3) != null -> throw IOException("rejected")
            }
        }
        if (sentAt != 0L) return Attempt.Ignored
        throw IOException("no session")
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun open(host: String, port: Int): SSLSocket {
        // The remote service's certificate is its own, generated on the Shield. There is no public
        // authority for it. Pairing then checks that this client and that certificate agree on the code.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf<KeyManager>(clientKeyManager()), arrayOf<TrustManager>(trust), null)
        if (cancelled()) throw CancelledPress()
        val socket = context.socketFactory.createSocket() as SSLSocket
        openSockets.add(socket)
        // cancel() may have run between the check above and the add: it never saw this socket.
        if (cancelled()) {
            close(socket)
            throw CancelledPress()
        }
        socket.useClientMode = true
        socket.soTimeout = 8_000
        try {
            socket.connect(InetSocketAddress(host, port), 2_000)
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            val reached = socket.isConnected
            close(socket)
            if (reached && e !is ConnectException && e !is SocketTimeoutException) throw NotPaired()
            throw e
        }
    }

    private class NotPaired : Exception()

    private fun clientKeyManager(): X509ExtendedKeyManager {
        val store = keyStore()
        return object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) =
                if (keyType == null || keyType.any { it.equals("RSA", true) }) ALIAS else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                val chain = store.getCertificateChain(ALIAS)
                if (!chain.isNullOrEmpty()) return chain.map { it as X509Certificate }.toTypedArray()
                val one = store.getCertificate(ALIAS) as? X509Certificate ?: return null
                return arrayOf(one)
            }
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(ALIAS)
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = null
            override fun getPrivateKey(alias: String?): PrivateKey? = store.getKey(ALIAS, null) as? PrivateKey
        }
    }

    private fun clientCertificate(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate

    private fun keyStore(): KeyStore {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return store
        // TLS signs with this key through raw RSA calls on a digest it computed itself: PKCS#1
        // padding, or no padding at all for RSA-PSS (it pads first). The key has to allow both and
        // DIGEST_NONE, or the keystore refuses, no client certificate is sent and the remote
        // service drops the connection.
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setRandomizedEncryptionRequired(false)
            .setCertificateSubject(X500Principal("CN=MKTV"))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000))
            .build()
        val generator = java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(spec)
        generator.generateKeyPair()
        return KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private fun writeFrame(socket: SSLSocket, payload: ByteArray) {
        socket.outputStream.write(AtvRemoteProto.frame(payload))
        socket.outputStream.flush()
    }

    private fun readFrame(socket: SSLSocket): ByteArray = readFrame(socket.inputStream)

    private fun readFrame(input: InputStream): ByteArray {
        // Checked as a Long: narrowing first would turn a huge declared length into a small one.
        val declared = readVarint(input)
        if (declared !in 1L..65_536L) throw IOException("bad frame")
        val len = declared.toInt()
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return buf
    }

    private fun readVarint(input: InputStream): Long = AtvRemoteProto.readVarint(input)

    private fun parseFrame(payload: ByteArray) = try {
        AtvRemoteProto.parse(payload)
    } catch (e: IllegalArgumentException) {
        throw IOException("message")
    }

    private fun dropPending() {
        close(pending.getAndSet(null)?.socket)
    }

    private fun close(socket: SSLSocket?) {
        if (socket == null) return
        openSockets.remove(socket)
        try { socket.close() } catch (_: Exception) {}
    }

    private fun X509Certificate.modulus(): BigInteger = (publicKey as java.security.interfaces.RSAPublicKey).modulus

    private fun X509Certificate.exponent(): BigInteger = (publicKey as java.security.interfaces.RSAPublicKey).publicExponent
}
