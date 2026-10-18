package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPeekTest {

    private val main5 = QuickPeek.Key(-1, 5)
    private val main6 = QuickPeek.Key(-1, 6)
    private val extra5 = QuickPeek.Key(2, 5)

    private fun row(key: QuickPeek.Key, name: String = "Ch ${key.streamId}", fav: Boolean = false) =
        QuickPeek.Row(key, key.streamId, name, null, fav, "c")

    private val h = 3_600_000L
    private fun airing(title: String, startH: Double, stopH: Double) =
        QuickPeek.Airing(title, (startH * h).toLong(), (stopH * h).toLong(), "")

    // ── Playing vs highlighted ─────────────────────────────────────────

    @Test
    fun openingAndHighlightingNeverTune() {
        val s = QuickPeek.Selection()
        s.playingNow(main5)
        s.highlight(main6)
        s.highlight(extra5)
        assertEquals(main5, s.playing)
        assertEquals(extra5, s.highlighted)
        assertNull(s.pending)
    }

    @Test
    fun okTunesTheHighlightedChannelOnce() {
        val s = QuickPeek.Selection()
        s.playingNow(main5)
        s.highlight(main6)
        assertEquals(QuickPeek.Selection.Decision.Tune(main6), s.select(s.highlighted!!))
        // A second OK (or a tap) before the player has switched does not tune it again.
        assertEquals(QuickPeek.Selection.Decision.Ignore, s.select(main6))
        assertEquals(main5, s.playing)
        s.playingNow(main6)
        assertNull(s.pending)
        assertEquals(main6, s.playing)
    }

    @Test
    fun pickingThePlayingChannelJustCloses() {
        val s = QuickPeek.Selection()
        s.playingNow(main5)
        assertEquals(QuickPeek.Selection.Decision.Close, s.select(main5))
        assertNull(s.pending)
    }

    @Test
    fun aSubstitutedCopyOrAFailedPickDoesNotBlockTheNextPick() {
        val s = QuickPeek.Selection()
        s.playingNow(main5)
        s.select(main6)
        // The player played a more reliable copy instead of 6.
        s.playingNow(QuickPeek.Key(-1, 60))
        assertEquals(QuickPeek.Selection.Decision.Tune(main6), s.select(main6))
        s.clearPending()
        assertEquals(QuickPeek.Selection.Decision.Tune(main6), s.select(main6))
    }

    @Test
    fun anotherProviderStartsAFreshGenerationAndDropsTheHighlight() {
        val s = QuickPeek.Selection()
        s.playingNow(main5)
        val g = s.generation
        s.highlight(main6)
        s.playingNow(main6) // same provider
        assertEquals(g, s.generation)
        assertEquals(main6, s.highlighted)
        s.playingNow(extra5) // another provider
        assertTrue(s.generation > g)
        assertNull(s.highlighted)
    }

    @Test
    fun theSameStreamIdOnTwoProvidersIsTwoChannels() {
        assertNotEquals(main5, extra5)
        assertNotEquals(QuickPeek.stableId(main5), QuickPeek.stableId(extra5))
        assertNotEquals(QuickPeek.stableId(QuickPeek.Key(0, 5)), QuickPeek.stableId(QuickPeek.Key(1, 5)))
        val rows = listOf(row(extra5), row(main5))
        assertEquals(1, QuickPeek.focusIndex(rows, null, main5))
        assertEquals(0, QuickPeek.focusIndex(rows, null, extra5))
        val s = QuickPeek.Selection()
        s.playingNow(main5)
        assertEquals(QuickPeek.Selection.Decision.Tune(extra5), s.select(extra5))
    }

    // ── Lists ──────────────────────────────────────────────────────────

    @Test
    fun listsRunFavoritesThenThisCategoryThenAllThenTheRest() {
        val cats = listOf("1" to "News", "2" to "Sports", "3" to "Kids")
        val sources = QuickPeek.sources(cats, "2")
        assertEquals(listOf("Favorites", "Sports", "All channels", "News", "Kids"), sources.map { it.label })
        assertEquals(1, QuickPeek.startSource(sources, "2"))
        // A channel with no known category opens on Favorites, and Sports is not listed twice.
        val none = QuickPeek.sources(cats, null)
        assertEquals(listOf("Favorites", "All channels", "News", "Sports", "Kids"), none.map { it.label })
        assertEquals(0, QuickPeek.startSource(none, null))
    }

    @Test
    fun leftAndRightWrapThroughTheLists() {
        assertEquals(4, QuickPeek.cycle(0, -1, 5))
        assertEquals(0, QuickPeek.cycle(4, 1, 5))
        assertEquals(2, QuickPeek.cycle(1, 1, 5))
        assertEquals(0, QuickPeek.cycle(0, 1, 0))
    }

    @Test
    fun changingListKeepsTheChannelInFocusWhenItIsThere() {
        val rows = listOf(row(main5), row(main6), row(QuickPeek.Key(-1, 7)))
        assertEquals(2, QuickPeek.focusIndex(rows, QuickPeek.Key(-1, 7), main5))
        // Not in this list: the playing channel, else the top.
        assertEquals(0, QuickPeek.focusIndex(rows, QuickPeek.Key(-1, 99), main5))
        assertEquals(1, QuickPeek.focusIndex(rows, null, main6))
        assertEquals(0, QuickPeek.focusIndex(rows, null, extra5))
    }

    @Test
    fun anEmptyListHasNothingToFocusOrRead() {
        assertEquals(-1, QuickPeek.focusIndex(emptyList(), main5, main5))
        assertEquals(-1, QuickPeek.step(0, 1, 0))
        assertTrue(QuickPeek.toLoad(emptyList(), 0, 5, 12, emptySet()).isEmpty())
    }

    @Test
    fun upAndDownStopAtTheEnds() {
        assertEquals(0, QuickPeek.step(0, -1, 10))
        assertEquals(9, QuickPeek.step(9, 1, 10))
        assertEquals(5, QuickPeek.step(2, 3, 10))
        assertEquals(9, QuickPeek.step(7, 50, 10))
    }

    @Test
    fun favoritesAreMarkedForTalkBack() {
        val text = QuickPeek.describe(row(main5, "ESPN", fav = true), null, playing = true, nowMs = 0L)
        assertTrue(text, text.startsWith("Channel 5, ESPN"))
        assertTrue(text, "playing now" in text)
        assertTrue(text, "favorite" in text)
    }

    // ── Guide ──────────────────────────────────────────────────────────

    @Test
    fun nowAndNextComeFromTheChannelsOwnShowsInAnyOrder() {
        val shows = listOf(airing("Late", 21.0, 22.0), airing("Evening", 20.0, 21.0), airing("Earlier", 19.0, 20.0))
        val nn = QuickPeek.nowNext(shows, (20.5 * h).toLong())
        assertEquals("Evening", nn.now?.title)
        assertEquals("Late", nn.next?.title)
        // In a gap between shows: nothing on, the next one still shown.
        val gap = QuickPeek.nowNext(listOf(airing("Later", 22.0, 23.0)), (21.5 * h).toLong())
        assertNull(gap.now)
        assertEquals("Later", gap.next?.title)
    }

    @Test
    fun noGuideDataIsShownAsSuchAndDoesNotCrash() {
        val nn = QuickPeek.nowNext(emptyList(), 0L)
        assertTrue(nn.empty)
        // A broken entry (ends before it starts) is ignored.
        assertTrue(QuickPeek.nowNext(listOf(airing("Bad", 2.0, 1.0)), (1.5 * h).toLong()).empty)
        val text = QuickPeek.describe(row(main6), nn, playing = false, nowMs = 0L)
        assertTrue(text, "No guide information" in text)
        assertFalse(text, "playing now" in text)
    }

    @Test
    fun progressAndTimeLeft() {
        val start = 0L
        val stop = 60 * 60_000L
        assertEquals(0, QuickPeek.progress(start, stop, -5_000L))
        assertEquals(500, QuickPeek.progress(start, stop, 30 * 60_000L))
        assertEquals(1000, QuickPeek.progress(start, stop, stop + 1))
        assertEquals(0, QuickPeek.progress(stop, start, 10L))
        assertEquals(30, QuickPeek.minutesLeft(stop, 30 * 60_000L))
        assertEquals(1, QuickPeek.minutesLeft(stop, stop - 20_000L))
        assertEquals(0, QuickPeek.minutesLeft(stop, stop))
        assertEquals("Ending", QuickPeek.remainingLabel(0))
        assertEquals("23 min left", QuickPeek.remainingLabel(23))
        assertEquals("2 h left", QuickPeek.remainingLabel(120))
        assertEquals("1 h 5 min left", QuickPeek.remainingLabel(65))
    }

    @Test
    fun guideTimesInSecondsOrMilliseconds() {
        assertEquals(1_800_000_000_000L, QuickPeek.ms(1_800_000_000L))
        assertEquals(1_800_000_000_000L, QuickPeek.ms(1_800_000_000_000L))
    }

    @Test
    fun theClockTickRereadsNothingUntilAShowEnds() {
        val entry = QuickPeek.NowNext(airing("On", 20.0, 21.0), airing("Next", 21.0, 22.0))
        // Every 30 seconds through the show: nothing to read again.
        var t = (20.0 * h).toLong()
        while (t < (21.0 * h).toLong()) {
            assertFalse(QuickPeek.stale(entry, t))
            t += 30_000L
        }
        assertTrue(QuickPeek.stale(entry, (21.0 * h).toLong()))
        // No guide at all: never re-read on the clock.
        assertFalse(QuickPeek.stale(QuickPeek.NowNext(null, null), Long.MAX_VALUE))
        // Rows already loaded are not read again when the list scrolls back over them.
        val rows = (1..40).map { row(QuickPeek.Key(-1, it)) }
        val loaded = rows.map { it.key }.toSet()
        assertTrue(QuickPeek.toLoad(rows, 10, 20, 12, loaded).isEmpty())
    }

    @Test
    fun aHugeListReadsOnlyTheRowsAroundTheScreen() {
        val rows = (1..30_000).map { row(QuickPeek.Key(-1, it)) }
        val want = QuickPeek.toLoad(rows, 15_000, 15_007, 12, emptySet())
        assertEquals(8 + 24, want.size)
        assertEquals(14_989, want.first().streamId)
        assertEquals(15_020, want.last().streamId)
        // Half already loaded: only the rest.
        val half = want.take(16).toSet()
        assertEquals(16, QuickPeek.toLoad(rows, 15_000, 15_007, 12, half).size)
        // Edges are clamped.
        assertEquals(1 + 12, QuickPeek.toLoad(rows, 0, 0, 12, emptySet()).size)
        assertEquals(29_999, QuickPeek.step(29_998, 5, rows.size))
        val started = System.nanoTime()
        assertEquals(29_999, QuickPeek.focusIndex(rows, rows.last().key, null))
        assertTrue("focus lookup took too long", System.nanoTime() - started < 200_000_000L)
    }
}
