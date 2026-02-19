package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.time.LocalTime
import java.time.OffsetDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val epgRepository: EpgRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 視聴履歴
    val watchHistory: StateFlow<List<KonomiHistoryProgram>> = repository.getLocalWatchHistory()
        .map { entities -> entities.map { KonomiDataMapper.toUiModel(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 前回視聴チャンネル
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

    // ジャンル設定
    val pickupGenreLabel = settingsRepository.homePickupGenre
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "アニメ")

    // ★追加: 有料放送除外設定
    val excludePaidBroadcasts = settingsRepository.excludePaidBroadcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    private val _genrePickupPrograms = MutableStateFlow<List<Pair<EpgProgram, String>>>(emptyList())
    val genrePickupPrograms: StateFlow<List<Pair<EpgProgram, String>>> = _genrePickupPrograms.asStateFlow()

    // 勢いのあるチャンネル抽出
    fun getHotChannels(liveRows: List<LiveRowState>): List<UiChannelState> {
        return liveRows.flatMap { it.channels }
            .filter { (it.jikkyoForce ?: 0) > 0 }
            .sortedByDescending { it.jikkyoForce }
            .take(5)
    }

    // 録画予約抽出
    fun getUpcomingReserves(reserves: List<ReserveItem>): List<ReserveItem> {
        val now = OffsetDateTime.now()
        return reserves.filter {
            val start = runCatching { OffsetDateTime.parse(it.program.startTime) }.getOrNull()
            start != null && start.isAfter(now)
        }.sortedBy { it.program.startTime }.take(5)
    }

    private fun fetchAllTypeGenrePickup(genre: String) {
        viewModelScope.launch {
            val now = OffsetDateTime.now()
            val startSearch = now.withHour(0).withMinute(0)
            val endSearch = now.plusDays(1).withHour(5).withMinute(0)
            val nightStart = LocalTime.of(18, 0)
            val nightEnd = LocalTime.of(5, 0)

            val types = listOf("GR", "BS", "CS")

            // ★現在の除外設定を取得
            val isExcludePaid = excludePaidBroadcasts.value == "ON"

            // 全放送波のデータを並列で取得
            val allPrograms = types.map { type ->
                async {
                    epgRepository.fetchEpgData(startSearch, endSearch, type).getOrNull() ?: emptyList()
                }
            }.awaitAll().flatten()

            val filtered = allPrograms.flatMap { wrapper ->
                wrapper.programs.map { it to wrapper.channel.name }
            }.filter { (prog, _) ->
                val start = runCatching { OffsetDateTime.parse(prog.start_time) }.getOrNull() ?: return@filter false
                val isGenre = prog.genres?.any { it.major.contains(genre) } == true
                val isNight = start.toLocalTime().let { t -> t.isAfter(nightStart) || t.isBefore(nightEnd) }

                // ★追加: is_free フラグをチェック (除外設定がONなら無料番組のみを通す)
                val isFreeCheckOk = if (isExcludePaid) prog.is_free else true

                isGenre && isNight && start.isAfter(now) && isFreeCheckOk
            }.sortedBy { it.first.start_time }.take(15)

            _genrePickupPrograms.value = filtered
        }
    }

    init {
        // ★修正: ジャンル、または有料除外設定のどちらかが変更されたら自動で再フェッチする
        viewModelScope.launch {
            combine(pickupGenreLabel, excludePaidBroadcasts) { genre, _ ->
                genre
            }.collectLatest { genre ->
                fetchAllTypeGenrePickup(genre)
            }
        }
    }

    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getWatchHistory().onSuccess { apiHistoryList ->
                apiHistoryList.forEach { history ->
                    val programId = history.program.id.toIntOrNull() ?: return@forEach
                    val existingEntity = repository.getHistoryEntityById(programId)
                    var newEntity = KonomiDataMapper.toEntity(history)
                    if (existingEntity != null) {
                        newEntity = newEntity.copy(
                            videoId = existingEntity.videoId, tileColumns = existingEntity.tileColumns,
                            tileRows = existingEntity.tileRows, tileInterval = existingEntity.tileInterval,
                            tileWidth = existingEntity.tileWidth, tileHeight = existingEntity.tileHeight
                        )
                    }
                    repository.saveToLocalHistory(newEntity)
                }
            }
            repository.refreshUser()
            fetchAllTypeGenrePickup(pickupGenreLabel.value)
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