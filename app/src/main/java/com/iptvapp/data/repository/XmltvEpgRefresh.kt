package com.iptvapp.data.repository

import com.iptvapp.data.local.XmltvGuideWriter
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram
import com.iptvapp.util.rethrowIfCancelled
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** One local channel the XMLTV feed is matched against. */
internal data class XmltvLocalChannel(
    val streamId: Int,
    val name: String,
    val epgChannelId: String?
)

/**
 * The user's guide corrections for one provider slot (EPG Health), as the refresh applies them.
 * A MAP rule takes the channel out of automatic matching and gives it its chosen guide channel's
 * listings from its chosen source; any rule can move the channel's listings by an offset.
 */
internal data class EpgRefreshRules(
    val overrides: List<Rule> = emptyList(),
    /** The stored identity of a source address (a salted hash: never the address). */
    val sourceKeyOf: (String) -> String = { "" },
) {
    data class Rule(val streamId: Int, val mapped: Boolean, val sourceKey: String?, val xmltvId: String?, val offsetMinutes: Int)

    val mappedStreams: Set<Int> = overrides.filter { it.mapped }.mapTo(HashSet()) { it.streamId }
    val offsets: Map<Int, Int> = overrides.filter { it.offsetMinutes != 0 }.associate { it.streamId to it.offsetMinutes }

    fun mapsFrom(sourceKey: String) = overrides.filter { it.mapped && it.sourceKey == sourceKey && !it.xmltvId.isNullOrBlank() }

    /** Only rules for channels that still exist: a correction never creates rows for a channel that is gone. */
    fun forChannels(streamIds: Set<Int>) = copy(overrides = overrides.filter { it.streamId in streamIds })

    companion object {
        val NONE = EpgRefreshRules()
    }
}

/**
 * Downloads and matches an XMLTV guide, then asks [writer] to swap it in.
 * Nothing is deleted until [writer] has a non-empty matched guide and the
 * refresh is still the newest one for that provider slot.
 *
 * Corrections ([EpgRefreshRules]) apply before automatic matching. One whose source this
 * refresh didn't read is fetched afterwards and committed for its channel alone; meanwhile a
 * whole-slot replace leaves that channel's rows alone, so a failed fetch never empties it.
 * [onSource] sees every download (null for a failed one), for EPG Health's source records.
 */
internal class XmltvEpgRefresh(private val writer: XmltvGuideWriter) {

