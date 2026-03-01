package com.beeregg2001.komorebi.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.beeregg2001.komorebi.data.db.Converters
import com.beeregg2001.komorebi.data.local.dao.*
import com.beeregg2001.komorebi.data.local.entity.*

@Database(
    entities = [
        WatchHistoryEntity::class,
        LastChannelEntity::class,
        EpgCacheEntity::class,
        // ★同期エンジン用のEntityを追加
        RecordedProgramEntity::class,
        SyncMetaEntity::class
    ],
    version = 10, // ★バージョンをアップ
    exportSchema = false
)
@TypeConverters(Converters::class) // ★TypeConverterを有効化
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
    abstract fun epgCacheDao(): EpgCacheDao

    // ★同期エンジン用のDaoを追加
    abstract fun recordedProgramDao(): RecordedProgramDao
    abstract fun syncMetaDao(): SyncMetaDao
}