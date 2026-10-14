package com.iptvapp.ui.player.timeshift

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TimeshiftTsExtractor

/**
 * Local timeshift segments are ordinary transport-stream files with a known length.
 * Media3's progressive TS modes duration-probe that file and return [Extractor.RESULT_SEEK].
 * The HLS chunk loader does not perform that seek; it treats the segment as finished.
 * [TimeshiftTsExtractor] keeps the HLS read path and names tracks by elementary PID.
 * Provider HLS does not use this factory.
 */
@UnstableApi
class TimeshiftHlsExtractorFactory : HlsExtractorFactory {
    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        val captions = muxedCaptionFormats ?: listOf(
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build(),
        )
        val extractor = TimeshiftTsExtractor(
            TimeshiftTsExtractor.MODE_HLS,
            TimeshiftTsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            timestampAdjuster,
            DefaultTsPayloadReaderFactory(
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM,
                captions,
            ),
            TimeshiftTsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        return try {
            sniffingExtractorInput.resetPeekPosition()
            if (extractor.sniff(sniffingExtractorInput)) {
                ReusableTsChunkExtractor(extractor)
            } else {
                defaultExtractor(
                    uri, format, muxedCaptionFormats, timestampAdjuster,
                    responseHeaders, sniffingExtractorInput, playerId,
                )
            }
        } catch (_: Exception) {
            defaultExtractor(
                uri, format, muxedCaptionFormats, timestampAdjuster,
                responseHeaders, sniffingExtractorInput, playerId,
            )
        }
    }

    private fun defaultExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor = HlsExtractorFactory.DEFAULT.createExtractor(
        uri,
        format,
        muxedCaptionFormats,
        timestampAdjuster,
        responseHeaders,
        sniffingExtractorInput,
        playerId,
    )

    /**
     * Reusable across continuous segments. Media3's bundled chunk extractor only treats a
     * stock TsExtractor as reusable, and its recreate path cannot build this fork.
     * Local segments are complete files, so a truncated-segment reset is a no-op.
     * TimeshiftTsExtractor.seek rejects HLS mode.
     */
    private class ReusableTsChunkExtractor(
        private val extractor: Extractor,
    ) : HlsMediaChunkExtractor {
        private val seekPosition = PositionHolder()

        override fun init(extractorOutput: ExtractorOutput) {
            extractor.init(extractorOutput)
        }

        override fun read(extractorInput: ExtractorInput): Boolean {
            return extractor.read(extractorInput, seekPosition) == Extractor.RESULT_CONTINUE
        }

        override fun isPackedAudioExtractor(): Boolean = false

        override fun isReusable(): Boolean = true

        override fun recreate(): HlsMediaChunkExtractor {
            throw IllegalStateException("Timeshift TS extractor is reusable")
        }

        override fun onTruncatedSegmentParsed() = Unit
    }
}
