package com.iptvapp.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Live scores (v7.75) from ESPN's public scoreboards: no account, no key, and nothing of the
 * viewer's is sent (a plain GET per league). Games are matched to guide airings by league, start
 * time, team names in the title and the channel's network, so the Sports screen and the player
 * can show the score of what is on.
 *
 * A league is fetched again after a minute while one of its games is on or about to start, and
 * after 15 minutes otherwise. Fetches only happen while a screen asks; failures keep the last copy.
 */
object LiveScores {

    data class Team(val abbr: String, val names: List<String>, val score: String)

    data class Event(
        val league: String,
        val startMs: Long,
        /** "pre", "in" or "post". */
        val state: String,
        /** ESPN's short status: "Bot 7th", "4th 2:31", "Halftime", "Final/12". */
        val detail: String,
        val period: Int,
        val clock: String,
        val home: Team,
        val away: Team,
        /** National broadcasters, as ESPN names them ("ESPN", "Fox", "TNT"). */
        val networks: List<String>,
    ) {
        val live get() = state == "in"
        val final get() = state == "post"

        /** "MIL 3 – TB 4 · Bot 9th"; empty before the game. */
        val label: String get() = if (state == "pre") "" else
            "${away.abbr} ${away.score} – ${home.abbr} ${home.score}" + if (detail.isNotBlank()) " · $detail" else ""

        /** Late and within a score: worth a look. */
        val close: Boolean get() {
            if (!live) return false
            val margin = kotlin.math.abs((home.score.toIntOrNull() ?: return false) - (away.score.toIntOrNull() ?: return false))
            return when (league) {
                "NFL", "College football" -> period >= 4 && margin <= 8
                "NBA", "WNBA" -> period >= 4 && margin <= 6
                "College basketball" -> period >= 2 && margin <= 6
                "MLB" -> period >= 8 && margin <= 2
                "NHL" -> period >= 3 && margin <= 1
                "Soccer" -> period >= 2 && (clock.takeWhile { it.isDigit() }.toIntOrNull() ?: 0) >= 70 && margin <= 1
                else -> false
            }
        }
    }

    /** SportsFinder's leagues and the ESPN scoreboards behind them. */
    private val FEEDS: Map<String, List<String>> = mapOf(
        "NFL" to listOf("football/nfl"),
        // College boards list only featured games unless asked for the whole top division.
        "College football" to listOf("football/college-football?groups=80"),
        "NBA" to listOf("basketball/nba"),
        "WNBA" to listOf("basketball/wnba"),
        "College basketball" to listOf("basketball/mens-college-basketball?groups=50", "basketball/womens-college-basketball?groups=50"),
        "MLB" to listOf("baseball/mlb"),
        "NHL" to listOf("hockey/nhl"),
        "Soccer" to listOf("soccer/usa.1", "soccer/eng.1", "soccer/esp.1", "soccer/mex.1", "soccer/uefa.champions", "soccer/uefa.europa"),
    )
    val LEAGUES: Set<String> get() = FEEDS.keys

    private const val BUSY_MS = 60_000L
    private const val QUIET_MS = 15 * 60_000L
    private const val SOON_MS = 15 * 60_000L

    private class Cached(val at: Long, val events: List<Event>)
    private val cache = HashMap<String, Cached>()

    /** Today's games in [leagues], from cache when fresh. Never throws; no network on a fresh cache. */
    fun events(leagues: Set<String> = LEAGUES, nowMs: Long = System.currentTimeMillis()): List<Event> =
        leagues.flatMap { league ->
            FEEDS[league].orEmpty().flatMap { path ->
                val held = synchronized(cache) { cache[path] }
                if (held != null && nowMs - held.at < maxAge(held.events, nowMs)) held.events
                else runCatching { parse(fetch(path), league) }.getOrNull()
                    ?.also { synchronized(cache) { cache[path] = Cached(nowMs, it) } }
                    ?: held?.events.orEmpty()
            }
        }

    /** What the last fetches got, without the network: for a screen's first draw. */
    fun cached(): List<Event> = synchronized(cache) { cache.values.flatMap { it.events } }

    private fun maxAge(events: List<Event>, nowMs: Long) =
        if (events.any { it.live || it.state == "pre" && it.startMs - nowMs < SOON_MS }) BUSY_MS else QUIET_MS

    private fun fetch(path: String): String {
        val query = path.substringAfter('?', "")
        val url = "https://site.api.espn.com/apis/site/v2/sports/${path.substringBefore('?')}/scoreboard" + if (query.isEmpty()) "" else "?$query"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
        }
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun iso(value: String): Long? = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { f ->
        runCatching { SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time }.getOrNull()
    }

