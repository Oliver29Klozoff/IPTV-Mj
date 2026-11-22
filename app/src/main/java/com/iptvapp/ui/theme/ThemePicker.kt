package com.iptvapp.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.iptvapp.R
import com.iptvapp.data.theme.AppBaseTheme
import com.iptvapp.data.theme.ThemePalette
import com.iptvapp.ui.settings.FlowLayout
import com.iptvapp.util.RackAccent

/**
 * The pieces Settings → Appearance and Customize Home → THEME… share: the three base-theme cards,
 * the curated accent chips and the live preview card (view_theme_preview.xml). Every card and
 * chip is D-pad focusable with the app's light focus outline; the chosen one is framed in the
 * accent instead, so focus and choice never look alike.
 */
object ThemePicker {

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun font(context: Context, res: Int) = runCatching { ResourcesCompat.getFont(context, res) }.getOrNull()

    /** Light outline that only shows under D-pad focus (the foreground, over the choice frame). */
    private fun focusRing(context: Context, cornerDp: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(context, cornerDp).toFloat()
            setStroke(dp(context, 2), context.getColor(R.color.rack_focus_ring))
            setColor(Color.TRANSPARENT)
        })
    }

    /** A 2dp frame in the accent (a gradient ring for a gradient) around [inner]. */
    private fun chosenFrame(context: Context, accent: RackAccent.Accent, inner: Int, cornerDp: Int) = LayerDrawable(arrayOf(
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, accent.stops).apply { cornerRadius = dp(context, cornerDp).toFloat() },
        GradientDrawable().apply { cornerRadius = dp(context, cornerDp - 2).toFloat(); setColor(inner) },
    )).apply { val i = dp(context, 2); setLayerInset(1, i, i, i, i) }

    /** One card per base theme in [container] (cleared first); [onPick] runs on a press. */
    fun buildBaseThemes(
        container: ViewGroup,
        selected: AppBaseTheme,
        accent: RackAccent.Accent,
        onPick: (AppBaseTheme) -> Unit,
    ) {
        val ctx = container.context
        container.removeAllViews()
        val surface = ctx.getColor(R.color.rack_surface)
        AppBaseTheme.entries.forEach { theme ->
            val chosen = theme == selected
            val title = ctx.getString(theme.titleRes)
            val desc = ctx.getString(theme.descRes)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                background = if (chosen) chosenFrame(ctx, accent, surface, 6) else GradientDrawable().apply {
                    cornerRadius = dp(ctx, 6).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(ctx, 1), ctx.getColor(R.color.rack_control_edge))
                }
                foreground = focusRing(ctx, 6)
                isFocusable = true
                isClickable = true
                isActivated = chosen
                contentDescription = ctx.getString(if (chosen) R.string.theme_option_selected else R.string.theme_option, title, desc)
                setOnClickListener { onPick(theme) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(ctx, 6) }
            }
            // The theme in miniature: its screen colour with a card on it.
            card.addView(View(ctx).apply {
                background = LayerDrawable(arrayOf(
                    GradientDrawable().apply {
                        cornerRadius = dp(ctx, 4).toFloat()
                        setColor(theme.backgroundColor)
                        setStroke(dp(ctx, 1), ctx.getColor(R.color.rack_control_edge))
                    },
                    GradientDrawable().apply {
                        cornerRadius = dp(ctx, 2).toFloat()
                        setColor(theme.cardColor)
                        setStroke(dp(ctx, 1), theme.borderColor)
                    },
                )).apply { val i = dp(ctx, 8); setLayerInset(1, i, i, i, i) }
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))
            })
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 12) }
                addView(TextView(ctx).apply {
                    text = if (chosen) "✓ $title" else title
                    textSize = 16f
                    typeface = font(ctx, R.font.barlow_medium)
                    setTextColor(ctx.getColor(R.color.rack_text))
                })
                addView(TextView(ctx).apply {
                    text = desc
                    textSize = 13f
                    typeface = font(ctx, R.font.barlow_medium)
                    setTextColor(ctx.getColor(R.color.rack_text_secondary))
                })
            })
            container.addView(card)
        }
    }

    /**
     * The curated accent chips in a wrapping row ([ThemePalette.PRESET_ACCENTS]); the one matching
     * [savedStart] / [savedEnd] is framed. [onPick] runs on a press.
     */
    fun buildCuratedAccents(
        context: Context,
        savedStart: String,
        savedEnd: String,
        onPick: (ThemePalette.AccentPreset) -> Unit,
    ): FlowLayout {
        val surface = context.getColor(R.color.rack_surface)
        return FlowLayout(context).apply {
            spacingPx = dp(context, 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            ThemePalette.PRESET_ACCENTS.forEach { preset ->
                val accent = accentOf(preset.start, preset.end ?: "")
                val chosen = preset.matches(savedStart, savedEnd)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 44))
                    setPadding(dp(context, 10), 0, dp(context, 14), 0)
                    background = if (chosen) chosenFrame(context, accent, surface, 5) else GradientDrawable().apply {
                        cornerRadius = dp(context, 5).toFloat()
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(context, 1), context.getColor(R.color.rack_control_edge))
                    }
                    foreground = focusRing(context, 5)
                    isFocusable = true
                    isClickable = true
                    isActivated = chosen
                    contentDescription = context.getString(
                        if (chosen) R.string.theme_accent_option_selected else R.string.theme_accent_option, preset.name
                    )
                    setOnClickListener { onPick(preset) }
                    addView(View(context).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, accent.stops).apply {
                            cornerRadius = dp(context, 10).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(context, 20), dp(context, 20))
                    })
                    addView(TextView(context).apply {
                        text = preset.name
                        textSize = 15f
                        typeface = font(context, R.font.barlow_medium)
                        setTextColor(context.getColor(R.color.rack_text))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { marginStart = dp(context, 8) }
                    })
                })
            }
        }
    }

    /** A saved accent as RackAccent paints it (a bad start falls back to the default cyan). */
    fun accentOf(start: String, end: String): RackAccent.Accent = RackAccent.Accent(
        com.iptvapp.util.AccentColors.startOrDefault(start),
        com.iptvapp.util.AccentColors.parseOrNull(end),
    )

    /** The accent's name for the preview's description: a preset's own name, or "your own". */
    fun accentName(context: Context, start: String, end: String): String =
        ThemePalette.presetFor(start, end)?.name ?: context.getString(R.string.theme_custom_accent)

    /**
     * Paints the preview (view_theme_preview.xml, rooted at [preview]) in [theme] and [accent]:
     * screen, card, outline, logo tile, progress bar and Play button. ThemeEngine leaves it alone,
     * so it always shows the theme being chosen, not the one the screen around it is in.
     */
    fun paintPreview(preview: View, theme: AppBaseTheme, accent: RackAccent.Accent, accentName: String) {
        val ctx = preview.context
        preview.setTag(R.id.theme_engine_skip, true)
        preview.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 6).toFloat()
            setColor(theme.backgroundColor)
            setStroke(dp(ctx, 1), ctx.getColor(R.color.rack_control_edge))
        }
        preview.findViewById<View>(R.id.themePreviewCard).background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 6).toFloat()
            setColor(theme.cardColor)
            setStroke(dp(ctx, 1), theme.borderColor)
        }
        preview.findViewById<TextView>(R.id.themePreviewLogo).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 4).toFloat()
                setColor(theme.surfaceColor)
                setStroke(dp(ctx, 1), theme.borderColor)
            }
            com.iptvapp.util.AccentText.apply(this, accent.start, accent.end)
        }
        RackAccent.paintProgress(preview.findViewById<ProgressBar>(R.id.themePreviewProgress), accent)
        RackAccent.paintFillButton(preview.findViewById(R.id.themePreviewPlay), accent)
        preview.contentDescription = ctx.getString(R.string.theme_preview_description, ctx.getString(theme.titleRes), accentName)
    }
}
