package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.LastChannelRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.OffsetDateTime
import javax.inject.Inject

data class BaseballGameInfo(
    val program: EpgProgram,
    val channel: EpgChannel,
    val logoUrl: String
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val liveProvider: LiveProvider,
    private val konomiRepository: KonomiRepository,
    private val epgRepository: EpgRepository,
    private val settingsRepository: SettingsRepository,
    private val lastChannelRepository: LastChannelRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val appUpdater: AppUpdater
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val backendType: StateFlow<String> = settingsRepository.backendType
        .stateIn(viewModelScope, SharingStarted.Eagerly, "KONOMITV")

    var lastClickedSection: String? = null
    var lastClickedItemId: String? = null

    private val _isFallbackTriggered = MutableStateFlow(false)
    val isFallbackTriggered: StateFlow<Boolean> = _isFallbackTriggered.asStateFlow()

    fun clearFocusMemory() {
        lastClickedSection = null
        lastClickedItemId = null
    }

    fun dismissFallbackWarning() {
        _isFallbackTriggered.value = false
    }

    val watchHistory: StateFlow<List<KonomiHistoryProgram>> =
        watchHistoryRepository.getLocalWatchHistory()
            .map { entities -> entities.map { KonomiDataMapper.toUiModel(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastWatchedChannelFlow: StateFlow<List<Channel>> = watchHistoryRepository.getLastChannels()
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
    val genrePickupPrograms: StateFlow<List<Pair<EpgProgram, String>>> =
        _genrePickupPrograms.asStateFlow()

    private val _genrePickupTimeSlot = MutableStateFlow("夜")
    val genrePickupTimeSlot: StateFlow<String> = _genrePickupTimeSlot.asStateFlow()

    private val _sharedEpgData = MutableStateFlow<List<EpgChannelWrapper>>(emptyList())

    val updateState: StateFlow<UpdateState> = appUpdater.updateState

    val favoriteBaseballTeams: StateFlow<Set<String>> = settingsRepository.favoriteBaseballTeams
        .map { json ->
            try {
                val listType = object : TypeToken<List<String>>() {}.type
                val list: List<String>? = Gson().fromJson(json, listType)
                list?.toSet() ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _favoriteBaseballGames =
        MutableStateFlow<List<Pair<String, List<BaseballGameInfo>>>>(emptyList())
    val favoriteBaseballGames: StateFlow<List<Pair<String, List<BaseballGameInfo>>>> =
        _favoriteBaseballGames.asStateFlow()

    private val _baseballDateOffset = MutableStateFlow(0)
    val baseballDateOffset: StateFlow<Int> = _baseballDateOffset.asStateFlow()

    private var cachedBaseballPrograms: List<Pair<EpgProgram, EpgChannel>> = emptyList()

    fun getHotChannels(liveRows: List<LiveRowState>): List<UiChannelState> {
        return liveRows.flatMap { it.channels }
            .filter { !it.channel.is_subchannel }
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

    private fun fetchAllTypeGenrePickup() {
        viewModelScope.launch {
            val genre = pickupGenreLabel.value
            val timeSetting = pickupTimeSetting.value
            val isExcludePaid = excludePaidBroadcasts.value == "ON"

            val now = OffsetDateTime.now()
            val startSearch = now.minusHours(1)
            val endSearch = now.plusDays(3)

            val types = listOf("GR", "BS", "CS")

            val allPrograms = types.map { type ->
                async {
                    epgRepository.getEpgDataStream(startSearch, endSearch, type)
                        .take(1)
                        .map { it.getOrNull() ?: emptyList() }
                        .firstOrNull() ?: emptyList()
                }
            }.awaitAll().flatten()

            cachedBaseballPrograms = withContext(Dispatchers.Default) {
                allPrograms.flatMap { wrapper ->
                    wrapper.programs.map { it to wrapper.channel }
                }.filter { (prog, _) ->
                    val isSports = prog.genres?.any { it.major.contains("スポーツ") } == true
                    if (!isSports) return@filter false

                    val isBaseballGenre =
                        prog.genres?.any { it.middle?.contains("野球") == true } == true || prog.title.contains(
                            "プロ野球"
                        )
                    if (!isBaseballGenre) return@filter false

                    val excludeKeywords = listOf(
                        "特集", "ヴィンテージ", "ハイライト", "ダイジェスト", "ニュース",
                        "名勝負", "傑作選", "セレクション", "トラリンク", "ガンガン！",
                        "伝説", "回顧", "すぽると", "熱闘", "プロ野球ニュース"
                    )
                    if (excludeKeywords.any { prog.title.contains(it) }) return@filter false

                    val matchKeywords =
                        listOf("中継", "対", "×", "vs", "戦", "生放送", "LIVE", "L！VE")
                    matchKeywords.any { keyword ->
                        prog.title.contains(
                            keyword,
                            ignoreCase = true
                        ) || prog.description.contains(
                            keyword,
                            ignoreCase = true
                        )
                    }
                }
            }

            _genrePickupPrograms.value =
                filterGenrePickup(allPrograms, genre, timeSetting, isExcludePaid)

            _favoriteBaseballGames.value = filterFavoriteBaseballGames(
                cachedBaseballPrograms,
                favoriteBaseballTeams.value,
                _baseballDateOffset.value
            )
        }
    }

    fun updateBaseballDateOffset(offset: Int) {
        if (offset in 0..2) {
            _baseballDateOffset.value = offset
            viewModelScope.launch {
                if (cachedBaseballPrograms.isNotEmpty()) {
                    _favoriteBaseballGames.value = filterFavoriteBaseballGames(
                        cachedBaseballPrograms,
                        favoriteBaseballTeams.value,
                        offset
                    )
                } else {
                    fetchAllTypeGenrePickup()
                }
            }
        }
    }

    private suspend fun filterFavoriteBaseballGames(
        baseballPrograms: List<Pair<EpgProgram, EpgChannel>>,
        favoriteTeams: Set<String>,
        offsetDays: Int
    ): List<Pair<String, List<BaseballGameInfo>>> = withContext(Dispatchers.Default) {
        if (favoriteTeams.isEmpty() || baseballPrograms.isEmpty()) return@withContext emptyList()

        val now = OffsetDateTime.now()
        val targetDateStart = now.withHour(4).withMinute(0).withSecond(0).withNano(0).let {
            if (now.hour < 4) it.minusDays(1) else it
        }.plusDays(offsetDays.toLong())
        val targetDateEnd = targetDateStart.plusDays(1)

        val groupedGames = favoriteTeams.mapNotNull { team ->
            val gamesForTeam = baseballPrograms.filter { (prog, _) ->
                prog.title.contains(team) || prog.description.contains(team)
            }.filter { (prog, _) ->
                val start = runCatching { OffsetDateTime.parse(prog.start_time) }.getOrNull()
                    ?: return@filter false
                start.isAfter(targetDateStart) && start.isBefore(targetDateEnd)
            }.map { (prog, channel) ->

                val logoUrl = liveProvider.getChannelLogoUrl(channel.display_channel_id)

                BaseballGameInfo(
                    program = prog,
                    channel = channel,
                    logoUrl = logoUrl
                )
            }.sortedBy { it.program.start_time }

            if (gamesForTeam.isNotEmpty()) team to gamesForTeam else null
        }

        groupedGames.sortedBy { it.first }
    }

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
            val isGenre = prog.genres?.any { it.major.contains(genre) } == true
            if (!isGenre) return@filter false

            val isFreeCheckOk = if (isExcludePaid) prog.is_free else true
            if (!isFreeCheckOk) return@filter false

            val start = runCatching { OffsetDateTime.parse(prog.start_time) }.getOrNull()
                ?: return@filter false

            val t = start.toLocalTime()
            val isTimeMatch = when (actualTimeSlot) {
                "朝" -> !t.isBefore(LocalTime.of(5, 0)) && t.isBefore(LocalTime.of(11, 0))
                "昼" -> !t.isBefore(LocalTime.of(11, 0)) && t.isBefore(LocalTime.of(18, 0))
                else -> !t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(5, 0))
            }

            val isWithin24Hours = start.isBefore(now.plusHours(24))

            isTimeMatch && start.isAfter(now) && isWithin24Hours
        }.sortedBy { it.first.start_time }.take(15)
    }

    private suspend fun performBackendHealthCheck() {
        val currentBackend = settingsRepository.backendType.first()

        if (currentBackend == "KONOMITV") return

        try {
            liveProvider.getChannels()
            Log.i("Komorebi_Failsafe", "Health check passed for backend: $currentBackend")
        } catch (e: Throwable) {
            Log.e("Komorebi_Failsafe", "Health check FAILED for backend: $currentBackend", e)
            _isFallbackTriggered.value = true
            Log.w(
                "Komorebi_Failsafe",
                "Backend health check failed. Showing warning without overwriting settings."
            )
        }
    }

    init {
        viewModelScope.launch {
            combine(
                pickupGenreLabel,
                pickupTimeSetting,
                excludePaidBroadcasts,
                favoriteBaseballTeams
            ) { genre, time, excludePaid, baseballTeams ->
                listOf(genre, time, excludePaid, baseballTeams.toString())
            }
                .distinctUntilChanged()
                .debounce(1500L)
                .collectLatest {
                    fetchAllTypeGenrePickup()
                }
        }

        // ★ 修正: アプリアップデート確認は急がないので、UI描画後（3秒後）に実行
        viewModelScope.launch {
            delay(3000)
            val receiveBeta = settingsRepository.receiveBetaUpdates.first()
            appUpdater.checkForUpdates(receiveBetaUpdates = receiveBeta)
        }

        // ★ 修正: バックエンドのヘルスチェックも、UIが立ち上がってから（1.5秒後）実行
        viewModelScope.launch {
            delay(1500)
            performBackendHealthCheck()
        }
    }

    fun startUpdateDownload(apkUrl: String) {
        viewModelScope.launch {
            appUpdater.downloadAndInstallUpdate(apkUrl)
        }
    }

    fun dismissUpdate() {
        appUpdater.resetState()
    }

    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                performBackendHealthCheck()

                try {
                    val backend = settingsRepository.backendType.first()
                    if (backend == "KONOMITV" || backend == "MIRAKURUN_ONLY") {
                        konomiRepository.getWatchHistory().onSuccess { apiHistoryList ->
                            val programIds =
                                apiHistoryList.mapNotNull { it.program.id.toIntOrNull() }
                            val existingEntitiesMap =
                                watchHistoryRepository.getHistoryEntitiesByIds(programIds)
                                    .associateBy { it.id }
                            val entitiesToSave = apiHistoryList.mapNotNull { history ->
                                val programId =
                                    history.program.id.toIntOrNull() ?: return@mapNotNull null
                                val existingEntity = existingEntitiesMap[programId]
                                var newEntity = KonomiDataMapper.toEntity(history)
                                if (existingEntity != null) {
                                    newEntity = newEntity.copy(
                                        videoId = existingEntity.videoId,
                                        tileColumns = existingEntity.tileColumns,
                                        tileRows = existingEntity.tileRows,
                                        tileInterval = existingEntity.tileInterval,
                                        tileWidth = existingEntity.tileWidth,
                                        tileHeight = existingEntity.tileHeight
                                    )
                                }
                                newEntity
                            }
                            if (entitiesToSave.isNotEmpty()) watchHistoryRepository.saveAllToLocalHistory(
                                entitiesToSave
                            )
                        }
                        konomiRepository.refreshUser()
                    }
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Failed to sync KonomiTV data. Skipping.", e)
                }

                fetchAllTypeGenrePickup()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error refreshing home data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveLastChannel(channel: Channel) {
        viewModelScope.launch {
            lastChannelRepository.saveLastChannel(
                LastChannelEntity(
                    channelId = channel.id, name = channel.name, type = channel.type,
                    channelNumber = channel.channelNumber, networkId = channel.networkId,
                    serviceId = channel.serviceId, updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun clearLastChannelHistory() {
        viewModelScope.launch {
            try {
                lastChannelRepository.clearLastChannels()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to clear last channels", e)
            }
        }
    }
}