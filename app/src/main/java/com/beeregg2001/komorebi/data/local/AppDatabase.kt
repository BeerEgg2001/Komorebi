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
        EpgCacheEntity::class, // ★復元: これがないとEpgRepositoryが壊れます
        RecordedProgramEntity::class,
        SyncMetaEntity::class,
        AiSeriesDictionaryEntity::class,
        EpgChannelEntity::class,
        EpgProgramEntity::class
    ],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
    abstract fun epgCacheDao(): EpgCacheDao // ★復元

    // 同期エンジン用のDao
    abstract fun recordedProgramDao(): RecordedProgramDao
    abstract fun syncMetaDao(): SyncMetaDao

    abstract fun epgDao(): EpgDao

    // AI辞書用のDao
    abstract fun aiSeriesDictionaryDao(): AiSeriesDictionaryDao
}