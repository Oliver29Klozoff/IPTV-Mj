package com.iptvapp.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
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

    private const val TAG = "XmltvFetcher"

    private const val MAX_FIELD_LEN = 4000

    // A malformed/unescaped feed (a stray "&" is enough to confuse the entity parser, or a
    // genuinely missing closing tag) can make KXmlParser's internal per-token StringBuilder
    // accumulate an unbounded run — confirmed via TWO separate real OOM crashes, both inside
    // KXmlParser.readValue/next itself (KXmlParser.kt:1389 and :1436). A whole-stream byte cap
    // doesn't help: the runaway is a SINGLE token, and a legitimate 1.4M-entry feed can itself
    // total well past any ceiling still tight enough to catch a bad token before it exhausts the
    // heap. Instead PerTokenLimitStream resets its own counter every time parse()'s loop
    // successfully consumes a parser.next() event (a real token boundary was reached) and throws
    // if MORE bytes than this are read before the NEXT boundary — directly bounding the size of
    // whatever single token KXmlParser is currently trying to build, independent of total
    // document size. No legitimate channel name/programme title/description approaches this.
    private const val MAX_TOKEN_BYTES = 2L * 1024 * 1024

    // Backstop in case interrupting the watchdog thread doesn't unblock a stuck read in time —
    // see parseWithTimeout's kdoc.
    private const val PARSE_TIMEOUT_MS = 60_000L

    private class PerTokenLimitStream(private val delegate: InputStream) : InputStream() {
        private var sinceCheckpoint = 0L
        fun checkpoint() { sinceCheckpoint = 0L }
        private fun check(n: Int): Int {
            if (n > 0) {
                sinceCheckpoint += n
                if (sinceCheckpoint > MAX_TOKEN_BYTES) {
                    throw java.io.IOException("XMLTV token exceeded $MAX_TOKEN_BYTES bytes without a tag boundary — aborting to avoid OOM")
                }
            }
            return n
        }
        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) check(1)
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = check(delegate.read(b, off, len))
        override fun close() = delegate.close()
    }

    /** Returns (channels, programs). Empty pair on any network/parse error — never throws. */
    fun fetch(url: String): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 90_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                android.util.Log.w(TAG, "HTTP $code for ${com.iptvapp.util.LogSanitizer.redactCredentials(url)}")
                return Pair(emptyList(), emptyList())
            }

            val buffered = conn.inputStream.buffered()
            buffered.mark(2)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val isGzip = b0 == 0x1f && b1 == 0x8b
            val stream: InputStream = if (isGzip) GZIPInputStream(buffered) else buffered
            val limited = PerTokenLimitStream(stream.buffered())

            // Primary defense is limited's per-token cap (throws IOException, caught below,
            // before the runaway allocation happens). The wall-clock watchdog is a backstop for
            // the case where interrupting doesn't apply (CPU-bound StringBuilder work between
            // reads doesn't poll the interrupt flag) — the per-token cap is what actually fires
            // in practice, well before this timeout would ever need to.
            parseWithTimeout(limited)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "fetch failed for ${com.iptvapp.util.LogSanitizer.redactCredentials(url)}: ${e.javaClass.simpleName}: ${e.message}")
            Pair(emptyList(), emptyList())
        }
    }

    private fun parseWithTimeout(stream: PerTokenLimitStream): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        val result = AtomicReference<Pair<List<XmltvChannel>, List<XmltvProgram>>?>(null)
        val error = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try {
                result.set(parse(stream))
            } catch (e: Throwable) {
                error.set(e)
            }
        }, "XmltvFetcher-parse")
        worker.isDaemon = true
        worker.start()
        worker.join(PARSE_TIMEOUT_MS)
        if (worker.isAlive) {
            android.util.Log.e(TAG, "XMLTV parse exceeded ${PARSE_TIMEOUT_MS}ms — interrupting to avoid OOM")
            worker.interrupt()
            worker.join(5_000)
            return Pair(emptyList(), emptyList())
        }
        error.get()?.let { throw it }
        return result.get() ?: Pair(emptyList(), emptyList())
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

    private fun parse(stream: PerTokenLimitStream): Pair<List<XmltvChannel>, List<XmltvProgram>> {
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
                    // Real-world feeds occasionally have a malformed/unescaped body (a stray "&"
                    // is enough to confuse the entity parser into treating a huge chunk of the
                    // document as one text run) that makes a single accumulating field balloon to
                    // hundreds of MB and OOM-crash the whole app before it's ever used — display
                    // names/titles/descriptions are only ever shown as short one-line UI text, so
                    // capping well above any legitimate value is free insurance against that.
                    when {
                        inDisplayName -> if (displayName.length < MAX_FIELD_LEN) displayName += t
                        inTitle       -> if (progTitle.length < MAX_FIELD_LEN) progTitle += t
                        inDesc        -> if (progDesc.length < MAX_FIELD_LEN) progDesc += t
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
            // A token boundary was just reached successfully — reset the per-token byte counter
            // so the NEXT token gets its own fresh budget, independent of how much of the
            // document has been consumed so far. This is what lets MAX_TOKEN_BYTES stay tight
            // (2MB) even though the whole feed can legitimately be hundreds of MB.
            stream.checkpoint()
        }

        return Pair(channels, programs)
    }
}
