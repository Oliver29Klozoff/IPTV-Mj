package com.iptvapp.data.local

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Query plans for the guide reads, on a 100_000-row table, before and after the indexes
 * migration 43 to 44 adds. Timings are written beside the plans; the assertions are that
 * SQLite actually uses the indexes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgIndexPlanTest {

    @Test
    fun indexesAreUsedForGuideReads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plain = open(context, withIndexes = false)
        val report = StringBuilder()
        try {
            val plainInsertMs = insert(plain)
            report.append("rows=${COUNT}\n")
            report.append("insert without indexes: ${plainInsertMs} ms\n")
            val before = plans(plain)
            report.append("\n-- before indexes --\n")
            before.forEach { (name, plan) -> report.append("$name\n$plan\n\n") }
            val beforeMs = timeQueries(plain)
            report.append("-- before query time (ms, mean of 5) --\n")
            beforeMs.forEach { (name, ms) -> report.append("$name: $ms\n") }
            before.values.forEach { plan ->
                assertFalse(plan.contains("index_epg_entries_serverIndex"))
                assertFalse(plan.contains("index_epg_entries_stopTimestamp"))
            }

            val indexSql = listOf(
                "CREATE INDEX IF NOT EXISTS `index_epg_entries_serverIndex_streamId_startTimestamp` ON `epg_entries` (`serverIndex`, `streamId`, `startTimestamp`)",
                "CREATE INDEX IF NOT EXISTS `index_epg_entries_serverIndex_startTimestamp` ON `epg_entries` (`serverIndex`, `startTimestamp`)",
                "CREATE INDEX IF NOT EXISTS `index_epg_entries_stopTimestamp` ON `epg_entries` (`stopTimestamp`)"
            )
            val indexBuildStart = System.nanoTime()
            indexSql.forEach { plain.execSQL(it) }
            report.append("build indexes on existing rows: ${(System.nanoTime() - indexBuildStart) / 1_000_000} ms\n\n")

            val after = plans(plain)
            report.append("-- after indexes --\n")
            after.forEach { (name, plan) -> report.append("$name\n$plan\n\n") }

            assertTrue(after.getValue("nowNextOne").contains("index_epg_entries_serverIndex_streamId_startTimestamp"))
            assertTrue(after.getValue("guideGrid").contains("index_epg_entries_serverIndex_streamId_startTimestamp"))
            assertTrue(after.getValue("nowNextMany").contains("index_epg_entries_serverIndex_streamId_startTimestamp"))
            assertTrue(after.getValue("airing").contains("index_epg_entries_serverIndex_startTimestamp"))
            assertTrue(after.getValue("upcoming").contains("index_epg_entries_stopTimestamp"))
            assertTrue(after.getValue("expiry").contains("index_epg_entries_stopTimestamp"))
            assertFalse(after.getValue("guideGrid").contains("USE TEMP B-TREE"))

            val timed = timeQueries(plain)
            report.append("-- after query time (ms, mean of 5) --\n")
            timed.forEach { (name, ms) -> report.append("$name: $ms\n") }
        } finally {
            plain.close()
            saveReport(report)
        }

        val indexed = open(context, withIndexes = true)
        try {
            val indexedInsertMs = insert(indexed)
            report.append("\ninsert with indexes already present: ${indexedInsertMs} ms\n")
            val countCursor = indexed.query("SELECT COUNT(*) FROM epg_entries")
            val count = try {
                countCursor.moveToFirst()
                countCursor.getInt(0)
            } finally {
                countCursor.close()
            }
            assertTrue(count == COUNT)
        } finally {
            indexed.close()
            saveReport(report)
        }
    }

    private fun saveReport(report: StringBuilder) {
        val text = report.toString()
        println(text)
        val module = when {
            File("schemas").isDirectory -> File(".").absoluteFile
            else -> File("app").absoluteFile
        }
        val out = File(module, "build/reports/epg-index-plan.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)
    }

    private fun plans(db: SupportSQLiteDatabase): Map<String, String> =
        QUERIES.mapValues { (_, sql) -> explain(db, sql.first, sql.second) }

    private fun timeQueries(db: SupportSQLiteDatabase): Map<String, Long> =
        QUERIES.mapValues { (_, sql) ->
            val args = sql.second
            val statement = sql.first
            repeat(2) { consume(db, statement, args) }
            val start = System.nanoTime()
            repeat(5) { consume(db, statement, args) }
            (System.nanoTime() - start) / 5 / 1_000_000
        }

    private fun explain(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>): String {
        val cursor = db.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args))
        try {
            val lines = mutableListOf<String>()
            while (cursor.moveToNext()) lines += cursor.getString(cursor.columnCount - 1)
            return lines.joinToString("\n")
        } finally {
            cursor.close()
        }
    }

    private fun consume(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>) {
        val cursor = db.query(SimpleSQLiteQuery(sql, args))
        try {
            while (cursor.moveToNext()) cursor.getString(0)
        } finally {
            cursor.close()
        }
    }

    private fun insert(db: SupportSQLiteDatabase): Long {
        val stmt = db.compileStatement(
            "INSERT INTO epg_entries (serverIndex, id, streamId, title, description, startTimestamp, stopTimestamp, nowPlaying, hasArchive) " +
                "VALUES (?, ?, ?, ?, '', ?, ?, 0, 0)"
        )
        val start = System.nanoTime()
        db.beginTransaction()
        try {
            var i = 0
            while (i < COUNT) {
                val server = if (i < PRIMARY_ROWS) -1 else 0
                val local = if (server == -1) i else i - PRIMARY_ROWS
                val stream = (local / PER_CHANNEL) + 1
                val slot = local % PER_CHANNEL
                val startSec = BASE + slot * SLOT_SEC
                stmt.clearBindings()
                stmt.bindLong(1, server.toLong())
                stmt.bindString(2, "p$i")
                stmt.bindLong(3, stream.toLong())
                stmt.bindString(4, "Program $i")
                stmt.bindLong(5, startSec)
                stmt.bindLong(6, startSec + SLOT_SEC)
                stmt.executeInsert()
                i++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun open(context: Context, withIndexes: Boolean): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE epg_entries (
                        serverIndex INTEGER NOT NULL,
                        id TEXT NOT NULL,
                        streamId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        startTimestamp INTEGER NOT NULL,
                        stopTimestamp INTEGER NOT NULL,
                        nowPlaying INTEGER NOT NULL,
                        hasArchive INTEGER NOT NULL,
                        PRIMARY KEY(serverIndex, id)
                    )
                    """.trimIndent()
                )
                if (withIndexes) {
                    db.execSQL("CREATE INDEX `index_epg_entries_serverIndex_streamId_startTimestamp` ON `epg_entries` (`serverIndex`, `streamId`, `startTimestamp`)")
                    db.execSQL("CREATE INDEX `index_epg_entries_serverIndex_startTimestamp` ON `epg_entries` (`serverIndex`, `startTimestamp`)")
                    db.execSQL("CREATE INDEX `index_epg_entries_stopTimestamp` ON `epg_entries` (`stopTimestamp`)")
                }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    companion object {
        private const val PER_CHANNEL = 200
        private const val PRIMARY_CHANNELS = 400
        private const val OTHER_CHANNELS = 100
        private const val PRIMARY_ROWS = PRIMARY_CHANNELS * PER_CHANNEL
        private const val COUNT = PRIMARY_ROWS + OTHER_CHANNELS * PER_CHANNEL
        private const val BASE = 1_700_000_000L
        private const val SLOT_SEC = 1_800L
        private val NOW = BASE + 20 * SLOT_SEC
        private val MANY_IDS = (1..40).joinToString(",")

        private val QUERIES: Map<String, Pair<String, Array<Any?>>> = linkedMapOf(
            "nowNextOne" to (
                "SELECT title FROM epg_entries WHERE serverIndex = ? AND streamId = ? AND startTimestamp <= ? AND stopTimestamp >= ? LIMIT 1"
                    to arrayOf<Any?>(-1, 1, NOW, NOW)
                ),
            "nowNextMany" to (
                "SELECT title FROM epg_entries WHERE serverIndex = -1 AND streamId IN ($MANY_IDS) ORDER BY streamId ASC, startTimestamp ASC"
                    to emptyArray<Any?>()
                ),
            "guideGrid" to (
                "SELECT title FROM epg_entries WHERE serverIndex = ? AND streamId = ? ORDER BY startTimestamp ASC"
                    to arrayOf<Any?>(-1, 1)
                ),
            "airing" to (
                "SELECT title FROM epg_entries WHERE serverIndex = ? AND startTimestamp <= ? AND stopTimestamp >= ?"
                    to arrayOf<Any?>(-1, NOW, NOW)
                ),
            "upcoming" to (
                "SELECT title FROM epg_entries WHERE stopTimestamp > ?"
                    to arrayOf<Any?>(NOW)
                ),
            "expiry" to (
                "SELECT title FROM epg_entries WHERE stopTimestamp < ?"
                    to arrayOf<Any?>(BASE)
                )
        )
    }
}
