package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
// ★ 修正: UrlBuilderへの直接依存を排除したためimportを削除
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider // ★ 追加
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.time.OffsetDateTime
import javax.inject.Inject

/**
 * 検索結果リストのUIに渡すための統合データクラス。
 * 番組情報単体だけでなく、どのチャンネルで放送されるかと、そのチャンネルのロゴURLをセットにして保持します。
 */
data class UiSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel,
    val logoUrl: String
)

private const val PREF_NAME_EPG_SEARCH = "epg_search_history_pref"
private const val KEY_EPG_HISTORY = "history_list"

/**
 * 番組表（EPGタブ）のUI状態とビジネスロジックを管理するViewModel。
 * APIからの数日分・数十チャンネルに及ぶ巨大な番組データ（fullEpgData）をメモリ上に保持し、
 * UIの要求（表示したい日付や時間帯）に応じて1日分だけをスライスしてUI層（CanvasEngine）に渡す役割を担います。
 */
@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val repository: EpgRepository, // EpgRepositoryは検索・キャッシュマネージャーとしてそのまま利用
    private val liveProvider: LiveProvider, // ★ 追加: ロゴURL生成などの抽象化プロバイダ
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ==========================================
    // 番組表のUI状態管理 (State)
    // ==========================================

    // EPGのメイン画面（グリッド）に渡すデータ状態。ComposeのMutableStateを利用して高速に再描画をトリガーします。
    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    // アプリ起動直後のバックグラウンドデータ先読み中フラグ
    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading

    // 最初のデータロードが完了したかどうかのフラグ（スプラッシュ画面の解除判定などに使用）
    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    // 現在表示している放送波のタブ（"GR"=地デジ, "BS", "CS" など）
    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType: StateFlow<String> = _selectedBroadcastingType.asStateFlow()

    // ※UI側(CanvasEngine)の仕様互換性を保つため、MirakurunのIP情報はStateに維持します
    private var mirakurunIp = ""
    private var mirakurunPort = ""

    private var hasInitialFetched = false
    private var epgJob: Job? = null

    // APIから取得した数日分の「全番組データ」。これを丸ごとUIに渡すと重すぎるため、裏側で保持しておきます。
    private var fullEpgData: List<EpgChannelWrapper> = emptyList()

    // 上記チャンネル群のロゴURLリスト（UI描画時の計算コストを省くためのキャッシュ）
    private var fullLogoUrls: List<String> = emptyList()

    // ユーザーが番組表上でフォーカスしている、またはジャンプ指定した「目標の日時」
    private var currentTargetTime: OffsetDateTime = OffsetDateTime.now()

    // ==========================================
    // 未来番組検索用のState
    // ==========================================
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // 実際に検索ボタンが押され、現在検索結果に反映されている確定済みのクエリ
    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<UiSearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<UiSearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // 🌟 追加: EPGフォーカス記憶と復元トリガー
    var lastFocusedChannelId: String? = null
    var lastFocusedTime: OffsetDateTime? = null
    var epgRestoreTrigger by androidx.compose.runtime.mutableStateOf(0L)
        private set

    fun saveEpgFocus(channelId: String, time: OffsetDateTime) {
        lastFocusedChannelId = channelId
        lastFocusedTime = time
    }

    fun triggerRestore() {
        epgRestoreTrigger = System.currentTimeMillis()
    }

    fun clearEpgFocus() {
        lastFocusedChannelId = null
        lastFocusedTime = null
    }

    init {
        loadSearchHistory() // アプリ起動時に履歴を読み込む
        loadInitialData()
        // ★ 追加: EDCBのバックグラウンドEPG取得完了を検知して、自分(ViewModel)のキャッシュを更新する
        viewModelScope.launch {
            com.beeregg2001.komorebi.data.repository.EdcbRepository.epgBackgroundUpdateEvent.collect {
                Log.i("EpgViewModel", "Background EPG fetch completed! Refreshing ViewModel cache...")

                // ★ 注意: 以下の関数名は、EpgViewModel内で「最初にEPGデータを取得している関数」の名前に書き換えてください。
                // 例: fetchEpgData() や loadPrograms() など
                // この関数を呼ぶことで、最新の1週間分のデータが cachedAllPrograms に上書きされます。
//                loadSearchHistory() // アプリ起動時に履歴を読み込む
                refreshEpgData()
            }
        }
    }

    // ==========================================
    // 検索履歴のローカル保存機能 (SharedPreferences)
    // ==========================================
    private fun loadSearchHistory() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_EPG_HISTORY, "[]")
            val jsonArray = JSONArray(jsonString)
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) list.add(jsonArray.getString(i))
            _searchHistory.value = list
        } catch (e: Exception) {
            _searchHistory.value = emptyList()
        }
    }

    private fun addSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        currentList.remove(query) // 重複排除
        currentList.add(0, query) // 先頭に追加
        if (currentList.size > 5) currentList.removeAt(currentList.lastIndex) // 最大5件まで保持
        _searchHistory.value = currentList
        saveSearchHistory(currentList)
    }

    fun removeSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        if (currentList.remove(query)) {
            _searchHistory.value = currentList
            saveSearchHistory(currentList)
        }
    }

    private fun saveSearchHistory(list: List<String>) {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_EPG_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    // ==========================================

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * APIを叩いて、未来の番組（番組表データ）からキーワード検索を実行します。
     * 結果は番組単体ではなく、チャンネル情報とロゴURLを結合したUiSearchResultItemのリストとしてUIに提供します。
     */
    @OptIn(UnstableApi::class)
    fun executeSearch(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ) {
        viewModelScope.launch {
            _isSearching.value = true

            // UIに表示するためのテキストを生成
            val displayQuery = listOfNotNull(
                keyword.takeIf { it.isNotBlank() },
                channelName?.takeIf { it.isNotBlank() },
                genre?.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            _searchQuery.value = displayQuery
            _activeSearchQuery.value = displayQuery
            if (displayQuery.isNotBlank()) addSearchHistory(displayQuery)

            try {
                // 1. リポジトリから検索結果を取得（数千件になる可能性がある）
                val rawResults = withContext(Dispatchers.Default) {
                    repository.searchFuturePrograms(
                        keyword,
                        genre,
                        dateStr,
                        isLiveOnly,
                        channelName
                    )
                }

                // ★改善点1: 先に日時順でソートし、最大100件に絞り込む（ここで無駄な処理をカット）
                val topMatches = rawResults.sortedBy {
                    try { OffsetDateTime.parse(it.program.start_time) }
                    catch (e: Exception) { OffsetDateTime.MAX }
                }.take(100)

                // ★改善点2: 絞り込んだ最大100件に対してだけ、ロゴ取得を「並列（async）」で一気に実行する
                val results = withContext(Dispatchers.IO) {
                    topMatches.map { item ->
                        async {
                            UiSearchResultItem(
                                program = item.program,
                                channel = item.channel,
                                logoUrl = getLogoUrl(item.channel)
                            )
                        }
                    }.awaitAll() // 全部のロゴ取得が並行して走り、全部終わるまで待つ
                }

                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("EpgViewModel", "Search Error", e)
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    suspend fun searchSilently(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<UiSearchResultItem> {
        return try {
            val rawResults = withContext(Dispatchers.Default) {
                repository.searchFuturePrograms(keyword, genre, dateStr, isLiveOnly, channelName)
            }

            // こちらも同様に100件に絞ってから並列でロゴを取得
            val topMatches = rawResults.sortedBy {
                try { OffsetDateTime.parse(it.program.start_time) }
                catch (e: Exception) { OffsetDateTime.MAX }
            }.take(100)

            withContext(Dispatchers.IO) {
                topMatches.map { item ->
                    async {
                        UiSearchResultItem(
                            program = item.program,
                            channel = item.channel,
                            logoUrl = getLogoUrl(item.channel)
                        )
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    /**
     * EPGデータ（地デジ、BSなど全波）をバックグラウンドで先読みしてキャッシュに格納します。
     */
    fun preloadEpgDataForSearch(availableTypes: List<String>) {
        val now = OffsetDateTime.now()
        val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
        val end = now.plusDays(7) // 1週間分を取得

        viewModelScope.launch(Dispatchers.IO) {
            availableTypes.map { type ->
                async {
                    if (!repository.hasCacheForType(type)) {
                        repository.fetchAndCacheEpgDataSilently(start, end, type)
                    }
                }
            }.awaitAll()
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            // ★ 修正: 個別のIP判定を排除し、SettingsRepositoryの初期化フラグ(isInitialized)に統合
            combine(
                settingsRepository.isInitialized,
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                _selectedBroadcastingType
            ) { isInit, mIp, mPort, type ->
                mirakurunIp = mIp
                mirakurunPort = mPort

                if (isInit && !hasInitialFetched) {
                    hasInitialFetched = true
                    viewModelScope.launch { refreshEpgData(type) }

                    // 検索・AI予約のために、他の放送波（BS・CS・SKY等）も裏側でメモリにキャッシュしておく
                    preloadEpgDataForSearch(listOf("GR", "BS", "CS", "SKY", "BS4K"))

                } else if (isInit && hasInitialFetched) {
                    refreshEpgData(type)
                }
            }.collectLatest { }
        }
    }

    fun preloadAllEpgData() {
        refreshEpgData()
    }

    fun refreshEpgData(channelType: String? = null) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            if (uiState !is EpgUiState.Success) {
                uiState = EpgUiState.Loading
            }

            val now = OffsetDateTime.now()
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
            val end = now.plusDays(7)

            val typeToFetch = channelType ?: _selectedBroadcastingType.value

            repository.getEpgDataStream(start, end, typeToFetch).collect { result ->
                result.onSuccess { data ->
                    fullEpgData = data
                    fullLogoUrls =
                        withContext(Dispatchers.Default) { data.map { getLogoUrl(it.channel) } }

                    sliceAndEmitEpgData()

                    _isInitialLoadComplete.value = true
                    _isPreloading.value = false
                }.onFailure { e ->
                    if (uiState !is EpgUiState.Success) {
                        uiState = EpgUiState.Error(e.message ?: "Unknown Error")
                        _isInitialLoadComplete.value = true
                    }
                }
            }
        }
    }

    fun updateTargetTime(time: OffsetDateTime) {
        currentTargetTime = time
        sliceAndEmitEpgData()
    }

    private fun getTvDayStart(time: OffsetDateTime): OffsetDateTime {
        val base = time.withHour(4).withMinute(0).withSecond(0).withNano(0)
        return if (time.hour < 4) base.minusDays(1) else base
    }

    private fun sliceAndEmitEpgData() {
        if (fullEpgData.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {

            val tvDayStart = getTvDayStart(currentTargetTime)
            val tvDayEnd = tvDayStart.plusHours(24)

            val slicedData = fullEpgData.map { wrapper ->
                val filteredPrograms = wrapper.programs.filter { prog ->
                    try {
                        val pStart = OffsetDateTime.parse(prog.start_time)
                        val pEnd = OffsetDateTime.parse(prog.end_time)
                        pEnd.isAfter(tvDayStart) && pStart.isBefore(tvDayEnd)
                    } catch (e: Exception) {
                        false
                    }
                }
                wrapper.copy(programs = filteredPrograms)
            }

            uiState = EpgUiState.Success(
                data = slicedData,
                logoUrls = fullLogoUrls,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                targetTime = currentTargetTime
            )
        }
    }

    /**
     * ★ 修正: LiveProviderに委譲したため、複雑な条件分岐が不要になり suspend関数 になりました。
     */
    @OptIn(UnstableApi::class)
    suspend fun getLogoUrl(channel: EpgChannel): String {
        return liveProvider.getChannelLogoUrl(channel.display_channel_id)
    }

    fun updateBroadcastingType(type: String) {
        if (_selectedBroadcastingType.value != type) {
            _selectedBroadcastingType.value = type
        }
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()

    data class Success(
        val data: List<EpgChannelWrapper>,
        val logoUrls: List<String>,
        val mirakurunIp: String,
        val mirakurunPort: String,
        val targetTime: OffsetDateTime
    ) : EpgUiState()

    data class Error(val message: String) : EpgUiState()
}