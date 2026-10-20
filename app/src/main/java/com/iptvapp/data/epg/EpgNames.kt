package com.iptvapp.data.epg

import java.text.Normalizer
import java.util.Locale

/**
 * What a channel or guide-channel name says about which channel it is, for guide matching.
 *
 * Things that never decide the channel are dropped: quality tags (HD, FHD, 4K…), a provider's
 * "US:" / "UK|" prefix, punctuation and accents. Things that do decide it are kept: numbers
 * (ESPN vs ESPN2), "+1" (the hour-later channel), feed words (East vs West, Pacific…), call signs
 * (KTTV vs WNYW), and the country, kept apart from the name so "ESPN US" still equals "ESPN" but
 * never "ESPN UK".
 *
 * The existing refresh's own name fallback (XmltvEpgRefresh.normalizeForMatch) drops east/west
 * and country words; this is stricter on purpose, because a repair suggestion must not merge
 * feeds the user can tell apart.
 */
data class EpgName(
    /** Every deciding token, in order: "hbo east", "espn2", "film4 plus 1". */
    val tokens: List<String>,
    /** [tokens] without feed words and call signs: the network, for finding related feeds. */
    val baseTokens: List<String>,
    /** East, west, pacific…: two names with different ones are different channels. */
    val feeds: Set<String>,
    /** KTTV, WNYW…: a local affiliate's identity. */
    val callSigns: Set<String>,
    /** "us", "uk"…, when the name or id says; null when it doesn't. */
    val country: String?,
) {
    val key: String get() = tokens.joinToString(" ")
    val baseKey: String get() = baseTokens.joinToString(" ")
    val isBlank: Boolean get() = tokens.isEmpty()

    /** Nothing in either name says they are different channels (countries only when both are known). */
    fun compatibleWith(other: EpgName): Boolean =
        feeds == other.feeds && callSigns == other.callSigns &&
            (country == null || other.country == null || country == other.country)

    /** Why [other] is not this channel, in words, or null when nothing says so. */
    fun conflictWith(other: EpgName): String? = when {
        feeds != other.feeds -> "Different feed (${feeds.label()} vs ${other.feeds.label()})"
        callSigns != other.callSigns -> "Different local station (${callSigns.upper()} vs ${other.callSigns.upper()})"
        country != null && other.country != null && country != other.country ->
            "Different country (${country.uppercase(Locale.US)} vs ${other.country.uppercase(Locale.US)})"
        numbers() != other.numbers() -> "Different channel number in the name"
        else -> null
    }

    private fun numbers() = tokens.flatMap { t -> DIGITS.findAll(t).map { it.value }.toList() }

    private fun Set<String>.upper() = if (isEmpty()) "none" else joinToString("/") { it.uppercase(Locale.US) }

    private fun Set<String>.label() = if (isEmpty()) "none" else joinToString("/") { it.replaceFirstChar(Char::uppercaseChar) }

    private companion object {
        val DIGITS = Regex("""\d+""")
    }
}

object EpgNames {
    private val QUALITY = Regex(
        """\b(4k|uhd|fhd|full\s?hd|hdr|1080[pi]?|720p|hd|sd|hevc|h\.?26[45]|x26[45]|raw|backup)\b""",
        RegexOption.IGNORE_CASE
    )
    // "US:", "UK |", "CA -" before the name: a provider's category baked into the channel name.
    private val PREFIX = Regex("""^\s*\[?([A-Za-z]{2,3})\]?\s*[|:\-]+\s*""")
    private val COMBINING = Regex("""\p{Mn}+""")
    private val SPLIT = Regex("""[^\p{L}\p{N}]+""")
    private val CALL_SIGN_RAW = Regex("""^[KW][A-Z]{2,3}$""")

