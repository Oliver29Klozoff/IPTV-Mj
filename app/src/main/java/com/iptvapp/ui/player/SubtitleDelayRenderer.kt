package com.iptvapp.ui.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/** The subtitle offset now (milliseconds, positive = later). Written on the main thread, read on the playback thread. */
class SubtitleDelayState {
    @Volatile var offsetMs: Int = 0
}

/**
 * The stock subtitle renderer (Media3's TextRenderer, which is final) on a shifted clock: asked
 * to render at a position [SubtitleDelayState.offsetMs] earlier, it shows each cue that much
 * later, start and end alike; a negative offset shows cues that much earlier. Cues are read
 * ahead of the position from what is already buffered, so an earlier offset works as far as
 * the buffer reaches (a file or VOD: all of it; live TV: what is downloaded ahead).
 *
 * Every text format the player handles goes through it unchanged (SRT, WebVTT, TTML, SSA,
 * CEA-608/708, DVB, a file opened from the device): only the clock it sees moves, so styling,
 * positions, forced cues and overlapping cues are the renderer's own.
 *
 * Changing the offset while a cue is on screen: moving subtitles earlier updates at once;
 * moving them later updates at the next cue change (the renderer only re-emits cues when one
 * starts or ends, and its internals are not open to this wrapper).
 */
@UnstableApi
class SubtitleDelayRenderer(delegate: Renderer, private val delay: SubtitleDelayState) : ForwardingRenderer(delegate) {

    private fun shifted(positionUs: Long): Long = positionUs - delay.offsetMs * 1_000L

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(shifted(positionUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        super.resetPosition(shifted(positionUs), sampleStreamIsResetToKeyFrame)
    }

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        // The renderer's own position moves; where its stream starts (and its time base) does not.
        super.enable(configuration, formats, stream, shifted(positionUs), joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }
}
