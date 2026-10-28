package com.iptvapp.service

import android.content.Context
import android.os.Environment
import androidx.room.withTransaction
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.*
import com.iptvapp.data.repository.XtreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SeriesRecordingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
    private val provider: RecordingProviderAccess
) {
    private val mutex = Mutex()
    private val dao get() = db.seriesRecordingDao()

    suspend fun providerKey(server: Int): String? = provider.key(server)

    suspend fun create(rule: SeriesRecordingRule, selected: EpgEntity): Long = mutex.withLock {
        val id = dao.insert(rule)
        val identity = SeriesRecordingPolicy.identity(rule.providerKey, selected)
        val (start, end) = SeriesRecordingPolicy.window(rule, selected)
        dao.insert(SeriesRecordingEpisode(identity, id, selected.id, selected.title, start, end,
            "PENDING", "", confirmed = rule.mode == "SINGLE"))
        id
    }

    suspend fun refresh() = mutex.withLock {
        val now = System.currentTimeMillis()
        for (rule in dao.rules().filter { !it.paused && !it.cancelled }) {
            if (providerKey(rule.serverIndex) != rule.providerKey) {
                dao.episodes().filter { it.ruleId == rule.id && it.recordingId == null }.forEach {
                    dao.update(it.copy(status = "UNCERTAIN", reason = "Provider changed or disabled; choose the preferred source again"))
                }
                continue
            }
            val guide = db.epgDao().getEpgForStream(rule.streamId, rule.serverIndex).first()
                .filter { SeriesRecordingPolicy.ms(it.stopTimestamp) > now }
            val matches = guide.filter { SeriesRecordingPolicy.eligible(rule, it) }
            if (rule.mode != "SINGLE") for (epg in matches) {
                val (start, end) = SeriesRecordingPolicy.window(rule, epg)
                if (end <= start || start > now + 14 * 86_400_000L) continue
                dao.insert(SeriesRecordingEpisode(SeriesRecordingPolicy.identity(rule.providerKey, epg), rule.id,
                    epg.id, epg.title, start, end, "PENDING", ""))
            }
            for (candidate in dao.episodes().filter { it.ruleId == rule.id && it.status !in setOf("CANCELLED", "SKIPPED", "RETAINED_HISTORY") }) {
                val alternatives = matches.filter { SeriesRecordingPolicy.identity(rule.providerKey, it) == candidate.identity }
                    .sortedBy { SeriesRecordingPolicy.ms(it.startTimestamp) }
                val epg = alternatives.firstOrNull { it.id == candidate.epgId } ?: alternatives.firstOrNull()
                val rec = candidate.recordingId?.let { db.recordingDao().getById(it) }
                if (rec != null && rec.status != "SCHEDULED") continue
                if (candidate.recordingId != null && rec == null) {
                    dao.update(candidate.copy(status = "RETAINED_HISTORY", reason = "Recording removed; duplicate history retained")); continue
                }
                if (epg == null) {
                    dao.update(candidate.copy(status = "UNCERTAIN", reason = "Airing missing or cancelled in guide; existing schedule preserved for review"))
                    continue
                }
                val (start, end) = SeriesRecordingPolicy.window(rule, epg)
                if (end - maxOf(now, start) < RecordingPlan.MIN_USEFUL_MS) continue
                val updated = candidate.copy(epgId = epg.id, title = epg.title, startMs = start, endMs = end)
                val uncertain = SeriesRecordingPolicy.uncertainty(rule, epg)
                if (uncertain != null && !candidate.confirmed) {
                    dao.update(updated.copy(status = "UNCERTAIN", reason = uncertain)); continue
                }
                val reason = admission(rule, start, end, rec?.id)
                if (reason != null) {
                    dao.update(updated.copy(status = "CONFLICT", reason = reason)); continue
                }
                if (rec != null) {
                    if (rec.scheduledStartMs != start || rec.durationMs != end - start)
                        db.recordingDao().moveScheduled(rec.id, start, end - start)
                    // Alarms are keyed by id, so this replaces rather than adds. It also re-arms a
                    // start that was held for review and has now been cleared.
                    if (rec.status == "SCHEDULED") RecordingAlarms.schedule(context, rec.id, maxOf(start, now + 2_000L))
                    dao.update(updated.copy(status = "SCHEDULED", reason = ""))
                } else schedule(rule, updated)
            }
        }
    }

    private suspend fun admission(rule: SeriesRecordingRule, start: Long, end: Long, exclude: Int? = null): String? {
        if (rule.stopLowStorage && (freeBytes() ?: return "Recording storage unavailable") < SeriesRecordingPolicy.requiredBytes(end - start))
            return "Insufficient storage for estimated 8 Mbps capture plus reserve"
        val limits = provider.limits(rule.serverIndex)
        currentCoroutineContext().ensureActive()
        if (limits != null && !limits.isActive) return "Provider account is unavailable"
        val existing = db.recordingDao().getAll().first().filter { it.id != exclude }
        // The account's connection count, not what is in use right now: a show watched live today
        // says nothing about next week's slots. Unknown counts as one.
        val slots = SeriesRecordingPolicy.capacity(limits?.maxConnections, 0)
        if (slots == 0) return "Provider connection limit reached"
        val conflicts = SeriesRecordingPolicy.conflict(start, end, rule.serverIndex, existing, slots)
        return conflicts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Overlaps: ") { it.programTitle ?: it.channelName }
    }

    private suspend fun schedule(rule: SeriesRecordingRule, episode: SeriesRecordingEpisode) {
        val duplicate = db.recordingDao().getAll().first().firstOrNull {
            it.serverIndex == rule.serverIndex && it.streamId == rule.streamId &&
                it.scheduledStartMs == episode.startMs && it.status != "CANCELLED"
        }
        if (duplicate != null) { dao.update(episode.copy(recordingId = duplicate.id, status = "SCHEDULED", reason = "Existing recording")); return }
        val url = provider.url(rule.serverIndex, rule.streamId)
        if (url.isBlank() || providerKey(rule.serverIndex) != rule.providerKey) return
        val output = RecordingTargets.create(context, prefs, rule.channelName, episode.startMs)
        val id = db.withTransaction {
            val current = dao.episode(episode.identity) ?: return@withTransaction null
            val currentRule = dao.rule(rule.id) ?: return@withTransaction null
            if (current.recordingId != null || currentRule.paused || currentRule.cancelled) return@withTransaction null
            val id = db.recordingDao().insert(RecordingEntity(streamId = rule.streamId, serverIndex = rule.serverIndex,
                channelName = rule.channelName, programTitle = episode.title, scheduledStartMs = episode.startMs,
                durationMs = episode.endMs - episode.startMs, outputPath = output)).toInt()
            dao.update(episode.copy(recordingId = id, status = "SCHEDULED", reason = ""))
            id
        }
        if (id == null) { com.iptvapp.util.RecordingFileUtils.deleteFile(context, output); return }
        RecordingUrls.put(context, id, url)
        RecordingAlarms.schedule(context, id, maxOf(episode.startMs, System.currentTimeMillis() + 2_000L))
    }

    suspend fun confirm(identity: String) = mutex.withLock {
        dao.episode(identity)?.let { dao.update(detachCancelled(it).copy(confirmed = true, status = "PENDING", reason = "")) }
    }

    /** A retry after the viewer cancelled the episode: drop the link to the cancelled recording so
     * refresh schedules a new one. Links to kept or finished recordings stay (duplicate protection). */
    private suspend fun detachCancelled(episode: SeriesRecordingEpisode): SeriesRecordingEpisode {
        val rec = episode.recordingId?.let { db.recordingDao().getById(it) } ?: return episode
        return if (rec.status == "CANCELLED") episode.copy(recordingId = null) else episode
    }

    suspend fun chooseAiring(identity: String, epg: EpgEntity) = mutex.withLock {
        val episode = dao.episode(identity) ?: return@withLock
        val rule = dao.rule(episode.ruleId) ?: return@withLock
        require(SeriesRecordingPolicy.reliable(epg) && SeriesRecordingPolicy.identity(rule.providerKey, epg) == identity)
        val (start, end) = SeriesRecordingPolicy.window(rule, epg)
        dao.update(detachCancelled(episode).copy(epgId = epg.id, startMs = start, endMs = end, status = "PENDING"))
    }

    /**
     * A new preferred channel or provider. Reservations not started yet are released, so refresh
     * books them on the new source with its own stream; finished and in-progress captures stay.
     * Another provider means other episode identities, so the old provider's episodes are closed.
     */
    suspend fun changeSource(old: SeriesRecordingRule, updated: SeriesRecordingRule) = mutex.withLock {
        val sameProvider = old.providerKey == updated.providerKey
        db.withTransaction {
            dao.update(updated)
            dao.episodes().filter { it.ruleId == updated.id && it.status !in setOf("CANCELLED", "SKIPPED", "RETAINED_HISTORY") }.forEach { episode ->
                val id = episode.recordingId
                if (id != null) {
                    if (db.recordingDao().cancelScheduled(id) == 0) return@forEach
                    RecordingAlarms.cancel(context, id)
                }
                dao.update(episode.copy(recordingId = null,
                    status = if (sameProvider) "PENDING" else "CANCELLED",
                    reason = if (sameProvider) "" else "Preferred source changed"))
            }
        }
    }

    /** Explicit user action only. Completed files and in-progress captures are untouched. */
    suspend fun setRule(rule: SeriesRecordingRule) = mutex.withLock {
        db.withTransaction {
            dao.update(rule)
            if (rule.paused || rule.cancelled) dao.episodes().filter { it.ruleId == rule.id }.forEach { episode ->
                val id = episode.recordingId
                if (id != null && db.recordingDao().cancelScheduled(id) > 0) {
                    RecordingAlarms.cancel(context, id)
                    dao.update(episode.copy(recordingId = null, status = if (rule.cancelled) "CANCELLED" else "PENDING"))
                } else if (id == null && rule.cancelled) dao.update(episode.copy(status = "CANCELLED"))
            }
        }
    }

    suspend fun cancelEpisode(identity: String) = mutex.withLock {
        dao.episode(identity)?.let { ep ->
            ep.recordingId?.let { if (db.recordingDao().cancelScheduled(it) > 0) RecordingAlarms.cancel(context, it) }
            dao.update(ep.copy(status = "CANCELLED", reason = "Cancelled by user"))
        }
    }

    /** "Keep last N": completed, unprotected episodes past each rule's count (SeriesRecordingStorage). */
    suspend fun applyRetention() = SeriesRecordingStorage.clean(context, db)

    fun freeBytes(): Long? = runCatching {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) null
        else android.os.StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    }.getOrNull()

    /** Preserve the recording reservation; only annotate its series conflict for user review. */
    suspend fun startRefusal(rec: RecordingEntity): String? = mutex.withLock {
        val episode = dao.forRecording(rec.id) ?: return@withLock null
        val rule = dao.rule(episode.ruleId) ?: return@withLock "Series rule needs review"
        // The guide dropped or changed this airing after it was booked: hold the start until the
        // viewer confirms it, keeping the episode's own review reason on screen.
        if (episode.status == "UNCERTAIN") return@withLock episode.reason.ifBlank { "Airing needs review" }
        val reason = when {
            rule.cancelled || rule.paused -> "Series is paused or cancelled"
            providerKey(rule.serverIndex) != rule.providerKey -> "Preferred provider changed or is disabled"
            else -> admission(rule, System.currentTimeMillis(), rec.scheduledStartMs + rec.durationMs, rec.id)
        }
        if (reason != null) dao.update(episode.copy(status = "CONFLICT", reason = reason))
        reason
    }
}

object RecordingTargets {
    suspend fun create(context: Context, prefs: PreferencesManager, channel: String, start: Long): String {
        val name = channel.replace(Regex("[^a-zA-Z0-9 _-]"), "_").take(80)
        val file = "${name}_${start}_${java.util.UUID.randomUUID()}.ts"
        val folder = prefs.recordingFolderName.first().replace(Regex("[^a-zA-Z0-9 _-]"), "_").ifBlank { "MKTV" }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$folder")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            return requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) { "Recording storage unavailable" }.toString()
        }
        val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)
        check(dir.isDirectory || dir.mkdirs()) { "Recording storage unavailable" }
        return java.io.File(dir, file).absolutePath
    }
}
