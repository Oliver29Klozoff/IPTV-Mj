package com.iptvapp.util

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A single long-lived instance is expected to back every lookup (see AppModule) — this class
 * used to be instantiated fresh per call, which meant every single request on the shared
 * OkHttpClient (live HLS segments/manifests included) paid for two sequential, uncached HTTPS
 * round-trips to the DoH resolver with no connection reuse. For a live stream re-resolving on
 * every reconnect, that extra latency was enough to trip ExoPlayer's playlist-staleness
 * watchdog (PlaylistStuckException) well before the provider's own manifest was actually stale.
 */
class DoHDns {

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Short timeouts so a slow/unreachable DoH resolver falls back to system DNS quickly
    // instead of blocking the caller for OkHttp's default ~10s per query.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun resolveUrl(provider: String, hostname: String, type: String) = when (provider) {
        "google"  -> "https://dns.google/resolve?name=$hostname&type=$type"
        "nextdns" -> "https://dns.nextdns.io/resolve?name=$hostname&type=$type"
        else      -> "https://cloudflare-dns.com/dns-query?name=$hostname&type=$type"
    }

    private fun queryType(provider: String, hostname: String, type: String): Pair<List<InetAddress>, Long> {
        val addresses = mutableListOf<InetAddress>()
        var minTtlSeconds = Long.MAX_VALUE
        try {
            val req = Request.Builder()
                .url(resolveUrl(provider, hostname, type))
                .addHeader("Accept", "application/dns-json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return addresses to 300L
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return addresses to 300L
                for (i in 0 until answers.length()) {
                    val ans = answers.getJSONObject(i)
                    val recordType = ans.getInt("type")
                    if (recordType == 1 || recordType == 28) { // A or AAAA
                        try {
                            addresses.add(InetAddress.getByName(ans.getString("data")))
                            minTtlSeconds = minOf(minTtlSeconds, ans.optLong("TTL", 300L))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        return addresses to (if (minTtlSeconds == Long.MAX_VALUE) 300L else minTtlSeconds)
    }

    fun lookup(hostname: String, provider: String): List<InetAddress> {
        val cacheKey = "$provider:$hostname"
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAtMs) return entry.addresses
        }

        // A and AAAA used to be queried sequentially — two blocking round-trips back to back.
        // Running them on separate threads and joining halves the worst-case latency.
        var aResult: Pair<List<InetAddress>, Long>? = null
        var aaaaResult: Pair<List<InetAddress>, Long>? = null
        val aThread = Thread { aResult = queryType(provider, hostname, "A") }
        val aaaaThread = Thread { aaaaResult = queryType(provider, hostname, "AAAA") }
        aThread.start(); aaaaThread.start()
        aThread.join(3500); aaaaThread.join(3500)

        val addresses = (aResult?.first.orEmpty() + aaaaResult?.first.orEmpty())
        val ttlSeconds = minOf(aResult?.second ?: 300L, aaaaResult?.second ?: 300L).coerceAtLeast(30L)

        if (addresses.isEmpty()) {
            // Fall back to system DNS rather than throwing — and don't cache the miss, so the
            // next lookup gets a fresh chance instead of being stuck on system DNS for the TTL.
            return try { Dns.SYSTEM.lookup(hostname) } catch (_: Exception) { throw UnknownHostException(hostname) }
        }
        cache[cacheKey] = CacheEntry(addresses, System.currentTimeMillis() + ttlSeconds * 1000L)
        return addresses
    }
}
