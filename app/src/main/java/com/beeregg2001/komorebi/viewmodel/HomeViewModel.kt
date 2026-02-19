package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.OffsetDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val watchHistory: StateFlow<List<KonomiHistoryProgram>> = repository.getLocalWatchHistory()
        .map { entities -> entities.map { KonomiDataMapper.toUiModel(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastWatchedChannelFlow: StateFlow<List<Channel>> = repository.getLastChannels()
        .map { entities ->
            entities.map { entity ->
                Channel(
                    id = entity.channelId, name = entity.name, type = entity.type,
                    channelNumber = entity.channelNumber ?: "", displayChannelId = entity.channelId,
                    networkId = entity.networkId, serviceId = entity.serviceId,
                    isWatchable = true, isDisplay = true, programPresent = null,
                    programFollowing = null, remocon_Id = 0
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pickupGenreLabel = settingsRepository.homePickupGenre
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "アニメ")

    val excludePaidBroadcasts = settingsRepository.excludePaidBroadcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    val pickupTimeSetting = settingsRepository.homePickupTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "自動")

    private val _genrePickupPrograms = MutableStateFlow<List<Pair<EpgProgram, String>>>(emptyList())
    val genrePickupPrograms: StateFlow<List<Pair<EpgProgram, String>>> = _genrePickupPrograms.asStateFlow()

    private val _genrePickupTimeSlot = MutableStateFlow("夜")
    val genrePickupTimeSlot: StateFlow<String> = _genrePickupTimeSlot.asStateFlow()

    private val _sharedEpgData = MutableStateFlow<List<EpgChannelWrapper>>(emptyList())

    fun getHotChannels(liveRows: List<LiveRowState>): List<UiChannelState> {
        return liveRows.flatMap { it.channels }
            .filter { (it.jikkyoForce ?: 0) > 0 }
            .sortedByDescending { it.jikkyoForce }
            .take(5)
    }

    fun getUpcomingReserves(reserves: List<ReserveItem>): List<ReserveItem> {
        val now = OffsetDateTime.now()
        return reserves.filter {
            val start = runCatching { OffsetDateTime.parse(it.program.startTime) }.getOrNull()
            start != null && start.isAfter(now)
        }.sortedBy { it.program.startTime }.take(5)
    }

    fun updateEpgData(data: List<EpgChannelWrapper>) {
        _sharedEpgData.value = data
    }

    private suspend fun filterGenrePickup(
        allPrograms: List<EpgChannelWrapper>,
        genre: String,
        timeSetting: String,
        excludePaidStr: String
    ): List<Pair<EpgProgram, String>> = withContext(Dispatchers.Default) {
        if (allPrograms.isEmpty()) return@withContext emptyList()

        val now = OffsetDateTime.now()

        val actualTimeSlot = if (timeSetting == "自動") {
            val h = now.hour
            when {
                h in 5..10 -> "朝"
                h in 11..17 -> "昼"
                else -> "夜"
            }
        } else {
            timeSetting
        }
        _genrePickupTimeSlot.value = actualTimeSlot

        val isExcludePaid = excludePaidStr == "ON"

        allPrograms.flatMap { wrapper ->
            wrapper.programs.map { it to wrapper.channel.name }
        }.filter { (prog, _) ->
            val start = runCatching { OffsetDateTime.parse(prog.start_time) }.getOrNull() ?: return@filter false
            val isGenre = prog.genres?.any { it.major.contains(genre) } == true

            val t = start.toLocalTime()
            val isTimeMatch = when (actualTimeSlot) {
                "朝" -> !t.isBefore(LocalTime.of(5, 0)) && t.isBefore(LocalTime.of(11, 0))
                "昼" -> !t.isBefore(LocalTime.of(11, 0)) && t.isBefore(LocalTime.of(18, 0))
                else -> !t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(5, 0))
            }

            val isFreeCheckOk = if (isExcludePaid) prog.is_free else true

            isGenre && isTimeMatch && start.isAfter(now) && isFreeCheckOk
        }.sortedBy { it.first.start_time }.take(15)
    }

    init {
        viewModelScope.launch {
            combine(
                _sharedEpgData,
                pickupGenreLabel,
                excludePaidBroadcasts,
                pickupTimeSetting
            ) { epgData, genre, excludePaid, time ->
                filterGenrePickup(epgData, genre, time, excludePaid)
            }.collectLatest { filteredPrograms ->
                _genrePickupPrograms.value = filteredPrograms
            }
        }
    }

    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getWatchHistory().onSuccess { apiHistoryList ->
                // ★修正: N+1問題を解消し、DBの読み書きを1回にまとめる（バルクインサート）
                val programIds = apiHistoryList.mapNotNull { it.program.id.toIntOrNull() }

                // 全件を一気に検索してMapにする
                val existingEntitiesMap = repository.getHistoryEntitiesByIds(programIds).associateBy { it.id }

                // メモリ上でマージして保存用リストを作る
                val entitiesToSave = apiHistoryList.mapNotNull { history ->
                    val programId = history.program.id.toIntOrNull() ?: return@mapNotNull null
                    val existingEntity = existingEntitiesMap[programId]
                    var newEntity = KonomiDataMapper.toEntity(history)
                    if (existingEntity != null) {
                        newEntity = newEntity.copy(
                            videoId = existingEntity.videoId, tileColumns = existingEntity.tileColumns,
                            tileRows = existingEntity.tileRows, tileInterval = existingEntity.tileInterval,
                            tileWidth = existingEntity.tileWidth, tileHeight = existingEntity.tileHeight
                        )
                    }
                    newEntity
                }

                // リストを1回のトランザクションで書き込む
                if (entitiesToSave.isNotEmpty()) {
                    repository.saveAllToLocalHistory(entitiesToSave)
                }
            }
            repository.refreshUser()
            _isLoading.value = false
        }
    }

    fun saveLastChannel(channel: Channel) {
        viewModelScope.launch {
            repository.saveLastChannel(
                LastChannelEntity(
                    channelId = channel.id, name = channel.name, type = channel.type,
                    channelNumber = channel.channelNumber, networkId = channel.networkId,
                    serviceId = channel.serviceId, updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
}