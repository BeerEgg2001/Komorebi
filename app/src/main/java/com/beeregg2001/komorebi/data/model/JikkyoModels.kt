package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

data class JikkyoResponse(
    @SerializedName("watch_session_url") val watchSessionUrl: String?,
    @SerializedName("comment_session_url") val commentSessionUrl: String?,
    @SerializedName("nicolive_watch_session_url") val nicoliveWatchSessionUrl: String?,
    @SerializedName("is_nxjikkyo_exclusive") val isNxJikkyoExclusive: Boolean
)