package com.iptvapp.util

import com.iptvapp.data.local.IptvDatabase

/**
 * Sports Now: games on now and coming up, picked out of the guide by title.
 *
 * Guide titles for games look like "MLB Baseball: Yankees at Red Sox", "NFL Football",
 * "Premier League Soccer: Arsenal vs. Chelsea" or "UFC 310: Pantoja vs. Asakura". A title counts
 * when it names a league or sport AND reads like a game (teams with vs / at / @, or the
 * "<league> <sport>" broadcast form) — so "SportsCenter", "NFL Live" or "Inside the NBA" stay out.
 * One entry per airing, on the best channel for it (favorite first, then the main provider),
 * like show alerts.
 */
object SportsFinder {

    /** [score] is the copy's feed score, or null with no history. */
    data class Channel(
        val serverIndex: Int,
        val streamId: Int,
        val name: String,
        val isFavorite: Boolean,
        val score: Int? = null,
        /** When this feed's airing of the game ends (feeds can differ by minutes). 0 = unknown. */
        val stopMs: Long = 0L,
    ) {
        /** The network: the channel's own name without " · provider", for preferred networks. */
        val network: String get() = name.substringBeforeLast(" · ")
    }

    data class Game(
        val league: String,
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        val channel: Channel,
        val otherChannels: Int,
        /** Every feed carrying this game, best first; [channel] is the first. */
        val feeds: List<Channel> = listOf(channel),
    ) {
        fun isLive(nowMs: Long) = startMs <= nowMs && stopMs > nowMs
    }

    // Order here is the order of the sections on screen.
    private val LEAGUES: List<Pair<String, Regex>> = listOf(
        // The prime-time windows are titled by night, without "NFL"; Saturday night is college.
        "NFL" to Regex("""\bNFL\b|\b(Sunday|Monday|Thursday) Night Football\b|\bRedZone\b"""),
        "College football" to Regex("""\b(college|NCAA[A-Z]?)\b.*\bfootball\b|\bCFB\b|\bSaturday Night Football\b""", RegexOption.IGNORE_CASE),
        "NBA" to Regex("""\bNBA\b"""),
        "WNBA" to Regex("""\bWNBA\b"""),
        "College basketball" to Regex("""\b(college|NCAA[A-Z]?)\b.*\bbasketball\b""", RegexOption.IGNORE_CASE),
        // College and Little League baseball, college hockey: their own sections, not MLB / NHL.
        "MLB" to Regex(
            """\bMLB\b|(?<!college |little league |NCAA )\bbaseball\b|(?<!little league )\bWorld Series\b(?! of)|\b(ALCS|NLCS|ALDS|NLDS)\b""",
            RegexOption.IGNORE_CASE
        ),
        "NHL" to Regex("""\bNHL\b|(?<!college |NCAA )\bhockey\b|\bStanley Cup\b""", RegexOption.IGNORE_CASE),
        "Soccer" to Regex(
            """\b(MLS|Premier League|EPL|La ?Liga|Serie A|Bundesliga|Ligue 1|Liga MX|Champions League|Europa League|Conference League|UEFA|FIFA|World Cup|Concacaf|Copa|soccer|f[uú]tbol)\b""",
            RegexOption.IGNORE_CASE
        ),
        "Fighting" to Regex("""\b(UFC|boxing|Bellator|PFL)\b""", RegexOption.IGNORE_CASE),
        "Racing" to Regex("""\b(NASCAR|Formula 1|Formula One|F1|IndyCar|MotoGP)\b""", RegexOption.IGNORE_CASE),
        "Golf" to Regex("""\b(PGA|LPGA|LIV|golf)\b""", RegexOption.IGNORE_CASE),
        "Tennis" to Regex("""\b(tennis|ATP|WTA|Wimbledon|US Open|French Open|Australian Open)\b""", RegexOption.IGNORE_CASE),
        "College baseball" to Regex("""\b(college|NCAA|Little League)\b.*\bbaseball\b""", RegexOption.IGNORE_CASE),
        "College hockey" to Regex("""\b(college|NCAA)\b.*\bhockey\b""", RegexOption.IGNORE_CASE),
    )

