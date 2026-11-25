package com.iptvapp.ui.settings

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptvapp.R
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Subtitle appearance in one place: a sample line over a dark "picture" that changes as each
 * option changes, then one row per option. Selecting a row (tap, or OK on the remote) steps it
 * to its next value and saves it at once; the player reads the same saved style.
 */
object SubtitleAppearance {
    val SIZES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val OFFSETS = listOf(0, 20, 40, 60, -20, -40, -60)
    val TEXT_COLORS = listOf(0xFFFFFFFF.toInt() to "White", 0xFFFFFF00.toInt() to "Yellow", 0xFF00E5FF.toInt() to "Cyan", 0xFF000000.toInt() to "Black")
    val BACKGROUNDS = listOf(0x00000000 to "None", 0x99000000.toInt() to "Shaded", 0xFF000000.toInt() to "Black box")
    val OUTLINE_COLORS = listOf(0xFF000000.toInt() to "Black", 0xFFFFFFFF.toInt() to "White", 0xFF404040.toInt() to "Dark gray")

    /** The value after [current] in [values], wrapping; anything unknown starts the list over. */
    fun <T> next(values: List<T>, current: T): T {
        val i = values.indexOf(current)
        return values[if (i < 0) 0 else (i + 1) % values.size]
    }

    fun sizeLabel(scale: Float) = "${Math.round(scale * 100)}%"
    fun offsetLabel(dp: Int) = when {
        dp == 0 -> "Default"
        dp > 0 -> "Up ${dp}dp"
        else -> "Down ${-dp}dp"
    }
    fun colorName(options: List<Pair<Int, String>>, color: Int): String =
        options.firstOrNull { it.first == color }?.second ?: "#%08X".format(color)

    /** The value shown on the Settings row, e.g. "125% · Yellow · Outline". */
    fun summary(s: PreferencesManager.SubtitleStyle): String = buildList {
        add(sizeLabel(s.sizeScale))
        add(colorName(TEXT_COLORS, s.textColor))
        if (s.bold) add("Bold")
        if (s.outlineEnabled) add("Outline")
        if (s.backgroundColor != 0) add(colorName(BACKGROUNDS, s.backgroundColor))
    }.joinToString(" · ")

    fun show(activity: AppCompatActivity, prefs: PreferencesManager, onChanged: (PreferencesManager.SubtitleStyle) -> Unit) {
        activity.lifecycleScope.launch {
            var style = prefs.subtitleStyle.first()
            val d = activity.resources.displayMetrics.density
            fun px(dp: Int) = (dp * d).toInt()

            val sample = TextView(activity).apply {
                text = "This is how subtitles will look."
                gravity = Gravity.CENTER
                setPadding(px(10), px(4), px(10), px(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val picture = FrameLayout(activity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF2A3A4A.toInt(), 0xFF101820.toInt()))
                    .apply { cornerRadius = 10 * d }
                addView(sample, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(14) })
            }
            fun paintSample() {
                sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * style.sizeScale)
                sample.setTypeface(null, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
                sample.setTextColor(style.textColor)
                sample.setBackgroundColor(style.backgroundColor)
                if (style.outlineEnabled) sample.setShadowLayer(4f, 0f, 0f, style.outlineColor)
                else sample.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                sample.translationY = -style.verticalOffsetDp * d
            }

            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val rows = mutableListOf<() -> Unit>()
            fun row(label: String, value: () -> String, step: suspend () -> Unit) {
                val view = TextView(activity).apply {
                    setBackgroundResource(R.drawable.rack_row_bg)
                    isFocusable = true
                    isClickable = true
                    minHeight = px(52)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(16), px(8), px(16), px(8))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setTextColor(ContextCompat.getColor(activity, R.color.rack_text))
                }
                val render = {
                    val v = value()
                    view.text = android.text.SpannableString("$label    $v").apply {
                        setSpan(android.text.style.ForegroundColorSpan(
                            ContextCompat.getColor(activity, R.color.rack_text_secondary)),
                            length - v.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    view.contentDescription = "$label, $v. Select to change."
                }
                view.setOnClickListener {
                    activity.lifecycleScope.launch(NonCancellable) {
                        step()
                        style = prefs.subtitleStyle.first()
                        paintSample()
                        rows.forEach { it() }
                        onChanged(style)
                    }
                }
                rows += render
                list.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(4) })
            }

            row("Size", { sizeLabel(style.sizeScale) }) { prefs.setSubtitleSizeScale(next(SIZES, style.sizeScale)) }
            row("Position", { offsetLabel(style.verticalOffsetDp) }) { prefs.setSubtitleVerticalOffsetDp(next(OFFSETS, style.verticalOffsetDp)) }
            row("Bold", { if (style.bold) "On" else "Off" }) { prefs.setSubtitleBold(!style.bold) }
            row("Text color", { colorName(TEXT_COLORS, style.textColor) }) {
                prefs.setSubtitleTextColor(next(TEXT_COLORS.map { it.first }, style.textColor))
            }
            row("Background", { colorName(BACKGROUNDS, style.backgroundColor) }) {
                prefs.setSubtitleBackgroundColor(next(BACKGROUNDS.map { it.first }, style.backgroundColor))
            }
            row("Outline", { if (style.outlineEnabled) "On" else "Off" }) { prefs.setSubtitleOutlineEnabled(!style.outlineEnabled) }
            row("Outline color", { colorName(OUTLINE_COLORS, style.outlineColor) }) {
                prefs.setSubtitleOutlineColor(next(OUTLINE_COLORS.map { it.first }, style.outlineColor))
            }
            val note = TextView(activity).apply {
                text = "Subtitles out of step with the speech? Fix that while watching: open the player menu, then Audio & subtitle sync."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(activity, R.color.rack_text_muted))
                setPadding(px(4), px(12), px(4), 0)
            }

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(8), px(20), px(8))
                addView(picture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(150)))
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(10) })
                addView(note)
            }
            paintSample()
            rows.forEach { it() }

            AlertDialog.Builder(activity)
                .setTitle("Subtitle appearance")
                .setView(ScrollView(activity).apply { addView(content) })
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset") { _, _ ->
                    activity.lifecycleScope.launch(NonCancellable) {
                        prefs.setSubtitleSizeScale(1.0f)
                        prefs.setSubtitleVerticalOffsetDp(0)
                        prefs.setSubtitleBold(false)
                        prefs.setSubtitleTextColor(0xFFFFFFFF.toInt())
                        prefs.setSubtitleBackgroundColor(0x00000000)
                        prefs.setSubtitleOutlineEnabled(true)
                        prefs.setSubtitleOutlineColor(0xFF000000.toInt())
                        onChanged(prefs.subtitleStyle.first())
                    }
                }
                .show()
            list.getChildAt(0)?.requestFocus()
        }
    }
}
