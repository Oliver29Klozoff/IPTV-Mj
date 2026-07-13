package com.iptvapp.trakt

import android.util.Log
import com.iptvapp.BuildConfig
import com.iptvapp.data.api.TraktApiService
import com.iptvapp.data.api.TraktDeviceCodeRequest
import com.iptvapp.data.api.TraktDeviceCodeResponse
import com.iptvapp.data.api.TraktEpisode
import com.iptvapp.data.api.TraktMovie
import com.iptvapp.data.api.TraktProxyApiService
import com.iptvapp.data.api.TraktProxyCodeRequest
import com.iptvapp.data.api.TraktProxyRefreshRequest
import com.iptvapp.data.api.TraktScrobbleRequest
import com.iptvapp.data.api.TraktShow
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.EpisodeWatchedEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed class TraktDeviceAuthResult {
    data class Pending(val userCode: String, val verificationUrl: String) : TraktDeviceAuthResult()
    object Success : TraktDeviceAuthResult()
    object Expired : TraktDeviceAuthResult()
    object Denied : TraktDeviceAuthResult()
    data class Error(val message: String) : TraktDeviceAuthResult()
}

/** Best-effort parse of a VOD/live title like "Movie Name (2019)" into title+year for Trakt matching. */
data class ParsedTitle(val title: String, val year: Int?)

