package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.ChannelProjection
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.dao.SeriesProjection
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.video.components.RecordCategory
import com.beeregg2001.komorebi.util.TitleNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject

private const val TAG = "Komorebi_RecordVM"
private const val PREF_NAME = "search_history_pref"
private const val KEY_HISTORY = "history_list"

// ★ 追加: 録画リスト用のソート列挙型
enum class RecordSortType { DATE, TITLE, DURATION }
enum class RecordSortOrder { ASC, DESC }

private data class FilterState(
    val category: RecordCategory,
    val channelId: String?,
    val genre: String?,
    val day: String?,
    val query: String,
    val sortType: RecordSortType,  // ★ 追加
    val sortOrder: RecordSortOrder // ★ 追加
)

data class SeriesInfo(
    val displayTitle: String,
    val searchKeyword: String,
    val programCount: Int,
    val representativeVideoId: Int,
    val isEpisodic: Boolean = false,
    val directThumbnailUrl: String? = null,
    val apiThumbnailUrl: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val reserveProvider: ReserveProvider,
    private val historyRepository: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val programDao: RecordedProgramDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val syncProgress: StateFlow<SyncProgress> = syncEngine.syncProgress

    private val _selectedCategory = MutableStateFlow(RecordCategory.ALL)
    val selectedCategory: StateFlow<RecordCategory> = _selectedCategory.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId: StateFlow<String?> = _selectedChannelId.asStateFlow()

    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()

    // ★ 追加: ソート状態の管理
    private val _sortType = MutableStateFlow(RecordSortType.DATE)
    val sortType: StateFlow<RecordSortType> = _sortType.asStateFlow()

    private val _sortOrder = MutableStateFlow(RecordSortOrder.DESC)
    val sortOrder: StateFlow<RecordSortOrder> = _sortOrder.asStateFlow()

    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _categoryBeforeSearch = MutableStateFlow<RecordCategory?>(null)
    val categoryBeforeSearch: StateFlow<RecordCategory?> = _categoryBeforeSearch.asStateFlow()

    private val _selectedSeriesGenre = MutableStateFlow<String?>(null)
    val selectedSeriesGenre: StateFlow<String?> = _selectedSeriesGenre.asStateFlow()

    private val _manualListViewOverride = MutableStateFlow<Boolean?>(null)

    val isListView: StateFlow<Boolean> = combine(
        settingsRepository.defaultRecordListView,
        _manualListViewOverride
    ) { defaultType, manualOverride ->
        manualOverride ?: (defaultType == "LIST")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _isRecordingLoading = MutableStateFlow(false)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres: StateFlow<List<String>> = _availableGenres.asStateFlow()

    private val _groupedSeries = MutableStateFlow<Map<String, List<SeriesInfo>>>(emptyMap())
    val groupedSeries: StateFlow<Map<String, List<SeriesInfo>>> = _groupedSeries.asStateFlow()

    private val _groupedChannels =
        MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Pair<String, String>>>> =
        _groupedChannels.asStateFlow()

    private var currentSearchQuery: String = ""
    private var streamMaintenanceJob: Job? = null

    private val _programDetail = MutableStateFlow<RecordedProgram?>(null)
    val programDetail: StateFlow<RecordedProgram?> = _programDetail.asStateFlow()

    private var detailFetchJob: Job? = null

    fun clearSyncError() {
        syncEngine.clearError()
    }

    fun fetchProgramDetail(videoId: Int) {
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            recordProvider.getRecordedProgram(videoId).onSuccess {
                _programDetail.value = it
            }.onFailure { Log.e(TAG, "Failed to fetch program detail", it) }
        }
    }

    fun clearProgramDetail() {
        _programDetail.value = null
    }

    val recentRecordings: StateFlow<List<RecordedProgram>> = programDao.getRecentRecordingsFlow()
        .map { entities -> entities.map { RecordDataMapper.toDomainModel(it) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadSearchHistory()

        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.syncProgress.map { it.isSyncing }.distinctUntilChanged()
                .collect { isSyncing ->
                    if (!isSyncing) {
                        val seriesList = programDao.getGroupedSeries()
                        val channelsList = programDao.getDistinctChannels()
                        if (seriesList.isNotEmpty() || channelsList.isNotEmpty()) {
                            buildSeriesAndChannelMaps(seriesList, channelsList)
                        }
                    }
                }
        }

        viewModelScope.launch {
            delay(2000)
            syncEngine.launchSyncAllRecords()
        }
    }

    fun handleBackNavigation(onExit: () -> Unit) {
        when {
            _activeSearchQuery.value.isNotEmpty() -> clearSearch()
            _selectedCategory.value != RecordCategory.ALL -> updateCategory(RecordCategory.ALL)
            else -> onExit()
        }
    }

    fun triggerSmartSync() {
        syncEngine.launchSmartSync()
    }

    // ★ 修正: ソート状態も Pager のトリガーとして Combine に含める
    val pagedRecordings: Flow<PagingData<RecordedProgram>> = combine(
        combine(
            _selectedCategory,
            _selectedChannelId,
            _selectedGenre,
            _selectedDay,
            _activeSearchQuery
        ) { c, ch, g, d, q ->
            FilterState(c, ch, g, d, q, RecordSortType.DATE, RecordSortOrder.DESC) // 仮の初期値
        },
        _sortType,
        _sortOrder
    ) { partialState, type, order ->
        partialState.copy(sortType = type, sortOrder = order)
    }.flatMapLatest { state ->
        flow {
            emit(PagingData.empty())
            delay(50)

            val pager = Pager(
                config = PagingConfig(
                    pageSize = 30,
                    prefetchDistance = 10,
                    initialLoadSize = 20,
                    enablePlaceholders = true
                )
            ) {
                val isDesc = state.sortOrder == RecordSortOrder.DESC

                when {
                    // 検索やカテゴリ指定時はソートボタンを非表示にするため、デフォルトの降順クエリを使う
                    state.query.isNotBlank() -> programDao.searchPagingSource(state.query)
                    state.category == RecordCategory.CHANNEL && !state.channelId.isNullOrEmpty() -> programDao.getPagingSourceByChannel(
                        state.channelId
                    )

                    state.category == RecordCategory.GENRE && !state.genre.isNullOrEmpty() -> programDao.getPagingSourceByGenre(
                        state.genre
                    )

                    state.category == RecordCategory.TIME && !state.day.isNullOrEmpty() -> {
                        val dayOfWeekStr = when (state.day.replace("曜日", "")) {
                            "日" -> "0"; "月" -> "1"; "火" -> "2"; "水" -> "3"
                            "木" -> "4"; "金" -> "5"; "土" -> "6"; else -> "0"
                        }
                        programDao.getPagingSourceByDayOfWeek(dayOfWeekStr)
                    }

                    // ★「未視聴」の場合、選択されたソート条件に応じてDaoを切り替える
                    state.category == RecordCategory.UNWATCHED -> {
                        when (state.sortType) {
                            RecordSortType.DATE -> if (isDesc) programDao.getUnwatched_DateDesc() else programDao.getUnwatched_DateAsc()
                            RecordSortType.TITLE -> if (isDesc) programDao.getUnwatched_TitleDesc() else programDao.getUnwatched_TitleAsc()
                            RecordSortType.DURATION -> if (isDesc) programDao.getUnwatched_DurationDesc() else programDao.getUnwatched_DurationAsc()
                        }
                    }

                    // ★「全ての録画」の場合、選択されたソート条件に応じてDaoを切り替える
                    else -> {
                        when (state.sortType) {
                            RecordSortType.DATE -> if (isDesc) programDao.getAll_DateDesc() else programDao.getAll_DateAsc()
                            RecordSortType.TITLE -> if (isDesc) programDao.getAll_TitleDesc() else programDao.getAll_TitleAsc()
                            RecordSortType.DURATION -> if (isDesc) programDao.getAll_DurationDesc() else programDao.getAll_DurationAsc()
                        }
                    }
                }
            }

            emitAll(pager.flow.map { pagingData ->
                pagingData.map { entity -> RecordDataMapper.toDomainModel(entity) }
            })
        }
    }.cachedIn(viewModelScope)

    // ★ 追加: メニューから指定されたソート条件を適用する
    fun setSort(type: RecordSortType, order: RecordSortOrder) {
        _sortType.value = type
        _sortOrder.value = order
    }

    fun updateListView(isList: Boolean) {
        _manualListViewOverride.value = isList
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSeriesGenre(genre: String?) {
        _selectedSeriesGenre.value = genre
    }

    fun updateCategory(category: RecordCategory) {
        if (_selectedCategory.value == category) return
        _selectedCategory.value = category
        _selectedGenre.value = null
        _selectedChannelId.value = null
        _selectedDay.value = null
        if (category == RecordCategory.SERIES) buildSeriesIndex()
    }

    fun updateGenre(genre: String?) {
        _selectedGenre.value = genre
        _selectedCategory.value = RecordCategory.GENRE
    }

    fun updateDay(day: String?) {
        _selectedDay.value = day
        _selectedCategory.value = RecordCategory.TIME
    }

    fun updateChannel(channelId: String?) {
        _selectedChannelId.value = channelId
        _selectedCategory.value = RecordCategory.CHANNEL
        _selectedGenre.value = null
        _selectedDay.value = null
        currentSearchQuery = ""
    }

    fun searchRecordings(query: String) {
        if (_activeSearchQuery.value.isEmpty() && query.isNotEmpty()) {
            _categoryBeforeSearch.value = _selectedCategory.value
        }
        _activeSearchQuery.value = query
        _searchQuery.value = query
        currentSearchQuery = query
        if (query.isNotBlank()) addSearchHistory(query)
        _selectedCategory.value = RecordCategory.ALL
        _selectedGenre.value = null
        _selectedChannelId.value = null
        _selectedDay.value = null
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        currentSearchQuery = ""
        _categoryBeforeSearch.value?.let {
            _selectedCategory.value = it
            _categoryBeforeSearch.value = null
        } ?: run { _selectedCategory.value = RecordCategory.ALL }
    }

    fun fetchRecentRecordings(forceRefresh: Boolean = false) {
        syncEngine.launchSyncAllRecords(forceFullSync = forceRefresh)
    }

    fun loadNextPage() {}

    private fun loadSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val jsonString = prefs.getString(KEY_HISTORY, "[]")
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
        currentList.remove(query); currentList.add(0, query)
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
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
            }
        }
    }

    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch { historyRepository.saveWatchHistory(program, positionSeconds) }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            try {
                historyRepository.clearWatchHistory()
            } catch (e: Exception) {
            }
        }
    }

    @UnstableApi
    fun startStreamMaintenance(
        program: RecordedProgram,
        quality: String,
        sessionId: String,
        currentPositionProvider: () -> Double
    ) {
        streamMaintenanceJob?.cancel()

        streamMaintenanceJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val position = currentPositionProvider()
                    recordProvider.keepAlive(
                        videoId = program.recordedVideo.id,
                        sessionId = sessionId,
                        quality = quality
                    )

                    Log.d("StreamMaintenance", "Keep-Alive sent. Session: $sessionId")
                } catch (e: Exception) {
                    Log.e("StreamMaintenance", "Failed to send Keep-Alive", e)
                }
                delay(4000L)
            }
        }
    }

    fun stopStreamMaintenance() {
        streamMaintenanceJob?.cancel()
        streamMaintenanceJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopStreamMaintenance()
    }

    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            recordProvider.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }
    }

    fun buildSeriesIndex() {}

    private suspend fun buildSeriesAndChannelMaps(
        seriesList: List<SeriesProjection>,
        channelsList: List<ChannelProjection>
    ) {
        _isSeriesLoading.value = true
        try {
            val allChannelMap =
                mutableMapOf<String, MutableMap<String, Triple<String, String, String>>>()
            channelsList.forEach { ch ->
                val type = if (ch.channelType == "GR") "地デジ" else ch.channelType ?: "その他"
                val channelTypeMap = allChannelMap.getOrPut(type) { mutableMapOf() }
                if (!channelTypeMap.containsKey(ch.channelId)) {
                    channelTypeMap[ch.channelId] =
                        Triple(ch.channelName ?: "", ch.channelId, ch.channelId)
                }
            }

            val typePriority = listOf("地デジ", "BS", "BS4K", "CS", "SKY", "その他")
            val extractNumber = { idStr: String ->
                Regex("\\d+").find(idStr)?.value?.toIntOrNull() ?: Int.MAX_VALUE
            }
            _groupedChannels.value = allChannelMap.entries
                .sortedBy { (type, _) ->
                    typePriority.indexOf(type).let { if (it != -1) it else typePriority.size }
                }
                .associate { entry ->
                    entry.key to entry.value.values.sortedWith(
                        compareBy({ extractNumber(it.third) }, { it.third })
                    ).map { Pair(it.first, it.second) }
                }

            val genresSet = mutableSetOf<String>()
            val finalGroupedSeries = mutableMapOf<String, MutableList<SeriesInfo>>()

            seriesList.forEach { proj ->
                if (proj.programCount >= 2 || proj.isEpisodic) {
                    val majorGenre = proj.genres?.firstOrNull()?.major ?: "その他"
                    genresSet.add(majorGenre)

                    val searchKeyword = TitleNormalizer.toSqlSearchQuery(proj.seriesName)

                    val seriesInfo = SeriesInfo(
                        displayTitle = proj.seriesName,
                        searchKeyword = searchKeyword,
                        programCount = proj.programCount,
                        representativeVideoId = proj.representativeVideoId,
                        isEpisodic = proj.isEpisodic,
                        directThumbnailUrl = proj.directThumbnailUrl,
                        apiThumbnailUrl = proj.apiThumbnailUrl
                    )

                    val list = finalGroupedSeries.getOrPut(majorGenre) { mutableListOf() }
                    list.add(seriesInfo)
                }
            }

            _availableGenres.value = genresSet.sorted()

            _groupedSeries.value = finalGroupedSeries.mapValues { entry ->
                entry.value.sortedBy { it.displayTitle }
            }.filterValues { it.isNotEmpty() }

        } catch (e: Exception) {
            Log.e(TAG, "Map Build Error", e)
        } finally {
            _isSeriesLoading.value = false
        }
    }
}