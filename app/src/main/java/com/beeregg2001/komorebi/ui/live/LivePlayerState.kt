package com.beeregg2001.komorebi.ui.live

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 信号情報の詳細を保持するデータクラス
 */
data class SignalMetadata(
    val videoRes: String = "-",
    val videoCodec: String = "-",
    val videoBitrate: String = "-",
    val verticalFreq: String = "-",
    val audioCodec: String = "-",
    val audioChannels: String = "-",
    val audioSampleRate: String = "-",
    val bufferDuration: String = "-",
    val droppedFrames: String = "0"
)

/**
 * LivePlayerScreenの複雑な状態と再生ロジックを管理するクラス
 */
@Stable
class LivePlayerState(
    val context: Context,
    initialQuality: String
) {
    // 再生設定状態
    var currentAudioMode by mutableStateOf(AudioMode.MAIN)
    var currentQuality by mutableStateOf(StreamQuality.fromValue(initialQuality))
    var currentStreamSource by mutableStateOf(StreamSource.KONOMITV)

    // プレイヤー状態・エラー管理
    var playerError by mutableStateOf<String?>(null)
    var retryKey by mutableIntStateOf(0)
    var isPlayerPlaying by mutableStateOf(false)

    // 信号情報オーバーレイの状態
    var isSignalInfoVisible by mutableStateOf(false)
    var signalInfo by mutableStateOf(SignalMetadata())

    // SSE / ステータス管理 (左画面/メイン画面用)
    var sseStatus by mutableStateOf("Standby")
    var sseDetail by mutableStateOf(AppStrings.SSE_CONNECTING)

    // SSE / ステータス管理 (右画面/サブ画面用)
    var dualSseStatus by mutableStateOf("Standby")
    var dualSseDetail by mutableStateOf(AppStrings.SSE_CONNECTING)

    // 二画面モードの状態管理
    var isDualDisplayMode by mutableStateOf(false)
    var activeDualPlayerIndex by mutableIntStateOf(0) // 0:左画面, 1:右画面
    var dualRightChannel by mutableStateOf<Channel?>(null)

    // 二画面のサイズ比率管理
    var leftScreenWeight by mutableFloatStateOf(1f)
    var rightScreenWeight by mutableFloatStateOf(1f)

    // センターキーの長押し判定用フラグ
    var isCenterLongPressHandled by mutableStateOf(false)

    // 最後の操作時刻（無操作フェードアウト判定用）
    var lastInteractionTime by mutableLongStateOf(System.currentTimeMillis())

    // Mirakurunで二画面を開こうとした際の警告ダイアログフラグ
    var showMirakurunDualWarningDialog by mutableStateOf(false)

    // ★追加: 二画面モード開始前のストリームソースを記憶
    var previousStreamSource by mutableStateOf<StreamSource?>(null)

    /**
     * 二画面モード中に決定キーを押した際、サイズを切り替えるロジック
     */
    fun toggleDualScreenSize() {
        if (activeDualPlayerIndex == 0) {
            if (leftScreenWeight == 1f && rightScreenWeight == 1f) {
                leftScreenWeight = 0.6f
                rightScreenWeight = 1.4f
            } else if (leftScreenWeight < 1f) {
                leftScreenWeight = 1.4f
                rightScreenWeight = 0.6f
            } else {
                leftScreenWeight = 1f
                rightScreenWeight = 1f
            }
        } else {
            if (rightScreenWeight == 1f && leftScreenWeight == 1f) {
                rightScreenWeight = 0.6f
                leftScreenWeight = 1.4f
            } else if (rightScreenWeight < 1f) {
                rightScreenWeight = 1.4f
                leftScreenWeight = 0.6f
            } else {
                rightScreenWeight = 1f
                leftScreenWeight = 1f
            }
        }
    }

    /**
     * プレイヤーのエラー内容をユーザー向けメッセージに変換
     */
    fun analyzePlayerError(error: PlaybackException): String {
        val cause = error.cause
        return when {
            cause is HttpDataSource.InvalidResponseCodeException -> {
                when (cause.responseCode) {
                    404 -> AppStrings.ERR_CHANNEL_NOT_FOUND
                    503 -> AppStrings.ERR_TUNER_FULL
                    else -> String.format(AppStrings.ERR_SERVER_HTTP, cause.responseCode)
                }
            }

            cause is HttpDataSource.HttpDataSourceException -> {
                when (cause.cause) {
                    is java.net.ConnectException -> AppStrings.ERR_CONNECTION_REFUSED
                    is java.net.SocketTimeoutException -> AppStrings.ERR_TIMEOUT
                    else -> AppStrings.ERR_NETWORK
                }
            }

            cause is java.io.IOException -> String.format(AppStrings.ERR_DATA_READ, cause.message)
            else -> "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})"
        }
    }

    fun retry() {
        playerError = null
        retryKey++
    }

    /**
     * キーイベントのハンドリング
     */
    fun handleKeyEvent(
        keyEvent: KeyEvent,
        isSubMenuOpen: Boolean,
        isMiniListOpen: Boolean,
        showOverlay: Boolean,
        isManualOverlay: Boolean,
        isPinnedOverlay: Boolean,
        currentChannelItem: Channel,
        groupedChannels: Map<String, List<Channel>>,
        scrollState: ScrollState,
        scope: CoroutineScope,
        onChannelSelect: (Channel) -> Unit,
        onShowOverlayChange: (Boolean) -> Unit,
        onManualOverlayChange: (Boolean) -> Unit,
        onPinnedOverlayChange: (Boolean) -> Unit,
        onSubMenuToggle: (Boolean) -> Unit,
        onMiniListToggle: (Boolean) -> Unit,
        onShowToast: (String) -> Unit
    ): Boolean {
        if (this.playerError != null || isSubMenuOpen || isMiniListOpen) return false

        val keyCode = keyEvent.nativeKeyEvent.keyCode
        val isActionDown = keyEvent.type == KeyEventType.KeyDown
        val isActionUp = keyEvent.type == KeyEventType.KeyUp

        if (isActionDown) {
            this.lastInteractionTime = System.currentTimeMillis()
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (isActionDown) {
                if (keyEvent.nativeKeyEvent.repeatCount > 0 && !isCenterLongPressHandled) {
                    isCenterLongPressHandled = true
                    if (this.isDualDisplayMode && this.dualRightChannel != null) {
                        val oldLeft = currentChannelItem
                        val oldRight = this.dualRightChannel!!
                        onChannelSelect(oldRight)
                        this.dualRightChannel = oldLeft
                        this.sseStatus = "Standby"
                        this.sseDetail = AppStrings.SSE_CONNECTING
                        this.dualSseStatus = "Standby"
                        this.dualSseDetail = AppStrings.SSE_CONNECTING
                        onShowToast(AppStrings.TOAST_DUAL_SCREEN_SWAPPED)
                    }
                    return true
                }
                return true
            } else if (isActionUp) {
                if (isCenterLongPressHandled) {
                    isCenterLongPressHandled = false
                    return true
                } else {
                    if (this.isDualDisplayMode) {
                        this.toggleDualScreenSize()
                        return true
                    }
                    if (showOverlay) {
                        onShowOverlayChange(false)
                        onManualOverlayChange(false)
                        onPinnedOverlayChange(true)
                    } else if (isPinnedOverlay) {
                        onPinnedOverlayChange(false)
                    } else {
                        onShowOverlayChange(true)
                        onManualOverlayChange(true)
                        onPinnedOverlayChange(false)
                    }
                    return true
                }
            }
        }

        if (!isActionDown) return false

        if (!isMiniListOpen) {
            if (this.isDualDisplayMode) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    this.activeDualPlayerIndex =
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) 0 else 1
                    return true
                }
            } else {
                val currentGroupList =
                    groupedChannels.values.find { list -> list.any { it.id == currentChannelItem.id } }
                if (currentGroupList != null) {
                    val currentIndex =
                        currentGroupList.indexOfFirst { it.id == currentChannelItem.id }
                    if (currentIndex != -1) {
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onManualOverlayChange(false)
                                onPinnedOverlayChange(false)
                                onShowOverlayChange(false)
                                onChannelSelect(currentGroupList[if (currentIndex > 0) currentIndex - 1 else currentGroupList.size - 1])
                                return true
                            }

                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onManualOverlayChange(false)
                                onPinnedOverlayChange(false)
                                onShowOverlayChange(false)
                                onChannelSelect(currentGroupList[if (currentIndex < currentGroupList.size - 1) currentIndex + 1 else 0])
                                return true
                            }
                        }
                    }
                }
            }
        }

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (showOverlay && isManualOverlay) {
                    scope.launch { scrollState.animateScrollTo(scrollState.value - 200) }
                    return true
                }
                if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                    onSubMenuToggle(true)
                    return true
                }
            }

            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (showOverlay && isManualOverlay) {
                    scope.launch { scrollState.animateScrollTo(scrollState.value + 200) }
                    return true
                }
                if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                    onMiniListToggle(true)
                    return true
                }
            }

            android.view.KeyEvent.KEYCODE_BACK -> {
                if (this.isDualDisplayMode) {
                    this.isDualDisplayMode = false
                    this.leftScreenWeight = 1f
                    this.rightScreenWeight = 1f

                    // ★追加: 二画面モード終了時に元のソースへ戻す
                    this.previousStreamSource?.let {
                        this.currentStreamSource = it
                        this.previousStreamSource = null
                    }

                    return true
                }
            }
        }
        return false
    }
}

@Composable
fun rememberLivePlayerState(
    context: Context,
    initialQuality: String
): LivePlayerState {
    return remember(initialQuality) {
        LivePlayerState(context, initialQuality)
    }
}