package com.iptvapp.handoff

import java.text.Normalizer
import java.util.Locale

/** One of this device's providers. [identity] (address + account) never leaves the device. */
data class LocalProvider(val serverIndex: Int, val label: String, val identity: String)

data class MovieItem(val serverIndex: Int, val streamId: Int, val title: String, val year: Int?, val ext: String)
data class ChannelItem(val serverIndex: Int, val streamId: Int, val name: String)
data class SeriesItem(val serverIndex: Int, val seriesId: Int, val title: String)
data class EpisodeItem(val id: String, val season: Int, val episode: Int, val title: String, val ext: String)

/** This device's catalog, as handoff sees it (Room + the provider API in the app; fakes in tests). */
interface HandoffCatalog {
    suspend fun providers(): List<LocalProvider>
    suspend fun movie(serverIndex: Int, streamId: Int): MovieItem?
    suspend fun moviesNamed(serverIndex: Int, title: String): List<MovieItem>
    suspend fun channel(serverIndex: Int, streamId: Int): ChannelItem?
    suspend fun channelsNamed(serverIndex: Int, name: String): List<ChannelItem>
    suspend fun series(serverIndex: Int, seriesId: Int): SeriesItem?
    suspend fun seriesNamed(serverIndex: Int, title: String): List<SeriesItem>
    /** A series' episodes; null when they couldn't be loaded (provider unreachable). */
    suspend fun episodes(serverIndex: Int, seriesId: Int): List<EpisodeItem>?
}

/** Exactly what the receiver will open. */
data class Target(
    val kind: ContentKind,
    val serverIndex: Int,
    val title: String,
    val streamId: Int = -1,
    val ext: String = "",
    val seriesId: Int = -1,
    val seriesTitle: String = "",
    val episode: EpisodeItem? = null,
    /** The episode's season, for Up Next. */
    val seasonEpisodes: List<EpisodeItem> = emptyList(),
) {
    /** What the approval prompt shows for this choice. */
    val display: String get() = when (kind) {
        ContentKind.EPISODE -> "$seriesTitle · S${episode?.season}E${episode?.episode} ${episode?.title.orEmpty()}".trim()
        else -> title
    }
}

sealed class Resolution {
    data class Playable(val target: Target) : Resolution()
    /** Several plausible matches: the person picks one; nothing plays by guess. */
    data class Choose(val options: List<Target>) : Resolution()
    /** The sender's provider isn't one of ours and hasn't been mapped to one. */
    data class ProviderMissing(val senderLabel: String, val local: List<LocalProvider>) : Resolution()
    data class NotFound(val message: String) : Resolution()
    /** The catalog couldn't be checked right now (provider unreachable). */
    data class Unavailable(val message: String) : Resolution()
}

/**
 * Finds a descriptor's content in this device's catalog.
 *
 * - Same provider (the provider tag matches one of ours): ids are authoritative, and an episode is
 *   also checked against its series' episode list (its id must be there, with the same season and
 *   number), so a reused id never plays the wrong thing.
 * - A provider the person mapped by hand: an id counts only if its title agrees too; otherwise the
 *   title is searched. One match plays; several are offered to choose from; none is "not found".
 * - Neither: [Resolution.ProviderMissing], so the person can map it (never assumed).
 */
class ContentMatcher(private val catalog: HandoffCatalog) {

    suspend fun resolve(d: Descriptor, peerSecret: String, providerMap: Map<String, Int>): Resolution {
        val providers = catalog.providers()
        if (providers.isEmpty()) return Resolution.NotFound("No provider is set up on this device")
        val exact = providers
            .filter { HandoffCrypto.constantTimeEquals(HandoffCrypto.providerTag(peerSecret, it.identity), d.providerTag) }
            .sortedBy { if (it.serverIndex == -1) Int.MIN_VALUE else it.serverIndex }
            .firstOrNull()
        if (exact != null) return byId(d, exact.serverIndex)
        val mapped = providerMap[d.providerTag]?.let { slot -> providers.firstOrNull { it.serverIndex == slot } }
            ?: return Resolution.ProviderMissing(d.providerLabel, providers)
        return byTitle(d, mapped.serverIndex)
    }

    private fun what(d: Descriptor) = when (d.kind) {
        ContentKind.MOVIE -> "movie"
        ContentKind.EPISODE -> "episode"
        ContentKind.LIVE -> "channel"
    }

