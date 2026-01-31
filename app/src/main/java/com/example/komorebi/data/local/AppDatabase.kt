package com.example.komorebi.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.komorebi.data.local.dao.WatchHistoryDao
import com.example.komorebi.data.local.entity.WatchHistoryEntity

@Database(entities = [WatchHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
}