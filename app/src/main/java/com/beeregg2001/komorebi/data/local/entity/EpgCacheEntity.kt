package com.beeregg2001.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epg_cache")
data class EpgCacheEntity(
    @PrimaryKey val channelType: String, // "GR", "BS", "CS" など
    val dataJson: String,                // Gsonで文字列化した番組表データ
    val updatedAt: Long                  // 保存日時
)