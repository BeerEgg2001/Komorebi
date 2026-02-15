package com.beeregg2001.komorebi.data.model

import com.beeregg2001.komorebi.viewmodel.Channel

/**
 * UI表示に特化した、計算済みのチャンネル状態。
 * Composable内での計算を排除するために使用。
 */
data class UiChannelState(
    val channel: Channel,
    val displayChannelId: String,
    val name: String,
    val programTitle: String,
    val progress: Float, // 0.0f ~ 1.0f (ViewModel側で計算済み)
    val hasProgram: Boolean
)

/**
 * ライブタブの1行分（ジャンル）を表現するモデル。
 */
data class LiveRowState(
    val genreId: String,
    val genreLabel: String,
    val channels: List<UiChannelState>
)

// --- 以下は既存のモデルをそのまま維持 ---
data class EpgChannelResponse(val channels: List<EpgChannelWrapper>)
data class EpgChannelWrapper(val channel: EpgChannel, val programs: List<EpgProgram>)
data class EpgChannel(
    val id: String, val display_channel_id: String, val network_id: Int, val service_id: Int,
    val transport_stream_id: Int, val remocon_id: Int, val channel_number: String,
    val type: String, val name: String, val jikkyo_force: Int?, val is_subchannel: Boolean,
    val is_radiochannel: Boolean, val is_watchable: Boolean
)
data class EpgProgram(
    val id: String, val channel_id: String, val network_id: Int, val service_id: Int,
    val event_id: Int, val title: String, val description: String, val extended: String? = null,
    val detail: Map<String, String>?, val start_time: String, val end_time: String,
    val duration: Int, val is_free: Boolean, val genres: List<EpgGenre>?, val video_type: String?,
    val audio_type: String?, val audio_sampling_rate: String?
) {
    val majorGenre: String? get() = genres?.firstOrNull()?.major
}
data class EpgGenre(val major: String, val middle: String)