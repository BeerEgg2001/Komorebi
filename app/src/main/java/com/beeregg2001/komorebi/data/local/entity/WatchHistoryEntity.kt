package com.beeregg2001.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val duration: Double,
    val videoId: Int, // 本来の録画ビデオID
    val playbackPosition: Double = 0.0,
    val watchedAt: Long = System.currentTimeMillis(),
    // ★追加: タイルサムネイルのメタデータ
    val tileColumns: Int = 1,
    val tileRows: Int = 1,
    val tileInterval: Double = 10.0,
    val tileWidth: Int = 320,
    val tileHeight: Int = 180
)