package com.iptvapp.handoff

import org.json.JSONObject

/** What is being handed over. Recordings, files and replays are not (they stay on their device). */
enum class ContentKind(val wire: String) {
    MOVIE("movie"), EPISODE("episode"), LIVE("live");

    companion object {
        fun of(wire: String): ContentKind? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * The playback descriptor: enough to find the same thing in the receiver's own catalog and carry on,
 * and nothing that could reach the source's provider. No URL, address, account name, password or
 * token is ever part of it — the provider is named only by [providerTag] (HandoffCrypto.providerTag)
 * and a display nickname.
 */
data class Descriptor(
    val txId: String,
    val kind: ContentKind,
    val providerTag: String,
    val providerLabel: String,
    /** Movie or channel id on the source's provider; -1 for an episode. */
    val streamId: Int,
    /** Movie / channel name, or the episode's own title. */
    val title: String,
    val year: Int? = null,
    val containerExt: String? = null,
    val seriesId: Int? = null,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = true,
    val speed: Float? = null,
    val audioLang: String? = null,
    val subtitleLang: String? = null,
    val subtitlesOn: Boolean? = null,
    val sourceName: String = "",
)

/** Why a descriptor or message was refused. */
class HandoffRejected(val reason: String) : Exception(reason)

object HandoffProtocol {
    const val VERSION = 1
    /** The whole sealed request or reply, on the wire. */
    const val MAX_ENVELOPE_BYTES = 16 * 1024
    /** The decrypted message. */
    const val MAX_MESSAGE_BYTES = 6 * 1024
    /** Sent and received times may differ this much (clocks, network). */
    const val TIME_WINDOW_MS = 2 * 60_000L
    /** An offer waits this long for the person to answer on the receiver. */
    const val APPROVAL_TTL_MS = 90_000L
    /** After accepting, the receiver's player has this long to really start. */
    const val PREPARE_TTL_MS = 45_000L
    /** Longest elapsed time added to a playing source's position. */
    const val MAX_CATCH_UP_MS = 5 * 60_000L
    const val PHONE_PORT = 9482

    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val EPISODE_ID = Regex("[A-Za-z0-9]{1,32}")
    private val EXT = Regex("[a-z0-9]{1,6}")
    private val LANG = Regex("[A-Za-z]{2,3}([-_][A-Za-z0-9]{1,8}){0,2}")
    /** Anything that looks like a link, an address or a path is never accepted in a text field. */
    private val LINKISH = Regex("""(?i)(://|^/|\\|\bhttps?\b|\bwww\.|\b\d{1,3}(\.\d{1,3}){3}\b|\bfile:|\bcontent:|@)""")

    fun isTxId(s: String) = HEX32.matches(s)
    fun isDeviceId(s: String) = HEX32.matches(s)

    // ── Descriptor ──────────────────────────────────────────────────────────

    fun encode(d: Descriptor): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("tx", d.txId)
        put("kind", d.kind.wire)
        put("ptag", d.providerTag)
        put("plabel", d.providerLabel)
        put("sid", d.streamId)
        put("title", d.title)
        d.year?.let { put("year", it) }
        d.containerExt?.let { put("ext", it) }
        d.seriesId?.let { put("series", it) }
        d.seriesTitle?.let { put("stitle", it) }
        d.season?.let { put("season", it) }
        d.episode?.let { put("ep", it) }
        d.episodeId?.let { put("epid", it) }
        put("pos", d.positionMs)
        put("dur", d.durationMs)
        put("playing", d.playing)
        d.speed?.let { put("speed", it.toDouble()) }
        d.audioLang?.let { put("alang", it) }
        d.subtitleLang?.let { put("slang", it) }
        d.subtitlesOn?.let { put("subs", it) }
        put("from", d.sourceName)
    }

    /** The descriptor, if every field is present, typed and within bounds; otherwise [HandoffRejected]. */
    fun decode(o: JSONObject): Descriptor {
        val allowed = setOf(
            "v", "tx", "kind", "ptag", "plabel", "sid", "title", "year", "ext", "series", "stitle", "season", "ep",
            "epid", "pos", "dur", "playing", "speed", "alang", "slang", "subs", "from",
        )
        val keys = o.keys().asSequence().toList()
        keys.firstOrNull { it !in allowed }?.let { throw HandoffRejected("unknown field") }
        if (o.optInt("v", -1) != VERSION) throw HandoffRejected("version")
        val tx = str(o, "tx") ?: throw HandoffRejected("tx")
        if (!isTxId(tx)) throw HandoffRejected("tx")
        val kind = ContentKind.of(str(o, "kind") ?: "") ?: throw HandoffRejected("kind")
        val tag = str(o, "ptag") ?: throw HandoffRejected("provider")
        if (!HEX64.matches(tag)) throw HandoffRejected("provider")
        val title = text(o, "title", 200, required = true)!!
        val streamId = int(o, "sid") ?: throw HandoffRejected("id")
        val d = Descriptor(
            txId = tx,
            kind = kind,
            providerTag = tag,
            providerLabel = text(o, "plabel", 40) ?: "",
            streamId = streamId,
            title = title,
            year = int(o, "year")?.also { if (it !in 1880..2200) throw HandoffRejected("year") },
            containerExt = str(o, "ext")?.also { if (!EXT.matches(it)) throw HandoffRejected("ext") },
            seriesId = int(o, "series"),
            seriesTitle = text(o, "stitle", 200),
            season = int(o, "season")?.also { if (it !in 0..9999) throw HandoffRejected("season") },
            episode = int(o, "ep")?.also { if (it !in 0..99999) throw HandoffRejected("episode") },
            episodeId = str(o, "epid")?.also { if (!EPISODE_ID.matches(it)) throw HandoffRejected("episode id") },
            positionMs = long(o, "pos") ?: 0L,
            durationMs = long(o, "dur") ?: 0L,
            playing = if (o.has("playing")) (o.opt("playing") as? Boolean ?: throw HandoffRejected("playing")) else true,
            speed = if (o.has("speed")) (o.opt("speed") as? Number)?.toFloat() ?: throw HandoffRejected("speed") else null,
            audioLang = str(o, "alang")?.also { if (!LANG.matches(it)) throw HandoffRejected("audio language") },
            subtitleLang = str(o, "slang")?.also { if (!LANG.matches(it)) throw HandoffRejected("subtitle language") },
            subtitlesOn = if (o.has("subs")) (o.opt("subs") as? Boolean ?: throw HandoffRejected("subs")) else null,
            sourceName = text(o, "from", 60) ?: "",
        )
        validate(d)
        return d
    }

    /** Rules that hold whichever way a descriptor was made (also checked before sending). */
    fun validate(d: Descriptor) {
        if (d.positionMs !in 0..(48 * 3_600_000L)) throw HandoffRejected("position")
        if (d.durationMs !in 0..(48 * 3_600_000L)) throw HandoffRejected("duration")
        d.speed?.let { if (it.isNaN() || it < 0.25f || it > 4f) throw HandoffRejected("speed") }
        when (d.kind) {
            ContentKind.MOVIE -> {
                if (d.streamId < 0) throw HandoffRejected("id")
            }
            ContentKind.LIVE -> {
                if (d.streamId < 0) throw HandoffRejected("id")
            }
            ContentKind.EPISODE -> {
                if (d.seriesId == null || d.seriesId < 0) throw HandoffRejected("series")
                if (d.season == null || d.episode == null) throw HandoffRejected("episode number")
                if (d.episodeId == null) throw HandoffRejected("episode id")
            }
        }
    }

    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.opt(key) as? String ?: throw HandoffRejected(key)
    }

