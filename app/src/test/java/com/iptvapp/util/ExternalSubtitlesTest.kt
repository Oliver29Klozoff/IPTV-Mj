package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalSubtitlesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    @Test
    fun onlyMoviesAndEpisodesAreOfferedASubtitle() {
        assertTrue(ExternalSubtitles.offersFor(isVod = true, isCatchup = false, isRecording = false))
        assertFalse(ExternalSubtitles.offersFor(isVod = true, isCatchup = true, isRecording = false))
        assertFalse(ExternalSubtitles.offersFor(isVod = false, isCatchup = false, isRecording = false))
        assertFalse(ExternalSubtitles.offersFor(isVod = true, isCatchup = false, isRecording = true))
    }

    @Test
    fun aReleaseNameBecomesATitleAndAYear() {
        val (title, year) = ExternalSubtitles.cleanTitle("The.Matrix.1999.1080p.BluRay.x264.mkv")
        assertEquals("The Matrix 1999", title)
        assertNull(year)
        val (film, filmYear) = ExternalSubtitles.cleanTitle("Inception (2010)")
        assertEquals("Inception", film)
        assertEquals(2010, filmYear)
        assertEquals("2001: A Space Odyssey" to null, ExternalSubtitles.cleanTitle("2001: A Space Odyssey"))
        assertEquals("1917" to null, ExternalSubtitles.cleanTitle("1917"))
    }

    @Test
    fun anEpisodeSearchesTheSeriesAndNotTheReleaseYear() {
        val search = ExternalSubtitles.searchFor(
            streamTitle = "S05E14 Stress Relief",
            seriesName = "The Office",
            season = 5,
            episode = 14,
            iso6392 = "eng"
        )
        assertEquals("the office", search.query)
        assertEquals(5, search.season)
        assertEquals(14, search.episode)
        assertNull(search.year)
        assertEquals("en", search.language)
        assertEquals(
            listOf(
                "episode_number" to "14",
                "languages" to "en",
                "query" to "the office",
                "season_number" to "5",
                "type" to "episode"
            ),
            ExternalSubtitles.queryParams(search)
        )
    }

    @Test
    fun aMovieSearchCarriesTheYearAndStaysAlphabetical() {
        val search = ExternalSubtitles.searchFor("Inception (2010)", "", -1, -1, "")
        assertEquals(
            listOf(
                "languages" to "en",
                "query" to "inception",
                "type" to "movie",
                "year" to "2010"
            ),
            ExternalSubtitles.queryParams(search)
        )
    }

    @Test
    fun searchResultsKeepTheFirstFileAndStopAtTwelve() {
        val rows = (1..14).joinToString(",") {
            """{"attributes":{"language":"en","release":"Cut $it","download_count":$it,"hearing_impaired":false,"files":[{"file_id":$it}]}}"""
        }
        val hits = ExternalSubtitles.parseSearch("""{"data":[$rows]}""", 200)
        assertEquals(12, hits.size)
        assertEquals(1L, hits.first().fileId)
        assertEquals("en · Cut 1", ExternalSubtitles.label(hits.first()))
    }

    @Test
    fun aRefusedKeyDoesNotEchoTheResponse() {
        val error = runCatching {
            ExternalSubtitles.parseSearch("""{"message":"invalid key abc"}""", 403)
        }.exceptionOrNull()
        assertEquals("OpenSubtitles refused the key. Check it in Settings.", error?.message)
    }

    @Test
    fun plainGzipAndZipSubtitlesBecomeUtf8() {
        assertEquals("srt", ExternalSubtitles.prepare(srt.toByteArray()).extension)
        val gzip = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(srt.toByteArray()) }
        }.toByteArray()
        assertEquals("srt", ExternalSubtitles.prepare(gzip).extension)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("movie.srt"))
                it.write(srt.toByteArray())
                it.closeEntry()
            }
        }.toByteArray()
        assertTrue(ExternalSubtitles.prepare(zip).text.contains("Hello"))
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("notes.txt"))
                it.write(ByteArray(ExternalSubtitles.MAX_BYTES + 1))
                it.closeEntry()
                it.putNextEntry(ZipEntry("movie.srt"))
                it.write(srt.toByteArray())
                it.closeEntry()
            }
        }.toByteArray()
        assertTrue(bomb.size < ExternalSubtitles.MAX_BYTES)
        val tooBig = runCatching { ExternalSubtitles.prepare(bomb) }.exceptionOrNull()
        assertEquals("That subtitle is too large.", tooBig?.message)
        val dirBomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("huge/"))
                it.write(ByteArray(ExternalSubtitles.MAX_BYTES + 1))
                it.closeEntry()
                it.putNextEntry(ZipEntry("movie.srt"))
                it.write(srt.toByteArray())
                it.closeEntry()
            }
        }.toByteArray()
        val dirTooBig = runCatching { ExternalSubtitles.prepare(dirBomb) }.exceptionOrNull()
        assertEquals("That subtitle is too large.", dirTooBig?.message)
        val latin = "1\n00:00:01,000 --> 00:00:02,000\nCaf\u00e9\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue(ExternalSubtitles.prepare(latin).text.contains("Café"))
    }

    @Test
    fun aVideoFileIsNotTreatedAsSubtitles() {
        val error = runCatching { ExternalSubtitles.prepare("not subtitles".toByteArray()) }.exceptionOrNull()
        assertEquals("That file isn't a subtitle file.", error?.message)
    }
}
