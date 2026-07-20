package com.iptvapp.ui.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Full-screen dimmed scrim with a rounded-rect hole punched around a target view's real screen
 * bounds — the spotlight itself. Caption/controls are a separate view added by TourController,
 * since their position (above/below the hole) depends on where the hole ends up on screen.
 * Uses a hardware-layer-disabled Paint xfermode punch (CLEAR), which requires this view's own
 * layer type be software for the clear blend to actually show the dimmed layer through, not
 * the window background behind it.
 */
class SpotlightOverlayView(context: Context) : View(context) {

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        isAntiAlias = true
    }
    private val holePaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var holeRect: RectF? = null
    private var holeRadius = 0f
    private var animator: ValueAnimator? = null
    private var animatedRect: RectF? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Animates the hole from its current position to [target] (screen coords), or snaps
     * instantly if there's no previous hole (first step). Padding expands the hole beyond the
     * view's exact bounds so the spotlight doesn't hug edges too tightly. */
    fun moveHoleTo(target: Rect, paddingPx: Float, cornerRadiusPx: Float) {
        val newRect = RectF(
            target.left - paddingPx, target.top - paddingPx,
            target.right + paddingPx, target.bottom + paddingPx
        )
        holeRadius = cornerRadiusPx
        val from = animatedRect ?: newRect
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (animatedRect == null) 0L else 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                animatedRect = RectF(
                    from.left + (newRect.left - from.left) * f,
                    from.top + (newRect.top - from.top) * f,
                    from.right + (newRect.right - from.right) * f,
                    from.bottom + (newRect.bottom - from.bottom) * f
                )
                holeRect = animatedRect
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        holeRect?.let { canvas.drawRoundRect(it, holeRadius, holeRadius, holePaint) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
