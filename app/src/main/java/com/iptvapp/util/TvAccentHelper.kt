package com.iptvapp.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.Button
import com.iptvapp.R

/** Background for the Shield's sidebar sections and genre chips, in the Studio Rack look the
 * phone / car box use (v6.87): D-pad focus is the lifted fill + light 2dp outline Settings uses
 * (never the accent), and the active section ("selected") keeps a lifted fill with a 3dp bar
 * down its left edge in the user's accent — top-to-bottom gradient for a gradient preset — so
 * it stays marked once focus moves elsewhere. */
object TvAccentHelper {

    fun buildFocusDrawable(context: Context, accent: Int, accentEnd: Int? = null): StateListDrawable {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics)
        val lifted = context.getColor(R.color.rack_focus_fill)

        val focused = GradientDrawable().apply {
            setColor(lifted)
            setStroke(dp(2).toInt(), context.getColor(R.color.rack_focus_ring))
            cornerRadius = dp(4)
        }
        val pressed = GradientDrawable().apply {
            setColor(context.getColor(R.color.rack_pressed))
            cornerRadius = dp(4)
        }
        // Layered as its own state (not merged into `focused`) so a focused-but-inactive button
        // still gets the plain focus outline, and the active-but-unfocused one keeps its bar.
        val selected = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(lifted) },
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(accent, accentEnd ?: accent))
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

    /** Gives a sidebar button its own background instance (StateListDrawables must not be shared
     * across views that might animate independently). Its label is painted separately — see
     * TvHomeActivity.paintSectionLabel — since only the active section shows the accent. */
    fun applyToButton(button: Button, accent: Int, accentEnd: Int? = null) {
        button.background = buildFocusDrawable(button.context, accent, accentEnd)
        // Inflated Buttons are MaterialButtons, whose theme backgroundTint would recolor this.
        androidx.core.view.ViewCompat.setBackgroundTintList(button, null)
        (button as? com.google.android.material.button.MaterialButton)?.backgroundTintList = null
    }
}
