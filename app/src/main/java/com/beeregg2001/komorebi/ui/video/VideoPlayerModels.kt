package com.beeregg2001.komorebi.ui.video

import androidx.compose.ui.graphics.vector.ImageVector

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, SPEED, SUBTITLE, QUALITY } // ★QUALITY追加

data class IndicatorState(
    val icon: ImageVector,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)

object VideoPlayerConstants {
    // KonomiTVの生成仕様に合わせ、10秒刻みの間隔
    val SEARCH_INTERVALS = listOf(10, 30, 60, 120, 300, 600)

    // ライブ側と共通の同期オフセット
    const val SUBTITLE_SYNC_OFFSET_MS = -500L
}