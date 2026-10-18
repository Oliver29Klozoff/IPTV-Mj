package com.iptvapp.util

/**
 * How far one channel's sound sits from its picture, in milliseconds.
 * Positive is sound later than the picture. Each channel keeps its own value.
 * The map is a device setting: one TV's delay is not another's.
 */
object ChannelAudioDelay {
    const val STEP_MS = 50
    const val LIMIT_MS = 2_000

    /** Live TV and a replay of that channel. A movie, episode, or recording has no channel delay. */
    fun offersFor(isVod: Boolean, isCatchup: Boolean, isRecording: Boolean): Boolean =
        !isRecording && (!isVod || isCatchup)

    fun key(serverIndex: Int, streamId: Int): String? =
        if (streamId < 0) null else "$serverIndex:$streamId"

    fun clamp(ms: Int): Int = ms.coerceIn(-LIMIT_MS, LIMIT_MS)

    /** [direction] -1 is earlier, +1 is later. */
    fun nudge(currentMs: Int, direction: Int): Int =
        clamp(currentMs + direction.coerceIn(-1, 1) * STEP_MS)

    fun label(ms: Int): String = when {
        ms > 0 -> "Sound $ms ms late"
        ms < 0 -> "Sound ${-ms} ms early"
        else -> "Sound in sync"
    }

    /**
     * Added to a video frame's timestamp. A positive audio delay shows the picture sooner,
     * so the sound lands later relative to it. Audio is the playback clock, so holding
     * the sound itself would hold the picture too.
     */
    fun videoShiftUs(audioDelayMs: Int): Long = -clamp(audioDelayMs).toLong() * 1_000L

    fun delayMs(stored: String, key: String): Int = decode(stored)[key] ?: 0

    fun write(stored: String, key: String, ms: Int): String {
        if (!isKey(key)) return stored
        val map = decode(stored).toMutableMap()
        val clamped = clamp(ms)
        if (clamped == 0) map.remove(key) else map[key] = clamped
        return encode(map)
    }

    fun decode(stored: String): Map<String, Int> {
        if (stored.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (part in stored.split(',')) {
            val bits = part.split('=', limit = 2)
            if (bits.size != 2) continue
            val key = bits[0]
            if (!isKey(key)) continue
            val ms = bits[1].toIntOrNull() ?: continue
            val clamped = clamp(ms)
            if (clamped != 0) out[key] = clamped
        }
        return out
    }

    /**
     * Where each previous extra provider landed in the new list. Matched by [oldIds] and
     * [newIds], which are stable for one account and empty when the slot has no account.
     * A provider that is gone, or that now occupies a slot with a different account, is absent.
     */
    fun movedIndices(oldIds: List<String>, newIds: List<String>): Map<Int, Int> {
        val used = mutableSetOf<Int>()
        val out = LinkedHashMap<Int, Int>()
        for ((oldIndex, id) in oldIds.withIndex()) {
            if (id.isEmpty()) {
                // A pasted playlist has no address. Adding a provider leaves it in place.
                // A shorter list can slide a different playlist into this slot, so that case
                // uses shiftedAfterRemoval instead of guessing.
                if (newIds.size >= oldIds.size && oldIndex < newIds.size && newIds[oldIndex].isEmpty()) {
                    out[oldIndex] = oldIndex
                }
                continue
            }
            val newIndex = newIds.withIndex().firstOrNull { (index, next) ->
                index !in used && next.isNotEmpty() && next == id
            }?.index ?: continue
            used += newIndex
            out[oldIndex] = newIndex
        }
        return out
    }

    /** Extra providers after one index is removed: later ones move down, that one is gone. */
    fun shiftedAfterRemoval(removedIndex: Int, oldCount: Int): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        if (removedIndex < 0) return out
        for (index in 0 until oldCount) {
            when {
                index < removedIndex -> out[index] = index
                index > removedIndex -> out[index] = index - 1
            }
        }
        return out
    }

    /**
     * Move extra-provider delays onto [oldExtraToNew]. The primary (-1) stays.
     * An extra provider with no entry is dropped, so a different account does not inherit it.
     */
    fun reindex(stored: String, oldExtraToNew: Map<Int, Int>): String {
        val next = LinkedHashMap<String, Int>()
        for ((rawKey, ms) in decode(stored)) {
            val server = parseServer(rawKey)
            val target = if (server == -1) -1 else oldExtraToNew[server] ?: continue
            val moved = key(target, parseStream(rawKey)) ?: continue
            next[moved] = ms
        }
        return encode(next)
    }

    /** Forget one provider. Used when the primary account itself is replaced. */
    fun dropServer(stored: String, serverIndex: Int): String {
        val next = LinkedHashMap<String, Int>()
        for ((rawKey, ms) in decode(stored)) {
            if (parseServer(rawKey) == serverIndex) continue
            next[rawKey] = ms
        }
        return encode(next)
    }

    fun encode(map: Map<String, Int>): String =
        map.entries
            .mapNotNull { (key, ms) ->
                if (!isKey(key)) return@mapNotNull null
                val clamped = clamp(ms)
                if (clamped == 0) null else "$key=$clamped"
            }
            .sorted()
            .joinToString(",")

    private fun isKey(raw: String): Boolean = key(parseServer(raw), parseStream(raw)) == raw

    private fun parseServer(key: String): Int =
        key.substringBefore(':', "").toIntOrNull() ?: Int.MIN_VALUE

    private fun parseStream(key: String): Int =
        key.substringAfter(':', "").toIntOrNull() ?: Int.MIN_VALUE
}

/** Read on the video thread, written when the viewer nudges the sound or changes channel. */
class ChannelAudioDelayState {
    @Volatile var offsetMs: Int = 0
}
