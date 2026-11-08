package com.iptvapp.util

import kotlin.math.abs

/**
 * When D-pad Left or a swipe opens Quick Peek over full-screen live TV, the Quick Bar. GUIDE and
 * MENU already open it, but many Fire TV remotes have neither key. Nothing here touches the player.
 */
object QuickBarInput {

    enum class LeftAction {
        /** The controls are showing: Left moves focus between them. */
        MOVE_FOCUS,
        /** A movie or episode: Left skips back. */
        VOD_SKIP,
        /** Live TV with replay controls: Left goes back 10 seconds (held, 30). */
        TIMESHIFT_REPLAY,
        /** Bare live TV: Left opens Quick Peek. */
        OPEN_QUICK_PEEK,
        /** Nothing to do (a held key, or no Quick Peek here). */
        IGNORE,
    }

    /**
     * What a D-pad Left press does in the player when Quick Peek is closed. [repeatCount] is the
     * key's repeat count: holding Left opens Quick Peek once, not again on every repeat.
     */
    fun leftAction(
        overlayVisible: Boolean,
        isVod: Boolean,
        timeshiftControlsOpen: Boolean,
        repeatCount: Int,
        quickPeekAvailable: Boolean,
    ): LeftAction = when {
        overlayVisible -> LeftAction.MOVE_FOCUS
        isVod -> LeftAction.VOD_SKIP
        timeshiftControlsOpen -> if (repeatCount == 0) LeftAction.TIMESHIFT_REPLAY else LeftAction.IGNORE
        repeatCount == 0 && quickPeekAvailable -> LeftAction.OPEN_QUICK_PEEK
        else -> LeftAction.IGNORE
    }

    /** The left-hand part of the screen a swipe has to start in, as a fraction of its width. */
    const val EDGE_FRACTION = 0.2f

    /**
     * True for a swipe that starts in the left edge zone of a [width]-pixel-wide screen and moves
     * right by at least [minDistancePx], more across than up or down. Vertical swipes there stay
     * brightness.
     */
    fun isEdgeSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        width: Float,
        minDistancePx: Float,
    ): Boolean {
        if (width <= 0f) return false
        if (startX > width * EDGE_FRACTION) return false
        val dx = endX - startX
        val dy = endY - startY
        return dx >= minDistancePx && abs(dx) > abs(dy) * 1.5f
    }
}
