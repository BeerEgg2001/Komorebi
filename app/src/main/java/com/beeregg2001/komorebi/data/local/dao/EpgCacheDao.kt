package com.beeregg2001.komorebi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity

@Dao
interface EpgCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cache: EpgCacheEntity)

    @Query("SELECT * FROM epg_cache WHERE channelType = :channelType")
    suspend fun getCache(channelType: String): EpgCacheEntity?
}