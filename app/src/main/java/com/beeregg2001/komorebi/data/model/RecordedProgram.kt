package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

// ルートオブジェクト
data class RecordedApiResponse(
    val total: Int,
    @SerializedName("recorded_programs") val recordedPrograms: List<RecordedProgram>
)

// 各録画番組の情報
data class RecordedProgram(
    val id: Int,
    val title: String,
    val description: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    val duration: Double,
    @SerializedName("is_partially_recorded") val isPartiallyRecorded: Boolean,
    val channel: RecordedChannel? = null,
    @SerializedName("recorded_video") val recordedVideo: RecordedVideo,

    // ViewModelで注入する録画中フラグ
    val isRecording: Boolean = false
)

data class RecordedChannel(
    val id: String,
    @SerializedName("display_channel_id") val displayChannelId: String,
    val type: String,
    val name: String,
    @SerializedName("channel_number") val channelNumber: String
)

data class RecordedVideo(
    val id: Int,
    val status: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("recording_start_time") val recordingStartTime: String,
    @SerializedName("recording_end_time") val recordingEndTime: String,
    val duration: Double,
    @SerializedName("container_format") val containerFormat: String,
    @SerializedName("video_codec") val videoCodec: String,
    @SerializedName("audio_codec") val audioCodec: String,
    // ★追加: メタデータ解析状態
    @SerializedName("has_key_frames") val hasKeyFrames: Boolean = true,
    @SerializedName("thumbnail_info") val thumbnailInfo: ThumbnailInfo? = null
)

data class ThumbnailInfo(
    val version: Int,
    val tile: TileInfo?
)

data class TileInfo(
    @SerializedName("image_width") val imageWidth: Int,
    @SerializedName("image_height") val imageHeight: Int,
    @SerializedName("tile_width") val tileWidth: Int,
    @SerializedName("tile_height") val tileHeight: Int,
    @SerializedName("column_count") val columnCount: Int,
    @SerializedName("row_count") val rowCount: Int,
    @SerializedName("interval_sec") val intervalSec: Double,
    @SerializedName("total_tiles") val totalTiles: Int
)