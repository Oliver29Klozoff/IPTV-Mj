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
    private fun ensureChannel(context: Context, create: Boolean, row: String, title: String): Long {
        val channelKey = if (row == "favorites") KEY_CHANNEL_ID else "channel_$row"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(channelKey, -1L)
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
            return -1L // A deleted row remains deleted until the user explicitly enables integration again.
        }
        if (!create) return -1L
        // Recover an insert completed before the preference write (process death).
        try {
            context.contentResolver.query(TvContractCompat.Channels.CONTENT_URI,
                arrayOf("_id", "internal_provider_id"), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(1) == "mktv:$row") {
                    val id = cursor.getLong(0)
                    prefs.edit().putLong(channelKey, id).commit()
                    return id
                }
            }
        } catch (_: Exception) { return -1L }


        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(title)
            .setInternalProviderId("mktv:$row")
            .setAppLinkIntentUri(Uri.parse("${LauncherLink.SCHEME}://home"))
            .build()

        return try {
            val uri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()
            ) ?: return -1L
            val id = ContentUris.parseId(uri)
            prefs.edit().putLong(channelKey, id).commit()
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 128, 128)
                drawable.draw(android.graphics.Canvas(bitmap))
                androidx.tvprovider.media.tv.ChannelLogoUtils.storeChannelLogo(context, id, bitmap)
            }
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
    suspend fun publishFavorites(context: Context, entries: List<Entry>) =
        publish(context, "favorites", "MKTV Favorites", entries)

    suspend fun publish(context: Context, row: String, title: String, entries: List<Entry>, recording: Boolean = false) {
        if (!hasTvHome(context)) return
        publishLock.withLock {
            withContext(Dispatchers.IO) {
                val cards = if (TvHomeIntegration.enabled(context)) entries.distinctBy { it.link }.take(MAX_PROGRAMS) else emptyList()
                val channelId = ensureChannel(context, cards.isNotEmpty(), row, title)
                if (channelId == -1L) return@withContext
                try {
                    val existing = mutableMapOf<String, Long>()
                    context.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                        arrayOf("_id", "internal_provider_id"), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val key = cursor.getString(1)
                            if (key == null || existing.containsKey(key)) context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(id), null, null)
                            else existing[key] = id
                        }
                    }
                    cards.forEachIndexed { index, card ->
                        val program = PreviewProgram.Builder().setChannelId(channelId)
                            .setType(if (recording) TvContractCompat.PreviewPrograms.TYPE_CLIP else TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                            .setTitle(card.title).setWeight(1000 - index).setInternalProviderId(card.link)
                            .setIntentUri(Uri.parse(card.link)).apply {
                                setPosterArtUri(Uri.parse(LauncherMetadata.artwork(card.icon) ?: "android.resource://${context.packageName}/${context.applicationInfo.icon}"))
                            }.build().toContentValues()
                        val old = existing.remove(card.link)
                        if (old == null) context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program)
                        else context.contentResolver.update(TvContractCompat.buildPreviewProgramUri(old), program, null, null)
                    }
                    existing.values.forEach { context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(it), null, null) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { Log.w(TAG, "Launcher row unavailable") }
            }
        }
    }

    /** Called only from an explicit Settings action, never a background synchronization. */
    fun requestApproval(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = listOf(KEY_CHANNEL_ID to "Favorites", "channel_recent" to "Recently Watched", "channel_recordings" to "Recent Recordings")
            .map { prefs.getLong(it.first, -1) to it.second }.filter { it.first >= 0 }
        if (context !is android.app.Activity || rows.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(context).setTitle("Show launcher row")
            .setItems(rows.map { it.second }.toTypedArray()) { _, which ->
                runCatching { context.startActivity(Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                    .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, rows[which].first)) }
            }.setNegativeButton("Cancel", null).show()
    }

    fun restoreDeletedRows(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        listOf(KEY_CHANNEL_ID, "channel_recent", "channel_recordings").forEach { key ->
            val id = prefs.getLong(key, -1)
            if (id >= 0) runCatching {
                val exists = context.contentResolver.query(TvContractCompat.buildChannelUri(id), arrayOf("_id"), null, null, null)?.use { it.moveToFirst() }
                if (exists == false) prefs.edit().remove(key).apply()
            }
        }
    }
}