    private val COUNTRIES = mapOf(
        "us" to "us", "usa" to "us", "uk" to "uk", "gb" to "uk", "ca" to "ca", "can" to "ca", "canada" to "ca",
        "au" to "au", "aus" to "au", "nz" to "nz", "ie" to "ie", "de" to "de", "fr" to "fr", "es" to "es",
        "it" to "it", "mx" to "mx", "br" to "br", "in" to "in", "nl" to "nl", "pt" to "pt", "pl" to "pl",
        "tr" to "tr", "ar" to "ar", "za" to "za", "ph" to "ph", "latino" to "latam", "latam" to "latam",
    )
    // Inside a name, a country word must not eat a real word ("in", "it", "es", "ar" are words).
    private val COUNTRY_WORDS = setOf("us", "usa", "uk", "gb", "ca", "canada", "au", "aus", "nz", "latam", "latino")
    private val FEEDS = mapOf(
        "east" to "east", "eastern" to "east", "west" to "west", "western" to "west",
        "pacific" to "pacific", "mountain" to "mountain", "central" to "central", "atlantic" to "atlantic",
    )
    // Capitalised four-letter words that look like call signs but are not stations.
    private val NOT_CALL_SIGNS = setOf("WWE", "WOW", "KIDS", "WILD", "WORK", "KISS", "WEST", "WGN")

    fun parse(name: String): EpgName {
        var s = name.trim()
        var country: String? = null
        PREFIX.find(s)?.let { m ->
            country = COUNTRIES[m.groupValues[1].lowercase(Locale.US)]
            s = s.substring(m.range.last + 1)
        }
        s = QUALITY.replace(s, " ")
        // "+" means something: ESPN+ is not ESPN, and "Film4 +1" is the hour-later channel.
        s = s.replace("+", " plus ")
        val rawTokens = s.split(SPLIT).filter { it.isNotEmpty() }
        val callSigns = rawTokens.filter { CALL_SIGN_RAW.matches(it) && it !in NOT_CALL_SIGNS }
            .map { it.lowercase(Locale.US) }.toSet()
        val tokens = ArrayList<String>()
        val feeds = HashSet<String>()
        for (raw in rawTokens) {
            val t = COMBINING.replace(Normalizer.normalize(raw.lowercase(Locale.US), Normalizer.Form.NFD), "")
            if (t.isEmpty() || t == "the") continue
            if (t in COUNTRY_WORDS) { country = country ?: COUNTRIES[t]; continue }
            FEEDS[t]?.let { feeds += it }
            // A number standing alone belongs to the word before it: "ESPN 2" is ESPN2, "Fox 5" is fox5.
            if (t.all(Char::isDigit) && tokens.isNotEmpty() && !tokens.last().all(Char::isDigit) && tokens.last() !in FEEDS) {
                tokens[tokens.size - 1] = tokens.last() + t
                continue
            }
            tokens += t
        }
        val base = tokens.filter { it !in FEEDS && it !in callSigns }
        return EpgName(tokens, base, feeds, callSigns, country)
    }

    /**
     * A guide channel: its display name, with what its id adds ("HBO.East.us" says the country
     * and the feed even when the display name is just "HBO").
     */
    fun parseGuide(id: String, displayName: String): EpgName {
        val byName = parse(displayName)
        val idParts = id.split('.', '-', '_', ' ').map { it.lowercase(Locale.US) }.filter { it.isNotEmpty() }
        val idCountry = idParts.lastOrNull()?.let { if (idParts.size > 1) COUNTRIES[it] else null }
        val idFeeds = idParts.mapNotNull { FEEDS[it] }.toSet()
        if (idCountry == null && idFeeds.isEmpty()) return byName
        val feeds = if (byName.feeds.isEmpty() && idFeeds.isNotEmpty()) idFeeds else byName.feeds
        val tokens = if (feeds != byName.feeds) byName.tokens + feeds.sorted() else byName.tokens
        return byName.copy(tokens = tokens, feeds = feeds, country = byName.country ?: idCountry)
    }
}
