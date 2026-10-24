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
        val next: Boolean = false,
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
        val missingMovies = prefs.launcherUnavailable(creds, "movie")
        val missingSeries = prefs.launcherUnavailable(creds, "series")
        val movies = db.vodDao().getInProgressVodRecent(MAX_ITEMS).filter { it.streamId !in missingMovies }.mapNotNull { vod ->
            val token = LauncherLink.token(key, LauncherLink.movieIdentity(creds.serverUrl, creds.username, vod.streamId))
            val link = runCatching { LauncherLink.buildMovie(vod.streamId, token) }.getOrNull() ?: return@mapNotNull null
            Item("movie:$token", vod.name, null, null, null, vod.streamIcon, link,
                vod.watchedMs, vod.durationMs, vod.lastWatchedAt)
        }
        val episodes = db.seriesDao().launcherRecent().filter { it.seriesId !in missingSeries }.mapNotNull { series ->
            val remembered = LauncherEpisodeCache.last(context, creds, series.seriesId)
            val fallback = if (remembered == null) db.episodeWatchedDao().launcherLatest(series.seriesId) else null
            val season = remembered?.first ?: fallback?.season ?: return@mapNotNull null
            val episode = remembered?.second ?: fallback?.episode ?: return@mapNotNull null
            val watched = db.episodeWatchedDao().getWatchedMs(series.seriesId, season, episode) ?: 0
            val duration = db.episodeWatchedDao().getDurationMs(series.seriesId, season, episode) ?: 0
            if (watched <= 0 || duration <= 0) return@mapNotNull null
            val completed = watched.toDouble() / duration >= 0.95
            val next = if (completed) LauncherEpisodeCache.next(LauncherEpisodeCache.read(context, creds, series.seriesId), season, episode)
                ?: return@mapNotNull null else null
            if (next != null && (db.episodeWatchedDao().getWatchedMs(series.seriesId, next.season, next.episode) ?: 0) > 0) return@mapNotNull null
            val targetSeason = next?.season ?: season
            val targetEpisode = next?.episode ?: episode
            val token = LauncherLink.token(key, LauncherLink.episodeIdentity(creds.serverUrl, creds.username, series.seriesId, targetSeason, targetEpisode))
            Item("series:" + LauncherLink.token(key, LauncherLink.movieIdentity(creds.serverUrl, creds.username, series.seriesId)),
                series.name, next?.title ?: "S${season}E${episode}", targetSeason, targetEpisode, series.cover,
                LauncherLink.buildEpisode(series.seriesId, targetSeason, targetEpisode, token), if (completed) 0 else watched,
                if (completed) 0 else duration, series.lastWatchedAt, next = completed)
        }
        (movies + episodes).filter { it.next || it.positionMs > 0 && it.durationMs > 0 }
            .sortedByDescending { it.engagedAtMs }
            .take(MAX_ITEMS)
    }

    /**
     * Reads what is in progress and matches the row to it, as one step under the lock: two
     * refreshes at once (the player saving, the home screen returning) cannot let an older
     * reading land after a newer one.
     */
    suspend fun refresh(context: Context, db: IptvDatabase, prefs: PreferencesManager) {
        if (!hasTvHome(context)) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    sync(context, if (TvHomeIntegration.enabled(context)) items(context, db, prefs) else emptyList())
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) {
                    // The home screen is a nicety: never let it break MKTV.
                    Log.w(TAG, "Watch Next publish failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Logging out: the viewing history leaves the TV home screen with the account. */
    suspend fun clearAll(context: Context) {
        if (!hasTvHome(context)) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Watch Next clear failed: ${e.javaClass.simpleName}")
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
                if (existing.containsKey(providerId)) resolver.delete(TvContractCompat.buildWatchNextProgramUri(c.getLong(0)), null, null)
                else existing[providerId] = Existing(c.getLong(0), c.getInt(2) != 0, c.getLong(3))
            }
        }
        val hidden = context.getSharedPreferences("launcher_hidden_titles", Context.MODE_PRIVATE)
        val hiddenEdit = hidden.edit()
        existing.filterValues { !it.browsable }.forEach { (id, row) -> hiddenEdit.putLong(id, row.engagedAtMs) }
        hiddenEdit.apply()
        val wanted = items.associateBy { it.key }
        // Remove stale provider rows; private tombstones preserve user dismissal across cleanup.
        for ((providerId, row) in existing) {
            if (providerId !in wanted) {
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(row.id), null, null)
            }
        }
        for (item in items) {
            val old = existing[item.key]
            if (hidden.contains(item.key) && item.engagedAtMs <= hidden.getLong(item.key, 0)) continue
            hidden.edit().remove(item.key).apply()
            // Removed from the row by the viewer: stays removed until the title is played again.
            if (old != null && !old.browsable && item.engagedAtMs <= old.engagedAtMs) continue
            val values = program(context, item).toContentValues()
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

    private fun program(context: Context, item: Item): WatchNextProgram {
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(if (item.next) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
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
        builder.setPosterArtUri(Uri.parse(LauncherMetadata.artwork(item.image) ?: "android.resource://${context.packageName}/${context.applicationInfo.icon}"))
        return builder.build()
    }
}
