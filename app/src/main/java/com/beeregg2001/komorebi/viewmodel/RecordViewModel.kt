package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.video.components.RecordCategory
import com.beeregg2001.komorebi.util.TitleNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private data class FilterState(
    val category: RecordCategory,
    val channelId: String?,
    val genre: String?,
    val day: String?,
    val query: String
)

data class SeriesInfo(
    val displayTitle: String,
    val searchKeyword: String,
    val programCount: Int,
    val representativeVideoId: Int
)

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val historyRepository: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val programDao: RecordedProgramDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // --- 1. 状態の定義 ---

    // ★追加: UIローディング用の進捗状況
    val syncProgress: StateFlow<SyncProgress> = syncEngine.syncProgress

    private val _selectedCategory = MutableStateFlow(RecordCategory.ALL)
    val selectedCategory: StateFlow<RecordCategory> = _selectedCategory.asStateFlow()
    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()
    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId: StateFlow<String?> = _selectedChannelId.asStateFlow()
    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()
    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _categoryBeforeSearch = MutableStateFlow<RecordCategory?>(null)
    private val _manualListViewOverride = MutableStateFlow<Boolean?>(null)
    val isListView: StateFlow<Boolean> =
        combine(settingsRepository.defaultRecordListView, _manualListViewOverride) { d, m ->
            m ?: (d == "LIST")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private val _selectedSeriesGenre = MutableStateFlow<String?>(null)
    val selectedSeriesGenre: StateFlow<String?> = _selectedSeriesGenre.asStateFlow()

    // --- 2. 表示形式のリアクティブ管理 ---

    private val _manualListViewOverride = MutableStateFlow<Boolean?>(null)

    val isListView: StateFlow<Boolean> = combine(
        settingsRepository.defaultRecordListView,
        _manualListViewOverride
    ) { defaultType, manualOverride ->
        manualOverride ?: (defaultType == "LIST")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- その他非同期状態 ---

    private val _isRecordingLoading = MutableStateFlow(false)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()
    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres: StateFlow<List<String>> = _availableGenres.asStateFlow()

    private val _groupedSeries =
        MutableStateFlow<Map<String, List<SeriesInfo>>>(emptyMap())
    val groupedSeries: StateFlow<Map<String, List<SeriesInfo>>> =
        _groupedSeries.asStateFlow()

    private val _groupedChannels =
        MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Pair<String, String>>>> =
        _groupedChannels.asStateFlow()

    private var currentSearchQuery: String = ""
    private var maintenanceJob: Job? = null

    private val _programDetail = MutableStateFlow<RecordedProgram?>(null)
    val programDetail: StateFlow<RecordedProgram?> = _programDetail.asStateFlow()

    fun fetchProgramDetail(videoId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getRecordedProgram(videoId).onSuccess {
                _programDetail.value = it
            }.onFailure {
                Log.e(TAG, "Failed to fetch program detail", it)
            }
        }
    }

    fun clearProgramDetail() {
        _programDetail.value = null
    }

    val recentRecordings: StateFlow<List<RecordedProgram>> = programDao.getRecentRecordingsFlow()
        .map { entities ->
            entities.map { RecordDataMapper.toDomainModel(it) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadSearchHistory()

        viewModelScope.launch(Dispatchers.IO) {
            programDao.getAllProgramsFlow()
                .debounce(1000L)
                .collect { entities ->
                    if (entities.isNotEmpty()) {
                        buildSeriesAndChannelMapsFromEntities(entities)
                    }
                }
        }

        viewModelScope.launch {
            syncEngine.syncAllRecords()
        }
    }

    // ★追加: 録画リストを開いた時などに呼ぶスマート同期
    fun triggerSmartSync() {
        viewModelScope.launch {
            syncEngine.smartSync()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedRecordings: Flow<PagingData<RecordedProgram>> = combine(
        _selectedCategory,
        _selectedChannelId,
        _selectedGenre,
        _selectedDay,
        _activeSearchQuery
    ) { category, channelId, genre, day, query ->
        FilterState(category, channelId, genre, day, query)
    }.flatMapLatest { state ->
        Pager(
            config = PagingConfig(
                pageSize = 30,
                prefetchDistance = 10,
                initialLoadSize = 20,
                enablePlaceholders = false
            )
        ) {
            when {
                state.query.isNotBlank() -> programDao.searchPagingSource(state.query)
                state.category == RecordCategory.CHANNEL && !state.channelId.isNullOrEmpty() ->
                    programDao.getPagingSourceByChannel(state.channelId)
                state.category == RecordCategory.GENRE && !state.genre.isNullOrEmpty() ->
                    programDao.getPagingSourceByGenre(state.genre)
                state.category == RecordCategory.TIME && !state.day.isNullOrEmpty() -> {
                    val dayOfWeekStr = when(state.day.replace("曜日", "")) {
                        "日" -> "0"
                        "月" -> "1"
                        "火" -> "2"
                        "水" -> "3"
                        "木" -> "4"
                        "金" -> "5"
                        "土" -> "6"
                        else -> "0"
                    }
                    programDao.getPagingSourceByDayOfWeek(dayOfWeekStr)
                }
                state.category == RecordCategory.UNWATCHED -> programDao.getPagingSourceUnwatched()
                else -> programDao.getAllPagingSource()
            }
        }.flow.map { pagingData ->
            pagingData.map { entity ->
                RecordDataMapper.toDomainModel(entity)
            }
        }
    }.cachedIn(viewModelScope)

    // --- 操作メソッド群 ---

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
        _selectedGenre.value = null; _selectedChannelId.value = null; _selectedDay.value = null
        if (category == RecordCategory.SERIES) buildSeriesIndex()
    }

    fun updateGenre(genre: String?) {
        _selectedGenre.value = genre; _selectedCategory.value = RecordCategory.GENRE
    }

    fun updateDay(day: String?) {
        _selectedDay.value = day; _selectedCategory.value = RecordCategory.TIME
    }

    fun updateChannel(channelName: String?) {
        _selectedChannelId.value = channelName // 便宜上名称を入れる
        _selectedCategory.value = RecordCategory.CHANNEL
        _selectedGenre.value = null
        _selectedDay.value = null
        currentSearchQuery = ""
    }

    fun searchRecordings(query: String) {
        if (_activeSearchQuery.value.isEmpty() && query.isNotEmpty()) _categoryBeforeSearch.value =
            _selectedCategory.value
        _activeSearchQuery.value = query; _searchQuery.value = query; currentSearchQuery = query
        if (query.isNotBlank()) addSearchHistory(query)
        _selectedCategory.value = RecordCategory.ALL
        _selectedGenre.value = null
        _selectedChannelId.value = null
        _selectedDay.value = null
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""; _searchQuery.value = ""; currentSearchQuery = ""
        _categoryBeforeSearch.value?.let {
            _selectedCategory.value = it; _categoryBeforeSearch.value = null
        }
    }

    fun fetchRecentRecordings(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            syncEngine.syncAllRecords(forceFullSync = forceRefresh)
        }
    }

    fun loadNextPage() {}

    // --- 検索履歴・視聴履歴 ---

    private fun loadSearchHistory() {
        try {
            val json = context.getSharedPreferences("search_history_pref", Context.MODE_PRIVATE)
                .getString("history_list", "[]")
            val array = JSONArray(json);
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            _searchHistory.value = list
        } catch (e: Exception) {
            _searchHistory.value = emptyList()
        }
    }

    private fun addSearchHistory(query: String) {
        val list = _searchHistory.value.toMutableList(); list.remove(query); list.add(0, query)
        if (list.size > 5) list.removeAt(list.lastIndex)
        _searchHistory.value = list
        viewModelScope.launch {
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

    // --- ストリーミング用 ---

    @UnstableApi
    fun startStreamMaintenance(
        program: RecordedProgram,
        quality: String,
        sessionId: String,
        getPositionSeconds: () -> Double
    ) {
        stopStreamMaintenance()
        maintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentPos = getPositionSeconds()
                    repository.keepAlive(program.recordedVideo.id, quality, sessionId)
                    historyRepository.saveWatchHistory(program, currentPos)
                } catch (e: Exception) {
                }
                delay(20000)
            }
        }
    }

    fun stopStreamMaintenance() {
        maintenanceJob?.cancel(); maintenanceJob = null
    }

    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> =
        withContext(Dispatchers.IO) {
            repository.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }

    // --- メニュー構築 ---

    fun buildSeriesIndex() {}

    private fun buildSeriesAndChannelMapsFromEntities(entities: List<com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity>) {
        _isSeriesLoading.value = true
        val allSeriesMap = mutableMapOf<String, MutableMap<String, SeriesInfo>>()
        val allChannelMap =
            mutableMapOf<String, MutableMap<String, Triple<String, String, String>>>()
        val genresSet = mutableSetOf<String>()

        try {
            val programs = entities.map { RecordDataMapper.toDomainModel(it) }

            programs.forEach { prog ->
                val genre = prog.genres?.firstOrNull()?.major ?: "その他"
                genresSet.add(genre)

                val displayTitle = TitleNormalizer.extractDisplayTitle(prog.title)
                val searchKeyword = TitleNormalizer.extractSearchKeyword(prog.title)
                if (displayTitle.isNotEmpty()) {
                    val genreMap = allSeriesMap.getOrPut(genre) { mutableMapOf() }
                    val existing = genreMap[displayTitle]
                    if (existing == null) {
                        genreMap[displayTitle] = SeriesInfo(displayTitle, searchKeyword, 1, prog.id)
                    } else {
                        genreMap[displayTitle] = existing.copy(programCount = existing.programCount + 1)
                    }
                }

                prog.channel?.let { ch ->
                    val type = when {
                        ch.type == "GR" -> "地デジ"
                        else -> ch.type
                    }
                    val channelTypeMap = allChannelMap.getOrPut(type) { mutableMapOf() }
                    if (!channelTypeMap.containsKey(ch.id)) {
                        val safeDisplayId = ch.displayChannelId.takeIf { it.isNotBlank() } ?: ch.id
                        channelTypeMap[ch.id] = Triple(ch.name, ch.id, safeDisplayId)
                    }
                }
            }

            _availableGenres.value = genresSet.sorted()
            _groupedSeries.value =
                allSeriesMap.mapValues { entry -> entry.value.values.sortedBy { it.displayTitle } }

            val typePriority = listOf("地デジ", "BS", "BS4K", "CS", "SKY", "その他")
            val extractNumber = { idStr: String ->
                Regex("\\d+").find(idStr)?.value?.toIntOrNull() ?: Int.MAX_VALUE
            }

            _groupedChannels.value = allChannelMap.entries
                .sortedBy { (type, _) ->
                    val index =
                        typePriority.indexOf(type); if (index != -1) index else typePriority.size
                }
                .associate { entry ->
                    entry.key to entry.value.values.sortedWith(
                        compareBy(
                            { extractNumber(it.third) },
                            { it.third }
                        )).map { Pair(it.first, it.second) }
                }

        } catch (e: Exception) {
            android.util.Log.e("RecordVM", "Map Build Error", e)
        } finally {
            _isSeriesLoading.value = false
        }
    }
}