    // Teams or fighters named: "A vs B", "A vs. B", "A v B", "A at B", "A @ B".
    private val MATCHUP = Regex("""\S\s+(vs\.?|v\.?|at|@)\s+\S""", RegexOption.IGNORE_CASE)

    // The broadcast form of a game with no teams in the title: "MLB Baseball", "NFL Football",
    // "College Basketball", "Premier League Soccer"…
    private val BROADCAST = Regex(
        """\b(baseball|football|basketball|hockey|soccer|f[uú]tbol|tennis|golf)\b\s*(:|$|\()""",
        RegexOption.IGNORE_CASE
    )

    // Events that are games without either form.
    private val EVENT = Regex(
        """\b(UFC \d+|UFC Fight Night|Grand Prix|(Cup|Xfinity|Truck|IndyCar) Series|(Indianapolis|Daytona) 500|L?PGA Tour|LIV Golf|Round \d|Final|Semifinal|Quarterfinal|""" +
            """(Sunday|Monday|Thursday|Saturday) Night (Football|Baseball)|RedZone|World Series|ALCS|NLCS|ALDS|NLDS|Wild Card|Stanley Cup|Playoffs?|Postseason|""" +
            """Champions League|Europa League|Conference League|Liga MX|Copa \p{L}+|F[uú]tbol \p{L}+|boxing)\b""",
        RegexOption.IGNORE_CASE
    )

