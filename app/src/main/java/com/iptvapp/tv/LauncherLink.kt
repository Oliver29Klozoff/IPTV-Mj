package com.iptvapp.tv

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The link behind each MKTV card on the Android TV home screen.
 *
 * Format: `mktv://play/v2/<streamId>/<token>`
 *  - streamId: the primary provider's channel id (ChannelEntity.streamId); may be negative.
 *  - token: HMAC-SHA256, under a random key that never leaves this install, of the channel's
 *    source identity (see [sourceIdentity]), cut to 128 bits.
 *
 * The token ties a card to the provider account it was published for, so a card made for one
 * provider can never play a same-numbered channel on another, and the link itself carries no
 * server address, username, password or stream URL. Any app can send MKTV an mktv:// link; one
 * without a token made with this install's key doesn't resolve.
 *
 * v7.23 and older published `mktv://play/<streamId>`: parsed as [Parsed.Legacy] and never played,
 * since nothing in it says which provider it was for.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
object LauncherLink {
    const val SCHEME = "mktv"
    const val HOST = "play"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    private const val VERSION = "v2"
    private const val TOKEN_BYTES = 16
    private const val MAX_LENGTH = 96

    private val CURRENT = Regex("^mktv://play/v2/(-?\\d{1,10})/([0-9a-f]{32})$")
    private val LEGACY = Regex("^mktv://play/(-?\\d{1,10})$")
    // Android TV Watch Next cards: a movie, or one episode of a series, to resume.
    private val MOVIE = Regex("^mktv://play/v2/m/(-?\\d{1,10})/([0-9a-f]{32})$")
    private val EPISODE = Regex("^mktv://play/v2/e/(-?\\d{1,10})/(\\d{1,4})/(\\d{1,5})/([0-9a-f]{32})$")
    private val TOKEN = Regex("^[0-9a-f]{32}$")

    sealed class Parsed {
        data class Recording(val id: Int, val token: String) : Parsed()
        data class Current(val streamId: Int, val token: String) : Parsed()
        data class Legacy(val streamId: Int) : Parsed()
        /** A movie on the main provider ([vodId] is its stream id), from Watch Next. */
        data class Movie(val vodId: Int, val token: String) : Parsed()
        /** One episode of a main-provider series, from Watch Next. */
        data class Episode(val seriesId: Int, val season: Int, val episode: Int, val token: String) : Parsed()
        object Invalid : Parsed()
    }

    /**
     * Watch Next links. Like a channel card, the token binds the title to the provider account
     * it was published for, and the link carries no server, login or stream URL.
     */
    fun buildMovie(vodId: Int, token: String): String {
        require(TOKEN.matches(token)) { "malformed token" }
        return "$SCHEME://$HOST/$VERSION/m/$vodId/$token"
    }

    fun buildEpisode(seriesId: Int, season: Int, episode: Int, token: String): String {
        require(TOKEN.matches(token)) { "malformed token" }
        require(season in 0..9999 && episode in 0..99999) { "episode out of range" }
        return "$SCHEME://$HOST/$VERSION/e/$seriesId/$season/$episode/$token"
    }

    fun movieIdentity(serverUrl: String, username: String, vodId: Int): String =
        "movie\n${normalizeServer(serverUrl)}\n$username\n$vodId"

    fun recordingIdentity(id: Int, path: String, started: Long): String = "recording\n$id\n$path\n$started"
    fun buildRecording(id: Int, token: String): String {
        require(id > 0 && TOKEN.matches(token))
        return "$SCHEME://$HOST/$VERSION/r/$id/$token"
    }

    fun episodeIdentity(serverUrl: String, username: String, seriesId: Int, season: Int, episode: Int): String =
        "episode\n${normalizeServer(serverUrl)}\n$username\n$seriesId\n$season\n$episode"

    /** True when [token] was made with this install's key for [identity]. */
    fun verifyIdentity(key: ByteArray, identity: String, token: String): Boolean =
        MessageDigest.isEqual(
            token(key, identity).toByteArray(Charsets.US_ASCII),
            token.toByteArray(Charsets.US_ASCII)
        )

