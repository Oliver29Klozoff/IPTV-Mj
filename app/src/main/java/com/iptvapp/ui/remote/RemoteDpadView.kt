package com.iptvapp.ui.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The phone remote's D-pad, drawn like the Shield remote's: a round ring with four arrows and a
 * round select button in the middle. A tap on the ring is the arrow on that side (by angle), a tap
 * in the middle is OK. [onPress] gets "up", "down", "left", "right" or "ok".
 */
class RemoteDpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPress: ((String) -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141414") }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2E2E2E")
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F1F1F") }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2A") }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EDEDED") }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDEDED")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var pressed: String? = null
    private val arc = RectF()

    init {
        isClickable = true
        contentDescription = "Arrows and OK"
    }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val outerR get() = min(width, height) / 2f - edgePaint.strokeWidth
    private val innerR get() = outerR * 0.42f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        edgePaint.strokeWidth = resources.displayMetrics.density * 1.5f
        okPaint.textSize = innerR * 0.42f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        // The pressed side lights up, so a tap is visibly a press.
        pressed?.takeIf { it != "ok" }?.let { side ->
            val start = when (side) { "right" -> -45f; "down" -> 45f; "left" -> 135f; else -> 225f }
            arc.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
            canvas.drawArc(arc, start, 90f, true, pressedPaint)
        }
        canvas.drawCircle(cx, cy, outerR, edgePaint)
        canvas.drawCircle(cx, cy, innerR, if (pressed == "ok") pressedPaint else centerPaint)
        canvas.drawCircle(cx, cy, innerR, edgePaint)
        canvas.drawText("OK", cx, cy - (okPaint.descent() + okPaint.ascent()) / 2, okPaint)
        val mid = (outerR + innerR) / 2
        val size = (outerR - innerR) * 0.22f
        drawArrow(canvas, cx, cy - mid, size, 0f)
        drawArrow(canvas, cx + mid, cy, size, 90f)
        drawArrow(canvas, cx, cy + mid, size, 180f)
        drawArrow(canvas, cx - mid, cy, size, 270f)
    }

    /** A small filled triangle pointing up, turned by [degrees]. */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, degrees: Float) {
        canvas.save()
        canvas.rotate(degrees, x, y)
        val p = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size, y + size * 0.6f)
            lineTo(x - size, y + size * 0.6f)
            close()
        }
        canvas.drawPath(p, arrowPaint)
        canvas.restore()
    }

    private fun sideAt(x: Float, y: Float): String? {
        val dx = x - cx
        val dy = y - cy
        val d = hypot(dx, dy)
        if (d > outerR) return null
        if (d <= innerR) return "ok"
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
        return when {
            deg >= -45 && deg < 45 -> "right"
            deg >= 45 && deg < 135 -> "down"
            deg >= -135 && deg < -45 -> "up"
            else -> "left"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = sideAt(event.x, event.y) ?: return false
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val side = pressed
                pressed = null
                invalidate()
                if (side != null && sideAt(event.x, event.y) == side) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onPress?.invoke(side)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
