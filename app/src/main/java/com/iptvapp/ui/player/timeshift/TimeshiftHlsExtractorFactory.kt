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
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TimeshiftTsExtractor

/**
 * Local timeshift segments are ordinary transport-stream files with a known length.
 * Media3's progressive TS modes duration-probe that file and return [Extractor.RESULT_SEEK].
 * The HLS chunk loader does not perform that seek; it treats the segment as finished.
 * [TimeshiftTsExtractor] keeps the HLS read path and names tracks by elementary PID.
 * Subtitles are parsed while the segment is read. Media3 1.11's text renderer accepts
 * those cues and rejects raw DVB samples. Provider HLS does not use this factory.
 */
@UnstableApi
class TimeshiftHlsExtractorFactory : HlsExtractorFactory {
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var parseSubtitlesDuringExtraction = true

    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory,
    ): HlsExtractorFactory {
        this.subtitleParserFactory = subtitleParserFactory
        return this
    }

    @Deprecated("Media3 still calls this while building an HLS source.")
    override fun experimentalParseSubtitlesDuringExtraction(
        parseSubtitlesDuringExtraction: Boolean,
    ): HlsExtractorFactory {
        this.parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format {
        if (parseSubtitlesDuringExtraction && subtitleParserFactory.supportsFormat(sourceFormat)) {
            val mime = sourceFormat.sampleMimeType.orEmpty()
            val codecs = sourceFormat.codecs
            return sourceFormat.buildUpon()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCueReplacementBehavior(subtitleParserFactory.getCueReplacementBehavior(sourceFormat))
                .setCodecs(mime + if (codecs != null) " $codecs" else "")
                .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE)
                .build()
        }
        return sourceFormat
    }

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
        val parser = if (parseSubtitlesDuringExtraction) {
            subtitleParserFactory
        } else {
            SubtitleParser.Factory.UNSUPPORTED
        }
        val flags = if (parseSubtitlesDuringExtraction) {
            0
        } else {
            TimeshiftTsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
        }
        val extractor = TimeshiftTsExtractor(
            TimeshiftTsExtractor.MODE_HLS,
            flags,
            parser,
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
