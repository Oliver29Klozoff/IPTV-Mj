package com.iptvapp.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.iptvapp.data.epg.EpgCatalog
import com.iptvapp.data.epg.EpgSamples
import com.iptvapp.data.local.entities.EpgOverrideEntity
import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Version 46 is the last shipped schema; 47 adds EPG Health's three tables and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgRepairMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate46To47KeepsTheGuideAndAddsRepairTables() {
        helper.createDatabase(NAME, 46).apply {
            execSQL("INSERT INTO epg_entries (serverIndex, id, streamId, title, description, startTimestamp, stopTimestamp, nowPlaying, hasArchive) VALUES (-1, 'x_amc_1', 7, 'Show', '', 1, 2, 0, 0)")
            close()
        }
        // Validates every new table and index against the exported 47 schema.
        val db = helper.runMigrationsAndValidate(NAME, 47, true, IptvDatabase.MIGRATION_46_47)
        db.query("SELECT title FROM epg_entries WHERE streamId = 7").use { it.moveToFirst(); assertEquals("Show", it.getString(0)) }
        db.execSQL("INSERT INTO epg_overrides (serverIndex, streamId, providerKey, mode, sourceKey, xmltvId, channelName, guideName, offsetMinutes, createdAt, updatedAt) VALUES (-1, 7, 'p', 'MAP', 's', 'amc.west', 'AMC', 'AMC West', 0, 1, 1)")
        // The same stream id on another provider is a separate correction.
        db.execSQL("INSERT INTO epg_overrides (serverIndex, streamId, providerKey, mode, sourceKey, xmltvId, channelName, guideName, offsetMinutes, createdAt, updatedAt) VALUES (0, 7, 'q', 'AUTO', NULL, NULL, 'AMC', NULL, 60, 1, 1)")
        db.query("SELECT COUNT(*) FROM epg_overrides WHERE streamId = 7").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
    }

    // 13: corrections survive closing and reopening the database (an app restart).
    @Test
    fun correctionsSurviveAReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(FILE)
        val first = Room.databaseBuilder(context, IptvDatabase::class.java, FILE).allowMainThreadQueries().build()
        first.epgRepairDao().upsertOverride(EpgOverrideEntity(-1, 7, "p", "MAP", "s", "amc.west", "AMC", "AMC West", 30, 1, 1))
        first.close()
        val second = Room.databaseBuilder(context, IptvDatabase::class.java, FILE).allowMainThreadQueries().build()
        assertEquals("amc.west", second.epgRepairDao().getOverride(-1, 7)!!.xmltvId)
        assertEquals(30, second.epgRepairDao().getOverride(-1, 7)!!.offsetMinutes)
        second.close()
        context.deleteDatabase(FILE)
        Unit
    }

    @Test
    fun aCatalogKeepsAFewUpcomingListingsPerChannelAndCountsTheRest() {
        val now = 1_800_000_000L
        val programs = (0 until 50).map { XmltvProgram("amc", "Show $it", "", now + it * 600L, now + it * 600L + 600, zoneless = it % 2 == 0) } +
            XmltvProgram("amc", "", "", now, now + 60) + // no title
            XmltvProgram("amc", "Backwards", "", now + 60, now) + // ends before it starts
            XmltvProgram("undeclared", "Orphan", "", now, now + 60)
        val (rows, stats) = EpgCatalog.build(-1, "s", listOf(XmltvChannel("amc", "AMC"), XmltvChannel("amc", "AMC again")), programs, now)
        assertEquals(2, rows.size) // "amc" once (its first name), and the undeclared id
        val amc = rows.single { it.xmltvId == "amc" }
        assertEquals("AMC", amc.displayName)
        assertEquals(50, amc.programCount)
        assertEquals(listOf("Show 0", "Show 1", "Show 2"), EpgSamples.decode(amc.sample).map { it.title })
        assertEquals(2, stats.invalidProgramCount)
        assertEquals(25, stats.zonelessProgramCount)
        assertEquals(now + 49 * 600L + 600, stats.horizonEnd)
        assertTrue(rows.single { it.xmltvId == "undeclared" }.displayName == "undeclared")
    }

    private companion object {
        const val NAME = "epg-repair-migration-test"
        const val FILE = "epg-repair-reopen-test"
    }
}
