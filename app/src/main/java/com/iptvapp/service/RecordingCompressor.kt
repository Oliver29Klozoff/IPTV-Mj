package com.iptvapp.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Re-encodes a finished recording at a "medium" bitrate tier to shrink it, run once the raw
 * capture has already completed successfully (never live — transcoding during capture risks
 * the one thing that can't be redone: the recording itself). */
object RecordingCompressor {

    // Medium tier: noticeably smaller than a typical live-TV source bitrate while staying
    // watchable on a TV-sized screen.
    private const val BITRATE_1080P = 2_800_000
    private const val BITRATE_720P = 1_600_000
    private const val BITRATE_SD = 900_000

    private fun targetBitrateFor(sourceHeight: Int): Int = when {
        sourceHeight >= 1000 -> BITRATE_1080P
        sourceHeight >= 700 -> BITRATE_720P
        else -> BITRATE_SD
    }

    /** Returns true if [outputPath] was written successfully. */
    suspend fun compress(context: Context, inputUri: Uri, outputPath: String, sourceHeight: Int): Boolean {
        val appContext = context.applicationContext
        val bitrate = targetBitrateFor(sourceHeight)

        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrate).build()
                    )
                    .build()

                val transformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e("RecordingCompressor", "Transformer export failed", exportException)
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { runCatching { transformer.cancel() } }
                }

                try {
                    val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri)).build()
                    transformer.start(editedItem, outputPath)
                } catch (e: Exception) {
                    Log.e("RecordingCompressor", "Transformer.start() threw", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }
}
