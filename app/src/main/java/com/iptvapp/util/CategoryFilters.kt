package com.iptvapp.util

/** Shared "USA Only" category-name matching, extracted from HomeViewModel.isUsCategory so
 * screens outside the phone/TV ViewModel (e.g. MultiViewActivity, which works off a flat
 * ChannelEntity list with no ViewModel of its own) can honor the same Settings toggle instead
 * of silently ignoring it. */
object CategoryFilters {
    fun isUsCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        // Different providers format the same "US" tag differently — some use "US|..." with
        // no spacing, others "US | ..." with spaces around the pipe. Collapsing whitespace
        // around every "|" before matching makes this work across both conventions instead of
        // only the first provider's exact style.
        val n = name.trim().uppercase().replace(Regex("\\s*\\|\\s*"), "|")
        // One provider (confirmed: a category list that came back completely empty under "USA
        // Only" despite genuinely being all-USA content) tags its USA categories "AM|USA GENERAL",
        // "AM|USA SPORTS", etc. — USA as the first WORD of the segment after a pipe, not the
        // whole segment on its own like "US|..." is. \b(...)\b keeps this from also matching
        // something like "MUSA" or "USAGE" that merely contains the letters.
        return n.startsWith("US|") || n.contains("|US|") ||
            Regex("""(^|\|)USA\b""").containsMatchIn(n)
    }
}
