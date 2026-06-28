package com.iptvapp.util

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException

class DoHDns(private val provider: String = "cloudflare") : Dns {

    private val httpClient = OkHttpClient()

    private fun resolveUrl(hostname: String, type: String) = when (provider) {
        "google"  -> "https://dns.google/resolve?name=$hostname&type=$type"
        "nextdns" -> "https://dns.nextdns.io/resolve?name=$hostname&type=$type"
        else      -> "https://cloudflare-dns.com/dns-query?name=$hostname&type=$type"
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        for (type in listOf("A", "AAAA")) {
            try {
                val req = Request.Builder()
                    .url(resolveUrl(hostname, type))
                    .addHeader("Accept", "application/dns-json")
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string() ?: continue
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: continue
                for (i in 0 until answers.length()) {
                    val ans = answers.getJSONObject(i)
                    val recordType = ans.getInt("type")
                    if (recordType == 1 || recordType == 28) { // A or AAAA
                        try {
                            addresses.add(InetAddress.getByName(ans.getString("data")))
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        if (addresses.isEmpty()) {
            // Fall back to system DNS rather than throwing
            return try { Dns.SYSTEM.lookup(hostname) } catch (_: Exception) { throw UnknownHostException(hostname) }
        }
        return addresses
    }
}
