package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

data class JikkyoResponse(
    @SerializedName("watch_session_url") val watchSessionUrl: String?,
    @SerializedName("comment_session_url") val commentSessionUrl: String?,
    @SerializedName("nicolive_watch_session_url") val nicoliveWatchSessionUrl: String?,
    @SerializedName("is_nxjikkyo_exclusive") val isNxJikkyoExclusive: Boolean
)

// ★追加: アーカイブコメント用データモデル
data class ArchivedJikkyoResponse(
    val is_success: Boolean,
    val comments: List<ArchivedComment>
)

data class ArchivedComment(
    val time: Double,      // 動画開始からの秒数
    val text: String,
    val color: String,
    val author: String,
    val type: String,
    val size: String
)