package com.iptvapp.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Wrap-to-the-next-line layout for the accent-color swatches and gradient chips SettingsActivity
 * builds in code — a row of them doesn't fit one line on a phone.
 *
 * Left-to-right only; this app's UI is English-only.
 */
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
        val maxLineWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight

        var lineWidth = 0
        var lineHeight = 0
        var lineHasItems = false
        var widest = 0
        var totalHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            child.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height)
            )
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineHasItems && lineWidth + spacingPx + childWidth > maxLineWidth) {
                widest = max(widest, lineWidth)
                totalHeight += lineHeight + spacingPx
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth += if (lineHasItems) spacingPx + childWidth else childWidth
                lineHeight = max(lineHeight, childHeight)
            }
            lineHasItems = true
        }
        if (lineHasItems) {
            widest = max(widest, lineWidth)
            totalHeight += lineHeight
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxRight = (r - l) - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var lineHasItems = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineHasItems && x + spacingPx + childWidth > maxRight) {
                x = paddingLeft
                y += lineHeight + spacingPx
                lineHeight = 0
                lineHasItems = false
            }
            if (lineHasItems) x += spacingPx
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += childWidth
            lineHeight = max(lineHeight, childHeight)
            lineHasItems = true
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}

/**
 * A Settings panel's groups, stacked in one column on a phone and split into two side-by-side
 * columns on a wide screen (landscape phone, car box) — how many comes from
 * `R.integer.rack_columns` (values / values-w840dp), so it flips with the same width qualifier as
 * the group box style. The activity is recreated on rotation, so reading it once is enough.
 *
 * Children go into the columns in turn (first left, second right, third left …) rather than into
 * whichever column is shorter: a group growing or shrinking (a disclosure row opening, a provider
 * being removed) then never makes other groups jump between columns. With only one visible child
 * there is nothing to pair it with, so it spans the full width.
 *
 * Accepts any MarginLayoutParams, including the LinearLayout.LayoutParams that
 * SettingsActivity.updateServerList gives its provider cards.
 */
class RackColumns @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private val columnCount = context.resources.getInteger(com.iptvapp.R.integer.rack_columns).coerceAtLeast(1)
    private val gap = context.resources.getDimensionPixelSize(com.iptvapp.R.dimen.rack_column_gap)

    private fun visibleChildren(): List<View> =
        (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }

    private fun columnsFor(visible: List<View>) = if (visible.size < 2) 1 else columnCount

    private fun columnWidth(totalWidth: Int, columns: Int) =
        ((totalWidth - paddingLeft - paddingRight - gap * (columns - 1)) / columns).coerceAtLeast(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val visible = visibleChildren()
        val columns = columnsFor(visible)
        val colWidth = columnWidth(width, columns)
        val heights = IntArray(columns)
        visible.forEachIndexed { i, child ->
            val lp = child.layoutParams as MarginLayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec((colWidth - lp.leftMargin - lp.rightMargin).coerceAtLeast(0), MeasureSpec.EXACTLY),
                getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0,
                    if (lp.height == LayoutParams.MATCH_PARENT) LayoutParams.WRAP_CONTENT else lp.height)
            )
            heights[i % columns] += lp.topMargin + child.measuredHeight + lp.bottomMargin
        }
        setMeasuredDimension(width, resolveSize((heights.maxOrNull() ?: 0) + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val visible = visibleChildren()
        val columns = columnsFor(visible)
        val colWidth = columnWidth(r - l, columns)
        val tops = IntArray(columns) { paddingTop }
        visible.forEachIndexed { i, child ->
            val col = i % columns
            val lp = child.layoutParams as MarginLayoutParams
            val left = paddingLeft + col * (colWidth + gap) + lp.leftMargin
            val top = tops[col] + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            tops[col] = top + child.measuredHeight + lp.bottomMargin
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p is MarginLayoutParams) MarginLayoutParams(p) else MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
