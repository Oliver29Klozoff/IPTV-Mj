package com.iptvapp.util

import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView

fun Context.isLargeScreenDevice(): Boolean {
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    val smallestWidthDp = resources.configuration.smallestScreenWidthDp
    return hasLeanback || hasTelevision || smallestWidthDp >= 600
}

fun ViewGroup.enableTvFocusHighlight() {
    for (i in 0 until childCount) {
        val child = getChildAt(i)

        if (child is RecyclerView || child is EditText) {
            continue
        }

        if (child is ViewGroup) {
            child.enableTvFocusHighlight()
        }

        if (child.isClickable || child.isFocusable) {
            child.isFocusable = true
            child.isFocusableInTouchMode = false

            child.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.bringToFront()
                }

                view.animate()
                    .scaleX(if (hasFocus) 1.07f else 1.0f)
                    .scaleY(if (hasFocus) 1.07f else 1.0f)
                    .translationZ(if (hasFocus) 28f else 0f)
                    .setDuration(120)
                    .start()

                view.elevation = if (hasFocus) 28f else 0f
                view.alpha = if (hasFocus) 1.0f else 0.92f
            }
        }
    }
}