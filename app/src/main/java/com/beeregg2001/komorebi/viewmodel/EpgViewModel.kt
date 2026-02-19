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
import com.beeregg2001.komorebi.common.UrlBuilder
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

    private var hasInitialFetched = false
    private var epgJob: Job? = null // ★追加: フロー監視ジョブの管理用

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

                val isMirakurunReady = mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()
                val isKonomiReady = konomiIp.isNotEmpty() && konomiPort.isNotEmpty()

                if ((isMirakurunReady || isKonomiReady) && !hasInitialFetched) {
                    hasInitialFetched = true

                    viewModelScope.launch {
                        delay(1500)
                        refreshEpgData(type)
                        _isPreloading.value = false
                    }
                } else if ((isMirakurunReady || isKonomiReady) && hasInitialFetched) {
                    refreshEpgData(type)
                }
            }.collectLatest { }
        }
    }

    fun preloadAllEpgData() {
        refreshEpgData()
    }

    fun refreshEpgData(channelType: String? = null) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            // ★変更: キャッシュがなく、初めての場合のみローディング状態にする
            if (uiState !is EpgUiState.Success) {
                uiState = EpgUiState.Loading
            }

            val now = OffsetDateTime.now()
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
            val epgLimit = now.plusDays(7)
            val requestedEnd = now.plusDays(3)
            val end = if (requestedEnd.isAfter(epgLimit)) epgLimit else requestedEnd

            val typeToFetch = channelType ?: _selectedBroadcastingType.value

            // ★変更: getEpgDataStream のフローを監視し、キャッシュ -> API最新 の順でUIを更新する
            repository.getEpgDataStream(
                startTime = start,
                endTime = end,
                channelType = typeToFetch
            ).collect { result ->
                result.onSuccess { data ->
                    val processedState = withContext(Dispatchers.Default) {
                        val logoUrls = data.map { getLogoUrl(it.channel) }
                        EpgUiState.Success(
                            data = data,
                            logoUrls = logoUrls,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort
                        )
                    }
                    uiState = processedState
                }.onFailure { e ->
                    // キャッシュで成功表示済みならエラーにせず維持する
                    if (uiState !is EpgUiState.Success) {
                        uiState = EpgUiState.Error(e.message ?: "Unknown Error")
                    }
                }
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
                val processedState = withContext(Dispatchers.Default) {
                    val logoUrls = newData.map { getLogoUrl(it.channel) }
                    EpgUiState.Success(
                        data = newData,
                        logoUrls = logoUrls,
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort
                    )
                }
                uiState = processedState
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        return if (mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()) {
            UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.network_id.toLong(), channel.service_id.toLong())
        } else {
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