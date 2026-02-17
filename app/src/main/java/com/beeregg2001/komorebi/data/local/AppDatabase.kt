package com.beeregg2001.komorebi.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity

@Database(
    entities = [
        WatchHistoryEntity::class,
        LastChannelEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
}