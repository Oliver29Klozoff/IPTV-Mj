package com.iptvapp.data.epg

import java.util.Locale

/** A channel the user has, on one provider. streamId is only unique together with serverIndex. */
data class EpgLocalChannel(
    val serverIndex: Int,
    val streamId: Int,
    val name: String,
    val epgChannelId: String?,
)

/** A channel one guide source lists, from the catalog saved at the last refresh of that source. */
data class EpgGuideChannel(
    val serverIndex: Int,
    val sourceKey: String,
    val xmltvId: String,
    val displayName: String,
    val programCount: Int,
    /** End of its last listing (epoch seconds); 0 when it has none. */
    val lastStop: Long,
    /** Up to three upcoming listings, for telling feeds apart: see [EpgSamples]. */
    val sample: String,
) {
    val ref: GuideRef get() = GuideRef(sourceKey, xmltvId.lowercase(Locale.US))
    fun hasListingsAfter(nowSec: Long) = lastStop > nowSec
}

/** One guide channel in one source. Ids are compared case-insensitively, as the refresh does. */
data class GuideRef(val sourceKey: String, val xmltvIdLower: String)

/**
 * How sure a suggestion is. There are no percentages: each state is a rule, documented on
 * [EpgMatcher.suggest], and only [EXACT] and [HIGH] can ever be applied without the user choosing.
 */
enum class MatchState { EXACT, HIGH, POSSIBLE, AMBIGUOUS, NONE }

data class EpgCandidate(val guide: EpgGuideChannel, val reason: String)

data class EpgMatchResult(
    val state: MatchState,
    /** Best first; for AMBIGUOUS, every plausible one (the user picks). */
    val candidates: List<EpgCandidate>,
    val reason: String,
) {
    val best: EpgCandidate? get() = candidates.firstOrNull()
}

/**
 * The guide channels of the sources a provider uses, indexed for lookups by id, by name and by
 * network, so a channel is never compared against every guide channel (30k channels × 40k guide
 * channels would be over a billion comparisons).
 */
class EpgMatchIndex(guides: List<EpgGuideChannel>, private val sourceRank: (String) -> Int) {
    internal val byId = HashMap<String, MutableList<EpgGuideChannel>>()
    internal val byKey = HashMap<String, MutableList<Pair<EpgGuideChannel, EpgName>>>()
    internal val byBase = HashMap<String, MutableList<Pair<EpgGuideChannel, EpgName>>>()
    internal val byFirst = HashMap<String, MutableList<Pair<EpgGuideChannel, EpgName>>>()
    internal val names = HashMap<GuideRef, EpgName>()

    init {
        for (g in guides) {
            byId.getOrPut(g.xmltvId.lowercase(Locale.US)) { ArrayList(1) } += g
            val n = EpgNames.parseGuide(g.xmltvId, g.displayName)
            names[g.ref] = n
            if (n.isBlank) continue
            byKey.getOrPut(n.key) { ArrayList(1) } += g to n
            byBase.getOrPut(n.baseKey) { ArrayList(1) } += g to n
            // Bounded: a very common first word ("fox", "sky") keeps at most this many.
            val first = byFirst.getOrPut(n.baseTokens.firstOrNull() ?: n.tokens.first()) { ArrayList(2) }
            if (first.size < MAX_PER_FIRST_WORD) first += g to n
        }
    }

    fun nameOf(g: EpgGuideChannel): EpgName = names[g.ref] ?: EpgNames.parseGuide(g.xmltvId, g.displayName)

    fun guide(ref: GuideRef): EpgGuideChannel? =
        byId[ref.xmltvIdLower]?.firstOrNull { it.sourceKey == ref.sourceKey }

    internal fun rank(g: EpgGuideChannel) = sourceRank(g.sourceKey)

    companion object {
        const val MAX_PER_FIRST_WORD = 400
    }
}

object EpgMatcher {
    private const val MAX_CANDIDATES = 8

