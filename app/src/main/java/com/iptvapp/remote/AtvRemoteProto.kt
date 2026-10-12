package com.iptvapp.remote

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The few Android TV remote messages Power needs, written by hand so the app does not take a
 * protobuf dependency for one button. Lengths on the wire are varints, same as the protocol.
 */
internal object AtvRemoteProto {

    const val KEYCODE_POWER = 26
    const val DIRECTION_SHORT = 3
    /** Ping, key inject, and power — the only features this button asks the TV for. */
    const val FEATURES = (1 shl 0) or (1 shl 1) or (1 shl 5)

    fun frame(payload: ByteArray): ByteArray {
        val prefix = Buf().apply { varint(payload.size.toLong()) }.toByteArray()
        return prefix + payload
    }

    fun pairingRequest(clientName: String): ByteArray = outer(10) {
        str(1, "atvremote")
        str(2, clientName)
    }

    fun pairingOptions(): ByteArray = outer(20) {
        nested(1) { int(1, 3); int(2, 6) } // hexadecimal, 6 symbols
        int(3, 1) // input role
    }

    fun pairingConfiguration(): ByteArray = outer(30) {
        nested(1) { int(1, 3); int(2, 6) }
        int(2, 1)
    }

    fun pairingSecret(secret: ByteArray): ByteArray = outer(40) { bytes(1, secret) }

    fun configureReply(features: Int): ByteArray = message(1) {
        int(1, features)
        nested(2) {
            int(3, 1)
            str(4, "1")
            str(5, "atvremote")
            str(6, "1.0.0")
        }
    }

    fun setActive(features: Int): ByteArray = message(2) { int(1, features) }

    fun pingResponse(value: Long): ByteArray = message(9) { int(1, value.toInt()) }

    fun powerKey(): ByteArray = message(10) {
        int(1, KEYCODE_POWER)
        int(2, DIRECTION_SHORT)
    }

    /**
     * The check the TV displays. [code] is the 6 hex characters on screen. The hash is sent back
     * only when its first byte is those first two characters — a mistyped code never reaches the TV.
     */
    fun secret(clientModulus: BigInteger, clientExponent: BigInteger, serverModulus: BigInteger, serverExponent: BigInteger, code: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(magnitude(clientModulus))
        digest.update(exponent(clientExponent))
        digest.update(magnitude(serverModulus))
        digest.update(exponent(serverExponent))
        digest.update(hexBytes(code.substring(2)))
        return digest.digest()
    }

    fun codeMatches(secret: ByteArray, code: String): Boolean =
        secret.isNotEmpty() && (secret[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    /** Hex, 6 characters, the shape the TV shows. */
    fun cleanCode(raw: String?): String? {
        val code = raw?.trim()?.replace(" ", "")?.uppercase() ?: return null
        if (code.isEmpty()) return null
        return code.takeIf { it.length == 6 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
    }

    fun parse(payload: ByteArray): Map<Int, List<Part>> {
        val out = linkedMapOf<Int, MutableList<Part>>()
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (i < payload.size && shift <= 63) {
                val b = payload[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("truncated")
        }
        while (i < payload.size) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            val values = out.getOrPut(field) { mutableListOf() }
            when ((tag and 7).toInt()) {
                0 -> values.add(Part.Num(varint()))
                2 -> {
                    // Compared as a Long against what is left, before narrowing: no overflow either way.
                    val declared = varint()
                    if (declared < 0 || declared > (payload.size - i).toLong()) throw IllegalArgumentException("length")
                    val len = declared.toInt()
                    values.add(Part.Blob(payload.copyOfRange(i, i + len)))
                    i += len
                }
                5 -> { if (payload.size - i < 4) throw IllegalArgumentException("truncated"); i += 4 }
                1 -> { if (payload.size - i < 8) throw IllegalArgumentException("truncated"); i += 8 }
                else -> throw IllegalArgumentException("wire")
            }
        }
        return out
    }

    fun num(fields: Map<Int, List<Part>>, field: Int): Long? = (fields[field]?.firstOrNull() as? Part.Num)?.value

    fun blob(fields: Map<Int, List<Part>>, field: Int): ByteArray? = (fields[field]?.firstOrNull() as? Part.Blob)?.bytes

    /** Integer as the bytes the pairing check hashes: no sign byte, even number of hex digits. */
    fun magnitude(value: BigInteger): ByteArray = hexBytes(value.toString(16))

    /** Public exponent, with the leading nibble the protocol always adds. */
    fun exponent(value: BigInteger): ByteArray = hexBytes("0" + value.toString(16))

    private fun hexBytes(hex: String): ByteArray {
        val even = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(even.length / 2) { i -> even.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun outer(field: Int, body: Buf.() -> Unit): ByteArray = Buf().apply {
        int(1, 2) // protocol version the TV's pairing service expects
        int(2, 200)
        nested(field, body)
    }.toByteArray()

    private fun message(field: Int, body: Buf.() -> Unit): ByteArray = Buf().apply { nested(field, body) }.toByteArray()

    sealed class Part {
        class Num(val value: Long) : Part()
        class Blob(val bytes: ByteArray) : Part()
    }

    class Buf {
        private val out = ByteArrayOutputStream()

        fun varint(value: Long) {
            var rest = value
            while (true) {
                val bits = (rest and 0x7F).toInt()
                rest = rest ushr 7
                if (rest == 0L) {
                    out.write(bits)
                    return
                }
                out.write(bits or 0x80)
            }
        }

        fun int(field: Int, value: Int) {
            varint(((field shl 3) or 0).toLong())
            varint(value.toLong() and 0xFFFF_FFFFL)
        }

        fun bytes(field: Int, data: ByteArray) {
            varint(((field shl 3) or 2).toLong())
            varint(data.size.toLong())
            out.write(data)
        }

        fun str(field: Int, text: String) = bytes(field, text.toByteArray(Charsets.UTF_8))

        fun nested(field: Int, body: Buf.() -> Unit) = bytes(field, Buf().apply(body).toByteArray())

        fun toByteArray() = out.toByteArray()
    }
}
