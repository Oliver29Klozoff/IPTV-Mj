package com.iptvapp.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.XmltvGuideWriter
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram
import androidx.sqlite.db.SimpleSQLiteQuery
import java.io.IOException
import java.net.SocketTimeoutException
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guide swap itself. Downloads are a lambda, so each failure happens at a known point
 * and nothing here sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class XmltvEpgRefreshTest {

    private val startSec = System.currentTimeMillis() / 1000 + 3_600
    private val stopSec = startSec + 3_600
    private val news = XmltvLocalChannel(7, "News HD", "news.us")
    private val sports = XmltvLocalChannel(8, "Sports", "sports.us")
    private val extra = XmltvLocalChannel(9, "Extra", "extra.us")

    private lateinit var db: IptvDatabase
    private lateinit var writer: XmltvGuideWriter
    private lateinit var refresh: XmltvEpgRefresh

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java
        ).allowMainThreadQueries().build()
        writer = XmltvGuideWriter(db)
        refresh = XmltvEpgRefresh(writer)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun successfulRefreshReplacesThatProviderAndLeavesTheOther() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"), row(0, 7, "Other Provider"))
        val wrote = load(channels = listOf(news, sports)) { feed(news, "Nightly") }
        assertEquals(1, wrote)
        assertEquals(listOf("Nightly"), titles(-1, 7))
        assertEquals(emptyList<String>(), titles(-1, 8))
        assertEquals(listOf("Other Provider"), titles(0, 7))
    }

    @Test
    fun networkFailureKeepsTheGuide() = keepsOn { throw IOException("down") }

    @Test
    fun timeoutKeepsTheGuide() = keepsOn { throw SocketTimeoutException("slow") }

    @Test
    fun malformedProgramsKeepTheGuide() = keepsOn {
        listOf(XmltvChannel("news.us", "News")) to listOf(
            XmltvProgram("news.us", "", "", startSec, stopSec),
            XmltvProgram("news.us", "Broken", "", startSec, startSec)
        )
    }

    @Test
    fun parserExceptionKeepsTheGuide() = keepsOn { throw IllegalStateException("bad xml") }

    @Test
    fun emptyBodyKeepsTheGuide() = keepsOn { emptyList<XmltvChannel>() to emptyList() }

    @Test
    fun databaseFailureDuringInsertRollsBack() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val ticket = writer.begin(-1)
        val failed = runCatching {
            writer.commit(
                serverIndex = -1,
                ticket = ticket,
                entries = listOf(row(-1, 7, "New", id = "new")),
                streamIds = null,
                stillCurrent = { true },
                afterDelete = { throw SQLException("disk full") }
            )
        }
        assertTrue(failed.exceptionOrNull() is SQLException)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun cancellationBeforeReplacementKeepsTheGuideAndPropagates() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val started = CompletableDeferred<Unit>()
        var seen: Throwable? = null
        val job = launch {
            try {
                load { 
                    started.complete(Unit)
                    suspendCancellableCoroutine { }
                }
            } catch (t: Throwable) {
                seen = t
                throw t
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(seen is CancellationException)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun cancellationInsideTheTransactionRollsBack() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val ticket = writer.begin(-1)
        val failed = runCatching {
            writer.commit(
                serverIndex = -1,
                ticket = ticket,
                entries = listOf(row(-1, 7, "New", id = "new")),
                streamIds = null,
                stillCurrent = { true },
                afterDelete = { throw CancellationException("stopped") }
            )
        }
        assertTrue(failed.exceptionOrNull() is CancellationException)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun staleRefreshDoesNotReplaceAfterTheProviderSlotIsCleared() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val wrote = load {
            writer.clear(-1)
            db.epgDao().upsertEpg(listOf(row(-1, 7, "Provider B", id = "b-row")))
            feed(news, "Stale A")
        }
        assertEquals(0, wrote)
        assertEquals(listOf("Provider B"), titles(-1, 7))
    }

    @Test
    fun staleRefreshDoesNotReplaceWhenTheProviderIdentityChanged() = runBlocking {
        seed(row(-1, 7, "Old News"))
        var same = true
        val wrote = load(still = { same }) {
            same = false
            feed(news, "Stale A")
        }
        assertEquals(0, wrote)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun olderCompletionCannotOverwriteANewerSuccess() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val older = async {
            load {
                entered.complete(Unit)
                release.await()
                feed(news, "Older")
            }
        }
        entered.await()
        val newerWrote = load { feed(news, "Newer") }
        release.complete(Unit)
        val olderWrote = older.await()
        assertTrue(newerWrote > 0)
        assertEquals(0, olderWrote)
        assertEquals(listOf("Newer"), titles(-1, 7))
    }

    @Test
    fun oneFailedSourceDoesNotWipeChannelsItDidNotReplace() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"))
        val wrote = load(sources = listOf("https://news.example/xmltv", "https://sports.example/xmltv")) { url ->
            if (url.contains("sports")) throw IOException("sports down")
            feed(news, "New News")
        }
        assertTrue(wrote > 0)
        assertEquals(listOf("New News"), titles(-1, 7))
        assertEquals(listOf("Old Sports"), titles(-1, 8))
    }

    @Test
    fun everySourceSucceedingReplacesTheWholeSlot() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 9, "Left Over"))
        val wrote = load(
            sources = listOf("https://a.example/xmltv", "https://b.example/xmltv"),
            channels = listOf(news, sports, extra)
        ) { url ->
            if (url.contains("://b.")) feed(sports, "Game") else feed(news, "Nightly")
        }
        assertEquals(2, wrote)
        assertEquals(listOf("Nightly"), titles(-1, 7))
        assertEquals(listOf("Game"), titles(-1, 8))
        assertEquals(emptyList<String>(), titles(-1, 9))
    }

    @Test
    fun backupGuideReplacesOnlyTheChannelsItMatched() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"))
        val wrote = load(backup = "https://backup.example/xmltv") { url ->
            if (url.contains("backup")) feed(news, "Backup News") else emptyList<XmltvChannel>() to emptyList()
        }
        assertEquals(1, wrote)
        assertEquals(listOf("Backup News"), titles(-1, 7))
        assertEquals(listOf("Old Sports"), titles(-1, 8))
    }

    @Test
    fun mergedFailureKeepsThatServerAndABackupDoesNotWipeUnmatchedChannels() = runBlocking {
        seed(row(2, 7, "Merged Old"), row(2, 8, "Merged Sports"), row(-1, 7, "Primary"))
        val failed = refresh.loadMerged(
            serverIndex = 2,
            sources = listOf("https://merged.example/xmltv"),
            backupUrl = "https://backup.example/xmltv",
            backupIsPartial = true,
            channels = listOf(news, sports),
            fetch = { throw IOException("down") },
            stillCurrent = { true }
        )
        assertEquals(0, failed)
        assertEquals(listOf("Merged Old"), titles(2, 7))

        val wrote = refresh.loadMerged(
            serverIndex = 2,
            sources = listOf("https://merged.example/xmltv", "https://backup.example/xmltv"),
            backupUrl = "https://backup.example/xmltv",
            backupIsPartial = true,
            channels = listOf(news, sports),
            fetch = { url ->
                if (url.contains("backup")) feed(news, "Backup Merged") else emptyList<XmltvChannel>() to emptyList()
            },
            stillCurrent = { true }
        )
        assertEquals(1, wrote)
        assertEquals(listOf("Backup Merged"), titles(2, 7))
        assertEquals(listOf("Merged Sports"), titles(2, 8))
        assertEquals(listOf("Primary"), titles(-1, 7))
    }

    @Test
    fun emptyReplacementDoesNotDelete() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val ticket = writer.begin(-1)
        val wrote = writer.commit(-1, ticket, emptyList(), null, stillCurrent = { true })
        assertEquals(0, wrote)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    // ── Cancellation and timeouts (the v7.21 rules: a source's own timeout is a failure,
    // the refresh being cancelled is not) ────────────────────────────────────────────────

    @Test
    fun oneSourceTimingOutLetsTheOtherSourceReplaceItsChannels() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"))
        val wrote = load(sources = listOf("https://news.example/xmltv", "https://sports.example/xmltv")) { url ->
            // That source's own time limit, not the refresh's.
            if (url.contains("news")) withTimeout(0) { awaitCancellation() } else feed(sports, "Game")
        }
        assertEquals(1, wrote)
        assertEquals(listOf("Game"), titles(-1, 8))
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun cancellingTheRefreshStopsEveryLaterStep() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"))
        val fetched = mutableListOf<String>()
        val inFetch = CompletableDeferred<Unit>()
        var snapshotTaken = false
        var diffRecorded = false
        var ranAfter = false
        val job = launch {
            load(
                sources = listOf("https://a.example/xmltv", "https://b.example/xmltv"),
                backup = "https://backup.example/xmltv",
                before = { snapshotTaken = true },
                after = { diffRecorded = true }
            ) { url ->
                fetched += url
                inFetch.complete(Unit)
                awaitCancellation()
            }
            ranAfter = true
        }
        inFetch.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("no later source or backup is tried", listOf("https://a.example/xmltv"), fetched)
        assertFalse(snapshotTaken)
        assertFalse(diffRecorded)
        assertFalse(ranAfter)
        assertEquals(listOf("Old News"), titles(-1, 7))
        assertEquals(listOf("Old Sports"), titles(-1, 8))
    }

    @Test
    fun aCallersTimeoutIsNotMistakenForOneSourceFailing() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val fetched = mutableListOf<String>()
        try {
            withTimeout(50) {
                load(sources = listOf("https://a.example/xmltv", "https://b.example/xmltv")) { url ->
                    fetched += url
                    awaitCancellation()
                }
            }
            fail("the caller's timeout must reach the caller")
        } catch (_: TimeoutCancellationException) {
        }
        assertEquals(listOf("https://a.example/xmltv"), fetched)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun realCancellationAfterTheDeleteRollsTheTransactionBack() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"), row(0, 7, "Other Provider"))
        val deleted = CompletableDeferred<Int>()
        var finished = false
        val ticket = writer.begin(-1)
        val job = launch {
            writer.commit(
                serverIndex = -1,
                ticket = ticket,
                entries = listOf(row(-1, 7, "New", id = "new")),
                streamIds = null,
                stillCurrent = { true },
                afterDelete = {
                    // Inside the transaction, after the delete: only the other provider's row is left.
                    deleted.complete(db.epgDao().getEpgCount())
                    awaitCancellation()
                }
            )
            finished = true
        }
        assertEquals(1, deleted.await())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(finished)
        assertEquals(listOf("Old News"), titles(-1, 7))
        assertEquals(listOf("Old Sports"), titles(-1, 8))
        assertEquals(listOf("Other Provider"), titles(0, 7))
    }

    @Test
    fun cancellationInsideTheTransactionRecordsNoFavoriteDiff() = runBlocking {
        seed(row(-1, 7, "Old News"))
        val inTransaction = CompletableDeferred<Unit>()
        var diffRecorded = false
        val job = launch {
            load(
                // stillCurrent runs inside the transaction, just before the delete.
                still = { inTransaction.complete(Unit); awaitCancellation() },
                after = { diffRecorded = true }
            ) { feed(news, "New") }
        }
        inTransaction.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(diffRecorded)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun cancellationJustBeforeTheCommitPreventsIt() = runBlocking {
        seed(row(-1, 7, "Old News"))
        var diffRecorded = false
        val job = launch {
            load(
                before = { currentCoroutineContext().job.cancel() },
                after = { diffRecorded = true }
            ) { feed(news, "New") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(diffRecorded)
        assertEquals(listOf("Old News"), titles(-1, 7))
    }

    @Test
    fun cancellingAMultiProviderRefreshStopsEveryProvider() = runBlocking {
        seed(row(1, 7, "One"), row(2, 7, "Two"))
        val backup = "https://backup.example/xmltv"
        val fetched = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bothStarted = CompletableDeferred<Unit>()
        var committed = false
        var ranAfter = false
        val refreshAll = launch {
            coroutineScope {
                for (server in 1..2) launch {
                    refresh.loadMerged(
                        serverIndex = server,
                        sources = listOf("https://p$server.example/xmltv", backup),
                        backupUrl = backup,
                        backupIsPartial = true,
                        channels = listOf(news),
                        fetch = { url ->
                            fetched += url
                            if (fetched.size == 2) bothStarted.complete(Unit)
                            awaitCancellation()
                        },
                        stillCurrent = { true },
                        onCommitted = { committed = true }
                    )
                }
            }
            ranAfter = true
        }
        bothStarted.await()
        refreshAll.cancelAndJoin()
        assertTrue(refreshAll.isCancelled)
        assertEquals("neither provider moves on to the backup", 2, fetched.size)
        assertFalse(fetched.contains(backup))
        assertFalse(committed)
        assertFalse(ranAfter)
        assertEquals(listOf("One"), titles(1, 7))
        assertEquals(listOf("Two"), titles(2, 7))
    }

    @Test
    fun aMultiProviderSourceTimingOutFallsThroughToTheNextSource() = runBlocking {
        seed(row(2, 7, "Merged Old"), row(2, 8, "Merged Sports"))
        val own = "https://merged.example/xmltv"
        val backup = "https://backup.example/xmltv"
        val wrote = refresh.loadMerged(
            serverIndex = 2,
            sources = listOf(own, backup),
            backupUrl = backup,
            backupIsPartial = true,
            channels = listOf(news, sports),
            fetch = { url -> if (url == own) withTimeout(0) { awaitCancellation() } else feed(news, "Backup News") },
            stillCurrent = { true }
        )
        assertEquals(1, wrote)
        assertEquals(listOf("Backup News"), titles(2, 7))
        assertEquals(listOf("Merged Sports"), titles(2, 8))
    }

    // ── Database failure: v7.21 behaviour (0, guide kept, caller carries on) ─────────────

    @Test
    fun databaseFailureKeepsTheGuideReturnsZeroAndTheNextRefreshStillWorks() = runBlocking {
        seed(row(-1, 7, "Old News"), row(-1, 8, "Old Sports"))
        val sql = db.openHelper.writableDatabase
        // A real SQLite error on insert, after the delete has run inside the transaction.
        sql.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON epg_entries BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END")
        var diffRecorded = false
        val wrote = load(after = { diffRecorded = true }) { feed(news, "New") }
        assertEquals(0, wrote)
        assertFalse(diffRecorded)
        assertEquals(listOf("Old News"), titles(-1, 7))
        assertEquals(listOf("Old Sports"), titles(-1, 8))

        sql.execSQL("DROP TRIGGER fail_insert")
        assertEquals(1, load { feed(news, "New") })
        assertEquals(listOf("New"), titles(-1, 7))
    }

    private fun keepsOn(
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>
    ) = runBlocking {
        seed(row(-1, 7, "Old News"), row(0, 7, "Other Provider"))
        val wrote = load(fetch = fetch)
        assertEquals(0, wrote)
        assertEquals(listOf("Old News"), titles(-1, 7))
        assertEquals(listOf("Other Provider"), titles(0, 7))
    }

    private suspend fun load(
        sources: List<String> = listOf("https://provider.example/xmltv"),
        backup: String? = null,
        channels: List<XmltvLocalChannel> = listOf(news, sports),
        still: suspend () -> Boolean = { true },
        before: suspend () -> Unit = {},
        after: suspend () -> Unit = {},
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>>
    ): Int = refresh.loadPrimary(sources, backup, channels, fetch, still, before, after)

    private suspend fun seed(vararg rows: EpgEntity) {
        db.epgDao().upsertEpg(rows.toList())
    }

    private fun titles(server: Int, stream: Int): List<String> {
        val cursor = db.query(
            SimpleSQLiteQuery(
                "SELECT title FROM epg_entries WHERE serverIndex = ? AND streamId = ? ORDER BY startTimestamp",
                arrayOf<Any>(server, stream)
            )
        )
        try {
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) out += cursor.getString(0)
            return out
        } finally {
            cursor.close()
        }
    }

    private fun row(server: Int, stream: Int, title: String, id: String = "keep-$server-$stream") = EpgEntity(
        serverIndex = server,
        id = id,
        streamId = stream,
        title = title,
        description = "",
        startTimestamp = startSec,
        stopTimestamp = stopSec,
        nowPlaying = 0,
        hasArchive = 0
    )

    private fun feed(channel: XmltvLocalChannel, title: String) =
        listOf(XmltvChannel(channel.epgChannelId!!, channel.name)) to listOf(
            XmltvProgram(channel.epgChannelId!!, title, "", startSec, stopSec)
        )
}
