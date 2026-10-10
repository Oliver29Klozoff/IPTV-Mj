package com.iptvapp.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLinkTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private val serverA = "http://provider-a.example:8080"
    private val serverB = "http://provider-b.example"
    private val user = "alice"
    private val password = "s3cretPass"

    private fun xtreamLink(server: String, username: String, streamId: Int, k: ByteArray = key): String =
        LauncherLink.build(
            streamId,
            LauncherLink.token(k, LauncherLink.sourceIdentity(server, username, streamId, null))
        )

    private fun current(link: String): LauncherLink.Parsed.Current =
        LauncherLink.parse(LauncherLink.ACTION_VIEW, link) as LauncherLink.Parsed.Current

    // 1. Xtream favorite with no stored streamUrl resolves from the signed-in provider.
    @Test fun xtreamFavoriteWithNullStreamUrlResolves() {
        val link = current(xtreamLink(serverA, user, 123))
        assertEquals(123, link.streamId)
        assertTrue(LauncherLink.verify(key, link, 123, null, serverA, user))
    }

    // 2. M3U / direct-URL favorite still resolves.
    @Test fun directUrlFavoriteResolves() {
        val direct = "http://cdn.example/live/$user/$password/77.ts"
        val link = current(
            LauncherLink.build(77, LauncherLink.token(key, LauncherLink.sourceIdentity("", "", 77, direct)))
        )
        assertTrue(LauncherLink.verify(key, link, 77, direct, "", ""))
        // Same id, different playlist entry: not the same channel.
        assertFalse(LauncherLink.verify(key, link, 77, "http://cdn.example/other.ts", "", ""))
    }

    // 3. Same stream id on two providers: a card for A never plays B's channel.
    @Test fun sameStreamIdOnTwoProvidersDoesNotCrossResolve() {
        val forA = current(xtreamLink(serverA, user, 123))
        val forB = current(xtreamLink(serverB, user, 123))
        assertTrue(LauncherLink.verify(key, forA, 123, null, serverA, user))
        assertFalse(LauncherLink.verify(key, forA, 123, null, serverB, user))
        assertTrue(LauncherLink.verify(key, forB, 123, null, serverB, user))
        assertFalse(LauncherLink.verify(key, forB, 123, null, serverA, user))
    }

    // 4 / 11. Provider removed or switched (another server signed in, or nobody): rejected.
    @Test fun removedOrSwitchedProviderIsRejected() {
        val link = current(xtreamLink(serverA, user, 5))
        assertFalse(LauncherLink.verify(key, link, 5, null, serverB, "bob"))
        assertFalse(LauncherLink.verify(key, link, 5, null, "", ""))
    }

    // Other login on the same server is another account.
    @Test fun differentUsernameIsRejected() {
        val link = current(xtreamLink(serverA, user, 5))
        assertFalse(LauncherLink.verify(key, link, 5, null, serverA, "bob"))
    }

    // 5. Channel deleted from the catalog: rejected.
    @Test fun removedChannelIsRejected() {
        val link = current(xtreamLink(serverA, user, 9))
        assertFalse(LauncherLink.verify(key, link, null, null, serverA, user))
        assertFalse(LauncherLink.verify(key, link, 10, null, serverA, user))
    }

    // 6. Password change: the identity has no password in it, so the card still resolves and
    // playback builds the URL from the new one.
    @Test fun passwordIsNotPartOfTheIdentity() {
        val identity = LauncherLink.sourceIdentity(serverA, user, 1, null)
        assertFalse(identity.contains(password))
        val link = current(xtreamLink(serverA, user, 1))
        assertTrue(LauncherLink.verify(key, link, 1, null, serverA, user))
    }

    @Test fun serverUrlFormattingDoesNotMatter() {
        val link = current(xtreamLink("$serverA/", user, 3))
        assertTrue(LauncherLink.verify(key, link, 3, null, " HTTP://Provider-A.example:8080 ", user))
    }

    // 7 / 8. The link carries no login, server or stream URL.
    @Test fun linkHasNoCredentialsOrUrls() {
        val direct = "http://cdn.example/live/$user/$password/77.ts"
        val links = listOf(
            xtreamLink(serverA, user, 123),
            LauncherLink.build(77, LauncherLink.token(key, LauncherLink.sourceIdentity(serverA, user, 77, direct)))
        )
        for (link in links) {
            assertFalse(link.contains(user))
            assertFalse(link.contains(password))
            assertFalse(link.contains("provider-a"))
            assertFalse(link.contains("cdn.example"))
            assertTrue(link.matches(Regex("^mktv://play/v2/\\d+/[0-9a-f]{32}$")))
        }
    }

    // 9. Raw URLs, queries, paths and other schemes are not links.
    @Test fun rawUrlInjectionIsRejected() {
        val tok = "0".repeat(32)
        val bad = listOf(
            "mktv://play?url=http://evil.example/x.m3u8",
            "mktv://play/v2/1/$tok?url=http://evil.example/x.m3u8",
            "mktv://play/v2/1/$tok#http://evil.example",
            "mktv://play/v2/1/$tok/extra",
            "mktv://play/http://evil.example/x.m3u8",
            "mktv://play/v2/1/file:///sdcard/x",
            "http://evil.example/mktv://play/v2/1/$tok",
            "mktv://tune/1",
            "mktv://play/../v2/1/$tok",
            ""
        )
        for (s in bad) {
            assertTrue(s, LauncherLink.parse(LauncherLink.ACTION_VIEW, s) is LauncherLink.Parsed.Invalid)
        }
    }

    // 10. Bad ids and tokens.
    @Test fun invalidIdentifierIsRejected() {
        val tok = "a".repeat(32)
        val bad = listOf(
            "mktv://play/v2/-1/$tok",
            "mktv://play/v2/99999999999/$tok",   // more than 10 digits
            "mktv://play/v2/4294967296/$tok",    // past Int range
            "mktv://play/v2/abc/$tok",
            "mktv://play/v2/1/${"A".repeat(32)}", // upper-case hex isn't ours
            "mktv://play/v2/1/${"a".repeat(31)}",
            "mktv://play/v2/1/${"g".repeat(32)}",
            "mktv://play/v2//$tok"
        )
        for (s in bad) {
            assertTrue(s, LauncherLink.parse(LauncherLink.ACTION_VIEW, s) is LauncherLink.Parsed.Invalid)
        }
        assertTrue(LauncherLink.parse(null, "mktv://play/v2/1/$tok") is LauncherLink.Parsed.Invalid)
        assertTrue(LauncherLink.parse("android.intent.action.SEND", "mktv://play/v2/1/$tok") is LauncherLink.Parsed.Invalid)
        assertTrue(LauncherLink.parse(LauncherLink.ACTION_VIEW, null) is LauncherLink.Parsed.Invalid)
    }

    // A token made with another key (another install, or a guessed one) doesn't resolve.
    @Test fun forgedOrTamperedTokenIsRejected() {
        val forged = current(xtreamLink(serverA, user, 123, otherKey))
        assertFalse(LauncherLink.verify(key, forged, 123, null, serverA, user))

        val real = current(xtreamLink(serverA, user, 123))
        val flipped = real.token.first().let { if (it == '0') '1' else '0' } + real.token.drop(1)
        assertFalse(LauncherLink.verify(key, real.copy(token = flipped), 123, null, serverA, user))
        // A valid token moved onto another channel id.
        assertFalse(LauncherLink.verify(key, real.copy(streamId = 124), 124, null, serverA, user))
    }

    // 14. v7.23-and-older cards are recognised, but as Legacy — never played.
    @Test fun legacyLinkIsRecognisedSeparately() {
        assertEquals(LauncherLink.Parsed.Legacy(42), LauncherLink.parse(LauncherLink.ACTION_VIEW, "mktv://play/42"))
        assertTrue(LauncherLink.parse(LauncherLink.ACTION_VIEW, "mktv://play/42?x=1") is LauncherLink.Parsed.Invalid)
    }

    // 12. Building is deterministic: republishing gives the same link, not a second one.
    @Test fun republishingGivesTheSameLink() {
        assertEquals(xtreamLink(serverA, user, 8), xtreamLink(serverA, user, 8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildRejectsMalformedToken() {
        LauncherLink.build(1, "not-a-token")
    }
}