    suspend fun loadPrimary(
        sources: List<String>,
        backupUrl: String?,
        channels: List<XmltvLocalChannel>,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>,
        stillCurrent: suspend () -> Boolean,
        onBeforeCommit: suspend () -> Unit = {},
        onCommitted: suspend () -> Unit = {},
        rules: EpgRefreshRules = EpgRefreshRules.NONE,
        onSource: suspend (String, Pair<List<XmltvChannel>, List<XmltvProgram>>?) -> Unit = { _, _ -> },
        // Corrections read after this refresh holds its ticket, so one saved meanwhile (which
        // invalidates tickets) is either seen here or makes this refresh unable to commit.
        rulesAfterTicket: (suspend () -> EpgRefreshRules)? = null,
    ): Int {
        // Nothing to match against, and nothing to store. Do not take a ticket: a no-op
        // refresh must not invalidate one that is already downloading.
        if (sources.isEmpty() || channels.isEmpty()) return 0
        val ticket = writer.begin(PRIMARY)
        val live = (rulesAfterTicket?.invoke() ?: rules).forChannels(channels.mapTo(HashSet()) { it.streamId })
        // Corrected channels are not matched automatically.
        val (byEpgId, byName) = primaryLookups(channels.filter { it.streamId !in live.mappedStreams })
        val sourceKeys = sources.mapTo(HashSet()) { live.sourceKeyOf(it) }
        val pending = pendingStreams(live, sourceKeys)
        val read = HashSet<String>()
        val matched = mutableListOf<EpgEntity>()
        var everySourceUsable = true
        for (url in sources) {
            coroutineContext.ensureActive()
            val parsed = fetchPrograms(url, fetch)
            onSource(url, parsed)
            // Empty, thrown, or unusable is not a guide we can prove is legitimate.
            // XmltvFetcher reports network, HTTP, timeout, and malformed XML the same way:
            // an empty program list. Prefer keeping the previous guide for anything that
            // source used to cover.
            if (parsed == null || parsed.second.none { usable(it) }) {
                everySourceUsable = false
                continue
            }
            val key = live.sourceKeyOf(url)
            read += key
            matched += matchPrimary(parsed.first, parsed.second, byEpgId, byName, live, key)
        }
        var wrote = 0
        // Listings only for corrected channels are not a guide for the provider: they are
        // written for those channels alone, and the stand-in is still tried for the rest.
        val automatic = matched.any { it.streamId !in live.mappedStreams }
        if (automatic) {
            // Every configured source produced a usable guide: the union is the new guide
            // and replaces the whole slot. One source failed: replace only the channels the
            // successful sources actually matched, and leave every other row alone.
            // (A correction waiting on another source keeps its rows either way.)
            // So does a corrected channel this refresh found nothing for (its guide channel gone).
            val keep = pending + (live.mappedStreams - matched.mapTo(HashSet()) { it.streamId })
            val streamIds = when {
                !everySourceUsable -> matched.map { it.streamId }.distinct()
                keep.isNotEmpty() -> channels.map { it.streamId }.filter { it !in keep }
                else -> null
            }
            wrote = commit(PRIMARY, ticket, matched, streamIds, stillCurrent, onBeforeCommit, onCommitted)
        } else if (matched.isNotEmpty()) {
            wrote = commit(PRIMARY, ticket, matched, matched.map { it.streamId }.distinct(), stillCurrent, onBeforeCommit, onCommitted)
        }
        if (!automatic && !backupUrl.isNullOrBlank() && backupUrl !in sources) {
            coroutineContext.ensureActive()
            val backup = fetchPrograms(backupUrl, fetch)
            onSource(backupUrl, backup)
            if (backup != null) {
                val key = live.sourceKeyOf(backupUrl)
                read += key
                val backupRows = matchPrimary(backup.first, backup.second, byEpgId, byName, live, key)
                if (backupRows.isNotEmpty()) {
                    android.util.Log.i("Xmltv", "primary: provider guide empty — backup guide matched ${backupRows.size} programs")
                    wrote += commit(
                        PRIMARY, ticket, backupRows,
                        streamIds = backupRows.map { it.streamId }.distinct(),
                        stillCurrent, onBeforeCommit, onCommitted
                    )
                }
            }
        }
        val others = listOfNotNull(backupUrl).filter { it.isNotBlank() }
        return wrote + loadCorrections(PRIMARY, ticket, live, read, sources + others, fetch, stillCurrent, onBeforeCommit, onCommitted, onSource)
    }

    suspend fun loadMerged(
        serverIndex: Int,
        sources: List<String>,
        backupUrl: String?,
        backupIsPartial: Boolean,
        channels: List<XmltvLocalChannel>,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>,
        stillCurrent: suspend () -> Boolean,
        onBeforeCommit: suspend () -> Unit = {},
        onCommitted: suspend () -> Unit = {},
        rules: EpgRefreshRules = EpgRefreshRules.NONE,
        onSource: suspend (String, Pair<List<XmltvChannel>, List<XmltvProgram>>?) -> Unit = { _, _ -> },
        rulesAfterTicket: (suspend () -> EpgRefreshRules)? = null,
    ): Int {
        if (channels.isEmpty() || sources.isEmpty()) return 0
        val ticket = writer.begin(serverIndex)
        val live = (rulesAfterTicket?.invoke() ?: rules).forChannels(channels.mapTo(HashSet()) { it.streamId })
        val (byEpgId, byName) = mergedLookups(channels.filter { it.streamId !in live.mappedStreams })
        val read = HashSet<String>()
        var wrote = 0
        for (url in sources) {
            coroutineContext.ensureActive()
            val parsed = fetchPrograms(url, fetch)
            onSource(url, parsed)
            if (parsed == null || parsed.second.isEmpty()) continue
            val key = live.sourceKeyOf(url)
            read += key
            val rows = matchMerged(serverIndex, parsed.first, parsed.second, byEpgId, byName, live, key)
            if (rows.isEmpty()) continue
            // Only corrected channels matched: not this provider's guide. Their listings are
            // written for them alone, and the next source is tried for everything else.
            if (rows.all { it.streamId in live.mappedStreams }) {
                wrote += commit(serverIndex, ticket, rows, rows.map { it.streamId }.distinct(), stillCurrent, onBeforeCommit, onCommitted)
                continue
            }
            val partial = backupIsPartial && url == backupUrl
            // The first source that matches is this provider's guide; a correction from another
            // source keeps its rows until it is fetched below.
            // A corrected channel this source has nothing for keeps its rows too.
            val keep = pendingStreams(live, setOf(key)) + (live.mappedStreams - rows.mapTo(HashSet()) { it.streamId })
            val streamIds = when {
                partial -> rows.map { it.streamId }.distinct()
                keep.isNotEmpty() -> channels.map { it.streamId }.filter { it !in keep }
                else -> null
            }
            wrote += commit(serverIndex, ticket, rows, streamIds, stillCurrent, onBeforeCommit, onCommitted)
            break
        }
        return wrote + loadCorrections(serverIndex, ticket, live, read, sources, fetch, stillCurrent, onBeforeCommit, onCommitted, onSource)
    }

