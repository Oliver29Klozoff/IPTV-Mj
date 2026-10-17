package com.iptvapp.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.iptvapp.util.DolbyVisionFallback

/**
 * The video renderer, except that a Dolby Vision stream is decoded as its base layer when
 * [DolbyVisionFallback.useBaseLayer] says so (the screen cannot show Dolby Vision, or Settings
 * asks for it), and only for profiles that have one. The choice needs the stream's profile,
 * which a MediaCodecSelector never sees; the renderer does.
 *
 * Decoding a Dolby Vision format on its base decoder is the same route Media3 takes on its own
 * for profiles 4, 8, 9 and 10 when a device has no Dolby Vision decoder.
 */
@UnstableApi
class DolbyVisionRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    selector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    private val appContext = context.applicationContext

    private fun baseLayerMime(format: Format): String? {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null
        return DolbyVisionFallback.baseLayerMime(MediaCodecUtil.getCodecProfileAndLevel(format)?.first)
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val base = baseLayerMime(format) ?: return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val decoders = mediaCodecSelector.getDecoderInfos(base, requiresSecureDecoder, false)
        if (decoders.isEmpty()) return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(appContext, decoders, format)
    }

    /** Judged on the layer that will actually be decoded, so a profile 7 stream is not refused. */
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val base = baseLayerMime(format) ?: return super.supportsFormat(mediaCodecSelector, format)
        return super.supportsFormat(mediaCodecSelector, format.buildUpon().setSampleMimeType(base).setCodecs(null).build())
    }
}
