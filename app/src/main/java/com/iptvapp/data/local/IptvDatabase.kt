package com.iptvapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iptvapp.data.local.dao.*
import com.iptvapp.data.local.entities.*

@Database(
    entities = [
        ChannelEntity::class,
        CategoryEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        EpgEntity::class,
        RecordingEntity::class,
        ChannelReliabilityEntity::class,
        EpisodeWatchedEntity::class,
        MergedChannelEntity::class,
        FavoriteFolderEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun epgDao(): EpgDao
    abstract fun recordingDao(): RecordingDao
    abstract fun reliabilityDao(): ReliabilityDao
    abstract fun episodeWatchedDao(): EpisodeWatchedDao
    abstract fun mergedChannelDao(): MergedChannelDao
    abstract fun favoriteFolderDao(): FavoriteFolderDao

    companion object {
        const val DATABASE_NAME = "iptv_db"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN watchedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN watchedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // recordings table removed; migration kept to preserve upgrade chain for existing users
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN streamUrl TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN favOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS recordings")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN viewCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recordings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        streamId INTEGER NOT NULL,
                        channelName TEXT NOT NULL,
                        scheduledStartMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        outputPath TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'SCHEDULED'
                    )
                """.trimIndent())
            }
        }

        // Drop and recreate epg_entries to add nowPlaying and hasArchive columns
        // that were missing from older installs. EPG data is re-fetchable so data loss is fine.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS epg_entries")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS epg_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        streamId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        startTimestamp INTEGER NOT NULL,
                        stopTimestamp INTEGER NOT NULL,
                        nowPlaying INTEGER NOT NULL DEFAULT 0,
                        hasArchive INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS channel_reliability (
                        streamId INTEGER NOT NULL PRIMARY KEY,
                        outcomes TEXT NOT NULL DEFAULT '',
                        lastUpdated INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS episode_watched (
                        seriesId INTEGER NOT NULL,
                        season INTEGER NOT NULL,
                        episode INTEGER NOT NULL,
                        watchedAt INTEGER NOT NULL,
                        PRIMARY KEY(seriesId, season, episode)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS merged_channels (
                        serverIndex INTEGER NOT NULL,
                        streamId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        streamIcon TEXT,
                        num INTEGER NOT NULL,
                        serverNickname TEXT NOT NULL,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(serverIndex, streamId)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN categoryId TEXT")
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN categoryName TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE channels ADD COLUMN favoriteFolderId INTEGER")
            }
        }
    }
}