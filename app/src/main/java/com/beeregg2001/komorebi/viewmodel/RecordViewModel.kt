package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.ui.video.components.RecordCategory
import com.beeregg2001.komorebi.util.TitleNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

private const val TAG = "Komorebi_RecordVM"
private const val PREF_NAME = "search_history_pref"
private const val KEY_HISTORY = "history_list"

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val historyRepository: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // --- 1. 状態の定義 (combineより上に記述) ---

    private val _allRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())

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
    val categoryBeforeSearch: StateFlow<RecordCategory?> = _categoryBeforeSearch.asStateFlow()

    private val _selectedSeriesGenre = MutableStateFlow<String?>(null)
    val selectedSeriesGenre: StateFlow<String?> = _selectedSeriesGenre.asStateFlow()

    // --- 2. 表示形式のリアクティブ管理 (設定反映の修正箇所) ---

    private val _manualListViewOverride = MutableStateFlow<Boolean?>(null)

    val isListView: StateFlow<Boolean> = combine(
        settingsRepository.defaultRecordListView,
        _manualListViewOverride
    ) { defaultType, manualOverride ->
        // 手動での切り替えがある場合はそれを優先、なければ設定値を反映
        manualOverride ?: (defaultType == "LIST")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- 3. 結合Flow (型不整合の修正箇所) ---

    @RequiresApi(Build.VERSION_CODES.O)
    val recentRecordings: StateFlow<List<RecordedProgram>> = combine(
        _allRecordings,
        _selectedCategory,
        _selectedGenre,
        _selectedChannelId,
        _selectedDay,
        _activeSearchQuery
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val recordings = values[0] as List<RecordedProgram>
        @Suppress("UNCHECKED_CAST")
        val category = values[1] as RecordCategory
        @Suppress("UNCHECKED_CAST")
        val genre = values[2] as String?
        @Suppress("UNCHECKED_CAST")
        val channelId = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val day = values[4] as String?
        // values[5] は activeSearchQuery (再計算のトリガーとして使用)

        when (category) {
            RecordCategory.ALL -> recordings
            RecordCategory.UNWATCHED -> {
                recordings.filter { it.playbackPosition < 5.0 }
            }
            RecordCategory.GENRE -> {
                if (genre.isNullOrEmpty()) recordings
                else recordings.filter { prog ->
                    prog.genres?.any { g -> g.major == genre } == true
                }
            }
            RecordCategory.CHANNEL -> {
                if (channelId.isNullOrEmpty()) recordings
                else recordings.filter { it.channel?.id == channelId }
            }
            RecordCategory.TIME -> {
                if (day.isNullOrEmpty()) recordings
                else recordings.filter { prog -> getDayOfWeekString(prog.startTime) == day }
            }
            else -> recordings
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // --- その他非同期状態 ---

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres: StateFlow<List<String>> = _availableGenres.asStateFlow()

    private val _groupedSeries = MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedSeries: StateFlow<Map<String, List<Pair<String, String>>>> = _groupedSeries.asStateFlow()

    private val _groupedChannels = MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Pair<String, String>>>> = _groupedChannels.asStateFlow()

    private var currentPage = 1
    private var totalItems = 0
    private var hasMorePages = true
    private val pageSize = 30
    private var currentSearchQuery: String = ""
    private var maintenanceJob: Job? = null

    init {
        loadSearchHistory()
        fetchRecentRecordings(forceRefresh = true)
        buildSeriesAndChannelMaps()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getDayOfWeekString(startTime: String): String {
        return try {
            val zdt = ZonedDateTime.parse(startTime)
            zdt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.JAPANESE)
        } catch (e: Exception) { "" }
    }

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
        fetchInitialRecordings()
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
        fetchInitialRecordings(clearData = true)
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        currentSearchQuery = ""
        if (!isListView.value) {
            updateCategory(RecordCategory.ALL)
        } else {
            _categoryBeforeSearch.value?.let {
                _selectedCategory.value = it
                _categoryBeforeSearch.value = null
            }
        }
        fetchInitialRecordings(clearData = true)
    }

    fun fetchRecentRecordings(forceRefresh: Boolean = false) {
        if (!forceRefresh && currentSearchQuery.isNotEmpty()) return
        currentSearchQuery = ""
        fetchInitialRecordings(clearData = false)
    }

    private fun fetchInitialRecordings(clearData: Boolean = true) {
        viewModelScope.launch {
            _isRecordingLoading.value = true
            if (clearData) _allRecordings.value = emptyList()
            try {
                currentPage = 1
                hasMorePages = true
                val response = if (currentSearchQuery.isNotBlank()) {
                    repository.searchRecordedPrograms(keyword = currentSearchQuery, page = 1)
                } else {
                    repository.getRecordedPrograms(page = 1)
                }

                totalItems = response.total
                val allFetchedPrograms = response.recordedPrograms.toMutableList()

                val totalPages = if (totalItems <= 0) 1 else (totalItems + pageSize - 1) / pageSize
                if (totalPages > 1) {
                    for (p in 2..totalPages) {
                        val nextResponse = if (currentSearchQuery.isNotBlank()) {
                            repository.searchRecordedPrograms(keyword = currentSearchQuery, page = p)
                        } else {
                            repository.getRecordedPrograms(page = p)
                        }
                        allFetchedPrograms.addAll(nextResponse.recordedPrograms)
                    }
                }

                val ids = allFetchedPrograms.map { it.id }
                val historyEntities = repository.getHistoryEntitiesByIds(ids)
                val historyMap: Map<Int, Double> = historyEntities.associate { it.id to it.playbackPosition }

                val initialList = allFetchedPrograms.map { program ->
                    program.copy(
                        isRecording = program.recordedVideo.status == "Recording",
                        playbackPosition = historyMap[program.id] ?: 0.0
                    )
                }

                hasMorePages = false
                currentPage = totalPages
                _allRecordings.value = initialList
            } catch (e: Exception) {
                Log.e(TAG, "Initial fetch failed", e)
            } finally {
                _isRecordingLoading.value = false
            }
        }
    }

    fun loadNextPage() {
        if (_isRecordingLoading.value || _isLoadingMore.value || !hasMorePages) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val nextPage = currentPage + 1
                val response = if (currentSearchQuery.isNotBlank()) {
                    repository.searchRecordedPrograms(keyword = currentSearchQuery, page = nextPage)
                } else {
                    repository.getRecordedPrograms(page = nextPage)
                }

                val programs = response.recordedPrograms
                val ids = programs.map { it.id }
                val historyEntities = repository.getHistoryEntitiesByIds(ids)
                val historyMap: Map<Int, Double> = historyEntities.associate { it.id to it.playbackPosition }

                val newItems = programs.map { program ->
                    program.copy(
                        isRecording = program.recordedVideo.status == "Recording",
                        playbackPosition = historyMap[program.id] ?: 0.0
                    )
                }

                if (newItems.isNotEmpty()) {
                    val currentList = _allRecordings.value.toMutableList()
                    currentList.addAll(newItems)
                    _allRecordings.value = currentList
                    currentPage = nextPage
                }
                if (newItems.size < pageSize || (totalItems > 0 && _allRecordings.value.size >= totalItems)) {
                    hasMorePages = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pagination load failed", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private fun loadSearchHistory() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_HISTORY, "[]")
            val jsonArray = JSONArray(jsonString)
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) { list.add(jsonArray.getString(i)) }
            _searchHistory.value = list
        } catch (e: Exception) {
            _searchHistory.value = emptyList()
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
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) { }
        }
    }

    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch { historyRepository.saveWatchHistory(program, positionSeconds) }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            try { historyRepository.clearWatchHistory() } catch (e: Exception) { }
        }
    }

    @UnstableApi
    fun startStreamMaintenance(program: RecordedProgram, quality: String, sessionId: String, getPositionSeconds: () -> Double) {
        stopStreamMaintenance()
        maintenanceJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val currentPos = getPositionSeconds()
                    repository.keepAlive(program.recordedVideo.id, quality, sessionId)
                    historyRepository.saveWatchHistory(program, currentPos)
                } catch (e: Exception) { }
                delay(20000)
            }
        }
    }

    fun stopStreamMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopStreamMaintenance()
    }

    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            repository.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }
    }

    fun buildSeriesIndex() {
        if (_groupedSeries.value.isNotEmpty() || _isSeriesLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSeriesLoading.value = true
            val allSeriesMap = mutableMapOf<String, MutableMap<String, Pair<String, String>>>()
            var page = 1
            try {
                while (true) {
                    val response = repository.getRecordedPrograms(page = page)
                    if (response.recordedPrograms.isEmpty()) break
                    response.recordedPrograms.forEach { prog ->
                        val genre = prog.genres?.firstOrNull()?.major ?: "その他"
                        val displayTitle = TitleNormalizer.extractDisplayTitle(prog.title)
                        val searchKeyword = TitleNormalizer.extractSearchKeyword(prog.title)
                        if (displayTitle.isNotEmpty()) {
                            val genreMap = allSeriesMap.getOrPut(genre) { mutableMapOf() }
                            if (!genreMap.containsKey(displayTitle)) {
                                genreMap[displayTitle] = Pair(displayTitle, searchKeyword)
                            }
                        }
                    }
                    if (response.recordedPrograms.size < 30) break
                    page++
                }
                _groupedSeries.value = allSeriesMap.mapValues { entry -> entry.value.values.sortedBy { it.first } }
            } catch (e: Exception) { } finally { _isSeriesLoading.value = false }
        }
    }

    private fun buildSeriesAndChannelMaps() {
        viewModelScope.launch {
            _isSeriesLoading.value = true
            val allSeriesMap = mutableMapOf<String, MutableMap<String, Pair<String, String>>>()
            val allChannelMap = mutableMapOf<String, MutableMap<String, Triple<String, String, String>>>()
            val genresSet = mutableSetOf<String>()
            var page = 1

            try {
                while (true) {
                    val response = repository.getRecordedPrograms(page = page)
                    if (response.recordedPrograms.isEmpty()) break

                    response.recordedPrograms.forEach { prog ->
                        val genre = prog.genres?.firstOrNull()?.major ?: "その他"
                        genresSet.add(genre)
                        val displayTitle = TitleNormalizer.extractDisplayTitle(prog.title)
                        val searchKeyword = TitleNormalizer.extractSearchKeyword(prog.title)
                        if (displayTitle.isNotEmpty()) {
                            val genreMap = allSeriesMap.getOrPut(genre) { mutableMapOf() }
                            if (!genreMap.containsKey(displayTitle)) {
                                genreMap[displayTitle] = Pair(displayTitle, searchKeyword)
                            }
                        }

                        prog.channel?.let { ch ->
                            val type = when {
                                ch.type == "GR" -> "地デジ"
                                else -> ch.type
                            }
                            val channelTypeMap = allChannelMap.getOrPut(type) { mutableMapOf() }
                            if (!channelTypeMap.containsKey(ch.id)) {
                                channelTypeMap[ch.id] = Triple(ch.name, ch.id, ch.displayChannelId)
                            }
                        }
                    }
                    if (response.recordedPrograms.size < 30) break
                    page++
                }

                _availableGenres.value = genresSet.sorted()
                _groupedSeries.value = allSeriesMap.mapValues { entry -> entry.value.values.sortedBy { it.first } }

                val typePriority = listOf("地デジ", "BS", "BS4K", "CS", "SKY", "その他")
                _groupedChannels.value = allChannelMap.entries
                    .sortedBy { (type, _) -> val index = typePriority.indexOf(type); if (index != -1) index else typePriority.size }
                    .associate { entry ->
                        entry.key to entry.value.values.sortedBy { it.third }.map { Pair(it.first, it.second) }
                    }
            } catch (e: Exception) { } finally { _isSeriesLoading.value = false }
        }
    }
}