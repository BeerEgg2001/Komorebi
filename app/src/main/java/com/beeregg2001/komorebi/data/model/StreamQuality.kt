package com.beeregg2001.komorebi.data.model

/**
 * プロジェクト全体で共通の画質定義
 */
enum class StreamQuality(val label: String, val value: String) {
    Q1080P_60("1080p (60fps)", "1080p-60fps"),
    Q1080P("1080p", "1080p"),
    Q810P("810p", "810p"),
    Q720P("720p", "720p"),
    Q540P("540p", "540p"),
    Q480P("480p", "480p"),
    Q360P("360p", "360p"),
    Q240P("240p", "240p");

    companion object {
        /**
         * 次の画質へ切り替える（トグル用）
         */
        fun next(current: StreamQuality): StreamQuality {
            val values = entries.toTypedArray()
            return values[(current.ordinal + 1) % values.size]
        }

        /**
         * 文字列から画質型を取得する
         */
        fun fromValue(value: String): StreamQuality {
            return entries.find { it.value == value } ?: Q1080P_60
        }
    }
}