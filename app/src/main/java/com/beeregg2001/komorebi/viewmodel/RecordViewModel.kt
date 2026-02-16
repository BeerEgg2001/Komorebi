package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject

private const val TAG = "Komorebi_RecordVM"
private const val PREF_NAME = "search_history_pref"
private const val KEY_HISTORY = "history_list"

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val historyRepository: WatchHistoryRepository,
    @ApplicationContext private val context: Context // 履歴保存用にContextを注入
) : ViewModel() {

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings.asStateFlow()

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // ★追加: 検索履歴のFlow
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private var currentPage = 1
    private var totalItems = 0
    private var hasMorePages = true
    private val pageSize = 30

    private var currentSearchQuery: String = ""

    init {
        fetchInitialRecordings()
        loadSearchHistory() // ★追加: 初期化時に履歴を読み込む
    }

    fun fetchRecentRecordings() {
        currentSearchQuery = ""
        fetchInitialRecordings()
    }

    fun searchRecordings(query: String) {
        Log.d(TAG, "searchRecordings called. query: '$query'")
        currentSearchQuery = query

        // ★追加: 検索実行時に履歴に保存 (空文字以外)
        if (query.isNotBlank()) {
            addSearchHistory(query)
        }

        fetchInitialRecordings()
    }

    private fun fetchInitialRecordings() {
        viewModelScope.launch {
            _isRecordingLoading.value = true
            try {
                currentPage = 1
                hasMorePages = true

                Log.d(TAG, "Fetching initial recordings. Query: '$currentSearchQuery'")

                val response = if (currentSearchQuery.isNotBlank()) {
                    repository.searchRecordedPrograms(keyword = currentSearchQuery, page = 1)
                } else {
                    repository.getRecordedPrograms(page = 1)
                }

                totalItems = response.total
                Log.d(TAG, "Fetch success. Total items: $totalItems, Returned: ${response.recordedPrograms.size}")

                val initialList = response.recordedPrograms.map { program ->
                    program.copy(isRecording = program.recordedVideo.status == "Recording")
                }

                if (initialList.size < pageSize || (totalItems > 0 && initialList.size >= totalItems)) {
                    hasMorePages = false
                }

                _recentRecordings.value = initialList

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching recordings", e)
                e.printStackTrace()
            } finally {
                _isRecordingLoading.value = false
            }
        }
    }

    fun loadNextPage() {
        if (_isRecordingLoading.value || _isLoadingMore.value || !hasMorePages) {
            return
        }

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val nextPage = currentPage + 1
                Log.d(TAG, "Loading page $nextPage. Query: '$currentSearchQuery'")

                val response = if (currentSearchQuery.isNotBlank()) {
                    repository.searchRecordedPrograms(keyword = currentSearchQuery, page = nextPage)
                } else {
                    repository.getRecordedPrograms(page = nextPage)
                }

                val newItems = response.recordedPrograms.map { program ->
                    program.copy(isRecording = program.recordedVideo.status == "Recording")
                }

                if (newItems.isNotEmpty()) {
                    val currentList = _recentRecordings.value.toMutableList()
                    currentList.addAll(newItems)
                    _recentRecordings.value = currentList

                    currentPage = nextPage
                }

                if (newItems.size < pageSize || (totalItems > 0 && _recentRecordings.value.size >= totalItems)) {
                    hasMorePages = false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading next page", e)
                e.printStackTrace()
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    // --- 検索履歴管理ロジック ---

    private fun loadSearchHistory() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_HISTORY, "[]")
            val jsonArray = JSONArray(jsonString)
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            _searchHistory.value = list
        } catch (e: Exception) {
            e.printStackTrace()
            _searchHistory.value = emptyList()
        }
    }

    private fun addSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        currentList.remove(query)
        currentList.add(0, query)
        // ★修正: 保存件数を5件に制限
        if (currentList.size > 5) {
            currentList.removeAt(currentList.lastIndex)
        }
        _searchHistory.value = currentList
        saveSearchHistory(currentList)
    }

    // 必要であればUIから呼び出して個別に削除する用
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // -------------------------

    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch {
            historyRepository.saveWatchHistory(program, positionSeconds)
        }
    }

    @UnstableApi
    fun keepAliveStream(videoId: Int, quality: String, sessionId: String) {
        viewModelScope.launch {
            try {
                repository.keepAlive(videoId, quality, sessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ★追加: アーカイブコメント取得
    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            repository.getArchivedJikkyo(videoId)
                .getOrDefault(emptyList())
                .sortedBy { it.time }
        }
    }
}