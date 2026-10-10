package com.iptvapp.data.repository

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.XmltvGuideWriter
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.util.XmltvFetcher
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real responses through the real XmltvFetcher, from a local server: every way a guide download
 * goes wrong must leave the stored guide as it was, and only a complete guide may replace it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class XmltvFetchFailureTest {

    private val startSec = System.currentTimeMillis() / 1000 / 60 * 60 + 3_600
    private val stopSec = startSec + 3_600
    private val news = XmltvLocalChannel(7, "News", "news.us")

    private lateinit var server: ServerSocket
    private val routes = mutableMapOf<String, () -> Pair<Int, String>>()
    private lateinit var db: IptvDatabase
    private lateinit var refresh: XmltvEpgRefresh

    private val validGuide by lazy {
        """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="news.us"><display-name>News</display-name></channel>
          <programme channel="news.us" start="${ts(startSec)}" stop="${ts(stopSec)}">
            <title>Nightly</title><desc>Tonight's news</desc>
          </programme>
        </tv>""".trimIndent()
    }

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        route("/ok") { 200 to validGuide }
        route("/server-error") { 500 to "Internal Server Error" }
        route("/login-page") { 200 to "<!DOCTYPE html><html><body><form><input name=\"password\"></form><br></body></html>" }
        route("/malformed") { 200 to "<tv><channel id=\"news.us\"><display-name>News & more</display-name></channel><programme channel=\"news.us\"></tv>" }
        route("/truncated") { 200 to validGuide.substring(0, validGuide.indexOf("<title>") + 10) }
        route("/empty") { 200 to "" }
        serve()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IptvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        refresh = XmltvEpgRefresh(XmltvGuideWriter(db))
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun theFetcherRecognisesAGoodGuide() {
        val (channels, programs) = XmltvFetcher.fetch(url("/ok"))
        assertEquals(listOf("news.us"), channels.map { it.id })
        assertEquals(listOf("Nightly"), programs.map { it.title })
    }

    @Test
    fun serverErrorKeepsTheGuide() = keepsTheGuide("/server-error")

    @Test
    fun htmlLoginPageKeepsTheGuide() = keepsTheGuide("/login-page")

    @Test
    fun malformedXmlKeepsTheGuide() = keepsTheGuide("/malformed")

    @Test
    fun truncatedDownloadKeepsTheGuide() = keepsTheGuide("/truncated")

    @Test
    fun emptyBodyKeepsTheGuide() = keepsTheGuide("/empty")

    @Test
    fun connectionRefusedKeepsTheGuide() {
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort } // released: nothing listens
        keepsTheGuide(fullUrl = "http://127.0.0.1:$port/xmltv")
    }

    @Test
    fun aCompleteGuideReplacesTheOldOne() = runBlocking {
        seed()
        val wrote = refresh.loadPrimary(
            sources = listOf(url("/ok")),
            backupUrl = null,
            channels = listOf(news),
            fetch = { XmltvFetcher.fetch(it) },
            stillCurrent = { true }
        )
        assertEquals(1, wrote)
        assertEquals(listOf("Nightly"), titles())
    }

    private fun keepsTheGuide(path: String = "", fullUrl: String = url(path)) = runBlocking {
        seed()
        val (_, programs) = XmltvFetcher.fetch(fullUrl)
        assertEquals("the fetcher reports nothing usable", 0, programs.size)
        val wrote = refresh.loadPrimary(
            sources = listOf(fullUrl),
            backupUrl = null,
            channels = listOf(news),
            fetch = { XmltvFetcher.fetch(it) },
            stillCurrent = { true }
        )
        assertEquals(0, wrote)
        assertEquals(listOf("Old News"), titles())
    }

    private suspend fun seed() {
        db.epgDao().upsertEpg(
            listOf(
                EpgEntity(
                    serverIndex = -1, id = "old", streamId = 7, title = "Old News", description = "",
                    startTimestamp = startSec, stopTimestamp = stopSec, nowPlaying = 0, hasArchive = 0
                )
            )
        )
    }

    private fun titles(): List<String> {
        val cursor = db.query(SimpleSQLiteQuery("SELECT title FROM epg_entries WHERE serverIndex = -1 AND streamId = 7"))
        try {
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) out += cursor.getString(0)
            return out
        } finally {
            cursor.close()
        }
    }

    private fun route(path: String, respond: () -> Pair<Int, String>) {
        routes[path] = respond
    }

    /** One request per connection, enough for HttpURLConnection. Body length is declared, so a
     * cut-short body is a real truncated download, not a slow one. */
    private fun serve() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1)?.substringBefore('?') ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val (code, body) = routes[path]?.invoke() ?: (404 to "")
                    val bytes = body.toByteArray()
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"


    private fun ts(sec: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(sec * 1000))
}
