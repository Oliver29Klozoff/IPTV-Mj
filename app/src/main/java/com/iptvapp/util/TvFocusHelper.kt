package com.iptvapp.util

import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView

// A dedicated plain SharedPreferences flag (not the DataStore-backed PreferencesManager used
// everywhere else) — isLargeScreenDevice() is called synchronously from several activities'
// onCreate (SplashActivity's routing decision in particular, before any coroutine/suspend
// context exists), so the override needs to be readable with zero async ceremony.
private const val FORCE_TV_MODE_PREFS = "force_tv_mode_prefs"
private const val KEY_FORCE_TV_MODE = "force_tv_mode"

fun Context.isForceTvModeEnabled(): Boolean =
    getSharedPreferences(FORCE_TV_MODE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_TV_MODE, false)

fun Context.setForceTvModeEnabled(enabled: Boolean) {
    getSharedPreferences(FORCE_TV_MODE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FORCE_TV_MODE, enabled).apply()
}

// Real hardware/screen-size detection, same as before — isLargeScreenDevice() below layers the
// manual Force TV Mode override on top so a "car box" device that reports as a phone (no
// FEATURE_LEANBACK/FEATURE_TELEVISION, smallestWidthDp < 600) can still be pointed at the TV UI
// deliberately, without needing new detection heuristics for every such device.
private fun Context.detectLargeScreenDevice(): Boolean {
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    val smallestWidthDp = resources.configuration.smallestScreenWidthDp
    return hasLeanback || hasTelevision || smallestWidthDp >= 600
}

fun Context.isLargeScreenDevice(): Boolean = isForceTvModeEnabled() || detectLargeScreenDevice()

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