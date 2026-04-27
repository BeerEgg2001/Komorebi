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
    val isCm: Boolean
)

@Stable
class VideoPlayerState {
    // 再生設定
    var currentAudioMode by mutableStateOf(AudioMode.MAIN)
    var currentSpeed by mutableFloatStateOf(1.0f)

    var currentQuality by mutableStateOf(
        StreamQuality(
            label = "読み込み中...",
            value = "",
            isRawTs = false
        )
    )

    // API経由の擬似シーク時に使用する仮想的なオフセット時間（ミリ秒）
    var playbackOffsetMs by mutableLongStateOf(0L)

    // UI状態
    var indicatorState by mutableStateOf<IndicatorState?>(null)
    var isPlayerPlaying by mutableStateOf(false)
    var wasPlayingBeforeSceneSearch by mutableStateOf(false)
    var lastInteractionTime by mutableLongStateOf(System.currentTimeMillis())

    // 字幕・実況・機能の表示フラグ
    var isCommentEnabled by mutableStateOf(true)
    var isSubtitleEnabled by mutableStateOf(false)
    var isAutoCmSkipEnabled by mutableStateOf(false)

    // 戻るキー長押し判定用
    var backKeyDownTime by mutableLongStateOf(0L)
    var isBackKeyLongPressed by mutableStateOf(false)

    // モダンUI時のシークバーフォーカス状態
    var isSeekBarFocused by mutableStateOf(false)

    // L字クロップ関連の状態変数
    var lCropEnabled by mutableStateOf(false)
    var lCropMode by mutableStateOf(LCropMode.HIDDEN)
    var lCropZoom by mutableFloatStateOf(100f)
    var lCropX by mutableFloatStateOf(0f)
    var lCropY by mutableFloatStateOf(0f)
    var lCropOrigin by mutableStateOf(ZoomOrigin.TopRight)

    // --- キー操作・シーク状態 ---
    var rightKeyDownTime by mutableLongStateOf(0L)
    var isRightKeyLongPressed by mutableStateOf(false)
    var leftKeyDownTime by mutableLongStateOf(0L)
    var isLeftKeyLongPressed by mutableStateOf(false)
    var downKeyDownTime by mutableLongStateOf(0L)
    var isDownKeyLongPressed by mutableStateOf(false)
    var pendingSeekPositionMs by mutableStateOf<Long?>(null)
    var lastSeekUpdateTime by mutableLongStateOf(0L)

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

    // ========================================================================
    // キーイベントハンドリング
    // ========================================================================
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
        val repeatCount = keyEvent.nativeKeyEvent.repeatCount
        val isActionDown = keyEvent.type == KeyEventType.KeyDown
        val isActionUp = keyEvent.type == KeyEventType.KeyUp

