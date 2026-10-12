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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Sleeps the Shield the way its own power button does.
 *
 * An app cannot inject that key itself. The Android TV remote service, which is already on the
 * Shield and is what the Google TV phone app talks to, can. The first press shows a code on the
 * TV; after that code is entered on the phone, later presses just send power. A short press sleeps
 * the box. The TV turns off with it when the Shield's HDMI power control is on.
 */
internal object AtvRemote {

    private const val TAG = "RemoteControl"
    // "_tls": a key made before DIGEST_NONE was allowed (see keyStore) can't sign the handshake.
    private const val ALIAS = "mktv_atv_remote_tls"
    private const val COMMAND_PORT = 6466
    private const val PAIR_PORT = 6467
    private const val CLIENT_NAME = "MKTV"
    private const val CODE_TTL_MS = 120_000L

    sealed class Outcome {
        /** The power key was sent. The Shield sleeps; the connection usually drops as it does. */
        object Asleep : Outcome()
        /** The remote service says the screen is already off, so power was not sent (it would wake it). */
        object AlreadyOff : Outcome()
        /** The TV is showing a 6-character code. Send it back with the next press. */
        object NeedCode : Outcome()
        class Refused(val reason: String) : Outcome()
    }

    private val gate = Any()
    private var pending: Pending? = null

    private class Pending(val socket: SSLSocket, val serverCert: X509Certificate, val clientCert: X509Certificate, val startedAt: Long)

    fun cancel() = synchronized(gate) { dropPending() }

    fun sleep(code: String?): Outcome = synchronized(gate) {
        val cleaned = AtvRemoteProto.cleanCode(code)
        if (code?.isNotBlank() == true && cleaned == null) {
            return@synchronized Outcome.Refused("The code is the 6 characters shown on the TV.")
        }
        if (cleaned != null) return@synchronized finish(cleaned)
        when (val attempt = sendPower()) {
            Attempt.Asleep -> Outcome.Asleep
            Attempt.AlreadyOff -> Outcome.AlreadyOff
            Attempt.NoService -> Outcome.Refused("This device has no Android TV remote service, so MKTV can't turn it off.")
            is Attempt.NeedsPairing -> begin(attempt.host)
        }
    }

    private fun finish(code: String): Outcome {
        val session = pending
        if (session == null || System.currentTimeMillis() - session.startedAt > CODE_TTL_MS) {
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
                when (val attempt = sendPower()) {
                    Attempt.Asleep -> Outcome.Asleep
                    Attempt.AlreadyOff -> Outcome.AlreadyOff
                    Attempt.NoService, is Attempt.NeedsPairing ->
                        Outcome.Refused("The Shield didn't accept the power button. Press Power again.")
                }
            }
        } catch (e: Exception) {
            dropPending()
            Log.w(TAG, "power code failed")
            Outcome.Refused("The code on the TV expired. Press Power again.")
        }
    }

    private fun begin(host: String): Outcome {
        val current = pending
        if (current != null && System.currentTimeMillis() - current.startedAt <= CODE_TTL_MS) return Outcome.NeedCode
        dropPending()
        val socket = try {
            open(host, PAIR_PORT)
        } catch (e: Exception) {
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
            pending = Pending(socket, serverCert, clientCert, System.currentTimeMillis())
            Outcome.NeedCode
        } catch (e: Exception) {
            close(socket)
            Log.w(TAG, "power pairing failed")
            Outcome.Refused("The Shield didn't show a code. Press Power again.")
        }
    }

    private sealed class Attempt {
        object Asleep : Attempt()
        object AlreadyOff : Attempt()
        object NoService : Attempt()
        class NeedsPairing(val host: String) : Attempt()
    }

    private fun sendPower(): Attempt {
        var sawService = false
        for (host in hosts()) {
            var socket: SSLSocket? = null
            try {
                socket = open(host, COMMAND_PORT)
                sawService = true
                return command(socket)
            } catch (_: NotPaired) {
                return Attempt.NeedsPairing(host)
            } catch (_: ConnectException) {
                // Nothing is listening here. Try the next address.
            } catch (_: SocketTimeoutException) {
                // This address didn't answer. Try the next one.
            } catch (_: Exception) {
                // The service accepted the connection, then dropped a client it has not paired.
                if (socket?.isConnected == true) return Attempt.NeedsPairing(host)
                Log.w(TAG, "power couldn't connect")
            } finally {
                close(socket)
            }
        }
        return if (sawService) Attempt.NeedsPairing(hosts().first()) else Attempt.NoService
    }

    /** @return asleep, or already off. Throws when the service drops a client it hasn't paired. */
    private fun command(socket: SSLSocket): Attempt {
        var features = AtvRemoteProto.FEATURES
        repeat(20) {
            val fields = parseFrame(readFrame(socket))
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
                    // Absent means off. Power would wake a sleeping Shield, so it is only sent while the screen is on.
                    val on = AtvRemoteProto.blob(fields, 40)?.let { AtvRemoteProto.num(parseFrame(it), 1) } == 1L
                    if (!on) return Attempt.AlreadyOff
                    writeFrame(socket, AtvRemoteProto.powerKey())
                    socket.outputStream.flush()
                    return Attempt.Asleep
                }
                AtvRemoteProto.blob(fields, 3) != null -> throw IOException("rejected")
            }
        }
        throw IOException("no session")
    }

    private fun hosts(): List<String> {
        val found = mutableListOf("127.0.0.1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    val ipv4 = addr as? Inet4Address ?: continue
                    val host = ipv4.hostAddress ?: continue
                    if (!ipv4.isLoopbackAddress && !host.startsWith("169.254.")) found.add(host)
                }
            }
        } catch (_: Exception) {
        }
        return found.distinct()
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
        val socket = context.socketFactory.createSocket() as SSLSocket
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
        val len = readVarint(input).toInt()
        if (len !in 1..65_536) throw IOException("bad frame")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return buf
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift <= 63) {
            val b = input.read()
            if (b < 0) throw EOFException()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("varint")
    }

    private fun parseFrame(payload: ByteArray) = try {
        AtvRemoteProto.parse(payload)
    } catch (e: IllegalArgumentException) {
        throw IOException("message")
    }

    private fun dropPending() {
        close(pending?.socket)
        pending = null
    }

    private fun close(socket: SSLSocket?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun X509Certificate.modulus(): BigInteger = (publicKey as java.security.interfaces.RSAPublicKey).modulus

    private fun X509Certificate.exponent(): BigInteger = (publicKey as java.security.interfaces.RSAPublicKey).publicExponent
}
