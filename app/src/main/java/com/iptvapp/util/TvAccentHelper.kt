package com.iptvapp.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.Button

/** Builds a focus-ring drawable matching tv_sidebar_focus.xml's look (translucent fill +
 * solid border on focus) but colored with the user's chosen accent, so themed buttons still
 * get a properly tinted focus highlight instead of the hardcoded blue. */
object TvAccentHelper {

    fun buildFocusDrawable(context: Context, accent: Int): StateListDrawable {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics)

        val focused = GradientDrawable().apply {
            setColor(withAlpha(accent, 0x33))
            setStroke(dp(2).toInt(), accent)
            cornerRadius = dp(6)
        }
        val pressed = GradientDrawable().apply {
            setColor(withAlpha(accent, 0x44))
            cornerRadius = dp(6)
        }
        // "Selected" here means "this is the sidebar's currently active section" (see
        // TvHomeActivity.activeSidebarButton/selectSection) — a persistent left accent bar +
        // faint tint, distinct from the focus ring above so the active section stays visibly
        // marked even after D-pad focus moves to a different button. Layered as a separate
        // state (not merged into `focused`) so a focused-but-inactive button still gets the
        // plain focus ring, and the active-but-unfocused button still shows its own marker.
        val selected = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(withAlpha(accent, 0x1A)) },
            GradientDrawable().apply { setColor(accent) }
        )).apply {
            setLayerInsetStart(1, 0)
            setLayerWidth(1, dp(3).toInt())
            setLayerGravity(1, android.view.Gravity.START)
        }
        val default = GradientDrawable().apply { setColor(Color.TRANSPARENT) }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected, android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), default)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /** Applies the accent to a button's text and gives it its own focus-drawable instance
     * (StateListDrawables must not be shared across views that might animate independently). */
    fun applyToButton(button: Button, accent: Int) {
        button.setTextColor(accent)
        button.background = buildFocusDrawable(button.context, accent)
    }
}
