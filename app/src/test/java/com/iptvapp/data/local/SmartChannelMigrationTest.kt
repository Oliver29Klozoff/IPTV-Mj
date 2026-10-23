package com.iptvapp.data.local

import android.app.Activity
import android.widget.Button
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.local.entities.SmartChannelGroupEntity
import com.iptvapp.ui.smart.DialogFocus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Version 48 is the shipped schema. 49 adds Smart Channel tables and leaves user data alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmartChannelMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate48To49KeepsFavoritesRecordingsAndAddsGroups() {
        helper.createDatabase(NAME, 48).apply {
            execSQL(
                "INSERT INTO channels (streamId, name, streamIcon, categoryId, epgChannelId, tvArchive, tvArchiveDuration, num, isFavorite, lastWatched, cachedAt, streamUrl, favOrder, viewCount, isHidden, favoriteFolderId, manualGenre, customNum) " +
                    "VALUES (9, 'ESPN HD', null, 'sports', 'espn.us', 0, 0, 1, 1, 50, 60, null, 3, 4, 0, null, null, null)",
            )
            execSQL(
                "INSERT INTO recordings (streamId, channelName, scheduledStartMs, durationMs, outputPath, status, serverIndex, programTitle) " +
                    "VALUES (9, 'ESPN HD', 10, 60000, '/tmp/a.ts', 'SCHEDULED', -1, 'Nightly News')",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(NAME, 49, true, IptvDatabase.MIGRATION_48_49)
        db.query("SELECT isFavorite, lastWatched FROM channels WHERE streamId = 9").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(50, it.getLong(1))
        }
        db.query("SELECT streamId, serverIndex, programTitle, status FROM recordings").use {
            assertTrue(it.moveToFirst())
            assertEquals(9, it.getInt(0))
            assertEquals(-1, it.getInt(1))
            assertEquals("Nightly News", it.getString(2))
            assertEquals("SCHEDULED", it.getString(3))
        }
        db.execSQL(
            "INSERT INTO smart_channel_groups (groupId, displayName, preferredServerIndex, preferredStreamId, hasPreferred, failoverEnabled, origin, reviewState, reason, groupFavorite, updatedAt) " +
                "VALUES ('v:espn', 'ESPN HD', -1, 9, 1, 1, 'AUTO', 'VERIFIED', 'Same verified guide identity and compatible region.', 0, 5)",
        )
        db.query("SELECT displayName FROM smart_channel_groups WHERE groupId = 'v:espn'").use {
            assertTrue(it.moveToFirst())
            assertEquals("ESPN HD", it.getString(0))
        }
        db.query("SELECT streamUrl FROM channels WHERE streamId = 9").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
        }
    }

    @Test
    fun preferredSourceChangeDoesNotRewriteTheRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val recordingId = db.recordingDao().insert(
            RecordingEntity(
                streamId = 9,
                channelName = "ESPN HD",
                scheduledStartMs = 10,
                durationMs = 60_000,
                outputPath = "/tmp/a.ts",
                status = "RECORDING",
                serverIndex = -1,
                programTitle = "Nightly News",
            ),
        ).toInt()
        db.smartChannelDao().upsertGroup(
            SmartChannelGroupEntity(
                groupId = "v:espn",
                displayName = "ESPN HD",
                preferredServerIndex = -1,
                preferredStreamId = 9,
                hasPreferred = true,
                failoverEnabled = true,
                origin = "AUTO",
                reviewState = "VERIFIED",
                reason = "Same verified guide identity and compatible region.",
                groupFavorite = false,
                updatedAt = 1,
            ),
        )
        val saved = db.smartChannelDao().group("v:espn")!!
        db.smartChannelDao().upsertGroup(saved.copy(preferredServerIndex = 2, preferredStreamId = 40, updatedAt = 2))
        val recording = db.recordingDao().getForHome().first { it.id == recordingId }
        assertEquals(9, recording.streamId)
        assertEquals(-1, recording.serverIndex)
        assertEquals("Nightly News", recording.programTitle)
        assertEquals("RECORDING", recording.status)
        val again = db.smartChannelDao().group("v:espn")
        assertEquals(2, again?.preferredServerIndex)
        assertFalse(again?.displayName?.contains("password") == true)
        db.close()
    }

    @Test
    fun dialogDismissRestoresFocus() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = Button(activity)
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        activity.setContentView(button)
        button.requestFocus()
        assertTrue(button.hasFocus())
        button.clearFocus()
        DialogFocus.restore(button)
        ShadowLooper.idleMainLooper()
        assertTrue(button.hasFocus())
    }

    companion object {
        private const val NAME = "smart-channel-migration"
    }
}
