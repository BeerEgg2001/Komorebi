package com.beeregg2001.komorebi.ui.live

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, VIDEO, SUBTITLE, QUALITY, COMMENT } // ★COMMENT追加
enum class StreamSource { MIRAKURUN, KONOMITV }

object LivePlayerConstants {
    const val TAG_SUBTITLE = "SubtitleDebug"
    const val SUBTITLE_SYNC_OFFSET_MS = -500L
}