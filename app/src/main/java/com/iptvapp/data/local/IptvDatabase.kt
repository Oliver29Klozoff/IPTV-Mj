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
        FavoriteFolderEntity::class,
        MergedVodEntity::class,
        MergedSeriesEntity::class
    ],
    version = 33,
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
    abstract fun mergedVodDao(): MergedVodDao
    abstract fun mergedSeriesDao(): MergedSeriesDao

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

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN favoriteFolderId INTEGER")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN serverIndex INTEGER NOT NULL DEFAULT -1")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episode_watched ADD COLUMN watchedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episode_watched ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // epg_entries is a pure cache table (fully repopulated by the next EPG refresh, no
        // user-authored data), so a drop+recreate is safe here — needed because the primary
        // key changes from a bare `id` to composite (serverIndex, id) to support merged/
        // secondary-provider EPG without id/streamId collisions across servers.
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS epg_entries")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS epg_entries (
                        serverIndex INTEGER NOT NULL DEFAULT -1,
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
                """.trimIndent())
            }
        }

        // Movies-tab equivalent of MIGRATION_13_14 (merged_channels' initial creation) — see
        // MergedVodEntity kdoc for why this exists as its own table.
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS merged_vod (
                        serverIndex INTEGER NOT NULL,
                        streamId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        streamIcon TEXT,
                        serverNickname TEXT NOT NULL,
                        categoryId TEXT,
                        categoryName TEXT,
                        rating TEXT,
                        containerExtension TEXT NOT NULL,
                        added TEXT,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        favoriteFolderId INTEGER,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(serverIndex, streamId)
                    )
                """.trimIndent())
            }
        }

        // Series-tab equivalent of MIGRATION_20_21 (merged_vod's creation) — see
        // MergedSeriesEntity kdoc.
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS merged_series (
                        serverIndex INTEGER NOT NULL,
                        seriesId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        cover TEXT,
                        plot TEXT,
                        genre TEXT,
                        rating TEXT,
                        serverNickname TEXT NOT NULL,
                        categoryId TEXT,
                        categoryName TEXT,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        favoriteFolderId INTEGER,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(serverIndex, seriesId)
                    )
                """.trimIndent())
            }
        }

        // Adds hide-individual-show support to Series (both primary and merged) — same
        // isHidden pattern ChannelEntity already established for Live channels, filtered out
        // of the normal list the same way (unhide-able from Settings > Display, mirroring
        // "Hidden Channels").
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE merged_series ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Adds hide-individual-item support to merged VOD (same isHidden pattern as
        // MIGRATION_22_23's merged_series), plus watch-progress tracking merged VOD never had
        // ("a later stretch goal" per MergedVodEntity's original kdoc) — needed for both the
        // watched-first sort primary Movies already has and a Clear Progress action.
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE merged_vod ADD COLUMN watchedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE merged_vod ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE merged_vod ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Dismiss flags for Continue Watching rows — separate from watchedMs so dismissing a
        // row doesn't destroy the actual resume position. Cleared automatically the next time
        // progress is saved (see VodDao.updateWatchProgress / EpisodeWatchedDao.saveProgress),
        // so a dismissed item reappears once the user actually resumes watching it.
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN dismissedFromContinueWatching INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN dismissedFromContinueWatching INTEGER NOT NULL DEFAULT 0")
            }
        }

        // A FAILED recording previously gave zero indication of why — connection-limit
        // rejection, network blip, and storage failure all looked identical. See
        // RecordingService.classifyFailureReason.
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN failureReason TEXT")
            }
        }

        // Lets a finished recording carry the EPG program title it was captured under, so
        // playing it back can scrobble the right title to Trakt instead of the channel name
        // (which isn't a program identity at all). See RecordingEntity.programTitle kdoc.
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN programTitle TEXT")
            }
        }

        // Backs ContinueWatchingCleanupWorker's staleness check — neither table previously
        // tracked when watch progress was last actually saved (cachedAt only reflects catalog
        // refresh time), so there was no way to tell a recently-abandoned in-progress title from
        // one abandoned months ago. Defaults to 0 for existing rows, same as a brand-new row
        // before its first progress save — existing in-progress entries are simply not eligible
        // for cleanup until the next time they're actually resumed.
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN lastWatchedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN lastWatchedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Lets merged/secondary-provider channels use the same reliable byEpgId-first XMLTV
        // matching the primary provider always had — see MergedChannelEntity.epgChannelId kdoc.
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN epgChannelId TEXT")
            }
        }

        // Adds hide-individual-channel support to merged/other-provider channels — same isHidden
        // pattern MIGRATION_22_23/23_24 already gave Series/VOD, filtered out of getAll/
        // getByServerAndCategory/search/favorites the same way. Primary channels already had this
        // (channels.isHidden since early on); this closes the one remaining gap so bulk-select's
        // new Hide button works the same in Providers as it does in Live.
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Speeds up category switching in the combined Live tab for large merged providers —
        // see MergedChannelEntity's indices kdoc. Index name matches Room's auto-generated
        // convention (index_<table>_<col1>_<col2>) so Room's schema validation on next open
        // recognizes it as already satisfying the @Index the entity now declares, instead of
        // trying to create a duplicate.
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_merged_channels_serverIndex_categoryId ON merged_channels(serverIndex, categoryId)")
            }
        }

        // Restores Favorites drag-reorder — dropped when Favorites became a combined primary+
        // merged list, since ChannelEntity.favOrder only ever applied to primary channels. This
        // gives merged channels the same column so both share one flat ordering sequence.
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN favOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Lets a favorite be manually pinned to the Favorites "Other" genre chip (soon to be user-
        // renameable), overriding keyword auto-classification entirely — see
        // ChannelEntity.manualGenre / MergedChannelEntity.manualGenre kdocs.
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN manualGenre TEXT")
                db.execSQL("ALTER TABLE merged_channels ADD COLUMN manualGenre TEXT")
            }
        }

        // Single source of truth for "every migration this app has ever had" — AppModule's main
        // DB instance and WidgetChannelService's separate widget-process DB instance both need
        // the complete chain, and used to maintain two independently hand-typed copies of this
        // list. The widget's copy silently fell behind (stuck at MIGRATION_17_18 while the app
        // was already on MIGRATION_24_25) since nothing forced the two lists to stay in sync —
        // it happened not to matter yet only because neither of the widget's two queries touched
        // any column added by a migration after 17_18, but the next schema change to
        // ChannelEntity/EpgEntity would have crashed the widget's RemoteViewsFactory outright.
        // Both call sites should always reference this array now instead of listing migrations
        // by hand.
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
            MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27,
            MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32,
            MIGRATION_32_33
        )
    }
}