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
    @SerializedName("recorded_video") val recordedVideo: RecordedVideo,
    // 必要に応じて detail (出演者など) も追加可能
)

// 実際のビデオファイル情報（サムネイルや再生に使用）
data class RecordedVideo(
    val id: Int,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("recording_start_time") val recordingStartTime: String,
    @SerializedName("recording_end_time") val recordingEndTime: String,
    val duration: Double,
    @SerializedName("container_format") val containerFormat: String,
    @SerializedName("video_codec") val videoCodec: String,
    @SerializedName("audio_codec") val audioCodec: String
)

data class RecordedItemDto(
    val id: String,
    val title: String,
    val description: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String
)

fun getThumbnailUrl(id: String, host: String, port: String): String {
    // KonomiTV APIの仕様に合わせたサムネイルURL
    // 文字列のIDをそのまま利用します
    return "http://$host:$port/api/video/$id/thumbnail"
}