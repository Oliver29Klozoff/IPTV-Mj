package com.iptvapp.util

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Subtitles for a movie or episode that has none of its own.
 * OpenSubtitles is searched by the title already on screen. A file the viewer picks
 * is accepted when it is an SRT or a WebVTT file, including one packed in gzip or zip.
 */
object ExternalSubtitles {
    const val MAX_BYTES = 2 * 1024 * 1024

    data class Search(
        val query: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val language: String
    )

    data class Hit(
        val fileId: Long,
        val language: String,
        val release: String,
        val downloads: Int,
        val hearingImpaired: Boolean
    )

    data class Prepared(val text: String, val extension: String)

    class Rejected(message: String) : Exception(message)

    /** Movies and episodes only. Live TV, catch-up and recordings keep the tracks they have. */
    fun offersFor(isVod: Boolean, isCatchup: Boolean, isRecording: Boolean): Boolean =
        isVod && !isCatchup && !isRecording

    fun searchFor(
        streamTitle: String,
        seriesName: String,
        season: Int,
        episode: Int,
        iso6392: String
    ): Search {
        val episodeMode = season >= 0 && episode >= 0 && (seriesName.isNotBlank() || streamTitle.isNotBlank())
        val raw = if (episodeMode && seriesName.isNotBlank()) seriesName else streamTitle
        val (title, year) = cleanTitle(raw)
        val query = title.ifBlank { raw.trim() }.lowercase()
        return Search(
            query = query,
            year = if (episodeMode) null else year,
            season = if (episodeMode) season else null,
            episode = if (episodeMode) episode else null,
            language = languageCode(iso6392)
        )
    }

    /** Alphabetical, lowercase. OpenSubtitles redirects any other form and misses its cache. */
    fun queryParams(search: Search): List<Pair<String, String>> {
        val items = mutableListOf(
            "languages" to search.language,
            "query" to search.query,
            "type" to if (search.season != null) "episode" else "movie"
        )
        if (search.season != null && search.episode != null) {
            items += "season_number" to search.season.toString()
            items += "episode_number" to search.episode.toString()
        }
        if (search.season == null && search.year != null) items += "year" to search.year.toString()
        return items.map { (key, value) -> key to value.lowercase() }.sortedBy { it.first }
    }

    fun label(hit: Hit): String {
        val name = hit.release.ifBlank { "Subtitle ${hit.fileId}" }
        val sdh = if (hit.hearingImpaired) " · SDH" else ""
        return "${hit.language} · $name$sdh"
    }

