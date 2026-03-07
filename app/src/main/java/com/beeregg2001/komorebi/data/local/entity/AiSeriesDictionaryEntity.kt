package com.beeregg2001.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_series_dictionary")
data class AiSeriesDictionaryEntity(
    @PrimaryKey val originalTitle: String, // 元のタイトル
    val normalizedSeriesName: String,      // AIが判定したシリーズ名
    val updatedAt: Long                    // 最終更新日時
)