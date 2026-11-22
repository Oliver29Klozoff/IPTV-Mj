package com.iptvapp.data.profile

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.iptvapp.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One person (or room) watching: their own favorites, history, Home layout and look
 * (ProfileSwitcher keeps each profile's copy). A Kids profile hides adult categories and Settings.
 * There are no PINs: anyone can switch profiles.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val avatar: Avatar = Avatar.USER,
    @ColorInt val color: Int = COLORS[0],
    val isKids: Boolean = false,
    /** Viewing style (ViewingPersona id) set up the first time this profile opens; null keeps the defaults. */
    val startPersonaId: String? = null,
) {
    /** The avatar picture: MKTV has no icon set for these, so each is a glyph. */
    enum class Avatar(val id: String, val glyph: String, @StringRes val labelRes: Int) {
        TV("tv", "📺", R.string.profile_avatar_tv),
        USER("user", "🙂", R.string.profile_avatar_user),
        SPORTS("sports", "🏆", R.string.profile_avatar_sports),
        CHILD("child", "🧸", R.string.profile_avatar_child),
        MOVIE("movie", "🎬", R.string.profile_avatar_movie),
        STAR("star", "⭐", R.string.profile_avatar_star);

        companion object {
            fun fromId(id: String?): Avatar = entries.firstOrNull { it.id == id } ?: USER
        }
    }

    val isPrimary: Boolean get() = id == PRIMARY_ID

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("avatar", avatar.id).put("color", color).put("kids", isKids)
        .apply { startPersonaId?.let { put("persona", it) } }

    companion object {
        /** The profile everything set up before profiles belongs to. It can't be deleted. */
        const val PRIMARY_ID = "default_primary"
        const val MAX_PROFILES = 6
        const val MAX_NAME_LENGTH = 20

        /** Cyan, Emerald, Amber, Crimson, Purple, Pink. */
        val COLORS = listOf(
            0xFF008CFF.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(),
            0xFFEF4444.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(),
        )

        fun primary(name: String = "Primary"): Profile =
            Profile(id = PRIMARY_ID, name = name, avatar = Avatar.TV, color = COLORS[0])

        fun fromJson(o: JSONObject): Profile? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = o.optString("name").trim().take(MAX_NAME_LENGTH).ifBlank { return null }
            return Profile(
                id = id, name = name, avatar = Avatar.fromId(o.optString("avatar")),
                color = o.optInt("color", COLORS[0]),
                // Primary is never a Kids profile: it is the way back to everything.
                isKids = id != PRIMARY_ID && o.optBoolean("kids", false),
                startPersonaId = o.optString("persona").takeIf { it.isNotBlank() },
            )
        }

        /** Saved list, always with Primary first, no duplicate ids, at most [MAX_PROFILES]. */
        fun listFromJson(json: String?): List<Profile> {
            val parsed = try {
                JSONArray(json ?: "[]").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) } }
            } catch (_: Exception) {
                emptyList()
            }.distinctBy { it.id }
            val primary = parsed.firstOrNull { it.isPrimary } ?: primary()
            return (listOf(primary) + parsed.filterNot { it.isPrimary }).take(MAX_PROFILES)
        }

        fun listToJson(profiles: List<Profile>): String = JSONArray(profiles.map { it.toJson() }).toString()
    }
}
