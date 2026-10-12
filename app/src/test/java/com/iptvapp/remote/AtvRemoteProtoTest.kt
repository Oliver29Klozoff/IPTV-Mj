package com.iptvapp.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** The power button's pairing check and the few messages it sends. */
class AtvRemoteProtoTest {

    @Test fun pairingSecretMatchesTheKnownHashAndTheCodePrefix() {
        val n = BigInteger("10001", 16)
        val secret = AtvRemoteProto.secret(n, n, n, n, "2B1234")
        assertEquals("2bcd3b4204c5b690d158a4130c550e944e3b99d0630509fbe8629d44ddd5f632", secret.toHex())
        assertTrue(AtvRemoteProto.codeMatches(secret, "2B1234"))
        assertFalse(AtvRemoteProto.codeMatches(secret, "AB1234"))
    }

    @Test fun modulusHasNoSignByteAndTheExponentKeepsItsLeadingNibble() {
        assertEquals("010001", AtvRemoteProto.magnitude(BigInteger("10001", 16)).toHex())
        assertEquals("010001", AtvRemoteProto.exponent(BigInteger.valueOf(65537)).toHex())
    }

    @Test fun codesAreSixHexCharacters() {
        assertEquals("AB1234", AtvRemoteProto.cleanCode(" ab 1234 "))
        assertNull(AtvRemoteProto.cleanCode("12345"))
        assertNull(AtvRemoteProto.cleanCode("ZZZZZZ"))
        assertNull(AtvRemoteProto.cleanCode(null))
    }

    @Test fun aFramedPowerKeyParsesBackToPower() {
        val payload = AtvRemoteProto.powerKey()
        val framed = AtvRemoteProto.frame(payload)
        assertTrue(payload.size < 128)
        assertEquals(payload.size, framed[0].toInt() and 0xFF)
        assertEquals(payload.size, framed.size - 1)
        val key = AtvRemoteProto.blob(AtvRemoteProto.parse(payload), 10)!!
        val fields = AtvRemoteProto.parse(key)
        assertEquals(AtvRemoteProto.KEYCODE_POWER.toLong(), AtvRemoteProto.num(fields, 1))
        assertEquals(AtvRemoteProto.DIRECTION_SHORT.toLong(), AtvRemoteProto.num(fields, 2))
    }

    @Test fun pairingRequestCarriesTheNameAndAnOkStatus() {
        val fields = AtvRemoteProto.parse(AtvRemoteProto.pairingRequest("MKTV"))
        assertEquals(2L, AtvRemoteProto.num(fields, 1))
        assertEquals(200L, AtvRemoteProto.num(fields, 2))
        val request = AtvRemoteProto.parse(AtvRemoteProto.blob(fields, 10)!!)
        assertEquals("atvremote", AtvRemoteProto.blob(request, 1)!!.toString(Charsets.UTF_8))
        assertEquals("MKTV", AtvRemoteProto.blob(request, 2)!!.toString(Charsets.UTF_8))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
