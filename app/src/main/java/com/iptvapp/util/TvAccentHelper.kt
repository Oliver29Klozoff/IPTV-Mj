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
        val default = GradientDrawable().apply { setColor(Color.TRANSPARENT) }

        return StateListDrawable().apply {
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
