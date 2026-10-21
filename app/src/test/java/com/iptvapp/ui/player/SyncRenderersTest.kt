package com.iptvapp.ui.player

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.util.ChannelAudioDelayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's renderers: only subtitles run on the subtitle offset's clock. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncRenderersTest {
    @Test
    fun onlyTheSubtitleRendererIsShifted() {
        val factory = PictureProfiles().renderersFactory(
            ApplicationProvider.getApplicationContext(),
            MediaCodecSelector.DEFAULT,
            passthroughAudio = false,
            audioDelay = ChannelAudioDelayState(),
            subtitleDelay = SubtitleDelayState(),
        )
        val renderers = factory.createRenderers(
            Handler(Looper.getMainLooper()),
            object : androidx.media3.exoplayer.video.VideoRendererEventListener {},
            object : androidx.media3.exoplayer.audio.AudioRendererEventListener {},
            { },
            { },
        )
        val shifted = renderers.filterIsInstance<SubtitleDelayRenderer>()
        assertEquals(1, shifted.size)
        assertEquals(C.TRACK_TYPE_TEXT, shifted.single().trackType)
        // The picture and the sound keep the player's clock (the sound offset moves the picture by its own route).
        assertTrue(renderers.filter { it.trackType == C.TRACK_TYPE_VIDEO }.none { it is SubtitleDelayRenderer })
        assertTrue(renderers.filter { it.trackType == C.TRACK_TYPE_AUDIO }.none { it is SubtitleDelayRenderer })
        assertTrue(renderers.any { it is AudioDelayVideoRenderer })
    }
}
