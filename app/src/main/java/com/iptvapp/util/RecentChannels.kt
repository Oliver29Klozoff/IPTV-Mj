package com.iptvapp.util

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Recent Channels: the last channels tuned on live TV, for the player's quick-switcher drawer.
 * Newest first, one entry per (provider, stream). Only what the drawer shows is kept: no stream
 * URLs, logins or tokens.
 */
data class RecentChannel(
    /** -1 for the main provider, else the extra provider's index. Stream ids repeat across providers. */
    val serverIndex: Int,
    val streamId: Int,
    val name: String,
    val iconUrl: String?,
    val serverNickname: String? = null,
) {
    fun sameChannel(serverIndex: Int, streamId: Int) = this.serverIndex == serverIndex && this.streamId == streamId
}

object RecentChannels {
    /** Kept: one more than shown, so the drawer still has [SHOWN] after leaving out the channel on now. */
    const val CAPACITY = 6
    const val SHOWN = 5

    /** [channel] moves (or goes) to the front; an older copy of it is dropped; the oldest past [capacity] go. */
    fun push(list: List<RecentChannel>, channel: RecentChannel, capacity: Int = CAPACITY): List<RecentChannel> =
        (listOf(channel) + list.filterNot { it.sameChannel(channel.serverIndex, channel.streamId) }).take(capacity)

    fun remove(list: List<RecentChannel>, serverIndex: Int, streamId: Int): List<RecentChannel> =
        list.filterNot { it.sameChannel(serverIndex, streamId) }

    /** What the drawer shows while ([playingServer], [playingStream]) is on: everything else, newest first. */
    fun available(list: List<RecentChannel>, playingServer: Int, playingStream: Int, max: Int = SHOWN): List<RecentChannel> =
        list.filterNot { it.sameChannel(playingServer, playingStream) }.take(max)

    private val gson = Gson()

    fun toJson(list: List<RecentChannel>): String = gson.toJson(list)

    /** Reads what [toJson] wrote. Damaged or unexpected text reads as an empty list, and bad entries are skipped. */
    fun fromJson(json: String?): List<RecentChannel> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JsonParser.parseString(json)
            if (!arr.isJsonArray) return emptyList()
            arr.asJsonArray.mapNotNull { el ->
                try {
                    val o = el.asJsonObject
                    val id = o.get("streamId")?.asInt ?: return@mapNotNull null
                    val server = o.get("serverIndex")?.asInt ?: return@mapNotNull null
                    val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    RecentChannel(
                        serverIndex = server,
                        streamId = id,
                        name = name,
                        iconUrl = o.get("iconUrl")?.takeIf { !it.isJsonNull }?.asString,
                        serverNickname = o.get("serverNickname")?.takeIf { !it.isJsonNull }?.asString,
                    )
                } catch (_: Exception) { null }
            }.distinctBy { it.serverIndex to it.streamId }.take(CAPACITY)
        } catch (_: Exception) { emptyList() }
    }

    /** Initials for a card with no logo: "ESPN 2" → "E2", "fox news" → "FN". */
    fun initials(name: String): String {
        val words = name.split(' ', '-', '_', '|', ':').map { it.trim() }.filter { it.isNotEmpty() }
        val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
        return letters.joinToString("").uppercase().ifEmpty { "TV" }
    }
}
