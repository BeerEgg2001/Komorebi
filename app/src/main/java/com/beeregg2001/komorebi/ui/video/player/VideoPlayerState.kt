package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.AudioMode

@Stable
class VideoPlayerState(
    initialQuality: String
) {
    // 再生設定
    var currentAudioMode by mutableStateOf(AudioMode.MAIN)
    var currentSpeed by mutableFloatStateOf(1.0f)
    var currentQuality by mutableStateOf(StreamQuality.fromValue(initialQuality))

    // UI状態
    var indicatorState by mutableStateOf<IndicatorState?>(null)
    var isPlayerPlaying by mutableStateOf(false)
    var wasPlayingBeforeSceneSearch by mutableStateOf(false)
    var lastInteractionTime by mutableLongStateOf(System.currentTimeMillis())

    // 字幕・実況の表示フラグ
    var isCommentEnabled by mutableStateOf(true)
    var isSubtitleEnabled by mutableStateOf(false)

    fun updateIndicator(icon: ImageVector, label: String) {
        indicatorState = IndicatorState(icon, label)
    }

    fun togglePlayPause(isPlaying: Boolean) {
        if (isPlaying) {
            updateIndicator(Icons.Default.Pause, "停止")
        } else {
            updateIndicator(Icons.Default.PlayArrow, "再生")
        }
    }
}

@Composable
fun rememberVideoPlayerState(initialQuality: String): VideoPlayerState {
    return remember(initialQuality) {
        VideoPlayerState(initialQuality)
    }
}