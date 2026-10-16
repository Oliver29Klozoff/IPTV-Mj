package com.iptvapp.ui.player

import com.iptvapp.ui.mosaic.MosaicLayout
import com.iptvapp.ui.mosaic.MosaicLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the real org.json; the plain JVM only has Android's empty stubs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TileHealPolicyTest {

    @Test
    fun `repairs wait longer each time, cap out, and try a backup from the third`() {
        assertEquals(1_000L, TileHealPolicy.delayMs(1))
        assertEquals(3_000L, TileHealPolicy.delayMs(2))
        assertEquals(30_000L, TileHealPolicy.delayMs(6))
        assertEquals(30_000L, TileHealPolicy.delayMs(50))
        assertFalse(TileHealPolicy.tryBackup(2))
        assertTrue(TileHealPolicy.tryBackup(3))
    }

    @Test
    fun `a picture with no new frames is frozen even while the clock runs`() {
        val d = TileHealPolicy.FreezeDetector()
        assertFalse(d.sample(0L, playing = true, positionMs = 1_000L, renderedFrames = 100))
        // Sound keeps the clock moving; the picture has stopped.
        assertFalse(d.sample(2_000L, true, 3_000L, 100))
        assertFalse(d.sample(6_000L, true, 7_000L, 100))
        assertTrue(d.sample(10_000L, true, 11_000L, 100))
        // Reported once, then counts again from scratch.
        assertFalse(d.sample(12_000L, true, 13_000L, 100))
    }

    @Test
    fun `new frames keep a tile healthy, and a pause is never a freeze`() {
        val d = TileHealPolicy.FreezeDetector()
        var frames = 0
        for (t in 0L..60_000L step 2_000L) {
            frames += 50
            assertFalse(d.sample(t, true, 5_000L, frames)) // a live clock may stand still
        }
        for (t in 60_000L..120_000L step 2_000L) assertFalse(d.sample(t, playing = false, positionMs = 5_000L, renderedFrames = frames))
    }

    @Test
    fun `radio has no frames and is judged on its clock`() {
        val d = TileHealPolicy.FreezeDetector()
        assertFalse(d.sample(0L, true, 1_000L, null))
        assertFalse(d.sample(2_000L, true, 3_000L, null))
        assertFalse(d.sample(4_000L, true, 3_000L, null))
        assertFalse(d.sample(8_000L, true, 3_000L, null))
        assertTrue(d.sample(12_000L, true, 3_000L, null))
    }

    @Test
    fun `layouts round-trip with empty tiles and mixed providers`() {
        val layout = MosaicLayout(
            "NFL Sunday", 4,
            listOf(MosaicLayout.Tile(-1, 10, "CBS"), null, MosaicLayout.Tile(1, 77, "FOX"), MosaicLayout.Tile(0, 5, "NBC")),
        )
        val back = MosaicLayouts.decode(MosaicLayouts.encode(listOf(layout)))
        assertEquals(listOf(layout), back)
    }

    @Test
    fun `bad or old data never crashes and is cut to the grid size`() {
        assertTrue(MosaicLayouts.decode(null).isEmpty())
        assertTrue(MosaicLayouts.decode("not json").isEmpty())
        val odd = MosaicLayouts.decode(
            """[{"name":"  ","grid":4},{"name":"News","grid":5,"tiles":[{"server":-1,"stream":3}]}]"""
        )
        assertEquals(1, odd.size)
        assertEquals(4, odd[0].gridSize) // an unknown size falls back to 2x2
        assertEquals(4, odd[0].tiles.size)
        assertEquals(MosaicLayout.Tile(-1, 3, ""), odd[0].tiles[0])
        assertNull(odd[0].tiles[1])
    }

    @Test
    fun `saving a name again replaces it in place, and delete ignores case`() {
        val a = MosaicLayout("News", 2, listOf(null, null))
        val b = MosaicLayout("Game Night", 4, List(4) { null })
        val updated = MosaicLayouts.upsert(listOf(a, b), MosaicLayout(" news ", 6, List(6) { null }))
        assertEquals(listOf("news", "Game Night"), updated.map { it.name })
        assertEquals(6, updated[0].gridSize)
        assertEquals(listOf("Game Night"), MosaicLayouts.remove(updated, "NEWS").map { it.name })
    }

    @Test
    fun `removing a provider empties its tiles and moves later ones down`() {
        val l = MosaicLayout(
            "Mix", 4,
            listOf(MosaicLayout.Tile(-1, 1, "A"), MosaicLayout.Tile(0, 2, "B"), MosaicLayout.Tile(1, 3, "C"), null),
        )
        val after = MosaicLayouts.afterProviderRemoved(listOf(l), removedIndex = 0).single()
        assertEquals(listOf(MosaicLayout.Tile(-1, 1, "A"), null, MosaicLayout.Tile(0, 3, "C"), null), after.tiles)
    }
}
