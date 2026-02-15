package com.beeregg2001.komorebi.ui.video

import androidx.compose.ui.graphics.vector.ImageVector

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, SPEED, SUBTITLE, QUALITY }

// 画質定義 (表示名とAPIパラメータを一元管理)
enum class StreamQuality(val label: String, val apiParams: String) {
    QUALITY_1080P_60("1080p (60fps)", "1080p-60fps"),
    QUALITY_1080P("1080p", "1080p"),
    QUALITY_810P("810p", "810p"),
    QUALITY_720P("720p", "720p"),
    QUALITY_540P("540p", "540p"),
    QUALITY_480P("480p", "480p"),
    QUALITY_360P("360p", "360p"),
    QUALITY_240P("240p", "240p");

    // ★追加: 設定画面・初期化用のヘルパー
    companion object {
        fun fromApiParams(params: String): StreamQuality {
            return entries.find { it.apiParams == params } ?: QUALITY_1080P_60
        }

        fun next(current: StreamQuality): StreamQuality {
            val values = entries.toTypedArray()
            return values[(current.ordinal + 1) % values.size]
        }
    }
}

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