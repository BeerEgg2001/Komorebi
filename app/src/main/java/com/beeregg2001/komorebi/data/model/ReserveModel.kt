package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

// ルートレスポンス
data class ReserveApiResponse(
    val total: Int,
    val reservations: List<ReserveItem>
)

// 予約アイテム (JSONの配列要素)
data class ReserveItem(
    val id: Int, // 予約ID
    val channel: ReserveChannel,
    val program: ReserveProgramDetail,

    @SerializedName("is_recording_in_progress")
    val isRecordingInProgress: Boolean,

    @SerializedName("recording_availability")
    val recordingAvailability: String,

    @SerializedName("estimated_recording_file_size")
    val estimatedRecordingFileSize: Long,

    @SerializedName("record_settings")
    val recordSettings: ReserveSettings
)

// 番組詳細情報
data class ReserveProgramDetail(
    val id: String,
    val title: String,
    val description: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    val duration: Int,
    val genres: List<ReserveGenre>? = null,
    val detail: Map<String, String>? = null,

    // ★追加: EpgProgramへの変換に必要なフィールド
    @SerializedName("is_free") val isFree: Boolean = true,
    @SerializedName("video_type") val videoType: String? = null,
    @SerializedName("primary_audio_type") val audioType: String? = null,
    @SerializedName("primary_audio_sampling_rate") val audioSamplingRate: String? = null
)

// チャンネル情報
data class ReserveChannel(
    val id: String,
    @SerializedName("network_id") val network_Id: Int,
    @SerializedName("service_id") val service_Id: Int,
    @SerializedName("channel_number") val channelNumber: String,
    @SerializedName("display_channel_id") val displayChannelId: String?,
    val type: String,
    val name: String
)

data class ReserveGenre(
    val major: String,
    val middle: String
)

// 録画設定
data class ReserveSettings(
    @SerializedName("is_enabled") val isEnabled: Boolean,
    val priority: Int
)

// 予約追加リクエスト用
data class ReserveRequest(
    @SerializedName("program_id") val programId: String,
    val option: ReserveOption? = null
)

data class ReserveOption(
    val enable: Boolean = true,
    val priority: Int = 1
)