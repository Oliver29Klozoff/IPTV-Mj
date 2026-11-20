package com.iptvapp.ui.home

import android.view.View
import android.view.ViewGroup
import com.iptvapp.data.home.TvSidebar

/**
 * Lays the TV sidebar's buttons out as Customize Sidebar saved them (TvSidebar): the same button
 * views, moved within their container and shown or hidden, so their click handlers, labels and
 * accent painting are untouched. Views in the container that aren't sidebar entries stay after them.
 */
object TvSidebarLayout {

    /**
     * Puts [buttons] (sidebar entry id → its view) in [items]' order inside [container], and shows
     * each only while it is both shown in [items] and [available] (e.g. Movies with Movies on in
     * Settings). A focused button that moves keeps its focus; one that is hidden hands it on to the
     * nearest shown button.
     */
    fun apply(
        container: ViewGroup,
        buttons: Map<String, View>,
        items: List<TvSidebar.SidebarItem>,
        available: (String) -> Boolean,
    ) {
        val focused = buttons.values.firstOrNull { it.isFocused }
        var at = 0
        for (item in items) {
            val view = buttons[item.id] ?: continue
            if (container.getChildAt(at) !== view) {
                container.removeView(view)
                container.addView(view, at)
            }
            at++
        }
        for (item in items) {
            val view = buttons[item.id] ?: continue
            view.visibility = if (item.visible && available(item.id)) View.VISIBLE else View.GONE
        }
        if (focused != null && !focused.isFocused) {
            (if (focused.visibility == View.VISIBLE) focused else nearestShown(container, focused))?.requestFocus()
        }
    }

    /** The shown, focusable buttons of [container], top to bottom. */
    fun shown(container: ViewGroup): List<View> =
        (0 until container.childCount).map { container.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.isFocusable }

    /** [view] itself while it shows; else the closest shown button below it, then above it. */
    fun nearestShown(container: ViewGroup, view: View): View? {
        if (view.visibility == View.VISIBLE) return view
        val all = (0 until container.childCount).map { container.getChildAt(it) }
        val i = all.indexOf(view)
        if (i < 0) return shown(container).firstOrNull()
        val usable = { v: View -> v.visibility == View.VISIBLE && v.isFocusable }
        return all.drop(i + 1).firstOrNull(usable) ?: all.take(i).lastOrNull(usable)
    }
}
