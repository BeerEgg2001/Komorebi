package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val recordProvider: RecordProvider,
    private val historyRepository: WatchHistoryRepository
) : ViewModel() {

    companion object {
        private const val TAG = "VideoPlayerViewModel"
    }

    // 番組詳細データ
    private val _programDetail = MutableStateFlow<RecordedProgram?>(null)
    val programDetail: StateFlow<RecordedProgram?> = _programDetail.asStateFlow()

    private var detailFetchJob: Job? = null
    private var streamMaintenanceJob: Job? = null

    // URLの解決
    suspend fun resolveStreamUrl(videoId: Int, quality: String, sessionId: String): String {
        return try {
            withContext(Dispatchers.IO) {
                recordProvider.getRecordStreamUrl(videoId, quality, sessionId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream URL", e)
            ""
        }
    }

    // 番組詳細の取得
    fun fetchProgramDetail(videoId: Int) {
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // 連打防止
            recordProvider.getRecordedProgram(videoId).onSuccess {
                _programDetail.value = it
            }.onFailure { Log.e(TAG, "Failed to fetch program detail", it) }
        }
    }

    fun clearProgramDetail() {
        _programDetail.value = null
    }

    // 過去コメント（ニコニコ実況等）の取得
    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            recordProvider.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }
    }

    // 視聴履歴の更新
    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.saveWatchHistory(program, positionSeconds)
        }
    }

    // Keep-Alive通信（KonomiTV等でのエンコード維持）
    @UnstableApi
    fun startStreamMaintenance(
        program: RecordedProgram,
        quality: String,
        sessionId: String,
        currentPositionProvider: () -> Double
    ) {
        streamMaintenanceJob?.cancel()
        streamMaintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    recordProvider.keepAlive(
                        videoId = program.recordedVideo.id,
                        sessionId = sessionId,
                        quality = quality
                    )
                    Log.d(TAG, "Keep-Alive sent. Session: $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send Keep-Alive", e)
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
        detailFetchJob?.cancel()
    }
}