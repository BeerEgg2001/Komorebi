package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

// --- APIレスポンス ---

data class ReservationConditionsResponse(
    val total: Int,
    @SerializedName("reservation_conditions") val reservationConditions: List<ReservationCondition>
)

data class ReservationCondition(
    val id: Int,
    @SerializedName("reservation_count") val reservationCount: Int,
    @SerializedName("program_search_condition") val programSearchCondition: ProgramSearchCondition,
    @SerializedName("record_settings") val recordSettings: RecordSettings
)

// --- リクエストボディ ---

data class ReservationConditionAddRequest(
    @SerializedName("program_search_condition") val programSearchCondition: ProgramSearchCondition,
    @SerializedName("record_settings") val recordSettings: RecordSettings
)

data class ReservationConditionUpdateRequest(
    @SerializedName("program_search_condition") val programSearchCondition: ProgramSearchCondition,
    @SerializedName("record_settings") val recordSettings: RecordSettings
)

// --- 詳細な構成要素 ---

data class ProgramSearchCondition(
    @SerializedName("is_enabled") var isEnabled: Boolean = true,
    var keyword: String = "",
    @SerializedName("exclude_keyword") var excludeKeyword: String = "",
    var note: String = "",
    @SerializedName("is_title_only") var isTitleOnly: Boolean = false,
    @SerializedName("is_case_sensitive") var isCaseSensitive: Boolean = false,
    @SerializedName("is_fuzzy_search_enabled") var isFuzzySearchEnabled: Boolean = false,
    @SerializedName("is_regex_search_enabled") var isRegexSearchEnabled: Boolean = false,
    @SerializedName("service_ranges") var serviceRanges: List<ProgramSearchConditionService>? = null,
    @SerializedName("genre_ranges") var genreRanges: List<Genre>? = null,
    @SerializedName("is_exclude_genre_ranges") var isExcludeGenreRanges: Boolean = false,
    @SerializedName("date_ranges") var dateRanges: List<ProgramSearchConditionDate>? = null,
    @SerializedName("is_exclude_date_ranges") var isExcludeDateRanges: Boolean = false,
    @SerializedName("duration_range_min") var durationRangeMin: Int? = null,
    @SerializedName("duration_range_max") var durationRangeMax: Int? = null,
    @SerializedName("broadcast_type") var broadcastType: String = "All", // "All", "FreeOnly", "PaidOnly"
    @SerializedName("duplicate_title_check_scope") var duplicateTitleCheckScope: String = "None", // "None", "SameChannelOnly", "AllChannels"
    @SerializedName("duplicate_title_check_period_days") var duplicateTitleCheckPeriodDays: Int = 6
)

data class ProgramSearchConditionService(
    @SerializedName("network_id") val networkId: Int,
    @SerializedName("transport_stream_id") val transportStreamId: Int,
    @SerializedName("service_id") val serviceId: Int
)

data class ProgramSearchConditionDate(
    @SerializedName("start_day_of_week") val startDayOfWeek: Int, // 0:日, 1:月, ... 6:土
    @SerializedName("start_hour") val startHour: Int,
    @SerializedName("start_minute") val startMinute: Int,
    @SerializedName("end_day_of_week") val endDayOfWeek: Int,
    @SerializedName("end_hour") val endHour: Int,
    @SerializedName("end_minute") val endMinute: Int
)

// 既存のコードになければこれも追加します
data class RecordSettings(
    @SerializedName("is_enabled") var isEnabled: Boolean = true,
    var priority: Int = 3,
    @SerializedName("recording_folders") var recordingFolders: List<RecordingFolder> = emptyList(),
    @SerializedName("recording_start_margin") var recordingStartMargin: Int? = null,
    @SerializedName("recording_end_margin") var recordingEndMargin: Int? = null,
    @SerializedName("recording_mode") var recordingMode: String = "SpecifiedService", // "AllServices", "SpecifiedService", "View" 等
    @SerializedName("caption_recording_mode") var captionRecordingMode: String = "Default",
    @SerializedName("data_broadcasting_recording_mode") var dataBroadcastingRecordingMode: String = "Default",
    @SerializedName("post_recording_mode") var postRecordingMode: String = "Default",
    @SerializedName("post_recording_bat_file_path") var postRecordingBatFilePath: String? = null,
    @SerializedName("is_event_relay_follow_enabled") var isEventRelayFollowEnabled: Boolean = true,
    @SerializedName("is_exact_recording_enabled") var isExactRecordingEnabled: Boolean = false,
    @SerializedName("is_oneseg_separate_output_enabled") var isOnesegSeparateOutputEnabled: Boolean = false,
    @SerializedName("is_sequential_recording_in_single_file_enabled") var isSequentialRecordingInSingleFileEnabled: Boolean = false,
    @SerializedName("forced_tuner_id") var forcedTunerId: Int? = null
)

// 既存のコードになければこれも追加します
data class RecordingFolder(
    @SerializedName("recording_folder_path") val recordingFolderPath: String,
    @SerializedName("recording_file_name_template") val recordingFileNameTemplate: String? = null,
    @SerializedName("is_oneseg_separate_recording_folder") val isOnesegSeparateRecordingFolder: Boolean = false
)