package com.iptvapp.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.entities.WatchLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WatchLogDaoTest {
    private lateinit var db: IptvDatabase
    private val dao get() = db.watchLogDao()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), IptvDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun `retiring two slots back to back keeps both histories apart`() = runBlocking {
        dao.upsert(WatchLogEntity("2026-10-28", -1, 5, "ESPN", 10f))
        dao.upsert(WatchLogEntity("2026-10-28", 0, 5, "FOX", 20f))
        // A primary switch retires the primary and the promoted slot in one go.
        dao.retire(-1); dao.retire(0)
        val rows = dao.since("2026-01-01")
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.serverIndex }.distinct().size)
        assertTrue(rows.all { it.serverIndex < -1 })
    }

    @Test fun `removing a provider retires it and moves later ones down`() = runBlocking {
        dao.upsert(WatchLogEntity("2026-10-28", 0, 5, "A", 1f))
        dao.upsert(WatchLogEntity("2026-10-28", 1, 5, "B", 2f))
        dao.upsert(WatchLogEntity("2026-10-28", 2, 5, "C", 3f))
        dao.removeServer(1)
        val byName = dao.since("2026-01-01").associate { it.name to it.serverIndex }
        assertEquals(0, byName["A"])
        assertEquals(1, byName["C"])
        assertTrue(byName.getValue("B") < -1)
    }
}
