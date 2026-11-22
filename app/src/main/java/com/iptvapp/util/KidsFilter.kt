package com.iptvapp.util

/**
 * Kids profiles: which categories and titles are for adults. Matched on the provider's own
 * category names (and, for search and Home rows, titles), so it can only hide what is labelled.
 */
object KidsFilter {
    private val BLOCKED = Regex(
        """(^|[^a-z0-9])(xxx|adults?|18\s?\+|\+\s?18|erotic|erotica|nsfw|porn\w*|playboy|hustler|brazzers|red\s?light|for\s+adults|after\s?dark|hot\s?club)([^a-z0-9]|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [name] (a category or title) looks like adult content. */
    fun isAdult(name: String?): Boolean = !name.isNullOrBlank() && BLOCKED.containsMatchIn(name)

    fun isCategoryAllowed(categoryName: String?, isKidsProfile: Boolean): Boolean =
        !isKidsProfile || !isAdult(categoryName)
}
