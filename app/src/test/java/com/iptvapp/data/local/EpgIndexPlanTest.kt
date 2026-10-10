package com.iptvapp.data.local

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Query plans for the guide reads (the DAO's own SQL), on the v43 table before and after
 * MIGRATION_43_44. SQLite picks a plan from the schema, not the row count (no ANALYZE), so a
 * small table gives the same plans as a large one; timings on 432k rows are in the Prompt 5
 * report, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgIndexPlanTest {

    @Test
    fun guideReadsUseTheIndexAndScansStayScans() {
        val db = open(ApplicationProvider.getApplicationContext())
        try {
            insert(db)
            val before = QUERIES.mapValues { (_, q) -> explain(db, q) }
            assertTrue(before.getValue("channelGuide").contains("USE TEMP B-TREE FOR ORDER BY"))
            assertTrue(before.getValue("favorites").contains("USE TEMP B-TREE FOR ORDER BY"))

            IptvDatabase.MIGRATION_43_44.migrate(db)
            val after = QUERIES.mapValues { (_, q) -> explain(db, q) }

            listOf("nowNextOne", "nowPlaying", "channelGuide", "favorites", "otherProvider", "streamIdsWithEpg").forEach {
                assertTrue("$it: ${after.getValue(it)}", after.getValue(it).contains(INDEX))
                assertFalse("$it: ${after.getValue(it)}", after.getValue(it).contains("TEMP B-TREE"))
            }
            // Reads that cover most of the table stay plain scans: an index here made them slower.
            listOf("upcomingTitles", "expired").forEach {
                val plan = after.getValue(it)
                assertTrue("$it: $plan", Regex("SCAN (TABLE )?epg_entries").containsMatchIn(plan))
                assertFalse("$it: $plan", plan.contains("INDEX"))
            }
            assertEquals(
                listOf(INDEX),
                strings(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'epg_entries' AND sql IS NOT NULL")
            )
        } finally {
            db.close()
        }
    }

    private fun explain(db: SupportSQLiteDatabase, sql: String): String {
        val cursor = db.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql"))
        try {
            val lines = mutableListOf<String>()
            while (cursor.moveToNext()) lines += cursor.getString(cursor.columnCount - 1)
            return lines.joinToString("\n")
        } finally {
            cursor.close()
        }
    }

    private fun strings(db: SupportSQLiteDatabase, sql: String): List<String> {
        val cursor = db.query(sql)
        try {
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) out += cursor.getString(0)
            return out
        } finally {
            cursor.close()
        }
    }

    private fun insert(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            WITH RECURSIVE sv(v) AS (SELECT -1 UNION ALL SELECT v + 1 FROM sv WHERE v < 1),
                 ch(c) AS (SELECT 1 UNION ALL SELECT c + 1 FROM ch WHERE c < 50),
                 sl(s) AS (SELECT 0 UNION ALL SELECT s + 1 FROM sl WHERE s < 47)
            INSERT INTO epg_entries
            SELECT v, 'x_' || c || '_' || s, c, 'Programme ' || s, '', $NOW + (s - 2) * 3600, $NOW + (s - 1) * 3600, 0, 0
            FROM sv, ch, sl
            """.trimIndent()
        )
    }

    private fun open(context: Context): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            // epg_entries exactly as v43 (schemas/43.json) created it.
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_entries` (`serverIndex` INTEGER NOT NULL, `id` TEXT NOT NULL, " +
                        "`streamId` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`startTimestamp` INTEGER NOT NULL, `stopTimestamp` INTEGER NOT NULL, `nowPlaying` INTEGER NOT NULL, " +
                        "`hasArchive` INTEGER NOT NULL, PRIMARY KEY(`serverIndex`, `id`))"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(callback).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    companion object {
        private const val INDEX = "index_epg_entries_serverIndex_streamId_startTimestamp"
        private const val NOW = 1_760_000_000L

        // The SQL of EpgDao's guide reads, with arguments filled in.
        private val QUERIES: Map<String, String> = linkedMapOf(
            "nowNextOne" to "SELECT * FROM epg_entries WHERE serverIndex = -1 AND streamId = 7 AND startTimestamp <= $NOW AND stopTimestamp >= $NOW LIMIT 1",
            "nowPlaying" to "SELECT * FROM epg_entries WHERE serverIndex = -1 AND streamId = 7 AND nowPlaying = 1 LIMIT 1",
            "channelGuide" to "SELECT * FROM epg_entries WHERE serverIndex = -1 AND streamId = 7 ORDER BY startTimestamp ASC",
            "favorites" to "SELECT * FROM epg_entries WHERE serverIndex = -1 AND streamId IN (1,2,3,4,5,6,7,8,9,10) ORDER BY streamId ASC, startTimestamp ASC",
            "otherProvider" to "SELECT * FROM epg_entries WHERE serverIndex = 0 AND streamId IN (3,4,5) ORDER BY streamId ASC, startTimestamp ASC",
            "streamIdsWithEpg" to "SELECT DISTINCT streamId FROM epg_entries WHERE serverIndex = -1",
            "upcomingTitles" to "SELECT serverIndex, streamId, title, startTimestamp, stopTimestamp FROM epg_entries WHERE stopTimestamp > $NOW",
            "expired" to "SELECT COUNT(*) FROM epg_entries WHERE stopTimestamp < $NOW"
        )
    }
}
