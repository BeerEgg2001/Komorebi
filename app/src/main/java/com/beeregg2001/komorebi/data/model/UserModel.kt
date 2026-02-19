package com.beeregg2001.komorebi.data.model

data class KonomiUser(
    val id: Int,
    val name: String,
    val pinned_channel_ids: List<String>,
)

data class KonomiHistoryProgram(
    val program: KonomiProgram,
    val playback_position: Double,
    val last_watched_at: String,
    // ★追加: UI受け渡し用のメタデータ（APIにはないがDBから復元した際に保持する）
    val videoId: Int? = null,
    val tileColumns: Int = 1,
    val tileRows: Int = 1,
    val tileInterval: Double = 10.0,
    val tileWidth: Int = 320,
    val tileHeight: Int = 180
)

data class KonomiProgram(
    val id: String,
    val title: String,
    val description: String,
    val start_time: String,
    val end_time: String,
    val channel_id: String,
)

data class HistoryUpdateRequest(
    val program_id: String,
    val playback_position: Double
)