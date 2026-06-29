package com.iptvapp.tv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.iptvapp.data.local.entities.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TvHomeChannelPublisher {

    private const val TAG = "TvHomeChannelPublisher"
    private const val PREFS = "tv_channel_prefs"
    private const val KEY_CHANNEL_ID = "home_channel_id"

    private const val DEEP_LINK_SCHEME = "mktv"
    private const val DEEP_LINK_HOST   = "play"

    fun buildDeepLink(streamId: Int): Uri =
        Uri.parse("$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/$streamId")

    /** Register (or return existing) app channel on the TV home screen. */
    private suspend fun ensureChannel(context: Context): Long = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (stored != -1L) return@withContext stored

        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName("MKTV Favorites")
            .setAppLinkIntentUri(Uri.parse("$DEEP_LINK_SCHEME://home"))
            .build()

        return@withContext try {
            val uri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()
            ) ?: return@withContext -1L
            val id = ContentUris.parseId(uri)
            prefs.edit().putLong(KEY_CHANNEL_ID, id).apply()
            // Ask system to show the channel row (user must approve once)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                        .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register channel: ${e.message}")
            -1L
        }
    }

    /** Publish up to 20 favorite channels as preview program cards. */
    suspend fun publishFavorites(context: Context, channels: List<ChannelEntity>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channelId = ensureChannel(context)
        if (channelId == -1L) return

        withContext(Dispatchers.IO) {
            try {
                // Remove stale programs
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                    null, null
                )

                channels.take(20).forEachIndexed { index, ch ->
                    val program = PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                        .setTitle(ch.name)
                        .setWeight(1000 - index)
                        .setIntentUri(buildDeepLink(ch.streamId))
                        .apply {
                            if (!ch.streamIcon.isNullOrBlank()) {
                                setPosterArtUri(Uri.parse(ch.streamIcon))
                                setThumbnailUri(Uri.parse(ch.streamIcon))
                            }
                        }
                        .build()

                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        program.toContentValues()
                    )
                }
                Log.d(TAG, "Published ${channels.size.coerceAtMost(20)} programs")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish programs: ${e.message}")
            }
        }
    }
}
