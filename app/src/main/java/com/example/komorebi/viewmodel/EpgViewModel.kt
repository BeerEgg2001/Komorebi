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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Duration

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: EpgRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // --- [State] データの状態 ---
    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading

    private val broadcastingTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")

    // --- [State] UI表示の状態管理 ---
    // UI側の remember から移動。これにより日時変更時もリセットされず、ViewModelが変更を検知できる
    private val _baseTime = MutableStateFlow(OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0))
    val baseTime: StateFlow<OffsetDateTime> = _baseTime.asStateFlow()

    // 番組表の描画定数（UIとViewModelで共通化）
    val dpPerMinute = 1.3f
    val hourHeightDp = 60 * dpPerMinute

    // 設定情報
    private var currentBaseUrl by mutableStateOf("")
    private var mirakurunBaseUrl by mutableStateOf("")

    // プレイヤー連携用
    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId: StateFlow<String?> = _selectedChannelId

    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType = _selectedBroadcastingType.asStateFlow()

    init {
        viewModelScope.launch {
            val ip = settingsRepository.mirakurunIp.first()
            val port = settingsRepository.mirakurunPort.first()
            mirakurunBaseUrl = "http://$ip:$port"
            currentBaseUrl = settingsRepository.getBaseUrl().removeSuffix("/")

            // 初回読み込み
            preloadAllEpgData()
        }
    }

    /**
     * 表示の起点時間を更新する（日時指定ジャンプで使用）
     */
    fun updateBaseTime(newTime: OffsetDateTime) {
        _baseTime.value = newTime
        // ここで新しい時間のデータを再取得するロジックがあれば呼ぶ
        loadEpg(days = 1)
    }

    /**
     * 指定された時間に対するY軸のオフセット（dp相当の数値）を計算する
     */
    fun getYOffsetForTime(timeStr: String): Float {
        return try {
            val startTime = OffsetDateTime.parse(timeStr)
            val minutes = Duration.between(baseTime.value, startTime).toMinutes()
            minutes * dpPerMinute
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 指定された日数のEPGデータを読み込む
     */
    fun loadEpg(days: Long) {
        viewModelScope.launch {
            uiState = EpgUiState.Loading
            // updateBaseTime で設定された baseTime を使用してリクエスト
            val result = repository.fetchEpgData(
                startTime = baseTime.value,
                channelType = null,
                days = days
            )

            uiState = result.fold(
                onSuccess = { EpgUiState.Success(it) },
                onFailure = { EpgUiState.Error(it.message ?: "Unknown Error") }
            )
        }
    }

    /**
     * 全放送波のデータをプリロードする
     */
    fun preloadAllEpgData() {
        viewModelScope.launch {
            _isPreloading.value = true
            uiState = EpgUiState.Loading
            try {
                val deferredList = broadcastingTypes.map { type ->
                    async {
                        repository.fetchEpgData(
                            startTime = baseTime.value,
                            channelType = type,
                            days = 1
                        ).getOrThrow()
                    }
                }
                val allData = deferredList.awaitAll().flatten()
                uiState = EpgUiState.Success(allData)
            } catch (e: Exception) {
                uiState = EpgUiState.Error(e.message ?: "Unknown Error")
            } finally {
                _isPreloading.value = false
            }
        }
    }

    /**
     * チャンネル・番組情報の補助メソッド
     */
    fun getMirakurunLogoUrl(channel: EpgChannel): String {
        val streamId = buildStreamId(channel)
        return "$mirakurunBaseUrl/api/services/$streamId/logo"
    }

    private fun buildStreamId(channel: EpgChannel): String {
        val networkIdPart = when (channel.type) {
            "GR" -> channel.network_id.toString()
            "BS", "CS", "SKY", "BS4K" -> "${channel.network_id}00"
            else -> channel.network_id.toString()
        }
        return "${networkIdPart}${channel.service_id}"
    }

    fun playChannel(channelId: String) {
        viewModelScope.launch { _selectedChannelId.value = channelId }
    }

    fun clearSelectedChannel() {
        _selectedChannelId.value = null
    }

    fun updateBroadcastingType(type: String) {
        _selectedBroadcastingType.value = type
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()
    data class Success(val data: List<EpgChannelWrapper>) : EpgUiState()
    data class Error(val message: String) : EpgUiState()
}