package com.iptvapp.util

import java.util.Locale
import kotlin.math.abs

/**
 * Audio & subtitle synchronization (the player's "Audio & Subtitle Sync" panel).
 *
 * One convention everywhere, phone and TV alike:
 *  - Audio offset: positive = the sound is heard later than the picture; negative = earlier.
 *    It is applied by moving when each video frame is shown (AudioDelayVideoRenderer), because
 *    the sound is the playback clock: holding the sound back would hold the picture with it.
 *    So it works on every audio output, passthrough (Dolby Digital, E-AC3) and Bluetooth
 *    included, and never re-encodes, speeds up or buffers the sound.
 *  - Subtitle offset: positive = subtitles appear later; negative = earlier. Applied by giving
 *    the subtitle renderer a shifted clock (SubtitleDelayRenderer), so both the start and the
 *    end of every cue move together, whatever the subtitle format.
 *
 * What is saved, and where it applies:
 *  - Live TV and replays of a channel: per channel (provider + channel). The channel's audio
 *    offset stays in the store the "Sound timing" setting always used (ChannelAudioDelay);
 *    its subtitle offset is here, under [channelKey].
 *  - Movies, episodes, recordings and files on the device: per item, under [movieKey],
 *    [episodeKey] or [fileKey]. Each key carries a short hash of the title, so a provider that
 *    reuses an id for a different video never inherits another video's offsets.
 *
 * Precedence when an item opens: an adjustment made in this session (not saved), then the
 * item's saved values, then zero.
 */
object PlaybackSync {
    const val AUDIO_LIMIT_MS = ChannelAudioDelay.LIMIT_MS
    const val SUBTITLE_LIMIT_MS = 10_000
    /** The step buttons: fine, normal, large. */
    val STEPS_MS = listOf(50, 100, 500)
    /** Saved items kept at most; the least recently saved go first. */
    const val MAX_SAVED = 1_000

    data class Offsets(val audioMs: Int = 0, val subtitleMs: Int = 0) {
        val isZero get() = audioMs == 0 && subtitleMs == 0
    }

    fun clampAudio(ms: Long): Int = ms.coerceIn(-AUDIO_LIMIT_MS.toLong(), AUDIO_LIMIT_MS.toLong()).toInt()
    fun clampSubtitle(ms: Long): Int = ms.coerceIn(-SUBTITLE_LIMIT_MS.toLong(), SUBTITLE_LIMIT_MS.toLong()).toInt()

