package com.iptvapp.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.iptvapp.util.CommercialMarkers

/** Thin ranges drawn above the recording seek bar. It does not take touches or focus. */
class CommercialMarkerBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var segments: List<CommercialMarkers.Segment> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var durationMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    private val confirmed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB020.toInt() }
    private val suggested = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFB020 }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val duration = durationMs
        if (duration <= 0L || width <= 0) return
        val top = 0f
        val bottom = height.toFloat()
        for (segment in segments) {
            if (segment.review == CommercialMarkers.Review.REJECTED) continue
            val start = CommercialMarkers.fraction(segment.startMs, duration) * width
            val end = CommercialMarkers.fraction(segment.endMs, duration) * width
            if (end <= start) continue
            val paint = if (segment.review == CommercialMarkers.Review.CONFIRMED) confirmed else suggested
            canvas.drawRect(start, top, end, bottom, paint)
        }
    }
}