    /**
     * Guide channels for [local], from the strongest evidence down; the first rule that finds
     * anything decides the state:
     *
     * 1. EXACT: a source lists the channel's own guide id (the provider's epg_channel_id).
     * 2. HIGH: exactly one guide channel per source has the same name once quality tags and
     *    prefixes are set aside, with the same feed, call sign and (when both say) country; it
     *    has listings; no other channel of this provider already gets it; and the guide lists no
     *    other feeds of that network. Otherwise the same-name match is POSSIBLE, or AMBIGUOUS
     *    when one source has several.
     * 3. AMBIGUOUS: the same network in two or more feeds or stations (HBO East and HBO West
     *    for a plain "HBO"): the user picks.
     *    POSSIBLE: one other feed of the network, or a similar name ("Fox" and "Fox Los Angeles").
     * 4. NONE.
     *
     * Different numbers (ESPN, ESPN2), "+1", feeds, call signs and countries never match each other.
     */
    fun suggest(
        local: EpgLocalChannel,
        index: EpgMatchIndex,
        nowSec: Long,
        claimedBy: (GuideRef) -> Int? = { null },
    ): EpgMatchResult {
        val name = EpgNames.parse(local.name)

        // 1. The channel's own guide id.
        val id = local.epgChannelId?.trim()?.lowercase(Locale.US).orEmpty()
        if (id.isNotEmpty()) {
            val byId = index.byId[id].orEmpty().sortedWith(compareBy({ !it.hasListingsAfter(nowSec) }, { index.rank(it) }))
            if (byId.isNotEmpty()) {
                val region = byId.firstOrNull()?.let { index.nameOf(it).country }
                val why = if (region != null && (name.country == null || name.country == region)) "Exact guide ID and region match" else "Exact guide ID"
                return EpgMatchResult(MatchState.EXACT, byId.map { EpgCandidate(it, why) }.take(MAX_CANDIDATES), why)
            }
        }
        if (name.isBlank) return EpgMatchResult(MatchState.NONE, emptyList(), "The channel's name has nothing to match on")

        // 2. The same name.
        val same = index.byKey[name.key].orEmpty().filter { (_, n) -> name.compatibleWith(n) }
        if (same.isNotEmpty()) {
            val perSource = same.groupBy { it.first.sourceKey }
            // A guide with listings for it first, then the preferred guide.
            val sorted = same.map { it.first }.sortedWith(compareBy({ !it.hasListingsAfter(nowSec) }, { index.rank(it) }))
            if (perSource.values.any { it.size > 1 }) {
                return EpgMatchResult(
                    MatchState.AMBIGUOUS, sorted.map { EpgCandidate(it, "Same name, listed more than once in one guide") }.take(MAX_CANDIDATES),
                    "Channel name matches several guide channels: pick one"
                )
            }
            val best = sorted.first()
            // Other feeds compete only with a name that doesn't say its own feed or station ("HBO", not "HBO East").
            val otherFeeds = if (name.feeds.isNotEmpty() || name.callSigns.isNotEmpty()) emptyList() else index.byBase[name.baseKey].orEmpty()
                .filter { (g, n) -> n.key != name.key && name.country.compatible(n.country) && g.hasListingsAfter(nowSec) }
            val other = claimedBy(best.ref)?.takeIf { it != local.streamId }
            val state: MatchState
            val why: String
            when {
                !best.hasListingsAfter(nowSec) -> { state = MatchState.POSSIBLE; why = "Name matches, but that guide channel has no upcoming listings" }
                other != null -> { state = MatchState.POSSIBLE; why = "Name matches, but another channel already uses this guide channel" }
                otherFeeds.isNotEmpty() -> { state = MatchState.POSSIBLE; why = "Name matches, but the guide also lists other feeds of it — confirm which" }
                else -> { state = MatchState.HIGH; why = if (name.country != null) "Channel name and region match" else "Channel name matches" }
            }
            val extra = otherFeeds.map { EpgCandidate(it.first, "Other feed: ${it.first.displayName}") }
            return EpgMatchResult(state, (sorted.map { EpgCandidate(it, why) } + extra).distinctBy { it.guide.ref }.take(MAX_CANDIDATES), why)
        }

        // 3. The same network in other feeds or stations.
        val related = index.byBase[name.baseKey].orEmpty()
            .filter { (_, n) -> name.country.compatible(n.country) && n.key != name.key }
            .sortedBy { index.rank(it.first) }
        if (related.isNotEmpty()) {
            val variants = related.map { it.second.feeds to it.second.callSigns }.distinct()
            return if (variants.size > 1) {
                EpgMatchResult(
                    MatchState.AMBIGUOUS,
                    related.map { (g, n) -> EpgCandidate(g, name.conflictWith(n) ?: "Another feed") }.take(MAX_CANDIDATES),
                    "Channel name matches, but multiple regional feeds exist"
                )
            } else {
                val (g, n) = related.first()
                val why = "Similar name — ${name.conflictWith(n)?.replaceFirstChar(Char::lowercaseChar) ?: "a different feed"} — manual confirmation required"
                EpgMatchResult(MatchState.POSSIBLE, related.map { EpgCandidate(it.first, why) }.take(MAX_CANDIDATES), why)
            }
        }

        // 4. A similar name: one name is the other with words added ("Fox" / "Fox Los Angeles").
        val first = name.baseTokens.firstOrNull() ?: name.tokens.first()
        val similar = index.byFirst[first].orEmpty().filter { (_, n) ->
            name.country.compatible(n.country) &&
                (n.baseTokens.startsWith(name.baseTokens) || name.baseTokens.startsWith(n.baseTokens)) &&
                n.baseTokens != name.baseTokens
                && extra(n.baseTokens, name.baseTokens).none { t -> t.any(Char::isDigit) || t.startsWith("plus") }
        }.sortedWith(compareBy({ index.rank(it.first) }, { it.second.tokens.size }))
        if (similar.isNotEmpty()) {
            val why = "Similar name — manual confirmation required"
            return EpgMatchResult(MatchState.POSSIBLE, similar.map { EpgCandidate(it.first, why) }.take(MAX_CANDIDATES), why)
        }
        return EpgMatchResult(MatchState.NONE, emptyList(), "No guide channel with this name or guide ID")
    }