    // Any Int: M3U imports can produce negative ids (their id hash overflows), and those channels
    // can be favorites too.
    fun build(streamId: Int, token: String): String {
        require(TOKEN.matches(token)) { "malformed token" }
        return "$SCHEME://$HOST/$VERSION/$streamId/$token"
    }

    /** Strict: the whole string must be exactly one of the two shapes. Anything else — a query,
     * a fragment, a URL, an out-of-range id, another action — is [Parsed.Invalid]. */
    fun parse(action: String?, data: String?): Parsed {
        if (action != ACTION_VIEW) return Parsed.Invalid
        if (data == null || data.length > MAX_LENGTH) return Parsed.Invalid
        Regex("^mktv://play/v2/r/([1-9][0-9]{0,9})/([0-9a-f]{32})$").matchEntire(data)?.let {
            return Parsed.Recording(it.groupValues[1].toIntOrNull() ?: return Parsed.Invalid, it.groupValues[2])
        }
        CURRENT.matchEntire(data)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return Parsed.Invalid
            return Parsed.Current(id, m.groupValues[2])
        }
        MOVIE.matchEntire(data)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return Parsed.Invalid
            return Parsed.Movie(id, m.groupValues[2])
        }
        EPISODE.matchEntire(data)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return Parsed.Invalid
            val season = m.groupValues[2].toIntOrNull() ?: return Parsed.Invalid
            val episode = m.groupValues[3].toIntOrNull() ?: return Parsed.Invalid
            return Parsed.Episode(id, season, episode, m.groupValues[4])
        }
        LEGACY.matchEntire(data)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return Parsed.Invalid
            return Parsed.Legacy(id)
        }
        return Parsed.Invalid
    }

    /**
     * What a card is bound to.
     *  - M3U-imported channel (its own stored URL): that URL. Re-importing the same playlist keeps
     *    cards working; a different playlist that reuses the id doesn't match.
     *  - Xtream channel (no stored URL): server + username + stream id. The password is left out
     *    on purpose: a changed password is still the same account, and playback builds the URL
     *    from whatever password is saved now. A different server or username is another account.
     */
    fun sourceIdentity(serverUrl: String, username: String, streamId: Int, directUrl: String?): String =
        if (!directUrl.isNullOrEmpty()) "direct\n$directUrl"
        else "xtream\n${normalizeServer(serverUrl)}\n$username\n$streamId"

    // Scheme and host are case-insensitive; a path is not — two providers can share a host under
    // paths that differ only in case (XtreamUrlBuilder keeps that case), so the path is kept as is.
    fun normalizeServer(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val m = SCHEME_AND_HOST.find(trimmed) ?: return trimmed
        return m.groupValues[1].lowercase(Locale.ROOT) + m.groupValues[2].lowercase(Locale.ROOT) + trimmed.substring(m.range.last + 1)
    }

    private val SCHEME_AND_HOST = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#]*)")

    fun token(key: ByteArray, identity: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(identity.toByteArray(Charsets.UTF_8))
        return digest.copyOf(TOKEN_BYTES).joinToString("") { b ->
            val v = b.toInt() and 0xff
            "${HEX[v ushr 4]}${HEX[v and 0x0f]}"
        }
    }

    /**
     * True only when [channelStreamId] is the channel the link names and the token was made for
     * that channel on the provider account that is signed in now ([serverUrl] / [username]).
     * [channelStreamId] null = no such channel any more.
     */
    fun verify(
        key: ByteArray,
        link: Parsed.Current,
        channelStreamId: Int?,
        channelDirectUrl: String?,
        serverUrl: String,
        username: String
    ): Boolean {
        if (channelStreamId == null || channelStreamId != link.streamId) return false
        val expected = token(key, sourceIdentity(serverUrl, username, link.streamId, channelDirectUrl))
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            link.token.toByteArray(Charsets.US_ASCII)
        )
    }

    private const val HEX = "0123456789abcdef"
}
