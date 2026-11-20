package com.iptvapp.data.home

/**
 * How the TV Home shows its mini player (Customize Home → Home Mini Player Display). [key] is
 * what PreferencesManager saves; anything unknown, including no saved value, reads as [HERO].
 */
enum class TvHomeMiniMode(val key: String, val label: String) {
    /** The large picture with one row under it that slides row by row (the layout since v7.82). */
    HERO("hero", "Hero (Large Preview)"),
    /** The picture and the rows share the column half and half. */
    COMPACT("compact", "Compact (Classic 50/50)"),
    /** No picture on Home: nothing plays there and the rows fill the column. */
    OFF("off", "Off (Disabled - Full Height Rows)");

    companion object {
        fun of(key: String?): TvHomeMiniMode = entries.firstOrNull { it.key == key } ?: HERO
    }
}
