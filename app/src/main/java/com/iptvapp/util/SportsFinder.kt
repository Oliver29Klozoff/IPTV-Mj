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

    data class Channel(val serverIndex: Int, val streamId: Int, val name: String, val isFavorite: Boolean)

    data class Game(
        val league: String,
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        val channel: Channel,
        val otherChannels: Int
    ) {
        fun isLive(nowMs: Long) = startMs <= nowMs && stopMs > nowMs
    }

    // Order here is the order of the sections on screen.
    private val LEAGUES: List<Pair<String, Regex>> = listOf(
        "NFL" to Regex("""\bNFL\b"""),
        "College football" to Regex("""\b(college|NCAA[A-Z]?)\b.*\bfootball\b|\bCFB\b""", RegexOption.IGNORE_CASE),
        "NBA" to Regex("""\bNBA\b"""),
        "WNBA" to Regex("""\bWNBA\b"""),
        "College basketball" to Regex("""\b(college|NCAA[A-Z]?)\b.*\bbasketball\b""", RegexOption.IGNORE_CASE),
        "MLB" to Regex("""\bMLB\b|\bbaseball\b""", RegexOption.IGNORE_CASE),
        "NHL" to Regex("""\bNHL\b|\bhockey\b""", RegexOption.IGNORE_CASE),
        "Soccer" to Regex(
            """\b(MLS|Premier League|EPL|La ?Liga|Serie A|Bundesliga|Ligue 1|Liga MX|Champions League|Europa League|UEFA|FIFA|World Cup|soccer|f[uú]tbol)\b""",
            RegexOption.IGNORE_CASE
        ),
        "Fighting" to Regex("""\b(UFC|boxing|Bellator|PFL)\b""", RegexOption.IGNORE_CASE),
        "Racing" to Regex("""\b(NASCAR|Formula 1|Formula One|F1|IndyCar|MotoGP)\b""", RegexOption.IGNORE_CASE),
        "Golf" to Regex("""\b(PGA|LPGA|golf)\b""", RegexOption.IGNORE_CASE),
        "Tennis" to Regex("""\b(tennis|ATP|WTA|Wimbledon|US Open|French Open|Australian Open)\b""", RegexOption.IGNORE_CASE)
    )

    // Teams or fighters named: "A vs B", "A vs. B", "A v B", "A at B", "A @ B".
    private val MATCHUP = Regex("""\S\s+(vs\.?|v\.?|at|@)\s+\S""", RegexOption.IGNORE_CASE)

    // The broadcast form of a game with no teams in the title: "MLB Baseball", "NFL Football",
    // "College Basketball", "Premier League Soccer"…
    private val BROADCAST = Regex(
        """\b(baseball|football|basketball|hockey|soccer|f[uú]tbol)\b\s*(:|$|\()""",
        RegexOption.IGNORE_CASE
    )

    // Events that are games without either form.
    private val EVENT = Regex(
        """\b(UFC \d+|UFC Fight Night|Grand Prix|Cup Series|Xfinity Series|PGA Tour|Round \d|Final|Semifinal|Quarterfinal)\b""",
        RegexOption.IGNORE_CASE
    )

    // Studio and magazine shows that name a league but aren't a game.
    private val NOT_A_GAME = Regex(
        """\b(pre-?game|post-?game|pregame|postgame|highlights|countdown|tonight|now|live:? .*show|insider|report|recap|center|centre|classics?|replay|preview|review|draft|talk|show|magazine|news|inside the|the best of|encore|rewind|films)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Games starting in the next [aheadHours] hours, or on now. Never throws. */
    suspend fun find(db: IptvDatabase, aheadHours: Int = 18): List<Game> = try {
        findInternal(db, aheadHours)
    } catch (e: Exception) {
        android.util.Log.w("SportsFinder", "failed: ${e.message}")
        emptyList()
    }

    private fun toMs(t: Long) = if (t < 100_000_000_000L) t * 1000L else t

    fun leagueOf(title: String): String? {
        if (NOT_A_GAME.containsMatchIn(title)) return null
        val league = LEAGUES.firstOrNull { it.second.containsMatchIn(title) }?.first ?: return null
        val isGame = MATCHUP.containsMatchIn(title) || BROADCAST.containsMatchIn(title) || EVENT.containsMatchIn(title)
        return if (isGame) league else null
    }

    private suspend fun findInternal(db: IptvDatabase, aheadHours: Int): List<Game> {
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
            val league = leagueOf(title) ?: continue
            val channel = channelCache.getOrPut(row.serverIndex to row.streamId) {
                resolveChannel(db, row.serverIndex, row.streamId)
            } ?: continue
            val key = "${title.lowercase()}@$start"
            val airing = airings.getOrPut(key) { Airing(league, title, start, stop, mutableListOf()) }
            if (stop > airing.stopMs) airing.stopMs = stop
            if (airing.channels.none { it.serverIndex == channel.serverIndex && it.streamId == channel.streamId }) {
                airing.channels += channel
            }
        }
        val order = LEAGUES.map { it.first }
        return airings.values.map { a ->
            val best = a.channels.sortedWith(
                compareByDescending<Channel> { it.isFavorite }.thenByDescending { it.serverIndex == -1 }
            ).first()
            Game(a.league, a.title, a.startMs, a.stopMs, best, a.channels.size - 1)
        }.sortedWith(
            compareBy<Game> { order.indexOf(it.league) }
                .thenByDescending { it.isLive(nowMs) }
                .thenBy { it.startMs }
        )
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
