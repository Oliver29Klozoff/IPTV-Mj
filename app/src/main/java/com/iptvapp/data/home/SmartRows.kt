package com.iptvapp.data.home

/**
 * Customize Home → Smart Rows: the rules for MKTV's automatic rows (HomeLayout.Builtin), on TV and
 * phone alike. Rows people make keep their own items; only a category row takes [Settings.limit].
 */
object SmartRows {

    /** Cards per automatic row the viewer can pick. */
    val LIMITS = listOf(10, 20, 30)
    const val DEFAULT_LIMIT = 20

    /** Continue Watching leaves out anything watched this far (percent), when [Settings.hideCompleted] is on. */
    const val COMPLETED_PERCENT = 90

    /**
     * [limit] cards per automatic row; [autoCollapseEmpty] leaves every empty row off Home, even
     * one set to show a hint while empty; [hideCompleted] leaves 90%-watched items out of
     * Continue Watching. The defaults are what a new or updated install gets.
     */
    data class Settings(
        val limit: Int = DEFAULT_LIMIT,
        val autoCollapseEmpty: Boolean = true,
        val hideCompleted: Boolean = true,
    )

    /** A saved limit, or the default when there is none or it isn't one of [LIMITS]. */
    fun normalizeLimit(v: Int?): Int = v?.takeIf { it in LIMITS } ?: DEFAULT_LIMIT

    /** MKTV's own "watched" mark (Continue Watching's cut-off without the Smart Rows rule). */
    const val WATCHED_PERCENT = 95

    /** The share of an item's length that counts as finished for Continue Watching. */
    fun finishedRatio(hideCompleted: Boolean): Double = (if (hideCompleted) COMPLETED_PERCENT else WATCHED_PERCENT) / 100.0

    /** Watched [COMPLETED_PERCENT] or more of it. Unknown length counts as not finished. */
    fun isFinished(watchedMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && watchedMs * 100 >= durationMs * COMPLETED_PERCENT

    /** Whether an empty row stays on Home: never with [Settings.autoCollapseEmpty], otherwise the row's own choice. */
    fun keepsEmpty(settings: Settings, section: HomeLayout.Section): Boolean =
        !settings.autoCollapseEmpty && section.showWhenEmpty
}
