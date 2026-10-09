package com.iptvapp.cast

import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The cast proxy's security rules, kept free of Android so they can be unit-tested (v7.20).
 *
 * The proxy listens on the home network while a cast runs, and anything on that network can reach
 * it. So it serves nothing a requester names: every URL it hands out is
 * `/<session token>/<kind>/<resource id><ext>`, where the token is 256 random bits made for this
 * cast and each resource id is a random handle MKTV registered for something it chose to cast — a
 * stream URL, a child playlist/segment of an approved playlist, a recording, or a live
 * repackaging session. A request without the current token, or for an id that isn't registered,
 * gets an error and touches no file and no upstream. Paths are never URL-decoded or treated as
 * file paths; [close] ends the session and every token and id with it.
 *
 * Defense in depth, applied when MKTV registers something and again when it is used:
 *  - files: MediaStore `content://media/…` items, or files whose canonical path is inside the
 *    allowed roots (recording folders) and not inside the app's private storage;
 *  - upstream: http/https only, and connections to loopback, link-local (cloud metadata),
 *    any-local or multicast addresses are refused; private-network addresses only for hosts
 *    MKTV itself registered as a cast source (a LAN IPTV server), never for hosts a playlist or
 *    a redirect names ([isBlockedAddress]).
 */
class CastProxyGuard(
    private val allowedFileRoots: List<File>,
    private val forbiddenFileRoots: List<File>,
    private val random: SecureRandom = SecureRandom()
) {
    sealed class Route {
        data class Rejected(val status: String) : Route()
        object Options : Route()
        data class Upstream(val url: String) : Route()
        data class LocalFile(val path: String) : Route()
        data class Live(val id: String) : Route()
        data class Segment(val id: String, val sequence: Long) : Route()
    }

    @Volatile private var token: String? = newHandle(32)
    private val lock = Any()

    // Registered upstream URLs by id (and the reverse, so a playlist re-fetched every few seconds
    // reuses its children's ids). The URLs MKTV chose to cast are kept for the whole session
    // ([sourceById]); children are bounded, least recently registered or used going first, since a
    // long live cast keeps registering new segments — the bound is far above any one playlist
    // (a 24-hour VOD in 2-second segments), so a playlist never outruns its own handles.
    private val sourceById = HashMap<String, String>()
    private val upstreamById = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            val drop = size > MAX_UPSTREAM_RESOURCES
            if (drop) idByUpstream.remove(eldest.value)
            return drop
        }
    }
    private val idByUpstream = HashMap<String, String>()
    private val files = HashMap<String, String>()
    private val liveIds = HashSet<String>()
    private val privateAllowedHosts = HashSet<String>()
    // The cast source's own private-network addresses: its IP literal, or what its name resolved to.
    // The only private addresses a connection may reach (see isBlockedConnection).
    private val privateAllowedAddresses = HashSet<InetAddress>()

    val isOpen: Boolean get() = token != null

    /** The path prefix every URL of this session starts with. */
    fun prefix(): String = "/" + (token ?: throw IllegalStateException("cast proxy session closed"))

    /** Ends the session: the token and every registered resource stop working at once. */
    fun close() = synchronized(lock) {
        token = null
        sourceById.clear()
        upstreamById.clear()
        idByUpstream.clear()
        files.clear()
        liveIds.clear()
        privateAllowedHosts.clear()
        privateAllowedAddresses.clear()
    }

    /** Registers [url] for this session and returns its path, or null when it isn't an http(s)
     * URL. [castSource] marks the URL MKTV chose to cast (it may be on the local network, e.g. a
     * LAN IPTV server); children found in its playlists are registered without it. */
    fun registerUpstream(url: String, castSource: Boolean): String? = synchronized(lock) {
        if (!isOpen) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return null
        val sourceId = sourceById.entries.firstOrNull { it.value == url }?.key
        val id = if (castSource) {
            privateAllowedHosts += host
            literalAddress(host)?.let { privateAllowedAddresses += it }
            sourceId ?: newHandle(16).also { sourceById[it] = url }
        } else if (sourceId != null) {
            sourceId
        } else {
            // A known child is touched (get) so a playlist re-fetch keeps the segments it lists.
            idByUpstream[url]?.also { upstreamById[it] }
                ?: newHandle(16).also { upstreamById[it] = url; idByUpstream[url] = it }
        }
        "${prefix()}/r/$id${extensionOf(uri.path)}"
    }

    /** Marks [url]'s host as one MKTV chose to cast from, without exposing the URL itself (the live
     * repackager fetches it directly). False when it isn't an http(s) URL. */
    fun allowCastSource(url: String): Boolean = synchronized(lock) {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase()
        if (!isOpen || (scheme != "http" && scheme != "https")) return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        privateAllowedHosts += host
        literalAddress(host)?.let { privateAllowedAddresses += it }
        true
    }

    /** Registers the recording at [path] (a MediaStore content:// URI, or a file path) for this
     * session and returns its path, or null when it isn't an allowed recording location. */
    fun registerFile(path: String): String? = synchronized(lock) {
        if (!isOpen || !isAllowedFile(path)) return null
        val id = newHandle(16)
        files[id] = path
        "${prefix()}/f/$id${extensionOf(path.substringBefore('?'))}"
    }

    /** Registers a live repackaging session and returns its id and manifest path. */
    fun registerLive(): Pair<String, String>? = synchronized(lock) {
        if (!isOpen) return null
        val id = newHandle(16)
        liveIds += id
        id to "${prefix()}/live/$id.m3u8"
    }

    fun segmentPath(liveId: String, sequence: Long): String = "${prefix()}/seg/$liveId/$sequence.ts"

    /** The live session id in a [registerLive] path, or null. */
    fun liveIdOf(path: String): String? = Regex("/live/([0-9a-f]+)\\.m3u8").find(path)?.groupValues?.get(1)

    /** Decides what a request may have. [rawPath] is the request target exactly as received. */
    fun route(method: String, rawPath: String): Route {
        if (method != "GET" && method != "HEAD" && method != "OPTIONS") return Route.Rejected("405 Method Not Allowed")
        val current = token ?: return Route.Rejected("410 Gone")
        // Only the path matters; ids and the token are plain hex, so nothing is ever decoded.
        val parts = rawPath.substringBefore('?').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || !MessageDigest.isEqual(parts[0].toByteArray(), current.toByteArray())) {
            return Route.Rejected("403 Forbidden")
        }
        if (method == "OPTIONS") return Route.Options
        fun id(s: String) = s.substringBefore('.').takeIf { it.matches(HANDLE) }
        return synchronized(lock) {
            when {
                parts.size == 3 && parts[1] == "r" ->
                    id(parts[2])?.let { sourceById[it] ?: upstreamById[it] }?.let { Route.Upstream(it) }
                parts.size == 3 && parts[1] == "f" ->
                    id(parts[2])?.let { files[it] }?.takeIf { isAllowedFile(it) }?.let { Route.LocalFile(it) }
                parts.size == 3 && parts[1] == "live" && parts[2].endsWith(".m3u8") ->
                    id(parts[2])?.takeIf { it in liveIds }?.let { Route.Live(it) }
                parts.size == 4 && parts[1] == "seg" && parts[2] in liveIds ->
                    parts[3].removeSuffix(".ts").toLongOrNull()?.takeIf { it >= 0 }?.let { Route.Segment(parts[2], it) }
                else -> null
            } ?: Route.Rejected("404 Not Found")
        }
    }

    /** Recordings only: MediaStore items, or files inside an allowed root and outside every
     * forbidden one, judged by canonical path so `..` and symlinks can't step out. */
    fun isAllowedFile(path: String): Boolean {
        if (path.startsWith("content://")) {
            val authority = path.removePrefix("content://").substringBefore('/')
            return authority == "media"
        }
        val raw = path.removePrefix("file://")
        if (!File(raw).isAbsolute) return false
        val canonical = try { File(raw).canonicalFile } catch (_: Exception) { return false }
        fun inside(root: File): Boolean {
            val r = try { root.canonicalFile } catch (_: Exception) { return false }
            return canonical.path == r.path || canonical.path.startsWith(r.path + File.separator)
        }
        if (forbiddenFileRoots.any { inside(it) }) return false
        return allowedFileRoots.any { inside(it) } && canonical.isFile
    }

    /** Whether a connection to [address] (for [host]) must be refused. Loopback, link-local
     * (169.254.x.x — cloud metadata — and fe80::), any-local and multicast never; private and
     * carrier-grade NAT ranges only for hosts MKTV registered as the cast source. */
    fun isBlockedAddress(address: InetAddress, host: String): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return true
        if (isPrivate(address)) return synchronized(lock) { host.lowercase().trim('[', ']') !in privateAllowedHosts }
        return false
    }

    /** Called with what a host name resolved to: a cast source's private addresses become reachable. */
    fun noteResolved(host: String, addresses: List<InetAddress>) = synchronized(lock) {
        if (host.lowercase().trim('[', ']') in privateAllowedHosts) privateAllowedAddresses += addresses.filter { isPrivate(it) }
    }

    /** The check made on every socket before it connects — every hop, redirects and IP-literal hosts
     * included, where no host name is at hand: never this device, link-local, any-local or
     * multicast, and a private address only if it is the cast source's own. */
    fun isBlockedConnection(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return true
        if (isPrivate(address)) return synchronized(lock) { address !in privateAllowedAddresses }
        return false
    }

    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isSiteLocalAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 // 100.64.0.0/10
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc // fc00::/7 unique local
            else -> false
        }
    }

    // An IP-literal host as an address, parsed without any lookup; null for names.
    private fun literalAddress(host: String): InetAddress? =
        if (host.contains(':') || host.matches(IPV4_LITERAL)) try { InetAddress.getByName(host) } catch (_: Exception) { null } else null

    private fun newHandle(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_UPSTREAM_RESOURCES = 50_000
        private val HANDLE = Regex("[0-9a-f]{32}")
        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val KNOWN_EXTENSIONS = setOf("m3u8", "m3u", "mpd", "ts", "mp4", "m4v", "m4s", "mkv", "mov", "webm", "avi", "aac", "vtt")

        /** ".m3u8", ".mp4", … from a path, so receivers and content-type guesses still see the
         * media type; empty for anything else. Never carries any other part of the URL. */
        fun extensionOf(path: String?): String {
            val ext = path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return if (ext in KNOWN_EXTENSIONS) ".$ext" else ""
        }
    }
}