    fun parseSearch(body: String, httpCode: Int): List<Hit> {
        val json = parseObject(body)
        if (httpCode !in 200..299) throw Rejected(userMessage(httpCode, json.string("message")))
        val data = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val hits = mutableListOf<Hit>()
        for (row in data) {
            if (hits.size == 12) break
            val attributes = row.asJsonObject.get("attributes")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val files = attributes.get("files")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val fileId = files.mapNotNull { file ->
                file.asJsonObject.get("file_id")?.takeIf { !it.isJsonNull }?.asLong?.takeIf { it > 0L }
            }.firstOrNull() ?: continue
            hits += Hit(
                fileId = fileId,
                language = attributes.string("language"),
                release = attributes.string("release"),
                downloads = attributes.get("download_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                hearingImpaired = attributes.get("hearing_impaired")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            )
        }
        return hits
    }

    fun parseDownload(body: String, httpCode: Int): String {
        val json = parseObject(body)
        if (httpCode !in 200..299) throw Rejected(userMessage(httpCode, json.string("message")))
        return json.string("link").takeIf { it.startsWith("https://") }
            ?: throw Rejected("OpenSubtitles didn't return a subtitle.")
    }

    /** UTF-8 SRT or WebVTT text, after unpacking gzip or zip. */
    fun prepare(bytes: ByteArray): Prepared {
        if (bytes.size > MAX_BYTES) throw Rejected("That subtitle is too large.")
        val raw = unpack(bytes)
        if (raw.size > MAX_BYTES) throw Rejected("That subtitle is too large.")
        val text = decode(raw).trimStart('\uFEFF').trim()
        if (text.isEmpty()) throw Rejected("That file isn't a subtitle file.")
        val extension = when {
            text.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            text.contains("-->") -> "srt"
            else -> throw Rejected("That file isn't a subtitle file.")
        }
        return Prepared(text, extension)
    }

    private fun unpack(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { readLimited(it) }
        }
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var fallback: ByteArray? = null
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val data = readLimited(zip)
                        val name = entry.name.lowercase()
                        if (name.endsWith(".srt") || name.endsWith(".vtt")) return data
                        if (fallback == null) fallback = data
                    }
                    entry = zip.nextEntry
                }
                return fallback ?: bytes
            }
        }
        return bytes
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total <= MAX_BYTES) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): String {
        val slice = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return try {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(slice))
                .toString()
        } catch (_: CharacterCodingException) {
            String(slice, Charset.forName("windows-1252"))
        }
    }

    fun languageCode(iso6392: String): String = when (iso6392.lowercase()) {
        "", "en", "eng" -> "en"
        "es", "spa" -> "es"
        "fr", "fra", "fre" -> "fr"
        "de", "deu", "ger" -> "de"
        "it", "ita" -> "it"
        "pt", "por" -> "pt"
        "ar", "ara" -> "ar"
        "ru", "rus" -> "ru"
        "hi", "hin" -> "hi"
        "zh", "zho", "chi" -> "zh"
        else -> if (iso6392.length == 2) iso6392.lowercase() else "en"
    }

    fun cleanTitle(raw: String): Pair<String, Int?> {
        var text = raw.trim()
        val ext = text.substringAfterLast('.', "")
        if (ext.lowercase() in VIDEO_EXTENSIONS) text = text.substringBeforeLast('.')
        text = text.replace('.', ' ').replace('_', ' ')
        // Only a year in parentheses is release metadata. 1917 and 2001 are titles.
        val year = YEAR_PARENS.find(text)?.groupValues?.get(1)?.toInt()
        text = YEAR_PARENS.replace(text, " ")
        text = SEASON_EPISODE.replace(text, " ")
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in QUALITY }
        return words.joinToString(" ") to year
    }

    private fun parseObject(body: String): com.google.gson.JsonObject =
        try {
            JsonParser.parseString(body).asJsonObject
        } catch (_: Exception) {
            throw Rejected("OpenSubtitles didn't answer.")
        }

    private fun com.google.gson.JsonObject.string(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun userMessage(httpCode: Int, apiMessage: String): String {
        val message = apiMessage.lowercase()
        return when {
            httpCode == 429 || "rate" in message -> "OpenSubtitles is busy. Try again in a moment."
            "download" in message && ("limit" in message || "quota" in message) ->
                "No OpenSubtitles downloads left today."
            httpCode == 401 || httpCode == 403 ->
                "OpenSubtitles refused the key. Check it in Settings."
            else -> "OpenSubtitles didn't answer."
        }
    }

    private val YEAR_PARENS = Regex("""\(((?:19|20)\d{2})\)""")
    private val SEASON_EPISODE = Regex("""(?i)\bs\d{1,2}\s*e\d{1,3}\b""")
    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v", "ts", "mpg", "mpeg")
    private val QUALITY = setOf(
        "1080p", "720p", "2160p", "480p", "4k", "uhd", "bluray", "blu-ray", "bdrip", "brrip",
        "webrip", "web-dl", "webdl", "hdtv", "hdrip", "dvdrip", "x264", "x265", "h264", "h265",
        "hevc", "aac", "dts", "hdr", "hdr10", "sdr", "10bit", "yify", "rarbg", "amzn", "nf",
        "proper", "repack", "extended", "remux", "ddp5", "dd5"
    )
}
