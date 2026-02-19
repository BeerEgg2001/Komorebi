package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

// --- レスポンス用モデル (取得) ---

data class ReserveApiResponse(
    val total: Int = 0,
    val reservations: List<ReserveItem> = emptyList()
)

data class ReserveItem(
    val id: Int,
    val channel: ReserveChannel,
    val program: ReserveProgramDetail,

    @SerializedName("is_recording_in_progress")
    val isRecordingInProgress: Boolean = false,

    @SerializedName("recording_availability")
    val recordingAvailability: String = "Pending",

    @SerializedName("estimated_recording_file_size")
    val estimatedRecordingFileSize: Long = 0L,

    @SerializedName("record_settings")
    val recordSettings: ReserveRecordSettings = ReserveRecordSettings()
)

data class ReserveProgramDetail(
    val id: String = "",
    val title: String = "番組情報なし",
    // ★修正: description を Nullable に。EDCB予約では空のことが多い
    val description: String? = "",
    @SerializedName("start_time") val startTime: String = "",
    @SerializedName("end_time") val endTime: String = "",
    val duration: Int = 0,
    val genres: List<ReserveGenre>? = null,
    val detail: Map<String, String>? = null,
    @SerializedName("is_free") val isFree: Boolean = true,
    @SerializedName("video_type") val videoType: String? = null,
    @SerializedName("primary_audio_type") val audioType: String? = null,
    @SerializedName("primary_audio_sampling_rate") val audioSamplingRate: String? = null
)

data class ReserveChannel(
    val id: String = "",
    // ★修正: Long に統一してパースエラーを回避
    @SerializedName("network_id") val network_Id: Long = 0L,
    @SerializedName("service_id") val service_Id: Long = 0L,
    @SerializedName("channel_number") val channelNumber: String = "",
    // ★修正: ここも Nullable に
    @SerializedName("display_channel_id") val displayChannelId: String? = null,
    val type: String = "",
    val name: String = "不明なチャンネル"
)

data class ReserveGenre(
    val major: String = "",
    val middle: String = ""
)

// --- リクエスト用モデル ---

data class ReserveRequest(
    @SerializedName("program_id") val programId: String,
    @SerializedName("record_settings") val recordSettings: ReserveRecordSettings
)

/**
 * 予約設定詳細
 */
data class ReserveRecordSettings(
    @SerializedName("is_enabled") val isEnabled: Boolean = true,
    @SerializedName("priority") val priority: Int = 3,
    @SerializedName("recording_folders") val recordingFolders: List<String>? = emptyList(),
    @SerializedName("recording_start_margin") val startMargin: Int = 0,
    @SerializedName("recording_end_margin") val endMargin: Int = 0,
    @SerializedName("recording_mode") val recordingMode: String = "SpecifiedService",
    @SerializedName("caption_recording_mode") val captionMode: String = "Default",
    @SerializedName("data_broadcasting_recording_mode") val dataMode: String = "Default",
    @SerializedName("post_recording_mode") val postRecordingMode: String = "Default",
    @SerializedName("post_recording_bat_file_path") val postRecordingBatFilePath: String? = null,
    @SerializedName("is_event_relay_follow_enabled") val isEventRelayFollowEnabled: Boolean = true,
    @SerializedName("is_exact_recording_enabled") val isExactRecordingEnabled: Boolean = false,
    @SerializedName("is_oneseg_separate_output_enabled") val isOnesegSeparateOutputEnabled: Boolean = false,
    @SerializedName("is_sequential_recording_in_single_file_enabled") val isSequentialRecordingEnabled: Boolean = false,
    @SerializedName("forced_tuner_id") val forcedTunerId: Int = 0
)