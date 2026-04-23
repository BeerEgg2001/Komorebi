package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.CmSection
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
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
import kotlinx.coroutines.CancellationException

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val recordProvider: RecordProvider,
    private val historyRepository: WatchHistoryRepository
) : ViewModel() {

    companion object {
        private const val TAG = "VideoPlayerViewModel"
    }

    private val _programDetail = MutableStateFlow<RecordedProgram?>(null)
    val programDetail: StateFlow<RecordedProgram?> = _programDetail.asStateFlow()

    private val _tiledThumbnailUrl = MutableStateFlow<String?>(null)
    val tiledThumbnailUrl: StateFlow<String?> = _tiledThumbnailUrl.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    val chapters: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

    private val _isLiveStream = MutableStateFlow(false)
    val isLiveStream: StateFlow<Boolean> = _isLiveStream.asStateFlow()

    private val _availableQualities =
        MutableStateFlow<List<StreamQuality>>(StreamQuality.DEFAULT_QUALITIES)
    val availableQualities: StateFlow<List<StreamQuality>> = _availableQualities.asStateFlow()

    private var detailFetchJob: Job? = null
    private var streamMaintenanceJob: Job? = null

    fun fetchAvailableQualities() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val qualities = recordProvider.getStreamQualities()
                if (qualities.isNotEmpty()) {
                    _availableQualities.value = qualities
                    Log.i(TAG, "Loaded dynamic stream qualities: ${qualities.size} options")
                } else {
                    _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stream qualities", e)
                _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
            }
        }
    }

    suspend fun resolveStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double = 0.0
    ): String {
        return try {
            withContext(Dispatchers.IO) {
                val url =
                    recordProvider.getRecordStreamUrl(videoId, quality, sessionId, offsetSeconds)
                _isLiveStream.value = url.contains("/api/xcode") && quality != "10"
                url
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream URL", e)
            ""
        }
    }

    fun fetchProgramDetail(videoId: Int) {
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            recordProvider.getRecordedProgram(videoId).onSuccess { program ->
                _programDetail.value = program

                Log.i(TAG, "[DataCheck] Fetched Program Detail. Title: ${program.title}")
                Log.i(
                    TAG,
                    "[DataCheck] CM Sections from API: ${program.recordedVideo.cmSections?.size ?: 0} sections found."
                )

                val tileUrl = recordProvider.getTiledThumbnailUrl(videoId)
                _tiledThumbnailUrl.value = tileUrl

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
        _isLiveStream.value = false
    }

    private fun calculateChapters(
        durationMs: Long,
        cmSections: List<CmSection>
    ): List<ChapterInfo> {
        if (cmSections.isEmpty()) return emptyList()

        val sortedMs = cmSections.map {
            CmSection(it.startTime * 1000.0, it.endTime * 1000.0)
        }.sortedBy { it.startTime }

        val mergedCmSections = mutableListOf<CmSection>()
        var currentStart = sortedMs[0].startTime
        var currentEnd = sortedMs[0].endTime

        for (i in 1 until sortedMs.size) {
            val next = sortedMs[i]
            if (next.startTime <= currentEnd + 2000.0) {
                currentEnd = maxOf(currentEnd, next.endTime)
            } else {
                mergedCmSections.add(CmSection(currentStart, currentEnd))
                currentStart = next.startTime
                currentEnd = next.endTime
            }
        }
        mergedCmSections.add(CmSection(currentStart, currentEnd))

        val boundaries = mutableSetOf(0L, durationMs)
        mergedCmSections.forEach {
            boundaries.add(it.startTime.toLong())
            boundaries.add(it.endTime.toLong())
        }
        val sortedBoundaries = boundaries.sorted()

        val list = mutableListOf<ChapterInfo>()
        for (i in 0 until sortedBoundaries.size - 1) {
            val start = sortedBoundaries[i]
            val end = sortedBoundaries[i + 1]

            if (end - start < 1000 && i != sortedBoundaries.size - 2) continue

            val midPoint = (start + end) / 2
            val isCm = mergedCmSections.any { cm ->
                midPoint >= cm.startTime.toLong() && midPoint <= cm.endTime.toLong()
            }
            list.add(ChapterInfo(start, end, isCm))
        }

        Log.i(
            TAG,
            "[DataCheck] Calculated ${list.size} chapters. (CM count: ${list.count { it.isCm }})"
        )
        return list
    }

    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            recordProvider.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }
    }

    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.saveWatchHistory(program, positionSeconds)
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
        streamMaintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    recordProvider.keepAlive(
                        videoId = program.recordedVideo.id,
                        sessionId = sessionId,
                        quality = quality
                    )
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