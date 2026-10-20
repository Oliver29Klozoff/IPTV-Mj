package com.iptvapp.data.epg

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.EpgGuideChannelEntity
import com.iptvapp.data.local.entities.EpgOverrideEntity
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPG Health: scans the guide already on the device for problems, suggests guide channels, and
 * saves the user's corrections, which the normal guide refresh then applies (XmltvEpgRefresh).
 *
 * A scan reads the database only: opening EPG Health, rescanning or scrolling never downloads
 * anything. Downloads happen only when the user applies a correction (just the source it uses)
 * or restores automatic matching (that provider's normal refresh). One scan runs at a time (a
 * new one replaces it), and changes are applied one at a time.
 */
@Singleton
class EpgRepairRepository @Inject constructor(
    private val db: IptvDatabase,
    private val repository: XtreamRepository,
    private val prefs: PreferencesManager,
) {
    sealed class ScanState {
        object Idle : ScanState()
        data class Scanning(val done: Int, val total: Int) : ScanState()
        data class Done(val report: EpgHealthReport) : ScanState()
        data class Failed(val message: String) : ScanState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private val scanLock = Any()
    private val changeLock = Mutex()

    /** Providers as of the last scan (no addresses), for the correction screens. */
    @Volatile private var providers: List<XtreamRepository.EpgHealthProvider> = emptyList()

    /** Starts a scan, replacing one already running (its result would be stale). */
    fun scan(): Job = synchronized(scanLock) {
        scanJob?.cancel()
        scope.launch {
            _state.value = ScanState.Scanning(0, 0)
            indexLock.withLock { indexes.clear() }
            try {
                val report = runScan { done, total -> _state.value = ScanState.Scanning(done, total) }
                _state.value = ScanState.Done(report)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("EpgHealth", "scan failed (${e.javaClass.simpleName})")
                _state.value = ScanState.Failed("The guide couldn't be checked. Try again.")
            }
        }.also { scanJob = it }
    }

    /** The last finished report, if any. */
    val report: EpgHealthReport? get() = (state.value as? ScanState.Done)?.report

    private suspend fun runScan(onProgress: (Int, Int) -> Unit): EpgHealthReport {
        val dao = db.epgRepairDao()
        val list = repository.epgHealthProviders()
        providers = list
        val records = dao.getSources()
        val overrides = dao.getOverrides()
        val nowSec = System.currentTimeMillis() / 1000
        val inputs = list.map { p ->
            val evidence = if (p.serverIndex == -1) offsetEvidence(nowSec) else emptyMap()
            EpgScanInput(
                serverIndex = p.serverIndex,
                providerName = p.name,
                providerKey = p.providerKey,
                sources = p.sources.sortedBy { it.rank }.map { Triple(it.sourceKey, it.kind, it.label) },
                channels = repository.epgHealthChannels(p.serverIndex),
                guideChannels = dao.getGuideChannelsLite(p.serverIndex).map { it.toModel() },
                coverage = dao.guideCoverage(p.serverIndex),
                xmltvRowIds = dao.xmltvRowIds(p.serverIndex).associate { it.streamId to it.rowId },
                sourceRecords = records.filter { it.serverIndex == p.serverIndex },
                overrides = overrides.filter { it.serverIndex == p.serverIndex },
                offsetEvidence = evidence,
            )
        }
        // Corrections for a slot that no longer holds a provider at all.
        val slots = list.map { it.serverIndex }.toSet()
        val gone = overrides.filter { it.serverIndex !in slots }
        val lastRefresh = prefs.lastEpgRefreshTime.first()
        val report = EpgHealthScanner.scan(inputs, System.currentTimeMillis(), lastRefresh, onProgress)
        return if (gone.isEmpty()) report else report.copy(unusedCorrections = report.unusedCorrections + gone)
    }

    /** The main provider's favorites that have both its short guide and XMLTV listings (bounded). */
    private suspend fun offsetEvidence(nowSec: Long): Map<Int, Pair<List<EpgSamples.Listing>, List<EpgSamples.Listing>>> {
        val dao = db.epgRepairDao()
        val out = HashMap<Int, Pair<List<EpgSamples.Listing>, List<EpgSamples.Listing>>>()
        for (streamId in dao.streamsWithShortGuide(-1, nowSec - 6 * 3600).take(MAX_OFFSET_CHECKS)) {
            val rows = dao.upcoming(-1, streamId, nowSec - 6 * 3600)
            val (xmltv, short) = rows.partition { it.id.startsWith("x_") }
            if (xmltv.isEmpty() || short.isEmpty()) continue
            out[streamId] = short.map { EpgSamples.Listing(it.startTimestamp, it.stopTimestamp, it.title) } to
                xmltv.map { EpgSamples.Listing(it.startTimestamp, it.stopTimestamp, it.title) }
        }
        return out
    }

    // ── Reading for the correction screen ──────────────────────────────

    /** Built per provider on first use after a scan (a scan clears them): local catalog only. */
    private val indexes = HashMap<Int, Pair<EpgMatchIndex, Map<String, Int>>>()
    private val indexLock = Mutex()

    private suspend fun indexFor(serverIndex: Int): Pair<EpgMatchIndex, Map<String, Int>>? = indexLock.withLock {
        indexes[serverIndex]?.let { return@withLock it }
        val provider = currentProvider(serverIndex) ?: return@withLock null
        val rank = provider.sources.associate { it.sourceKey to it.rank }
        val guides = withContext(Dispatchers.IO) { db.epgRepairDao().getGuideChannelsLite(serverIndex) }
            .filter { it.sourceKey in rank }.map { it.toModel() }
        val built = withContext(Dispatchers.Default) { EpgMatchIndex(guides) { rank[it] ?: Int.MAX_VALUE } } to rank
        indexes[serverIndex] = built
        built
    }

    /** What EPG Health knows about one channel right now, for its correction screen. */
    data class ChannelStatus(
        val lastStop: Long,
        /** The guide channel its listings come from, when they are XMLTV listings. */
        val current: EpgGuideChannel?,
        val match: EpgMatchResult,
        val suspicion: String?,
        val override: EpgOverrideEntity?,
    )

    suspend fun channelStatus(channel: EpgLocalChannel): ChannelStatus = withContext(Dispatchers.IO) {
        val dao = db.epgRepairDao()
        val nowSec = System.currentTimeMillis() / 1000
        val lastStop = dao.lastStopFor(channel.serverIndex, channel.streamId)
        val (index, _) = indexFor(channel.serverIndex) ?: (EpgMatchIndex(emptyList()) { 0 } to emptyMap())
        val currentId = dao.xmltvRowIdFor(channel.serverIndex, channel.streamId)
            ?.let { com.iptvapp.data.repository.XmltvEpgRefresh.guideChannelOfRow(it, channel.streamId) }
        val currentLite = currentId?.let { id -> index.byId[id.lowercase(Locale.US)]?.minByOrNull { index.rank(it) } }
        val current = currentLite?.let { dao.getGuideChannel(it.serverIndex, it.sourceKey, it.xmltvId)?.toModel() ?: it }
        val override = dao.getOverride(channel.serverIndex, channel.streamId)
        val match = withContext(Dispatchers.Default) {
            EpgMatcher.suggest(if (current != null) channel.copy(epgChannelId = null) else channel, index, nowSec)
        }
        val suspicion = current?.let { EpgMatcher.suspicion(channel, it, index) }
        // Suggestions come back without listings (the scan reads the catalog without them): add them.
        val withSamples = match.copy(candidates = match.candidates.filter { it.guide.ref != current?.ref }.map { c ->
            c.copy(guide = dao.getGuideChannel(c.guide.serverIndex, c.guide.sourceKey, c.guide.xmltvId)?.toModel() ?: c.guide)
        })
        ChannelStatus(lastStop, current, withSamples, suspicion, override)
    }

    fun providerName(serverIndex: Int): String = providers.firstOrNull { it.serverIndex == serverIndex }?.name ?: "Provider"

    /** A source's label for display, by its key. */
    fun sourceLabel(serverIndex: Int, sourceKey: String): String =
        providers.firstOrNull { it.serverIndex == serverIndex }?.sources?.firstOrNull { it.sourceKey == sourceKey }?.label ?: "Guide"

    /** One guide channel with its upcoming listings. */
    suspend fun guideChannel(serverIndex: Int, sourceKey: String, xmltvId: String): EpgGuideChannel? =
        withContext(Dispatchers.IO) { db.epgRepairDao().getGuideChannel(serverIndex, sourceKey, xmltvId)?.toModel() }

    /**
     * Guide channels of [serverIndex]'s current sources whose name or id contains [query], with
     * listings, best source first. Local only.
     */
    suspend fun searchGuide(serverIndex: Int, query: String): List<EpgGuideChannel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val keys = providers.firstOrNull { it.serverIndex == serverIndex }?.sources?.associate { it.sourceKey to it.rank }.orEmpty()
        val like = "%" + q.replace("\\", "").replace("%", "").replace("_", " ") + "%"
        db.epgRepairDao().searchGuideChannels(serverIndex, like, SEARCH_LIMIT)
            .filter { it.sourceKey in keys }
            .map { it.toModel() }
            .sortedWith(compareBy({ keys[it.sourceKey] ?: Int.MAX_VALUE }, { it.displayName.lowercase(Locale.US) }))
    }

    /** Recordings still to come on a channel (shown before moving its listings). */
    suspend fun futureRecordings(serverIndex: Int, streamId: Int): Int = withContext(Dispatchers.IO) {
        db.epgRepairDao().futureRecordings(serverIndex, streamId, System.currentTimeMillis())
    }

    suspend fun override(serverIndex: Int, streamId: Int): EpgOverrideEntity? =
        withContext(Dispatchers.IO) { db.epgRepairDao().getOverride(serverIndex, streamId) }

    // ── Changes ────────────────────────────────────────────────────────

    data class ApplyOutcome(val applied: Int, val withListings: Int, val message: String)

    /**
     * Saves "take [guide]'s listings" for [channel] and loads them now from that one source.
     * The channel keeps any time offset it had. A failed download keeps the correction (it is
     * applied at the next refresh) and the channel's previous listings.
     */
    suspend fun applyCorrection(channel: EpgLocalChannel, guide: EpgGuideChannel): ApplyOutcome =
        applyCorrections(channel.serverIndex, listOf(channel to guide))

    /** "Apply safe matches": the scan's safe suggestions only (EpgMatcher.safeToApply), never a fuzzy one. */
    suspend fun applySafeMatches(report: EpgHealthReport): ApplyOutcome {
        val safe = report.issues.filter { it.kind == EpgIssueKind.MISSING_FIXABLE && it.safe && it.match?.best != null }
        if (safe.isEmpty()) return ApplyOutcome(0, 0, "No suggestions are safe to apply automatically. Review them one by one.")
        var applied = 0
        var loaded = 0
        for ((serverIndex, group) in safe.groupBy { it.channel.serverIndex }) {
            val r = applyCorrections(serverIndex, group.map { it.channel to it.match!!.best!!.guide })
            applied += r.applied
            loaded += r.withListings
        }
        val pending = applied - loaded
        return ApplyOutcome(
            applied, loaded,
            "Applied $applied safe ${if (applied == 1) "match" else "matches"}" +
                (if (pending > 0) "; $pending will get listings at the next guide refresh" else "") +
                ". ${(report.totals.fixable - applied).let { left -> "$left ${if (left == 1) "suggestion still needs" else "suggestions still need"} review." }}"
        )
    }

    private suspend fun applyCorrections(serverIndex: Int, picks: List<Pair<EpgLocalChannel, EpgGuideChannel>>): ApplyOutcome =
        changeLock.withLock {
            withContext(Dispatchers.IO) {
                val provider = providers.firstOrNull { it.serverIndex == serverIndex }
                    ?: repository.epgHealthProviders().also { providers = it }.firstOrNull { it.serverIndex == serverIndex }
                    ?: return@withContext ApplyOutcome(0, 0, "That provider is no longer configured.")
                val keys = provider.sources.map { it.sourceKey }.toSet()
                val dao = db.epgRepairDao()
                val now = System.currentTimeMillis()
                val saved = ArrayList<Int>()
                indexLock.withLock { indexes.remove(serverIndex) }
                for ((channel, guide) in picks) {
                    if (guide.serverIndex != serverIndex || guide.sourceKey !in keys) continue
                    val old = dao.getOverride(serverIndex, channel.streamId)?.takeIf { it.providerKey == provider.providerKey }
                    dao.upsertOverride(
                        EpgOverrideEntity(
                            serverIndex = serverIndex,
                            streamId = channel.streamId,
                            providerKey = provider.providerKey,
                            mode = EpgOverrideMode.MAP.name,
                            sourceKey = guide.sourceKey,
                            xmltvId = guide.xmltvId,
                            channelName = channel.name,
                            guideName = guide.displayName,
                            offsetMinutes = old?.offsetMinutes ?: 0,
                            createdAt = old?.createdAt ?: now,
                            updatedAt = now,
                        )
                    )
                    saved += channel.streamId
                }
                if (saved.isEmpty()) return@withContext ApplyOutcome(0, 0, "Nothing was saved: that guide is no longer one of this provider's.")
                // A refresh already downloading read the corrections as they were: it must not overwrite these.
                repository.invalidateEpgRefresh(serverIndex)
                val wrote = try {
                    repository.loadEpgCorrections(serverIndex, saved.toSet())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    0
                }
                // The provider's own short guide for these channels is their old mapping: it goes too.
                saved.forEach { dao.deleteShortGuideRows(serverIndex, it) }
                // Did it help? A channel counts only when its upcoming listings now come from the chosen
                // guide channel (a failed download keeps the old, possibly wrong, ones).
                val nowSec = System.currentTimeMillis() / 1000
                val chosen = picks.associate { (ch, g) -> ch.streamId to g.xmltvId.lowercase(Locale.US) }
                val fixed = saved.count { streamId ->
                    val id = dao.xmltvRowIdFor(serverIndex, streamId)
                        ?.let { com.iptvapp.data.repository.XmltvEpgRefresh.guideChannelOfRow(it, streamId) }?.lowercase(Locale.US)
                    id != null && id == chosen[streamId] && dao.lastStopFor(serverIndex, streamId) > nowSec
                }
                val withListings = fixed
                val message = when {
                    saved.size == 1 && fixed == 1 -> "Saved. The channel now shows listings from \"${picks.first().second.displayName}\"."
                    saved.size == 1 && wrote == 0 -> "Saved. Its listings couldn't be loaded right now; they'll load at the next guide refresh."
                    saved.size == 1 -> "Saved. That guide channel has no upcoming listings yet."
                    else -> "Saved ${saved.size} corrections; $fixed now show listings from their chosen guide channel."
                }
                ApplyOutcome(saved.size, withListings, message)
            }
        }

    /**
     * A time offset for one channel ([minutes] added to its guide times; 0 = none). Its XMLTV
     * listings move now by the difference, and every refresh applies it from then on.
     */
    suspend fun setOffset(channel: EpgLocalChannel, minutes: Int): String = changeLock.withLock {
        withContext(Dispatchers.IO) {
            require(EpgTimeOffset.isValid(minutes)) { "offset out of range" }
            val provider = currentProvider(channel.serverIndex) ?: return@withContext "That provider is no longer configured."
            val dao = db.epgRepairDao()
            val old = dao.getOverride(channel.serverIndex, channel.streamId)?.takeIf { it.providerKey == provider.providerKey }
            val delta = minutes - (old?.offsetMinutes ?: 0)
            val now = System.currentTimeMillis()
            if (old == null || old.mode == EpgOverrideMode.AUTO.name) {
                if (minutes == 0) dao.deleteOverride(channel.serverIndex, channel.streamId)
                else dao.upsertOverride(
                    EpgOverrideEntity(
                        channel.serverIndex, channel.streamId, provider.providerKey, EpgOverrideMode.AUTO.name,
                        null, null, channel.name, null, minutes, old?.createdAt ?: now, now
                    )
                )
            } else {
                dao.upsertOverride(old.copy(offsetMinutes = minutes, updatedAt = now))
            }
            repository.shiftEpgListings(channel.serverIndex, channel.streamId, delta)
            if (minutes == 0) "Time offset removed." else "Listings moved ${EpgTimeOffset.label(minutes)}."
        }
    }

    /**
     * Forgets the correction for one channel. A time offset is undone on its listings now; a
     * chosen guide channel is replaced by that provider's normal refresh (automatic matching).
     */
    suspend fun resetCorrection(serverIndex: Int, streamId: Int): String = changeLock.withLock {
        withContext(Dispatchers.IO) {
            val dao = db.epgRepairDao()
            val old = dao.getOverride(serverIndex, streamId) ?: return@withContext "Already using automatic matching."
            // Saved for the provider this slot holds now? One saved for an earlier provider was never applied here.
            val applies = currentProvider(serverIndex)?.providerKey == old.providerKey
            dao.deleteOverride(serverIndex, streamId)
            if (applies) repository.invalidateEpgRefresh(serverIndex)
            if (applies && old.offsetMinutes != 0) repository.shiftEpgListings(serverIndex, streamId, -old.offsetMinutes)
            if (applies && old.mode == EpgOverrideMode.MAP.name) {
                val wrote = try { repository.refreshEpgSlot(serverIndex) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { 0 }
                if (wrote == 0) return@withContext "Automatic matching restored. The guide couldn't be refreshed right now; it will update at the next refresh."
            }
            "Automatic matching restored."
        }
    }

    /** Forgets every correction (each provider then refreshes once, for those with a chosen guide channel). */
    suspend fun resetAll(): String = changeLock.withLock {
        withContext(Dispatchers.IO) {
            val dao = db.epgRepairDao()
            val all = dao.getOverrides()
            if (all.isEmpty()) return@withContext "There are no corrections to remove."
            val current = repository.epgHealthProviders().also { providers = it }.associateBy { it.serverIndex }
            for (o in all) {
                dao.deleteOverride(o.serverIndex, o.streamId)
                // Only an offset that was applied (to the provider the slot holds now) is undone.
                val applies = current[o.serverIndex]?.providerKey == o.providerKey
                if (applies) repository.invalidateEpgRefresh(o.serverIndex)
                if (applies && o.offsetMinutes != 0) repository.shiftEpgListings(o.serverIndex, o.streamId, -o.offsetMinutes)
            }
            val slots = all.filter { it.mode == EpgOverrideMode.MAP.name && current[it.serverIndex]?.providerKey == it.providerKey }
                .map { it.serverIndex }.distinct()
            for (s in slots) {
                try { repository.refreshEpgSlot(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            }
            "Removed ${all.size} ${if (all.size == 1) "correction" else "corrections"}; automatic matching restored."
        }
    }

    private suspend fun currentProvider(serverIndex: Int): XtreamRepository.EpgHealthProvider? =
        (providers.firstOrNull { it.serverIndex == serverIndex }
            ?: repository.epgHealthProviders().also { providers = it }.firstOrNull { it.serverIndex == serverIndex })

    private fun EpgGuideChannelEntity.toModel() =
        EpgGuideChannel(serverIndex, sourceKey, xmltvId, displayName, programCount, lastStop, sample)

    private companion object {
        const val SEARCH_LIMIT = 200
        const val MAX_OFFSET_CHECKS = 300
    }
}
