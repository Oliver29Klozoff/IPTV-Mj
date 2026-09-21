package com.iptvapp.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [CastBox] against the receiver's implementation. Every SEALED_* constant below was
 * produced by the MKTV TV app's own crypto (Tizen/webOS `js/crypto.js`) via
 * `node tools/gen-interop-fixtures.js` in that project — so these tests fail if either side
 * drifts, which is the only thing standing between a working cast and a silent decrypt failure
 * on a TV we can't attach a debugger to.
 *
 * The key here is a fixed, deliberately fake 00..1f and carries no real secret.
 */
class CastBoxTest {

    private fun fromHex(h: String) = ByteArray(h.length / 2) {
        ((Character.digit(h[it * 2], 16) shl 4) or Character.digit(h[it * 2 + 1], 16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val key = fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")

    // ─── primitives, so a divergence points at the exact step that broke ───

    @Test
    fun `subkey derivation matches receiver`() {
        val m = CastBox::class.java.getDeclaredMethod("hmac", ByteArray::class.java, ByteArray::class.java)
        m.isAccessible = true
        assertEquals(
            "3b6d09e1da933dbd19b6f2a6b2cf2cae111c1b7e35b723c2aab9f9039df1219a",
            hex(m.invoke(CastBox, key, "mktv-cast-enc-v1".toByteArray()) as ByteArray)
        )
        assertEquals(
            "9b5341766e6c296f367611fbb81fc6c07bbe795dfab7af5d02f81eb8d6d29d1f",
            hex(m.invoke(CastBox, key, "mktv-cast-mac-v1".toByteArray()) as ByteArray)
        )
    }

    @Test
    fun `base64url matches receiver`() {
        assertEquals("AAECAwQFBgcICQoLDA0ODw", CastBox.b64uEncode(fromHex("000102030405060708090a0b0c0d0e0f")))
        assertEquals("____", CastBox.b64uEncode(byteArrayOf(-1, -1, -1)))
        assertEquals("_w", CastBox.b64uEncode(byteArrayOf(-1)))
        // no padding, and URL-safe alphabet only
        assertTrue(CastBox.b64uEncode(ByteArray(10) { -1 }).none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun `base64url round trips every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, CastBox.b64uDecode(CastBox.b64uEncode(all)))
        for (n in 0..39) {
            val b = ByteArray(n) { ((it * 37 + n * 11) and 0xff).toByte() }
            assertArrayEquals(b, CastBox.b64uDecode(CastBox.b64uEncode(b)))
        }
    }

    // ─── sealed boxes produced by the RECEIVER must open here ───

    @Test
    fun `opens a box sealed by the receiver`() {
        val sealed = "CW8rmaYRlZLW7Teo2mTtGQ.8QuT5RT_5mvn21HA-4u0pFqhCyt1wln811khKnLPQnsBxUl3wTArUp0i" +
            "nbLjyqWimMHejrw6_RCErKW0kjDDO5dm2OKDi9wFudkiI0Tveas.A9-m7ur9jgvzBLjLvtBteI2FERiysdcTXMtZmOIOPF8"
        assertEquals(
            """{"url":"http://line.example.xyz/live/user/pass/12345.ts","title":"US| HBO 2 HD"}""",
            CastBox.open(key, sealed)
        )
    }

    @Test
    fun `opens a multi-keystream-block box from the receiver`() {
        // Longer than 32 bytes, so this fails if the keystream counter or its big-endian
        // encoding disagrees between the two implementations.
        val sealed = "u4qfkPrFfGRNdNZDCEOk8w.qDyDKXwYz_TQjbLMpFMIeHFDLUpv97BjuMjVfdu11SJoZ21IcaTkurRoHFGU" +
            "fIJkYELFuS5n8NlAc4wwuboG0fGZokRyQmGMW7v8rZGPrYwpXb3srEdR__9qAktiqfsqlrHHpDCdBC15He4PanAQ1NO5XB" +
            "u0DJKiYh-0v0LZoe3RfgqLPNyOD_BRuHn5HCHGRd1MqAQQSCWG7b0tZxRnPIJ6APjN3QLf5m-Nsau5ZH-dfoi9VVX6OpSH" +
            "uJVZEA.dWe3zkL5DTES065DHOX0WWN-sLVNVSjGaD0875-qjTY"
        val opened = CastBox.open(key, sealed)
        assertTrue(opened!!.contains("segment/segment/"))
        assertTrue(opened.endsWith("""9.ts","title":"Long"}"""))
    }

    @Test
    fun `opens a unicode box from the receiver`() {
        val sealed = "S54MMFLAqeWsyNoYNHbrTw.47TYKEVmfRpDLCoQsQ92to8C5uN8PBi1r4u7qovOKB-cFrQfXFhOVuiXFXvY4oJ5pRbc87iG" +
            ".9IUnLdnIj_7LNt3Fb18JLbkmZfXMDxXKf4_LdUJos7o"
        assertEquals("""{"url":"http://h/1.ts","title":"café 日本語 😀"}""", CastBox.open(key, sealed))
    }

    // ─── our own seal must be well-formed and self-consistent ───

    @Test
    fun `seal then open round trips`() {
        val msg = """{"url":"http://host/live/u/p/7.ts","title":"Test"}"""
        assertEquals(msg, CastBox.open(key, CastBox.seal(key, msg)))
    }

    @Test
    fun `seal hides the plaintext`() {
        val sealed = CastBox.seal(key, """{"url":"http://line.example.xyz/live/user/pass/1.ts"}""")
        assertTrue(!sealed.contains("line.example"))
        assertTrue(!sealed.contains("pass"))
    }

    @Test
    fun `nonce differs between seals`() {
        val msg = "same message"
        assertNotEquals(CastBox.seal(key, msg), CastBox.seal(key, msg))
    }

    @Test
    fun `wrong key is rejected rather than returning garbage`() {
        val sealed = CastBox.seal(key, "secret")
        val other = ByteArray(32) { 0x7f }
        assertNull(CastBox.open(other, sealed))
    }

    @Test
    fun `any single character tamper is rejected`() {
        val sealed = CastBox.seal(key, """{"url":"http://h/1.ts","title":"T"}""")

        // The final base64url character of each part carries bits that fall outside the encoded
        // byte count, so some single-character edits decode to the very same bytes — a
        // non-canonical spelling of the identical message, not a forgery. Those are skipped:
        // they change nothing an attacker could exploit. Every edit that genuinely alters the
        // decoded nonce, ciphertext or tag must be caught.
        fun decoded(s: String): List<List<Byte>>? {
            val p = s.split(".")
            if (p.size != 3) return null
            return p.map { CastBox.b64uDecode(it).toList() }
        }
        val original = decoded(sealed)

        var accepted = 0
        var meaningful = 0
        for (i in sealed.indices) {
            if (sealed[i] == '.') continue
            val mutated = sealed.substring(0, i) + (if (sealed[i] == 'A') 'B' else 'A') + sealed.substring(i + 1)
            if (decoded(mutated) == original) continue     // same bytes, different spelling
            meaningful++
            if (CastBox.open(key, mutated) != null) accepted++
        }
        assertEquals(0, accepted)
        // Guard against the skip clause silently swallowing the whole test.
        assertTrue("expected most mutations to be meaningful, got $meaningful", meaningful > sealed.length - 10)
    }

    @Test
    fun `malformed input is rejected`() {
        assertNull(CastBox.open(key, ""))
        assertNull(CastBox.open(key, "nonsense"))
        assertNull(CastBox.open(key, "a.b"))
        assertNull(CastBox.open(key, "a.b.c.d"))
        // right shape, wrong nonce/tag lengths
        assertNull(CastBox.open(key, "AAAA.AAAA.AAAA"))
    }

    // ─── manual pairing by typed phrase ───
    //
    // Same rule as above: every expected value came from the TV app's own implementation via
    // `node tools/gen-interop-fixtures.js`.

    @Test
    fun `phrase normalization matches receiver`() {
        assertEquals("wobbly-pickle-ninja-toast", CastBox.normalizePhrase("Wobbly Pickle Ninja Toast"))
        assertEquals("wobbly-pickle-ninja-toast", CastBox.normalizePhrase("  WOBBLY--pickle  NINJA_toast "))
        assertEquals("wobbly-pickle-ninja-toast", CastBox.normalizePhrase("wobbly-pickle-ninja-toast"))
        assertEquals("", CastBox.normalizePhrase("   "))
        assertEquals("", CastBox.normalizePhrase("---"))
    }

    @Test
    fun `pbkdf2 matches receiver`() {
        val ph = "wobbly-pickle-ninja-toast"
        assertEquals("921bdc2dca798ccb03aa5bd84f37ca00921751b3f2baabd14dae5980361d9cc1",
            hex(CastBox.pbkdf2(ph, "salt", 1)))
        // Two iterations pins the XOR-accumulate step, where an off-by-one hides.
        assertEquals("176e119ec159c56344dde6961eb1fb4f9677e922362d121500d01f37ddb4313a",
            hex(CastBox.pbkdf2(ph, "salt", 2)))
    }

    @Test
    fun `session id and key derived from phrase match receiver`() {
        val ph = "wobbly-pickle-ninja-toast"
        assertEquals("r8ntzp3z", CastBox.idFromPhrase(ph))
        assertEquals("e3120018ab16614dab45aed6f8f338ea46e8ae81d9b0e8bb4d1038ac9ad9946a",
            hex(CastBox.keyFromPhrase(ph)))
        // Whatever the user types, however spaced or cased, must land on the same session.
        assertEquals("r8ntzp3z", CastBox.idFromPhrase("Wobbly Pickle Ninja Toast"))
        assertEquals("r8ntzp3z", CastBox.idFromPhrase("  WOBBLY--pickle  NINJA_toast "))
    }

    @Test
    fun `opens a phrase-sealed box from the receiver`() {
        val sealed = "P4m6l7BVlkOA9Pvgm4UH7Q.6X_JHF8-XGSor71vlZCppFqlnGq-oqubkpwUeneX1kV1ICtSJKdwWbA_31QLEylV" +
            "zSgea1ouJIWWfu3VNDUPviez-YD_eLhclpStt60.UZ8e5OsaG8TzwcxMoOXVPaONkcu2alBL2JHgqHZN1jo"
        assertEquals(
            """{"url":"http://line.example.xyz/live/user/pass/777.ts","title":"Manual Pair"}""",
            CastBox.open(CastBox.keyFromPhrase("wobbly-pickle-ninja-toast"), sealed)
        )
    }

    @Test
    fun `a one-word typo does not derive a different session`() {
        // Getting a word wrong must fail to open, not quietly pair with something else.
        val right = CastBox.keyFromPhrase("wobbly-pickle-ninja-toast")
        val wrong = CastBox.keyFromPhrase("wobbly-pickle-ninja-waffle")
        val sealed = CastBox.seal(right, """{"url":"http://h/1.ts"}""")
        assertNull(CastBox.open(wrong, sealed))
        assertNotEquals(CastBox.idFromPhrase("wobbly-pickle-ninja-toast"),
            CastBox.idFromPhrase("wobbly-pickle-ninja-waffle"))
    }

    @Test
    fun `manual payload round trips through parseScanned`() {
        val t = CastBox.parseScanned(CastBox.manualPayload("Wobbly Pickle Ninja Toast"))
        assertEquals("r8ntzp3z", t.code)
        assertArrayEquals(CastBox.keyFromPhrase("wobbly-pickle-ninja-toast"), t.key)
    }

    @Test
    fun `manual payload accepts whatever spacing the user typed`() {
        val a = CastBox.parseScanned("phrase:WOBBLY  pickle -- Ninja_toast")
        assertEquals("r8ntzp3z", a.code)
        assertArrayEquals(CastBox.keyFromPhrase("wobbly-pickle-ninja-toast"), a.key)
    }

    @Test
    fun `an empty or single-word phrase is refused, not keyed`() {
        // Otherwise a stray tap would seal a box addressed to a session nobody is watching.
        assertNull(CastBox.parseScanned("phrase:").key)
        assertNull(CastBox.parseScanned("phrase:   ").key)
        assertNull(CastBox.parseScanned("phrase:wobbly").key)
    }

    // ─── QR payload parsing ───

    @Test
    fun `parses a scanned code with a key`() {
        val scanned = "abc12345." + CastBox.b64uEncode(key)
        val t = CastBox.parseScanned(scanned)
        assertEquals("abc12345", t.code)
        assertArrayEquals(key, t.key)
    }

    @Test
    fun `parses a bare code as keyless`() {
        val t = CastBox.parseScanned("ABC12345")
        assertEquals("abc12345", t.code)   // normalized to lowercase, as Firestore ids are
        assertNull(t.key)
    }

    @Test
    fun `treats a wrong-length key as keyless rather than using it`() {
        val t = CastBox.parseScanned("abc12345." + CastBox.b64uEncode(ByteArray(16)))
        assertEquals("abc12345", t.code)
        assertNull(t.key)
    }

    @Test
    fun `tolerates surrounding whitespace from the scanner`() {
        val t = CastBox.parseScanned("  abc12345." + CastBox.b64uEncode(key) + "  ")
        assertEquals("abc12345", t.code)
        assertArrayEquals(key, t.key)
    }
}
