package com.beeregg2001.komorebi.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.beeregg2001.komorebi.data.model.EpgGenre

@Entity(
    tableName = "recorded_programs",
    indices = [
        Index(value = ["channel_id", "start_time"]),
        Index(value = ["start_time"]),
        Index(value = ["title"])
    ]
)
data class RecordedProgramEntity(
    @PrimaryKey
    val id: Int,
    val title: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    @ColumnInfo(name = "end_time") val endTime: String,

    @ColumnInfo(name = "video_duration") val videoDuration: Double,
    @ColumnInfo(name = "has_key_frames") val hasKeyFrames: Boolean,
    @ColumnInfo(name = "is_recording") val isRecording: Boolean,
    @ColumnInfo(name = "playback_position") val playbackPosition: Double,

    @ColumnInfo(name = "channel_id") val channelId: String?,
    @ColumnInfo(name = "channel_type") val channelType: String?,
    @ColumnInfo(name = "channel_name") val channelName: String?,

    val genres: List<EpgGenre>?,

    // ★追加: シーンサーチに必要なタイルメタデータ
    @ColumnInfo(name = "tile_columns") val tileColumns: Int? = null,
    @ColumnInfo(name = "tile_rows") val tileRows: Int? = null,
    @ColumnInfo(name = "tile_interval") val tileInterval: Double? = null,
    @ColumnInfo(name = "tile_width") val tileWidth: Int? = null,
    @ColumnInfo(name = "tile_height") val tileHeight: Int? = null
)