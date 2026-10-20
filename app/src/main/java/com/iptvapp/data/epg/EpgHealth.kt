package com.iptvapp.data.epg

import com.iptvapp.data.local.dao.EpgCoverageRow
import com.iptvapp.data.local.entities.EpgGuideSourceEntity
import com.iptvapp.data.local.entities.EpgOverrideEntity
import com.iptvapp.data.repository.XmltvEpgRefresh
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** What EPG Health found for one channel. */
enum class EpgIssueKind {
    /** No listings, and a guide channel that has listings looks like it (a suggestion to review). */
    MISSING_FIXABLE,
    /** No listings, and no configured guide has this channel: nothing to repair. */
    MISSING_UNAVAILABLE,
    /** Listings ran out, or end soon while its guide covers much longer. */
    STALE,
    /** Has listings, but from a guide channel whose name contradicts the channel's (feed, station, country, number). */
    SUSPECT,
    /** The provider's own short guide and the XMLTV guide agree on programs but not on times. */
    OFFSET_HINT,
    /** A saved correction, working. */
    CORRECTED,
    /** A saved correction whose guide channel is gone or has no listings. */
    CORRECTION_BROKEN,
    /** Nothing found wrong (listed only in "All channels", for matching by hand). */
    OK,
}

data class EpgIssue(
    val kind: EpgIssueKind,
    val channel: EpgLocalChannel,
    val providerName: String,
    val detail: String,
    val match: EpgMatchResult?,
    val current: EpgGuideChannel?,
    val override: EpgOverrideEntity?,
    /** For OFFSET_HINT: the minutes the evidence suggests adding. */
    val offsetHint: Int?,
    /** "Apply safe matches" may apply [match] (EpgMatcher.safeToApply). */
    val safe: Boolean,
    val lastStop: Long,
)

data class EpgSourceStatus(
    val serverIndex: Int,
    val providerName: String,
    val label: String,
    val kind: EpgSourceKind,
    val record: EpgGuideSourceEntity?,
    /** Plain words: "No future listings", "Last successful refresh was 2 days ago"… */
    val notes: List<String>,
)

data class EpgHealthTotals(
    val checked: Int,
    val withListings: Int,
    val withoutListings: Int,
    val fixable: Int,
    val unavailable: Int,
    val stale: Int,
    val suspect: Int,
    val suggestions: Int,
    val safeSuggestions: Int,
    val corrections: Int,
    val brokenCorrections: Int,
    val lastSuccessfulUpdate: Long,
) {
    /** Channels with usable listings, as a whole percent of those checked. */
    val coveragePercent: Int get() = if (checked == 0) 0 else (withListings * 100L / checked).toInt()
}

data class EpgHealthReport(
    val generatedAt: Long,
    val totals: EpgHealthTotals,
    val issues: List<EpgIssue>,
    val sources: List<EpgSourceStatus>,
    /** Corrections saved for a provider that is no longer in its slot: kept, not applied. */
    val unusedCorrections: List<EpgOverrideEntity>,
)

/** One provider's data for the scan, all read locally. */
data class EpgScanInput(
    val serverIndex: Int,
    val providerName: String,
    val providerKey: String,
    /** The slot's sources, best first (key, kind, label). */
    val sources: List<Triple<String, EpgSourceKind, String>>,
    val channels: List<EpgLocalChannel>,
    val guideChannels: List<EpgGuideChannel>,
    val coverage: List<EpgCoverageRow>,
    /** streamId → an XMLTV row id of its listings ("x_<guide id>_…"). */
    val xmltvRowIds: Map<Int, String>,
    val sourceRecords: List<EpgGuideSourceEntity>,
    val overrides: List<EpgOverrideEntity>,
    /** For OFFSET_HINT: streamId → (provider short guide listings, XMLTV listings). */
    val offsetEvidence: Map<Int, Pair<List<EpgSamples.Listing>, List<EpgSamples.Listing>>> = emptyMap(),
)

/**
 * The health scan itself: pure, over data already read from the database, so it is tested
 * without Android and never touches the network. Cancellable between channels.
 */
