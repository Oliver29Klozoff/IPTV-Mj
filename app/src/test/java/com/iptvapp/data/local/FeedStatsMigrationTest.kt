package com.iptvapp.data.local

import android.app.Application
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Version 44 is the last shipped schema. 45 only adds feed scores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FeedStatsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate44To45KeepsReliabilityAndAddsFeedStats() {
        helper.createDatabase(NAME, 44).apply {
            execSQL("INSERT INTO channel_reliability (streamId, outcomes, lastUpdated) VALUES (7, '1101', 10)")
            close()
        }

        // Validates the new table against the exported 45 schema.
        val migrated = helper.runMigrationsAndValidate(NAME, 45, true, IptvDatabase.MIGRATION_44_45)
        migrated.query("SELECT outcomes FROM channel_reliability WHERE streamId = 7").use {
            it.moveToFirst()
            assertEquals("1101", it.getString(0))
        }
        migrated.execSQL(
            "INSERT INTO feed_stats (serverIndex, streamId, outcomes, tuneMs, stallsPerHour, watchedMinutes, lastFailureAt, updatedAt) " +
                "VALUES (0, 7, '1', 1200, 0, 5, 0, 10)"
        )
        // Same stream id on another provider is a separate copy.
        migrated.execSQL(
            "INSERT INTO feed_stats (serverIndex, streamId, outcomes, tuneMs, stallsPerHour, watchedMinutes, lastFailureAt, updatedAt) " +
                "VALUES (-1, 7, '0', 0, 0, 0, 20, 20)"
        )
        migrated.query("SELECT COUNT(*) FROM feed_stats WHERE streamId = 7").use {
            it.moveToFirst()
            assertEquals(2, it.getInt(0))
        }
    }

    private companion object {
        const val NAME = "feed-stats-migration-test"
    }
}
