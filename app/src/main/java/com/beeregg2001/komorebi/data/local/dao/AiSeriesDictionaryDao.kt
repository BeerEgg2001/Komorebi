package com.beeregg2001.komorebi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity

@Dao
interface AiSeriesDictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dictionaryItems: List<AiSeriesDictionaryEntity>)

    @Query("SELECT * FROM ai_series_dictionary WHERE originalTitle = :originalTitle")
    suspend fun getNormalizedName(originalTitle: String): AiSeriesDictionaryEntity?

    @Query("SELECT * FROM ai_series_dictionary")
    suspend fun getAllDictionary(): List<AiSeriesDictionaryEntity>

    @Query("DELETE FROM ai_series_dictionary")
    suspend fun clearAll()
}