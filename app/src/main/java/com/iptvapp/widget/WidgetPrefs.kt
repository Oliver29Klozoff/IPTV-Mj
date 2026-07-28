package com.iptvapp.widget

import android.content.Context

// Widgets are per-instance (the same widget type can be added to the home screen multiple
// times, each with its own appWidgetId), so a per-widget channel selection can't live in the
// app's normal DataStore Preferences singleton — it needs to be keyed by widget id. Plain
// SharedPreferences is the standard Android pattern for this (same approach the platform's own
// AppWidgetConfigureActivity samples use), not the DataStore used everywhere else in this app.
object WidgetPrefs {
    private const val PREFS_NAME = "widget_channel_selection"
    private const val KEY_PREFIX = "widget_"
    private const val MODE_KEY_PREFIX = "widget_mode_"

    const val MODE_LIVE = "live"
    const val MODE_CONTINUE_WATCHING = "continue_watching"

    // null = no explicit selection saved yet (falls back to "first 10 favorites", the original
    // hardcoded behavior) — only non-null once the user has actually gone through Configure.
    fun getSelectedStreamIds(context: Context, widgetId: Int): Set<Int>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_PREFIX + widgetId, null) ?: return null
        return stored.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setSelectedStreamIds(context: Context, widgetId: Int, streamIds: Set<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_PREFIX + widgetId, streamIds.map { it.toString() }.toSet())
            .apply()
    }

    // Distinct from clear() below — this only resets the channel selection (used when the user
    // saves Configure with nothing checked, falling back to "first 10 favorites"), without also
    // wiping the mode the user just chose on the same screen.
    fun clearSelectedStreamIds(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PREFIX + widgetId)
            .apply()
    }

    // Defaults to MODE_LIVE so widgets added before this feature existed keep their original
    // behavior unchanged.
    fun getMode(context: Context, widgetId: Int): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MODE_KEY_PREFIX + widgetId, MODE_LIVE) ?: MODE_LIVE

    fun setMode(context: Context, widgetId: Int, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(MODE_KEY_PREFIX + widgetId, mode)
            .apply()
    }

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PREFIX + widgetId)
            .remove(MODE_KEY_PREFIX + widgetId)
            .apply()
    }
}
