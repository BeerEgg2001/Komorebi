package com.beeregg2001.komorebi.ui.live

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource

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

    // SSE / ステータス管理
    var sseStatus by mutableStateOf("Standby")
    var sseDetail by mutableStateOf(AppStrings.SSE_CONNECTING)

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
                    else -> "サーバーエラー (HTTP ${cause.responseCode})"
                }
            }
            cause is HttpDataSource.HttpDataSourceException -> {
                when (cause.cause) {
                    is java.net.ConnectException -> AppStrings.ERR_CONNECTION_REFUSED
                    is java.net.SocketTimeoutException -> AppStrings.ERR_TIMEOUT
                    else -> AppStrings.ERR_NETWORK
                }
            }
            cause is java.io.IOException -> "データ読み込みエラー: ${cause.message}"
            else -> "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})"
        }
    }

    fun retry() {
        playerError = null
        retryKey++
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
