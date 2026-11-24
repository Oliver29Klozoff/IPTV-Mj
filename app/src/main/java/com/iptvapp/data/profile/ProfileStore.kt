package com.iptvapp.data.profile

import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The household's profiles (at most [Profile.MAX_PROFILES], Primary always first). Switching which
 * one is active moves its data in and out: that is ProfileSwitcher's job, not this store's.
 */
object ProfileStore {
    private val lock = Mutex()

    fun profiles(prefs: PreferencesManager): Flow<List<Profile>> = prefs.profilesJson.map { Profile.listFromJson(it) }

    fun profilesNow(prefs: PreferencesManager): List<Profile> = Profile.listFromJson(prefs.profilesJsonNow())

    suspend fun profilesSaved(prefs: PreferencesManager): List<Profile> = Profile.listFromJson(prefs.profilesJson.first())

    /** The active profile (Primary when the saved one is gone). */
    fun active(prefs: PreferencesManager): Flow<Profile> =
        combine(profiles(prefs), prefs.activeProfileId) { list, id -> list.firstOrNull { it.id == id } ?: list.first() }

    fun activeNow(prefs: PreferencesManager): Profile =
        profilesNow(prefs).let { list -> list.firstOrNull { it.id == prefs.activeProfileIdNow() } ?: list.first() }

    sealed class SaveResult {
        data class Saved(val profile: Profile) : SaveResult()
        object Full : SaveResult()
        object NoName : SaveResult()
    }

    /** Adds [profile], or changes the one with its id. Primary stays Primary and never Kids. */
    suspend fun save(prefs: PreferencesManager, profile: Profile): SaveResult = withContext(NonCancellable) {
        lock.withLock {
            val name = profile.name.trim().take(Profile.MAX_NAME_LENGTH)
            if (name.isEmpty()) return@withLock SaveResult.NoName
            val clean = profile.copy(name = name, isKids = profile.isKids && !profile.isPrimary)
            val list = profilesSaved(prefs)
            val updated = if (list.any { it.id == clean.id }) list.map { if (it.id == clean.id) clean else it }
            else if (list.size >= Profile.MAX_PROFILES) return@withLock SaveResult.Full
            else list + clean
            prefs.setProfilesJson(Profile.listToJson(updated))
            SaveResult.Saved(clean)
        }
    }

    /**
     * Removes profile [id] from the list. Primary can't be removed, nor the active one (switch
     * away first: its data is live). False when nothing was removed.
     */
    suspend fun remove(prefs: PreferencesManager, id: String): Boolean = withContext(NonCancellable) {
        lock.withLock {
            if (id == Profile.PRIMARY_ID || id == prefs.activeProfileId.first()) return@withLock false
            val list = profilesSaved(prefs)
            if (list.none { it.id == id }) return@withLock false
            prefs.setProfilesJson(Profile.listToJson(list.filterNot { it.id == id }))
            true
        }
    }

    /**
     * The avatar on Home and "Who's watching" in Personalize MKTV only show once a second profile
     * has been added (from Settings); a one-person household never sees them.
     */
    fun showsWhoIsWatching(profileCount: Int): Boolean = profileCount >= 2

    /** "Who's watching?" at start: on by default once there are two or more profiles. */
    fun shouldAskOnStart(askSetting: Boolean?, profileCount: Int): Boolean =
        profileCount >= 2 && (askSetting ?: true)
}
