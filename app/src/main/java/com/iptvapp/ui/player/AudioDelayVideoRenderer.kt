package com.iptvapp.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.iptvapp.util.ChannelAudioDelay
import com.iptvapp.util.ChannelAudioDelayState
import java.nio.ByteBuffer

/**
 * The stock video renderer, with this channel's sound offset applied by shifting when
 * the picture is shown. Tunneled playback releases frames in the decoder, so the offset
 * does not apply while the playback is actually tunneled ([ChannelAudioDelayState.tunneling]).
 */
@UnstableApi
open class AudioDelayVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
    private val delay: ChannelAudioDelayState,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedVideoJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        // Whether this playback really tunnels: the setting asks for it, the device decides.
        delay.tunneling = configuration.tunneling
    }

    override fun onDisabled() {
        delay.tunneling = false
        super.onDisabled()
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean = super.processOutputBuffer(
        positionUs,
        elapsedRealtimeUs,
        codec,
        buffer,
        bufferIndex,
        bufferFlags,
        sampleCount,
        bufferPresentationTimeUs + ChannelAudioDelay.videoShiftUs(delay.offsetMs),
        isDecodeOnlyBuffer,
        isLastBuffer,
        format,
    )
}
