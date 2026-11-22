package com.iptvapp.data.theme

/**
 * The curated accents shown first in Settings → Accent color and in Customize Home → THEME….
 * [end] makes it a left-to-right gradient. Saved the same way as any other accent
 * (PreferencesManager.accentColor / accentColorEnd), so older builds and backups still read them.
 */
object ThemePalette {

    data class AccentPreset(val name: String, val start: String, val end: String? = null) {
        val isGradient: Boolean get() = end != null

        /** Whether the saved accent ([savedStart], [savedEnd], "" for a solid) is this preset. */
        fun matches(savedStart: String?, savedEnd: String?): Boolean =
            start.equals(savedStart?.trim(), ignoreCase = true) &&
                (end ?: "").equals(savedEnd?.trim().orEmpty(), ignoreCase = true)
    }

    /** Cyber Cyan is the app's long-standing default accent (#06B6D4), so nobody's colour moves. */
    val PRESET_ACCENTS = listOf(
        AccentPreset("Cyber Cyan", "#06B6D4"),
        AccentPreset("Emerald Sport", "#10B981"),
        AccentPreset("Neon Amber", "#F59E0B"),
        AccentPreset("Crimson Red", "#EF4444"),
        AccentPreset("Royal Purple", "#8B5CF6"),
        AccentPreset("Electric Magenta", "#EC4899"),
        AccentPreset("Sunset Horizon", "#F59E0B", "#EF4444"),
        AccentPreset("Ocean Wave", "#008CFF", "#10B981"),
    )

    fun presetFor(savedStart: String?, savedEnd: String?): AccentPreset? =
        PRESET_ACCENTS.firstOrNull { it.matches(savedStart, savedEnd) }
}
