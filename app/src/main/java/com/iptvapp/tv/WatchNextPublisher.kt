package com.iptvapp.tv

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Puts the main provider's unfinished movies and episodes on the Android TV / Google TV home
 * screen's Watch Next row, the same titles as Smart Home's Continue Watching. A card opens the
 * title in MKTV through a signed link (see [LauncherLink.buildMovie]), so it only works for the
 * provider account it was published for and carries no login.
 *
 * Each publish matches the row to what is in progress now: new titles are added, positions
 * updated, finished or dismissed ones removed. A card the viewer removed from the row is left
 * removed until that title is played again.
 */
object WatchNextPublisher {
    private const val TAG = "WatchNextPublisher"
    const val MAX_ITEMS = 10

    data class Item(
        /** Stable per title, kept in the card's internal provider id. */
        val key: String,
        val title: String,
        val episodeTitle: String?,
        val season: Int?,
        val episode: Int?,
        val image: String?,
        val link: String,
        val positionMs: Long,
        val durationMs: Long,
        val engagedAtMs: Long,
    )

    private val lock = Mutex()

    private fun hasTvHome(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** What is in progress now, newest first, each with its signed link. */
    suspend fun items(context: Context, db: IptvDatabase, prefs: PreferencesManager): List<Item> = withContext(Dispatchers.IO) {
        val creds = prefs.credentials.first()
        if (creds.serverUrl.isBlank()) return@withContext emptyList()
        val key = LauncherLinkKey.get(context)
        val movies = db.vodDao().getInProgressVodRecent(MAX_ITEMS).mapNotNull { vod ->
            val token = LauncherLink.token(key, LauncherLink.movieIdentity(creds.serverUrl, creds.username, vod.streamId))
            val link = runCatching { LauncherLink.buildMovie(vod.streamId, token) }.getOrNull() ?: return@mapNotNull null
            Item("movie:${vod.streamId}", vod.name, null, null, null, vod.streamIcon, link,
                vod.watchedMs, vod.durationMs, vod.lastWatchedAt)
        }
        val episodes = db.seriesDao().getInProgressSeries().first().mapNotNull { row ->
            val s = row.series
            val token = LauncherLink.token(
                key, LauncherLink.episodeIdentity(creds.serverUrl, creds.username, s.seriesId, row.lastSeason, row.lastEpisode)
            )
            val link = runCatching { LauncherLink.buildEpisode(s.seriesId, row.lastSeason, row.lastEpisode, token) }.getOrNull()
                ?: return@mapNotNull null
            Item("episode:${s.seriesId}", s.name, "S${row.lastSeason}E${row.lastEpisode}", row.lastSeason, row.lastEpisode,
                s.cover, link, row.lastEpisodeWatchedMs, row.lastEpisodeDurationMs, s.lastWatchedAt)
        }
        (movies + episodes).filter { it.positionMs > 0 && it.durationMs > 0 }
            .sortedByDescending { it.engagedAtMs }
            .take(MAX_ITEMS)
    }

    suspend fun publish(context: Context, items: List<Item>) {
        if (!hasTvHome(context)) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    sync(context, items)
                } catch (e: Exception) {
                    // The home screen is a nicety: never let it break MKTV.
                    Log.w(TAG, "Watch Next publish failed: ${e.message}")
                }
            }
        }
    }

    private data class Existing(val id: Long, val browsable: Boolean, val engagedAtMs: Long)

    private fun sync(context: Context, items: List<Item>) {
        val resolver = context.contentResolver
        val existing = HashMap<String, Existing>()
        // The provider only returns this app's own Watch Next rows.
        resolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(
                TvContractCompat.WatchNextPrograms._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val providerId = c.getString(1) ?: continue
                existing[providerId] = Existing(c.getLong(0), c.getInt(2) != 0, c.getLong(3))
            }
        }
        val wanted = items.associateBy { it.key }
        // Finished, dismissed or out of the top: off the row.
        for ((providerId, row) in existing) {
            if (providerId !in wanted) {
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(row.id), null, null)
            }
        }
        for (item in items) {
            val old = existing[item.key]
            // Removed from the row by the viewer: stays removed until the title is played again.
            if (old != null && !old.browsable && item.engagedAtMs <= old.engagedAtMs) continue
            val values = program(item).toContentValues()
            when {
                old == null -> resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                // Removed by the viewer, played again since. An update cannot make it visible again:
                // toContentValues() leaves out the protected browsable column. A fresh row can.
                !old.browsable -> {
                    resolver.delete(TvContractCompat.buildWatchNextProgramUri(old.id), null, null)
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                }
                else -> resolver.update(TvContractCompat.buildWatchNextProgramUri(old.id), values, null, null)
            }
        }
    }

    private fun program(item: Item): WatchNextProgram {
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(
                if (item.season != null) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                else TvContractCompat.PreviewPrograms.TYPE_MOVIE
            )
            .setTitle(item.title)
            .setInternalProviderId(item.key)
            .setIntentUri(Uri.parse(item.link))
            .setLastEngagementTimeUtcMillis(item.engagedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setLastPlaybackPositionMillis(item.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(item.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setBrowsable(true)
        item.episodeTitle?.let { builder.setEpisodeTitle(it) }
        item.season?.let { builder.setSeasonNumber(it) }
        item.episode?.let { builder.setEpisodeNumber(it) }
        item.image?.takeIf { it.startsWith("http") }?.let { builder.setPosterArtUri(Uri.parse(it)) }
        return builder.build()
    }
}