@Singleton
class TraktManager @Inject constructor(
    private val api: TraktApiService,
    private val proxyApi: TraktProxyApiService,
    private val prefs: PreferencesManager,
    private val db: IptvDatabase
) {
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val proxyUrl = BuildConfig.TRAKT_PROXY_URL

    // client_secret is never embedded in the app — only client_id (not sensitive) plus the
    // proxy URL. The proxy (see cloudflare/trakt-proxy-worker.js) holds the secret and does
    // the token exchange/refresh on the app's behalf.
    val isConfigured: Boolean get() = clientId.isNotBlank() && proxyUrl.isNotBlank()
    val isConnected: Flow<Boolean> get() = prefs.traktConnected

    fun parseTitle(raw: String): ParsedTitle {
        val m = Regex("""^(.*?)\s*\((\d{4})\)\s*$""").find(raw.trim())
        return if (m != null) ParsedTitle(m.groupValues[1].trim(), m.groupValues[2].toIntOrNull())
        else ParsedTitle(raw.trim(), null)
    }

    /** Parses "S1E2 Episode Title" (as built by SeriesDetailActivity) into season/episode numbers. */
    fun parseSeasonEpisode(streamTitle: String): Pair<Int, Int>? {
        val m = Regex("""^S(\d+)E(\d+)""").find(streamTitle.trim())
        return m?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    /** Starts the device-code login flow, emitting Pending (with the code to show the user) then
     * polling until Success/Expired/Denied. Collect this flow from a coroutine tied to the dialog's lifecycle. */
    suspend fun startDeviceAuth(onUpdate: suspend (TraktDeviceAuthResult) -> Unit) {
        if (!isConfigured) {
            onUpdate(TraktDeviceAuthResult.Error("Trakt is not configured for this build"))
            return
        }
        val codeResp = try {
            api.getDeviceCode(TraktDeviceCodeRequest(clientId))
        } catch (e: Exception) {
            onUpdate(TraktDeviceAuthResult.Error(e.message ?: "Network error"))
            return
        }
        val code: TraktDeviceCodeResponse = codeResp.body() ?: run {
            onUpdate(TraktDeviceAuthResult.Error("Trakt returned no device code"))
            return
        }
        onUpdate(TraktDeviceAuthResult.Pending(code.userCode, code.verificationUrl))

        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.interval * 1000L)
            try {
                val tokenResp = proxyApi.getDeviceToken(TraktProxyCodeRequest(code.deviceCode))
                when (tokenResp.code()) {
                    200 -> {
                        val token = tokenResp.body() ?: continue
                        prefs.saveTraktTokens(
                            token.accessToken, token.refreshToken,
                            System.currentTimeMillis() + token.expiresIn * 1000L
                        )
                        onUpdate(TraktDeviceAuthResult.Success)
                        return
                    }
                    400 -> continue // authorization pending — keep polling
                    404 -> { onUpdate(TraktDeviceAuthResult.Error("Invalid device code")); return }
                    409 -> { onUpdate(TraktDeviceAuthResult.Denied); return }
                    410 -> { onUpdate(TraktDeviceAuthResult.Expired); return }
                    418 -> { onUpdate(TraktDeviceAuthResult.Denied); return }
                    else -> continue
                }
            } catch (e: Exception) {
                Log.e("TraktManager", "Poll error: ${e.message}")
            }
        }
        onUpdate(TraktDeviceAuthResult.Expired)
    }

    suspend fun disconnect() = prefs.clearTraktTokens()

    /** Returns a valid access token, refreshing it first if it's expired. Null if not connected/configured. */
    private suspend fun validAccessToken(): String? {
        if (!isConfigured) return null
        val token = prefs.traktAccessTokenBlocking() ?: return null
        val expiresAt = prefs.getTraktTokenExpiresAt()
        if (System.currentTimeMillis() < expiresAt - 60_000L) return token

        val refresh = prefs.getTraktRefreshToken() ?: return null
        return try {
            val resp = proxyApi.refreshToken(TraktProxyRefreshRequest(refresh))
            val body = resp.body() ?: return null
            prefs.saveTraktTokens(body.accessToken, body.refreshToken, System.currentTimeMillis() + body.expiresIn * 1000L)
            body.accessToken
        } catch (e: Exception) {
            Log.e("TraktManager", "Refresh failed: ${e.message}")
            null
        }
    }

    private suspend fun scrobble(
        progress: Float,
        call: suspend (auth: String, body: TraktScrobbleRequest) -> Unit,
        movie: TraktMovie? = null,
        show: TraktShow? = null,
        episode: TraktEpisode? = null
    ) {
        val token = validAccessToken() ?: return
        try {
            call("Bearer $token", TraktScrobbleRequest(movie = movie, show = show, episode = episode, progress = progress))
        } catch (e: Exception) {
            Log.e("TraktManager", "Scrobble call failed: ${e.message}")
        }
    }

    suspend fun scrobbleMovieStart(title: String, year: Int?, progress: Float) =
        scrobble(progress, { auth, body -> api.scrobbleStart(auth, clientId, body = body) }, movie = TraktMovie(title, year))

    suspend fun scrobbleMoviePause(title: String, year: Int?, progress: Float) =
        scrobble(progress, { auth, body -> api.scrobblePause(auth, clientId, body = body) }, movie = TraktMovie(title, year))

    suspend fun scrobbleMovieStop(title: String, year: Int?, progress: Float) =
        scrobble(progress, { auth, body -> api.scrobbleStop(auth, clientId, body = body) }, movie = TraktMovie(title, year))

    suspend fun scrobbleEpisodeStart(showTitle: String, season: Int, episode: Int, progress: Float) =
        scrobble(progress, { auth, body -> api.scrobbleStart(auth, clientId, body = body) },
            show = TraktShow(showTitle), episode = TraktEpisode(season, episode))

    suspend fun scrobbleEpisodePause(showTitle: String, season: Int, episode: Int, progress: Float) =
        scrobble(progress, { auth, body -> api.scrobblePause(auth, clientId, body = body) },
            show = TraktShow(showTitle), episode = TraktEpisode(season, episode))

    suspend fun scrobbleEpisodeStop(showTitle: String, season: Int, episode: Int, progress: Float) =
        scrobble(progress, { auth, body -> api.scrobbleStop(auth, clientId, body = body) },
            show = TraktShow(showTitle), episode = TraktEpisode(season, episode))

    data class SyncBackResult(
        val moviesMatched: Int,
        val showsMatched: Int,
        val episodesMarked: Int,
        val unmatchedMovies: List<String> = emptyList(),
        val unmatchedShows: List<String> = emptyList()
    )

    /** One-time (re-runnable) pull of Trakt's watched history into local state — the reverse
     * of scrobbling. Matches by parsed title (+year for movies, since two different local VOD
     * entries can share a name) against Trakt's title, since neither side has a shared ID to
     * join on (the local catalog has no TMDB/IMDB ids). Movies get marked watched via the
     * existing watchedMs/durationMs fields; episodes have no local storage at all normally, so
     * this is also what backs [EpisodeWatchedEntity] in the first place. Titles that don't
     * match anything locally (not in your provider's catalog, or a naming mismatch) are
     * reported back rather than silently skipped, so a bad match isn't a silent black box. */
    suspend fun syncWatchedHistoryBack(): SyncBackResult {
        val token = validAccessToken() ?: return SyncBackResult(0, 0, 0)
        val auth = "Bearer $token"

        var moviesMatched = 0
        val unmatchedMovies = mutableListOf<String>()
        try {
            val watchedMovies = api.getWatchedMovies(auth, clientId).body().orEmpty()
            val localVod = db.vodDao().getAllVod().first()
            for (watched in watchedMovies) {
                val match = localVod.firstOrNull { vod ->
                    val parsed = parseTitle(vod.name)
                    parsed.title.equals(watched.movie.title, ignoreCase = true) &&
                        (parsed.year == null || watched.movie.year == null || parsed.year == watched.movie.year)
                }
                if (match == null) {
                    unmatchedMovies += watched.movie.year?.let { "${watched.movie.title} ($it)" } ?: watched.movie.title
                    continue
                }
                val duration = match.durationMs.takeIf { it > 0 } ?: 1L
                db.vodDao().updateWatchProgress(match.streamId, duration, duration)
                moviesMatched++
            }
        } catch (e: Exception) {
            Log.e("TraktManager", "Movie history sync failed: ${e.message}")
        }

        var showsMatched = 0
        var episodesMarked = 0
        val unmatchedShows = mutableListOf<String>()
        try {
            val watchedShows = api.getWatchedShows(auth, clientId).body().orEmpty()
            val localSeries = db.seriesDao().getAllSeries().first()
            for (watched in watchedShows) {
                val match = localSeries.firstOrNull { series ->
                    parseTitle(series.name).title.equals(watched.show.title, ignoreCase = true)
                }
                if (match == null) {
                    unmatchedShows += watched.show.title
                    continue
                }
                showsMatched++
                for (season in watched.seasons) {
                    for (ep in season.episodes) {
                        db.episodeWatchedDao().upsert(EpisodeWatchedEntity(match.seriesId, season.number, ep.number))
                        episodesMarked++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TraktManager", "Show history sync failed: ${e.message}")
        }

        return SyncBackResult(moviesMatched, showsMatched, episodesMarked, unmatchedMovies, unmatchedShows)
    }
}
