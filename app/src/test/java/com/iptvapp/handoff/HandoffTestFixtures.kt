package com.iptvapp.handoff

/** Shared fakes for the handoff tests. */
object Fx {
    const val PHONE_ID = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
    const val TV_ID = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
    const val OTHER_ID = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"
    const val SECRET = "5f2b8a0c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8"
    const val OTHER_SECRET = "0000000000000000000000000000000000000000000000000000000000000001"

    /** The phone's own provider identity (never sent) and the TV's two providers. */
    const val PROVIDER_A = "http://a.example:8080\nalice"
    const val PROVIDER_B = "http://b.example\nbob"

    fun phonePeer(secret: String = SECRET, map: Map<String, Int> = emptyMap()) =
        Peer(PHONE_ID, "Pixel Phone", "phone", secret, handoff = true, providerMap = map)

    fun tvPeer(secret: String = SECRET) = Peer(TV_ID, "Living Room Shield", "tv", secret, handoff = true)

    fun movie(
        tx: String = "0123456789abcdef0123456789abcdef",
        providerIdentity: String = PROVIDER_A,
        streamId: Int = 900,
        title: String = "Example Movie",
        position: Long = 3_930_000L,
        playing: Boolean = true,
    ) = Descriptor(
        txId = tx, kind = ContentKind.MOVIE, providerTag = HandoffCrypto.providerTag(SECRET, providerIdentity),
        providerLabel = "T-Rex", streamId = streamId, title = title, year = 2020, containerExt = "mkv",
        positionMs = position, durationMs = 7_200_000L, playing = playing, sourceName = "Pixel Phone",
    )

    fun episode(
        tx: String = "fedcba9876543210fedcba9876543210",
        providerIdentity: String = PROVIDER_A,
        seriesId: Int = 77, season: Int = 3, episode: Int = 7, episodeId: String = "5007",
    ) = Descriptor(
        txId = tx, kind = ContentKind.EPISODE, providerTag = HandoffCrypto.providerTag(SECRET, providerIdentity),
        providerLabel = "T-Rex", streamId = -1, title = "The Seventh", seriesId = seriesId, seriesTitle = "Example Show",
        season = season, episode = episode, episodeId = episodeId, containerExt = "mp4", positionMs = 1_305_000L,
        durationMs = 2_700_000L, sourceName = "Living Room Shield",
    )

    fun live(tx: String = "11112222333344445555666677778888", providerIdentity: String = PROVIDER_A, streamId: Int = 45553, title: String = "US| GSN HD") =
        Descriptor(
            txId = tx, kind = ContentKind.LIVE, providerTag = HandoffCrypto.providerTag(SECRET, providerIdentity),
            providerLabel = "T-Rex", streamId = streamId, title = title, positionMs = 999_000L, sourceName = "Pixel Phone",
        )
}

class FakeCatalog : HandoffCatalog {
    val providerList = mutableListOf(LocalProvider(-1, "T-Rex", Fx.PROVIDER_A), LocalProvider(0, "Strong", Fx.PROVIDER_B))
    val movies = mutableListOf<MovieItem>()
    val channels = mutableListOf<ChannelItem>()
    val seriesList = mutableListOf<SeriesItem>()
    val episodesBySeries = mutableMapOf<Pair<Int, Int>, List<EpisodeItem>?>()

    override suspend fun providers() = providerList.toList()
    override suspend fun movie(serverIndex: Int, streamId: Int) = movies.firstOrNull { it.serverIndex == serverIndex && it.streamId == streamId }
    override suspend fun moviesNamed(serverIndex: Int, title: String) =
        movies.filter { it.serverIndex == serverIndex && ContentMatcher.norm(it.title).contains(ContentMatcher.norm(title)) }
    override suspend fun channel(serverIndex: Int, streamId: Int) = channels.firstOrNull { it.serverIndex == serverIndex && it.streamId == streamId }
    override suspend fun channelsNamed(serverIndex: Int, name: String) = channels.filter { it.serverIndex == serverIndex }
    override suspend fun series(serverIndex: Int, seriesId: Int) = seriesList.firstOrNull { it.serverIndex == serverIndex && it.seriesId == seriesId }
    override suspend fun seriesNamed(serverIndex: Int, title: String) =
        seriesList.filter { it.serverIndex == serverIndex && ContentMatcher.norm(it.title) == ContentMatcher.norm(title) }
    override suspend fun episodes(serverIndex: Int, seriesId: Int) = episodesBySeries[serverIndex to seriesId]
}

class FakeUi(var canPresent: Boolean = true) : ReceiverUi {
    val presented = mutableListOf<String>()
    val dismissed = mutableListOf<String>()
    val closed = mutableListOf<String>()
    override fun present(incoming: Incoming): Boolean { presented += incoming.txId; return canPresent }
    override fun dismiss(txId: String) { dismissed += txId }
    override fun closePlayer(txId: String) { closed += txId }
}

class MapStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Reversible "sealing" for tests, so stored values never equal the secret. */
class FakeSealer : SecretSealer {
    override fun seal(plain: String) = "sealed:" + plain.reversed()
    override fun open(sealed: String) = sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.reversed()
}
