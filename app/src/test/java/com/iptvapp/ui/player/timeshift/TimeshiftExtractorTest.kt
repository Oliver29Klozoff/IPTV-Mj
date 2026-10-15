package com.iptvapp.ui.player.timeshift

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.EOFException
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A known-length local segment must be read to the end. Progressive TS mode asks the
 * caller to seek once the program map is known, and the HLS chunk loader treats that
 * seek as the end of the segment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TimeshiftExtractorTest {

    @Test
    fun localSegmentReadsEveryPacketAndKeepsBothAudioPids() {
        val bytes = twoAudioProgram()
        val output = CapturingOutput()
        val chunk = TimeshiftHlsExtractorFactory().createExtractor(
            Uri.parse("file:///timeshift/index.m3u8"),
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_M3U8).build(),
            null,
            TimestampAdjuster(0L),
            emptyMap(),
            MemoryExtractorInput(bytes),
            PlayerId.UNSET,
        )
        chunk.init(output)
        val input = MemoryExtractorInput(bytes)
        var continues = 0
        while (chunk.read(input)) {
            continues++
            assertTrue(continues <= bytes.size / 188)
        }
        assertEquals(bytes.size / 188, continues)
        assertTrue(output.trackIds.contains(AUDIO_A))
        assertTrue(output.trackIds.contains(AUDIO_B))
        assertFalse(output.trackIds.contains(0x0F))
    }

    @Test
    fun dvbSubtitlesAreParsedIntoCuesBeforeTheyReachThePlayer() {
        val factory = TimeshiftHlsExtractorFactory()
        val dvb = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_DVBSUBS).build()
        val output = factory.getOutputTextFormat(dvb)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, output.sampleMimeType)
    }

    @Test
    fun progressiveSinglePmtModeSeeksOnTheSameSegment() {
        val bytes = twoAudioProgram()
        val extractor = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            TimestampAdjuster(0L),
            DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        extractor.init(CapturingOutput())
        val input = MemoryExtractorInput(bytes)
        val holder = PositionHolder()
        var sawSeek = false
        for (step in 0 until (bytes.size / 188 + 5)) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                sawSeek = true
                break
            }
            if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        assertTrue(sawSeek)
    }

    private class CapturingOutput : ExtractorOutput {
        val trackIds = mutableListOf<Int>()

        override fun track(id: Int, type: Int): TrackOutput {
            trackIds += id
            return DiscardingTrackOutput()
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) = Unit
    }

    /** Read position and peek position over one byte array. Length is known. */
    private class MemoryExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var position = 0
        private var peekPosition = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = min(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            peekPosition = position
            return count
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            return readOrPeek(target, offset, length, allowEndOfInput, peek = false)
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            if (!readFully(target, offset, length, false)) throw EOFException()
        }

        override fun skip(length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = min(length, data.size - position)
            position += count
            peekPosition = position
            return count
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (length == 0) {
                peekPosition = position
                return true
            }
            if (data.size - position < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            position += length
            peekPosition = position
            return true
        }

        override fun skipFully(length: Int) {
            if (!skipFully(length, false)) throw EOFException()
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peekPosition >= data.size) return C.RESULT_END_OF_INPUT
            val count = min(length, data.size - peekPosition)
            data.copyInto(target, offset, peekPosition, peekPosition + count)
            peekPosition += count
            return count
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            return readOrPeek(target, offset, length, allowEndOfInput, peek = true)
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            if (!peekFully(target, offset, length, false)) throw EOFException()
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - peekPosition < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            peekPosition += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            if (!advancePeekPosition(length, false)) throw EOFException()
        }

        override fun resetPeekPosition() {
            peekPosition = position
        }

        override fun getPeekPosition(): Long = peekPosition.toLong()

        override fun getPosition(): Long = position.toLong()

        override fun getLength(): Long = data.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Unit {
            this.position = position.toInt()
            peekPosition = this.position
            throw e
        }

        private fun readOrPeek(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean,
            peek: Boolean,
        ): Boolean {
            val cursor = if (peek) peekPosition else position
            if (data.size - cursor < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            data.copyInto(target, offset, cursor, cursor + length)
            if (peek) peekPosition = cursor + length else {
                position = cursor + length
                peekPosition = position
            }
            return true
        }
    }

    companion object {
        private const val PMT_PID = 0x100
        private const val AUDIO_A = 0x101
        private const val AUDIO_B = 0x102
        private const val PACKET = 188

        private fun twoAudioProgram(): ByteArray {
            val packets = ArrayList<ByteArray>(8)
            packets += psiPacket(pid = 0, section = patSection(PMT_PID))
            packets += psiPacket(pid = PMT_PID, section = pmtSection())
            repeat(6) { packets += nullPacket() }
            assertEquals(8, packets.size)
            return ByteArray(packets.size * PACKET).also { out ->
                packets.forEachIndexed { index, packet ->
                    packet.copyInto(out, index * PACKET)
                }
            }
        }

        private fun patSection(pmtPid: Int): ByteArray = withCrc(
            byteArrayOf(
                0x00,
                0xB0.toByte(),
                0x0D,
                0x00,
                0x01,
                0xC1.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                (0xE0 or ((pmtPid ushr 8) and 0x1F)).toByte(),
                (pmtPid and 0xFF).toByte(),
            ),
        )

        private fun pmtSection(): ByteArray = withCrc(
            byteArrayOf(
                0x02,
                0xB0.toByte(),
                0x17,
                0x00,
                0x01,
                0xC1.toByte(),
                0x00,
                0x00,
                (0xE0 or ((AUDIO_A ushr 8) and 0x1F)).toByte(),
                (AUDIO_A and 0xFF).toByte(),
                0xF0.toByte(),
                0x00,
                0x0F,
                (0xE0 or ((AUDIO_A ushr 8) and 0x1F)).toByte(),
                (AUDIO_A and 0xFF).toByte(),
                0xF0.toByte(),
                0x00,
                0x0F,
                (0xE0 or ((AUDIO_B ushr 8) and 0x1F)).toByte(),
                (AUDIO_B and 0xFF).toByte(),
                0xF0.toByte(),
                0x00,
            ),
        )

        private fun withCrc(sectionWithoutCrc: ByteArray): ByteArray {
            val crc = Util.crc32(sectionWithoutCrc, 0, sectionWithoutCrc.size, 0xFFFFFFFF.toInt())
            val out = ByteArray(sectionWithoutCrc.size + 4)
            sectionWithoutCrc.copyInto(out)
            out[out.size - 4] = (crc ushr 24).toByte()
            out[out.size - 3] = (crc ushr 16).toByte()
            out[out.size - 2] = (crc ushr 8).toByte()
            out[out.size - 1] = crc.toByte()
            check(Util.crc32(out, 0, out.size, 0xFFFFFFFF.toInt()) == 0)
            return out
        }

        private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
            val packet = ByteArray(PACKET)
            packet[0] = 0x47
            packet[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = 0x10
            packet[4] = 0x00
            section.copyInto(packet, destinationOffset = 5)
            return packet
        }

        private fun nullPacket(): ByteArray {
            val packet = ByteArray(PACKET)
            packet[0] = 0x47
            packet[1] = 0x1F
            packet[2] = 0xFF.toByte()
            packet[3] = 0x10
            return packet
        }
    }
}