    /**
     * Listings for corrected channels whose sources were not read yet, from just those
     * sources, committed for just those channels. [ticket] null uses the slot's current one, so
     * applying a correction from EPG Health never cancels a full refresh already running.
     */
    suspend fun loadCorrections(
        serverIndex: Int,
        ticket: Int?,
        rules: EpgRefreshRules,
        alreadyRead: Set<String>,
        sources: List<String>,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>,
        stillCurrent: suspend () -> Boolean,
        onBeforeCommit: suspend () -> Unit = {},
        onCommitted: suspend () -> Unit = {},
        onSource: suspend (String, Pair<List<XmltvChannel>, List<XmltvProgram>>?) -> Unit = { _, _ -> },
    ): Int {
        val waiting = rules.overrides.filter { it.mapped && it.sourceKey != null && it.sourceKey !in alreadyRead && !it.xmltvId.isNullOrBlank() }
        if (waiting.isEmpty()) return 0
        val slotTicket = ticket ?: writer.current(serverIndex)
        val done = HashSet(alreadyRead)
        var wrote = 0
        for (url in sources.distinct()) {
            val key = rules.sourceKeyOf(url)
            if (key in done || waiting.none { it.sourceKey == key }) continue
            coroutineContext.ensureActive()
            val parsed = fetchPrograms(url, fetch)
            onSource(url, parsed)
            done += key
            if (parsed == null) continue
            val map = correctionMap(parsed.second, rules.mapsFrom(key))
            val rows = rows(serverIndex, parsed.second, map, includeStreamInId = true, qualified = rules.mappedStreams, offsets = rules.offsets)
            if (rows.isEmpty()) continue
            wrote += commit(serverIndex, slotTicket, rows, rows.map { it.streamId }.distinct(), stillCurrent, onBeforeCommit, onCommitted)
        }
        return wrote
    }

    /** Corrected channels whose source is not among [read]: their rows stay until that source is fetched. */
    private fun pendingStreams(rules: EpgRefreshRules, read: Set<String>): Set<Int> =
        rules.overrides.filter { it.mapped && it.sourceKey != null && it.sourceKey !in read }.mapTo(HashSet()) { it.streamId }

    /**
     * A database failure here rolls the swap back and returns 0, as v7.21 did: the previous
     * guide stays, and the caller (EpgRefreshWorker's per-channel fallback and show alerts)
     * carries on. Cancellation is not a failure and always propagates.
     */
    private suspend fun commit(
        serverIndex: Int,
        ticket: Int,
        entries: List<EpgEntity>,
        streamIds: List<Int>?,
        stillCurrent: suspend () -> Boolean,
        onBeforeCommit: suspend () -> Unit,
        onCommitted: suspend () -> Unit
    ): Int {
        coroutineContext.ensureActive()
        val wrote = try {
            onBeforeCommit()
            writer.commit(serverIndex, ticket, entries, streamIds, stillCurrent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "serverIndex=$serverIndex: guide not saved, previous guide kept (${e.javaClass.simpleName})")
            return 0
        }
        // Not reached after cancellation: the favorite diff is only recorded for a guide that
        // actually committed.
        coroutineContext.ensureActive()
        if (wrote > 0) onCommitted()
        return wrote
    }

    /**
     * Null when this source failed. Its own timeout counts as a failure, so the next source
     * still runs; the refresh itself being cancelled stops here (rethrowIfCancelled, then
     * ensureActive for a timeout that was really the caller's).
     */
    private suspend fun fetchPrograms(
        url: String,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>
    ): Pair<List<XmltvChannel>, List<XmltvProgram>>? {
        return try {
            fetch(url)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            coroutineContext.ensureActive()
            null
        }
    }

