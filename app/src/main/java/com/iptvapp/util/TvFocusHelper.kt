package com.iptvapp.util

import android.view.ViewGroup

fun ViewGroup.enableTvFocusHighlight() {
    for (i in 0 until childCount) {
        val child = getChildAt(i)

        if (child is ViewGroup) {
            child.enableTvFocusHighlight()
        }

        if (child.isClickable || child.isFocusable) {
            child.isFocusable = true
            child.isFocusableInTouchMode = false

            child.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.08f else 1.0f)
                    .scaleY(if (hasFocus) 1.08f else 1.0f)
                    .setDuration(120)
                    .start()

                view.elevation = if (hasFocus) 24f else 0f
                view.alpha = if (hasFocus) 1.0f else 0.9f
            }
        }
    }
}