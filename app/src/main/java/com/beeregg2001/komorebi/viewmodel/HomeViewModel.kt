package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
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

/**
 * ホームタブ（アプリ起動時のポータル画面）のUI状態とビジネスロジックを管理するViewModel。
 * 視聴履歴（レジューム再生）、最後に見たチャンネル履歴、ニコニコ実況の盛り上がり（Hotチャンネル）、
 * 設定に基づくおすすめジャンル番組のピックアップ、およびアプリの自動アップデートチェックを統括します。
 */
@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val epgRepository: EpgRepository,
    private val settingsRepository: SettingsRepository,
    private val appUpdater: AppUpdater // ★追加: アプリの自動更新管理
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ==========================================
    // UI用ステート (Flow)
    // ==========================================

    // KonomiTVの視聴履歴（途中まで見た録画番組）。ローカルDBからの変更をFlowでリアルタイム監視。
    val watchHistory: StateFlow<List<KonomiHistoryProgram>> = repository.getLocalWatchHistory()
        .map { entities -> entities.map { KonomiDataMapper.toUiModel(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 過去にライブ視聴したチャンネルの履歴。
    // EntityからUI用のChannelモデルに変換し、ワンボタンでそのチャンネルに戻れるようにします。
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

    // ユーザー設定: ホーム画面に表示する「おすすめ番組」のジャンル（例: アニメ、ドラマ等）
    val pickupGenreLabel = settingsRepository.homePickupGenre
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "アニメ")

    // ユーザー設定: 有料放送（WOWOWなど）をおすすめから除外するかどうか
    val excludePaidBroadcasts = settingsRepository.excludePaidBroadcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    // ユーザー設定: おすすめ番組を「いつの時間帯のもの」にするか（朝、昼、夜、自動）
    val pickupTimeSetting = settingsRepository.homePickupTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "自動")

    // 上記の設定に基づいて抽出された、おすすめ番組リスト
    private val _genrePickupPrograms = MutableStateFlow<List<Pair<EpgProgram, String>>>(emptyList())
    val genrePickupPrograms: StateFlow<List<Pair<EpgProgram, String>>> =
        _genrePickupPrograms.asStateFlow()

    // 上記のおすすめ番組が「朝」「昼」「夜」どの時間帯のものかを示すUI表示用の文字列
    private val _genrePickupTimeSlot = MutableStateFlow("夜")
    val genrePickupTimeSlot: StateFlow<String> = _genrePickupTimeSlot.asStateFlow()

    // EpgViewModelから共有される、最新の番組表データ（ピックアップ検索用）
    private val _sharedEpgData = MutableStateFlow<List<EpgChannelWrapper>>(emptyList())

    // アプリのアップデート状態（確認中、利用可能、ダウンロード中など）
    val updateState: StateFlow<UpdateState> = appUpdater.updateState

    // ==========================================
    // データ加工・取得メソッド
    // ==========================================

    /**
     * ライブタブのチャンネルリストから、「ニコニコ実況の勢い(コメント数/分)」が0より大きいものを抽出し、
     * 勢い順にソートして上位5件を「今盛り上がっているチャンネル」として返します。
     */
    fun getHotChannels(liveRows: List<LiveRowState>): List<UiChannelState> {
        return liveRows.flatMap { it.channels }
            .filter { (it.jikkyoForce ?: 0) > 0 }
            .sortedByDescending { it.jikkyoForce }
            .take(5)
    }

    /**
     * 録画予約リストの中から、現在時刻より未来に放送される番組だけを抽出し、
     * 放送時間が近い順に上位5件を返します。
     */
    fun getUpcomingReserves(reserves: List<ReserveItem>): List<ReserveItem> {
        val now = OffsetDateTime.now()
        return reserves.filter {
            val start = runCatching { OffsetDateTime.parse(it.program.startTime) }.getOrNull()
            start != null && start.isAfter(now)
        }.sortedBy { it.program.startTime }.take(5)
    }

    /**
     * EpgViewModelから番組表データを受け取って保持します。
     */
    fun updateEpgData(data: List<EpgChannelWrapper>) {
        _sharedEpgData.value = data
    }

    /**
     * EPGデータから、ユーザーが設定した「ジャンル」「時間帯」「有料放送除外設定」に
     * 合致する未来の番組を検索・抽出します。
     */
    private fun fetchAllTypeGenrePickup() {
        viewModelScope.launch {
            val genre = pickupGenreLabel.value
            val timeSetting = pickupTimeSetting.value
            val isExcludePaid = excludePaidBroadcasts.value == "ON"

            // 現在時刻の1時間前から24時間後までの範囲で検索
            val now = OffsetDateTime.now()
            val startSearch = now.minusHours(1)
            val endSearch = now.plusHours(24)

            // 地デジ(GR)、BS、CS の全波のEPGデータを並列(async)で取得
            val types = listOf("GR", "BS", "CS")
            val allPrograms = types.map { type ->
                async {
                    epgRepository.getEpgDataStream(startSearch, endSearch, type)
                        .take(1)
                        .map { it.getOrNull() ?: emptyList() }
                        .firstOrNull() ?: emptyList()
                }
            }.awaitAll().flatten()

            _genrePickupPrograms.value =
                filterGenrePickup(allPrograms, genre, timeSetting, isExcludePaid)
        }
    }

    /**
     * 取得したEPGデータに対して、詳細なフィルタリングを行います。
     * （ジャンル一致、時間帯一致、無料放送のみなど）
     */
    private suspend fun filterGenrePickup(
        allPrograms: List<EpgChannelWrapper>,
        genre: String,
        timeSetting: String,
        isExcludePaid: Boolean
    ): List<Pair<EpgProgram, String>> = withContext(Dispatchers.Default) {
        if (allPrograms.isEmpty()) return@withContext emptyList()

        val now = OffsetDateTime.now()

        // 「自動」設定の場合、現在時刻に応じて提案する時間帯を変化させる
        val actualTimeSlot = if (timeSetting == "自動") {
            val h = now.hour
            if (h in 5..10) "朝" else if (h in 11..17) "昼" else "夜"
        } else {
            timeSetting
        }
        _genrePickupTimeSlot.value = actualTimeSlot // UIの表示名（「今日の夜の〜」など）に反映

        allPrograms.flatMap { wrapper ->
            wrapper.programs.map { it to wrapper.channel.name }
        }.filter { (prog, _) ->
            val start = runCatching { OffsetDateTime.parse(prog.start_time) }.getOrNull()
                ?: return@filter false

            // 1. ジャンルが一致するか
            val isGenre = prog.genres?.any { it.major.contains(genre) } == true

            val t = start.toLocalTime()
            // 2. 設定された時間帯（朝:5-11時、昼:11-18時、夜:18-5時）に一致するか
            val isTimeMatch = when (actualTimeSlot) {
                "朝" -> !t.isBefore(LocalTime.of(5, 0)) && t.isBefore(LocalTime.of(11, 0))
                "昼" -> !t.isBefore(LocalTime.of(11, 0)) && t.isBefore(LocalTime.of(18, 0))
                else -> !t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(5, 0))
            }

            // 3. 有料放送を除外するか
            val isFreeCheckOk = if (isExcludePaid) prog.is_free else true

            // 全条件をクリアし、かつ未来の番組であるものを残す
            isGenre && isTimeMatch && start.isAfter(now) && isFreeCheckOk
        }.sortedBy { it.first.start_time }.take(15) // 時間順に並べて上位15件を採用
    }

    init {
        // 設定（ジャンル、時間、有料放送）が変更されたら、1秒待ってからおすすめ番組を再検索する
        viewModelScope.launch {
            combine(pickupGenreLabel, pickupTimeSetting, excludePaidBroadcasts) { _, _, _ -> Unit }
                .collectLatest {
                    delay(1000) // ユーザーが連続して設定を変えた際のAPI連打を防ぐ
                    fetchAllTypeGenrePickup()
                }
        }

        // アプリ起動時に GitHub Releases から最新バージョンのAPKがあるかをチェック
        viewModelScope.launch {
            appUpdater.checkForUpdates()
        }
    }

    // ==========================================
    // アップデート管理メソッド
    // ==========================================
    fun startUpdateDownload(apkUrl: String) {
        viewModelScope.launch {
            appUpdater.downloadAndInstallUpdate(apkUrl)
        }
    }

    fun dismissUpdate() {
        appUpdater.resetState()
    }

    // ==========================================
    // ホーム画面のデータリフレッシュ
    // ==========================================
    /**
     * 視聴履歴をサーバーから取得してローカルDBを更新し、
     * おすすめ番組（ジャンルピックアップ）の再検索を行います。
     */
    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true

            // 1. KonomiTVサーバーから最新の視聴履歴を取得
            repository.getWatchHistory().onSuccess { apiHistoryList ->
                val programIds = apiHistoryList.mapNotNull { it.program.id.toIntOrNull() }
                val existingEntitiesMap =
                    repository.getHistoryEntitiesByIds(programIds).associateBy { it.id }

                // 2. ローカルDBに存在し、すでにサムネイルのシーク位置などが解析済みの場合はその情報を引き継ぐ
                val entitiesToSave = apiHistoryList.mapNotNull { history ->
                    val programId = history.program.id.toIntOrNull() ?: return@mapNotNull null
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

                // 3. ローカルDBを更新
                if (entitiesToSave.isNotEmpty()) repository.saveAllToLocalHistory(entitiesToSave)
            }

            repository.refreshUser() // ユーザーのセッション維持
            fetchAllTypeGenrePickup()

            _isLoading.value = false
        }
    }

    // ==========================================
    // チャンネル視聴履歴の保存・削除
    // ==========================================
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

    fun clearLastChannelHistory() {
        viewModelScope.launch {
            try {
                repository.clearLastChannels()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to clear last channels", e)
            }
        }
    }
}