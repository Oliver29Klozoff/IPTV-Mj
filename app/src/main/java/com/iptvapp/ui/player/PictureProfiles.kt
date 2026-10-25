package com.iptvapp.ui.player

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.iptvapp.util.ChannelAudioDelayState

/**
 * Applies a system picture profile to the video decoder on Android 16 and newer.
 * Older devices, and TVs without that profile, keep the picture they already use.
 * The platform has no public codec key for a sound profile, so sound is left to the TV.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PictureProfiles {
    private var profile: Any? = null
    private var videoAdapter: MediaCodecAdapter? = null

    fun apply(next: Any?) {
        profile = next
        videoAdapter?.let { push(it, next) }
    }

    /**
     * [dolbyVisionBaseLayer]: decode Dolby Vision as its base layer where the profile has one
     * (see DolbyVisionRenderer). Off keeps the stock video renderer.
     */
    fun renderersFactory(
        context: Context,
        selector: MediaCodecSelector,
        passthroughAudio: Boolean,
        dolbyVisionBaseLayer: Boolean = false,
        audioDelay: ChannelAudioDelayState,
        subtitleDelay: SubtitleDelayState? = null,
    ): DefaultRenderersFactory {
        val holder = this
        val factory = object : DefaultRenderersFactory(context) {
            override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
                return ProfileAdapter(super.getCodecAdapterFactory(), holder)
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out
                )
                val at = out.indexOfFirst { it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer }
                if (at < 0) return
                // Same Builder defaults as DefaultRenderersFactory's own renderer (checked against
                // Media3 1.11.1). Dolby Vision only changes the decoder; every channel gets the
                // sound-timing offset.
                out[at] = if (dolbyVisionBaseLayer) {
                    DolbyVisionRenderer(
                        context, getCodecAdapterFactory(), mediaCodecSelector, allowedVideoJoiningTimeMs,
                        enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                        audioDelay,
                    )
                } else {
                    AudioDelayVideoRenderer(
                        context, getCodecAdapterFactory(), mediaCodecSelector, allowedVideoJoiningTimeMs,
                        enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                        audioDelay,
                    )
                }
            }

            override fun buildTextRenderers(
                context: Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                // [out] already holds the video and audio renderers: only the ones added here are
                // subtitles, and only they run on the subtitle offset's clock (SubtitleDelayRenderer).
                val first = out.size
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                if (subtitleDelay != null) {
                    for (i in first until out.size) {
                        if (out[i].trackType == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                            out[i] = SubtitleDelayRenderer(out[i], subtitleDelay)
                        }
                    }
                }
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                if (!passthroughAudio) {
                    return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                        ?: androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .build()
                }
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        return factory.setMediaCodecSelector(selector)
    }

    private class ProfileAdapter(
        private val base: MediaCodecAdapter.Factory,
        private val holder: PictureProfiles
    ) : MediaCodecAdapter.Factory {
        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
            val adapter = base.createAdapter(configuration)
            val mime = configuration.format.sampleMimeType
            if (mime != null && mime.startsWith("video/")) {
                holder.videoAdapter = adapter
                push(adapter, holder.profile)
            }
            return adapter
        }
    }

    companion object {
        fun lookup(context: Context, name: String): Any? {
            if (Build.VERSION.SDK_INT < 36) return null
            return Api36.lookup(context, name)
        }

        private fun push(adapter: MediaCodecAdapter, profile: Any?) {
            if (profile == null || Build.VERSION.SDK_INT < 36) return
            Api36.push(adapter, profile)
        }
    }

    /** Loaded only on Android 16. The picture-profile classes are not on older devices. */
    @androidx.annotation.RequiresApi(36)
    private object Api36 {
        fun lookup(context: Context, name: String): android.media.quality.PictureProfile? {
            return try {
                val manager = context.getSystemService(android.media.quality.MediaQualityManager::class.java)
                    ?: return null
                manager.getPictureProfile(android.media.quality.PictureProfile.TYPE_SYSTEM, name, null)
            } catch (_: RuntimeException) {
                null
            }
        }

        fun push(adapter: MediaCodecAdapter, profile: Any) {
            val bundle = Bundle()
            bundle.putParcelable(MediaFormat.KEY_PICTURE_PROFILE_INSTANCE, profile as android.media.quality.PictureProfile)
            try {
                adapter.setParameters(bundle)
            } catch (_: RuntimeException) {
                // The decoder rejected the profile. Playback continues with the TV's current picture.
            }
        }
    }
}
