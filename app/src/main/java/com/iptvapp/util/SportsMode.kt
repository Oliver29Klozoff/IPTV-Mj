package com.iptvapp.util

import java.util.Locale

/**
 * Sports Mode and Smart Catch-Up decisions. Nothing here touches the player, disk, or network.
 *
 * Sports Mode is off unless a setting or a one-viewing override says otherwise, so ordinary
 * live TV keeps its existing buffer. A score is shown only when the guide title already
 * contains one. Favorite teams are a short list used as shortcuts, not a second guide.
 */
object SportsMode {
    const val OFF = 0
    const val GAMES = 1
    const val ALL_LIVE = 2

    /** Local replay used for this viewing when Sports Mode is on and Timeshift is Off. Not a saved setting. */
    const val SESSION_REPLAY_MINUTES = 5

    const val MIN_BUFFER_MS = 2_000
    const val MAX_BUFFER_MS = 8_000
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
    const val TARGET_OFFSET_MS = 3_000L
    const val MIN_OFFSET_MS = 1_000L
    const val MAX_OFFSET_MS = 20_000L

    /** A panel mode counts when its refresh is within this many Hz of the video rate, or twice that rate. */
    const val FRAME_MATCH_HZ = 1f

    const val MAX_FAVORITE_TEAMS = 12
    const val MAX_TEAM_CHARS = 40

    const val BACK_BUFFER_MS = 120_000

    data class BufferProfile(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val backBufferMs: Int,
        val retainBackBufferFromKeyframe: Boolean
    )

    data class RefreshMode(val id: Int, val refreshHz: Float, val width: Int = 0, val height: Int = 0)

    fun normalizeSetting(value: Int): Int = when (value) {
        OFF, GAMES, ALL_LIVE -> value
        else -> OFF
    }

    fun settingLabel(value: Int): String = when (normalizeSetting(value)) {
        GAMES -> "Games"
        ALL_LIVE -> "All live TV"
        else -> "Off"
    }

    /**
     * @param sessionOn null follows [setting]. True or false is this viewing only and wins.
     * The caller keeps this off for VOD, catch-up, recordings, casting, and audio-only.
     */
    fun active(setting: Int, programmeTitle: String?, sessionOn: Boolean?): Boolean {
        if (sessionOn != null) return sessionOn
        return when (normalizeSetting(setting)) {
            ALL_LIVE -> true
            GAMES -> !programmeTitle.isNullOrBlank() && SportsFinder.leagueOf(programmeTitle) != null
            else -> false
        }
    }

    /**
     * A sports-only replay pump. Once this channel has already settled on the provider
     * window, do not probe again: opening the guide overlay must not restart playback.
     */
    fun needsSessionBuffer(
        sportsOn: Boolean,
        savedMinutes: Int,
        sessionRunning: Boolean,
        directFallback: Boolean,
        nativeSettled: Boolean
    ): Boolean = sportsOn && savedMinutes == 0 && !sessionRunning && !directFallback && !nativeSettled

    /**
     * Minutes of local replay. A saved Timeshift window is never shortened just because
     * Sports Mode is on. Timeshift Off plus Sports Mode uses [SESSION_REPLAY_MINUTES]
     * for this viewing and does not rewrite the saved Off.
     */
    fun replayMinutes(savedMinutes: Int, sportsOn: Boolean): Int {
        if (savedMinutes > 0) return savedMinutes
        return if (sportsOn) SESSION_REPLAY_MINUTES else 0
    }

    /** Fastest buffer that still holds a short stall. Off keeps the two existing live profiles. */
    fun loadControl(sportsOn: Boolean, extraBuffering: Boolean): BufferProfile {
        if (sportsOn) {
            return BufferProfile(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                BACK_BUFFER_MS,
                true
            )
        }
        return if (extraBuffering) {
            BufferProfile(90_000, 240_000, 10_000, 15_000, BACK_BUFFER_MS, true)
        } else {
            BufferProfile(50_000, 120_000, 5_000, 10_000, BACK_BUFFER_MS, true)
        }
    }

