package com.example.komorebi.viewmodel

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.data.model.EpgChannel
import com.example.komorebi.data.model.EpgChannelWrapper
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import com.example.komorebi.viewmodel.Program as PlayerProgram

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: EpgRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading

    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType: StateFlow<String> = _selectedBroadcastingType.asStateFlow()

    private var mirakurunIp = ""
    private var mirakurunPort = ""

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            combine(
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                _selectedBroadcastingType
            ) { ip, port, type ->
                mirakurunIp = ip
                mirakurunPort = port
                Triple(ip, port, type)
            }.collectLatest { (ip, port, type) ->
                if (ip.isNotEmpty() && port.isNotEmpty()) {
                    refreshEpgData(type)
                    // 初回ロード完了時にプリロードフラグを下ろす
                    _isPreloading.value = false
                }
            }
        }
    }

    /**
     * EPGデータを取得（配信期間1週間の制限を考慮）
     */
    fun refreshEpgData(channelType: String? = null) {
        viewModelScope.launch {
            uiState = EpgUiState.Loading

            // 配信制限の定義
            val now = OffsetDateTime.now()
            // 開始は 00:00:00 固定（後に「1時間前まで表示」のロジックでフィルタリングするが、データとしては当日分から持つ）
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)

            // EPGの最大配信期間は1週間（7日間）とする
            val epgLimit = now.plusDays(7)

            // 終了時刻を「現在+3日（デフォルト取得分）」とするが、最大でもepgLimitを超えないようにする
            val requestedEnd = now.plusDays(3)
            val end = if (requestedEnd.isAfter(epgLimit)) epgLimit else requestedEnd

            val typeToFetch = channelType ?: _selectedBroadcastingType.value
            val result = repository.fetchEpgData(
                startTime = start,
                endTime = end,
                channelType = typeToFetch
            )

            result.onSuccess { data ->
                val logoUrls = data.map { getLogoUrl(it.channel) }
                uiState = EpgUiState.Success(
                    data = data,
                    logoUrls = logoUrls,
                    mirakurunIp = mirakurunIp,
                    mirakurunPort = mirakurunPort
                )
            }.onFailure { e ->
                uiState = EpgUiState.Error(e.message ?: "Unknown Error")
            }
        }
    }

    /**
     * 追加の番組データを取得（ジャンプ機能などで未来を見る場合）
     */
    fun fetchExtendedEpgData(targetTime: OffsetDateTime) {
        val currentState = uiState as? EpgUiState.Success ?: return

        viewModelScope.launch {
            val now = OffsetDateTime.now()
            val epgLimit = now.plusDays(7) // 配信限界：1週間後

            // リクエストする開始・終了時刻の決定
            val start = targetTime.minusHours(6)
            var end = targetTime.plusDays(1)

            // 配信限界を超えないようにガード
            if (start.isAfter(epgLimit)) return@launch // 完全に範囲外なら何もしない
            if (end.isAfter(epgLimit)) {
                end = epgLimit
            }

            val result = repository.fetchEpgData(
                startTime = start,
                endTime = end,
                channelType = _selectedBroadcastingType.value
            )

            result.onSuccess { newData ->
                // ロゴURLの生成とUI状態の更新
                val logoUrls = newData.map { getLogoUrl(it.channel) }
                uiState = EpgUiState.Success(
                    data = newData,
                    logoUrls = logoUrls,
                    mirakurunIp = mirakurunIp,
                    mirakurunPort = mirakurunPort
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        val mirakurunBaseUrl = "http://$mirakurunIp:$mirakurunPort"
        val networkIdPart = when (channel.type) {
            "GR" -> channel.network_id.toString()
            "BS", "CS", "SKY", "BS4K" -> "${channel.network_id}00"
            else -> channel.network_id.toString()
        }
        // service_id は通常 5桁の文字列としてリクエストする必要があるためパディングを追加
        val serviceIdStr = channel.service_id.toString()
        val streamId = "$networkIdPart$serviceIdStr"
        return "$mirakurunBaseUrl/api/services/$streamId/logo"
    }

    fun updateBroadcastingType(type: String) {
        if (_selectedBroadcastingType.value != type) {
            _selectedBroadcastingType.value = type
        }
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()
    data class Success(
        val data: List<EpgChannelWrapper>,
        val logoUrls: List<String>,
        val mirakurunIp: String,
        val mirakurunPort: String
    ) : EpgUiState()
    data class Error(val message: String) : EpgUiState()
}