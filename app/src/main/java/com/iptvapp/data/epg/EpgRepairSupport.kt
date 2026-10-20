package com.iptvapp.data.epg

import com.iptvapp.util.SeekPreview
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** Where a guide source comes from: the provider's own xmltv.php, one the user added, or MKTV's public stand-in. */
enum class EpgSourceKind { PROVIDER, CUSTOM, PUBLIC }

/** How a source's last refresh went. */
enum class EpgSourceResult { OK, FAILED, EMPTY }

/** MAP: listings from the chosen guide channel. AUTO: automatic matching, with only a time offset. */
enum class EpgOverrideMode { MAP, AUTO }

/**
 * Identities for sources and providers that hold no secret: a guide address carries the
 * provider's username and password, so it is only ever stored as a salted hash, with a label
 * made from its kind and host.
 */
object EpgSourceKeys {
    fun sourceKey(url: String, salt: String): String = "s" + SeekPreview.sha256(salt + "\nsource\n" + url.trim()).take(24)

    fun providerKey(serverUrl: String, username: String, salt: String): String =
        "p" + SeekPreview.sha256(salt + "\nprovider\n" + serverUrl.trim().trimEnd('/').lowercase(Locale.US) + "\n" + username).take(24)

    fun label(kind: EpgSourceKind, url: String, providerName: String?): String = when (kind) {
        EpgSourceKind.PROVIDER -> if (providerName.isNullOrBlank()) "Provider guide" else "Provider guide ($providerName)"
        EpgSourceKind.PUBLIC -> "Public US guide"
        EpgSourceKind.CUSTOM -> host(url)?.let { "Custom guide ($it)" } ?: "Custom guide"
    }

    /** The host only: never a path or query, which can hold credentials. */
    fun host(url: String): String? = try {
        java.net.URI(url.trim()).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

/**
 * A few upcoming listings of a guide channel, kept in the catalog as one short string
 * ("start\tstop\ttitle" lines), so a feed can be recognised when matching by hand.
 */
object EpgSamples {
    const val MAX = 3
    private const val MAX_TITLE = 80

    data class Listing(val startSec: Long, val stopSec: Long, val title: String)

    fun encode(listings: List<Listing>): String = listings.take(MAX).joinToString("\n") { l ->
        val title = l.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_TITLE)
        "${l.startSec}\t${l.stopSec}\t$title"
    }

    fun decode(sample: String): List<Listing> = sample.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val stop = parts[1].toLongOrNull() ?: return@mapNotNull null
        Listing(start, stop, parts[2])
    }.toList()
}

/**
 * A per-channel time correction, for a guide whose times are really off (not a display time
 * zone: XMLTV times carry their own offset and are shown in the device's zone, with its own
 * daylight-saving rules). Stored as minutes added to the source's times; the source's own
 * timestamps are never rewritten in the catalog.
 */
object EpgTimeOffset {
    const val MIN_MINUTES = -12 * 60
    const val MAX_MINUTES = 12 * 60
    const val STEP_MINUTES = 15

    /** The quick choices offered; anything else valid is a custom value. */
    val CHOICES = listOf(-120, -60, -30, 30, 60, 120)

    fun isValid(minutes: Int) = minutes in MIN_MINUTES..MAX_MINUTES && minutes % STEP_MINUTES == 0

    fun seconds(minutes: Int): Long = minutes * 60L

    fun label(minutes: Int): String {
        if (minutes == 0) return "Automatic (no offset)"
        val sign = if (minutes > 0) "+" else "−"
        val h = abs(minutes) / 60
        val m = abs(minutes) % 60
        return sign + when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    /** A source time moved by [minutes], shown in [zone]: daylight saving comes from the zone's rules. */
    fun displayed(startSec: Long, minutes: Int, zone: ZoneId) =
        Instant.ofEpochSecond(startSec + seconds(minutes)).atZone(zone).toLocalDateTime()

    /**
     * An offset the evidence supports, or null: the provider's own short guide and the XMLTV
     * guide list the same programs for one channel, and nearly all of them disagree by the same
     * whole quarter hour. Returns the minutes to add to the XMLTV listings. One odd listing, or
     * too few shared ones, suggests nothing.
     */
    fun suggest(reference: List<EpgSamples.Listing>, xmltv: List<EpgSamples.Listing>): Int? {
        if (reference.isEmpty() || xmltv.isEmpty()) return null
        val byTitle = xmltv.groupBy { normalizeTitle(it.title) }
        val deltas = ArrayList<Long>()
        for (r in reference) {
            val same = byTitle[normalizeTitle(r.title)] ?: continue
            // The nearest listing with that title: a daily show repeats.
            val nearest = same.minByOrNull { abs(it.startSec - r.startSec) } ?: continue
            val d = r.startSec - nearest.startSec
            if (abs(d) <= MAX_MINUTES * 60L) deltas += d
        }
        if (deltas.size < MIN_PAIRS) return null
        val common = deltas.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        if (common.value * 5 < deltas.size * 4) return null
        val minutes = (common.key / 60).toInt()
        return minutes.takeIf { it != 0 && isValid(it) }
    }

    private const val MIN_PAIRS = 3

    private fun normalizeTitle(t: String) = t.lowercase(Locale.US).filter { it.isLetterOrDigit() }
}
