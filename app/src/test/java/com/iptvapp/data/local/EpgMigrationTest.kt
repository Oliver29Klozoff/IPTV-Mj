package com.iptvapp.data.local

import android.app.Application
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Version 43 is the last shipped schema. 44 only adds guide indexes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate43To44KeepsRowsAndAddsGuideIndexes() {
        helper.createDatabase(NAME, 43).apply {
            execSQL(
                """
                INSERT INTO channels (
                    streamId, name, streamIcon, categoryId, epgChannelId, tvArchive, tvArchiveDuration,
                    num, isFavorite, lastWatched, cachedAt, streamUrl, favOrder, viewCount, isHidden,
                    favoriteFolderId, manualGenre, customNum
                ) VALUES (7, 'News', NULL, 'live', 'news.us', 0, 0, 1, 1, NULL, 10, NULL, 3, 4, 0, 1, NULL, NULL)
                """.trimIndent()
            )
            execSQL("INSERT INTO favorite_folders (id, name, sortOrder) VALUES (1, 'Sports', 0)")
            execSQL(
                """
                INSERT INTO merged_channels (
                    serverIndex, streamId, name, streamIcon, num, serverNickname, epgChannelId,
                    categoryId, categoryName, isFavorite, favoriteFolderId, cachedAt, isHidden,
                    favOrder, manualGenre, streamUrl
                ) VALUES (0, 9, 'Extra', NULL, 2, 'Backup TV', 'extra.id', 'c', 'News', 1, 1, 10, 0, 0, NULL, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO epg_entries (
                    serverIndex, id, streamId, title, description, startTimestamp, stopTimestamp, nowPlaying, hasArchive
                ) VALUES (-1, 'keep-primary', 7, 'Nightly', '', 100, 200, 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO epg_entries (
                    serverIndex, id, streamId, title, description, startTimestamp, stopTimestamp, nowPlaying, hasArchive
                ) VALUES (0, 'keep-merged', 9, 'Other', '', 100, 200, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 44, true, IptvDatabase.MIGRATION_43_44)
        assertEquals(listOf(INDEX), guideIndexes(migrated))

        assertEquals(listOf("News"), strings(migrated, "SELECT name FROM channels WHERE streamId = 7"))
        assertEquals(listOf("1"), strings(migrated, "SELECT isFavorite FROM channels WHERE streamId = 7"))
        assertEquals(listOf("Sports"), strings(migrated, "SELECT name FROM favorite_folders WHERE id = 1"))
        assertEquals(listOf("Backup TV"), strings(migrated, "SELECT serverNickname FROM merged_channels WHERE serverIndex = 0"))
        assertEquals(
            listOf("Nightly"),
            strings(
                migrated,
                """
                SELECT title FROM epg_entries
                WHERE serverIndex = -1 AND streamId = 7 AND startTimestamp <= 150 AND stopTimestamp >= 150
                """.trimIndent()
            )
        )
        assertEquals(listOf("Other"), strings(migrated, "SELECT title FROM epg_entries WHERE serverIndex = 0"))
        migrated.close()
    }

    @Test
    fun anEmptyGuideMigrates() {
        helper.createDatabase(NAME, 43).close()
        val migrated = helper.runMigrationsAndValidate(NAME, 44, true, IptvDatabase.MIGRATION_43_44)
        assertEquals(listOf("0"), strings(migrated, "SELECT COUNT(*) FROM epg_entries"))
        assertEquals(listOf(INDEX), guideIndexes(migrated))
        migrated.close()
    }

    @Test
    fun aLargeGuideMigratesWithEveryRowIntact() {
        helper.createDatabase(NAME, 43).apply {
            // 4 providers x 300 channels x 96 programmes = 115,200 rows.
            execSQL(
                """
                WITH RECURSIVE sv(v) AS (SELECT -1 UNION ALL SELECT v + 1 FROM sv WHERE v < 2),
                     ch(c) AS (SELECT 1 UNION ALL SELECT c + 1 FROM ch WHERE c < 300),
                     sl(s) AS (SELECT 0 UNION ALL SELECT s + 1 FROM sl WHERE s < 95)
                INSERT INTO epg_entries
                SELECT v, 'x_' || c || '_' || s, c, 'Programme ' || s, 'about it', 1000 + s * 3600, 1000 + (s + 1) * 3600, 0, 0
                FROM sv, ch, sl
                """.trimIndent()
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(NAME, 44, true, IptvDatabase.MIGRATION_43_44)
        assertEquals(listOf("115200"), strings(migrated, "SELECT COUNT(*) FROM epg_entries"))
        assertEquals(listOf(INDEX), guideIndexes(migrated))
        assertEquals(
            listOf("Programme 5"),
            strings(migrated, "SELECT title FROM epg_entries WHERE serverIndex = 1 AND streamId = 42 AND startTimestamp <= 19100 AND stopTimestamp >= 19100")
        )
        migrated.close()
    }

    @Test
    fun duplicateLookingRowsCannotBreakTheIndex() {
        // The index isn't unique: the same channel and start under different ids (two feeds), the
        // same id on two providers, and the same stream number on two providers all exist in real
        // caches, and none of them may stop the app from starting.
        helper.createDatabase(NAME, 43).apply {
            listOf(
                "(-1, 'a', 7, 'Feed A', '', 100, 200, 0, 0)",
                "(-1, 'b', 7, 'Feed B', '', 100, 200, 0, 0)",
                "(0, 'a', 7, 'Other A', '', 100, 200, 1, 0)",
                "(1, 'a', 7, 'Third A', '', 100, 200, 0, 1)"
            ).forEach {
                execSQL("INSERT INTO epg_entries (serverIndex, id, streamId, title, description, startTimestamp, stopTimestamp, nowPlaying, hasArchive) VALUES $it")
            }
            close()
        }
        val migrated = helper.runMigrationsAndValidate(NAME, 44, true, IptvDatabase.MIGRATION_43_44)
        assertEquals(
            listOf("Feed A", "Feed B"),
            strings(migrated, "SELECT title FROM epg_entries WHERE serverIndex = -1 AND streamId = 7 ORDER BY startTimestamp, id")
        )
        assertEquals(listOf("4"), strings(migrated, "SELECT COUNT(*) FROM epg_entries"))
        migrated.close()
    }

    private fun guideIndexes(db: SupportSQLiteDatabase) = strings(
        db,
        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'epg_entries' AND sql IS NOT NULL ORDER BY name"
    )

    private fun strings(db: SupportSQLiteDatabase, sql: String): List<String> {
        val cursor: Cursor = db.query(sql)
        try {
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) out += cursor.getString(0)
            return out
        } finally {
            cursor.close()
        }
    }

    companion object {
        private const val NAME = "epg-migration-43-44"
        private const val INDEX = "index_epg_entries_serverIndex_streamId_startTimestamp"
    }
}
