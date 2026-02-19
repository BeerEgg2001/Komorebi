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
import com.beeregg2001.komorebi.data.repository.EpgRepository // ★再追加
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.OffsetDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val epgRepository: EpgRepository, // ★EpgRepositoryを再注入
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

    // 各種設定
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

    // ★修正: 特定の放送波に依存せず、GR, BS, CS 全てを個別に取得・集計する
    private fun fetchAllTypeGenrePickup() {
        viewModelScope.launch {
            val genre = pickupGenreLabel.value
            val timeSetting = pickupTimeSetting.value
            val isExcludePaid = excludePaidBroadcasts.value == "ON"

            // 取得範囲は24時間分に絞って軽量化
            val now = OffsetDateTime.now()
            val startSearch = now.minusHours(1)
            val endSearch = now.plusHours(24)

            val types = listOf("GR", "BS", "CS")

            // 全放送波のデータを並列でフェッチ（キャッシュがあれば瞬時に返る）
            val allPrograms = types.map { type ->
                async {
                    // Flowの最初の値（キャッシュまたは最新）を1件取得
                    epgRepository.getEpgDataStream(startSearch, endSearch, type).first().getOrNull() ?: emptyList()
                }
            }.awaitAll().flatten()

            _genrePickupPrograms.value = filterGenrePickup(allPrograms, genre, timeSetting, isExcludePaid)
        }
    }

    // フィルタリング処理（共通ロジック）
    private suspend fun filterGenrePickup(
        allPrograms: List<EpgChannelWrapper>,
        genre: String,
        timeSetting: String,
        isExcludePaid: Boolean
    ): List<Pair<EpgProgram, String>> = withContext(Dispatchers.Default) {
        if (allPrograms.isEmpty()) return@withContext emptyList()

        val now = OffsetDateTime.now()
        val actualTimeSlot = if (timeSetting == "自動") {
            val h = now.hour
            if (h in 5..10) "朝" else if (h in 11..17) "昼" else "夜"
        } else {
            timeSetting
        }
        _genrePickupTimeSlot.value = actualTimeSlot

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
            // 設定変更を監視して再取得
            combine(pickupGenreLabel, pickupTimeSetting, excludePaidBroadcasts) { _, _, _ -> Unit }
                .collectLatest {
                    // 起動直後の負荷集中を避けるため少し待機
                    delay(1000)
                    fetchAllTypeGenrePickup()
                }
        }
    }

    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true
            // 視聴履歴の同期（バルクインサート）
            repository.getWatchHistory().onSuccess { apiHistoryList ->
                val programIds = apiHistoryList.mapNotNull { it.program.id.toIntOrNull() }
                val existingEntitiesMap = repository.getHistoryEntitiesByIds(programIds).associateBy { it.id }
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
                if (entitiesToSave.isNotEmpty()) repository.saveAllToLocalHistory(entitiesToSave)
            }
            repository.refreshUser()
            // ホーム更新時にピックアップも再取得
            fetchAllTypeGenrePickup()
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