    /**
     * Display mode id whose refresh is closest to the video frame rate, or to twice that
     * rate (24 to 48, 25 to 50, 30 to 60). The 1x match wins when both are close.
     * Returns 0 when nothing is within [FRAME_MATCH_HZ].
     * When the current output size is known, only modes of that size are eligible,
     * so a refresh match cannot drop a 4K panel to 1080p.
     */
    fun displayModeId(
        frameRate: Float,
        modes: List<RefreshMode>,
        currentWidth: Int = 0,
        currentHeight: Int = 0
    ): Int {
        val pool = if (currentWidth > 0 && currentHeight > 0) {
            modes.filter { it.width == currentWidth && it.height == currentHeight }
        } else {
            modes
        }
        if (!(frameRate > 1f) || pool.isEmpty()) return 0
        var bestId = 0
        var bestDelta = FRAME_MATCH_HZ + 0.0001f
        var bestRank = 2
        for (mode in pool) {
            if (mode.id == 0 || !(mode.refreshHz > 0f)) continue
            val direct = kotlin.math.abs(frameRate - mode.refreshHz)
            val doubled = kotlin.math.abs(frameRate * 2f - mode.refreshHz)
            val rank: Int
            val delta: Float
            if (direct <= doubled) {
                rank = 0
                delta = direct
            } else {
                rank = 1
                delta = doubled
            }
            val closer = delta < bestDelta || (delta == bestDelta && rank < bestRank)
            if (delta <= FRAME_MATCH_HZ && closer) {
                bestDelta = delta
                bestRank = rank
                bestId = mode.id
            }
        }
        return bestId
    }

    /** Score text already written in the title, or null. Never invents a score. */
    fun scoreInTitle(title: String): String? {
        val body = detailAfterLeague(title)
        commaScore(body)?.let { return it }
        if (SportsFinder.leagueOf(title) == null) return null
        return hyphenScore(body)
    }

    fun scoreDescription(score: String): String {
        val pair = Regex("""^(\d+)\u2013(\d+)$""").find(score)
        return if (pair != null) "Score ${pair.groupValues[1]} to ${pair.groupValues[2]}" else "Score $score"
    }

    /** The two sides of "A vs B", "A at B", or "A @ B", after a league prefix. Empty when it is not a matchup. */
    fun teamsInTitle(title: String): List<String> {
        var body = detailAfterLeague(title)
        body = body.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim()
        body = body.substringBefore(" - ").trim()
        val match = MATCHUP_TEAMS.find(body) ?: return emptyList()
        val left = cleanTeam(match.groupValues[1]) ?: return emptyList()
        val right = cleanTeam(match.groupValues[2]) ?: return emptyList()
        return listOf(left, right)
    }

    fun parseTeams(stored: String): List<String> {
        val out = ArrayList<String>(MAX_FAVORITE_TEAMS)
        val seen = HashSet<String>()
        for (raw in stored.split('\n')) {
            val name = cleanTeam(raw.replace('\r', ' ')) ?: continue
            if (!seen.add(name.lowercase(Locale.US))) continue
            out += name
            if (out.size == MAX_FAVORITE_TEAMS) break
        }
        return out
    }

    fun normalizeStoredTeams(stored: String): String = parseTeams(stored).joinToString("\n")

    fun addFavorite(stored: String, name: String): String {
        val teams = parseTeams(stored).toMutableList()
        val clean = cleanTeam(name) ?: return normalizeStoredTeams(stored)
        if (teams.any { it.equals(clean, ignoreCase = true) }) return teams.joinToString("\n")
        if (teams.size >= MAX_FAVORITE_TEAMS) return teams.joinToString("\n")
        teams += clean
        return teams.joinToString("\n")
    }

    fun removeFavorite(stored: String, name: String): String {
        val key = name.trim().lowercase(Locale.US)
        return parseTeams(stored).filterNot { it.lowercase(Locale.US) == key }.joinToString("\n")
    }

    fun titleHasTeam(title: String, team: String): Boolean {
        val clean = cleanTeam(team) ?: return false
        return TEAM_EDGE.containsMatchIn(title, clean)
    }

    /** The live game for this team, or the soonest one still ahead. Null when the guide has none. */
    fun gameForTeam(games: List<SportsFinder.Game>, team: String, nowMs: Long): SportsFinder.Game? {
        val hits = games.filter { titleHasTeam(it.title, team) }
        return hits.filter { it.isLive(nowMs) }.minByOrNull { it.startMs }
            ?: hits.filter { it.startMs >= nowMs }.minByOrNull { it.startMs }
    }

