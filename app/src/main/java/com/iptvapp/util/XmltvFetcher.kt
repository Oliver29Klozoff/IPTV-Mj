package com.iptvapp.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class XmltvChannel(val id: String, val displayName: String)

data class XmltvProgram(
    val channelId: String,
    val title: String,
    val description: String,
    val startSec: Long,
    val stopSec: Long
)

object XmltvFetcher {

    fun buildUrl(serverUrl: String, username: String, password: String): String =
        "${serverUrl.trimEnd('/')}/xmltv.php?username=$username&password=$password"

    /** Returns (channels, programs). Empty pair on any network/parse error — never throws. */
    fun fetch(url: String): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 90_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.connect()
            if (conn.responseCode !in 200..299) return Pair(emptyList(), emptyList())

            val buffered = conn.inputStream.buffered()
            buffered.mark(2)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val isGzip = b0 == 0x1f && b1 == 0x8b
            val stream: InputStream = if (isGzip) GZIPInputStream(buffered) else buffered

            stream.buffered().use { parse(it) }
        } catch (e: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    // SimpleDateFormat is explicitly documented as NOT thread-safe — a single shared instance
    // here, called from parseTs() during concurrent merged-provider EPG fetches (each provider's
    // XMLTV feed is parsed on its own coroutine), let two threads corrupt its internal
    // Calendar/NumberFormat state at the same time. That corruption doesn't just produce a wrong
    // date — it can drive DecimalFormat.parse into a runaway loop that keeps allocating until the
    // heap is exhausted (confirmed via a real OOM crash inside SimpleDateFormat.subParse while
    // parsing a large XMLTV feed). A thread-local instance gives each thread/coroutine its own
    // formatter, matching the standard fix for this well-known SimpleDateFormat pitfall.
    private val tsFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }

    private fun parseTs(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            val s = raw.trim()
            val withTz = if (' ' in s) s else "$s +0000"
            (tsFmt.get()!!.parse(withTz)?.time ?: 0L) / 1000L
        } catch (_: Exception) { 0L }
    }

    private fun parse(stream: InputStream): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        val channels  = mutableListOf<XmltvChannel>()
        val programs  = mutableListOf<XmltvProgram>()

        val nowSec  = System.currentTimeMillis() / 1000
        val minSec  = nowSec - 3  * 24 * 3600L
        val maxSec  = nowSec + 14 * 24 * 3600L

        val parser  = Xml.newPullParser()
        parser.setFeature(Xml.FEATURE_RELAXED, true)
        parser.setInput(stream, null)

        var inChannel     = false
        var inDisplayName = false
        var channelId     = ""
        var displayName   = ""
        var channelDone   = false   // only take first display-name per channel

        var inTitle = false
        var inDesc  = false
        var progChannelId = ""
        var progStart     = 0L
        var progStop      = 0L
        var progTitle     = ""
        var progDesc      = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel     = true
                        channelId     = parser.getAttributeValue(null, "id") ?: ""
                        displayName   = ""
                        channelDone   = false
                    }
                    "display-name" -> if (inChannel && !channelDone) inDisplayName = true
                    "programme" -> {
                        progChannelId = parser.getAttributeValue(null, "channel") ?: ""
                        progStart     = parseTs(parser.getAttributeValue(null, "start") ?: "")
                        progStop      = parseTs(parser.getAttributeValue(null, "stop")  ?: "")
                        progTitle     = ""
                        progDesc      = ""
                    }
                    "title" -> inTitle = true
                    "desc"  -> inDesc  = true
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text ?: ""
                    when {
                        inDisplayName -> displayName += t
                        inTitle       -> progTitle   += t
                        inDesc        -> progDesc    += t
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        if (channelId.isNotEmpty() && displayName.isNotBlank()) {
                            channels.add(XmltvChannel(channelId, displayName.trim()))
                        }
                        inChannel     = false
                        inDisplayName = false
                    }
                    "display-name" -> {
                        inDisplayName = false
                        channelDone   = true
                    }
                    "title" -> inTitle = false
                    "desc"  -> inDesc  = false
                    "programme" -> {
                        if (progChannelId.isNotEmpty()
                            && progStart in minSec..maxSec
                            && progTitle.isNotBlank()
                        ) {
                            programs.add(XmltvProgram(progChannelId, progTitle.trim(), progDesc.trim(), progStart, progStop))
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Pair(channels, programs)
    }
}