        // L字クロップ位置調整モード
        if (lCropMode == LCropMode.DIRECT_ADJUST) {
            if (isActionDown) {
                when (keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_UP -> lCropY -= 2f
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> lCropY += 2f
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> lCropX -= 2f
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> lCropX += 2f
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        lCropZoom = when {
                            lCropZoom < 125f -> 125f
                            lCropZoom < 150f -> 150f
                            lCropZoom < 175f -> 175f
                            lCropZoom < 200f -> 200f
                            else -> 100f
                        }
                    }

                    NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_ESCAPE -> {
                        lCropMode = LCropMode.MENU
                    }
                }
            }
            return true
        }

        if (lCropMode == LCropMode.MENU) return false
        if (isSubOverlayOpen) return false

        if (isActionDown) {
            lastInteractionTime = System.currentTimeMillis()
        }

        // 戻るキー
        if (keyCode == NativeKeyEvent.KEYCODE_BACK || keyCode == NativeKeyEvent.KEYCODE_ESCAPE) {
            if (isActionDown) {
                if (repeatCount == 0) {
                    backKeyDownTime = System.currentTimeMillis()
                    isBackKeyLongPressed = false
                } else {
                    if (!isBackKeyLongPressed && System.currentTimeMillis() - backKeyDownTime > 500) {
                        isBackKeyLongPressed = true
                        onPiPRequested()
                    }
                }
                return true
            } else if (isActionUp) {
                if (!isBackKeyLongPressed && System.currentTimeMillis() - backKeyDownTime < 500) {
                    if (showControls && isModern) {
                        onShowControlsChange(false)
                    } else {
                        onBackPressed()
                    }
                }
                backKeyDownTime = 0L
                isBackKeyLongPressed = false
                return true
            }
            return false
        }

        // モダンUI コントロール表示時の挙動
        if (isModern) {
            if (!showControls) {
                if (keyCode in listOf(
                        NativeKeyEvent.KEYCODE_DPAD_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN,
                        NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER
                    )
                ) {
                    if (isActionDown) onShowControlsChange(true)
                    return true
                }
            } else if (isSeekBarFocused) {
                if (keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN) return false
                if (keyCode == NativeKeyEvent.KEYCODE_DPAD_UP) {
                    if (isActionDown) onShowControlsChange(false)
                    return true
                }
            } else {
                return false
            }
        }

        // RIGHT (早送り / シーク)
        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT) {
            val isChapterMode = !isModern || !showControls
            if (isActionDown) {
                if (isModern && isSeekBarFocused) triggerSeekingPreview()
                if (repeatCount == 0) {
                    rightKeyDownTime = System.currentTimeMillis()
                    isRightKeyLongPressed = false
                    pendingSeekPositionMs = getCurrentPositionMs()
                } else {
                    if (!isRightKeyLongPressed && System.currentTimeMillis() - rightKeyDownTime > 500) {
                        isRightKeyLongPressed = true
                        onShowControlsChange(true)
                    }
                    if (isRightKeyLongPressed) {
                        val now = System.currentTimeMillis()
                        if (now - lastSeekUpdateTime > 150) {
                            lastSeekUpdateTime = now
                            val currentTarget = pendingSeekPositionMs ?: getCurrentPositionMs()

                            if (isChapterMode) {
                                val boundaries = listOf(0L) + chapters.flatMap {
                                    listOf(
                                        it.startTimeMs,
                                        it.endTimeMs
                                    )
                                }.distinct() + totalDurationMs
                                if (boundaries.size <= 2) {
                                    pendingSeekPositionMs =
                                        (currentTarget + 180_000).coerceAtMost(totalDurationMs)
                                    updateIndicator(Icons.Default.FastForward, "+3m")
                                } else {
                                    val next = boundaries.firstOrNull { it > currentTarget + 1000 }
                                        ?: totalDurationMs
                                    pendingSeekPositionMs = next
                                    updateIndicator(Icons.Default.SkipNext, "次チャプター")
                                }
                            } else {
                                pendingSeekPositionMs =
                                    (currentTarget + 15_000).coerceAtMost(totalDurationMs)
                            }
                        }
                    }
                }
            } else if (isActionUp) {
                if (isModern && isSeekBarFocused) triggerSeekingPreview()
                if (!isRightKeyLongPressed) {
                    onShowControlsChange(true)
                    performSeek((getCurrentPositionMs() + 30_000).coerceAtMost(totalDurationMs))
                    if (isChapterMode) updateIndicator(Icons.Default.FastForward, "+30s")
                } else {
                    pendingSeekPositionMs?.let { performSeek(it) }
                }
                pendingSeekPositionMs = null
                rightKeyDownTime = 0L
                isRightKeyLongPressed = false
            }
            return true
        }

        // LEFT (巻き戻し / シーク)
        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_LEFT) {
            val isChapterMode = !isModern || !showControls
            if (isActionDown) {
                if (isModern && isSeekBarFocused) triggerSeekingPreview()
                if (repeatCount == 0) {
                    leftKeyDownTime = System.currentTimeMillis()
                    isLeftKeyLongPressed = false
                    pendingSeekPositionMs = getCurrentPositionMs()
                } else {
                    if (!isLeftKeyLongPressed && System.currentTimeMillis() - leftKeyDownTime > 500) {
                        isLeftKeyLongPressed = true
                        onShowControlsChange(true)
                    }
                    if (isLeftKeyLongPressed) {
                        val now = System.currentTimeMillis()
                        if (now - lastSeekUpdateTime > 150) {
                            lastSeekUpdateTime = now
                            val currentTarget = pendingSeekPositionMs ?: getCurrentPositionMs()

                            if (isChapterMode) {
                                val boundaries = listOf(0L) + chapters.flatMap {
                                    listOf(
                                        it.startTimeMs,
                                        it.endTimeMs
                                    )
                                }.distinct() + totalDurationMs
                                if (boundaries.size <= 2) {
                                    pendingSeekPositionMs =
                                        (currentTarget - 60_000).coerceAtLeast(0L)
                                    updateIndicator(Icons.Default.FastRewind, "-1m")
                                } else {
                                    val prev =
                                        boundaries.lastOrNull { it < currentTarget - 1000 } ?: 0L
                                    pendingSeekPositionMs = prev
                                    updateIndicator(Icons.Default.SkipPrevious, "前チャプター")
                                }
                            } else {
                                pendingSeekPositionMs = (currentTarget - 15_000).coerceAtLeast(0L)
                            }
                        }
                    }
                }
            } else if (isActionUp) {
                if (isModern && isSeekBarFocused) triggerSeekingPreview()
                if (!isLeftKeyLongPressed) {
                    onShowControlsChange(true)
                    performSeek((getCurrentPositionMs() - 10_000).coerceAtLeast(0L))
                    if (isChapterMode) updateIndicator(Icons.Default.FastRewind, "-10s")
                } else {
                    pendingSeekPositionMs?.let { performSeek(it) }
                }
                pendingSeekPositionMs = null
                leftKeyDownTime = 0L
                isLeftKeyLongPressed = false
            }
            return true
        }

        // DOWN
        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN) {
            val isChapterMode = !isModern || !showControls
            if (!isChapterMode) return false
            if (isActionDown) {
                if (repeatCount == 0) {
                    downKeyDownTime = System.currentTimeMillis(); isDownKeyLongPressed = false
                } else {
                    val elapsed = System.currentTimeMillis() - downKeyDownTime
                    if (!isDownKeyLongPressed && elapsed > 500) {
                        isDownKeyLongPressed = true
                        if (chapters.size > 1) {
                            onChapterListToggle(true)
                            onShowControlsChange(true)
                        }
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

        // UP
        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_UP) {
            val isChapterMode = !isModern || !showControls
            if (!isChapterMode) return false
            if (isActionDown) {
                onShowControlsChange(true)
                if (!isModern) onSubMenuToggle(true)
            }
            return true
        }

        // CENTER/ENTER
        if (keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER || keyCode == NativeKeyEvent.KEYCODE_ENTER) {
            if (isActionDown) {
                onShowControlsChange(true)
                togglePlayPause(exoPlayerIsPlaying)
                if (exoPlayerIsPlaying) onPause() else onPlay()
            }
            return true
        }

        return false
    }
}

@Composable
fun rememberVideoPlayerState(): VideoPlayerState {
    return remember { VideoPlayerState() }
}