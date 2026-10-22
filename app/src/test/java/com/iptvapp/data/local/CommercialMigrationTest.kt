package com.iptvapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Version 47 is the shipped schema. 48 adds commercial markers and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommercialMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate47To48AddsMarkersWithoutTouchingRecordings() {
        helper.createDatabase(NAME, 47).apply {
            execSQL(
                "INSERT INTO recordings (streamId, channelName, scheduledStartMs, durationMs, outputPath, status, serverIndex) " +
                    "VALUES (3, 'News', 10, 60000, '/tmp/a.ts', 'DONE', -1)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(NAME, 48, true, IptvDatabase.MIGRATION_47_48)
        db.query("SELECT channelName FROM recordings").use {
            it.moveToFirst()
            assertEquals("News", it.getString(0))
        }
        db.execSQL(
            "INSERT INTO commercial_segments (recordingId, startMs, endMs, origin, review, evidence, analysisVersion, fileSize, fileModifiedMs, updatedAtMs) " +
                "VALUES (1, 12000, 15000, 'MANUAL', 'CONFIRMED', 'manual', 1, 100, 50, 7)"
        )
        db.execSQL(
            "INSERT INTO commercial_segments (recordingId, startMs, endMs, origin, review, evidence, analysisVersion, fileSize, fileModifiedMs, updatedAtMs) " +
                "VALUES (2, 1000, 2000, 'AUTOMATIC', 'SUGGESTED', 'silence_rhythm', 1, 100, 50, 7)"
        )
        db.query("SELECT COUNT(*) FROM commercial_segments WHERE recordingId = 1").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT review FROM commercial_segments WHERE recordingId = 1").use {
            it.moveToFirst()
            assertEquals("CONFIRMED", it.getString(0))
        }
    }

    companion object {
        private const val NAME = "commercial-migration"
    }
}
