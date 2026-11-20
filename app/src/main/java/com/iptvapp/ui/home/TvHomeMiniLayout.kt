package com.iptvapp.ui.home

import android.view.View
import android.widget.LinearLayout
import com.iptvapp.data.home.TvHomeMiniMode

/**
 * The TV home's right column for each [TvHomeMiniMode]: the mini player section (picture, footer
 * and guide progress, activity_tv_home's tvMiniPlayerSection) over the rows (tvRvHome).
 */
object TvHomeMiniLayout {

    /** Whether the mini player section shows. Away from Home it always does: it has the column. */
    fun playerShown(mode: TvHomeMiniMode, homeShowing: Boolean) = !homeShowing || mode != TvHomeMiniMode.OFF

    /** Hero keeps one row in a fixed slot, each row stretched to fill it; the others let rows size themselves. */
    fun rowsFillSlot(mode: TvHomeMiniMode) = mode == TvHomeMiniMode.HERO

    /**
     * Sizes [section] and [rows] for [mode]. Hero: the rows get the fixed [slotPx] and the player
     * the rest. Compact: they split the column evenly. Off: the player is gone and the rows take
     * the whole column. The rows' own visibility stays with the caller (Home shows them).
     * [player] (the focusable picture) can't take focus while hidden: a view inside a GONE parent
     * still accepts requestFocus, which would leave the remote on nothing. [expanded] (the idle
     * full screen) keeps it unfocusable as well, for its own reason.
     */
    fun apply(
        mode: TvHomeMiniMode, homeShowing: Boolean, section: View, rows: View, slotPx: Int,
        player: View? = null, expanded: Boolean = false,
    ) {
        val shown = playerShown(mode, homeShowing)
        section.visibility = if (shown) View.VISIBLE else View.GONE
        player?.isFocusable = shown && !expanded
        (section.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height != 0 || lp.weight != 1f) { lp.height = 0; lp.weight = 1f; section.layoutParams = lp }
        }
        val lp = rows.layoutParams as? LinearLayout.LayoutParams ?: return
        val flexible = homeShowing && !rowsFillSlot(mode)
        val height = if (flexible) 0 else slotPx
        val weight = if (flexible) 1f else 0f
        if (lp.height != height || lp.weight != weight) {
            lp.height = height
            lp.weight = weight
            rows.layoutParams = lp
        }
    }
}
