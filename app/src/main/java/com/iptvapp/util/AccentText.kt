package com.iptvapp.util

import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Text in the user's accent — a left-to-right gradient across the text itself when the accent is
 * one of the gradient presets (Sunset, Ocean, Berry, Aurora), a plain color otherwise.
 *
 * The gradient is a shader on the TextView's paint, fitted to where the text actually sits
 * (its line bounds, so start-aligned and centered labels both get the full sweep), and re-fitted
 * whenever the view is laid out again or its text changes. Anything that later needs the label
 * back to a plain color must go through [plain] — a shader overrides setTextColor.
 *
 * Main thread only, like any View work. Views are held weakly, so rebuilt views don't leak.
 */
object AccentText {

    private class Fitter(val layout: View.OnLayoutChangeListener, val watcher: TextWatcher)

    private val fitters = WeakHashMap<TextView, Fitter>()

    /** [end] null (or the same as [start]) means a solid accent. */
    fun apply(tv: TextView, start: Int, end: Int?) {
        detach(tv)
        tv.setTextColor(start)
        if (end == null || end == start) {
            tv.paint.shader = null
            tv.invalidate()
            return
        }
        val fit = {
            val layout = tv.layout
            var left = 0f
            var right = (tv.width - tv.totalPaddingLeft - tv.totalPaddingRight).toFloat()
            if (layout != null && layout.lineCount > 0) {
                left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
                right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
            }
            tv.paint.shader = LinearGradient(left, 0f, maxOf(right, left + 1f), 0f, start, end, Shader.TileMode.CLAMP)
            tv.invalidate()
        }
        val fitter = Fitter(
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() },
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                // A fixed-width label (e.g. match_parent) gets new text without a new layout pass,
                // so the layout listener alone would leave the gradient fitted to the old text.
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = fit()
                override fun afterTextChanged(s: Editable?) {}
            }
        )
        tv.addOnLayoutChangeListener(fitter.layout)
        tv.addTextChangedListener(fitter.watcher)
        fitters[tv] = fitter
        fit()
    }

    /** Back to a plain [color], no gradient. */
    fun plain(tv: TextView, color: Int) {
        detach(tv)
        tv.paint.shader = null
        tv.setTextColor(color)
        tv.invalidate()
    }

    /** Back to plain, state-dependent colors (e.g. a focus-aware selector), no gradient. */
    fun plain(tv: TextView, colors: ColorStateList) {
        detach(tv)
        tv.paint.shader = null
        tv.setTextColor(colors)
        tv.invalidate()
    }

    private fun detach(tv: TextView) {
        fitters.remove(tv)?.let {
            tv.removeOnLayoutChangeListener(it.layout)
            tv.removeTextChangedListener(it.watcher)
        }
    }
}
