package com.iptvapp.ui.recordings

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.iptvapp.data.local.entities.RecordingEntity
import java.util.Calendar

/** Classic DVR-style day schedule: a vertical column of 24 hour gridlines with recording blocks
 * positioned/sized by their actual time within the day, color-coded by status (same mapping as
 * the flat list's RecordingAdapter/RecordingListAdapter). No canvas drawing — hour rows and
 * blocks are plain child Views placed with FrameLayout.LayoutParams, same "compute a pixel
 * offset from a time value" idea the EPG timeline uses for its horizontal blocks, just applied
 * to the vertical axis here since a day-grid's natural axis is top-to-bottom.
 *
 * Purely a rendering + tap-dispatch component — every action (play/retry/delete/rename) is
 * handled by the caller via onBlockClick; this view has no recording business logic itself. */
class DayScheduleView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    companion object {
        private const val HOURS_IN_DAY = 24

        /** Midnight (local time) for the day [offsetDays] away from today. */
        fun dayStartMsForOffset(offsetDays: Int): Long {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, offsetDays)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    var onBlockClick: ((RecordingEntity) -> Unit)? = null
    var onBlockLongClick: ((RecordingEntity) -> Unit)? = null

    private val hourHeightPx = dp(64f)
    private val labelWidthPx = dp(48f)
    private val minBlockHeightPx = dp(28f)

    private val gridContainer = FrameLayout(context)
    private val nowLine = View(context).apply {
        setBackgroundColor(0xFFFF4444.toInt())
        visibility = View.GONE
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    init {
        addView(gridContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buildHourRows()
        gridContainer.addView(nowLine, LayoutParams(LayoutParams.MATCH_PARENT, dp(2f)))
    }

    private fun buildHourRows() {
        for (hour in 0 until HOURS_IN_DAY) {
            val row = View(context).apply {
                setBackgroundColor(0xFF1E2A3A.toInt())
            }
            gridContainer.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)).apply { topMargin = hour * hourHeightPx }
            )
            val label = TextView(context).apply {
                text = hourLabel(hour)
                textSize = 11f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.START or Gravity.TOP
            }
            gridContainer.addView(
                label,
                LayoutParams(labelWidthPx, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = hour * hourHeightPx + dp(2f)
                    leftMargin = dp(4f)
                }
            )
        }
        gridContainer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, hourHeightPx * HOURS_IN_DAY)
    }

    private fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }

    /** [dayStartMs] must be midnight (local time) of the day being shown. [recordings] should
     * already be filtered to ones overlapping this day — this view just lays out what it's given. */
    fun submitDay(dayStartMs: Long, recordings: List<RecordingEntity>, isToday: Boolean) {
        // Remove previously-added blocks (everything past the fixed hour rows + labels + now-line,
        // i.e. views added in the block loop below on a prior call).
        val fixedChildCount = HOURS_IN_DAY * 2 + 1 // hour line + label per hour, plus the now-line
        while (gridContainer.childCount > fixedChildCount) {
            gridContainer.removeViewAt(gridContainer.childCount - 1)
        }

        val dayEndMs = dayStartMs + 24 * 60 * 60_000L
        val overlapping = recordings.filter { it.scheduledStartMs < dayEndMs && it.scheduledStartMs + it.durationMs > dayStartMs }

        // Overlapping-in-time recordings lay out side-by-side rather than stacking illegibly —
        // greedily assign each block to the first "lane" (column) whose last-placed block ends
        // before this one starts, opening a new lane otherwise. Same conflict case already
        // flagged to the user at scheduling time (getOverlappingRecordings); this just visualizes it.
        val sorted = overlapping.sortedBy { it.scheduledStartMs }
        val laneEndTimes = mutableListOf<Long>()
        val laneOf = mutableMapOf<Int, Int>()
        sorted.forEach { rec ->
            val laneIdx = laneEndTimes.indexOfFirst { it <= rec.scheduledStartMs }
            if (laneIdx >= 0) {
                laneEndTimes[laneIdx] = rec.scheduledStartMs + rec.durationMs
                laneOf[rec.id] = laneIdx
            } else {
                laneEndTimes.add(rec.scheduledStartMs + rec.durationMs)
                laneOf[rec.id] = laneEndTimes.size - 1
            }
        }
        val laneCount = laneEndTimes.size.coerceAtLeast(1)

        sorted.forEach { rec ->
            val clampedStart = rec.scheduledStartMs.coerceAtLeast(dayStartMs)
            val clampedEnd = (rec.scheduledStartMs + rec.durationMs).coerceAtMost(dayEndMs)
            val startMinutes = (clampedStart - dayStartMs) / 60_000f
            val durationMinutes = (clampedEnd - clampedStart) / 60_000f

            val topPx = (startMinutes / 60f * hourHeightPx).toInt()
            val heightPx = (durationMinutes / 60f * hourHeightPx).toInt().coerceAtLeast(minBlockHeightPx)
            val lane = laneOf[rec.id] ?: 0

            val (bg, fg) = colorsFor(rec.status)
            val block = TextView(context).apply {
                text = rec.channelName
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                setTextColor(fg)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bg)
                    setStroke(dp(1f), fg)
                }
                setOnClickListener { onBlockClick?.invoke(rec) }
                setOnLongClickListener { onBlockLongClick?.invoke(rec); true }
                // Explicit rather than relying on the clickable-implies-focusable default —
                // needed for D-pad navigation between blocks on TV.
                isFocusable = true
                isFocusableInTouchMode = false
            }
            gridContainer.addView(block, LayoutParams(LayoutParams.MATCH_PARENT, heightPx).apply {
                topMargin = topPx
                leftMargin = labelWidthPx + dp(4f)
                // Lane splitting: recompute width/left as a fraction of the space right of the
                // hour labels once laneCount is known. Post to run after layout has a real width.
            })
            post {
                val totalWidth = width - labelWidthPx - dp(8f)
                if (totalWidth > 0) {
                    val lp = block.layoutParams as LayoutParams
                    lp.width = totalWidth / laneCount
                    lp.leftMargin = labelWidthPx + dp(4f) + lane * (totalWidth / laneCount)
                    block.layoutParams = lp
                }
            }
        }

        if (isToday) {
            val nowMinutes = (System.currentTimeMillis() - dayStartMs) / 60_000f
            if (nowMinutes in 0f..(24 * 60f)) {
                nowLine.visibility = View.VISIBLE
                val lp = nowLine.layoutParams as LayoutParams
                lp.topMargin = (nowMinutes / 60f * hourHeightPx).toInt()
                nowLine.layoutParams = lp
            } else {
                nowLine.visibility = View.GONE
            }
        } else {
            nowLine.visibility = View.GONE
        }
    }

    private fun colorsFor(status: String): Pair<Int, Int> = when (status) {
        "RECORDING"   -> 0x33FF4444.toInt() to 0xFFFF4444.toInt()
        "COMPRESSING" -> 0x33AF52DE.toInt() to 0xFFAF52DE.toInt()
        "DONE"        -> 0x3300CC66.toInt() to 0xFF00CC66.toInt()
        "FAILED"      -> 0x33FF8800.toInt() to 0xFFFF8800.toInt()
        else          -> 0x33008CFF.toInt() to 0xFF008CFF.toInt()
    }
}
