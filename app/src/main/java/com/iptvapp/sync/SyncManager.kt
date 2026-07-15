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
                "favoriteOrder"      to allFavorites.map { it.streamId }
            )

            firestore.collection("users").document(user.uid).set(data, SetOptions.merge()).await()

            // Write short-code → UID lookup so other devices can resolve the 8-char code
            val shortCode = user.uid.take(8).lowercase()
            firestore.collection("codes").document(shortCode)
                .set(hashMapOf("uid" to user.uid)).await()

            // Save own UID as target so pulling from same device works
            if (prefs.getSyncGistId().isEmpty()) prefs.setSyncGistId(user.uid)
            prefs.setLastSyncTime(System.currentTimeMillis())
            "Synced ${favChannelIds.size} favorites ✓\nSync code: ${shortCode.uppercase()}"
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

            val syncedAt   = doc.getLong("syncedAt") ?: 0L
            val syncDevice = doc.getString("device") ?: "Unknown"
            val dateStr    = if (syncedAt > 0)
                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(syncedAt))
            else "Unknown time"

            prefs.setLastSyncTime(System.currentTimeMillis())
            "Pulled ${merged.size} favorites from $syncDevice ($dateStr)"
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