    /** Display text: bounded, no control characters, nothing that looks like a link or address. */
    private fun text(o: JSONObject, key: String, max: Int, required: Boolean = false): String? {
        val s = str(o, key) ?: return if (required) throw HandoffRejected(key) else null
        if (s.length > max || (required && s.isBlank())) throw HandoffRejected(key)
        if (s.any { it.isISOControl() }) throw HandoffRejected(key)
        if (looksLikeLink(s)) throw HandoffRejected("link in $key")
        return s
    }

    fun looksLikeLink(s: String): Boolean = LINKISH.containsMatchIn(s)

    /** Display text made safe to send: no control characters, nothing link-like, at most [max] characters. */
    fun safeText(s: String, max: Int): String {
        var t = s.filterNot { it.isISOControl() }
        var guard = 0
        while (looksLikeLink(t) && guard++ < 8) t = LINKISH.replace(t, " ")
        t = t.replace(Regex("""[/\\@]"""), " ").replace(Regex("""\s+"""), " ").trim().take(max)
        return if (looksLikeLink(t)) "" else t
    }

    private fun int(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val n = o.opt(key) as? Number ?: throw HandoffRejected(key)
        val l = n.toLong()
        if (l.toDouble() != n.toDouble() || l !in Int.MIN_VALUE..Int.MAX_VALUE) throw HandoffRejected(key)
        return l.toInt()
    }

    private fun long(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        val n = o.opt(key) as? Number ?: throw HandoffRejected(key)
        if (n.toLong().toDouble() != n.toDouble()) throw HandoffRejected(key)
        return n.toLong()
    }

    // ── Messages (inside the envelope) ──────────────────────────────────────

    /** Message types a receiver understands; anything else is refused. */
    val MESSAGE_TYPES = setOf("hello", "ping", "offer", "status", "complete", "cancel", "forget")

    /** Device info a peer sends about itself (hello). */
    data class Hello(val deviceId: String, val name: String, val type: String, val port: Int)

    fun hello(h: Hello) = JSONObject().put("t", "hello").put("id", h.deviceId).put("name", h.name).put("type", h.type).put("port", h.port)

    fun decodeHello(o: JSONObject): Hello {
        val id = str(o, "id") ?: throw HandoffRejected("id")
        if (!isDeviceId(id)) throw HandoffRejected("id")
        val name = text(o, "name", 60, required = true)!!
        val type = str(o, "type")?.takeIf { it == "tv" || it == "phone" } ?: throw HandoffRejected("type")
        val port = int(o, "port") ?: 0
        if (port != 0 && port !in 1024..65535) throw HandoffRejected("port")
        return Hello(id, name, type, port)
    }

    /** A source's fresh state with each status poll: where it is now and whether it plays. */
    data class SourceState(val positionMs: Long, val playing: Boolean)

    fun decodeSourceState(o: JSONObject): SourceState? {
        if (!o.has("pos")) return null
        val pos = long(o, "pos") ?: return null
        if (pos !in 0..(48 * 3_600_000L)) throw HandoffRejected("position")
        val playing = o.opt("playing") as? Boolean ?: true
        return SourceState(pos, playing)
    }

    fun txOf(o: JSONObject): String {
        val tx = str(o, "tx") ?: throw HandoffRejected("tx")
        if (!isTxId(tx)) throw HandoffRejected("tx")
        return tx
    }
}
