package com.iptvapp.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.RackAccent
import com.iptvapp.util.WatchStats
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Watch stats (v7.75): live TV watched on this device over 30 days (WatchStats), the busiest
 * channels, and the channels that keep failing or stalling, so a bad copy is easy to spot.
 * Built in code; every row takes focus so a remote can scroll it.
 */
@AndroidEntryPoint
class WatchStatsActivity : AppCompatActivity() {

    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var prefs: PreferencesManager

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.rack_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        val back = ImageButton(this).apply {
            setBackgroundResource(R.drawable.rack_row_bg)
            setImageResource(R.drawable.ic_player_back)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rack_text))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("WATCH STATS", 20f, R.color.oled_cyan_primary, bold = true).apply { letterSpacing = 0.12f }
        titles.addView(title)
        titles.addView(text("Live TV on this device, the last 30 days", 13f, R.color.rack_text_secondary))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        root.addView(header)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        root.addView(NestedScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lifecycleScope.launch {
            val accent = RackAccent.load(prefs).start
            title.setTextColor(accent)
            val now = System.currentTimeMillis()
            val summary = withContext(Dispatchers.IO) {
                WatchStats.summarize(db.watchLogDao().since(WatchStats.firstDay(now)), now)
            }
            show(summary, accent)
            back.requestFocus()
        }
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        typeface = ResourcesCompat.getFont(this@WatchStatsActivity, if (bold) R.font.barlow_condensed_bold else R.font.barlow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
    }

    private fun section(label: String) {
        content.addView(text(label.uppercase(), 13f, R.color.rack_text_muted, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(dp(4), dp(20), dp(4), dp(6))
        })
    }

    /** A focusable row: [main] on the left, [side] on the right, [detail] under [main]. */
    private fun row(main: String, side: String, detail: String? = null, sideColor: Int = R.color.rack_text) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setBackgroundResource(R.drawable.rack_row_bg)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(main, 16f, R.color.rack_text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        detail?.let { left.addView(text(it, 13f, R.color.rack_text_secondary)) }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(side, 16f, sideColor, bold = true).apply { setPadding(dp(12), 0, 0, 0) })
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
    }

    private fun show(s: WatchStats.Summary, accent: Int) {
        if (s.empty) {
            content.addView(text("Nothing yet. Watch some live TV and your hours, top channels and any channels that keep dropping show up here.",
                16f, R.color.rack_text_secondary).apply { setPadding(dp(4), dp(24), dp(4), 0) })
            return
        }
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(label: String, value: String, note: String) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                setBackgroundResource(R.drawable.rack_row_bg)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            t.addView(text(label.uppercase(), 12f, R.color.rack_text_muted, bold = true).apply { letterSpacing = 0.12f })
            t.addView(text(value, 22f, R.color.rack_text, bold = true).apply { setTextColor(accent) })
            t.addView(text(note, 12f, R.color.rack_text_secondary))
            tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        val change = when {
            s.lastWeekMinutes <= 0f -> "No viewing the week before"
            else -> {
                val pct = ((s.thisWeekMinutes - s.lastWeekMinutes) / s.lastWeekMinutes * 100).toInt()
                if (pct >= 0) "Up $pct% on the week before" else "Down ${-pct}% on the week before"
            }
        }
        tile("This week", WatchStats.duration(s.thisWeekMinutes), change)
        tile("30 days", WatchStats.duration(s.monthMinutes), "On ${s.activeDays} of 30 days")
        content.addView(tiles)

        section("The last 14 days")
        content.addView(DayBars(this, s.days, accent, getColor(R.color.rack_text_muted)).apply { isFocusable = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))

        if (s.topChannels.isNotEmpty()) {
            section("Most watched")
            s.topChannels.forEachIndexed { i, c -> row("${i + 1}.  ${c.name}", WatchStats.duration(c.minutes)) }
        }

        section("Channels that dropped or stalled")
        if (s.trouble.isEmpty()) row("None in 30 days", "")
        else s.trouble.forEach { c ->
            val parts = mutableListOf<String>()
            if (c.failures > 0) parts += "${c.failures} failed or dropped"
            if (c.minutes >= 10f && c.stallsPerHour >= 0.1f) parts += "%.1f stalls an hour".format(c.stallsPerHour)
            val provider = when {
                c.serverIndex == null || c.serverIndex == -1 -> "Main provider"
                c.serverIndex < -1 -> "A provider since changed"
                else -> "Provider ${c.serverIndex + 1}"
            }
            row(c.name, "${c.starts} starts", "$provider · ${parts.joinToString(" · ")}", R.color.rack_text_secondary)
        }
        content.addView(text("A channel that keeps dropping often plays better from another copy: hold OK on it in a channel list, choose All Sources and pick another.",
            13f, R.color.rack_text_muted).apply { setPadding(dp(4), dp(10), dp(4), 0) })
    }

    /** Minutes per day as bars, today on the right, with the day's initial under each. */
    private class DayBars(context: Context, private val days: List<Pair<String, Float>>, private val accent: Int, private val muted: Int) : View(context) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; alpha = 70 }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textAlign = Paint.Align.CENTER
            textSize = 11f * context.resources.displayMetrics.scaledDensity
        }
        private val rect = RectF()
        private val weekdays = days.map { (day, _) ->
            runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(day)
                    ?.let { java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault()).format(it) }
            }.getOrNull() ?: ""
        }

        init {
            contentDescription = days.joinToString { (d, m) -> "$d ${WatchStats.duration(m)}" }
            setBackgroundResource(R.drawable.rack_row_bg)
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val pad = 12 * d
            val labelH = 16 * d
            val most = days.maxOf { it.second }.coerceAtLeast(1f)
            val slot = (width - 2 * pad) / days.size
            val barW = slot * 0.6f
            val bottom = height - pad - labelH
            val top = pad
            days.forEachIndexed { i, (_, minutes) ->
                val cx = pad + slot * i + slot / 2
                val h = if (minutes <= 0f) 2 * d else ((bottom - top) * minutes / most).coerceAtLeast(3 * d)
                rect.set(cx - barW / 2, bottom - h, cx + barW / 2, bottom)
                canvas.drawRoundRect(rect, 3 * d, 3 * d, if (minutes > 0f) bar else empty)
                canvas.drawText(weekdays[i], cx, height - pad, label)
            }
        }
    }
}
