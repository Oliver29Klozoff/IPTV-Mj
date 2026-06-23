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
        EpgEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun epgDao(): EpgDao

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
    }
}