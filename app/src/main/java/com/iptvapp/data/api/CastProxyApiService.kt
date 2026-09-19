package com.iptvapp.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class CastWrapRequest(val url: String)
data class CastWrapResponse(val token: String)

/** Talks to the user's own Cloudflare Worker (cloudflare/cast-proxy-worker.js) to turn a raw,
 * credential-bearing Xtream stream URL into an opaque, encrypted, time-limited token — see
 * CastRelayManager kdoc for why this exists. Never called with anything that ends up going to a
 * third party directly; this is the one and only place the raw URL is sent anywhere; it goes to
 * the user's own self-hosted worker, not Firestore or the receiving device. */
interface CastProxyApiService {
    @POST("wrap")
    suspend fun wrap(@Header("X-App-Key") appKey: String, @Body body: CastWrapRequest): Response<CastWrapResponse>
}