    companion object {
        const val PRIMARY = -1
        private const val TAG = "Xmltv"

        /** A program MKTV can put on a guide row. Anything else is not a guide. */
        fun usable(program: XmltvProgram): Boolean =
            program.channelId.isNotBlank() &&
                program.title.isNotBlank() &&
                program.startSec > 0L &&
                program.stopSec > program.startSec

        fun normalizeForMatch(name: String): String =
            name.lowercase()
                .replace(Regex("\\b(hd|fhd|uhd|4k|sd|the|us|usa|uk|ca|east|west|hevc|h264|h265)\\b"), " ")
                .replace(Regex("[^a-z0-9]"), "")
                .trim()

        fun primaryLookups(channels: List<XmltvLocalChannel>): Pair<Map<String, Int>, Map<String, Int>> {
            val byEpgId = mutableMapOf<String, Int>()
            val byNameRaw = mutableMapOf<String, MutableList<Int>>()
            channels.forEach { ch ->
                if (!ch.epgChannelId.isNullOrBlank()) byEpgId[ch.epgChannelId.lowercase()] = ch.streamId
                val key = normalizeForMatch(ch.name)
                if (key.isNotBlank()) byNameRaw.getOrPut(key) { mutableListOf() }.add(ch.streamId)
            }
            val byName = byNameRaw.filterValues { it.size == 1 }.mapValues { it.value[0] }
            return byEpgId to byName
        }

        fun mergedLookups(channels: List<XmltvLocalChannel>): Pair<Map<String, List<Int>>, Map<String, List<Int>>> {
            val byEpgId = mutableMapOf<String, MutableList<Int>>()
            val byNameRaw = mutableMapOf<String, MutableList<Int>>()
            channels.forEach { ch ->
                if (!ch.epgChannelId.isNullOrBlank()) {
                    byEpgId.getOrPut(ch.epgChannelId.lowercase()) { mutableListOf() }.add(ch.streamId)
                }
                val key = normalizeForMatch(ch.name)
                if (key.isNotBlank()) byNameRaw.getOrPut(key) { mutableListOf() }.add(ch.streamId)
            }
            return byEpgId to byNameRaw.filterValues { it.size == 1 }
        }

        fun matchPrimary(
            xmlChannels: List<XmltvChannel>,
            xmlPrograms: List<XmltvProgram>,
            byEpgId: Map<String, Int>,
            byName: Map<String, Int>,
            rules: EpgRefreshRules = EpgRefreshRules.NONE,
            sourceKey: String = "",
        ): List<EpgEntity> {
            val map = claimOneEach(xmlChannels, xmlPrograms, byEpgId, byName).mapValues { listOf(it.value) }.toMutableMap()
            correctionMap(xmlPrograms, rules.mapsFrom(sourceKey)).forEach { (id, streams) -> map[id] = map[id].orEmpty() + streams }
            return rows(PRIMARY, xmlPrograms, map, includeStreamInId = false, qualified = rules.mappedStreams, offsets = rules.offsets)
        }

        /**
         * The guide channel each correction chose, under the id the feed itself spells it
         * (ids are compared case-insensitively, as automatic matching does).
         */
        internal fun correctionMap(xmlPrograms: List<XmltvProgram>, corrections: List<EpgRefreshRules.Rule>): Map<String, List<Int>> {
            if (corrections.isEmpty()) return emptyMap()
            val wanted = corrections.groupBy({ it.xmltvId!!.lowercase() }, { it.streamId })
            val out = HashMap<String, List<Int>>()
            for (p in xmlPrograms) {
                if (p.channelId in out) continue
                wanted[p.channelId.lowercase()]?.let { out[p.channelId] = it }
            }
            return out
        }

        fun matchMerged(
            serverIndex: Int,
            xmlChannels: List<XmltvChannel>,
            xmlPrograms: List<XmltvProgram>,
            byEpgId: Map<String, List<Int>>,
            byName: Map<String, List<Int>>,
            rules: EpgRefreshRules = EpgRefreshRules.NONE,
            sourceKey: String = "",
        ): List<EpgEntity> {
            val claimNowSec = System.currentTimeMillis() / 1000
            val withPrograms = xmlPrograms.filter { it.stopSec > claimNowSec }.mapTo(HashSet()) { it.channelId }
            val candidates = xmlChannels.filter { it.id in withPrograms }
            val matches = mutableMapOf<String, List<Int>>()
            val claimed = mutableSetOf<Int>()
            candidates.forEach { xmlCh ->
                byEpgId[xmlCh.id.lowercase()]?.let { matches[xmlCh.id] = it; claimed += it }
            }
            candidates.forEach { xmlCh ->
                if (xmlCh.id in matches) return@forEach
                val resolved = byName[normalizeForMatch(xmlCh.displayName)]?.filter { claimed.add(it) } ?: return@forEach
                if (resolved.isNotEmpty()) matches[xmlCh.id] = resolved
            }
            correctionMap(xmlPrograms, rules.mapsFrom(sourceKey)).forEach { (id, streams) -> matches[id] = matches[id].orEmpty() + streams }
            return rows(serverIndex, xmlPrograms, matches, includeStreamInId = true, offsets = rules.offsets)
        }

        private fun claimOneEach(
            xmlChannels: List<XmltvChannel>,
            xmlPrograms: List<XmltvProgram>,
            byEpgId: Map<String, Int>,
            byName: Map<String, Int>
        ): Map<String, Int> {
            val claimNowSec = System.currentTimeMillis() / 1000
            val withPrograms = xmlPrograms.filter { it.stopSec > claimNowSec }.mapTo(HashSet()) { it.channelId }
            val candidates = xmlChannels.filter { it.id in withPrograms }
            val xmlChannelToStreamId = mutableMapOf<String, Int>()
            val claimed = mutableSetOf<Int>()
            candidates.forEach { xmlCh ->
                byEpgId[xmlCh.id.lowercase()]?.let { xmlChannelToStreamId[xmlCh.id] = it; claimed += it }
            }
            candidates.forEach { xmlCh ->
                if (xmlCh.id in xmlChannelToStreamId) return@forEach
                val resolved = byName[normalizeForMatch(xmlCh.displayName)] ?: return@forEach
                if (claimed.add(resolved)) xmlChannelToStreamId[xmlCh.id] = resolved
            }
            return xmlChannelToStreamId
        }

        /**
         * Guide rows. A row id names its guide channel ("x_<channel>_<start>", with the stream
         * as well where one guide channel feeds several channels: always on other providers, and
         * on the main one for a corrected channel). The start in the id is the source's own, so
         * an offset ([offsets], minutes per channel) never changes a row's identity.
         */
        private fun rows(
            serverIndex: Int,
            xmlPrograms: List<XmltvProgram>,
            xmlChannelToStreamIds: Map<String, List<Int>>,
            includeStreamInId: Boolean,
            qualified: Set<Int> = emptySet(),
            offsets: Map<Int, Int> = emptyMap(),
        ): List<EpgEntity> {
            val nowSec = System.currentTimeMillis() / 1000
            val entities = mutableListOf<EpgEntity>()
            xmlPrograms.forEach { prog ->
                if (!usable(prog)) return@forEach
                val streamIds = xmlChannelToStreamIds[prog.channelId] ?: return@forEach
                streamIds.forEach { streamId ->
                    val id = if (includeStreamInId || streamId in qualified) {
                        "x_${prog.channelId}_${streamId}_${prog.startSec}"
                    } else {
                        "x_${prog.channelId}_${prog.startSec}"
                    }
                    val shift = (offsets[streamId] ?: 0) * 60L
                    val start = prog.startSec + shift
                    val stop = prog.stopSec + shift
                    entities.add(
                        EpgEntity(
                            serverIndex = serverIndex,
                            id = id,
                            streamId = streamId,
                            title = prog.title,
                            description = prog.description,
                            startTimestamp = start,
                            stopTimestamp = stop,
                            nowPlaying = if (start <= nowSec && stop > nowSec) 1 else 0,
                            hasArchive = 0
                        )
                    )
                }
            }
            return entities
        }

        /**
         * The guide channel ids a row may have come from (empty for a row that is not from
         * XMLTV: the provider's own short guide). Reverses [rows]' id format, which can't always
         * be read one way: "x_station_7_<start>" on stream 7 is guide channel "station" in a
         * stream-qualified row, or "station_7" in a plain one. Both readings are returned
         * (qualified first) for the caller to resolve against the guide channels it knows.
         */
        fun guideChannelsOfRow(rowId: String, streamId: Int): List<String> {
            if (!rowId.startsWith("x_")) return emptyList()
            val withoutStart = rowId.substringBeforeLast('_', "").takeIf { it.length > 2 } ?: return emptyList()
            val body = withoutStart.substring(2)
            val suffix = "_$streamId"
            return if (body.endsWith(suffix) && body.length > suffix.length) listOf(body.dropLast(suffix.length), body)
            else listOf(body).filter { it.isNotEmpty() }
        }
    }
}
