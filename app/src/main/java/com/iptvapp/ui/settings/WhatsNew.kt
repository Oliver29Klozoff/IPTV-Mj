package com.iptvapp.ui.settings

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R

/**
 * What's New: the bundled CHANGELOG.md as one card per release, newest first. The version and
 * date on top, an "Installed" tag on the one running, and each note as a bullet in large, light
 * text. Neutral greys and white, like the Rack screens; no colored glow.
 */
object WhatsNew {

    data class Line(val text: String, val bullet: Boolean, val indent: Int)
    data class Release(val version: String, val date: String?, val lines: List<Line>)

    private val HEADER = Regex("""^##\s+v?(.+?)(?:\s+-\s+(\d{4}-\d{2}-\d{2})\b.*)?$""")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Splits the changelog into releases; a note's wrapped lines join back into one. */
    fun parse(markdown: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: String? = null
        var date: String? = null
        val lines = mutableListOf<Line>()
        fun flush() {
            version?.let { releases += Release(it, date, lines.toList()) }
            lines.clear()
        }
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val header = HEADER.find(raw.trimEnd())
            if (raw.startsWith("## ") && header != null) {
                flush()
                version = header.groupValues[1].trim()
                date = header.groupValues[2].ifEmpty { null }
                continue
            }
            if (version == null || raw.isBlank() || raw.startsWith("#")) continue
            val lead = raw.length - raw.trimStart().length
            val body = raw.trim()
            if (body.startsWith("- ") || body.startsWith("* ")) {
                lines += Line(body.substring(2).trim(), bullet = true, indent = if (lead >= 2) 1 else 0)
            } else if (lines.isNotEmpty() && lead > 0) {
                val last = lines.removeAt(lines.lastIndex)
                lines += last.copy(text = last.text + " " + body)
            } else {
                lines += Line(body, bullet = false, indent = 0)
            }
        }
        flush()
        return releases
    }

    /** "2026-11-23" → "Nov 23, 2026" (anything else as it was). */
    fun prettyDate(iso: String?): String? {
        val parts = iso?.split('-') ?: return null
        val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return iso
        val day = parts.getOrNull(2)?.toIntOrNull() ?: return iso
        return "${MONTHS[month - 1]} $day, ${parts[0]}"
    }

    /** `**bold**` as bold white, `code` without its backticks. */
    fun styled(text: String, strongColor: Int): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    out.append(text, i + 2, end)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(strongColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '`') { i++; continue }
            out.append(text[i])
            i++
        }
        return out
    }

    fun load(activity: Activity): List<Release> = try {
        parse(activity.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() })
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Every release ([onlyVersion] null), or just that one with a button to the full history
     * (shown once after an update).
     */
    fun show(activity: Activity, installedVersion: String?, onlyVersion: String? = null): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val all = load(activity)
        val releases = if (onlyVersion != null) all.filter { it.version == onlyVersion } else all
        if (releases.isEmpty()) return false
        val res = activity.resources
        val d = res.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun color(id: Int) = ContextCompat.getColor(activity, id)
        val heading = ResourcesCompat.getFont(activity, R.font.barlow_condensed_bold)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(R.color.rack_surface))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), color(R.color.rack_control_edge))
            }
        }
        val title = TextView(activity).apply {
            text = if (onlyVersion != null) activity.getString(R.string.whats_new_in, onlyVersion) else activity.getString(R.string.whats_new_title)
            typeface = heading
            setTextColor(color(R.color.rack_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun button(textRes: Int, onClick: () -> Unit) = TextView(activity).apply {
            setText(textRes)
            typeface = heading
            isAllCaps = true
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(color(R.color.rack_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isFocusable = true
            isClickable = true
            background = focusBackground(activity, dp(10).toFloat(), outlined = true)
            setOnClickListener { onClick() }
        }
        val close = button(R.string.whats_new_close) { dialog.dismiss() }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(16), dp(14), dp(12))
            addView(title)
            addView(close)
        })
        root.addView(View(activity).apply {
            setBackgroundColor(color(R.color.rack_border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val screenH = res.displayMetrics.heightPixels
        val list = MaxHeightList(activity, (screenH * 0.72f).toInt()).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = Adapter(releases, installedVersion)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            isVerticalScrollBarEnabled = true
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (onlyVersion != null) ViewGroup.LayoutParams.WRAP_CONTENT else (screenH * 0.72f).toInt(),
        ))

        if (onlyVersion != null) {
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(14), dp(4), dp(14), dp(14))
                addView(button(R.string.whats_new_all) {
                    dialog.dismiss()
                    show(activity, installedVersion)
                })
            })
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = minOf(res.displayMetrics.widthPixels - dp(32), dp(640))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // The remote starts on the newest release; Down walks the releases, Up reaches Close.
        dialog.setOnShowListener { list.post { list.getChildAt(0)?.requestFocus() ?: close.requestFocus() } }
        dialog.show()
        return true
    }

    /** A list as tall as its releases, up to [maxHeight] (then it scrolls). */
    private class MaxHeightList(context: android.content.Context, val maxHeight: Int) : RecyclerView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val mode = View.MeasureSpec.getMode(heightSpec)
            val size = View.MeasureSpec.getSize(heightSpec)
            val cap = if (mode == View.MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }
    }

    /** A rounded panel ringed in white while the remote is on it (nothing while touched). */
    private fun focusBackground(activity: Activity, radius: Float, outlined: Boolean): StateListDrawable {
        val d = activity.resources.displayMetrics.density
        fun shape(fill: Int, stroke: Int?, width: Float) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke((width * d).toInt(), stroke)
        }
        val ring = ContextCompat.getColor(activity, R.color.rack_focus_ring)
        val focusFill = ContextCompat.getColor(activity, R.color.rack_focus_fill)
        val edge = ContextCompat.getColor(activity, R.color.rack_control_edge)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focusFill, ring, 2f))
            addState(intArrayOf(android.R.attr.state_pressed), shape(focusFill, if (outlined) edge else null, 1f))
            addState(intArrayOf(), shape(Color.TRANSPARENT, if (outlined) edge else null, 1f))
        }
    }

    private class Holder(val view: LinearLayout) : RecyclerView.ViewHolder(view)

    private class Adapter(val releases: List<Release>, val installed: String?) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = releases.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val activity = parent.context as Activity
            val d = parent.resources.displayMetrics.density
            return Holder(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (16 * d).toInt())
                isFocusable = true
                background = focusBackground(activity, 12 * d, outlined = false)
            })
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = releases[position]
            val box = holder.view
            val ctx = box.context
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            fun color(id: Int) = ContextCompat.getColor(ctx, id)
            val strong = color(R.color.rack_text)
            val body = 0xFFD6D6D6.toInt()
            box.removeAllViews()

            val isInstalled = r.version == installed
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = "v${r.version}"
                    typeface = ResourcesCompat.getFont(ctx, R.font.barlow_condensed_bold)
                    setTextColor(strong)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                })
                prettyDate(r.date)?.let { date ->
                    addView(TextView(ctx).apply {
                        text = date
                        setTextColor(color(R.color.rack_text_muted))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(dp(12), 0, 0, 0)
                    })
                }
                if (isInstalled) addView(TextView(ctx).apply {
                    setText(R.string.whats_new_installed)
                    isAllCaps = true
                    letterSpacing = 0.08f
                    typeface = ResourcesCompat.getFont(ctx, R.font.barlow_condensed_bold)
                    setTextColor(strong)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(10), dp(2), dp(10), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(color(R.color.rack_control_off))
                        setStroke(dp(1), color(R.color.rack_text_secondary))
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(12) }
                })
            })

            for (line in r.lines) {
                val text = TextView(ctx).apply {
                    this.text = styled(line.text, strong)
                    setTextColor(body)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setLineSpacing(0f, 1.3f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                box.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4 + 18 * line.indent), dp(8), 0, 0)
                    if (line.bullet) addView(TextView(ctx).apply {
                        this.text = if (line.indent > 0) "◦" else "•"
                        setTextColor(color(R.color.rack_text_secondary))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setLineSpacing(0f, 1.3f)
                        layoutParams = LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    addView(text)
                })
            }
            if (position < releases.lastIndex) {
                box.addView(View(ctx).apply {
                    setBackgroundColor(color(R.color.rack_divider))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(16) }
                })
            }
            box.contentDescription = null
        }
    }
}
