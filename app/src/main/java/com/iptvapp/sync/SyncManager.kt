package com.iptvapp.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val db: IptvDatabase
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    /** The UID of this device's anonymous Firebase account — used as the sync code to share. */
    suspend fun getOwnSyncCode(): String = try {
        signInIfNeeded().uid
    } catch (e: Exception) { "" }

    suspend fun syncUp(): String = withContext(Dispatchers.IO) {
        try {
            val user = signInIfNeeded()
            val favChannelIds  = db.channelDao().getFavoriteChannelIds()
            val recentIds      = db.channelDao().getRecentChannels().first().take(50).map { it.streamId }
            val favCategoryIds = prefs.favoriteLiveCategoryIds.first().toList()

            // Folder ids are local autoincrement values (not portable across devices), so
            // folders are synced by NAME — push the folder name list plus a streamId->name
            // map for every favorite that's actually in a folder (Unsorted favorites are
            // omitted, same as before this feature existed).
            val folders = db.favoriteFolderDao().getAll().first()
            val folderNameById = folders.associate { it.id to it.name }
            val allFavorites = db.channelDao().getFavoriteChannelsBlocking()
            val channelFolders = allFavorites
                .mapNotNull { ch -> ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> ch.streamId.toString() to name } } }
                .toMap()

            // Only push rows with real progress — most cached VOD/series/episodes were never
            // started, and including every one would balloon the payload for no benefit (same
            // reasoning as favoriteChannelIds only including actual favorites, not every channel).
            val vodProgress = db.vodDao().getUserData()
                .filter { it.watchedMs > 0 }
                .associate { it.streamId.toString() to mapOf("watchedMs" to it.watchedMs, "durationMs" to it.durationMs) }
            val seriesProgress = db.seriesDao().getUserData()
                .filter { it.watchedMs > 0 }
                .associate { it.seriesId.toString() to mapOf("watchedMs" to it.watchedMs, "durationMs" to it.durationMs) }
            val episodesWatched = db.episodeWatchedDao().getAll()
                .map {
                    mapOf(
                        "seriesId" to it.seriesId, "season" to it.season, "episode" to it.episode,
                        "watchedAt" to it.watchedAt, "watchedMs" to it.watchedMs, "durationMs" to it.durationMs
                    )
                }

            val data = hashMapOf(
                "version"            to 1,
                "syncedAt"           to System.currentTimeMillis(),
                "device"             to android.os.Build.MODEL,
                "favoriteChannelIds" to favChannelIds,
                "favoriteCategoryIds" to favCategoryIds,
                "recentlyWatchedIds" to recentIds,
                "favoriteFolders"    to folders.map { it.name },
                "channelFolders"     to channelFolders,
                // Already in favOrder sequence (getFavoriteChannelsBlocking orders by it) —
                // pushed as-is so drag-reordering carries over too, not just which channels
                // are favorited/foldered.
                "favoriteOrder"      to allFavorites.map { it.streamId },
                "vodProgress"        to vodProgress,
                "seriesProgress"     to seriesProgress,
                "episodesWatched"    to episodesWatched
            )

            firestore.collection("users").document(user.uid).set(data, SetOptions.merge()).await()

            // Write short-code → UID lookup so other devices can resolve the 8-char code
            val shortCode = user.uid.take(8).lowercase()
            firestore.collection("codes").document(shortCode)
                .set(hashMapOf("uid" to user.uid)).await()

            // Save own UID as target so pulling from same device works
            if (prefs.getSyncGistId().isEmpty()) prefs.setSyncGistId(user.uid)
            prefs.setLastSyncTime(System.currentTimeMillis())
            "Synced ${favChannelIds.size} favorites and watch progress ✓\nSync code: ${shortCode.uppercase()}"
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }

    suspend fun syncDown(): String = withContext(Dispatchers.IO) {
        try {
            signInIfNeeded()
            val stored = prefs.getSyncGistId()
            if (stored.isEmpty()) return@withContext "No sync code set — push from your main device first, or enter a pairing code"

            // Resolve short code (≤8 chars) to full UID via lookup table
            val targetUid = if (stored.length <= 8) {
                val lookup = firestore.collection("codes").document(stored.lowercase()).get().await()
                lookup.getString("uid") ?: return@withContext "Pairing code not found — make sure the other device has pushed to cloud first"
            } else {
                stored
            }

            val doc = firestore.collection("users").document(targetUid).get().await()
            if (!doc.exists()) return@withContext "No sync data found for this code"

            @Suppress("UNCHECKED_CAST")
            val remoteFavIds = (doc.get("favoriteChannelIds") as? List<*>)
                ?.mapNotNull { (it as? Long)?.toInt() } ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val remoteCatIds = (doc.get("favoriteCategoryIds") as? List<*>)
                ?.mapNotNull { it as? String } ?: emptyList()

            val localFavIds = db.channelDao().getFavoriteChannelIds().toSet()
            val merged = (remoteFavIds + localFavIds).distinct()
            merged.forEach { db.channelDao().setFavorite(it, true) }

            val localCatIds = prefs.favoriteLiveCategoryIds.first()
            prefs.setFavoriteLiveCategoryIds(localCatIds + remoteCatIds.toSet())

            // Folders are matched/created by NAME (ids are local-only autoincrement values,
            // not portable across devices) — any remote folder name this device doesn't
            // already have gets created, then every synced channel->folder assignment is
            // applied by resolving the name to this device's local folder id.
            @Suppress("UNCHECKED_CAST")
            val remoteFolderNames = (doc.get("favoriteFolders") as? List<*>)
                ?.mapNotNull { it as? String } ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val remoteChannelFolders = (doc.get("channelFolders") as? Map<*, *>)
                ?.entries?.mapNotNull { (k, v) -> (k as? String)?.toIntOrNull()?.let { it to (v as? String) } }
                ?.filter { it.second != null } ?: emptyList()

            if (remoteFolderNames.isNotEmpty() || remoteChannelFolders.isNotEmpty()) {
                val existingFolders = db.favoriteFolderDao().getAll().first()
                val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
                var nextOrder = existingFolders.size
                for (name in remoteFolderNames) {
                    if (name !in idByName) {
                        val newId = db.favoriteFolderDao().insert(
                            com.iptvapp.data.local.entities.FavoriteFolderEntity(name = name, sortOrder = nextOrder++)
                        ).toInt()
                        idByName[name] = newId
                    }
                }
                remoteChannelFolders.forEach { (streamId, folderName) ->
                    idByName[folderName]?.let { folderId ->
                        db.channelDao().setFavoriteFolder(streamId, folderId)
                    }
                }
            }

            // Drag-reorder position — only applied for channels that exist locally (favorited
            // either already or just merged above); anything remote-only that failed to merge
            // for some reason is simply skipped rather than crashing on a bad index.
            @Suppress("UNCHECKED_CAST")
            val remoteOrder = (doc.get("favoriteOrder") as? List<*>)
                ?.mapNotNull { (it as? Long)?.toInt() } ?: emptyList()
            if (remoteOrder.isNotEmpty()) {
                val localFavoriteSet = db.channelDao().getFavoriteChannelIds().toSet()
                remoteOrder.filter { it in localFavoriteSet }
                    .forEachIndexed { index, streamId -> db.channelDao().updateFavOrder(streamId, index) }
            }

            // Watch progress merges by keeping whichever position is FURTHER ALONG, never the
            // remote value unconditionally — a stale, less-progressed row (e.g. this device
            // hasn't synced in a while but you kept watching locally) must not rewind playback
            // on either device.
            fun Any?.asLong(): Long? = when (this) {
                is Long -> this
                is Number -> this.toLong()
                else -> null
            }

            @Suppress("UNCHECKED_CAST")
            val remoteVodProgress = (doc.get("vodProgress") as? Map<String, Map<*, *>>) ?: emptyMap()
            var vodProgressMerged = 0
            remoteVodProgress.forEach { (streamIdStr, progress) ->
                val streamId = streamIdStr.toIntOrNull() ?: return@forEach
                val remoteWatched = progress["watchedMs"].asLong() ?: return@forEach
                val remoteDuration = progress["durationMs"].asLong() ?: return@forEach
                val localWatched = db.vodDao().getWatchedMs(streamId) ?: 0L
                if (remoteWatched > localWatched) {
                    db.vodDao().updateWatchProgress(streamId, remoteWatched, remoteDuration)
                    vodProgressMerged++
                }
            }

            @Suppress("UNCHECKED_CAST")
            val remoteSeriesProgress = (doc.get("seriesProgress") as? Map<String, Map<*, *>>) ?: emptyMap()
            remoteSeriesProgress.forEach { (seriesIdStr, progress) ->
                val seriesId = seriesIdStr.toIntOrNull() ?: return@forEach
                val remoteWatched = progress["watchedMs"].asLong() ?: return@forEach
                val remoteDuration = progress["durationMs"].asLong() ?: return@forEach
                val localWatched = db.seriesDao().getWatchedMs(seriesId)
                if (remoteWatched > localWatched) {
                    db.seriesDao().updateWatchProgress(seriesId, remoteWatched, remoteDuration)
                    vodProgressMerged++
                }
            }

            // Episode rows carry both a completed-watch flag (watchedAt > 0, boolean — only
            // ever applied if not already true locally, never removed) and a resume position
            // (watchedMs/durationMs, merged by keeping whichever is further along, same rule
            // as VOD/series progress above).
            @Suppress("UNCHECKED_CAST")
            val remoteEpisodesWatched = (doc.get("episodesWatched") as? List<Map<*, *>>) ?: emptyList()
            remoteEpisodesWatched.forEach { entry ->
                val seriesId = entry["seriesId"].asLong()?.toInt() ?: return@forEach
                val season = entry["season"].asLong()?.toInt() ?: return@forEach
                val episode = entry["episode"].asLong()?.toInt() ?: return@forEach
                val remoteWatchedAt = entry["watchedAt"].asLong() ?: 0L
                val remoteWatched = entry["watchedMs"].asLong() ?: 0L
                val remoteDuration = entry["durationMs"].asLong() ?: 0L

                if (remoteWatchedAt > 0 && !db.episodeWatchedDao().isWatched(seriesId, season, episode)) {
                    db.episodeWatchedDao().upsert(
                        com.iptvapp.data.local.entities.EpisodeWatchedEntity(
                            seriesId = seriesId, season = season, episode = episode,
                            watchedAt = remoteWatchedAt, watchedMs = remoteWatched, durationMs = remoteDuration
                        )
                    )
                } else if (remoteDuration > 0) {
                    val localWatched = db.episodeWatchedDao().getWatchedMs(seriesId, season, episode) ?: 0L
                    if (remoteWatched > localWatched) {
                        db.episodeWatchedDao().saveProgress(seriesId, season, episode, remoteWatched, remoteDuration)
                        vodProgressMerged++
                    }
                }
            }

            val syncedAt   = doc.getLong("syncedAt") ?: 0L
            val syncDevice = doc.getString("device") ?: "Unknown"
            val dateStr    = if (syncedAt > 0)
                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(syncedAt))
            else "Unknown time"

            prefs.setLastSyncTime(System.currentTimeMillis())
            val progressSuffix = if (vodProgressMerged > 0) " and $vodProgressMerged watch progress update${if (vodProgressMerged == 1) "" else "s"}" else ""
            "Pulled ${merged.size} favorites$progressSuffix from $syncDevice ($dateStr)"
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }

    suspend fun setPairingCode(code: String) {
        prefs.setSyncGistId(code.trim().lowercase())
    }

    suspend fun getLastSyncSummary(): String {
        val time      = prefs.lastSyncTime.first()
        val targetUid = prefs.getSyncGistId()
        val timeStr   = if (time == 0L) "Never synced"
            else "Last synced: " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(time))
        val codeStr   = if (targetUid.isNotEmpty()) "\nPaired code: ${targetUid.take(8).uppercase()}..." else ""
        return timeStr + codeStr
    }
}