    // Studio and magazine shows that name a league but aren't a game.
    private val NOT_A_GAME = Regex(
        """\b(pre-?game|post-?game|pregame|postgame|highlights|countdown|tonight|now|live:? .*show|insider|report|recap|center|centre|classics?|replay|preview|review|draft|talk|show|magazine|news|inside the|the best of|encore|rewind|films|poker|picante|central|school|academy|lessons?)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Games starting in the next [aheadHours] hours, or on now. Never throws. [preferredNetworks]
     * is league to network ("NBA" to "ESPN"): that network's feed comes first for the league.
     */
    suspend fun find(
        db: IptvDatabase,
        aheadHours: Int = 18,
        preferredNetworks: Map<String, String> = emptyMap(),
        /** League to today's team names (LiveScores.teams), for titles that only name the teams. */
        teams: Map<String, Set<String>> = emptyMap(),
    ): List<Game> = try {
        findInternal(db, aheadHours, preferredNetworks, teams)
    } catch (e: Exception) {
        android.util.Log.w("SportsFinder", "failed: ${e.message}")
        emptyList()
    }

    private fun toMs(t: Long) = if (t < 100_000_000_000L) t * 1000L else t

    fun leagueOf(title: String, teams: Map<String, Set<String>> = emptyMap()): String? {
        if (NOT_A_GAME.containsMatchIn(title)) return null
        val league = LEAGUES.firstOrNull { it.second.containsMatchIn(title) }?.first ?: return teamLeague(title, teams)
        val isGame = MATCHUP.containsMatchIn(title) || BROADCAST.containsMatchIn(title) || EVENT.containsMatchIn(title)
        return if (isGame) league else null
    }

    /** "Cowboys vs. Giants": a team of one league on each side of the matchup. */
    private fun teamLeague(title: String, teams: Map<String, Set<String>>): String? {
        if (teams.isEmpty()) return null
        val m = MATCHUP.find(title) ?: return null
        fun words(s: String) = " " + s.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
        val left = words(title.substring(0, m.range.first + 1))
        val right = words(title.substring(m.range.last))
        return teams.entries.firstOrNull { (_, names) ->
            names.any { left.contains(words(it)) } && names.any { right.contains(words(it)) }
        }?.key
    }

    /**
     * A game's feeds, best first: the viewer's network for the league, then the copy that has
     * played best (feed score; none counts as FeedScore.UNKNOWN), then favorites, then the main
     * provider. Ties keep the order found.
     */
    fun rankFeeds(league: String, feeds: List<Channel>, preferredNetworks: Map<String, String>): List<Channel> {
        val preferred = preferredNetworks[league]?.let { ChannelNameMatcher.normalize(it) }?.takeIf { it.isNotEmpty() }
        return feeds.sortedWith(
            compareByDescending<Channel> { preferred != null && ChannelNameMatcher.normalize(it.network) == preferred }
                .thenByDescending { it.score ?: FeedScore.UNKNOWN }
                .thenByDescending { it.isFavorite }
                .thenByDescending { it.serverIndex == -1 }
        )
    }

    /** Preferred networks are saved as "league<TAB>network" lines. Bad lines are skipped. */
    fun decodePreferred(stored: String?): Map<String, String> =
        stored.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim() else null
        }.toMap()

    fun encodePreferred(map: Map<String, String>): String =
        map.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("\n") { "${it.key.trim()}\t${it.value.trim()}" }

    private suspend fun findInternal(db: IptvDatabase, aheadHours: Int, preferredNetworks: Map<String, String>, teams: Map<String, Set<String>>): List<Game> {
        val nowMs = System.currentTimeMillis()
        val untilMs = nowMs + aheadHours * 3_600_000L
        data class Airing(val league: String, val title: String, val startMs: Long, var stopMs: Long, val channels: MutableList<Channel>)
        val airings = linkedMapOf<String, Airing>()
        val channelCache = hashMapOf<Pair<Int, Int>, Channel?>()
        for (row in db.epgDao().getUpcomingTitles(nowMs / 1000L)) {
            val start = toMs(row.startTimestamp)
            val stop = toMs(row.stopTimestamp)
            if (stop <= nowMs || start > untilMs) continue
            val title = row.title.trim()
            val league = leagueOf(title, teams) ?: continue
            val channel = channelCache.getOrPut(row.serverIndex to row.streamId) {
                resolveChannel(db, row.serverIndex, row.streamId)
            } ?: continue
            // Teams in the title make it one game wherever it airs. A generic title ("NFL Football")
            // at the same time can be different regional games, so those stay one row per channel.
            val key = "${title.lowercase()}@$start" +
                if (MATCHUP.containsMatchIn(title)) "" else "#${row.serverIndex}:${row.streamId}"
            val airing = airings.getOrPut(key) { Airing(league, title, start, stop, mutableListOf()) }
            if (stop > airing.stopMs) airing.stopMs = stop
            if (airing.channels.none { it.serverIndex == channel.serverIndex && it.streamId == channel.streamId }) {
                airing.channels += channel.copy(stopMs = stop)
            }
        }
        val order = LEAGUES.map { it.first }
        val scores = feedScores(db, channelCache.values.filterNotNull(), nowMs)
        return airings.values.map { a ->
            val ranked = rankFeeds(a.league, a.channels.map { it.copy(score = scores[it.serverIndex to it.streamId]) }, preferredNetworks)
            Game(a.league, a.title, a.startMs, a.stopMs, ranked.first(), a.channels.size - 1, ranked)
        }.sortedWith(
            compareBy<Game> { order.indexOf(it.league) }
                .thenByDescending { it.isLive(nowMs) }
                .thenBy { it.startMs }
        )
    }

    /** Feed scores for the channels carrying games, looked up a provider at a time. */
    private suspend fun feedScores(db: IptvDatabase, channels: List<Channel>, nowMs: Long): Map<Pair<Int, Int>, Int> {
        val out = HashMap<Pair<Int, Int>, Int>()
        channels.groupBy { it.serverIndex }.forEach { (server, list) ->
            list.map { it.streamId }.distinct().chunked(500).forEach { ids ->
                db.feedStatsDao().getForServer(server, ids).forEach { out[server to it.streamId] = FeedScore.score(it, nowMs) }
            }
        }
        return out
    }

    private suspend fun resolveChannel(db: IptvDatabase, serverIndex: Int, streamId: Int): Channel? =
        if (serverIndex == -1) {
            db.channelDao().getChannelById(streamId)
                ?.takeIf { !it.isHidden }
                ?.let { Channel(-1, it.streamId, it.name, it.isFavorite) }
        } else {
            db.mergedChannelDao().getByIndexAndId(serverIndex, streamId)
                ?.takeIf { !it.isHidden }
                ?.let { Channel(it.serverIndex, it.streamId, "${it.name} · ${it.serverNickname}", it.isFavorite) }
        }
}
