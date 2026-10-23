package com.iptvapp.ui.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.iptvapp.ui.mosaic.MultiViewPolicy
import com.iptvapp.ui.mosaic.MosaicLayout
import com.iptvapp.ui.mosaic.MosaicLayouts
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TileHealerTest {
    private class FakePlayer {
        val listeners = mutableListOf<Player.Listener>()
        val opened = mutableListOf<String>()
        var stops = 0
        var state = Player.STATE_IDLE
        var volume = 0f
        var volumeChanged: () -> Unit = {}
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0]); null }
                "stop" -> { stops++; state = Player.STATE_IDLE; null }
                "setMediaItem" -> { opened.add((args!![0] as MediaItem).localConfiguration!!.uri.toString()); null }
                "getPlaybackState" -> state
                "isPlaying" -> false
                "getCurrentPosition" -> 0L
                "setVolume" -> { volume = args!![0] as Float; volumeChanged(); null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player
        fun ended() { listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) } }
    }

    private fun TestScope.healer(fake: FakePlayer, statuses: MutableList<String?> = mutableListOf()) = TileHealer(
        fake.player, backgroundScope, "test", {}, { statuses.add(it) }, clock = { testScheduler.currentTime }
    )
    private fun source(id: String) = TileHealer.Source(id, id) { "https://example.test/$id" }

    @Test fun `late obsolete resolver cannot replace final channel and reuses same player`() = runTest {
        val fake = FakePlayer()
        val h = healer(fake)
        val stale = CompletableDeferred<String>()
        h.open(TileHealer.Source("old", "old") { withContext(NonCancellable) { stale.await() } })
        runCurrent()
        h.open(source("final")); runCurrent()
        stale.complete("https://example.test/old"); runCurrent()
        assertEquals(listOf("https://example.test/final"), fake.opened)
        assertEquals(1, fake.listeners.size)
        h.release()
        assertTrue(fake.listeners.isEmpty())
    }

    @Test fun `one failed tile stops after six retries without touching another`() = runTest {
        val failed = FakePlayer(); val good = FakePlayer()
        val statuses = mutableListOf<String?>()
        val h = healer(failed, statuses); val other = healer(good)
        h.open(source("bad")); other.open(source("good")); runCurrent()
        val goodStops = good.stops
        repeat(6) { attempt ->
            failed.ended(); failed.ended(); runCurrent()
            advanceTimeBy(TileHealPolicy.delayMs(attempt + 1)); runCurrent()
        }
        failed.ended(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(7, failed.opened.size)
        assertTrue(statuses.last()!!.startsWith("Stopped after 6"))
        assertEquals(1, good.opened.size)
        assertEquals(goodStops, good.stops)
        h.release(); other.release()
    }

    @Test fun `pause cancels repair resume opens once and release blocks callbacks`() = runTest {
        val fake = FakePlayer(); val h = healer(fake)
        h.open(source("a")); runCurrent()
        fake.ended(); runCurrent(); h.pause()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, fake.opened.size)
        h.resume(); h.resume(); runCurrent()
        assertEquals(2, fake.opened.size)
        h.release(); fake.ended(); h.open(source("b"))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, fake.opened.size)
    }

    @Test fun `unavailable resolver has bounded retries and manual selection resets exhaustion`() = runTest {
        val fake = FakePlayer(); val statuses = mutableListOf<String?>(); val h = healer(fake, statuses)
        var calls = 0
        h.open(TileHealer.Source("bad", "bad") { calls++; null }); runCurrent()
        repeat(6) { advanceTimeBy(TileHealPolicy.delayMs(it + 1)); runCurrent() }
        assertEquals(7, calls)
        assertTrue(statuses.last()!!.startsWith("Stopped after 6"))
        h.open(source("replacement")); runCurrent()
        assertEquals(listOf("https://example.test/replacement"), fake.opened)
        h.release()
    }

    @Test fun `resource policy limits weak devices and counts each provider independently`() {
        assertEquals(1, MultiViewPolicy.tileLimit(true, 512, 8))
        assertEquals(1, MultiViewPolicy.tileLimit(false, 256, 1))
        assertEquals(2, MultiViewPolicy.tileLimit(false, 128, 8))
        assertEquals(4, MultiViewPolicy.tileLimit(false, 256, 4))
        assertEquals(0, MultiViewPolicy.providerSlots(2, 2, 1))
        assertEquals(0, MultiViewPolicy.providerSlots(1, 0, 1))
        assertEquals(1, MultiViewPolicy.providerSlots(1, 0, 0))
        assertEquals(0, MultiViewPolicy.providerSlots(null, null, 1))
    }

    @Test fun `known provider refusal does not trigger network retry loops`() = runTest {
        val fake = FakePlayer(); val statuses = mutableListOf<String?>(); val h = healer(fake, statuses)
        var calls = 0
        h.open(TileHealer.Source("full", "full") { calls++; throw TileHealer.AdmissionDenied("Provider full") })
        runCurrent(); advanceTimeBy(120_000); runCurrent()
        assertEquals(1, calls)
        assertTrue(fake.opened.isEmpty())
        assertEquals("Provider full", statuses.last())
        h.release()
    }

    @Test fun `audio handoff never overlaps even when selecting an earlier tile`() {
        val tiles = List(4) { FakePlayer() }
        tiles.forEach { it.volumeChanged = { assertTrue(tiles.count { tile -> tile.volume > 0f } <= 1) } }
        val players = tiles.map { it.player }
        com.iptvapp.ui.mosaic.MultiViewAudio.select(players, 3)
        com.iptvapp.ui.mosaic.MultiViewAudio.select(players, 0)
        assertEquals(1f, tiles[0].volume, 0f)
        com.iptvapp.ui.mosaic.MultiViewAudio.select(players, 0, true)
        assertTrue(tiles.all { it.volume == 0f })
    }

    @Test fun `simultaneous admissions reserve slots separately for primary and secondary`() = runTest {
        val admission = com.iptvapp.ui.mosaic.MultiViewConnections {
            delay(100)
            com.iptvapp.data.repository.XtreamRepository.AccountLimits("Active", 1, 0, 0)
        }
        val primary = async { admission.acquire(0, -1) }
        val secondary = async { admission.acquire(1, 0) }
        val extra = async { admission.acquire(2, -1) }
        assertTrue(primary.await()); assertTrue(secondary.await()); assertFalse(extra.await())
        assertTrue(admission.acquire(0, -1)) // same tile replacement keeps its slot
        admission.release(0)
        assertTrue(admission.acquire(2, -1))
    }

    @Test fun `cleared layout cannot gain a slot from a late provider response`() = runTest {
        val response = CompletableDeferred<com.iptvapp.data.repository.XtreamRepository.AccountLimits?>()
        val admission = com.iptvapp.ui.mosaic.MultiViewConnections { response.await() }
        val pending = async { admission.acquire(0, -1) }
        runCurrent(); admission.clear()
        response.complete(com.iptvapp.data.repository.XtreamRepository.AccountLimits("Active", 2, 0, 0))
        assertFalse(pending.await())
    }

    @Test fun `saved mixed provider arrangement selection and mute survive round trip`() {
        val layout = MosaicLayout("Mixed", 2, listOf(MosaicLayout.Tile(0, 42, "News"), MosaicLayout.Tile(-1, 42, "Sports")), 1, true)
        assertEquals(listOf(layout), MosaicLayouts.decode(MosaicLayouts.encode(listOf(layout))))
        val reduced = layout.copy(gridSize = 1, tiles = layout.tiles.take(1), selectedTile = 0)
        assertEquals(listOf(reduced), MosaicLayouts.decode(MosaicLayouts.encode(listOf(reduced))))
    }
}
