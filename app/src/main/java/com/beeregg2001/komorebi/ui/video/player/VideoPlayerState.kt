package com.beeregg2001.komorebi.ui.video.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.AudioMode

enum class LCropMode { HIDDEN, MENU, DIRECT_ADJUST }
enum class ZoomOrigin { TopLeft, TopRight, BottomLeft, BottomRight }

data class ChapterInfo(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isCm: Boolean,
    val isMarkerOnly: Boolean = false, // trueなら「区間」ではなく「点（マーカー）」として扱う
    val label: String = ""             // UIに表示するチャプター名（例: "Aパート", "oxA" など）
)

@Stable
class VideoPlayerState {
    // 再生設定
    var currentAudioMode by mutableStateOf(AudioMode.MAIN)
    var currentSpeed by mutableFloatStateOf(1.0f)
    var currentQuality by mutableStateOf(StreamQuality("", ""))
    var isSubtitleEnabled by mutableStateOf(false)
    var isCommentEnabled by mutableStateOf(false)

    var lCropEnabled by mutableStateOf(false)
    var lCropMode by mutableStateOf(LCropMode.HIDDEN)
    var lCropZoom by mutableFloatStateOf(100f)
    var lCropX by mutableFloatStateOf(0f)
    var lCropY by mutableFloatStateOf(0f)
    var lCropOrigin by mutableStateOf(ZoomOrigin.TopRight)
    var isAutoCmSkipEnabled by mutableStateOf(true)

    var playbackOffsetMs by mutableLongStateOf(0L)
    var pendingSeekPositionMs by mutableStateOf<Long?>(null)

    var isPlayerPlaying by mutableStateOf(true)

    var indicatorState by mutableStateOf<IndicatorState?>(null)

    var lastInteractionTime by mutableLongStateOf(0L)
    var isSeekBarFocused by mutableStateOf(false)

    var wasPlayingBeforeSceneSearch = false
    var downKeyDownTime = 0L
    var isDownKeyLongPressed = false

    // ★ 新規追加: クイックシーク状態の追跡
    var isQuickSeeking by mutableStateOf(false)

    fun togglePlayPause(isPlaying: Boolean) {
        isPlayerPlaying = !isPlaying
        indicatorState = if (isPlaying) {
            IndicatorState(icon = Icons.Default.Pause, label = "一時停止")
        } else {
            IndicatorState(icon = Icons.Default.PlayArrow, label = "再生")
        }
    }

