package com.iptvapp.ui.home

import android.view.View
import android.view.ViewGroup
import com.google.android.material.tabs.TabLayout

/**
 * The phone's tabs switched off in Settings (Movies, Series, Watching, Providers). Material's
 * TabLayout makes a tab visible again whenever it redraws it (setTabTextColors, which the accent
 * color uses, redraws them all), so the hidden ones are remembered here and hidden again after
 * any redraw: [reapply] straight after a known one, and a layout listener for the rest.
 */
class HiddenTabs(private val tabs: TabLayout) {

    private val hidden = mutableSetOf<Int>()

    init {
        tabs.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (hidden.any { tabs.getTabAt(it)?.view?.visibility == View.VISIBLE }) tabs.post { reapply() }
        }
    }

    fun set(index: Int, show: Boolean) {
        if (show) hidden.remove(index) else hidden.add(index)
        show(index, show)
        tabs.requestLayout()
    }

    fun reapply() = hidden.forEach { show(it, false) }

    private fun show(index: Int, show: Boolean) {
        val view = tabs.getTabAt(index)?.view ?: return
        view.visibility = if (show) View.VISIBLE else View.GONE
        view.layoutParams?.let { lp ->
            lp.width = if (show) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            view.layoutParams = lp
        }
    }
}
