package com.iptvapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SeriesRecordingMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IptvDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun `migration preserves schedules and commercial markers`() {
        helper.createDatabase("series-migration", 49).apply {
            execSQL("INSERT INTO recordings (id, streamId, channelName, scheduledStartMs, durationMs, outputPath, status, serverIndex, programTitle) VALUES (77, 1, 'Channel', 100, 60000, '/tmp/keep.ts', 'DONE', -1, 'Show')")
            execSQL("INSERT INTO commercial_segments (recordingId,startMs,endMs,origin,review,evidence,analysisVersion,fileSize,fileModifiedMs,updatedAtMs) VALUES (77,10,20,'MANUAL','APPROVED','manual',1,1000,1,1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate("series-migration", 50, true, IptvDatabase.MIGRATION_49_50)
        migrated.query("SELECT status, outputPath, isProtected FROM recordings WHERE id = 77").use {
            assertTrue(it.moveToFirst()); assertEquals("DONE", it.getString(0)); assertEquals("/tmp/keep.ts", it.getString(1)); assertEquals(0, it.getInt(2))
        }
        migrated.query("SELECT COUNT(*) FROM commercial_segments WHERE recordingId = 77").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }
}
