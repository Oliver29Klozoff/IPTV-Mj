package com.iptvapp.util

import android.graphics.Color

/**
 * The saved accent colour is a string (#RRGGBB, #AARRGGBB or a colour name Android knows). It can
 * be anything after a backup restore or an older build, and Color.parseColor throws on bad input —
 * which crashed the home screen and Settings on every open. Read it through here instead.
 */
object AccentColors {
    /** The accent setting's own default (PreferencesManager.accentColor). */
    const val DEFAULT_HEX = "#06B6D4"

    /** The colour, or null when [value] is empty or not a colour. */
    fun parseOrNull(value: String?): Int? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        return try { Color.parseColor(v) } catch (_: IllegalArgumentException) { null } catch (_: StringIndexOutOfBoundsException) { null }
    }

    /** The saved start colour, or the default when it can't be read. */
    fun startOrDefault(value: String?): Int = parseOrNull(value) ?: Color.parseColor(DEFAULT_HEX)

    /** [value] if it is a valid colour string, else [fallback] (a valid choice is never changed). */
    fun validOr(value: String?, fallback: String): String = if (parseOrNull(value) != null) value!!.trim() else fallback
}
