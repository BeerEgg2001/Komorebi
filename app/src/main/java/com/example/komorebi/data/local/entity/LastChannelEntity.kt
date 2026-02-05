package com.example.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "last_watched_channel")
data class LastChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, // 常に1つのレコードを更新
    val channelId: String,
    val name: String,
    val type: String,
    val channelNumber: String?,
    val networkId: Long,
    val serviceId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)