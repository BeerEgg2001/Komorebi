package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val repository: KonomiRepository // リポジトリを注入
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _groupedChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Channel>>> = _groupedChannels

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading

    // ★追加: 接続エラーフラグ（チャンネルリスト取得失敗時）
    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()

    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    private suspend fun fetchChannelsInternal() {
        try {
            // 処理開始時はエラーをリセット
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
        } catch (e: Exception) {
            e.printStackTrace()
            // ★追加: エラー発生時にフラグを立てる
            _connectionError.value = true
        } finally {
            _isLoading.value = false
        }
    }

    fun fetchChannels() {
        // ★修正: 即座にローディング状態にすることで、MainRootScreenのLaunchedEffectを確実にリセットさせる
        _isLoading.value = true
        viewModelScope.launch {
            fetchChannelsInternal()
        }
    }

    fun fetchRecentRecordings() {
        // 即座にローディング状態にする
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

    // Polling関係はそのまま維持
    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchChannelsInternal()
                delay(30_000L) // 30秒間隔
            }
        }
    }
    fun stopPolling() { pollingJob?.cancel() }
    override fun onCleared() { super.onCleared(); stopPolling() }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            // Entityに変換して保存
            val entity = WatchHistoryEntity(
                id = program.id,
                title = program.title,
                description = program.description,
                startTime = program.startTime,
                endTime = program.endTime,
                duration = program.duration,
                videoId = program.recordedVideo.id,
                watchedAt = System.currentTimeMillis()
            )
            repository.saveToLocalHistory(entity)
        }
    }
}