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
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.OffsetDateTime
import javax.inject.Inject

// ★追加: UIに渡すための「完全な番組＋チャンネル＋ロゴURL」のデータクラス
data class UiSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel,
    val logoUrl: String
)

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

    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType: StateFlow<String> = _selectedBroadcastingType.asStateFlow()

    private var mirakurunIp = ""
    private var mirakurunPort = ""
    private var konomiIp = ""
    private var konomiPort = ""

    private var hasInitialFetched = false
    private var epgJob: Job? = null

    private var fullEpgData: List<EpgChannelWrapper> = emptyList()
    private var fullLogoUrls: List<String> = emptyList()
    private var currentTargetTime: OffsetDateTime = OffsetDateTime.now()

    // ==========================================
    // 未来番組検索用のState
    // ==========================================
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    // ★修正: リストの型を UiSearchResultItem に変更
    private val _searchResults = MutableStateFlow<List<UiSearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<UiSearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            _activeSearchQuery.value = trimmed

            val currentList = _searchHistory.value.toMutableList()
            currentList.remove(trimmed)
            currentList.add(0, trimmed)
            if (currentList.size > 5) {
                currentList.removeAt(currentList.lastIndex)
            }
            _searchHistory.value = currentList

            viewModelScope.launch(Dispatchers.Default) {
                _isSearching.value = true
                // Repositoryからデータを取り出し、ここでロゴURLを付与してしまう！
                val results = repository.searchFuturePrograms(trimmed)
                val uiResults = results.map { item ->
                    UiSearchResultItem(
                        program = item.program,
                        channel = item.channel,
                        logoUrl = getLogoUrl(item.channel)
                    )
                }
                _searchResults.value = uiResults
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    // ==========================================
    // ★追加: 検索に備えて全放送波のデータを裏で取得しておく
    // ==========================================
    fun preloadEpgDataForSearch(availableTypes: List<String>) {
        val now = OffsetDateTime.now()
        val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
        val end = now.plusDays(7)

        viewModelScope.launch(Dispatchers.IO) {
            // ★修正: forEach の順番待ちをやめ、async {}.awaitAll() で全放送波を同時にフェッチして爆速化！
            availableTypes.map { type ->
                async {
                    if (!repository.hasCacheForType(type)) {
                        repository.fetchAndCacheEpgDataSilently(start, end, type)
                    }
                }
            }.awaitAll()
        }
    }
    // ==========================================

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
                    viewModelScope.launch { refreshEpgData(type) }
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
            if (uiState !is EpgUiState.Success) {
                uiState = EpgUiState.Loading
            }

            val now = OffsetDateTime.now()
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
            val end = now.plusDays(7)

            val typeToFetch = channelType ?: _selectedBroadcastingType.value

            repository.getEpgDataStream(start, end, typeToFetch).collect { result ->
                result.onSuccess { data ->
                    fullEpgData = data
                    fullLogoUrls =
                        withContext(Dispatchers.Default) { data.map { getLogoUrl(it.channel) } }

                    sliceAndEmitEpgData()

                    _isInitialLoadComplete.value = true
                    _isPreloading.value = false
                }.onFailure { e ->
                    if (uiState !is EpgUiState.Success) {
                        uiState = EpgUiState.Error(e.message ?: "Unknown Error")
                        _isInitialLoadComplete.value = true
                    }
                }
            }
        }
    }

    fun updateTargetTime(time: OffsetDateTime) {
        currentTargetTime = time
        sliceAndEmitEpgData()
    }

    private fun getTvDayStart(time: OffsetDateTime): OffsetDateTime {
        val base = time.withHour(4).withMinute(0).withSecond(0).withNano(0)
        return if (time.hour < 4) base.minusDays(1) else base
    }

    private fun sliceAndEmitEpgData() {
        if (fullEpgData.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {

            val tvDayStart = getTvDayStart(currentTargetTime)
            val tvDayEnd = tvDayStart.plusHours(24)

            val slicedData = fullEpgData.map { wrapper ->
                val filteredPrograms = wrapper.programs.filter { prog ->
                    try {
                        val pStart = OffsetDateTime.parse(prog.start_time)
                        val pEnd = OffsetDateTime.parse(prog.end_time)
                        pEnd.isAfter(tvDayStart) && pStart.isBefore(tvDayEnd)
                    } catch (e: Exception) {
                        false
                    }
                }
                wrapper.copy(programs = filteredPrograms)
            }

            uiState = EpgUiState.Success(
                data = slicedData,
                logoUrls = fullLogoUrls,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                targetTime = currentTargetTime
            )
        }
    }

    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        return if (mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()) {
            UrlBuilder.getMirakurunLogoUrl(
                mirakurunIp, mirakurunPort, channel.network_id.toLong(), channel.service_id.toLong()
            )
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
        val mirakurunPort: String,
        val targetTime: OffsetDateTime
    ) : EpgUiState()

    data class Error(val message: String) : EpgUiState()
}