object EpgHealthScanner {
    /** A channel whose listings end within this, while its guide reaches past [WELL_COVERED_SEC], is stale. */
    const val ENDS_SOON_SEC = 6 * 3600L
    const val WELL_COVERED_SEC = 24 * 3600L
    /** A source not refreshed successfully for this long gets a note. */
    const val OLD_REFRESH_MS = 48 * 3600_000L

    suspend fun scan(
        inputs: List<EpgScanInput>,
        nowMs: Long,
        lastRefreshMs: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EpgHealthReport {
        val nowSec = nowMs / 1000
        val total = inputs.sumOf { it.channels.size }
        var done = 0
        val issues = ArrayList<EpgIssue>()
        val sources = ArrayList<EpgSourceStatus>()
        val unused = ArrayList<EpgOverrideEntity>()
        var withListings = 0
        var lastSuccess = lastRefreshMs
        for (input in inputs) {
            coroutineContext.ensureActive()
            val rank = input.sources.withIndex().associate { (i, s) -> s.first to i }
            val known = rank.keys
            // Only this provider's current sources count; a list left from an earlier provider in the slot doesn't.
            val guides = input.guideChannels.filter { it.sourceKey in known }
            val index = EpgMatchIndex(guides) { rank[it] ?: Int.MAX_VALUE }
            val records = input.sourceRecords.filter { it.sourceKey in known }.associateBy { it.sourceKey }
            val horizon = records.values.filter { it.lastResult == EpgSourceResult.OK.name }.maxOfOrNull { it.horizonEnd } ?: 0L
            records.values.forEach { if (it.lastSuccessAt > lastSuccess) lastSuccess = it.lastSuccessAt }
            for ((key, kind, label) in input.sources) {
                sources += EpgSourceStatus(input.serverIndex, input.providerName, label, kind, records[key], sourceNotes(records[key], kind, nowMs))
            }
            val anyFailed = records.values.any { it.lastResult != EpgSourceResult.OK.name }
            val allEmpty = records.isNotEmpty() && records.values.all { it.lastResult == EpgSourceResult.EMPTY.name }

            val coverage = input.coverage.associateBy { it.streamId }
            val overrides = HashMap<Int, EpgOverrideEntity>()
            for (o in input.overrides) {
                if (o.providerKey == input.providerKey) overrides[o.streamId] = o else unused += o
            }
            val currentId = HashMap<Int, String>()
            val claimed = HashMap<String, Int>()
            for ((streamId, rowId) in input.xmltvRowIds) {
                val ids = XmltvEpgRefresh.guideChannelsOfRow(rowId, streamId)
                if (ids.isEmpty()) continue
                val id = resolveRow(ids, input.serverIndex, overrides[streamId]) { index.byId.containsKey(it.lowercase(Locale.US)) }
                currentId[streamId] = id
                claimed.putIfAbsent(id.lowercase(Locale.US), streamId)
            }
            val claimedBy = { ref: GuideRef -> claimed[ref.xmltvIdLower] }
            // Channels that share a guide id: automatic matching gives it to one of them only.
            val sharedIds = input.channels.filter { !it.epgChannelId.isNullOrBlank() }
                .groupBy { it.epgChannelId!!.trim().lowercase(Locale.US) }.filterValues { it.size > 1 }

            for (ch in input.channels) {
                if (++done % 500 == 0) { coroutineContext.ensureActive(); onProgress(done, total) }
                val lastStop = coverage[ch.streamId]?.lastStop ?: 0L
                val has = lastStop > nowSec
                if (has) withListings++
                val current = currentId[ch.streamId]?.let { id -> guideFor(index, id) }
                val override = overrides[ch.streamId]

                if (override != null) {
                    val mapped = override.mode == EpgOverrideMode.MAP.name
                    val chosen = if (mapped) override.xmltvId?.let { id -> override.sourceKey?.let { index.guide(GuideRef(it, id.lowercase(Locale.US))) } } else null
                    // What the stored listings really come from: a failed download leaves the old ones in place.
                    val landed = !mapped || currentId[ch.streamId]?.equals(override.xmltvId, ignoreCase = true) == true
                    val broken = mapped && (chosen == null || !chosen.hasListingsAfter(nowSec) || (has && !landed))
                    val detail = when {
                        !mapped -> "Automatic matching, times moved ${EpgTimeOffset.label(override.offsetMinutes)}"
                        chosen == null -> "The chosen guide channel \"${override.guideName ?: override.xmltvId}\" is no longer in that guide"
                        !chosen.hasListingsAfter(nowSec) -> "The chosen guide channel \"${chosen.displayName}\" has no upcoming listings"
                        !has -> "Saved — listings load at the next guide refresh"
                        !landed -> "Saved, but the channel still shows its previous listings: the chosen guide channel's haven't loaded yet (next guide refresh)"
                        else -> "Listings from \"${chosen.displayName}\"" + if (override.offsetMinutes != 0) ", moved ${EpgTimeOffset.label(override.offsetMinutes)}" else ""
                    }
                    issues += EpgIssue(
                        if (broken) EpgIssueKind.CORRECTION_BROKEN else EpgIssueKind.CORRECTED, ch, input.providerName, detail,
                        null, chosen ?: current, override, null, false, lastStop
                    )
                    continue
                }

                if (!has) {
                    val match = EpgMatcher.suggest(ch, index, nowSec, claimedBy)
                    val usable = match.state != MatchState.NONE && match.best?.guide?.hasListingsAfter(nowSec) == true
                    val shared = ch.epgChannelId?.trim()?.lowercase(Locale.US)?.let { sharedIds[it] }?.filter { it.streamId != ch.streamId }
                    val safe = usable && EpgMatcher.safeToApply(match, ch, nowSec, claimedBy)
                    val (kind, detail) = when {
                        lastStop in 1..nowSec -> EpgIssueKind.STALE to "No future listings — the last one ended ${ago(lastStop, nowSec)}"
                        usable && !shared.isNullOrEmpty() ->
                            EpgIssueKind.MISSING_FIXABLE to "Shares its guide ID with ${shared.first().name}; automatic matching gives the listings to one channel only"
                        usable -> EpgIssueKind.MISSING_FIXABLE to match.reason
                        match.state == MatchState.EXACT -> EpgIssueKind.MISSING_UNAVAILABLE to "Its guide lists this channel but has no upcoming listings for it"
                        guides.isEmpty() && allEmpty -> EpgIssueKind.MISSING_UNAVAILABLE to "The guide source returned no guide entries at the last refresh"
                        guides.isEmpty() && anyFailed -> EpgIssueKind.MISSING_UNAVAILABLE to "The guide source failed at the last refresh"
                        guides.isEmpty() -> EpgIssueKind.MISSING_UNAVAILABLE to "No guide has been downloaded for this provider yet"
                        else -> EpgIssueKind.MISSING_UNAVAILABLE to "No configured guide lists this channel"
                    }
                    issues += EpgIssue(kind, ch, input.providerName, detail, match.takeIf { it.state != MatchState.NONE }, current, null, null, safe && kind == EpgIssueKind.MISSING_FIXABLE, lastStop)
                    continue
                }

                // Has listings: do they look like the right ones, and do they last?
                if (current != null) {
                    EpgMatcher.suspicion(ch, current, index)?.let { why ->
                        val alt = EpgMatcher.suggest(ch.copy(epgChannelId = null), index, nowSec, claimedBy)
                            .let { r -> r.copy(candidates = r.candidates.filter { it.guide.ref != current.ref }) }
                        issues += EpgIssue(EpgIssueKind.SUSPECT, ch, input.providerName, why, alt.takeIf { it.candidates.isNotEmpty() }, current, null, null, false, lastStop)
                        continue
                    }
                }
                if (lastStop - nowSec < ENDS_SOON_SEC && horizon - nowSec > WELL_COVERED_SEC) {
                    issues += EpgIssue(EpgIssueKind.STALE, ch, input.providerName, "Listings end ${inHours(lastStop, nowSec)}, though the guide covers ${days(horizon, nowSec)}", null, current, null, null, false, lastStop)
                    continue
                }
                input.offsetEvidence[ch.streamId]?.let { (reference, xmltv) ->
                    EpgTimeOffset.suggest(reference, xmltv)?.let { minutes ->
                        issues += EpgIssue(
                            EpgIssueKind.OFFSET_HINT, ch, input.providerName,
                            "The provider's own guide lists the same programs ${EpgTimeOffset.label(minutes)} from this guide",
                            null, current, null, minutes, false, lastStop
                        )
                    }
                }
            }
        }
        onProgress(total, total)
        val fixable = issues.filter { it.kind == EpgIssueKind.MISSING_FIXABLE }
        val totals = EpgHealthTotals(
            checked = total,
            withListings = withListings,
            withoutListings = total - withListings,
            fixable = fixable.size,
            unavailable = issues.count { it.kind == EpgIssueKind.MISSING_UNAVAILABLE },
            stale = issues.count { it.kind == EpgIssueKind.STALE },
            suspect = issues.count { it.kind == EpgIssueKind.SUSPECT },
            suggestions = issues.count { it.match?.candidates?.isNotEmpty() == true && it.kind != EpgIssueKind.CORRECTED },
            safeSuggestions = fixable.count { it.safe },
            corrections = issues.count { it.kind == EpgIssueKind.CORRECTED },
            brokenCorrections = issues.count { it.kind == EpgIssueKind.CORRECTION_BROKEN },
            lastSuccessfulUpdate = lastSuccess,
        )
        return EpgHealthReport(nowMs, totals, issues, sources, unused)
    }

    /**
     * Which reading of a row id (XmltvEpgRefresh.guideChannelsOfRow) is its guide channel: the
     * chosen one of a correction; else, in the format this slot writes (other providers' rows
     * always name the stream, the main provider's only for corrected channels), one a guide
     * lists; else that format's reading.
     */
    fun resolveRow(readings: List<String>, serverIndex: Int, override: EpgOverrideEntity?, known: (String) -> Boolean): String {
        readings.firstOrNull { r -> override?.mode == EpgOverrideMode.MAP.name && override.xmltvId.equals(r, ignoreCase = true) }?.let { return it }
        val ordered = if (serverIndex >= 0) readings else readings.reversed()
        return ordered.firstOrNull(known) ?: ordered.first()
    }

    private fun guideFor(index: EpgMatchIndex, id: String): EpgGuideChannel? =
        index.byId[id.lowercase(Locale.US)]?.minByOrNull { index.rank(it) }

    fun sourceNotes(record: EpgGuideSourceEntity?, kind: EpgSourceKind, nowMs: Long): List<String> {
        if (record == null) return listOf(if (kind == EpgSourceKind.PUBLIC) "Used only when the other guides fail" else "Not downloaded yet")
        val notes = ArrayList<String>()
        when (record.lastResult) {
            EpgSourceResult.FAILED.name -> notes += "The last download failed"
            EpgSourceResult.EMPTY.name -> notes += "Source returned no guide entries"
        }
        if (record.lastSuccessAt == 0L) notes += "Never downloaded successfully"
        else if (nowMs - record.lastSuccessAt > OLD_REFRESH_MS) notes += "Last successful refresh was ${(nowMs - record.lastSuccessAt) / 86_400_000L} days ago"
        if (record.lastSuccessAt > 0 && record.horizonEnd in 1..(nowMs / 1000)) notes += "No future listings available"
        if (record.invalidProgramCount > 0) notes += "${record.invalidProgramCount} listings skipped (no title, or an end before the start)"
        if (record.zonelessProgramCount > 0) notes += "${record.zonelessProgramCount} listings have no time zone (read as UTC)"
        return notes
    }

    private fun ago(sec: Long, nowSec: Long): String {
        val h = (nowSec - sec) / 3600
        return when {
            h < 1 -> "less than an hour ago"
            h < 48 -> "$h hours ago"
            else -> "${h / 24} days ago"
        }
    }

    private fun inHours(sec: Long, nowSec: Long): String {
        val m = (sec - nowSec) / 60
        return if (m < 60) "in $m minutes" else "in ${m / 60} hours"
    }

    private fun days(sec: Long, nowSec: Long): String {
        val d = (sec - nowSec) / 86_400
        return if (d < 2) "${(sec - nowSec) / 3600} hours" else "$d days"
    }
}
