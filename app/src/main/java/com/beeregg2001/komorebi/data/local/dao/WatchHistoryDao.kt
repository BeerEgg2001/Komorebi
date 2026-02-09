package com.beeregg2001.komorebi.data.local.dao

import androidx.room.*
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(history: WatchHistoryEntity)

    // 最新30件を取得するFlow
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 30")
    fun getAllHistory(): Flow<List<WatchHistoryEntity>>

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: Int)
}