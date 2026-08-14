package com.iptvapp.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class RewatchNote(
    val id: String,
    val authorUid: String,
    val authorLabel: String,
    val positionMs: Long,
    val text: String,
    val createdAtMs: Long
)

/** Time Capsule Rewatch Notes: asynchronous, timestamp-anchored notes on VOD/episode content,
 * persisted across separate viewings (unlike WatchPartyManager, which is ephemeral/live-session
 * only). Scoping choice (see class-level note on visibility): notes are public-by-content, not
 * circle-scoped — anyone who knows/watches this exact title+season+episode (or VOD title) can see
 * its notes. A real "circle" (only people you've shared a Watch Party with) would need an
 * efficient query over Watch Party membership history, which doesn't exist as a queryable index
 * anywhere in this app yet; building that social graph is out of scope for a v1 note feature, so
 * this intentionally simpler public-by-content model was chosen instead. */
@Singleton
class RewatchNotesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val notesRoot get() = firestore.collection("rewatch_notes")

    private suspend fun signInIfNeeded() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        auth.currentUser!!
    }

    /** Stable, provider-agnostic content key. Deliberately built only from identity fields that
     * are the same across different users/providers watching "the same" title — NOT streamId/
     * seriesId/serverIndex, which are per-provider catalog ids and would silently fragment notes
     * per-provider instead of uniting them per-title. Episodes: title+season+episode. VOD: title
     * alone (normalized). */
    fun contentKeyFor(content: WatchPartyContent): String {
        val normalizedTitle = content.title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_")
        return when (content.contentType) {
            "EPISODE" -> "ep_${normalizedTitle}_s${content.seasonNum}_e${content.episodeNum}"
            else -> "vod_$normalizedTitle"
        }
    }

    private fun docToNote(id: String, m: Map<String, Any?>): RewatchNote = RewatchNote(
        id = id,
        authorUid = m["authorUid"] as? String ?: "",
        authorLabel = m["authorLabel"] as? String ?: "A friend",
        positionMs = (m["positionMs"] as? Long) ?: 0L,
        text = m["text"] as? String ?: "",
        createdAtMs = (m["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time
            ?: System.currentTimeMillis()
    )

    /** Adds a new timestamped note under this content's key. authorLabel falls back to a short
     * uid slice — the app has no display-name/nickname concept today. */
    suspend fun addNote(contentKey: String, positionMs: Long, text: String) = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        val label = "Friend-${user.uid.takeLast(4)}"
        val data = hashMapOf(
            "authorUid" to user.uid,
            "authorLabel" to label,
            "positionMs" to positionMs,
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp()
        )
        notesRoot.document(contentKey).collection("notes").add(data).await()
        Unit
    }

    /** One-shot fetch of all notes for this content, sorted by playback position. */
    suspend fun getNotes(contentKey: String): List<RewatchNote> = withContext(Dispatchers.IO) {
        val user = signInIfNeeded()
        val snap = notesRoot.document(contentKey).collection("notes").get().await()
        snap.documents.mapNotNull { d -> d.data?.let { docToNote(d.id, it) } }
            .sortedBy { it.positionMs }
            .also { _ -> user } // keep signInIfNeeded's anonymous auth as the read gate, matching WatchPartyManager's pattern
    }

    /** Live listener variant, same hasPendingWrites() echo-filter WatchPartyManager's listen() uses. */
    fun listenNotes(contentKey: String, onUpdate: (List<RewatchNote>) -> Unit): ListenerRegistration {
        return notesRoot.document(contentKey).collection("notes")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
                val notes = snap.documents.mapNotNull { d -> d.data?.let { docToNote(d.id, it) } }
                    .sortedBy { it.positionMs }
                onUpdate(notes)
            }
    }
}
