package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val historyRepository: WatchHistoryRepository
) : ViewModel() {

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings.asStateFlow()

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var currentPage = 1
    private var totalItems = 0
    private var hasMorePages = true
    private val pageSize = 30

    // ★追加: 現在の検索キーワード（空文字の場合は通常の一覧表示）
    private var currentSearchQuery: String = ""

    init {
        fetchInitialRecordings()
    }

    fun fetchRecentRecordings() {
        // 通常更新時は検索をクリアする
        currentSearchQuery = ""
        fetchInitialRecordings()
    }

    // ★追加: 番組検索を実行する関数
    fun searchRecordings(query: String) {
        currentSearchQuery = query
        fetchInitialRecordings()
    }

    private fun fetchInitialRecordings() {
        viewModelScope.launch {
            _isRecordingLoading.value = true
            try {
                currentPage = 1
                hasMorePages = true

                // ★修正: 検索キーワードの有無でAPIを切り替え
                val response = if (currentSearchQuery.isNotBlank()) {
                    repository.searchRecordedPrograms(keyword = currentSearchQuery, page = 1)
                } else {
                    repository.getRecordedPrograms(page = 1)
                }

                totalItems = response.total

                // JSONのstatusフィールドを使って録画中判定を行う
                val initialList = response.recordedPrograms.map { program ->
                    program.copy(isRecording = program.recordedVideo.status == "Recording")
                }

                if (initialList.size < pageSize || (totalItems > 0 && initialList.size >= totalItems)) {
                    hasMorePages = false
                }

                _recentRecordings.value = initialList

            } catch (e: Exception) {
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

                // ★修正: 検索キーワードの有無でAPIを切り替え
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
                e.printStackTrace()
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

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
}