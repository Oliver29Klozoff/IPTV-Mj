package com.iptvapp.reliability

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.dao.isTornRead
import com.iptvapp.data.local.dao.retryOnTornRead
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.ui.home.LatestOnlyListAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Prompt 12: a large catalog refresh with its list on screen (torn reads, diff backlog). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Prompt12CatalogRefreshTest {

    private fun torn() = IllegalStateException(
        "Couldn't read row 2709, col 0 from CursorWindow.  Make sure the Cursor is initialized correctly before accessing data from it."
    )

    @Test
    fun aTornReadIsReadAgain() = runTest {
        var reads = 0
        val result = flow {
            reads++
            if (reads <= 2) throw torn()
            emit(listOf(1, 2, 3))
        }.retryOnTornRead().first()
        assertEquals(listOf(1, 2, 3), result)
        assertEquals(3, reads)
    }

    @Test
    fun otherErrorsAreNotRetried() = runTest {
        var reads = 0
        try {
            flow<Int> { reads++; throw IllegalStateException("something else") }.retryOnTornRead().first()
            fail("expected the error")
        } catch (e: IllegalStateException) {
            assertEquals("something else", e.message)
        }
        assertEquals(1, reads)
        assertFalse(isTornRead(IllegalArgumentException("from CursorWindow")))
        assertTrue(isTornRead(torn()))
    }

    @Test
    fun aCursorThatKeepsFailingStillFails() = runTest {
        var reads = 0
        try {
            flow<Int> { reads++; throw torn() }.retryOnTornRead(maxAttempts = 3).first()
            fail("expected the error")
        } catch (e: IllegalStateException) {
            assertTrue(isTornRead(e))
        }
        assertEquals(4, reads) // the first read and three retries
    }

    @Test
    fun theDaoWrappersStillReadTheTable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IptvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.vodDao().upsertVod((1..5).map {
                VodEntity(streamId = it, name = "Movie $it", streamIcon = null, categoryId = "1", rating = null, containerExtension = "mkv", added = "$it")
            })
            assertEquals(listOf(5, 4, 3, 2, 1), db.vodDao().getAllVod().first().map { it.streamId })
            assertEquals(5, db.vodDao().getAllVodRaw().first().size)
        } finally {
            db.close()
        }
    }

    // A diff runs on the calling thread here, and its result is delivered when the main looper runs.
    private class Adapter : LatestOnlyListAdapter<Int, RecyclerView.ViewHolder>(
        AsyncDifferConfig.Builder(object : DiffUtil.ItemCallback<Int>() {
            override fun areItemsTheSame(a: Int, b: Int) = a == b
            override fun areContentsTheSame(a: Int, b: Int) = a == b
        }).setBackgroundThreadExecutor { it.run() }.build()
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(View(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    @Test
    fun onlyTheNewestWaitingListIsDiffed() {
        val adapter = Adapter()
        val first = listOf(1, 2, 3)
        adapter.submitList(first) // into an empty adapter: committed at once, no diff
        assertEquals(first, adapter.currentList)

        val seen = mutableListOf<List<Int>>()
        val a = listOf(1, 2, 3, 4)
        val b = listOf(1, 2, 3, 4, 5)
        val c = listOf(2, 3, 4, 5, 6)
        adapter.submitList(a) { seen += adapter.currentList } // diff starts
        adapter.submitList(b) { seen += adapter.currentList } // waits
        adapter.submitList(c) { seen += adapter.currentList } // replaces b, which is never diffed
        assertEquals(first, adapter.currentList)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(c, adapter.currentList)
        // a's callback saw a; b's and c's both ran once c was committed. b was never on screen.
        assertEquals(listOf(a, c, c), seen)
    }

    // A list submitted from a commit callback is the newest: it must not be overtaken by an older
    // list that was already waiting.
    private fun callbackSubmits(fromCallback: (Adapter) -> List<Int>?): List<Int> {
        val adapter = Adapter()
        adapter.submitList(listOf(1))
        adapter.submitList(listOf(1, 9)) { adapter.submitList(fromCallback(adapter)) } // diffing
        adapter.submitList(listOf(2)) // waiting, older than whatever the callback submits
        shadowOf(Looper.getMainLooper()).idle()
        return adapter.currentList
    }

    @Test
    fun aListSubmittedFromACommitCallbackBeatsAnOlderWaitingOne() {
        assertEquals(listOf(3), callbackSubmits { listOf(3) })
    }

    @Test
    fun aNullSubmittedFromACommitCallbackBeatsAnOlderWaitingOne() {
        assertEquals(emptyList<Int>(), callbackSubmits { null })
    }

    @Test
    fun theSameListSubmittedFromACommitCallbackBeatsAnOlderWaitingOne() {
        assertEquals(listOf(1, 9), callbackSubmits { it.currentList })
    }

    @Test
    fun withNothingRunningAListIsDiffedRightAway() {
        val adapter = Adapter()
        adapter.submitList(listOf(1))
        var done = false
        adapter.submitList(listOf(1, 2)) { done = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(done)
        assertEquals(listOf(1, 2), adapter.currentList)
        // And again, now that the first diff has finished.
        adapter.submitList(listOf(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2), adapter.currentList)
    }

    // A few search results after a whole catalog (or back): shown at once, not after a diff that
    // takes minutes, even while another diff is still running.
    @Test
    fun aListFarLargerOrSmallerIsShownAtOnceWithoutADiff() {
        val adapter = Adapter()
        val catalog = (1..5_000).toList()
        adapter.submitList(listOf(1, 2, 3))
        val seen = mutableListOf<String>()
        adapter.submitList(listOf(1, 2, 3, 4)) { seen += "running diff" } // diff starts
        adapter.submitList(listOf(1, 2)) { seen += "waiting" } // waits
        adapter.submitList(catalog) { seen += "catalog" }
        // No looper run needed: it's already on screen, and the replaced lists' callbacks ran.
        assertEquals(catalog, adapter.currentList)
        assertEquals(listOf("running diff", "waiting", "catalog"), seen)

        val results = listOf(7, 42)
        adapter.submitList(results)
        assertEquals(results, adapter.currentList)

        // The overtaken diff's late result doesn't land, and ordinary diffs still work.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(results, adapter.currentList)
        adapter.submitList(listOf(7, 42, 99))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(7, 42, 99), adapter.currentList)
    }

    @Test
    fun aLargeListSubmittedFromACommitCallbackIsShownAtOnce() {
        val adapter = Adapter()
        val catalog = (1..5_000).toList()
        adapter.submitList(listOf(1))
        adapter.submitList(listOf(1, 9)) { adapter.submitList(catalog) }
        adapter.submitList(listOf(2)) // waiting, older than the catalog
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(catalog, adapter.currentList)
    }
}