    /**
     * Why the guide channel a channel currently gets its listings from looks like the wrong one,
     * or null. [byOwnId] is true when the provider's own guide id chose it (then only a feed,
     * station or country contradiction counts); a name-based match also counts a different number.
     */
    fun suspicion(local: EpgLocalChannel, current: EpgGuideChannel, index: EpgMatchIndex): String? {
        val name = EpgNames.parse(local.name)
        if (name.isBlank) return null
        val guide = index.nameOf(current)
        val conflict = name.conflictWith(guide) ?: return null
        val byOwnId = local.epgChannelId?.trim()?.equals(current.xmltvId, ignoreCase = true) == true
        if (byOwnId && conflict.startsWith("Different channel number")) return null
        return if (byOwnId) "The provider's guide ID points to \"${current.displayName}\": $conflict"
        else "Matched by name to \"${current.displayName}\": $conflict"
    }

    /**
     * Whether "Apply safe matches" may use [result] for a channel with no listings and no saved
     * correction: an EXACT or HIGH match whose guide channel has listings and is not already
     * another channel's guide. Everything else waits for the user.
     */
    fun safeToApply(result: EpgMatchResult, local: EpgLocalChannel, nowSec: Long, claimedBy: (GuideRef) -> Int?): Boolean {
        if (result.state != MatchState.EXACT && result.state != MatchState.HIGH) return false
        val best = result.best?.guide ?: return false
        if (!best.hasListingsAfter(nowSec)) return false
        val other = claimedBy(best.ref)
        return other == null || other == local.streamId
    }

    private fun String?.compatible(other: String?) = this == null || other == null || this == other

    /** The words one name has beyond the other (a number among them means a different channel). */
    private fun extra(a: List<String>, b: List<String>) = if (a.size >= b.size) a.drop(b.size) else b.drop(a.size)

    private fun List<String>.startsWith(prefix: List<String>) = prefix.isNotEmpty() && size >= prefix.size && subList(0, prefix.size) == prefix
}