    /** "+250 ms", "-1.5 s", "0 ms". */
    fun label(ms: Int): String {
        if (ms == 0) return "0 ms"
        val sign = if (ms > 0) "+" else "−"
        val a = abs(ms)
        return if (a < 1_000) "$sign$a ms" else {
            val s = a / 1000.0
            sign + (if (a % 1000 == 0) "${a / 1000} s" else String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.') + " s")
        }
    }

    fun audioMeaning(ms: Int): String = when {
        ms > 0 -> "Sound ${label(ms).drop(1)} later than the picture"
        ms < 0 -> "Sound ${label(ms).drop(1)} earlier than the picture"
        else -> "Sound as the stream has it"
    }

    fun subtitleMeaning(ms: Int): String = when {
        ms > 0 -> "Subtitles ${label(ms).drop(1)} later"
        ms < 0 -> "Subtitles ${label(ms).drop(1)} earlier"
        else -> "Subtitles at their own times"
    }

    /** Spoken (TalkBack): "plus 250 milliseconds", "minus 1.5 seconds". */
    fun spoken(ms: Int): String {
        if (ms == 0) return "zero"
        val sign = if (ms > 0) "plus" else "minus"
        val a = abs(ms)
        return if (a < 1000) "$sign $a milliseconds" else "$sign ${label(a).removePrefix("+").removeSuffix(" s")} seconds"
    }

    // ── Keys (no addresses, usernames or passwords: ids and hashes only) ─────────

    /** A live channel (and its replays), on one provider slot. */
    fun channelKey(serverIndex: Int, streamId: Int): String? = if (streamId < 0) null else "c:$serverIndex:$streamId"

    fun movieKey(serverIndex: Int, streamId: Int, title: String): String? =
        if (streamId < 0) null else "m:$serverIndex:$streamId:${titleHash(title)}"

    fun episodeKey(serverIndex: Int, episodeId: String, title: String): String? {
        val id = episodeId.filter { it.isLetterOrDigit() }.take(32)
        return if (id.isEmpty()) null else "e:$serverIndex:$id:${titleHash(title)}"
    }

    /** A recording or a file on the device: by its location (hashed), and its title. */
    fun fileKey(location: String, title: String): String? =
        if (location.isBlank()) null else "f:${SeekPreview.sha256(location).take(16)}:${titleHash(title)}"

    fun titleHash(title: String): String =
        SeekPreview.sha256(title.trim().lowercase(Locale.US)).take(8)

    /** The provider slot a key belongs to, or null for one that has none (files). */
    fun serverOf(key: String): Int? {
        val parts = key.split(':')
        return if (parts.size >= 3 && parts[0] in setOf("c", "m", "e")) parts[1].toIntOrNull() else null
    }

    fun isKey(key: String): Boolean {
        val parts = key.split(':')
        return when (parts.firstOrNull()) {
            "c" -> parts.size == 3 && parts[1].toIntOrNull() != null && (parts[2].toIntOrNull() ?: -1) >= 0
            "m" -> parts.size == 4 && parts[1].toIntOrNull() != null && (parts[2].toIntOrNull() ?: -1) >= 0 && parts[3].length == 8
            "e" -> parts.size == 4 && parts[1].toIntOrNull() != null && parts[2].isNotEmpty() && parts[2].all { it.isLetterOrDigit() } && parts[3].length == 8
            "f" -> parts.size == 3 && parts[1].length == 16 && parts[2].length == 8
            else -> false
        }
    }

    // ── The saved store: "key=audio/subtitle" entries, least recently saved first ─

    fun decode(stored: String): LinkedHashMap<String, Offsets> {
        val out = LinkedHashMap<String, Offsets>()
        if (stored.isBlank()) return out
        for (part in stored.split(',')) {
            val bits = part.split('=', limit = 2)
            if (bits.size != 2 || !isKey(bits[0])) continue
            val values = bits[1].split('/', limit = 2)
            if (values.size != 2) continue
            val a = values[0].toLongOrNull() ?: continue
            val s = values[1].toLongOrNull() ?: continue
            val o = Offsets(clampAudio(a), clampSubtitle(s))
            if (!o.isZero) out[bits[0]] = o
        }
        return out
    }

    fun encode(map: Map<String, Offsets>): String =
        map.entries.filter { isKey(it.key) && !it.value.isZero }
            .joinToString(",") { (k, o) -> "$k=${clampAudio(o.audioMs.toLong())}/${clampSubtitle(o.subtitleMs.toLong())}" }

    fun read(stored: String, key: String): Offsets = decode(stored)[key] ?: Offsets()

    /** Saves [offsets] for [key] (zero removes it), as the most recent entry, keeping at most [MAX_SAVED]. */
    fun write(stored: String, key: String, offsets: Offsets): String {
        if (!isKey(key)) return stored
        val map = decode(stored)
        map.remove(key)
        val clean = Offsets(clampAudio(offsets.audioMs.toLong()), clampSubtitle(offsets.subtitleMs.toLong()))
        if (!clean.isZero) map[key] = clean
        while (map.size > MAX_SAVED) map.remove(map.keys.first())
        return encode(map)
    }

    /** Extra providers moved ([ChannelAudioDelay.movedIndices]): their entries follow; a gone one's entries go. */
    fun reindex(stored: String, oldExtraToNew: Map<Int, Int>): String {
        val out = LinkedHashMap<String, Offsets>()
        for ((key, o) in decode(stored)) {
            val server = serverOf(key)
            if (server == null || server == -1) { out[key] = o; continue }
            val target = oldExtraToNew[server] ?: continue
            val parts = key.split(':').toMutableList()
            parts[1] = target.toString()
            out[parts.joinToString(":")] = o
        }
        return encode(out)
    }

    /** One provider replaced: its entries go. */
    fun dropServer(stored: String, serverIndex: Int): String =
        encode(decode(stored).filterKeys { serverOf(it) != serverIndex })

    /** Why the audio offset can't act now, or null when it can. */
    fun audioUnavailableReason(tunneled: Boolean, casting: Boolean, listeningOnly: Boolean, hasVideo: Boolean): String? = when {
        casting -> "Playing on another screen: its own player sets the timing"
        listeningOnly -> "Listening only: there is no picture to line the sound up with"
        tunneled -> "Tunneled playback is on (Settings): the TV shows the picture itself, so its timing can't be moved"
        !hasVideo -> "This stream has no picture to line the sound up with"
        else -> null
    }
}
