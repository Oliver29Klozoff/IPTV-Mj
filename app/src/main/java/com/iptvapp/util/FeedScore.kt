package com.iptvapp.util

import com.iptvapp.data.local.entities.FeedStatsEntity

/**
 * How good one provider's copy of a channel has been, from 0 to 100. A copy with no history
 * scores [UNKNOWN], so a fresh copy is tried ahead of a known-bad one but behind a proven one.
 *
 * Starts that played (half the score), stalls per hour of watching (30%), and time to first
 * picture (20%) all lean toward a neutral prior until there is history, so one lucky start
 * cannot outrank a channel with a long clean record. A failure in the last hour costs extra,
 * because a provider that just failed is likely to fail again.
 */
object FeedScore {
    const val UNKNOWN = 50
    const val MAX_OUTCOMES = 10

    /** Watching time behind the stall rate is capped, so a bad week fades instead of sticking. */
    const val MAX_WATCHED_MINUTES = 600f

    private const val PRIOR_STARTS = 2.0
    private const val PRIOR_SUCCESS = 0.8
    private const val PRIOR_MINUTES = 10.0
    private const val PRIOR_STALLS_PER_HOUR = 2.0
    private const val FAST_TUNE_MS = 1_500.0
    private const val SLOW_TUNE_MS = 10_000.0

    fun score(stats: FeedStatsEntity?, nowMs: Long): Int {
        if (stats == null || (stats.outcomes.isEmpty() && stats.watchedMinutes <= 0f)) return UNKNOWN
        val starts = stats.outcomes.length
        val played = stats.outcomes.count { it == '1' }
        val success = (played + PRIOR_SUCCESS * PRIOR_STARTS) / (starts + PRIOR_STARTS)

        val minutes = stats.watchedMinutes.toDouble()
        val stallRate = (stats.stallsPerHour * minutes + PRIOR_STALLS_PER_HOUR * PRIOR_MINUTES) /
            (minutes + PRIOR_MINUTES)
        // Six stalls an hour, one every ten minutes, halves this part.
        val smooth = 1.0 / (1.0 + stallRate / 6.0)

        val tune = if (stats.tuneMs <= 0L) 0.5
        else (1.0 - (stats.tuneMs - FAST_TUNE_MS) / (SLOW_TUNE_MS - FAST_TUNE_MS)).coerceIn(0.0, 1.0)

        val sinceFailure = nowMs - stats.lastFailureAt
        val recent = when {
            stats.lastFailureAt <= 0L -> 1.0
            sinceFailure < 10 * 60_000L -> 0.5
            sinceFailure < 60 * 60_000L -> 0.8
            else -> 1.0
        }
        return (100.0 * (0.5 * success + 0.3 * smooth + 0.2 * tune) * recent).toInt().coerceIn(0, 100)
    }

    /** A channel start: [tuneMs] is open-to-first-picture, ignored for a failure. */
    fun afterStart(stats: FeedStatsEntity, played: Boolean, tuneMs: Long, nowMs: Long): FeedStatsEntity {
        val outcomes = (stats.outcomes + if (played) '1' else '0').takeLast(MAX_OUTCOMES)
        val tune = when {
            !played || tuneMs <= 0L -> stats.tuneMs
            stats.tuneMs <= 0L -> tuneMs
            else -> (stats.tuneMs * 0.7 + tuneMs * 0.3).toLong()
        }
        return stats.copy(
            outcomes = outcomes,
            tuneMs = tune,
            lastFailureAt = if (played) stats.lastFailureAt else nowMs,
            updatedAt = nowMs,
        )
    }

    /** [minutes] of watching that had [stalls] rebuffers in it. */
    fun afterWatching(stats: FeedStatsEntity, minutes: Float, stalls: Int, nowMs: Long): FeedStatsEntity {
        if (minutes <= 0f) return stats
        val before = stats.watchedMinutes
        val rate = (stats.stallsPerHour * before + stalls * 60f) / (before + minutes)
        return stats.copy(
            stallsPerHour = rate,
            watchedMinutes = (before + minutes).coerceAtMost(MAX_WATCHED_MINUTES),
            updatedAt = nowMs,
        )
    }
}

/**
 * Decides when a live channel has stalled often enough to move to a backup copy. A single
 * stall is normal; three inside two minutes is a feed going bad. The first seconds after a
 * start are ignored, because a fresh stream often rebuffers once while it settles, and after a
 * switch the new copy gets a few minutes before it can be judged.
 */
class StallMonitor(
    private val windowMs: Long = 120_000L,
    private val stallsToSwitch: Int = 3,
    private val settleMs: Long = 15_000L,
    private val cooldownMs: Long = 180_000L,
    private val maxSwitches: Int = 2,
) {
    private val stalls = ArrayDeque<Long>()
    private var startedAt = 0L
    private var lastSwitchAt = 0L
    private var switches = 0

    /** Total rebuffers since the last [drainStalls], for feed scoring. */
    var unreportedStalls: Int = 0
        private set

    /** A new channel the user picked. Resets everything, including the switch budget. */
    fun newChannel(nowMs: Long) {
        stalls.clear()
        startedAt = nowMs
        lastSwitchAt = 0L
        switches = 0
        unreportedStalls = 0
    }

    /** A rebuffer after the channel was already playing. True when it is time to switch. */
    fun onStall(nowMs: Long): Boolean {
        unreportedStalls++
        if (nowMs - startedAt < settleMs) return false
        stalls.addLast(nowMs)
        while (stalls.isNotEmpty() && nowMs - stalls.first() > windowMs) stalls.removeFirst()
        if (switches >= maxSwitches) return false
        if (lastSwitchAt > 0L && nowMs - lastSwitchAt < cooldownMs) return false
        return stalls.size >= stallsToSwitch
    }

    /** The player moved to a backup copy. Its own stalls start from zero. */
    fun switched(nowMs: Long) {
        stalls.clear()
        lastSwitchAt = nowMs
        startedAt = nowMs
        switches++
    }

    fun drainStalls(): Int = unreportedStalls.also { unreportedStalls = 0 }
}
