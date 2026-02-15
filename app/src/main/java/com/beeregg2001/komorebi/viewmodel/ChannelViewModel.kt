package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.OffsetDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // UIが購読する最終的な「計算済み」リスト
    private val _liveRows = MutableStateFlow<List<LiveRowState>>(emptyList())
    val liveRows: StateFlow<List<LiveRowState>> = _liveRows.asStateFlow()

    private val _groupedChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Channel>>> = _groupedChannels

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading

    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()

    private var pollingJob: Job? = null
    private var progressUpdateJob: Job? = null

    init {
        startPolling()
        startProgressUpdater() // プログレスバー更新タイマー開始
    }

    /**
     * 進行度(Progress)とUI表示用モデルをバックグラウンドで一括生成
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun transformToUiState(grouped: Map<String, List<Channel>>): List<LiveRowState> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        grouped.map { (type, channels) ->
            LiveRowState(
                genreId = type,
                genreLabel = when(type) { "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; "BS4K" -> "BS4K"; "SKY" -> "スカパー"; else -> type },
                channels = channels.map { ch ->
                    val start = ch.programPresent?.startTime?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
                    val dur = ch.programPresent?.duration ?: 0
                    val progress = if (start > 0 && dur > 0) {
                        ((now - start).toFloat() / (dur * 1000).toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    UiChannelState(
                        channel = ch,
                        displayChannelId = ch.displayChannelId,
                        name = ch.name,
                        programTitle = ch.programPresent?.title ?: "放送休止中",
                        progress = progress,
                        hasProgram = ch.programPresent != null
                    )
                }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun fetchChannelsInternal() {
        try {
            _connectionError.value = false
            val response = repository.getChannels()
            val processed = withContext(Dispatchers.Default) {
                val all = mutableListOf<Channel>()
                response.terrestrial?.let { all.addAll(it) }
                response.bs?.let { all.addAll(it) }
                response.cs?.let { all.addAll(it) }
                response.sky?.let { all.addAll(it) }
                response.bs4k?.let { all.addAll(it) }
                all.filter { it.isDisplay }.groupBy { it.type }
            }
            _groupedChannels.value = processed
            // UI用モデルを生成して反映
            _liveRows.value = transformToUiState(processed)
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionError.value = true
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * プログレスバーを定期的に更新（UIスレッドを介さず計算）
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000L) // 15秒に1回更新で十分「リアルタイム」です
                if (_groupedChannels.value.isNotEmpty()) {
                    _liveRows.value = transformToUiState(_groupedChannels.value)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun fetchChannels() {
        _isLoading.value = true
        viewModelScope.launch { fetchChannelsInternal() }
    }

    fun fetchRecentRecordings() {
        _isRecordingLoading.value = true
        viewModelScope.launch {
            try {
                val response = repository.getRecordedPrograms(page = 1)
                _recentRecordings.value = response.recordedPrograms
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRecordingLoading.value = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchChannelsInternal()
                delay(60_000L) // チャンネル情報の更新は1分間隔に緩和（負荷軽減）
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        progressUpdateJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            val entity = WatchHistoryEntity(
                id = program.id, title = program.title, description = program.description,
                startTime = program.startTime, endTime = program.endTime, duration = program.duration,
                videoId = program.recordedVideo.id, watchedAt = System.currentTimeMillis()
            )
            repository.saveToLocalHistory(entity)
        }
    }
}