    private suspend fun byId(d: Descriptor, server: Int): Resolution = when (d.kind) {
        ContentKind.MOVIE -> catalog.movie(server, d.streamId)?.let { Resolution.Playable(movieTarget(it)) }
            ?: Resolution.NotFound("This movie isn't in this device's catalog")
        ContentKind.LIVE -> catalog.channel(server, d.streamId)?.let { Resolution.Playable(channelTarget(it)) }
            ?: Resolution.NotFound("This channel isn't in this device's channel list")
        ContentKind.EPISODE -> {
            val seriesId = d.seriesId!!
            val series = catalog.series(server, seriesId)
            val episodes = catalog.episodes(server, seriesId)
            when {
                episodes == null -> Resolution.Unavailable("Couldn't load this series from the provider")
                else -> {
                    val ep = episodes.firstOrNull { it.id == d.episodeId }
                    if (ep == null || ep.season != d.season || ep.episode != d.episode) {
                        Resolution.NotFound("This episode isn't in this device's catalog")
                    } else {
                        Resolution.Playable(episodeTarget(server, seriesId, series?.title ?: d.seriesTitle.orEmpty(), ep, episodes))
                    }
                }
            }
        }
    }

    private suspend fun byTitle(d: Descriptor, server: Int): Resolution {
        when (d.kind) {
            ContentKind.MOVIE -> {
                val want = norm(d.title)
                catalog.movie(server, d.streamId)?.takeIf { norm(it.title) == want && yearsAgree(it.year, d.year) }
                    ?.let { return Resolution.Playable(movieTarget(it)) }
                val found = catalog.moviesNamed(server, d.title).filter { norm(it.title) == want && yearsAgree(it.year, d.year) }
                    .distinctBy { it.streamId }
                return pick(found.map { movieTarget(it) }, d)
            }
            ContentKind.LIVE -> {
                val want = channelNorm(d.title)
                catalog.channel(server, d.streamId)?.takeIf { channelNorm(it.name) == want }
                    ?.let { return Resolution.Playable(channelTarget(it)) }
                val found = catalog.channelsNamed(server, d.title).filter { channelNorm(it.name) == want }.distinctBy { it.streamId }
                return pick(found.map { channelTarget(it) }, d)
            }
            ContentKind.EPISODE -> {
                val want = norm(d.seriesTitle.orEmpty())
                if (want.isEmpty()) return Resolution.NotFound("This episode's series has no name to look for")
                val sameId = catalog.series(server, d.seriesId!!)?.takeIf { norm(it.title) == want }
                val candidates = (listOfNotNull(sameId) + catalog.seriesNamed(server, d.seriesTitle.orEmpty())
                    .filter { norm(it.title) == want }).distinctBy { it.seriesId }.take(MAX_OPTIONS)
                val targets = mutableListOf<Target>()
                var unreachable = false
                for (s in candidates) {
                    val episodes = catalog.episodes(server, s.seriesId)
                    if (episodes == null) { unreachable = true; continue }
                    val ep = episodes.firstOrNull { it.season == d.season && it.episode == d.episode } ?: continue
                    targets.add(episodeTarget(server, s.seriesId, s.title, ep, episodes))
                }
                if (targets.isEmpty() && unreachable) return Resolution.Unavailable("Couldn't load this series from the provider")
                return pick(targets, d)
            }
        }
    }

    private fun pick(found: List<Target>, d: Descriptor): Resolution = when (found.size) {
        0 -> Resolution.NotFound("This ${what(d)} isn't available on this device's providers")
        1 -> Resolution.Playable(found.single())
        else -> Resolution.Choose(found.take(MAX_OPTIONS))
    }

    private fun yearsAgree(a: Int?, b: Int?) = a == null || b == null || a == b

    private fun movieTarget(m: MovieItem) =
        Target(ContentKind.MOVIE, m.serverIndex, m.title, streamId = m.streamId, ext = m.ext)

    private fun channelTarget(c: ChannelItem) = Target(ContentKind.LIVE, c.serverIndex, c.name, streamId = c.streamId)

    private fun episodeTarget(server: Int, seriesId: Int, seriesTitle: String, ep: EpisodeItem, all: List<EpisodeItem>) = Target(
        ContentKind.EPISODE, server, ep.title, ext = ep.ext, seriesId = seriesId, seriesTitle = seriesTitle, episode = ep,
        seasonEpisodes = all.filter { it.season == ep.season }.sortedBy { it.episode },
    )

    companion object {
        const val MAX_OPTIONS = 8

        /** Titles compared without case, accents, punctuation or extra spaces. */
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        /**
         * Channel names across providers: also without a leading country/group prefix ("US| ", "UK: ",
         * "EN - ") and quality marks (HD, FHD, UHD, 4K, RAW, 60fps). East/West, +1 and numbers stay,
         * since they are different channels.
         */
        fun channelNorm(s: String): String {
            var t = norm(s)
            t = t.replace(Regex("^[a-z]{2,3} (?=\\S)"), "").takeIf { it.isNotBlank() && s.trimStart().matches(Regex("(?i)^[a-z]{2,3}\\s*[|:\\-].*")) } ?: t
            t = t.split(' ').filterNot { it in QUALITY }.joinToString(" ")
            return t.trim()
        }

        private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "raw", "60fps", "50fps", "hevc", "h265")
    }
}
