package com.iptvapp.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Movie and series catalog replacement, without the network.
 * A refresh that lost its turn must not delete what is already stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrimaryCatalogRefreshTest {

    private lateinit var db: IptvDatabase
    private lateinit var refresh: PrimaryCatalogRefresh

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java
        ).allowMainThreadQueries().build()
        refresh = PrimaryCatalogRefresh(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun movieRefreshDropsTitlesTheProviderNoLongerListsAndKeepsProgress() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "Kept", favorite = true, watched = 40), movie(2, "Gone")))
        val wrote = refresh.replaceVod(listOf(movie(1, "Kept", favorite = true, watched = 40)), { true }, { it })
        assertTrue(wrote)
        assertEquals(listOf(1), vodIds())
        val kept = db.vodDao().getVodByStreamId(1)!!
        assertTrue(kept.isFavorite)
        assertEquals(40L, kept.watchedMs)
    }

    @Test
    fun aFavoriteOrStartedTitleTheProviderLeavesOutKeepsItsRow() = runBlocking {
        // Providers drop titles for a while and bring them back; the user's favorite and progress
        // live on these rows, so only titles with nothing of the user's on them are removed.
        db.vodDao().upsertVod(listOf(movie(1, "Fav", favorite = true), movie(2, "Started", watched = 90), movie(3, "Plain")))
        db.seriesDao().upsertSeries(listOf(show(10, "Hidden", hidden = true), show(11, "Plain")))
        assertTrue(refresh.replaceVod(listOf(movie(4, "New")), { true }, { it }))
        assertTrue(refresh.replaceSeries(listOf(show(12, "New")), { true }, { it }))
        assertEquals(listOf(1, 2, 4), vodIds())
        assertEquals(listOf(10, 12), seriesIds())
        assertEquals(90L, db.vodDao().getVodByStreamId(2)!!.watchedMs)
    }

    @Test
    fun emptyMovieListClearsTheCatalog() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "Old")))
        assertTrue(refresh.replaceVod(emptyList<VodEntity>(), { true }, { it }))
        assertEquals(emptyList<Int>(), vodIds())
    }

    @Test
    fun abortedMovieRefreshLeavesTheCatalogAlone() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "Old")))
        assertFalse(refresh.replaceVod(listOf(movie(2, "New")), { false }, { it }))
        assertEquals(listOf(1), vodIds())
    }

    @Test
    fun abortingAfterTheFirstChunkDoesNotSweep() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(9_999, "Old")))
        var checks = 0
        val wrote = refresh.replaceVod((1..2001).map { movie(it, "N$it") }, {
            checks += 1
            checks == 1
        }, { it })
        assertFalse(wrote)
        val ids = vodIds()
        assertTrue(9_999 in ids)
        assertTrue(1 in ids)
        assertTrue(2000 in ids)
        assertFalse(2001 in ids)
    }

    @Test
    fun moreThanFiveHundredStaleMoviesAreAllRemoved() = runBlocking {
        db.vodDao().upsertVod((1..501).map { movie(it, "M$it") })
        assertTrue(refresh.replaceVod(listOf(movie(1, "M1")), { true }, { it }))
        assertEquals(listOf(1), vodIds())
    }

    @Test
    fun seriesRefreshDropsRemovedShowsAndKeepsOneThatRemains() = runBlocking {
        db.seriesDao().upsertSeries(listOf(show(3, "Stay", hidden = true), show(4, "Leave")))
        assertTrue(refresh.replaceSeries(listOf(show(3, "Stay", hidden = true)), { true }, { it }))
        assertEquals(listOf(3), seriesIds())
        assertTrue(db.seriesDao().getSeriesById(3)!!.isHidden)
    }

    @Test
    fun emptySeriesListClearsTheCatalog() = runBlocking {
        db.seriesDao().upsertSeries(listOf(show(3, "Old")))
        assertTrue(refresh.replaceSeries(emptyList<SeriesEntity>(), { true }, { it }))
        assertEquals(emptyList<Int>(), seriesIds())
    }

    @Test
    fun vodCategoryReplaceDropsStaleVodCategoriesAndLeavesLive() = runBlocking {
        db.categoryDao().upsertCategories(listOf(
            CategoryEntity("old", "Old", 0, "vod"),
            CategoryEntity("live", "Live", 0, "live")
        ))
        assertTrue(refresh.replaceCategories("vod", listOf(CategoryEntity("new", "New", 0, "vod")), { true }))
        assertEquals(setOf("new"), categoryIds("vod"))
        assertEquals(setOf("live"), categoryIds("live"))
    }

    @Test
    fun abortedCategoryReplaceDeletesNothing() = runBlocking {
        db.categoryDao().upsertCategories(listOf(CategoryEntity("old", "Old", 0, "vod")))
        assertFalse(refresh.replaceCategories("vod", listOf(CategoryEntity("new", "New", 0, "vod")), { false }))
        assertEquals(setOf("old"), categoryIds("vod"))
    }

    @Test
    fun emptyCategoryListClearsOnlyThatType() = runBlocking {
        db.categoryDao().upsertCategories(listOf(
            CategoryEntity("vod", "Vod", 0, "vod"),
            CategoryEntity("live", "Live", 0, "live")
        ))
        assertTrue(refresh.replaceCategories("vod", emptyList(), { true }))
        assertEquals(emptySet<String>(), categoryIds("vod"))
        assertEquals(setOf("live"), categoryIds("live"))
    }

    @Test
    fun staleDeletionStopsWhenTheRefreshLosesItsTurn() = runBlocking {
        // Plain rows: favorited ones are never stale-deleted at all (see the test above).
        db.vodDao().upsertVod((1..600).map { movie(it, "M$it") })
        var checks = 0
        val wrote = refresh.replaceVod(listOf(movie(1, "M1")), {
            checks += 1
            checks < 4
        }, { it })
        assertFalse(wrote)
        assertEquals(600, vodIds().size)
        assertTrue(2 in vodIds())
    }

    @Test
    fun staleDeletionStopsBetweenChunks() = runBlocking {
        db.vodDao().upsertVod((1..600).map { movie(it, "M$it") })
        var checks = 0
        val wrote = refresh.replaceVod(listOf(movie(1, "M1")), {
            checks += 1
            checks < 5
        }, { it })
        assertFalse(wrote)
        assertEquals(100, vodIds().size)
        assertTrue(1 in vodIds())
    }

    @Test
    fun aCategoryRefreshDoesNotCancelAnInFlightMovieRefresh() = runBlocking {
        withTimeout(5_000) {
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val titles = async {
                    refresh.runVod { still ->
                        started.complete(Unit)
                        release.await()
                        still()
                    }
                }
                started.await()
                assertEquals("categories", refresh.runVodCategories { "categories" })
                release.complete(Unit)
                assertEquals(true, titles.await())
            }
        }
    }

    @Test
    fun aNewerRefreshWinsAndTheOlderOneDoesNotWrite() = runBlocking {
        withTimeout(5_000) {
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val older = async {
                    refresh.runVod { still ->
                        started.complete(Unit)
                        while (still()) yield()
                        false
                    }
                }
                started.await()
                val newer = async { refresh.runVod { "newer" } }
                assertEquals(false, older.await())
                assertEquals("newer", newer.await())
            }
        }
    }

    @Test
    fun providerSwitchCancelsDownloadsWithoutWaitingForThem() = runBlocking {
        withTimeout(5_000) {
            coroutineScope {
                val vodIn = CompletableDeferred<Unit>()
                val seriesIn = CompletableDeferred<Unit>()
                val vodCategoriesIn = CompletableDeferred<Unit>()
                val seriesCategoriesIn = CompletableDeferred<Unit>()
                val vod = async {
                    refresh.runVod {
                        vodIn.complete(Unit)
                        awaitCancellation()
                        "finished"
                    } ?: "stopped"
                }
                val series = async {
                    refresh.runSeries {
                        seriesIn.complete(Unit)
                        awaitCancellation()
                        "finished"
                    } ?: "stopped"
                }
                val vodCategories = async {
                    refresh.runVodCategories {
                        vodCategoriesIn.complete(Unit)
                        awaitCancellation()
                        "finished"
                    } ?: "stopped"
                }
                val seriesCategories = async {
                    refresh.runSeriesCategories {
                        seriesCategoriesIn.complete(Unit)
                        awaitCancellation()
                        "finished"
                    } ?: "stopped"
                }
                vodIn.await()
                seriesIn.await()
                vodCategoriesIn.await()
                seriesCategoriesIn.await()
                refresh.supersede()
                assertEquals("cleared", refresh.afterIdle { "cleared" })
                assertEquals("stopped", vod.await())
                assertEquals("stopped", series.await())
                assertEquals("stopped", vodCategories.await())
                assertEquals("stopped", seriesCategories.await())
                assertFalse(vod.isCancelled)
                assertFalse(series.isCancelled)
                assertFalse(vodCategories.isCancelled)
                assertFalse(seriesCategories.isCancelled)
            }
        }
    }

    @Test
    fun aRefreshStartedAfterSupersedeStillRuns() = runBlocking {
        refresh.supersede()
        assertEquals("ok", refresh.runVod { "ok" })
    }

    @Test
    fun cancellingAnInFlightRefreshStopsIt() = runBlocking {
        withTimeout(5_000) {
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val job = launch {
                    refresh.runVod {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                started.await()
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
            }
        }
    }

    private suspend fun vodIds() = db.vodDao().getUserData().map { it.streamId }.sorted()
    private suspend fun seriesIds() = db.seriesDao().getUserData().map { it.seriesId }.sorted()
    private suspend fun categoryIds(type: String) = db.categoryDao().getIdsByType(type).toSet()

    private fun movie(id: Int, name: String, favorite: Boolean = false, watched: Long = 0) = VodEntity(
        streamId = id,
        name = name,
        streamIcon = null,
        categoryId = "1",
        rating = null,
        containerExtension = "mkv",
        added = "1",
        isFavorite = favorite,
        watchedMs = watched,
        durationMs = if (watched > 0) 100 else 0
    )

    private fun show(id: Int, name: String, hidden: Boolean = false) = SeriesEntity(
        seriesId = id,
        name = name,
        cover = null,
        plot = null,
        genre = null,
        rating = null,
        categoryId = "1",
        isHidden = hidden
    )
}
