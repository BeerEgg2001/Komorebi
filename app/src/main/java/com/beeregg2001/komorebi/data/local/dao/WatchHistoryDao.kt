package com.beeregg2001.komorebi.data.local.dao

import androidx.room.*
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(history: WatchHistoryEntity)

    // ★追加: リストを一括で保存する（N+1問題解消用）
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(histories: List<WatchHistoryEntity>)

    // 最新30件を取得するFlow
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 30")
    fun getAllHistory(): Flow<List<WatchHistoryEntity>>

    // IDによる個別取得（メタデータ引き継ぎ用）
    @Query("SELECT * FROM watch_history WHERE id = :id")
    suspend fun getById(id: Int): WatchHistoryEntity?

    // ★追加: 複数のIDから一括で取得する（N+1問題解消用）
    @Query("SELECT * FROM watch_history WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: Int)
}