    private fun detailAfterLeague(title: String): String {
        val colon = title.indexOf(':')
        return if (colon >= 0) title.substring(colon + 1).trim() else title.trim()
    }

    private fun commaScore(body: String): String? {
        val match = COMMA_SCORE.find(body) ?: return null
        val leftName = match.groupValues[1].trim()
        val rightName = match.groupValues[3].trim()
        val left = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[4].toIntOrNull() ?: return null
        if (!plausible(left) || !plausible(right)) return null
        if (!leftName.any { it.isLetter() } || !rightName.any { it.isLetter() }) return null
        return "$leftName $left, $rightName $right"
    }

    private fun hyphenScore(body: String): String? {
        val match = HYPHEN_SCORE.findAll(body).firstOrNull { candidate ->
            val left = candidate.groupValues[1].toIntOrNull() ?: return@firstOrNull false
            val right = candidate.groupValues[2].toIntOrNull() ?: return@firstOrNull false
            plausible(left) && plausible(right) && !clockRange(body, candidate.range.last)
        } ?: return null
        return "${match.groupValues[1]}\u2013${match.groupValues[2]}"
    }

    private fun plausible(n: Int) = n in 0..199

    private fun clockRange(body: String, endIndex: Int): Boolean {
        val after = body.substring((endIndex + 1).coerceAtMost(body.length)).trimStart().lowercase(Locale.US)
        return after.startsWith("am") || after.startsWith("pm") || after.startsWith("a.m") || after.startsWith("p.m")
    }

    private fun cleanTeam(raw: String): String? {
        var s = raw.trim().replace(Regex("""\s+"""), " ")
        s = s.replace(Regex("""\s+\d{1,3}\s*$"""), "").trim()
        s = s.trim(' ', '-', '\u2013', '.', ',', ':')
        if (s.length > MAX_TEAM_CHARS) s = s.take(MAX_TEAM_CHARS).trim()
        if (s.length < 2 || !s.any { it.isLetter() }) return null
        return s
    }

    private val HYPHEN_SCORE = Regex("""(?<!\d)(\d{1,3})\s*[-–]\s*(\d{1,3})(?!\d)""")
    private val COMMA_SCORE = Regex(
        """([A-Za-z0-9][A-Za-z0-9 .'-]{0,40}?)\s+(\d{1,3})\s*,\s*([A-Za-z0-9][A-Za-z0-9 .'-]{0,40}?)\s+(\d{1,3})(?!\d)"""
    )
    private val MATCHUP_TEAMS = Regex("""^(.+?)\s+(?:vs\.?|v\.?|at|@)\s+(.+)$""", RegexOption.IGNORE_CASE)

    private object TEAM_EDGE {
        fun containsMatchIn(title: String, team: String): Boolean {
            val pattern = Regex("(?<![A-Za-z0-9])" + Regex.escape(team) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            return pattern.containsMatchIn(title)
        }
    }
}

/**
 * The catch-up prompt on the live player. Start From Beginning uses the existing
 * catch-up URL. Watch Live only dismisses this airing.
 */
object SmartCatchUp {
    const val OFFER_AFTER_MS = 35L * 60L * 1_000L

    fun shouldOffer(
        archiveEnabled: Boolean,
        programmeStartMs: Long,
        programmeStopMs: Long,
        nowMs: Long,
        alreadyOnCatchup: Boolean,
        dismissedForStartMs: Long,
        blocked: Boolean
    ): Boolean {
        if (blocked || !archiveEnabled || alreadyOnCatchup) return false
        if (programmeStartMs <= 0L || programmeStopMs <= programmeStartMs) return false
        if (nowMs < programmeStartMs || nowMs >= programmeStopMs) return false
        if (dismissedForStartMs == programmeStartMs) return false
        return nowMs - programmeStartMs >= OFFER_AFTER_MS
    }

    fun startedLabel(programmeStartMs: Long, nowMs: Long): String {
        val mins = ((nowMs - programmeStartMs).coerceAtLeast(0L) / 60_000L).toInt()
        return "Started $mins min ago"
    }
}
