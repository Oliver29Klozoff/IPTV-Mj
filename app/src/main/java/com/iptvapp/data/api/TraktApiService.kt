package com.iptvapp.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
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

data class TraktDeviceTokenRequest(
    val code: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("client_secret") val clientSecret: String
)

data class TraktRefreshTokenRequest(
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("client_secret") val clientSecret: String,
    @SerializedName("grant_type") val grantType: String = "refresh_token"
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

interface TraktApiService {
    @POST("oauth/device/code")
    suspend fun getDeviceCode(@Body body: TraktDeviceCodeRequest): Response<TraktDeviceCodeResponse>

    @POST("oauth/device/token")
    suspend fun getDeviceToken(@Body body: TraktDeviceTokenRequest): Response<TraktTokenResponse>

    @POST("oauth/token")
    suspend fun refreshToken(@Body body: TraktRefreshTokenRequest): Response<TraktTokenResponse>

    @Headers("Content-Type: application/json")
    @POST("sync/scrobble/start")
    suspend fun scrobbleStart(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2", @Body body: TraktScrobbleRequest): Response<Unit>

    @Headers("Content-Type: application/json")
    @POST("sync/scrobble/pause")
    suspend fun scrobblePause(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2", @Body body: TraktScrobbleRequest): Response<Unit>

    @Headers("Content-Type: application/json")
    @POST("sync/scrobble/stop")
    suspend fun scrobbleStop(@Header("Authorization") auth: String, @Header("trakt-api-key") key: String, @Header("trakt-api-version") version: String = "2", @Body body: TraktScrobbleRequest): Response<Unit>
}
