package com.iptvapp.data.epg

import com.iptvapp.data.local.entities.EpgGuideChannelEntity
import com.iptvapp.data.repository.XmltvEpgRefresh
import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram

/**
 * What one parsed guide source holds, reduced to what EPG Health needs: its channels (with a
 * count, the end of their last listing and the next few listings) and totals for the source.
 * One pass over the listings, keeping at most [EpgSamples.MAX] per channel: a guide with millions
 * of listings costs a few per channel here, never a copy of it.
 */
object EpgCatalog {
    /** More guide channels than any real source lists; a feed with more is cut, not refused. */
    const val MAX_CHANNELS = 60_000

    data class Stats(
        val channelCount: Int,
        val programCount: Int,
        val invalidProgramCount: Int,
        val zonelessProgramCount: Int,
        val horizonEnd: Long,
    )

    fun build(
        serverIndex: Int,
        sourceKey: String,
        channels: List<XmltvChannel>,
        programs: List<XmltvProgram>,
        nowSec: Long,
    ): Pair<List<EpgGuideChannelEntity>, Stats> {
        class Acc(val name: String) {
            var count = 0
            var lastStop = 0L
            val next = ArrayList<EpgSamples.Listing>(EpgSamples.MAX + 1)
        }
        val byId = LinkedHashMap<String, Acc>()
        for (c in channels) {
            if (byId.size >= MAX_CHANNELS) break
            // A feed that lists one id twice: the first name stands, as the refresh does.
            byId.putIfAbsent(c.id, Acc(c.displayName))
        }
        var usableCount = 0
        var invalid = 0
        var zoneless = 0
        var horizon = 0L
        for (p in programs) {
            if (!XmltvEpgRefresh.usable(p)) { invalid++; continue }
            usableCount++
            if (p.zoneless) zoneless++
            if (p.stopSec > horizon) horizon = p.stopSec
            // Listings for an id the feed never declared still make it a channel (by its id).
            val acc = byId[p.channelId] ?: if (byId.size < MAX_CHANNELS) Acc(p.channelId).also { byId[p.channelId] = it } else continue
            acc.count++
            if (p.stopSec > acc.lastStop) acc.lastStop = p.stopSec
            if (p.stopSec > nowSec) {
                val next = acc.next
                if (next.size < EpgSamples.MAX) {
                    next += EpgSamples.Listing(p.startSec, p.stopSec, p.title)
                } else {
                    val latest = next.maxByOrNull { it.startSec }!!
                    if (p.startSec < latest.startSec) { next.remove(latest); next += EpgSamples.Listing(p.startSec, p.stopSec, p.title) }
                }
            }
        }
        val rows = byId.map { (id, acc) ->
            EpgGuideChannelEntity(
                serverIndex = serverIndex,
                sourceKey = sourceKey,
                xmltvId = id,
                displayName = acc.name.take(200),
                programCount = acc.count,
                lastStop = acc.lastStop,
                sample = EpgSamples.encode(acc.next.sortedBy { it.startSec }),
            )
        }
        return rows to Stats(rows.size, usableCount, invalid, zoneless, horizon)
    }
}
