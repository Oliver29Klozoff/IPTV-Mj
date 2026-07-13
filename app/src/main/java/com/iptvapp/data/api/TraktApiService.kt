package com.iptvapp.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

data class TraktDeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String,
    @SerializedName("verification_url") val verificationUrl: String,
    @SerializedName("expires_in") val expiresIn: Int,
    val interval: Int
)

data class TraktDeviceCodeRequest(
    @SerializedName("client_id") val clientId: String
)

data class TraktTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Long
)

data class TraktMovieIds(val trakt: Int? = null)
data class TraktMovie(val title: String, val year: Int? = null)
data class TraktShow(val title: String)
data class TraktEpisode(val season: Int, val number: Int)

data class TraktScrobbleRequest(
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
    val episode: TraktEpisode? = null,
    val progress: Float
)

// GET /sync/watched/movies and /sync/watched/shows response shapes — used for one-time
// history sync-back (seeding "already watched" state), not the outgoing scrobble calls above.
data class TraktWatchedMovie(
    val plays: Int,
    @SerializedName("last_watched_at") val lastWatchedAt: String?,
    val movie: TraktMovie
)

data class TraktWatchedShow(
    val plays: Int,
    @SerializedName("last_watched_at") val lastWatchedAt: String?,
    val show: TraktShow,
    val seasons: List<TraktWatchedSeason>
)

data class TraktWatchedSeason(
    val number: Int,
    val episodes: List<TraktWatchedEpisode>
)

data class TraktWatchedEpisode(
    val number: Int,
    val plays: Int
)

// Sent to OUR proxy (not Trakt directly) — the proxy attaches client_id/client_secret itself,
// so the app never needs to know the secret.
data class TraktProxyCodeRequest(val code: String)
data class TraktProxyRefreshRequest(@SerializedName("refresh_token") val refreshToken: String)

interface TraktProxyApiService {
    @POST("device/token")
    suspend fun getDeviceToken(@Body body: TraktProxyCodeRequest): Response<TraktTokenResponse>

    @POST("refresh")
    suspend fun refreshToken(@Body body: TraktProxyRefreshRequest): Response<TraktTokenResponse>
}

interface TraktApiService {
    @POST("oauth/device/code")
    suspend fun getDeviceCode(@Body body: TraktDeviceCodeRequest): Response<TraktDeviceCodeResponse>

    @Headers("Content-Type: application/json")
    @POST("sync/scrobble/start")
    suspend fun scrobbleStart(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2", @Body body: TraktScrobbleRequest): Response<Unit>

    @Headers("Content-Type: application/json")
    @POST("sync/scrobble/pause")
    suspend fun scrobblePause(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2", @Body body: TraktScrobbleRequest): Response<Unit>

    @Headers("Content-Type: application/json")
    @POST("sync/scrobble/stop")
    suspend fun scrobbleStop(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2", @Body body: TraktScrobbleRequest): Response<Unit>

    @GET("sync/watched/movies")
    suspend fun getWatchedMovies(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2"): Response<List<TraktWatchedMovie>>

    @GET("sync/watched/shows")
    suspend fun getWatchedShows(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2"): Response<List<TraktWatchedShow>>
}
