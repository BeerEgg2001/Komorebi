package com.beeregg2001.komorebi.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity

@Database(
    entities = [
        WatchHistoryEntity::class,
        LastChannelEntity::class,
        EpgCacheEntity::class // ★キャッシュ用Entityを追加
    ],
    version = 7, // ★バージョンを6から7へアップ
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
    abstract fun epgCacheDao(): EpgCacheDao // ★Daoを追加
}