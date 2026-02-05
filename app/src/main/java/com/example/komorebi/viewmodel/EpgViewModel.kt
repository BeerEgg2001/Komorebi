package com.example.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.komorebi.data.model.EpgChannelWrapper
import com.example.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import javax.inject.Inject
import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.data.model.EpgChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: EpgRepository,
    private val settingsRepository: SettingsRepository // 追加
) : ViewModel() {
    // import があれば 'by' が正しく機能します
    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading
    private val broadcastingTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")

    // ロゴ生成用に現在のベースURLを保持
    private var currentBaseUrl by mutableStateOf("")
    private var mirakurunBaseUrl by mutableStateOf("")

    // 選択されたチャンネルIDを保持するState（Player画面がこれを監視して再生を開始する）
    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId: StateFlow<String?> = _selectedChannelId

    init {
        // 初期化時にベースURLを取得しておく
        viewModelScope.launch {
            val ip = settingsRepository.mirakurunIp.first()
            val port = settingsRepository.mirakurunPort.first()
            mirakurunBaseUrl = "http://$ip:$port"
            currentBaseUrl = settingsRepository.getBaseUrl().removeSuffix("/")
            // ★ ベースURLが確定したら即座に読み込み開始
            loadEpg(1)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun loadEpg(days: Long) {
        viewModelScope.launch {
            uiState = EpgUiState.Loading
// 現在時刻をそのまま取得（秒以下を0にするとリクエストURLが綺麗になります）
//            val now = OffsetDateTime.now().withSecond(0).withNano(0)

            // ヒント: 現在放送中の番組をしっかり表示したい場合は、
            // 30分前（.minusMinutes(30)）から取得するのが一般的です
            val baseTime = OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0)

            // fetchEpgData に渡す
            val result = repository.fetchEpgData(
                startTime = baseTime,
                channelType = null, // 明示的にnullを渡す（全取得）
                days = days
            )

            uiState = result.fold(
                onSuccess = { EpgUiState.Success(it) },
                onFailure = { EpgUiState.Error(it.message ?: "Unknown Error") }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun preloadAllEpgData() {
        viewModelScope.launch {
            _isPreloading.value = true
            uiState = EpgUiState.Loading
            try {
                // 現在時刻から直近の0分0秒を起点にする
                val baseTime = OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0)

                // 各放送波（GR, BS, CS...）ごとに並列でリクエストを投げる
                val deferredList = broadcastingTypes.map { type ->
                    async {
                        // Result<List<EpgChannelWrapper>> が返ってくるので getOrThrow() で展開
                        repository.fetchEpgData(
                            startTime = baseTime,
                            channelType = type,
                            days = 1 // 必要に応じて期間を調整
                        ).getOrThrow()
                    }
                }

                // awaitAll() で全放送波の結果を待ち、flatten() で一つの List<EpgChannelWrapper> にまとめる
                val allData = deferredList.awaitAll().flatten()

                uiState = EpgUiState.Success(allData)
            } catch (e: Exception) {
                e.printStackTrace()
                uiState = EpgUiState.Error(e.message ?: "Unknown Error")
            } finally {
                _isPreloading.value = false
            }
        }
    }

    /**
     * 指定されたチャンネルIDで再生を開始する
     */
    fun playChannel(channelId: String) {
        viewModelScope.launch {
            _selectedChannelId.value = channelId
            // ここで必要に応じて、再生画面への遷移フラグを立てたり、
            // 最後に視聴したチャンネルとして保存する処理を追加します
        }
    }

    /**
     * 再生完了後やエラー時にIDをクリアする
     */
    fun clearSelectedChannel() {
        _selectedChannelId.value = null
    }

    // チャンネルオブジェクトから URL を生成する
    fun getMirakurunLogoUrl(channel: EpgChannel): String {
        // channel.id が数字（ServiceId）であることを想定しています
        // もし channel.serviceId というプロパティがあるならそれを使ってください
        val streamId = buildStreamId(channel)
        return "$mirakurunBaseUrl/api/services/$streamId/logo"
    }

    fun buildStreamId(channel: EpgChannel): String {
        val networkIdPart = when (channel.type) {
            "GR" -> channel.network_id.toString()
            "BS", "CS", "SKY", "BS4K" -> "${channel.network_id}00"
            else -> channel.network_id.toString()
        }
        return "${networkIdPart}${channel.service_id}"
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()
    data class Success(val data: List<EpgChannelWrapper>) : EpgUiState()
    data class Error(val message: String) : EpgUiState()
}