package com.iptvapp.tv

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The link behind each MKTV card on the Android TV home screen.
 *
 * Format: `mktv://play/v2/<streamId>/<token>`
 *  - streamId: the primary provider's channel id (ChannelEntity.streamId).
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

    private val CURRENT = Regex("^mktv://play/v2/(\\d{1,10})/([0-9a-f]{32})$")
    private val LEGACY = Regex("^mktv://play/(\\d{1,10})$")
    private val TOKEN = Regex("^[0-9a-f]{32}$")

    sealed class Parsed {
        data class Current(val streamId: Int, val token: String) : Parsed()
        data class Legacy(val streamId: Int) : Parsed()
        object Invalid : Parsed()
    }

    fun build(streamId: Int, token: String): String {
        require(streamId >= 0) { "streamId must be >= 0" }
        require(TOKEN.matches(token)) { "malformed token" }
        return "$SCHEME://$HOST/$VERSION/$streamId/$token"
    }

    /** Strict: the whole string must be exactly one of the two shapes. Anything else — a query,
     * a fragment, a URL, an out-of-range id, another action — is [Parsed.Invalid]. */
    fun parse(action: String?, data: String?): Parsed {
        if (action != ACTION_VIEW) return Parsed.Invalid
        if (data == null || data.length > MAX_LENGTH) return Parsed.Invalid
        CURRENT.matchEntire(data)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return Parsed.Invalid
            return Parsed.Current(id, m.groupValues[2])
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

    fun normalizeServer(url: String): String = url.trim().trimEnd('/').lowercase(Locale.ROOT)

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
