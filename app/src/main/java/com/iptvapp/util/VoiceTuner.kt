package com.iptvapp.util

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity

/**
 * Voice tune (v7.14): turns what was said ("ESPN", "channel 205", "put on the Yankees game") into
 * a channel. Tried in order, for each of the recognizer's guesses:
 *  1. a channel number — the custom number you gave a channel, then the provider's number;
 *  2. a channel name — same channel by normalized name (ChannelNameMatcher: "ESPN" finds
 *     "US: ESPN HD"), then a name that starts with what was said;
 *  3. a show on now whose title contains every word that was said ("Yankees" finds
 *     "MLB Baseball: Yankees at Red Sox").
 * Favorites win ties, then the main provider.
 */
object VoiceTuner {

    data class Target(val serverIndex: Int, val streamId: Int, val channelName: String, val showTitle: String?)

    private val LEADING = Regex(
        """^(please\s+)?(play|watch|put on|turn on|turn to|tune to|tune in to|go to|switch to|change to|open|show me|find)\s+""",
        RegexOption.IGNORE_CASE
    )
    private val FILLER = setOf(
        "the", "a", "an", "channel", "on", "please", "game", "match", "now", "live"
    )
    private val NUMBER_WORDS = Regex("""^(channel\s+|number\s+)?(\d{1,5})$""", RegexOption.IGNORE_CASE)

    private data class Candidate(val serverIndex: Int, val streamId: Int, val name: String, val isFavorite: Boolean, val norm: String)

    /** Never throws; null when nothing matched any of [phrases]. */
    suspend fun resolve(
        db: IptvDatabase,
        phrases: List<String>,
        primary: List<ChannelEntity>,
        merged: List<MergedChannelEntity>
    ): Target? = try {
        val candidates = primary.filter { !it.isHidden }.map {
            Candidate(-1, it.streamId, it.name, it.isFavorite, ChannelNameMatcher.normalize(it.name))
        } + merged.filter { !it.isHidden }.map {
            Candidate(it.serverIndex, it.streamId, "${it.name} · ${it.serverNickname}", it.isFavorite, ChannelNameMatcher.normalize(it.name))
        }
        val byPreference = compareByDescending<Candidate> { it.isFavorite }
            .thenByDescending { it.serverIndex == -1 }
            .thenBy { it.name.length }
        var result: Target? = null
        for (raw in phrases) {
            val spoken = raw.trim().replace(LEADING, "").trim()
            if (spoken.isEmpty()) continue

            NUMBER_WORDS.matchEntire(spoken)?.let { m ->
                val n = m.groupValues[2].toInt()
                val custom = primary.firstOrNull { !it.isHidden && it.customNum == n }
                result = if (custom != null) Target(-1, custom.streamId, custom.name, null)
                else {
                    // Provider numbers: the main provider's or an enabled other provider's, same preference order.
                    val byNum = (primary.filter { !it.isHidden && it.num == n }.map { it.streamId to -1 } +
                        merged.filter { !it.isHidden && it.num == n }.map { it.streamId to it.serverIndex }).toSet()
                    candidates.filter { (it.streamId to it.serverIndex) in byNum }.sortedWith(byPreference).firstOrNull()
                        ?.let { Target(it.serverIndex, it.streamId, it.name, null) }
                }
            }
            if (result != null) break

            // The name exactly as said first — "The Weather Channel" and "The CW" keep their filler words.
            val intact = ChannelNameMatcher.normalize(spoken)
            if (intact.length >= 2) {
                candidates.filter { it.norm == intact }.sortedWith(byPreference).firstOrNull()
                    ?.let { result = Target(it.serverIndex, it.streamId, it.name, null) }
            }
            if (result != null) break

            val words = spoken.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() && it !in FILLER }
            val norm = ChannelNameMatcher.normalize(words.joinToString(" "))
            if (norm.length >= 2) {
                val exact = candidates.filter { it.norm == norm }.sortedWith(byPreference).firstOrNull()
                val prefix = if (exact == null && norm.length >= 3)
                    candidates.filter { it.norm.startsWith(norm) }.sortedWith(byPreference).firstOrNull() else null
                (exact ?: prefix)?.let { result = Target(it.serverIndex, it.streamId, it.name, null) }
            }
            if (result != null) break

            val keywords = words.filter { it.length >= 3 }
            if (keywords.isNotEmpty()) result = showOnNow(db, keywords, candidates, byPreference)
            if (result != null) break
        }
        result
    } catch (e: Exception) {
        android.util.Log.w("VoiceTuner", "resolve failed: ${e.message}")
        null
    }

    private suspend fun showOnNow(
        db: IptvDatabase,
        keywords: List<String>,
        candidates: List<Candidate>,
        byPreference: Comparator<Candidate>
    ): Target? {
        val nowMs = System.currentTimeMillis()
        fun ms(t: Long) = if (t < 100_000_000_000L) t * 1000L else t
        val byKey = candidates.associateBy { it.serverIndex to it.streamId }
        val hits = db.epgDao().getUpcomingTitles(nowMs / 1000L).filter { row ->
            ms(row.startTimestamp) <= nowMs && ms(row.stopTimestamp) > nowMs &&
                keywords.all { row.title.contains(it, ignoreCase = true) }
        }
        val best = hits.mapNotNull { row -> byKey[row.serverIndex to row.streamId]?.let { it to row.title } }
            .sortedWith(compareBy(byPreference) { it.first })
            .firstOrNull() ?: return null
        return Target(best.first.serverIndex, best.first.streamId, best.first.name, best.second)
    }
}
