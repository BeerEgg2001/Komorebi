package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.O)
    val watchHistory: StateFlow<List<KonomiHistoryProgram>> = repository.getLocalWatchHistory()
        .map { entities: List<WatchHistoryEntity> ->
            entities.map { KonomiDataMapper.toUiModel(it) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val lastWatchedChannelFlow: StateFlow<List<Channel>> = repository.getLastChannels()
        .map { entities: List<LastChannelEntity> ->
            entities.map { entity ->
                Channel(
                    id = entity.channelId,
                    name = entity.name,
                    type = entity.type,
                    channelNumber = entity.channelNumber ?: "",
                    displayChannelId = entity.channelId,
                    networkId = entity.networkId,
                    serviceId = entity.serviceId,
                    isWatchable = true,
                    isDisplay = true,
                    programPresent = null,
                    programFollowing = null,
                    remocon_Id = 0
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        refreshHomeData()
    }

    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true

            repository.getWatchHistory().onSuccess { apiHistoryList ->
                apiHistoryList.forEach { history ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val programId = history.program.id.toIntOrNull() ?: return@forEach

                        // ★修正: 既存データを取得してメタデータ（タイル情報など）を引き継ぐ
                        // これを行わないと、API同期のたびにタイル情報がデフォルト(1x1)に上書きされてしまう
                        val existingEntity = repository.getHistoryEntityById(programId)

                        var newEntity = KonomiDataMapper.toEntity(history)

                        if (existingEntity != null) {
                            newEntity = newEntity.copy(
                                videoId = existingEntity.videoId, // 正しいVideoIDを維持
                                tileColumns = existingEntity.tileColumns,
                                tileRows = existingEntity.tileRows,
                                tileInterval = existingEntity.tileInterval,
                                tileWidth = existingEntity.tileWidth,
                                tileHeight = existingEntity.tileHeight
                            )
                        }

                        repository.saveToLocalHistory(newEntity)
                    }
                }
            }

            repository.refreshUser()
            _isLoading.value = false
        }
    }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            repository.saveToLocalHistory(KonomiDataMapper.toEntity(program))
        }
    }

    fun saveLastChannel(channel: Channel) {
        viewModelScope.launch {
            repository.saveLastChannel(
                LastChannelEntity(
                    channelId = channel.id,
                    name = channel.name,
                    type = channel.type,
                    channelNumber = channel.channelNumber,
                    networkId = channel.networkId,
                    serviceId = channel.serviceId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
}