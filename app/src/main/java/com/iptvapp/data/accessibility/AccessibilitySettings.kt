package com.iptvapp.data.accessibility

import androidx.annotation.StringRes
import com.iptvapp.R

/** Settings → Accessibility → Text size. Multiplies the device's own font scale. */
enum class TextSizeLevel(val scale: Float, @StringRes val labelRes: Int) {
    STANDARD(1.0f, R.string.a11y_text_standard),
    LARGE(1.15f, R.string.a11y_text_large),
    EXTRA_LARGE(1.30f, R.string.a11y_text_extra_large);

    companion object {
        /** The level nearest [scale]; anything unreadable or out of range is Standard. */
        fun fromScale(scale: Float?): TextSizeLevel {
            if (scale == null || scale.isNaN() || scale < 0.9f || scale > 1.5f) return STANDARD
            return entries.minBy { kotlin.math.abs(it.scale - scale) }
        }
    }
}

/**
 * High-Visibility (Senior) Mode and the three settings it switches together. Each setting can
 * also be changed on its own; one that was never set follows the master switch.
 */
data class AccessibilitySettings(
    val seniorMode: Boolean = false,
    val textSize: TextSizeLevel = TextSizeLevel.STANDARD,
    val extendedOsd: Boolean = false,
    val highContrastFocus: Boolean = false,
) {
    /** How long the channel banner stays after tuning. */
    val tuneBannerMs: Long get() = if (extendedOsd) EXTENDED_BANNER_MS else STANDARD_BANNER_MS
    /** How long the player controls stay after the last key press or tap. */
    val playerControlsMs: Long get() = if (extendedOsd) EXTENDED_CONTROLS_MS else STANDARD_CONTROLS_MS
    /** Touch targets on phones and car boxes, in dp (0 = the layout's own). */
    val minTouchTargetDp: Int get() = if (seniorMode) SENIOR_TOUCH_TARGET_DP else 0

    /**
     * What decides how a screen's resources are built: a change makes open screens rebuild once
     * when they come back, so the new size reaches them without restarting the app.
     */
    val screenSignature: String get() = "${textSize.name}:$seniorMode"

    companion object {
        const val STANDARD_BANNER_MS = 2_500L
        const val EXTENDED_BANNER_MS = 6_500L
        const val STANDARD_CONTROLS_MS = 5_000L
        const val EXTENDED_CONTROLS_MS = 12_000L
        const val STANDARD_FOCUS_STROKE_DP = 3
        const val HIGH_CONTRAST_FOCUS_STROKE_DP = 4
        const val SENIOR_TOUCH_TARGET_DP = 52

        /** The settings as stored: a value never set follows [seniorMode]. */
        fun resolve(seniorMode: Boolean?, fontScale: Float?, extendedOsd: Boolean?, highContrastFocus: Boolean?): AccessibilitySettings {
            val senior = seniorMode == true
            return AccessibilitySettings(
                seniorMode = senior,
                textSize = if (fontScale == null) (if (senior) TextSizeLevel.EXTRA_LARGE else TextSizeLevel.STANDARD) else TextSizeLevel.fromScale(fontScale),
                extendedOsd = extendedOsd ?: senior,
                highContrastFocus = highContrastFocus ?: senior,
            )
        }

        /** Everything the master switch sets when turned on. */
        val SENIOR = AccessibilitySettings(true, TextSizeLevel.EXTRA_LARGE, extendedOsd = true, highContrastFocus = true)
        /** And when turned off. */
        val STANDARD = AccessibilitySettings()
    }
}
