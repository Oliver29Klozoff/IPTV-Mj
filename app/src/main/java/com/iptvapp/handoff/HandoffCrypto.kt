package com.iptvapp.handoff

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The handoff envelope: standard primitives only (HKDF-SHA256, RFC 5869; AES-256-GCM), keyed by the
 * secret both devices worked out when they paired (RemoteControlServer.deriveToken — it never
 * crosses the network).
 *
 * Each direction has its own key (HKDF info names sender and receiver), so a message can't be
 * reflected back at its sender. The additional data binds the protocol label, the method, the path,
 * both device ids and the send time; a reply also binds the request's nonce, so an old reply can't
 * answer a new request. GCM gives confidentiality and integrity together.
 *
 * Pure JVM: no Android types, so it is unit-tested directly.
 */
object HandoffCrypto {
    const val LABEL = "MKTV-HO1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val SALT = "MKTV handoff v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    class Sealed(val nonceHex: String, val cipherHex: String)

    /** HKDF-SHA256 (extract then expand) for [length] ≤ 32 bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..32)
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(length)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** The pairing secret as key material: the hex token's bytes (any string works). */
    fun secretBytes(token: String): ByteArray =
        if (token.length % 2 == 0 && token.all { it in '0'..'9' || it in 'a'..'f' }) hexToBytes(token)
        else token.toByteArray(Charsets.UTF_8)

    /** The key for messages from [fromId] to [toId]. */
    fun directionKey(token: String, fromId: String, toId: String): ByteArray =
        hkdf(secretBytes(token), SALT, "key $fromId>$toId".toByteArray(Charsets.UTF_8))

    fun aad(method: String, path: String, fromId: String, toId: String, sentAtMs: Long, requestNonceHex: String?): ByteArray =
        listOf(LABEL, method, path, fromId, toId, sentAtMs.toString(), requestNonceHex.orEmpty())
            .joinToString("\n").toByteArray(Charsets.UTF_8)

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return Sealed(bytesToHex(nonce), bytesToHex(cipher.doFinal(plaintext)))
    }

    /** The plaintext, or null when the key, nonce, additional data or ciphertext don't match. */
    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray? = try {
        val nonce = hexToBytes(sealed.nonceHex)
        if (nonce.size != NONCE_BYTES) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(hexToBytes(sealed.cipherHex))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Which local provider this is, comparable only by the paired peer: HMAC under the pairing
     * secret of the provider's normalized address and account name. It reveals nothing else, and
     * differs for every pair of devices, so it can't be matched across pairings.
     */
    fun providerTag(token: String, providerIdentity: String): String =
        bytesToHex(hmac(secretBytes(token), "provider\n$providerIdentity".toByteArray(Charsets.UTF_8)))

    fun randomHex(bytes: Int): String = bytesToHex(ByteArray(bytes).also { random.nextBytes(it) })

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun bytesToHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private const val HEX = "0123456789abcdef"
}
