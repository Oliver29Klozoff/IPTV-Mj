package com.iptvapp.data.local

import android.app.Application
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.ui.guide.GuideRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Version 45 is the last shipped schema. 46 adds catch-up to other providers' channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MergedArchiveMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate45To46KeepsChannelsWithNoArchiveUntilTheNextRefresh() {
        helper.createDatabase(NAME, 45).apply {
            execSQL(
                """
                INSERT INTO merged_channels (
                    serverIndex, streamId, name, streamIcon, num, serverNickname, epgChannelId,
                    categoryId, categoryName, isFavorite, favoriteFolderId, cachedAt, isHidden,
                    favOrder, manualGenre, streamUrl
                ) VALUES (0, 9, 'Extra', NULL, 2, 'Backup TV', 'extra.id', 'c', 'News', 1, 1, 10, 0, 0, NULL, NULL)
                """.trimIndent()
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(NAME, 46, true, IptvDatabase.MIGRATION_45_46)
        migrated.query("SELECT name, isFavorite, tvArchive, tvArchiveDuration FROM merged_channels WHERE streamId = 9").use {
            it.moveToFirst()
            assertEquals("Extra", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertEquals(0, it.getInt(2))
            assertEquals(0, it.getInt(3))
        }
    }

    @Test
    fun aGuideRowOnAnotherProviderReplaysInsideItsArchiveWindow() {
        val now = 1_800_000_000_000L
        val merged = MergedChannelEntity(
            serverIndex = 1, streamId = 5, name = "Sports Arena", streamIcon = null, num = 5, serverNickname = "Backup",
            categoryId = "9", categoryName = "Sports", tvArchive = 1, tvArchiveDuration = 2
        )
        fun program(startH: Long) = EpgEntity(
            serverIndex = 1, id = "p$startH", streamId = 5, title = "Match", description = "",
            startTimestamp = now / 1000 + startH * 3600, stopTimestamp = now / 1000 + (startH + 2) * 3600,
            nowPlaying = 0, hasArchive = 0
        )
        val row = GuideRow(mergedChannel = merged, programs = emptyList())
        assertTrue(row.supportsReplay)
        assertTrue(row.canReplay(program(-5), now))
        assertFalse(row.canReplay(program(-24 * 3), now)) // older than two days
        val noArchive = GuideRow(mergedChannel = merged.copy(tvArchive = 0), programs = emptyList())
        assertFalse(noArchive.canReplay(program(-5), now))
    }

    private companion object {
        const val NAME = "merged-archive-migration-test"
    }
}
