package com.iptvapp.util

// Normalizes a channel's display name for cross-provider "is this the same real channel"
// matching (used by live-playback failover — see PlayerActivity/HomeActivity/TvHomeActivity's
// scheduleRetry give-up paths). There's no shared ID across providers for the same real channel
// (each provider's epgChannelId is its own scheme), so name comparison after stripping the parts
// that vary provider-to-provider (quality tags, country/language prefixes, punctuation) is the
// only signal available. Exact-after-normalize rather than fuzzy/edit-distance matching — a
// false-positive failover (switching to a DIFFERENT real channel because the names looked close)
// is worse than occasionally missing a real match, so this stays conservative.
object ChannelNameMatcher {
    // Same quality-tag vocabulary as ChannelQualityTag, but matched for removal here rather than
    // extraction — a name like "ESPN HD" and "ESPN FHD" from two providers must normalize to the
    // same "ESPN" to match at all.
    private val QUALITY_TAG = Regex("""\b(4K|UHD|FHD|FULL\s?HD|1080P?|HD|SD|HEVC|H\.?265|H\.?264)\b""", RegexOption.IGNORE_CASE)

    // Leading "US|", "US | ", "UK:", "USA -" style provider category/country prefixes some
    // resellers bake directly into the channel name itself (not just the category name) —
    // stripped so "US| ESPN" and "ESPN" normalize the same.
    private val LEADING_PREFIX = Regex("""^\s*[A-Za-z]{2,4}\s*[|:\-]+\s*""")

    private val NON_ALNUM = Regex("""[^a-z0-9]+""")

    /** Two channels are considered "the same real channel" if their normalized names are equal
     * and non-blank — never treats an empty/all-punctuation result as a match. */
    fun normalize(name: String): String {
        var n = name.trim()
        n = LEADING_PREFIX.replace(n, "")
        n = QUALITY_TAG.replace(n, " ")
        n = n.lowercase()
        n = NON_ALNUM.replace(n, "")
        return n
    }

    fun matches(nameA: String, nameB: String): Boolean {
        val a = normalize(nameA)
        val b = normalize(nameB)
        return a.isNotEmpty() && a == b
    }
}
