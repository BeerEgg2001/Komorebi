package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.CmSection
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.ui.video.player.ChapterInfo
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

    // ★ 追加: サムネイルタイルURLとチャプター情報の状態管理
    private val _tiledThumbnailUrl = MutableStateFlow<String?>(null)
    val tiledThumbnailUrl: StateFlow<String?> = _tiledThumbnailUrl.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    val chapters: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

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

    // 番組詳細の取得と、それに付随するタイルURL・チャプターの計算
    fun fetchProgramDetail(videoId: Int) {
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // 連打防止
            recordProvider.getRecordedProgram(videoId).onSuccess { program ->
                _programDetail.value = program

                // ★ タイル画像URLの解決と保持
                _tiledThumbnailUrl.value = recordProvider.getTiledThumbnailUrl(videoId)

                // ★ チャプターの計算と保持
                val durationMs = (program.recordedVideo.duration * 1000).toLong()
                val cmSections = program.recordedVideo.cmSections ?: emptyList()
                _chapters.value = calculateChapters(durationMs, cmSections)

            }.onFailure { Log.e(TAG, "Failed to fetch program detail", it) }
        }
    }

    fun clearProgramDetail() {
        _programDetail.value = null
        _tiledThumbnailUrl.value = null
        _chapters.value = emptyList()
    }

    // =======================================================
    // ★ チャプター計算ロジック (SceneSearchOverlayから移管)
    // =======================================================
    private fun calculateChapters(
        durationMs: Long,
        cmSections: List<CmSection>
    ): List<ChapterInfo> {
        if (cmSections.isEmpty()) return emptyList()

        // 1. CM区間をマージして整理
        val sorted = cmSections.sortedBy { it.startTime }
        val mergedCmSections = mutableListOf<CmSection>()
        var currentStart = sorted[0].startTime
        var currentEnd = sorted[0].endTime

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startTime <= currentEnd + 1.0) {
                currentEnd = maxOf(currentEnd, next.endTime)
            } else {
                mergedCmSections.add(CmSection(currentStart, currentEnd))
                currentStart = next.startTime
                currentEnd = next.endTime
            }
        }
        mergedCmSections.add(CmSection(currentStart, currentEnd))

        // 2. 本編/CMの切り替わりポイントを算出
        val boundaries = mutableSetOf(0L, durationMs)
        mergedCmSections.forEach {
            boundaries.add((it.startTime * 1000).toLong())
            boundaries.add((it.endTime * 1000).toLong())
        }
        val sortedBoundaries = boundaries.sorted()

        // 3. チャプター情報のリストを作成
        val list = mutableListOf<ChapterInfo>()
        for (i in 0 until sortedBoundaries.size - 1) {
            val start = sortedBoundaries[i]
            val end = sortedBoundaries[i + 1]

            // 判定にノイズが混ざるのを防ぐため、1秒未満の極端に短い区間はスキップ（末尾以外）
            if (end - start < 2000 && i != sortedBoundaries.size - 2) continue

            // この区間の「ど真ん中」の時間がCMブロックに含まれているかで判定
            val midPoint = (start + end) / 2
            val isCm = mergedCmSections.any { cm ->
                val cmStartMs = (cm.startTime * 1000).toLong()
                val cmEndMs = (cm.endTime * 1000).toLong()
                midPoint in cmStartMs..cmEndMs
            }
            list.add(ChapterInfo(start, end, isCm))
        }
        return list
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