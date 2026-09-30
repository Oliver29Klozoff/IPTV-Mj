package com.iptvapp.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import kotlin.math.max

/**
 * Wrap-to-the-next-line layout for Settings' chip rows (v6.79 Stitch layout).
 *
 * The chip look needs rows that wrap — "Off / Every 6 hours / Every 12 hours / Daily" doesn't fit
 * one line in a phone's content pane — and a plain RadioGroup is a LinearLayout, so it can only
 * lay its buttons out in one straight line. [FlowRadioGroup] keeps everything RadioGroup does
 * (mutual exclusion, check listeners, `check()`, the same ids SettingsActivity already binds to)
 * and only replaces how its children are measured and placed.
 *
 * [FlowLayout] is the same arrangement for things that aren't radio buttons — the accent-color
 * swatches and gradient chips SettingsActivity builds in code.
 *
 * Left-to-right only; this app's UI is English-only.
 */
private fun measureFlow(group: ViewGroup, widthMeasureSpec: Int, heightMeasureSpec: Int, spacing: Int): IntArray {
    val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
    val maxLineWidth = if (widthMode == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else View.MeasureSpec.getSize(widthMeasureSpec) - group.paddingLeft - group.paddingRight

    var lineWidth = 0
    var lineHeight = 0
    var lineHasItems = false
    var widest = 0
    var totalHeight = 0
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        if (child.visibility == View.GONE) continue
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        child.measure(
            ViewGroup.getChildMeasureSpec(widthMeasureSpec,
                group.paddingLeft + group.paddingRight + lp.leftMargin + lp.rightMargin, lp.width),
            ViewGroup.getChildMeasureSpec(heightMeasureSpec,
                group.paddingTop + group.paddingBottom + lp.topMargin + lp.bottomMargin, lp.height)
        )
        val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
        val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
        if (lineHasItems && lineWidth + spacing + childWidth > maxLineWidth) {
            widest = max(widest, lineWidth)
            totalHeight += lineHeight + spacing
            lineWidth = childWidth
            lineHeight = childHeight
        } else {
            lineWidth += if (lineHasItems) spacing + childWidth else childWidth
            lineHeight = max(lineHeight, childHeight)
        }
        lineHasItems = true
    }
    if (lineHasItems) {
        widest = max(widest, lineWidth)
        totalHeight += lineHeight
    }
    return intArrayOf(
        View.resolveSize(widest + group.paddingLeft + group.paddingRight, widthMeasureSpec),
        View.resolveSize(totalHeight + group.paddingTop + group.paddingBottom, heightMeasureSpec)
    )
}

private fun layoutFlow(group: ViewGroup, width: Int, spacing: Int) {
    val maxRight = width - group.paddingRight
    var x = group.paddingLeft
    var y = group.paddingTop
    var lineHeight = 0
    var lineHasItems = false
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        if (child.visibility == View.GONE) continue
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
        val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
        if (lineHasItems && x + spacing + childWidth > maxRight) {
            x = group.paddingLeft
            y += lineHeight + spacing
            lineHeight = 0
            lineHasItems = false
        }
        if (lineHasItems) x += spacing
        val left = x + lp.leftMargin
        val top = y + lp.topMargin
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        x += childWidth
        lineHeight = max(lineHeight, childHeight)
        lineHasItems = true
    }
}

class FlowRadioGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RadioGroup(context, attrs) {

    private val spacing = (8 * resources.displayMetrics.density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = measureFlow(this, widthMeasureSpec, heightMeasureSpec, spacing)
        setMeasuredDimension(size[0], size[1])
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutFlow(this, r - l, spacing)
    }
}

class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var spacingPx: Int = (8 * resources.displayMetrics.density + 0.5f).toInt()
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = measureFlow(this, widthMeasureSpec, heightMeasureSpec, spacingPx)
        setMeasuredDimension(size[0], size[1])
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutFlow(this, r - l, spacingPx)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
