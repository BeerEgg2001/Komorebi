package com.beeregg2001.komorebi.ui.video

import androidx.compose.ui.graphics.vector.ImageVector

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, SPEED }

data class IndicatorState(
    val icon: ImageVector,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)

object VideoPlayerConstants {
    // ★KonomiTVの生成仕様に合わせ、10秒刻みの間隔に変更
    val SEARCH_INTERVALS = listOf(10, 30, 60, 120, 300, 600)
}