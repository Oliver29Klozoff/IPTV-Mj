package com.iptvapp.data.repository

import com.iptvapp.data.local.XmltvGuideWriter
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram
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
 * Downloads and matches an XMLTV guide, then asks [writer] to swap it in.
 * Nothing is deleted until [writer] has a non-empty matched guide and the
 * refresh is still the newest one for that provider slot.
 */
internal class XmltvEpgRefresh(private val writer: XmltvGuideWriter) {

    suspend fun loadPrimary(
        sources: List<String>,
        backupUrl: String?,
        channels: List<XmltvLocalChannel>,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>,
        stillCurrent: suspend () -> Boolean,
        onBeforeCommit: suspend () -> Unit = {},
        onCommitted: suspend () -> Unit = {}
    ): Int {
        // Nothing to match against, and nothing to store. Do not take a ticket: a no-op
        // refresh must not invalidate one that is already downloading.
        if (sources.isEmpty() || channels.isEmpty()) return 0
        val ticket = writer.begin(PRIMARY)
        val (byEpgId, byName) = primaryLookups(channels)
        val matched = mutableListOf<EpgEntity>()
        var everySourceUsable = true
        for (url in sources) {
            coroutineContext.ensureActive()
            val parsed = fetchPrograms(url, fetch)
            // Empty, thrown, or unusable is not a guide we can prove is legitimate.
            // XmltvFetcher reports network, HTTP, timeout, and malformed XML the same way:
            // an empty program list. Prefer keeping the previous guide for anything that
            // source used to cover.
            if (parsed == null || parsed.second.none { usable(it) }) {
                everySourceUsable = false
                continue
            }
            matched += matchPrimary(parsed.first, parsed.second, byEpgId, byName)
        }
        if (matched.isNotEmpty()) {
            // Every configured source produced a usable guide: the union is the new guide
            // and replaces the whole slot. One source failed: replace only the channels the
            // successful sources actually matched, and leave every other row alone.
            val streamIds = if (everySourceUsable) null else matched.map { it.streamId }.distinct()
            return commit(PRIMARY, ticket, matched, streamIds, stillCurrent, onBeforeCommit, onCommitted)
        }
        if (backupUrl.isNullOrBlank() || backupUrl in sources) return 0
        coroutineContext.ensureActive()
        val backup = fetchPrograms(backupUrl, fetch) ?: return 0
        val backupRows = matchPrimary(backup.first, backup.second, byEpgId, byName)
        if (backupRows.isEmpty()) return 0
        android.util.Log.i("Xmltv", "primary: provider guide empty — backup guide matched ${backupRows.size} programs")
        return commit(
            PRIMARY, ticket, backupRows,
            streamIds = backupRows.map { it.streamId }.distinct(),
            stillCurrent, onBeforeCommit, onCommitted
        )
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
        onCommitted: suspend () -> Unit = {}
    ): Int {
        if (channels.isEmpty() || sources.isEmpty()) return 0
        val ticket = writer.begin(serverIndex)
        val (byEpgId, byName) = mergedLookups(channels)
        for (url in sources) {
            coroutineContext.ensureActive()
            val parsed = fetchPrograms(url, fetch) ?: continue
            if (parsed.second.isEmpty()) continue
            val rows = matchMerged(serverIndex, parsed.first, parsed.second, byEpgId, byName)
            if (rows.isEmpty()) continue
            val partial = backupIsPartial && url == backupUrl
            val streamIds = if (partial) rows.map { it.streamId }.distinct() else null
            return commit(serverIndex, ticket, rows, streamIds, stillCurrent, onBeforeCommit, onCommitted)
        }
        return 0
    }

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
        onBeforeCommit()
        val wrote = writer.commit(serverIndex, ticket, entries, streamIds, stillCurrent)
        if (wrote > 0) onCommitted()
        return wrote
    }

    private suspend fun fetchPrograms(
        url: String,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>
    ): Pair<List<XmltvChannel>, List<XmltvProgram>>? {
        return try {
            fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PRIMARY = -1

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
            byName: Map<String, Int>
        ): List<EpgEntity> {
            val xmlChannelToStreamId = claimOneEach(xmlChannels, xmlPrograms, byEpgId, byName)
            return rows(PRIMARY, xmlPrograms, xmlChannelToStreamId.mapValues { listOf(it.value) }, includeStreamInId = false)
        }

        fun matchMerged(
            serverIndex: Int,
            xmlChannels: List<XmltvChannel>,
            xmlPrograms: List<XmltvProgram>,
            byEpgId: Map<String, List<Int>>,
            byName: Map<String, List<Int>>
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
            return rows(serverIndex, xmlPrograms, matches, includeStreamInId = true)
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

        private fun rows(
            serverIndex: Int,
            xmlPrograms: List<XmltvProgram>,
            xmlChannelToStreamIds: Map<String, List<Int>>,
            includeStreamInId: Boolean
        ): List<EpgEntity> {
            val nowSec = System.currentTimeMillis() / 1000
            val entities = mutableListOf<EpgEntity>()
            xmlPrograms.forEach { prog ->
                if (!usable(prog)) return@forEach
                val streamIds = xmlChannelToStreamIds[prog.channelId] ?: return@forEach
                streamIds.forEach { streamId ->
                    val id = if (includeStreamInId) {
                        "x_${prog.channelId}_${streamId}_${prog.startSec}"
                    } else {
                        "x_${prog.channelId}_${prog.startSec}"
                    }
                    entities.add(
                        EpgEntity(
                            serverIndex = serverIndex,
                            id = id,
                            streamId = streamId,
                            title = prog.title,
                            description = prog.description,
                            startTimestamp = prog.startSec,
                            stopTimestamp = prog.stopSec,
                            nowPlaying = if (prog.startSec <= nowSec && prog.stopSec > nowSec) 1 else 0,
                            hasArchive = 0
                        )
                    )
                }
            }
            return entities
        }
    }
}
