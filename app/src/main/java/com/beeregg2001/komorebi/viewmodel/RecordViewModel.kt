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

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val historyRepository: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
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
    private val _manualListViewOverride = MutableStateFlow<Boolean?>(null)
    val isListView: StateFlow<Boolean> =
        combine(settingsRepository.defaultRecordListView, _manualListViewOverride) { d, m ->
            m ?: (d == "LIST")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private val _selectedSeriesGenre = MutableStateFlow<String?>(null)
    val selectedSeriesGenre: StateFlow<String?> = _selectedSeriesGenre.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.O)
    val recentRecordings: StateFlow<List<RecordedProgram>> = combine(
        _allRecordings,
        _selectedCategory,
        _selectedGenre,
        _selectedDay
    ) { recs, cat, genre, day ->
        when (cat) {
            RecordCategory.ALL, RecordCategory.CHANNEL -> recs // CHANNEL時はAPI検索済みのrecsをそのまま返す
            RecordCategory.UNWATCHED -> recs.filter { it.playbackPosition < 5.0 }
            RecordCategory.GENRE -> if (genre.isNullOrEmpty()) recs else recs.filter { p -> p.genres?.any { it.major == genre } == true }
            RecordCategory.TIME -> if (day.isNullOrEmpty()) recs else recs.filter {
                getDayOfWeekString(
                    it.startTime
                ) == day
            }

            else -> recs
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()
    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres: StateFlow<List<String>> = _availableGenres.asStateFlow()
    private val _groupedSeries =
        MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedSeries: StateFlow<Map<String, List<Pair<String, String>>>> =
        _groupedSeries.asStateFlow()
    private val _groupedChannels =
        MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Pair<String, String>>>> =
        _groupedChannels.asStateFlow()
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()
    private var currentPage = 1
    private var totalItems = 0
    private var hasMorePages = true
    private val pageSize = 30
    private var currentSearchQuery: String = ""
    private var maintenanceJob: Job? = null

    init {
        loadSearchHistory()
        fetchMasterChannels() // 番組を走査せずマスターから取得
        fetchRecentRecordings(forceRefresh = true)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun fetchMasterChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = repository.getChannels()
                val allChannels = listOfNotNull(
                    response.terrestrial,
                    response.bs,
                    response.cs,
                    response.sky,
                    response.bs4k
                ).flatten()
                val typePriority = listOf("GR", "BS", "BS4K", "CS", "SKY")
                val mapped = allChannels.filter { it.isDisplay }.groupBy { it.type }.entries
                    .sortedBy { (type, _) ->
                        val idx =
                            typePriority.indexOf(type); if (idx != -1) idx else typePriority.size
                    }
                    .associate { entry ->
                        val label = when (entry.key) {
                            "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; "BS4K" -> "BS4K"; "SKY" -> "スカパー"; else -> entry.key
                        }
                        // Pair の second にチャンネル名をセットして、検索キーワードとして使えるようにする
                        label to entry.value.sortedBy { it.displayChannelId ?: it.id }
                            .map { it.name to it.name }
                    }
                _groupedChannels.value = mapped
            } catch (e: Exception) {
                Log.e("RecordVM", "Master fetch failed", e)
            }
        }
    }

    private fun fetchInitialRecordings(clearData: Boolean = true) {
        viewModelScope.launch {
            _isRecordingLoading.value = true
            if (clearData) _allRecordings.value = emptyList()
            try {
                currentPage = 1
                val response =
                    if (currentSearchQuery.isNotBlank()) repository.searchRecordedPrograms(
                        currentSearchQuery,
                        1
                    ) else repository.getRecordedPrograms(page = 1)
                totalItems = response.total
                val ids = response.recordedPrograms.map { it.id }
                val historyMap = repository.getHistoryEntitiesByIds(ids)
                    .associate { it.id to it.playbackPosition }
                val initialList = response.recordedPrograms.map {
                    it.copy(
                        isRecording = it.recordedVideo.status == "Recording",
                        playbackPosition = historyMap[it.id] ?: 0.0
                    )
                }
                _allRecordings.value = initialList
                hasMorePages = totalItems > pageSize
            } catch (e: Exception) {
                Log.e("RecordVM", "Initial fetch failed", e)
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
                val response =
                    if (currentSearchQuery.isNotBlank()) repository.searchRecordedPrograms(
                        currentSearchQuery,
                        nextPage
                    ) else repository.getRecordedPrograms(page = nextPage)
                val ids = response.recordedPrograms.map { it.id }
                val historyMap = repository.getHistoryEntitiesByIds(ids)
                    .associate { it.id to it.playbackPosition }
                val newItems = response.recordedPrograms.map {
                    it.copy(
                        isRecording = it.recordedVideo.status == "Recording",
                        playbackPosition = historyMap[it.id] ?: 0.0
                    )
                }
                if (newItems.isNotEmpty()) {
                    _allRecordings.value += newItems
                    currentPage = nextPage
                }
                if (newItems.size < pageSize || _allRecordings.value.size >= totalItems) hasMorePages =
                    false
            } catch (e: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getDayOfWeekString(startTime: String): String {
        return try {
            ZonedDateTime.parse(startTime).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.JAPANESE)
        } catch (e: Exception) {
            ""
        }
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
        _selectedGenre.value = null; _selectedDay.value = null
        currentSearchQuery = channelName ?: "" // チャンネル名を検索キーワードに設定
        fetchInitialRecordings(clearData = true)
    }

    fun searchRecordings(query: String) {
        if (_activeSearchQuery.value.isEmpty() && query.isNotEmpty()) _categoryBeforeSearch.value =
            _selectedCategory.value
        _activeSearchQuery.value = query; _searchQuery.value = query; currentSearchQuery = query
        if (query.isNotBlank()) addSearchHistory(query)
        _selectedCategory.value = RecordCategory.ALL
        fetchInitialRecordings(clearData = true)
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""; _searchQuery.value = ""; currentSearchQuery = ""
        _categoryBeforeSearch.value?.let {
            _selectedCategory.value = it; _categoryBeforeSearch.value = null
        }
        fetchInitialRecordings(clearData = true)
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            try { historyRepository.clearWatchHistory() } catch (e: Exception) { }
        }
    }

    fun fetchRecentRecordings(forceRefresh: Boolean = false) {
        if (!forceRefresh && currentSearchQuery.isNotEmpty()) return
        currentSearchQuery = ""
        fetchInitialRecordings(clearData = false)
    }

    private fun loadSearchHistory() {
        try {
            val json = context.getSharedPreferences("search_history_pref", Context.MODE_PRIVATE)
                .getString("history_list", "[]")
            val array = JSONArray(json);
            val list = ArrayList<String>()
            for (i in 0 until array.length()) list.add(array.getString(i))
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
                context.getSharedPreferences("search_history_pref", Context.MODE_PRIVATE).edit()
                    .putString("history_list", JSONArray(list).toString()).apply()
            } catch (e: Exception) {
            }
        }
    }

    fun updateWatchHistory(p: RecordedProgram, pos: Double) {
        viewModelScope.launch { historyRepository.saveWatchHistory(p, pos) }
    }

    @UnstableApi
    fun startStreamMaintenance(p: RecordedProgram, q: String, sid: String, getPos: () -> Double) {
        stopStreamMaintenance()
        maintenanceJob = viewModelScope.launch {
            while (isActive) {
                try {
                    repository.keepAlive(
                        p.recordedVideo.id,
                        q,
                        sid
                    ); historyRepository.saveWatchHistory(p, getPos())
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

    fun buildSeriesIndex() {
        if (_groupedSeries.value.isNotEmpty() || _isSeriesLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSeriesLoading.value = true
            val map = mutableMapOf<String, MutableMap<String, Pair<String, String>>>()
            val genres = mutableSetOf<String>()
            var page = 1
            try {
                while (true) {
                    val response = repository.getRecordedPrograms(page = page)
                    if (response.recordedPrograms.isEmpty()) break
                    response.recordedPrograms.forEach { p ->
                        val g = p.genres?.firstOrNull()?.major ?: "その他"; genres.add(g)
                        val title = TitleNormalizer.extractDisplayTitle(p.title)
                        if (title.isNotEmpty()) map.getOrPut(g) { mutableMapOf() }.getOrPut(title) {
                            Pair(
                                title,
                                TitleNormalizer.extractSearchKeyword(p.title)
                            )
                        }
                    }
                    if (response.recordedPrograms.size < 30) break
                    page++
                }
                _availableGenres.value = genres.sorted()
                _groupedSeries.value = map.mapValues { it.value.values.sortedBy { p -> p.first } }
            } catch (e: Exception) {
            } finally {
                _isSeriesLoading.value = false
            }
        }
    }
}