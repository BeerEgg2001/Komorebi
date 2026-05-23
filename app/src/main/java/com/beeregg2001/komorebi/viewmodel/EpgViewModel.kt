package com.beeregg2001.komorebi.viewmodel

import android.content.Context
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
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.time.OffsetDateTime
import javax.inject.Inject

data class UiSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel,
    val logoUrl: String
)

private const val PREF_NAME_EPG_SEARCH = "epg_search_history_pref"
private const val KEY_EPG_HISTORY = "history_list"

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val repository: EpgRepository,
    private val liveProvider: LiveProvider,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
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

    private var hasInitialFetched = false
    private var epgJob: Job? = null

    // ★ 追加: DB検索待ちをゼロにするためのメモリキャッシュ群
    private val epgMemoryCache = mutableMapOf<String, List<EpgChannelWrapper>>()
    private val logoMemoryCache = mutableMapOf<String, List<String>>()

    private var fullEpgData: List<EpgChannelWrapper> = emptyList()
    private var fullLogoUrls: List<String> = emptyList()
    private var currentTargetTime: OffsetDateTime = OffsetDateTime.now()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<UiSearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<UiSearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    var lastFocusedChannelId: String? = null
    var lastFocusedTime: OffsetDateTime? = null
    var epgRestoreTrigger by androidx.compose.runtime.mutableStateOf(0L)
        private set

    fun saveEpgFocus(channelId: String, time: OffsetDateTime) {
        lastFocusedChannelId = channelId
        lastFocusedTime = time
    }

    fun triggerRestore() {
        epgRestoreTrigger = System.currentTimeMillis()
    }

    fun clearEpgFocus() {
        lastFocusedChannelId = null
        lastFocusedTime = null
    }

    init {
        loadSearchHistory()
        loadInitialData()

        viewModelScope.launch {
            com.beeregg2001.komorebi.data.repository.edcb.EdcbEpgCacheManager.epgBackgroundUpdateEvent.collect {
                Log.i(
                    "EpgViewModel",
                    "Background EPG fetch completed! Refreshing ViewModel cache..."
                )
                refreshEpgData()
            }
        }
    }

    private fun loadSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
                val jsonString = prefs.getString(KEY_EPG_HISTORY, "[]")
                val jsonArray = JSONArray(jsonString)
                val list = ArrayList<String>()
                for (i in 0 until jsonArray.length()) list.add(jsonArray.getString(i))
                _searchHistory.value = list
            } catch (e: Exception) {
                _searchHistory.value = emptyList()
            }
        }
    }

    private fun addSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        currentList.remove(query)
        currentList.add(0, query)
        if (currentList.size > 5) currentList.removeAt(currentList.lastIndex)
        _searchHistory.value = currentList
        saveSearchHistory(currentList)
    }

    fun removeSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        if (currentList.remove(query)) {
            _searchHistory.value = currentList
            saveSearchHistory(currentList)
        }
    }

    private fun saveSearchHistory(list: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_EPG_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(UnstableApi::class)
    fun executeSearch(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ) {
        viewModelScope.launch {
            _isSearching.value = true
            val displayQuery = listOfNotNull(
                keyword.takeIf { it.isNotBlank() },
                channelName?.takeIf { it.isNotBlank() },
                genre?.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            _searchQuery.value = displayQuery
            _activeSearchQuery.value = displayQuery
            if (displayQuery.isNotBlank()) addSearchHistory(displayQuery)

            try {
                val rawResults = withContext(Dispatchers.Default) {
                    repository.searchFuturePrograms(
                        keyword,
                        genre,
                        dateStr,
                        isLiveOnly,
                        channelName
                    )
                }

                val topMatches = rawResults.sortedBy {
                    try {
                        OffsetDateTime.parse(it.program.start_time)
                    } catch (e: Exception) {
                        OffsetDateTime.MAX
                    }
                }.take(100)

                val results = withContext(Dispatchers.IO) {
                    topMatches.map { item ->
                        async {
                            UiSearchResultItem(
                                program = item.program,
                                channel = item.channel,
                                logoUrl = getLogoUrl(item.channel)
                            )
                        }
                    }.awaitAll()
                }

                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("EpgViewModel", "Search Error", e)
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    suspend fun searchSilently(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<UiSearchResultItem> {
        return try {
            val rawResults = withContext(Dispatchers.Default) {
                repository.searchFuturePrograms(keyword, genre, dateStr, isLiveOnly, channelName)
            }

            val topMatches = rawResults.sortedBy {
                try {
                    OffsetDateTime.parse(it.program.start_time)
                } catch (e: Exception) {
                    OffsetDateTime.MAX
                }
            }.take(100)

            withContext(Dispatchers.IO) {
                topMatches.map { item ->
                    async {
                        UiSearchResultItem(
                            program = item.program,
                            channel = item.channel,
                            logoUrl = getLogoUrl(item.channel)
                        )
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun preloadEpgDataForSearch(availableTypes: List<String>) {
        val now = OffsetDateTime.now()
        val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
        val end = now.plusDays(7)

        viewModelScope.launch(Dispatchers.IO) {
            availableTypes.map { type ->
                async {
                    if (!repository.hasCacheForType(type)) {
                        repository.fetchAndCacheEpgDataSilently(start, end, type)
                    }
                }
            }.awaitAll()
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            combine(
                settingsRepository.isInitialized,
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                _selectedBroadcastingType
            ) { isInit, mIp, mPort, type ->
                mirakurunIp = mIp
                mirakurunPort = mPort

                if (isInit && !hasInitialFetched) {
                    hasInitialFetched = true
                    viewModelScope.launch { refreshEpgData(type) }

                    viewModelScope.launch {
                        delay(10000)
                        preloadEpgDataForSearch(listOf("GR", "BS", "CS", "SKY", "BS4K"))
                    }

                } else if (isInit && hasInitialFetched) {
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
            val typeToFetch = channelType ?: _selectedBroadcastingType.value
            val now = OffsetDateTime.now()
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
            val end = now.plusDays(7)

            // ★ 修正: メモリキャッシュがあれば即座にUIへ反映（Loadingスピナーすら出さない）
            if (epgMemoryCache.containsKey(typeToFetch)) {
                fullEpgData = epgMemoryCache[typeToFetch]!!
                fullLogoUrls = logoMemoryCache[typeToFetch] ?: emptyList()
                sliceAndEmitEpgData()
            } else {
                if (uiState !is EpgUiState.Success) {
                    uiState = EpgUiState.Loading
                }
            }

            // キャッシュ表示後も、裏側でRoomから最新情報を取得してキャッシュを更新する
            repository.getEpgDataStream(start, end, typeToFetch).collect { result ->
                result.onSuccess { data ->
                    fullEpgData = data
                    epgMemoryCache[typeToFetch] = data

                    fullLogoUrls =
                        withContext(Dispatchers.Default) { data.map { getLogoUrl(it.channel) } }
                    logoMemoryCache[typeToFetch] = fullLogoUrls

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
    suspend fun getLogoUrl(channel: EpgChannel): String {
        return liveProvider.getChannelLogoUrl(channel.display_channel_id)
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