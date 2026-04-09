package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.OffsetDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ChannelViewModel @Inject constructor(
    // ★ 修正: KonomiRepository ではなく、抽象化された Provider を Inject
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val watchHistoryRepository: WatchHistoryRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _liveRows = MutableStateFlow<List<LiveRowState>>(emptyList())
    val liveRows: StateFlow<List<LiveRowState>> = _liveRows.asStateFlow()

    private val _groupedChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Channel>>> = _groupedChannels

    private val baseballKeywords = listOf(
        "阪神", "タイガース", "広島", "カープ", "DeNA", "ベイスターズ",
        "巨人", "ジャイアンツ", "ヤクルト", "スワローズ", "中日", "ドラゴンズ",
        "オリックス", "バファローズ", "ロッテ", "マリーンズ", "ソフトバンク", "ホークス",
        "楽天", "イーグルス", "西武", "ライオンズ", "日本ハム", "ファイターズ", "プロ野球"
    )

    private val excludeKeywords = listOf(
        "プロ野球ニュース", "すぽると", "熱闘", "ダイジェスト", "ハイライト",
        "特集", "傑作選", "名勝負", "セレクション", "回顧", "伝説", "競馬"
    )

    private val matchKeywords = listOf(
        "ナイター", "デーゲーム", "ベースボール", "プロ野球中継", "実況中継",
        "ガオトラ", "オープン戦", "公式戦", "クライマックスシリーズ", "日本シリーズ"
    )

    private val versusSymbols = listOf("対", "×", "vs", "VS", "-", "ー")

    val baseballGroupedChannels: StateFlow<Map<String, List<Channel>>> =
        _groupedChannels.map { grouped ->
            grouped.mapValues { (_, channels) ->
                channels.filter { ch ->
                    val presentTitle = ch.programPresent?.title ?: ""
                    val presentDesc = ch.programPresent?.description ?: ""
                    val followingTitle = ch.programFollowing?.title ?: ""
                    val followingDesc = ch.programFollowing?.description ?: ""

                    fun isBaseballGame(title: String, desc: String): Boolean {
                        if (title.isBlank()) return false

                        val fullText = "$title $desc"

                        val hasKeyword = baseballKeywords.any { keyword ->
                            fullText.contains(keyword)
                        }
                        if (!hasKeyword) return false

                        val isExcluded = excludeKeywords.any { keyword ->
                            title.contains(keyword)
                        }
                        if (isExcluded) return false

                        val hasVersusSymbol = versusSymbols.any { title.contains(it) }
                        val hasGenericLiveWord =
                            title.contains("中継") || title.contains("生") || title.contains(
                                "LIVE",
                                ignoreCase = true
                            )

                        if (hasVersusSymbol && hasGenericLiveWord) return true

                        val isStrongMatch = matchKeywords.any { keyword ->
                            title.contains(keyword, ignoreCase = true) || desc.contains(
                                keyword,
                                ignoreCase = true
                            )
                        }
                        if (isStrongMatch) return true

                        if (title.length <= 15 && title.contains("プロ野球") && hasGenericLiveWord) return true

                        return false
                    }

                    isBaseballGame(presentTitle, presentDesc) || isBaseballGame(
                        followingTitle,
                        followingDesc
                    )
                }
            }.filterValues { it.isNotEmpty() }

        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading

    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()

    private var pollingJob: Job? = null
    private var progressUpdateJob: Job? = null

    private var fetchJob: Job? = null

    private var lastFetchedTimeMillis = 0L

    init {
        startPolling()
        startProgressUpdater()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun transformToUiState(grouped: Map<String, List<Channel>>): List<LiveRowState> =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val orderedTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")

            val sortedKeys = grouped.keys.sortedBy { key ->
                val index = orderedTypes.indexOf(key)
                if (index >= 0) index else Int.MAX_VALUE
            }

            sortedKeys.mapNotNull { type ->
                val channels = grouped[type] ?: return@mapNotNull null
                LiveRowState(
                    genreId = type,
                    genreLabel = when (type) {
                        "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; "BS4K" -> "BS4K"; "SKY" -> "スカパー"; else -> type
                    },
                    channels = channels.map { ch ->
                        val start = ch.programPresent?.startTime?.let {
                            runCatching {
                                OffsetDateTime.parse(it).toInstant().toEpochMilli()
                            }.getOrNull()
                        } ?: 0L
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
                            hasProgram = ch.programPresent != null,
                            jikkyoForce = ch.jikkyoForce
                        )
                    }
                )
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun fetchChannelsInternal() {
        try {
            _connectionError.value = false
            // ★ 修正: LiveProvider から取得
            val response = liveProvider.getChannels()

            val processed = withContext(Dispatchers.Default) {
                val rawChannels = listOfNotNull(
                    response.terrestrial, response.bs, response.cs, response.sky, response.bs4k
                ).flatten()

                val allChannels = rawChannels.map { apiChannel ->
                    Channel(
                        id = apiChannel.id,
                        name = apiChannel.name,
                        type = apiChannel.type,
                        channelNumber = apiChannel.channelNumber,
                        networkId = apiChannel.networkId,
                        serviceId = apiChannel.serviceId,
                        displayChannelId = apiChannel.displayChannelId ?: apiChannel.id,
                        isWatchable = apiChannel.isWatchable,
                        isDisplay = apiChannel.isDisplay,
                        programPresent = apiChannel.programPresent,
                        programFollowing = apiChannel.programFollowing,
                        remocon_Id = apiChannel.remocon_Id,
                        jikkyoForce = apiChannel.jikkyoForce
                    )
                }

                val hotCount = allChannels.count { (it.jikkyoForce ?: 0) > 0 }
                Log.i(
                    "ChannelViewModel",
                    "Fetched channels. Total: ${allChannels.size}, Hot(force > 0): $hotCount"
                )

                allChannels.filter { it.isDisplay }.groupBy { it.type }
            }
            _groupedChannels.value = processed
            _liveRows.value = transformToUiState(processed)

            lastFetchedTimeMillis = System.currentTimeMillis()
        } catch (e: CancellationException) {
            Log.d("ChannelViewModel", "fetchChannelsInternal cancelled")
            throw e
        } catch (e: Throwable) { // ★ 修正: Throwable でキャッチ
            Log.e("ChannelViewModel", "Error fetching channels", e)
            _connectionError.value = true
        } finally {
            _isLoading.value = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                if (_groupedChannels.value.isNotEmpty()) {
                    _liveRows.value = transformToUiState(_groupedChannels.value)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun fetchChannels() {
        _isLoading.value = true
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            fetchChannelsInternal()
        }
    }

    fun fetchRecentRecordings() {
        _isRecordingLoading.value = true
        viewModelScope.launch {
            try {
                // ★ 修正: RecordProvider から取得
                val response = recordProvider.getRecordedPrograms(page = 1)
                _recentRecordings.value = response.recordedPrograms
            } catch (e: Throwable) { // ★ 修正: Throwable でキャッチ
                Log.e("ChannelViewModel", "Error recordings", e)
            } finally {
                _isRecordingLoading.value = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            if (System.currentTimeMillis() - lastFetchedTimeMillis > 60_000L) {
                Log.i("ChannelViewModel", "Data is stale. Fetching immediately.")
                fetchChannelsInternal()
            }

            while (isActive) {
                val now = System.currentTimeMillis()
                val delayToNextMinute = 60_000L - (now % 60_000L)

                delay(delayToNextMinute + 1500L)

                if (isActive) {
                    fetchChannelsInternal()
                }
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
        fetchJob?.cancel()
    }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            val entity = KonomiDataMapper.toEntity(program)
            watchHistoryRepository.saveToLocalHistory(entity)
        }
    }

    // ★ 追加: UIから非同期でロゴURLを取得するためのメソッド
    suspend fun getChannelLogoUrl(channelId: String): String {
        // ※リポジトリの変数名は環境に合わせて変更してください (liveProvider, repository など)
        return liveProvider.getChannelLogoUrl(channelId)
    }
}