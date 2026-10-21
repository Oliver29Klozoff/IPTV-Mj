package com.iptvapp.handoff

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Envelope, protocol validation and peer storage: who may send, what may be sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HandoffSecurityTest {
    private val now = 1_800_000_000_000L
    private val offer = JSONObject().put("t", "offer").put("tx", "0123456789abcdef0123456789abcdef")

    private fun sealFromPhone(message: JSONObject = offer, secret: String = Fx.SECRET, at: Long = now, to: String = Fx.TV_ID) =
        HandoffEnvelope.seal(secret, Fx.PHONE_ID, to, "POST", "/handoff/offer", message, at).toString()

    private fun openAtTv(body: String, secrets: (String) -> List<String> = { listOf(Fx.SECRET) }, replay: ReplayGuard? = ReplayGuard(), at: Long = now) =
        HandoffEnvelope.open(body, Fx.TV_ID, "POST", "/handoff/offer", at, secrets, replay)

    @Test fun aPairedDevicesMessageOpens() {
        val r = openAtTv(sealFromPhone())
        assertTrue(r is HandoffEnvelope.OpenResult.Ok)
        val ok = r as HandoffEnvelope.OpenResult.Ok
        assertEquals(Fx.PHONE_ID, ok.opened.fromId)
        assertEquals("offer", ok.opened.message.getString("t"))
    }

    @Test fun anUnpairedDeviceIsRefused() {
        // Sealed with a secret the TV doesn't have.
        assertTrue(openAtTv(sealFromPhone(secret = Fx.OTHER_SECRET)) is HandoffEnvelope.OpenResult.Refused)
        // Or a sender the TV has no secret for at all.
        assertTrue(openAtTv(sealFromPhone(), secrets = { emptyList() }) is HandoffEnvelope.OpenResult.Refused)
    }

    @Test fun aForgottenPeerIsRefused() {
        val registry = PeerRegistry(MapStore(), FakeSealer())
        registry.upsert(Fx.phonePeer())
        val body = sealFromPhone()
        registry.remove(Fx.PHONE_ID)
        val r = openAtTv(body, secrets = { id -> listOfNotNull(registry.byId(id)?.secret) })
        assertTrue(r is HandoffEnvelope.OpenResult.Refused)
    }

    @Test fun anOldMessageIsRefusedWithTheTime() {
        val r = openAtTv(sealFromPhone(at = now - HandoffProtocol.TIME_WINDOW_MS - 1))
        assertTrue(r is HandoffEnvelope.OpenResult.Clock)
        assertEquals(now, (r as HandoffEnvelope.OpenResult.Clock).nowMs)
    }

    @Test fun aTamperedMessageIsRefused() {
        val o = JSONObject(sealFromPhone())
        val c = o.getString("c")
        val flipped = (if (c[10] == '0') '1' else '0')
        o.put("c", c.substring(0, 10) + flipped + c.substring(11))
        assertTrue(openAtTv(o.toString()) is HandoffEnvelope.OpenResult.Refused)
        // Changing the claimed send time breaks the additional data too.
        val o2 = JSONObject(sealFromPhone()).put("at", now + 1)
        assertTrue(openAtTv(o2.toString()) is HandoffEnvelope.OpenResult.Refused)
    }

    @Test fun aMessageForAnotherPathOrDeviceIsRefused() {
        val body = sealFromPhone()
        assertTrue(HandoffEnvelope.open(body, Fx.TV_ID, "POST", "/handoff/cancel", now, { listOf(Fx.SECRET) }, ReplayGuard())
            is HandoffEnvelope.OpenResult.Refused)
        assertTrue(HandoffEnvelope.open(sealFromPhone(to = Fx.OTHER_ID), Fx.TV_ID, "POST", "/handoff/offer", now, { listOf(Fx.SECRET) }, ReplayGuard())
            is HandoffEnvelope.OpenResult.Refused)
    }

    @Test fun aReplayIsRefused() {
        val guard = ReplayGuard()
        val body = sealFromPhone()
        assertTrue(openAtTv(body, replay = guard) is HandoffEnvelope.OpenResult.Ok)
        assertTrue(openAtTv(body, replay = guard) is HandoffEnvelope.OpenResult.Refused)
    }

    @Test fun aReflectedMessageIsRefused() {
        // The phone's own message sent back to it: a different direction key.
        val body = sealFromPhone()
        val r = HandoffEnvelope.open(body, Fx.PHONE_ID, "POST", "/handoff/offer", now, { listOf(Fx.SECRET) }, ReplayGuard())
        assertTrue(r is HandoffEnvelope.OpenResult.Refused)
    }

    @Test fun anOversizedOrMalformedBodyIsRefused() {
        assertTrue(openAtTv("x".repeat(HandoffProtocol.MAX_ENVELOPE_BYTES + 1)) is HandoffEnvelope.OpenResult.Refused)
        assertTrue(openAtTv("{not json") is HandoffEnvelope.OpenResult.Refused)
        assertTrue(openAtTv("{}") is HandoffEnvelope.OpenResult.Refused)
        try {
            sealFromPhone(JSONObject().put("t", "offer").put("pad", "y".repeat(HandoffProtocol.MAX_MESSAGE_BYTES)))
            fail("an oversized message must not be sealed")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun aReplyIsBoundToItsRequest() {
        val reqNonce = JSONObject(sealFromPhone()).getString("n")
        val reply = HandoffEnvelope.seal(Fx.SECRET, Fx.TV_ID, Fx.PHONE_ID, "POST", "/handoff/offer", JSONObject().put("state", "playing"), now, reqNonce)
        val good = HandoffEnvelope.open(reply.toString(), Fx.PHONE_ID, "POST", "/handoff/offer", now, { listOf(Fx.SECRET) }, null, reqNonce)
        assertTrue(good is HandoffEnvelope.OpenResult.Ok)
        // The same reply can't answer a different request.
        val other = JSONObject(sealFromPhone()).getString("n")
        val bad = HandoffEnvelope.open(reply.toString(), Fx.PHONE_ID, "POST", "/handoff/offer", now, { listOf(Fx.SECRET) }, null, other)
        assertTrue(bad is HandoffEnvelope.OpenResult.Refused)
    }

    // ── Descriptor ──────────────────────────────────────────────────────────

    @Test fun aDescriptorRoundTrips() {
        val d = Fx.episode()
        assertEquals(d, HandoffProtocol.decode(HandoffProtocol.encode(d)))
    }

    @Test fun noCredentialOrAddressEverEntersTheDescriptor() {
        val wire = HandoffProtocol.encode(Fx.movie()).toString()
        assertFalse(wire.contains("alice"))
        assertFalse(wire.contains("a.example"))
        assertFalse(wire.contains("8080"))
        assertFalse(wire.contains("http"))
        // The provider is only a keyed tag, different for every pairing secret.
        assertFalse(HandoffCrypto.providerTag(Fx.SECRET, Fx.PROVIDER_A) == HandoffCrypto.providerTag(Fx.OTHER_SECRET, Fx.PROVIDER_A))
    }

    @Test fun aUrlOrAddressIsRefusedInEveryTextField() {
        for (bad in listOf("http://evil.example/x.m3u8", "https://a", "file:/sdcard/x", "content://x", "/data/data/x", "10.0.0.5", "user@host", "www.example.com")) {
            val o = HandoffProtocol.encode(Fx.movie()).put("title", bad)
            assertRejected(o)
            assertRejected(HandoffProtocol.encode(Fx.movie()).put("plabel", bad))
            assertRejected(HandoffProtocol.encode(Fx.movie()).put("from", bad))
        }
        // A url-carrying field that isn't part of the protocol is refused outright.
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("url", "http://x"))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("password", "p"))
    }

    @Test fun malformedDescriptorsAreRefused() {
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("tx", "short"))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("v", 2))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("kind", "recording"))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("sid", "900"))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("pos", -5))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("speed", 16))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("ext", "../mkv"))
        assertRejected(HandoffProtocol.encode(Fx.episode()).put("epid", "12/../3"))
        assertRejected(HandoffProtocol.encode(Fx.episode()).remove("season").let { HandoffProtocol.encode(Fx.episode()).apply { remove("season") } })
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("title", "a\u0000b"))
        assertRejected(HandoffProtocol.encode(Fx.movie()).put("alang", "en; drop"))
    }

    @Test fun speedAndLanguagesWithinBoundsAreKept() {
        val d = Fx.movie().copy(speed = 1.5f, audioLang = "en", subtitleLang = "pt-BR", subtitlesOn = true)
        val back = HandoffProtocol.decode(HandoffProtocol.encode(d))
        assertEquals(1.5f, back.speed)
        assertEquals("pt-BR", back.subtitleLang)
        assertEquals(true, back.subtitlesOn)
    }

    private fun assertRejected(o: JSONObject) {
        try {
            HandoffProtocol.decode(o)
            fail("should be refused: $o")
        } catch (_: HandoffRejected) {
        }
    }

    // ── Peer storage ────────────────────────────────────────────────────────

    @Test fun peerSecretsAreStoredSealedAndSurviveARestart() {
        val store = MapStore()
        PeerRegistry(store, FakeSealer()).upsert(Fx.tvPeer())
        assertFalse(store.map.values.any { it.contains(Fx.SECRET) })
        val again = PeerRegistry(store, FakeSealer())
        assertEquals(Fx.SECRET, again.byId(Fx.TV_ID)?.secret)
    }

    @Test fun aSecretThatCantBeOpenedDropsThePeer() {
        val store = MapStore()
        PeerRegistry(store, FakeSealer()).upsert(Fx.tvPeer())
        val broken = object : SecretSealer {
            override fun seal(plain: String) = null
            override fun open(sealed: String): String? = null
        }
        assertNull(PeerRegistry(store, broken).byId(Fx.TV_ID))
    }

    @Test fun forgettingRemovesThePeer() {
        val registry = PeerRegistry(MapStore(), FakeSealer())
        registry.upsert(Fx.tvPeer())
        assertNotNull(registry.remove(Fx.TV_ID))
        assertNull(registry.byId(Fx.TV_ID))
        assertNull(registry.bySecret(Fx.SECRET))
    }

    @Test fun helloIsValidated() {
        val h = HandoffProtocol.decodeHello(HandoffProtocol.hello(HandoffProtocol.Hello(Fx.PHONE_ID, "Pixel", "phone", 9482)))
        assertEquals("phone", h.type)
        for (bad in listOf(
            HandoffProtocol.hello(HandoffProtocol.Hello("xyz", "Pixel", "phone", 9482)),
            HandoffProtocol.hello(HandoffProtocol.Hello(Fx.PHONE_ID, "Pixel", "server", 9482)),
            HandoffProtocol.hello(HandoffProtocol.Hello(Fx.PHONE_ID, "Pixel", "phone", 80)),
            HandoffProtocol.hello(HandoffProtocol.Hello(Fx.PHONE_ID, "http://x", "phone", 9482)),
        )) {
            try { HandoffProtocol.decodeHello(bad); fail("should be refused: $bad") } catch (_: HandoffRejected) {}
        }
    }
}