    fun handleKeyEvent(
        keyEvent: KeyEvent,
        isPiPMode: Boolean,
        isModern: Boolean,
        showControls: Boolean,
        isSubOverlayOpen: Boolean,
        chapters: List<ChapterInfo>,
        totalDurationMs: Long,
        getCurrentPositionMs: () -> Long,
        performSeek: (Long) -> Unit,
        triggerSeekingPreview: () -> Unit,
        onShowControlsChange: (Boolean) -> Unit,
        onPiPRequested: () -> Unit,
        onBackPressed: () -> Unit,
        onSceneSearchToggle: (Boolean) -> Unit,
        onChapterListToggle: (Boolean) -> Unit,
        onSubMenuToggle: (Boolean) -> Unit,
        exoPlayerIsPlaying: Boolean,
        onPause: () -> Unit,
        onPlay: () -> Unit
    ): Boolean {
        if (isPiPMode) return false
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        val isActionDown = keyEvent.nativeKeyEvent.action == NativeKeyEvent.ACTION_DOWN
        val isActionUp = keyEvent.nativeKeyEvent.action == NativeKeyEvent.ACTION_UP

        // ★ 安全装置: UIが非表示になったら必ずクイックシークモードを解除する
        if (!showControls) {
            isQuickSeeking = false
        }

        if (lCropMode == LCropMode.DIRECT_ADJUST) {
            if (isActionDown) {
                when (keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                        lCropY = (lCropY - 5f).coerceAtLeast(0f); return true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                        lCropY = (lCropY + 5f).coerceAtMost(100f); return true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        lCropX = (lCropX - 5f).coerceAtLeast(0f); return true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        lCropX = (lCropX + 5f).coerceAtMost(100f); return true
                    }

                    NativeKeyEvent.KEYCODE_PAGE_UP, NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        lCropZoom = (lCropZoom + 5f).coerceAtMost(200f); return true
                    }

                    NativeKeyEvent.KEYCODE_PAGE_DOWN, NativeKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        lCropZoom = (lCropZoom - 5f).coerceAtLeast(100f); return true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_ESCAPE -> {
                        lCropMode = LCropMode.MENU; onSubMenuToggle(true); return true
                    }
                }
            }
            return true
        }

        if (isSubOverlayOpen) return false

        if (keyCode == NativeKeyEvent.KEYCODE_BACK || keyCode == NativeKeyEvent.KEYCODE_ESCAPE) {
            if (isActionDown) {
                if (showControls) {
                    onShowControlsChange(false)
                    isQuickSeeking = false // 手動で閉じた時も解除
                } else {
                    onBackPressed()
                }
            }
            return true
        }

        // UI非表示時の安全装置
        if (!showControls) {
            if (keyCode in listOf(
                    NativeKeyEvent.KEYCODE_DPAD_CENTER,
                    NativeKeyEvent.KEYCODE_ENTER,
                    NativeKeyEvent.KEYCODE_DPAD_UP,
                    NativeKeyEvent.KEYCODE_DPAD_DOWN
                )
            ) {
                if (isActionUp) {
                    onShowControlsChange(true)
                }
                return true
            }
        }

        // ★ モダンUI表示時のフォーカスとクイックシークの制御
        if (isModern && showControls) {
            if (isQuickSeeking) {
                // クイックシーク中に別のナビゲーションキー（上下決定）が押されたら、
                // モードを解除してCompose側に処理（フォーカス移動やボタン押下）を譲る
                if (keyCode in listOf(
                        NativeKeyEvent.KEYCODE_DPAD_UP,
                        NativeKeyEvent.KEYCODE_DPAD_DOWN,
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER
                    )
                ) {
                    if (isActionDown) {
                        isQuickSeeking = false
                    }
                    return false
                }
            }

            if (!isQuickSeeking) {
                // クイックシーク中でなければ、ナビゲーションキーはすべてCompose（UI操作）に譲る
                if (keyCode in listOf(
                        NativeKeyEvent.KEYCODE_DPAD_LEFT,
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT,
                        NativeKeyEvent.KEYCODE_DPAD_UP,
                        NativeKeyEvent.KEYCODE_DPAD_DOWN,
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER
                    )
                ) {
                    return false
                }
            }
        }

        // -----------------------------------------------------------
        // 以下の処理は、UI表示中（または左右キー押下時）にのみ到達します
        // -----------------------------------------------------------

        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_LEFT) {
            if (isActionDown) {
                isQuickSeeking = true // ★ クイックシークモード開始
                onShowControlsChange(true)
                indicatorState = IndicatorState(icon = Icons.Default.FastRewind, label = "巻き戻し")
                val basePos = pendingSeekPositionMs ?: getCurrentPositionMs()
                val skipAmount = if (keyEvent.nativeKeyEvent.repeatCount > 0) 15000L else 30000L
                val newPos = (basePos - skipAmount).coerceAtLeast(0L)
                performSeek(newPos)
                triggerSeekingPreview()
            }
            return true
        }

        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT) {
            if (isActionDown) {
                isQuickSeeking = true // ★ クイックシークモード開始
                onShowControlsChange(true)
                indicatorState = IndicatorState(icon = Icons.Default.FastForward, label = "早送り")
                val basePos = pendingSeekPositionMs ?: getCurrentPositionMs()
                val skipAmount = if (keyEvent.nativeKeyEvent.repeatCount > 0) 15000L else 30000L
                val newPos =
                    (basePos + skipAmount).coerceAtMost(if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE)
                performSeek(newPos)
                triggerSeekingPreview()
            }
            return true
        }

        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN) {
            val isChapterMode = !isModern || !showControls
            if (!isChapterMode) return false
            if (isActionDown) {
                if (downKeyDownTime == 0L) downKeyDownTime = System.currentTimeMillis()
                val elapsed = System.currentTimeMillis() - downKeyDownTime
                if (!isDownKeyLongPressed && elapsed > 500) {
                    isDownKeyLongPressed = true
                    if (chapters.size > 1) {
                        onChapterListToggle(true)
                        onShowControlsChange(true)
                    }
                }
            } else if (isActionUp) {
                if (!isDownKeyLongPressed) {
                    onShowControlsChange(true); onSceneSearchToggle(true)
                }
                downKeyDownTime = 0L; isDownKeyLongPressed = false
            }
            return true
        }

        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_UP) {
            val isChapterMode = !isModern || !showControls
            if (!isChapterMode) return false
            if (isActionDown) {
                onShowControlsChange(true)
                if (!isModern) onSubMenuToggle(true)
            }
            return true
        }

        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER || keyCode == NativeKeyEvent.KEYCODE_ENTER) {
            if (isActionDown) {
                return true
            } else if (isActionUp) {
                onShowControlsChange(true)
                togglePlayPause(exoPlayerIsPlaying)
                if (exoPlayerIsPlaying) onPause() else onPlay()
                return true
            }
        }

        return false
    }
}

@Composable
fun rememberVideoPlayerState(): VideoPlayerState {
    return remember { VideoPlayerState() }
}