package com.iptvapp.util

import com.iptvapp.util.QuickBarInput.LeftAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBarInputTest {

    private fun left(
        overlay: Boolean = false, vod: Boolean = false, timeshift: Boolean = false,
        repeat: Int = 0, peek: Boolean = true,
    ) = QuickBarInput.leftAction(overlay, vod, timeshift, repeat, peek)

    @Test fun bareLeftOnLiveTvOpensQuickPeek() = assertEquals(LeftAction.OPEN_QUICK_PEEK, left())

    @Test fun controlsShowingKeepFocusMoves() {
        assertEquals(LeftAction.MOVE_FOCUS, left(overlay = true))
        assertEquals(LeftAction.MOVE_FOCUS, left(overlay = true, vod = true))
        assertEquals(LeftAction.MOVE_FOCUS, left(overlay = true, timeshift = true))
    }

    @Test fun moviesKeepSkipping() {
        assertEquals(LeftAction.VOD_SKIP, left(vod = true))
        assertEquals(LeftAction.VOD_SKIP, left(vod = true, repeat = 3))
    }

    @Test fun replayControlsKeepGoingBack() {
        assertEquals(LeftAction.TIMESHIFT_REPLAY, left(timeshift = true))
        // Held: the long press does the 30-second jump; it never opens Quick Peek.
        assertEquals(LeftAction.IGNORE, left(timeshift = true, repeat = 1))
    }

    @Test fun heldLeftOpensOnlyOnce() = assertEquals(LeftAction.IGNORE, left(repeat = 2))

    @Test fun noQuickPeekHereSwallowsLeft() = assertEquals(LeftAction.IGNORE, left(peek = false))

    @Test fun swipeRightFromLeftEdgeCounts() =
        assertTrue(QuickBarInput.isEdgeSwipe(20f, 500f, 300f, 540f, width = 1000f, minDistancePx = 100f))

    @Test fun swipeOutsideTheEdgeZoneDoesNot() =
        assertFalse(QuickBarInput.isEdgeSwipe(250f, 500f, 700f, 500f, width = 1000f, minDistancePx = 100f))

    @Test fun shortOrLeftwardOrVerticalSwipesDoNot() {
        assertFalse(QuickBarInput.isEdgeSwipe(20f, 500f, 80f, 500f, 1000f, 100f))
        assertFalse(QuickBarInput.isEdgeSwipe(190f, 500f, 10f, 500f, 1000f, 100f))
        assertFalse(QuickBarInput.isEdgeSwipe(20f, 100f, 200f, 600f, 1000f, 100f))
    }

    @Test fun replayOnTapOpensQuickPeekHoldRewinds() {
        assertEquals(QuickBarInput.LeftRelease.OPEN_QUICK_PEEK, QuickBarInput.leftRelease(longPressed = false, quickPeekAvailable = true))
        assertEquals(QuickBarInput.LeftRelease.NOTHING, QuickBarInput.leftRelease(longPressed = true, quickPeekAvailable = true))
        assertEquals(QuickBarInput.LeftRelease.REPLAY, QuickBarInput.leftRelease(longPressed = false, quickPeekAvailable = false))
    }

    @Test fun noWidthNoSwipe() = assertFalse(QuickBarInput.isEdgeSwipe(0f, 0f, 500f, 0f, 0f, 10f))
}
