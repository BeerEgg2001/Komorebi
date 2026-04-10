package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.AudioMode

// ★ 追加: L字クロップ機能のモードと起点の定義
enum class LCropMode { HIDDEN, MENU, DIRECT_ADJUST }
enum class ZoomOrigin { TopLeft, TopRight, BottomLeft, BottomRight }

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

    // 戻るキー長押し判定用
    var backKeyDownTime by mutableLongStateOf(0L)
    var isBackKeyLongPressed by mutableStateOf(false)

    // ★ 追加: L字クロップ関連の状態変数
    var lCropEnabled by mutableStateOf(false)
    var lCropMode by mutableStateOf(LCropMode.HIDDEN)
    var lCropZoom by mutableFloatStateOf(100f) // 100f to 200f
    var lCropX by mutableFloatStateOf(0f) // -100f to 100f (パーセンテージ)
    var lCropY by mutableFloatStateOf(0f) // -100f to 100f (パーセンテージ)
    var lCropOrigin by mutableStateOf(ZoomOrigin.TopRight)

    /**
     * 画面中央のインジケーター（再生・一時停止など）を更新します
     */
    fun updateIndicator(icon: ImageVector, label: String) {
        indicatorState = IndicatorState(icon, label)
    }

    /**
     * 再生・一時停止のトグルとインジケーター表示を行います
     */
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