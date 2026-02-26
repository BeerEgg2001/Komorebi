package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class RecordCategory(val label: String, val icon: ImageVector) {
    ALL("全ての録画", Icons.Default.AllInclusive),
    UNWATCHED("未視聴", Icons.Default.NewReleases),
    SERIES("シリーズ別", Icons.Default.VideoLibrary),
    GENRE("ジャンル別", Icons.Default.Category),
    CHANNEL("チャンネル別", Icons.Default.Tv),
    TIME("曜日・時間帯", Icons.Default.Schedule)
}