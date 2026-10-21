package com.iptvapp.handoff

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Matching, the receiver's transaction and the sender's, with fakes (no sockets, no sleeps). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HandoffFlowTest {

    private fun catalog() = FakeCatalog().apply {
        movies += MovieItem(-1, 900, "Example Movie", 2020, "mkv")
        // The same id on the TV's other provider is a different film.
        movies += MovieItem(0, 900, "Another Film", 2019, "mp4")
        channels += ChannelItem(-1, 45553, "US| GSN HD")
        seriesList += SeriesItem(-1, 77, "Example Show")
        episodesBySeries[-1 to 77] = listOf(
            EpisodeItem("5006", 3, 6, "The Sixth", "mp4"),
            EpisodeItem("5007", 3, 7, "The Seventh", "mp4"),
            EpisodeItem("6001", 4, 1, "Next Season", "mp4"),
        )
    }

    // ── Matching ────────────────────────────────────────────────────────────

    @Test fun theExactProviderResolvesById() = runTest {
        val r = ContentMatcher(catalog()).resolve(Fx.movie(), Fx.SECRET, emptyMap())
        val t = (r as Resolution.Playable).target
        assertEquals(-1, t.serverIndex)
        assertEquals("Example Movie", t.title)
    }

    @Test fun aSameIdOnAnotherProviderIsNeverUsed() = runTest {
        // The phone's provider B is the TV's slot 0: the same id 900 there is "Another Film".
        val c = catalog()
        val viaB = ContentMatcher(c).resolve(Fx.movie(providerIdentity = Fx.PROVIDER_B, title = "Another Film"), Fx.SECRET, emptyMap())
        assertEquals(0, (viaB as Resolution.Playable).target.serverIndex)
        // And provider A's movie 900 is never taken for provider B's.
        val viaA = ContentMatcher(c).resolve(Fx.movie(), Fx.SECRET, emptyMap()) as Resolution.Playable
        assertEquals("Example Movie", viaA.target.title)
    }

    @Test fun anUnknownProviderAsksForAMappingInsteadOfGuessing() = runTest {
        val r = ContentMatcher(catalog()).resolve(Fx.movie(providerIdentity = "http://c.example\ncarol"), Fx.SECRET, emptyMap())
        assertTrue(r is Resolution.ProviderMissing)
        assertEquals("T-Rex", (r as Resolution.ProviderMissing).senderLabel)
    }

    @Test fun noProviderOnTheReceiver() = runTest {
        val c = catalog().apply { providerList.clear() }
        assertTrue(ContentMatcher(c).resolve(Fx.movie(), Fx.SECRET, emptyMap()) is Resolution.NotFound)
    }

    @Test fun aMissingMovieIsNotFound() = runTest {
        val r = ContentMatcher(catalog()).resolve(Fx.movie(streamId = 12345), Fx.SECRET, emptyMap())
        assertTrue(r is Resolution.NotFound)
    }

    @Test fun aMappedProviderMatchesByTitleAndAsksWhenAmbiguous() = runTest {
        val unknown = "http://c.example\ncarol"
        val tag = HandoffCrypto.providerTag(Fx.SECRET, unknown)
        val c = catalog().apply {
            movies += MovieItem(0, 41, "Example Movie", 2020, "mkv")
            movies += MovieItem(0, 42, "Example Movie", 2020, "mp4")
        }
        val r = ContentMatcher(c).resolve(Fx.movie(providerIdentity = unknown), Fx.SECRET, mapOf(tag to 0))
        assertTrue(r is Resolution.Choose)
        assertEquals(2, (r as Resolution.Choose).options.size)
        // One match plays; the mapped provider's same id with another title is not trusted.
        val one = catalog()
        one.movies += MovieItem(0, 41, "Example Movie", 2020, "mkv")
        val single = ContentMatcher(one).resolve(Fx.movie(providerIdentity = unknown), Fx.SECRET, mapOf(tag to 0))
        assertEquals(41, ((single as Resolution.Playable).target).streamId)
    }

    @Test fun theExactEpisodeIsResolvedAndCheckedAgainstItsSeries() = runTest {
        val r = ContentMatcher(catalog()).resolve(Fx.episode(), Fx.SECRET, emptyMap()) as Resolution.Playable
        assertEquals("5007", r.target.episode?.id)
        assertEquals(listOf("5006", "5007"), r.target.seasonEpisodes.map { it.id })
        // The id exists but as a different episode: refused, never played as the wrong one.
        val wrong = ContentMatcher(catalog()).resolve(Fx.episode(episode = 8), Fx.SECRET, emptyMap())
        assertTrue(wrong is Resolution.NotFound)
        // Series that can't be loaded right now.
        val c = catalog().apply { episodesBySeries[-1 to 77] = null }
        assertTrue(ContentMatcher(c).resolve(Fx.episode(), Fx.SECRET, emptyMap()) is Resolution.Unavailable)
    }

    @Test fun aMappedEpisodeIsFoundBySeriesNameAndNumber() = runTest {
        val unknown = "http://c.example\ncarol"
        val tag = HandoffCrypto.providerTag(Fx.SECRET, unknown)
        val c = catalog().apply {
            seriesList += SeriesItem(0, 300, "Example Show")
            episodesBySeries[0 to 300] = listOf(EpisodeItem("x9", 3, 7, "The Seventh", "mkv"))
        }
        val r = ContentMatcher(c).resolve(Fx.episode(providerIdentity = unknown), Fx.SECRET, mapOf(tag to 0)) as Resolution.Playable
        assertEquals("x9", r.target.episode?.id)
    }

    @Test fun titlesInOtherScriptsStayDistinct() {
        assertEquals("кино один", ContentMatcher.norm("Кино Один"))
        assertFalse(ContentMatcher.norm("Кино Один") == ContentMatcher.norm("Кино Два"))
        assertFalse(ContentMatcher.norm("東京物語") == ContentMatcher.norm("七人の侍"))
        assertFalse(ContentMatcher.norm("東京物語").isEmpty())
    }

    @Test fun deadlinesApplyWithoutTheSender() = runTest {
        val clock = longArrayOf(1_000L)
        val ui = FakeUi()
        val rx = receiver(ui, clock = clock)
        rx.offer(Fx.phonePeer(), Fx.movie())
        assertTrue(rx.sweep())
        clock[0] += HandoffProtocol.APPROVAL_TTL_MS + 1
        // No poll arrives (the sender is gone); the sweep still closes the prompt.
        assertFalse(rx.sweep())
        assertEquals(listOf(Fx.movie().txId), ui.dismissed)
        // A player that only starts after its deadline is not reported as playing.
        rx.offer(Fx.phonePeer(), Fx.live())
        rx.accept(Fx.live().txId)
        clock[0] += HandoffProtocol.PREPARE_TTL_MS + 1
        assertFalse(rx.onPlaying(Fx.live().txId))
        assertEquals(InState.FAILED, rx.get(Fx.live().txId)!!.state)
    }

    @Test fun channelNamesMatchAcrossProviderPrefixesButNotAcrossFeeds() {
        assertEquals(ContentMatcher.channelNorm("US| GSN HD"), ContentMatcher.channelNorm("US: GSN"))
        assertEquals(ContentMatcher.channelNorm("US| GSN HD"), ContentMatcher.channelNorm("GSN ᴿᴬᵂ"))
        assertFalse(ContentMatcher.channelNorm("HBO East") == ContentMatcher.channelNorm("HBO West"))
        assertFalse(ContentMatcher.channelNorm("ESPN") == ContentMatcher.channelNorm("ESPN 2"))
    }

    // ── Receiver ────────────────────────────────────────────────────────────

    private fun receiver(ui: FakeUi = FakeUi(), sending: Boolean = false, clock: LongArray = longArrayOf(1_000L)) =
        HandoffReceiver(ContentMatcher(catalog()), ui, { sending }, "TV").also { it.now = { clock[0] } }

    @Test fun anOfferWaitsForTheAnswerAndARepeatIsAnsweredOnce() = runTest {
        val ui = FakeUi()
        val rx = receiver(ui)
        assertEquals("awaiting", rx.offer(Fx.phonePeer(), Fx.movie()).getString("state"))
        assertEquals("awaiting", rx.offer(Fx.phonePeer(), Fx.movie()).getString("state"))
        assertEquals(1, ui.presented.size)
    }

    @Test fun theSenderOnlyHearsPlayingOnceThePlayerPlays() = runTest {
        val clock = longArrayOf(1_000L)
        val rx = receiver(clock = clock)
        val tx = Fx.movie().txId
        rx.offer(Fx.phonePeer(), Fx.movie())
        clock[0] = 6_000L
        val plan = rx.accept(tx)!!
        // Five seconds of the source playing are added: 3 930 000 → 3 935 000.
        assertEquals(3_935_000L, plan.startPositionMs)
        assertEquals("preparing", rx.status(Fx.phonePeer(), tx, null).getString("state"))
        rx.onPlaying(tx)
        assertEquals("playing", rx.status(Fx.phonePeer(), tx, null).getString("state"))
        assertEquals("completed", rx.complete(Fx.phonePeer(), tx).getString("state"))
    }

    @Test fun theLatestPositionFromPollsIsUsed() = runTest {
        val clock = longArrayOf(1_000L)
        val rx = receiver(clock = clock)
        val tx = Fx.movie().txId
        rx.offer(Fx.phonePeer(), Fx.movie())
        clock[0] = 30_000L
        rx.status(Fx.phonePeer(), tx, HandoffProtocol.SourceState(4_000_000L, playing = false))
        clock[0] = 40_000L
        // Paused at the source: no time is added.
        assertEquals(4_000_000L, rx.accept(tx)!!.startPositionMs)
    }

    @Test fun liveAlwaysStartsAtTheLiveEdge() = runTest {
        val rx = receiver()
        rx.offer(Fx.phonePeer(), Fx.live())
        val plan = rx.accept(Fx.live().txId)!!
        assertEquals(0L, plan.startPositionMs)
        assertEquals(ContentKind.LIVE, plan.target.kind)
    }

    @Test fun aPositionPastTheReceiversCopyIsClamped() {
        assertEquals(3_590_000L, HandoffReceiver.clampToDuration(7_000_000L, 3_600_000L))
        assertEquals(1_000L, HandoffReceiver.clampToDuration(1_000L, 3_600_000L))
        assertEquals(1_000L, HandoffReceiver.clampToDuration(1_000L, 0L))
    }

    @Test fun declining() = runTest {
        val rx = receiver()
        rx.offer(Fx.phonePeer(), Fx.movie())
        rx.decline(Fx.movie().txId)
        assertEquals("declined", rx.status(Fx.phonePeer(), Fx.movie().txId, null).getString("state"))
        assertNull(rx.accept(Fx.movie().txId))
    }

    @Test fun aSecondOfferWhileOneIsPendingIsBusy() = runTest {
        val rx = receiver()
        rx.offer(Fx.phonePeer(), Fx.movie())
        assertEquals("busy", rx.offer(Fx.phonePeer(), Fx.live()).getString("state"))
    }

    @Test fun opposingTransfersAreRefused() = runTest {
        assertEquals("busy", receiver(sending = true).offer(Fx.phonePeer(), Fx.movie()).getString("state"))
    }

    @Test fun missingContentFailsAtOnceWithoutAPrompt() = runTest {
        val ui = FakeUi()
        val r = receiver(ui).offer(Fx.phonePeer(), Fx.movie(streamId = 31337))
        assertEquals("failed", r.getString("state"))
        assertTrue(r.getString("reason").contains("isn't"))
        assertTrue(ui.presented.isEmpty())
    }

    @Test fun aPromptThatCantBeShownTellsTheSenderWhy() = runTest {
        val r = receiver(FakeUi(canPresent = false)).offer(Fx.phonePeer(), Fx.movie())
        assertEquals("failed", r.getString("state"))
        assertEquals("Open MKTV on the TV to accept", r.getString("reason"))
    }

    @Test fun anUnansweredOfferTimesOut() = runTest {
        val clock = longArrayOf(1_000L)
        val ui = FakeUi()
        val rx = receiver(ui, clock = clock)
        rx.offer(Fx.phonePeer(), Fx.movie())
        clock[0] += HandoffProtocol.APPROVAL_TTL_MS + 1
        assertEquals("timed_out", rx.status(Fx.phonePeer(), Fx.movie().txId, null).getString("state"))
        assertEquals(listOf(Fx.movie().txId), ui.dismissed)
        assertNull(rx.accept(Fx.movie().txId))
    }

    @Test fun aPlayerThatFailsOrNeverStartsIsReportedAsFailed() = runTest {
        val clock = longArrayOf(1_000L)
        val ui = FakeUi()
        val rx = receiver(ui, clock = clock)
        rx.offer(Fx.phonePeer(), Fx.movie())
        rx.accept(Fx.movie().txId)
        rx.onPlayerFailed(Fx.movie().txId, "Source error")
        val r = rx.status(Fx.phonePeer(), Fx.movie().txId, null)
        assertEquals("failed", r.getString("state"))
        // Another that never starts.
        rx.offer(Fx.phonePeer(), Fx.live())
        rx.accept(Fx.live().txId)
        clock[0] += HandoffProtocol.PREPARE_TTL_MS + 1
        assertEquals("failed", rx.status(Fx.phonePeer(), Fx.live().txId, null).getString("state"))
        assertEquals(listOf(Fx.live().txId), ui.closed)
    }

    @Test fun aCancelBeforeStartClosesThePlayer() = runTest {
        val ui = FakeUi()
        val rx = receiver(ui)
        rx.offer(Fx.phonePeer(), Fx.movie())
        rx.accept(Fx.movie().txId)
        assertEquals("cancelled", rx.cancel(Fx.phonePeer(), Fx.movie().txId).getString("state"))
        assertEquals(listOf(Fx.movie().txId), ui.closed)
        // A late "playing" from that player doesn't revive it.
        rx.onPlaying(Fx.movie().txId)
        assertEquals("cancelled", rx.status(Fx.phonePeer(), Fx.movie().txId, null).getString("state"))
    }

    @Test fun anotherDeviceCantTouchSomeoneElsesRequest() = runTest {
        val rx = receiver()
        rx.offer(Fx.phonePeer(), Fx.movie())
        val stranger = Fx.phonePeer().copy(deviceId = Fx.OTHER_ID)
        assertEquals("failed", rx.cancel(stranger, Fx.movie().txId).getString("state"))
        assertEquals("failed", rx.offer(stranger, Fx.movie()).getString("state"))
        assertEquals("awaiting", rx.status(Fx.phonePeer(), Fx.movie().txId, null).getString("state"))
    }

    @Test fun mappingAProviderThatLacksTheContentFailsTheRequest() = runTest {
        val unknown = "http://c.example\ncarol"
        val tag = HandoffCrypto.providerTag(Fx.SECRET, unknown)
        val rx = HandoffReceiver(ContentMatcher(catalog()), FakeUi(), { false })
        val d = Fx.movie(providerIdentity = unknown, title = "Nowhere To Be Found")
        rx.offer(Fx.phonePeer(), d)
        assertTrue(rx.get(d.txId)!!.resolution is Resolution.ProviderMissing)
        // The person maps it to slot 0, which doesn't have that film: the sender hears "failed", not silence.
        assertTrue(rx.remap(Fx.phonePeer(map = mapOf(tag to 0)), d.txId) is Resolution.NotFound)
        assertEquals("failed", rx.status(Fx.phonePeer(), d.txId, null).getString("state"))
        assertNull(rx.current())
    }

    @Test fun anAmbiguousMatchNeedsAChoice() = runTest {
        val unknown = "http://c.example\ncarol"
        val tag = HandoffCrypto.providerTag(Fx.SECRET, unknown)
        val c = catalog().apply {
            movies += MovieItem(0, 41, "Example Movie", 2020, "mkv")
            movies += MovieItem(0, 42, "Example Movie", 2020, "mp4")
        }
        val rx = HandoffReceiver(ContentMatcher(c), FakeUi(), { false })
        val d = Fx.movie(providerIdentity = unknown)
        rx.offer(Fx.phonePeer(map = mapOf(tag to 0)), d)
        assertNull(rx.accept(d.txId))
        val options = (rx.get(d.txId)!!.resolution as Resolution.Choose).options
        assertEquals(42, rx.accept(d.txId, options[1])!!.target.streamId)
    }

    // ── Sender ──────────────────────────────────────────────────────────────

    private class FakePlayback(var pos: Long = 3_930_000L, var playing: Boolean = true) : SourcePlayback {
        var pauses = 0
        override fun positionMs() = pos
        override fun isPlaying() = playing
        override fun pauseForHandoff() { pauses++; playing = false }
    }

    /** Answers from a script, one per call; null entries are lost replies. */
    private class ScriptedTransport(vararg replies: String?) : HandoffTransport {
        private val queue = ArrayDeque(replies.toList())
        val sent = mutableListOf<JSONObject>()
        override suspend fun send(peer: Peer, type: String, message: JSONObject): JSONObject? {
            sent += message
            if (type == "complete" || type == "cancel") return JSONObject().put("state", "completed")
            val next = if (queue.isEmpty()) null else queue.removeFirst()
            return next?.let { JSONObject().put("state", it).put("reason", "") }
        }
    }

    @Test fun theSourcePausesOnlyAfterTheReceiverPlays() = runTest {
        val playback = FakePlayback()
        val transport = ScriptedTransport("awaiting", "awaiting", "preparing", "playing")
        val states = mutableListOf<OutState>()
        val sender = HandoffSender(transport, playback, { s, _ -> states += s }, now = { testScheduler.currentTime })
        val result = async { sender.run(Fx.tvPeer(), Fx.movie()) }
        runCurrent()
        assertEquals(0, playback.pauses)
        advanceTimeBy(1_100)
        assertEquals(0, playback.pauses)
        assertEquals(OutState.TRANSFERRED, result.await())
        assertEquals(1, playback.pauses)
        assertTrue(states.containsAll(listOf(OutState.REQUESTING, OutState.AWAITING_APPROVAL, OutState.PREPARING, OutState.TRANSFERRED)))
        // Every message in the transaction carries the same id, polls carry the position.
        assertTrue(transport.sent.all { it.getString("tx") == Fx.movie().txId })
        assertTrue(transport.sent.filter { it.getString("t") == "status" }.all { it.has("pos") })
        assertEquals("complete", transport.sent.last().getString("t"))
    }

    @Test fun aFailureLeavesTheSourcePlaying() = runTest {
        for (end in listOf("failed", "declined", "busy", "timed_out", "cancelled")) {
            val playback = FakePlayback()
            val r = HandoffSender(ScriptedTransport("awaiting", end), playback, { _, _ -> }, now = { testScheduler.currentTime })
                .run(Fx.tvPeer(), Fx.movie())
            assertTrue(r.terminal && r != OutState.TRANSFERRED)
            assertEquals(0, playback.pauses)
            assertTrue(playback.playing)
        }
    }

    @Test fun lostRepliesEndTheAttemptAndKeepPlaying() = runTest {
        val playback = FakePlayback()
        val transport = ScriptedTransport("awaiting") // then nothing answers
        val r = HandoffSender(transport, playback, { _, _ -> }, now = { testScheduler.currentTime }).run(Fx.tvPeer(), Fx.movie())
        assertEquals(OutState.FAILED, r)
        assertEquals(0, playback.pauses)
        assertEquals("cancel", transport.sent.last().getString("t"))
    }

    @Test fun anUnreachableReceiverIsRetriedABoundedNumberOfTimes() = runTest {
        val transport = ScriptedTransport(null, null, null)
        val r = HandoffSender(transport, FakePlayback(), { _, _ -> }, now = { testScheduler.currentTime }).run(Fx.tvPeer(), Fx.movie())
        assertEquals(OutState.FAILED, r)
        assertEquals(3, transport.sent.count { it.getString("t") == "offer" })
        // The repeats are the same transaction, so a receiver that got one acts once.
        assertEquals(1, transport.sent.map { it.getString("tx") }.toSet().size)
    }

    @Test fun aReceiverThatNeverAnswersTimesOut() = runTest {
        val replies = arrayOf<String?>("awaiting") + Array<String?>(400) { "awaiting" }
        val playback = FakePlayback()
        val r = HandoffSender(ScriptedTransport(*replies), playback, { _, _ -> }, now = { testScheduler.currentTime }).run(Fx.tvPeer(), Fx.movie())
        assertEquals(OutState.TIMED_OUT, r)
        assertEquals(0, playback.pauses)
    }

    @Test fun cancellingStopsAndTellsTheReceiver() = runTest {
        val playback = FakePlayback()
        val transport = ScriptedTransport(*Array<String?>(50) { "awaiting" })
        val sender = HandoffSender(transport, playback, { _, _ -> }, now = { testScheduler.currentTime })
        val result = async { sender.run(Fx.tvPeer(), Fx.movie()) }
        advanceTimeBy(2_500)
        sender.cancel()
        assertEquals(OutState.CANCELLED, result.await())
        assertEquals(0, playback.pauses)
        assertEquals("cancel", transport.sent.last().getString("t"))
    }

    @Test fun liveSendsNoPosition() = runTest {
        val transport = ScriptedTransport("awaiting", "playing")
        HandoffSender(transport, FakePlayback(), { _, _ -> }, now = { testScheduler.currentTime }).run(Fx.tvPeer(), Fx.live())
        assertFalse(transport.sent.filter { it.getString("t") == "status" }.any { it.has("pos") })
    }

    // ── End to end through real envelopes ──────────────────────────────────

    /** The sender and receiver joined by sealed envelopes, as the app joins them over HTTP. */
    private class LoopbackTransport(private val rx: HandoffReceiver, private val rxPeerView: Peer) : HandoffTransport {
        val guard = ReplayGuard()
        override suspend fun send(peer: Peer, type: String, message: JSONObject): JSONObject? {
            val path = "/handoff/$type"
            val now = System.currentTimeMillis()
            val req = HandoffEnvelope.seal(peer.secret, Fx.PHONE_ID, Fx.TV_ID, "POST", path, message, now)
            val opened = HandoffEnvelope.open(req.toString(), Fx.TV_ID, "POST", path, now, { listOf(Fx.SECRET) }, guard)
                as? HandoffEnvelope.OpenResult.Ok ?: return null
            val m = opened.opened.message
            val reply = when (type) {
                "offer" -> rx.offer(rxPeerView, HandoffProtocol.decode(m.getJSONObject("d")))
                "status" -> rx.status(rxPeerView, HandoffProtocol.txOf(m), HandoffProtocol.decodeSourceState(m))
                "complete" -> rx.complete(rxPeerView, HandoffProtocol.txOf(m))
                "cancel" -> rx.cancel(rxPeerView, HandoffProtocol.txOf(m))
                else -> return null
            }
            val sealedReply = HandoffEnvelope.seal(Fx.SECRET, Fx.TV_ID, Fx.PHONE_ID, "POST", path, reply, now, opened.opened.nonceHex)
            val back = HandoffEnvelope.open(sealedReply.toString(), Fx.PHONE_ID, "POST", path, now, { listOf(Fx.SECRET) }, null, opened.opened.nonceHex)
            return (back as? HandoffEnvelope.OpenResult.Ok)?.opened?.message
        }
    }

    @Test fun aMovieGoesFromPhoneToTvEndToEnd() = runTest {
        val ui = FakeUi()
        val rx = HandoffReceiver(ContentMatcher(catalog()), ui, { false })
        val playback = FakePlayback()
        val transport = LoopbackTransport(rx, Fx.phonePeer())
        val sender = HandoffSender(transport, playback, { _, _ -> }, now = { testScheduler.currentTime })
        val result = async { sender.run(Fx.tvPeer(), Fx.movie()) }
        runCurrent()
        assertEquals(listOf(Fx.movie().txId), ui.presented)
        // The person accepts on the TV; its player starts.
        val plan = rx.accept(Fx.movie().txId)
        assertNotNull(plan)
        advanceTimeBy(1_100)
        assertEquals(0, playback.pauses)
        rx.onPlaying(Fx.movie().txId)
        assertEquals(OutState.TRANSFERRED, result.await())
        assertEquals(1, playback.pauses)
        assertEquals(InState.COMPLETED, rx.get(Fx.movie().txId)!!.state)
    }

    @Test fun aMissingMovieFailsEndToEndAndThePhoneKeepsPlaying() = runTest {
        val rx = HandoffReceiver(ContentMatcher(catalog()), FakeUi(), { false })
        val playback = FakePlayback()
        val r = HandoffSender(LoopbackTransport(rx, Fx.phonePeer()), playback, { _, _ -> }, now = { testScheduler.currentTime })
            .run(Fx.tvPeer(), Fx.movie(streamId = 404))
        assertEquals(OutState.FAILED, r)
        assertEquals(0, playback.pauses)
        assertNull(rx.current())
    }
}
