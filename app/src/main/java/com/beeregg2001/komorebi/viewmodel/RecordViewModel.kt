package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val historyRepository: WatchHistoryRepository // ★追加: 履歴リポジトリを注入
) : ViewModel() {

    // 録画リスト（全ページ分を蓄積）
    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings.asStateFlow()

    // 初回読み込み中フラグ
    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()

    // 追加読み込み中フラグ（ページネーション用）
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // ページネーション管理用
    private var currentPage = 1
    private var totalItems = 0
    private var hasMorePages = true
    private val pageSize = 30

    init {
        fetchInitialRecordings()
    }

    /**
     * 初回ロード（1ページ目）
     */
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
                val initialList = response.recordedPrograms

                // ページネーション終了判定
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

    /**
     * 次のページを読み込む（スクロール時に呼ばれる）
     */
    fun loadNextPage() {
        // 読み込み中、またはこれ以上ページがない場合は何もしない
        if (_isRecordingLoading.value || _isLoadingMore.value || !hasMorePages) {
            return
        }

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val nextPage = currentPage + 1
                val response = repository.getRecordedPrograms(page = nextPage)

                val newItems = response.recordedPrograms

                if (newItems.isNotEmpty()) {
                    // 既存のリストに追加
                    val currentList = _recentRecordings.value.toMutableList()
                    currentList.addAll(newItems)
                    _recentRecordings.value = currentList

                    currentPage = nextPage
                }

                // APIのtotal情報、または取得件数で終了判定
                // totalが0で定義されている場合も考慮し、取得数がページサイズ未満なら終了とする
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
     * ★追加: 視聴履歴を更新する
     */
    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch {
            historyRepository.saveWatchHistory(program, positionSeconds)
        }
    }

    /**
     * ★追加: 録画ストリームを維持する
     */
    fun keepAliveStream(videoId: Int, quality: String, sessionId: String) {
        viewModelScope.launch {
            try {
                // KonomiRepository経由でApiのkeepAliveを呼ぶように実装してください
                repository.keepAlive(videoId, quality, sessionId)
            } catch (e: Exception) {
                // バックグラウンド処理なのでログ出力のみでOK
                e.printStackTrace()
            }
        }
    }
}