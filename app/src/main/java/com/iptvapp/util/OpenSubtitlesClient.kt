package com.iptvapp.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenSubtitles REST API. The viewer's own API key is sent on every call.
 * A username, when they saved one, spends that account's download allowance.
 * Download links are not logged: they are short-lived and belong to the account.
 */
class OpenSubtitlesClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    fun login(apiKey: String, userAgent: String, username: String, password: String): String? {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val response = post("/login", apiKey, userAgent, token = null, json = body)
        if (response.code !in 200..299) return null
        return runCatching { JSONObject(response.body).optString("token") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun search(
        apiKey: String,
        userAgent: String,
        params: List<Pair<String, String>>,
        token: String?
    ): List<ExternalSubtitles.Hit> {
        val url = StringBuilder("$API/subtitles")
        params.forEachIndexed { index, (key, value) ->
            url.append(if (index == 0) '?' else '&')
            url.append(key).append('=').append(encode(value))
        }
        val response = get(url.toString(), apiKey, userAgent, token)
        return ExternalSubtitles.parseSearch(response.body, response.code)
    }

    /** Temporary https link for [fileId]. Asks for SRT so the player can read it. */
    fun downloadLink(apiKey: String, userAgent: String, token: String?, fileId: Long): String {
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString()
        val response = post("/download", apiKey, userAgent, token, body)
        return ExternalSubtitles.parseDownload(response.body, response.code)
    }

    fun fetch(link: String): ByteArray {
        if (!link.startsWith("https://")) throw ExternalSubtitles.Rejected("OpenSubtitles didn't return a subtitle.")
        val response = http.newCall(Request.Builder().url(link).build()).execute()
        response.use {
            if (!it.isSuccessful) throw ExternalSubtitles.Rejected("OpenSubtitles didn't return a subtitle.")
            val stream = it.body?.byteStream() ?: throw ExternalSubtitles.Rejected("OpenSubtitles didn't return a subtitle.")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total <= ExternalSubtitles.MAX_BYTES) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun get(url: String, apiKey: String, userAgent: String, token: String?): HttpResult {
        val request = Request.Builder().url(url).headers(apiKey, userAgent, token).build()
        return execute(request)
    }

    private fun post(path: String, apiKey: String, userAgent: String, token: String?, json: String): HttpResult {
        val request = Request.Builder()
            .url(API + path)
            .headers(apiKey, userAgent, token)
            .post(json.toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): HttpResult {
        return try {
            http.newCall(request).execute().use { response ->
                HttpResult(response.code, response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            throw ExternalSubtitles.Rejected("OpenSubtitles didn't answer.")
        }
    }

    private fun Request.Builder.headers(apiKey: String, userAgent: String, token: String?): Request.Builder {
        header("Api-Key", apiKey)
        header("User-Agent", userAgent)
        header("Accept", "application/json")
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        return this
    }

    /** Spaces as plus signs, which is the form OpenSubtitles caches. */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class HttpResult(val code: Int, val body: String)

    private companion object {
        const val API = "https://api.opensubtitles.com/api/v1"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