    /** One scoreboard. Events it cannot read are skipped. */
    fun parse(json: String, league: String): List<Event> {
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            runCatching {
                val e = events.getJSONObject(i)
                val c = e.getJSONArray("competitions").getJSONObject(0)
                val status = c.optJSONObject("status") ?: e.getJSONObject("status")
                val type = status.getJSONObject("type")
                val teams = c.getJSONArray("competitors").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
                fun team(side: String): Team {
                    val t = teams.first { it.optString("homeAway") == side }
                    val info = t.getJSONObject("team")
                    val names = listOf("displayName", "shortDisplayName", "name", "location")
                        .map { info.optString(it).trim() }.filter { it.length >= 3 }.distinct()
                    return Team(info.optString("abbreviation"), names, t.optString("score", "0"))
                }
                val broadcasts = c.optJSONArray("broadcasts")
                val networks = if (broadcasts == null) emptyList() else (0 until broadcasts.length()).flatMap { b ->
                    val names = broadcasts.getJSONObject(b).optJSONArray("names")
                    if (names == null) emptyList() else (0 until names.length()).map { names.getString(it) }
                }
                Event(
                    league = league,
                    startMs = iso(e.getString("date")) ?: return@runCatching null,
                    state = type.optString("state"),
                    detail = type.optString("shortDetail"),
                    period = status.optInt("period"),
                    clock = status.optString("displayClock"),
                    home = team("home"),
                    away = team("away"),
                    networks = networks,
                )
            }.getOrNull()
        }
    }

    /** Team names per league from [events], for guide titles that only name the teams. */
    fun teams(events: List<Event>): Map<String, Set<String>> =
        events.groupBy { it.league }.mapValues { (_, list) -> list.flatMap { it.home.names + it.away.names }.toSet() }

    // "A vs B", "A at B", "A @ B": the title names its teams (as SportsFinder reads it).
    private val MATCHUP = Regex("""\S\s+(vs\.?|v\.?|at|@)\s+\S""", RegexOption.IGNORE_CASE)

    private fun words(s: String) = " " + s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "

    private fun mentions(title: String, team: Team) = team.names.any { title.contains(words(it)) }

    /** "US: FOX 5 HD" carries "Fox"; "ESPN 2" carries "ESPN2". */
    fun carries(channel: String, network: String): Boolean {
        val c = channel.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val n = network.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return n.length >= 2 && c.contains(n)
    }

    /**
     * The scoreboard game an airing shows. Teams in the title decide it; a title without teams
     * ("MLB Baseball", "Monday Night Football") takes the one game in the league starting within
     * 30 minutes, or among several the one on the channel's network.
     */
    fun match(league: String, title: String, startMs: Long, channels: List<String>, events: List<Event>): Event? {
        val candidates = events.filter { it.league == league && kotlin.math.abs(it.startMs - startMs) <= 3 * 3_600_000L }
        if (candidates.isEmpty()) return null
        val t = words(title)
        val both = candidates.filter { mentions(t, it.home) && mentions(t, it.away) }
        if (both.isNotEmpty()) return both.minBy { kotlin.math.abs(it.startMs - startMs) }
        // Teams named, but not one of these games: someone else's game.
        if (MATCHUP.containsMatchIn(title) || candidates.any { mentions(t, it.home) || mentions(t, it.away) }) return null
        val near = candidates.filter { kotlin.math.abs(it.startMs - startMs) <= 30 * 60_000L }
        val onNetwork = near.filter { e -> e.networks.any { n -> channels.any { carries(it, n) } } }
        return onNetwork.singleOrNull() ?: near.singleOrNull()
    }

    private val LIVE_TITLE = Regex("""\blive\b""", RegexOption.IGNORE_CASE)

    /**
     * An airing that is on now but most likely a rerun: the league's scoreboard came back and has
     * no game within three hours of it, and the guide doesn't call it live. Wednesday-night
     * "College Football" on a conference network is last Saturday's game.
     */
    fun likelyReplay(game: SportsFinder.Game, events: List<Event>): Boolean {
        if (LIVE_TITLE.containsMatchIn(game.title) || game.league !in FEEDS) return false
        val league = events.filter { it.league == game.league }
        return league.isNotEmpty() && league.none { kotlin.math.abs(it.startMs - game.startMs) <= 3 * 3_600_000L }
    }

    fun match(game: SportsFinder.Game, events: List<Event>): Event? =
        match(game.league, game.title, game.startMs, game.feeds.map { it.network }, events)
}
