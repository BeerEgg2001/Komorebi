package com.beeregg2001.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_program",
    foreignKeys = [
        ForeignKey(
            entity = EpgChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE // チャンネルが消えたら番組も消す
        )
    ],
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["startTimeEpoch"]),
        Index(value = ["endTimeEpoch"]),
        Index(value = ["title"]) // ★シリーズ検索を爆速にするためのインデックス
    ]
)
data class EpgProgramEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val networkId: Int,
    val serviceId: Int,
    val eventId: Int,
    val title: String,
    val description: String,
    val extended: String?,
    val detailJson: String?, // Map<String, String> を Gson等で文字列化して保存
    val startTime: String,   // 元のISO8601文字列
    val endTime: String,
    val startTimeEpoch: Long, // 検索・ソート用（ミリ秒）
    val endTimeEpoch: Long,   // 古いデータの自動削除用（ミリ秒）
    val duration: Int,
    val isFree: Boolean,
    val genresJson: String?, // List<EpgGenre> を文字列化
    val videoType: String?,
    val audioType: String?,
    val audioSamplingRate: String?
)