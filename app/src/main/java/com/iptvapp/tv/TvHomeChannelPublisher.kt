package com.iptvapp.tv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object TvHomeChannelPublisher {

    private const val TAG = "TvHomeChannelPublisher"
    private const val PREFS = "tv_channel_prefs"
    private const val KEY_CHANNEL_ID = "home_channel_id"

    const val MAX_PROGRAMS = 20

    /** One card. [link] is a [LauncherLink] — no URL or login in it. */
    data class Entry(val streamId: Int, val title: String, val icon: String?, val link: String)

    // Each publish clears the row and fills it again. Two at once could interleave and leave
    // the same channel on the row twice.
    private val publishLock = Mutex()

    /** Only Android TV / Google TV home screens have these rows; anywhere else (phones, the
     * car box) there is nothing to publish to. */
    private fun hasTvHome(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /**
     * Our row on the TV home screen, made if [create] and it doesn't exist yet. -1 = none.
     * A row the system no longer has (deleted by the user or the launcher) is made again rather
     * than published into.
     */
    private fun ensureChannel(context: Context, create: Boolean): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (stored != -1L) {
            val exists = try {
                context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored), arrayOf(BaseColumns._ID), null, null, null
                )?.use { it.moveToFirst() }
            } catch (e: Exception) {
                // Can't tell: keep the row we have rather than risk making a second one.
                true
            }
            if (exists != false) return stored
            prefs.edit().remove(KEY_CHANNEL_ID).commit()
        }
        if (!create) return -1L

        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName("MKTV Favorites")
            .setAppLinkIntentUri(Uri.parse("${LauncherLink.SCHEME}://home"))
            .build()

        return try {
            val uri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()
            ) ?: return -1L
            val id = ContentUris.parseId(uri)
            prefs.edit().putLong(KEY_CHANNEL_ID, id).commit()
            // Ask system to show the channel row (user must approve once)
            context.startActivity(
                Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                    .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register channel: ${e.javaClass.simpleName}")
            -1L
        }
    }

    /**
     * Makes the row show exactly [entries] (first [MAX_PROGRAMS]), one card per channel. An empty
     * list clears the row — the last favorite removed no longer stays on the home screen — but
     * never creates one.
     */
    suspend fun publishFavorites(context: Context, entries: List<Entry>) {
        if (!hasTvHome(context)) return
        val cards = entries.distinctBy { it.streamId }.take(MAX_PROGRAMS)
        publishLock.withLock {
            withContext(Dispatchers.IO) {
                val channelId = ensureChannel(context, create = cards.isNotEmpty())
                if (channelId == -1L) return@withContext
                try {
                    context.contentResolver.delete(
                        TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                        null, null
                    )
                    cards.forEachIndexed { index, card ->
                        val program = PreviewProgram.Builder()
                            .setChannelId(channelId)
                            .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                            .setTitle(card.title)
                            .setWeight(1000 - index)
                            .setIntentUri(Uri.parse(card.link))
                            .apply {
                                if (!card.icon.isNullOrBlank()) {
                                    setPosterArtUri(Uri.parse(card.icon))
                                    setThumbnailUri(Uri.parse(card.icon))
                                }
                            }
                            .build()

                        context.contentResolver.insert(
                            TvContractCompat.PreviewPrograms.CONTENT_URI,
                            program.toContentValues()
                        )
                    }
                    Log.d(TAG, "Published ${cards.size} programs")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish programs: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
