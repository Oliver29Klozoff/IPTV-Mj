package com.iptvapp.util

import android.content.Context
import android.content.res.Configuration

/** Settings → Accessibility → Text size: a screen's context with its font scale multiplied. */
object AccessibilityContextWrapper {

    /**
     * [base] with its font scale times [scaleMultiplier] (on top of the device's own setting).
     * Every text size in the app is in sp, so this reaches all of it. 1.0 returns [base] itself.
     */
    fun wrap(base: Context, scaleMultiplier: Float): Context {
        if (scaleMultiplier == 1.0f) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = base.resources.configuration.fontScale * scaleMultiplier
        return base.createConfigurationContext(config)
    }
}
