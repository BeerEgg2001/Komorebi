package com.example.komorebi.data.local.dao

import androidx.room.*
import com.example.komorebi.data.local.entity.LastChannelEntity
import com.example.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LastChannelDao {
    // 1. まず同じ channelId のデータがあれば削除（重複防止）
    @Query("DELETE FROM last_watched_channel WHERE channelId = :channelId")
    suspend fun deleteByChannelId(channelId: String)

    // 2. 新しいデータを挿入
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LastChannelEntity)

    // リポジトリから呼ぶためのトランザクション
    @Transaction
    suspend fun insertOrUpdate(entity: LastChannelEntity) {
        deleteByChannelId(entity.channelId)
        insert(entity)
    }

    @Query("SELECT * FROM last_watched_channel ORDER BY updatedAt DESC LIMIT 10")
    fun getLastChannels(): Flow<List<LastChannelEntity>>
}

// AppDatabase.kt に DAO を追加
@Database(entities = [WatchHistoryEntity::class, LastChannelEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
}