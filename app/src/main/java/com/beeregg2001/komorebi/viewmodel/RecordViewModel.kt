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
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
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

    init {
        fetchInitialRecordings()
    }

    fun fetchRecentRecordings() {
        fetchInitialRecordings()
    }

    private fun fetchInitialRecordings() {
        viewModelScope.launch {
            _isRecordingLoading.value = true
            try {
                currentPage = 1
                hasMorePages = true
                val response = repository.getRecordedPrograms(page = 1)

                totalItems = response.total

                // ★修正: 取得したリストに対して録画中判定を行う
                val initialList = response.recordedPrograms.map { program ->
                    program.copy(isRecording = checkIsRecording(program.endTime))
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
                val response = repository.getRecordedPrograms(page = nextPage)

                // ★修正: 追加取得したリストに対しても録画中判定を行う
                val newItems = response.recordedPrograms.map { program ->
                    program.copy(isRecording = checkIsRecording(program.endTime))
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

    /**
     * ★追加: 終了時刻文字列を現在時刻と比較して録画中かどうかを判定する
     * ISO 8601形式などを想定 (APIの仕様に合わせて調整してください)
     */
    private fun checkIsRecording(endTimeStr: String): Boolean {
        // API Level 26 (Android O) 以上が前提
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // 多くのJSON APIで使用されるISO形式パーサーを使用
                // ※フォーマットが特殊な場合はDateTimeFormatter.ofPatternなどで調整してください
                val endInstant = try {
                    Instant.parse(endTimeStr)
                } catch (e: DateTimeParseException) {
                    // ISO形式でパースできない場合のフォールバック（必要に応じて）
                    return false
                }

                // 現在時刻が終了時刻より前であれば「録画中」
                return Instant.now().isBefore(endInstant)
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }
        return false
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