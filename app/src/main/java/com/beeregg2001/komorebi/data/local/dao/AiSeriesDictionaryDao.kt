package com.beeregg2001.komorebi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity
import kotlinx.coroutines.flow.Flow // ★追加

@Dao
interface AiSeriesDictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dictionaryItems: List<AiSeriesDictionaryEntity>)

    @Query("SELECT * FROM ai_series_dictionary WHERE originalTitle = :originalTitle")
    suspend fun getNormalizedName(originalTitle: String): AiSeriesDictionaryEntity?

    @Query("SELECT * FROM ai_series_dictionary")
    suspend fun getAllDictionary(): List<AiSeriesDictionaryEntity>

    // ★追加: 辞書の更新をリアルタイムで検知してUIを再描画するためのFlow
    @Query("SELECT * FROM ai_series_dictionary")
    fun getAllDictionaryFlow(): Flow<List<AiSeriesDictionaryEntity>>

    @Query("DELETE FROM ai_series_dictionary")
    suspend fun clearAll()
}