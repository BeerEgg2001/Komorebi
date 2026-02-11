package com.beeregg2001.komorebi.data.model

/**
 * APIレスポンスのルート
 */
data class EpgChannelResponse(
    val channels: List<EpgChannelWrapper>
)

/**
 * チャンネルと番組リストのペア
 */
data class EpgChannelWrapper(
    val channel: EpgChannel,
    val programs: List<EpgProgram>
)

/**
 * チャンネル詳細情報
 */
data class EpgChannel(
    val id: String,                  // "NID31874-SID56336"
    val display_channel_id: String,  // "gr011"
    val network_id: Int,
    val service_id: Int,
    val transport_stream_id: Int,
    val remocon_id: Int,
    val channel_number: String,      // "011"
    val type: String,                // "GR", "BS"
    val name: String,                // "KBCテレビ"
    val jikkyo_force: Int?,
    val is_subchannel: Boolean,
    val is_radiochannel: Boolean,
    val is_watchable: Boolean
)

/**
 * 番組詳細情報
 */
data class EpgProgram(
    val id: String,
    val channel_id: String,
    val network_id: Int,
    val service_id: Int,
    val event_id: Int,
    val title: String,
    val description: String,
    val extended: String? = null,
    val detail: Map<String, String>?, // "番組内容": "..." 等の動的プロパティ
    val start_time: String,           // ISO8601形式
    val end_time: String,
    val duration: Int,                // 秒単位
    val is_free: Boolean,
    val genres: List<EpgGenre>?,
    val video_type: String?,
    val audio_type: String?,
    val audio_sampling_rate: String?
) {
    // UI表示用の補助プロパティ
    val majorGenre: String? get() = genres?.firstOrNull()?.major
}

/**
 * ARIBジャンル情報
 */
data class EpgGenre(
    val major: String,
    val middle: String
)