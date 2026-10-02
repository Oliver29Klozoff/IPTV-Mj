package com.iptvapp.util

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.iptvapp.R
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first

/**
 * The user's accent for screens outside Settings that carry its "Studio Rack" look (v6.85: the
 * guide grid and the movie / series detail screens). Same rules as SettingsActivity's own
 * applyRackAccent: a gradient preset is drawn as a real left-to-right gradient, text on the
 * accent is black or white by contrast over both ends, focus is a light outline, never the accent.
 */
object RackAccent {

    data class Accent(val start: Int, val end: Int?) {
        val stops: IntArray get() = intArrayOf(start, end ?: start)

        /** Black or white, whichever reads better on the accent over both of its ends. */
        val onAccent: Int
            get() {
                val ends = listOfNotNull(start, end)
                val black = ends.minOf { ColorUtils.calculateContrast(Color.BLACK, it) }
                val white = ends.minOf { ColorUtils.calculateContrast(Color.WHITE, it) }
                return if (black >= white) Color.BLACK else Color.WHITE
            }
    }

    private const val DEFAULT_ACCENT = 0xFF06B6D4.toInt()

    suspend fun load(prefs: PreferencesManager): Accent {
        val start = runCatching { Color.parseColor(prefs.accentColor.first()) }.getOrDefault(DEFAULT_ACCENT)
        val end = prefs.accentColorEnd.first().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        return Accent(start, end)
    }

    /** A solid accent button (Play): accent or gradient fill, light outline under D-pad focus,
     * grey while disabled, and a ripple. Replaces the button's own one-color background. */
    fun paintFillButton(button: TextView, accent: Accent) {
        val res = button.resources
        val density = res.displayMetrics.density
        val radius = 4 * density
        fun shape(colors: IntArray?, solid: Int, stroke: Int?) = GradientDrawable().apply {
            cornerRadius = radius
            if (colors != null) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                this.colors = colors
            } else {
                setColor(solid)
            }
            if (stroke != null) setStroke((2 * density + 0.5f).toInt(), stroke)
        }
        val ctx = button.context
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(null, ctx.getColor(R.color.rack_control_off), null))
            addState(intArrayOf(android.R.attr.state_focused), shape(accent.stops, 0, ctx.getColor(R.color.rack_focus_ring)))
            addState(intArrayOf(), shape(accent.stops, 0, null))
        }
        button.background = RippleDrawable(ColorStateList.valueOf(0x33000000), states, shape(null, Color.WHITE, null))
        androidx.core.view.ViewCompat.setBackgroundTintList(button, null)
        (button as? com.google.android.material.button.MaterialButton)?.backgroundTintList = null
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ctx.getColor(R.color.rack_disabled_text), accent.onAccent)
        ))
    }

    /** A determinate progress bar (watch progress) in the accent / gradient on a grey track. */
    fun paintProgress(bar: ProgressBar, accent: Accent) {
        bar.progressTintList = null
        bar.progressBackgroundTintList = null
        bar.progressDrawable = LayerDrawable(arrayOf<Drawable>(
            GradientDrawable().apply { setColor(bar.context.getColor(R.color.rack_control_off)) },
            ClipDrawable(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, accent.stops),
                Gravity.START, ClipDrawable.HORIZONTAL
            )
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }
}
