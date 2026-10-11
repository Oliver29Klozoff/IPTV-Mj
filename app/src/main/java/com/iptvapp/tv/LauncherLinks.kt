package com.iptvapp.tv

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.iptvapp.data.api.XtreamUrlBuilder
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.ServerCredentials
import com.iptvapp.data.local.entities.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Android side of [LauncherLink]: signs cards for publishing and resolves a tapped card. */
object LauncherLinks {

    /** Cards for these channels, signed for the provider account signed in now. */
    suspend fun entriesFor(
        context: Context,
        channels: List<ChannelEntity>,
        creds: ServerCredentials
    ): List<TvHomeChannelPublisher.Entry> = withContext(Dispatchers.IO) {
        val key = LauncherLinkKey.get(context)
        // One channel that can't be linked is left off the row; it never takes the row (or the
        // home screen collecting it) down with it.
        channels.mapNotNull { ch ->
            val link = runCatching { linkFor(key, ch, creds) }.getOrNull() ?: return@mapNotNull null
            TvHomeChannelPublisher.Entry(
                streamId = ch.streamId,
                title = ch.name,
                icon = ch.streamIcon,
                link = link
            )
        }
    }

    /** The same link a home-screen card for [channel] would carry (Settings' "Play This Channel"). */
    suspend fun forChannel(context: Context, prefs: PreferencesManager, channel: ChannelEntity): String {
        val creds = prefs.credentials.first()
        return withContext(Dispatchers.IO) { linkFor(LauncherLinkKey.get(context), channel, creds) }
    }

    /** A tapped card that checked out: its channel and the URL to play. */
    data class Resolved(val channel: ChannelEntity, val url: String)

    /**
     * The channel a tapped card names and its playback URL, or null when it can't be played
     * safely: the channel is gone, or the card was made for another provider account (switched,
     * removed, other login). Never falls back to another channel or provider with the same id.
     *
     * The token is checked against one reading of the login and the URL is built from that same
     * reading, so a provider switch landing part-way can't check out against one account and then
     * play the other account's same-numbered channel. Same URL rule as getLiveStreamUrl: an M3U
     * channel's own URL, otherwise one built from the login in the preferred format.
     */
    suspend fun resolve(
        context: Context,
        prefs: PreferencesManager,
        link: LauncherLink.Parsed.Current,
        lookup: suspend (Int) -> ChannelEntity?
    ): Resolved? {
        val channel = lookup(link.streamId) ?: return null
        val creds = prefs.credentials.first()
        val key = withContext(Dispatchers.IO) { LauncherLinkKey.get(context) }
        val ok = LauncherLink.verify(
            key, link, channel.streamId, channel.streamUrl, creds.serverUrl, creds.username
        )
        if (!ok) return null
        val url = channel.streamUrl
            ?: XtreamUrlBuilder(creds.serverUrl, creds.username, creds.password)
                .liveStreamUrl(channel.streamId, prefs.preferredFormat.first())
        return Resolved(channel, url)
    }

    private fun linkFor(key: ByteArray, ch: ChannelEntity, creds: ServerCredentials): String =
        LauncherLink.build(
            ch.streamId,
            LauncherLink.token(
                key,
                LauncherLink.sourceIdentity(creds.serverUrl, creds.username, ch.streamId, ch.streamUrl)
            )
        )
}

/** Random per-install key for [LauncherLink] tokens. Kept in app-private storage only. */
object LauncherLinkKey {
    private const val PREFS = "tv_channel_prefs"
    private const val KEY = "launcher_link_key"
    private const val SIZE = 32

    @Synchronized
    fun get(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { stored ->
            val bytes = try { Base64.decode(stored, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { null }
            if (bytes != null && bytes.size == SIZE) return bytes
        }
        val fresh = ByteArray(SIZE).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()
        return fresh
    }
}

/**
 * The card a tap asked for, saved by [LauncherPlayActivity] as well as sent in the intent — the
 * same belt and braces as ReminderTune, in case Android resumes the home screen without
 * delivering the new intent. [take] reads and clears both in one go, so one tap plays once.
 */
object LauncherPending {
    private const val PREFS = "launcher_pending"
    private const val KEY_LINK = "link"
    private const val KEY_AT = "at"
    private const val MAX_AGE_MS = 2 * 60 * 1000L

    fun mark(context: Context, link: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LINK, link)
            .putLong(KEY_AT, System.currentTimeMillis())
            .commit()
    }

    fun take(context: Context, intent: Intent?): String? {
        val data = intent?.data
        val isCard = data != null && data.scheme == LauncherLink.SCHEME && data.host == LauncherLink.HOST
        val fromIntent = if (isCard) intent?.dataString else null
        if (isCard) intent?.data = null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LINK, null)
        val at = prefs.getLong(KEY_AT, 0L)
        if (saved != null) prefs.edit().remove(KEY_LINK).remove(KEY_AT).commit()
        val savedFresh = saved?.takeIf { at > 0L && System.currentTimeMillis() - at <= MAX_AGE_MS }
        return fromIntent ?: savedFresh
    }
}
