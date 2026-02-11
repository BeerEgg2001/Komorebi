package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder // 追加
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.OffsetDateTime
import javax.inject.Inject

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
    private var konomiIp = ""
    private var konomiPort = ""

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            combine(
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                settingsRepository.konomiIp,
                settingsRepository.konomiPort,
                _selectedBroadcastingType
            ) { mIp, mPort, kIp, kPort, type ->
                mirakurunIp = mIp
                mirakurunPort = mPort
                konomiIp = kIp
                konomiPort = kPort
                // Mirakurun または KonomiTV のどちらかが設定されていればリフレッシュ
                val isMirakurunReady = mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()
                val isKonomiReady = konomiIp.isNotEmpty() && konomiPort.isNotEmpty()

                if (isMirakurunReady || isKonomiReady) {
                    refreshEpgData(type)
                    _isPreloading.value = false
                }
            }.collectLatest { }
        }
    }

    /**
     * EPGデータを一括でプリロード/リフレッシュする
     * 設定変更後などに呼び出される
     */
    fun preloadAllEpgData() {
        refreshEpgData()
    }

    fun refreshEpgData(channelType: String? = null) {
        viewModelScope.launch {
            uiState = EpgUiState.Loading
            val now = OffsetDateTime.now()
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
            val epgLimit = now.plusDays(7)
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

    fun fetchExtendedEpgData(targetTime: OffsetDateTime) {
        val currentState = uiState as? EpgUiState.Success ?: return
        viewModelScope.launch {
            val now = OffsetDateTime.now()
            val epgLimit = now.plusDays(7)
            val start = targetTime.minusHours(6)
            var end = targetTime.plusDays(1)
            if (start.isAfter(epgLimit)) return@launch
            if (end.isAfter(epgLimit)) end = epgLimit

            val result = repository.fetchEpgData(
                startTime = start,
                endTime = end,
                channelType = _selectedBroadcastingType.value
            )

            result.onSuccess { newData ->
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

    /**
     * ロゴURLを組み立てる (Mirakurun優先、なければKonomiTV)
     */
    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        return if (mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()) {
            // Mirakurun API (UrlBuilderを使用)
            UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.network_id.toLong(), channel.service_id.toLong(), channel.type)
        } else {
            // KonomiTV API (UrlBuilderを使用)
            UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.display_channel_id)
        }
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