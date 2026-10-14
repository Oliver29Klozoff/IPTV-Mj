package com.iptvapp.ui.player.timeshift

import java.io.ByteArrayOutputStream

/**
 * Splits an infinite MPEG-TS socket into short files on 188-byte packet boundaries.
 * The first piece is shorter so the picture can start; later pieces prefer a keyframe.
 *
 * EXTINF uses the PCR or PTS span. A burst or a stall makes packet-arrival time disagree
 * with the programme clock, so the wall clock only decides a cut when the stream has no
 * media timestamps.
 *
 * A cut starts at the keyframe, so the PAT and PMT that sat in front of it stay in the
 * previous file. Each new piece begins with the latest copies, or a seek into that
 * file has no tables to decode from.
 */
class TsCutter(
    private val firstTargetMs: Long = 1_000L,
    private val targetMs: Long = 4_000L,
    private val maxMs: Long = 8_000L,
    private val clock: () -> Long,
) {
    data class Cut(val bytes: ByteArray, val durationMs: Long, val discontinuity: Boolean)

    private val pending = ByteArrayOutputStream()
    private var remainder = ByteArray(0)
    private var segmentStartMs = -1L
    private var cuts = 0
    private var pendingBreak = false
    /** First 90 kHz PCR or PTS in the open segment. -1 means none yet. */
    private var firstTicks = -1L
    /** Latest media timestamp written into the open segment. -1 means none yet. */
    private var lastTicks = -1L
    /** Latest program tables. Repeated at the front of every segment after the first. */
    private var patPacket: ByteArray? = null
    private val pmtPids = LinkedHashSet<Int>()
    private val pmtPackets = LinkedHashMap<Int, ByteArray>()

    fun markDiscontinuity() {
        pendingBreak = true
        // The unfinished piece belongs to the connection that just died. Keeping it would
        // glue those packets to the new stream and put the discontinuity tag in the wrong place.
        pending.reset()
        remainder = ByteArray(0)
        firstTicks = -1L
        lastTicks = -1L
        segmentStartMs = -1L
        // The next connection may be a different program. Do not prefix its first pieces
        // with the tables from the stream that just died.
        patPacket = null
        pmtPids.clear()
        pmtPackets.clear()
    }

    fun feed(data: ByteArray, offset: Int, length: Int): List<Cut> {
        if (length <= 0) return emptyList()
        val merged = ByteArray(remainder.size + length)
        if (remainder.isNotEmpty()) System.arraycopy(remainder, 0, merged, 0, remainder.size)
        System.arraycopy(data, offset, merged, remainder.size, length)
        var index = syncAt(merged)
        val out = ArrayList<Cut>()
        while (index + PACKET <= merged.size) {
            if (merged[index] != SYNC) {
                index = syncAt(merged, index + 1)
                if (index < 0) break
                continue
            }
            val now = clock()
            if (segmentStartMs < 0L) segmentStartMs = now
            val elapsed = now - segmentStartMs
            val ticks = mediaTicks90k(merged, index)
            val target = if (cuts == 0) firstTargetMs else targetMs
            val keyframe = randomAccess(merged, index)
            // This packet's timestamp is the end of the piece about to close, and the start
            // of the next one. It is not written into the piece that just finished.
            // A packet with no PCR or PTS still closes on the last media timestamp. A network
            // stall must not become the segment's duration once a programme clock exists.
            val hasMediaClock = firstTicks >= 0L
            val endTicks = if (ticks >= 0L) ticks else lastTicks
            val closingMs = if (hasMediaClock && endTicks >= 0L) spanMs(firstTicks, endTicks) else 0L
            val byMedia = closingMs >= target && (keyframe || closingMs >= maxMs)
            val byWall = !hasMediaClock && elapsed >= target && (keyframe || elapsed >= maxMs)
            val stalled = elapsed >= maxMs
            if (pending.size() > 0 && (byMedia || byWall || stalled)) {
                val duration = if (hasMediaClock) closingMs.coerceAtLeast(1L) else elapsed
                out += finish(duration)
                segmentStartMs = now
                firstTicks = if (ticks >= 0L) ticks else -1L
                lastTicks = firstTicks
                prependTables()
            } else if (ticks >= 0L) {
                if (firstTicks < 0L) firstTicks = ticks
                lastTicks = ticks
            }
            rememberTables(merged, index)
            pending.write(merged, index, PACKET)
            index += PACKET
        }
        remainder = if (index in 0 until merged.size) merged.copyOfRange(index, merged.size) else ByteArray(0)
        return out
    }

    private fun rememberTables(data: ByteArray, offset: Int) {
        val pid = pidOf(data, offset)
        if (pid == 0) {
            val copy = data.copyOfRange(offset, offset + PACKET)
            val programs = patPrograms(copy, 0)
            if (programs.isEmpty()) return
            patPacket = copy
            pmtPids.clear()
            pmtPids.addAll(programs)
            pmtPackets.keys.retainAll(pmtPids)
            return
        }
        if (pid in pmtPids) {
            pmtPackets[pid] = data.copyOfRange(offset, offset + PACKET)
        }
    }

    private fun prependTables() {
        val pat = patPacket ?: return
        pending.write(pat)
        for (pid in pmtPids) {
            pmtPackets[pid]?.let { pending.write(it) }
        }
    }

    private fun finish(elapsedMs: Long): Cut {
        val bytes = pending.toByteArray()
        pending.reset()
        cuts++
        val disc = pendingBreak
        pendingBreak = false
        return Cut(bytes, elapsedMs.coerceAtLeast(1L), disc)
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()

        fun pidOf(packet: ByteArray, offset: Int): Int {
            if (offset + 3 > packet.size) return -1
            return ((packet[offset + 1].toInt() and 0x1F) shl 8) or (packet[offset + 2].toInt() and 0xFF)
        }

        /** PMT PIDs listed by a payload-start PAT. Empty when this packet is not one. */
        fun patPrograms(packet: ByteArray, offset: Int): List<Int> {
            if (offset + PACKET > packet.size || pidOf(packet, offset) != 0) return emptyList()
            if (packet[offset + 1].toInt() and 0x40 == 0) return emptyList()
            val adaptation = (packet[offset + 3].toInt() ushr 4) and 0x3
            var payload = offset + 4
            if (adaptation == 2 || adaptation == 3) {
                val length = packet[offset + 4].toInt() and 0xFF
                payload = offset + 5 + length
            } else if (adaptation != 1) {
                return emptyList()
            }
            if (payload >= offset + PACKET) return emptyList()
            val start = payload + 1 + (packet[payload].toInt() and 0xFF)
            if (start + 12 >= offset + PACKET) return emptyList()
            if (packet[start].toInt() and 0xFF != 0x00) return emptyList()
            val sectionLength = ((packet[start + 1].toInt() and 0x0F) shl 8) or (packet[start + 2].toInt() and 0xFF)
            var index = start + 8
            val loopEnd = minOf(start + 3 + sectionLength - 4, offset + PACKET)
            val pids = ArrayList<Int>()
            while (index + 4 <= loopEnd) {
                val program = ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
                val pid = ((packet[index + 2].toInt() and 0x1F) shl 8) or (packet[index + 3].toInt() and 0xFF)
                if (program != 0 && pid != 0) pids += pid
                index += 4
            }
            return pids
        }

        fun randomAccess(packet: ByteArray, offset: Int): Boolean {
            if (offset + PACKET > packet.size || packet[offset] != SYNC) return false
            val adaptation = (packet[offset + 3].toInt() ushr 4) and 0x3
            if (adaptation != 2 && adaptation != 3) return false
            val length = packet[offset + 4].toInt() and 0xFF
            if (length < 1 || offset + 5 >= packet.size) return false
            return (packet[offset + 5].toInt() and 0x40) != 0
        }

        /** 90 kHz PCR base, else PES PTS. -1 when this packet has neither. */
        fun mediaTicks90k(packet: ByteArray, offset: Int): Long {
            if (offset + PACKET > packet.size || packet[offset] != SYNC) return -1L
            val adaptation = (packet[offset + 3].toInt() ushr 4) and 0x3
            var payload = offset + 4
            if (adaptation == 2 || adaptation == 3) {
                val length = packet[offset + 4].toInt() and 0xFF
                if (length >= 7 && offset + 12 <= packet.size) {
                    val flags = packet[offset + 5].toInt() and 0xFF
                    if (flags and 0x10 != 0) return pcrBase90k(packet, offset + 6)
                }
                if (adaptation == 2) return -1L
                payload = offset + 5 + length
            }
            val pusi = packet[offset + 1].toInt() and 0x40 != 0
            if (!pusi || payload + 14 > offset + PACKET) return -1L
            if (packet[payload].toInt() != 0 || packet[payload + 1].toInt() != 0 || packet[payload + 2].toInt() != 1) {
                return -1L
            }
            return pesPts90k(packet, payload)
        }

        fun spanMs(start90k: Long, end90k: Long): Long {
            var delta = end90k - start90k
            val modulo = 1L shl 33
            if (delta < 0) delta += modulo
            // A jump this large inside one segment is a clock break, not a duration.
            if (delta > 90_000L * 30L) return 0L
            return delta / 90L
        }

        private fun pcrBase90k(packet: ByteArray, offset: Int): Long {
            if (offset + 6 > packet.size) return -1L
            val b0 = packet[offset].toLong() and 0xFF
            val b1 = packet[offset + 1].toLong() and 0xFF
            val b2 = packet[offset + 2].toLong() and 0xFF
            val b3 = packet[offset + 3].toLong() and 0xFF
            val b4 = packet[offset + 4].toLong() and 0xFF
            return (b0 shl 25) or (b1 shl 17) or (b2 shl 9) or (b3 shl 1) or (b4 ushr 7)
        }

        private fun pesPts90k(packet: ByteArray, pes: Int): Long {
            if (pes + 14 > packet.size) return -1L
            val flags = packet[pes + 7].toInt() and 0xFF
            if (flags and 0x80 == 0) return -1L
            val p = pes + 9
            val b0 = packet[p].toLong() and 0xFF
            val b1 = packet[p + 1].toLong() and 0xFF
            val b2 = packet[p + 2].toLong() and 0xFF
            val b3 = packet[p + 3].toLong() and 0xFF
            val b4 = packet[p + 4].toLong() and 0xFF
            return ((b0 and 0x0E) shl 29) or (b1 shl 22) or ((b2 and 0xFE) shl 14) or (b3 shl 7) or (b4 ushr 1)
        }

        private fun syncAt(data: ByteArray, start: Int = 0): Int {
            var i = start
            while (i + PACKET <= data.size) {
                if (data[i] == SYNC && (i + PACKET * 2 > data.size || data[i + PACKET] == SYNC)) return i
                i++
            }
            if (i < data.size && data[i] == SYNC) return i
            return if (start < data.size) data.size else -1
        }
    }
}
