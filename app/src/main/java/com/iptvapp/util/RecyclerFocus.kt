package com.iptvapp.util

import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView

/**
 * Puts the remote on item 0 once it is laid out. Results that have only just been submitted
 * have no views yet, so this waits for the layout passes that create them (a few at most) and
 * stops as soon as item 0 itself takes focus.
 */
fun RecyclerView.focusFirstItemWhenLaidOut(maxLayouts: Int = 5) {
    if ((adapter?.itemCount ?: 0) == 0) return
    scrollToPosition(0)
    val observer = viewTreeObserver
    observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        var layouts = 0
        override fun onGlobalLayout() {
            val focused = findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true
            if (focused || ++layouts >= maxLayouts) {
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                else viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        }
    })
    requestLayout()
}
