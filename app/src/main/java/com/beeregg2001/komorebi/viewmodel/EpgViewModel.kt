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
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext // ★追加
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray // ★追加
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
 * KonomiTV APIからの数日分・数十チャンネルに及ぶ巨大な番組データ（fullEpgData）をメモリ上に保持し、
 * UIの要求（表示したい日付や時間帯）に応じて1日分だけをスライスしてUI層（CanvasEngine）に渡す役割を担います。
 */
@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: EpgRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context // ★追加: SharedPreferencesにアクセスするため
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

    // サーバーの接続情報（ロゴ画像のURL生成などに使用）
    private var mirakurunIp = ""
    private var mirakurunPort = ""
    private var konomiIp = ""
    private var konomiPort = ""

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

    init {
        loadSearchHistory() // ★追加: アプリ起動時に履歴を読み込む
        loadInitialData()
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
     * KonomiTVのAPIを叩いて、未来の番組（番組表データ）からキーワード検索を実行します。
     * 結果は番組単体ではなく、チャンネル情報とロゴURLを結合したUiSearchResultItemのリストとしてUIに提供します。
     */
    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            _activeSearchQuery.value = trimmed

            // ★修正: ローカル保存機能を持つ関数を使用する
            addSearchHistory(trimmed)

            viewModelScope.launch(Dispatchers.Default) {
                _isSearching.value = true
                val results = repository.searchFuturePrograms(trimmed)
                val uiResults = results.map { item ->
                    UiSearchResultItem(
                        program = item.program,
                        channel = item.channel,
                        logoUrl = getLogoUrl(item.channel)
                    )
                }
                _searchResults.value = uiResults
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    /**
     * EPGデータ（地デジ、BSなど全波）をバックグラウンドで先読みしてキャッシュに格納します。
     * アプリの起動直後や待機時間に実行しておくことで、番組表を開いた瞬間にデータが表示されるようになります。
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

    /**
     * IPアドレスなどの設定情報が読み込まれるのを監視し、準備ができたら番組表データの取得を開始します。
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            combine(
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                settingsRepository.konomiIp,
                settingsRepository.konomiPort,
                _selectedBroadcastingType
            ) { mIp, mPort, kIp, kPort, type ->
                mirakurunIp = mIp
                mirakurunPort = mPort
                konomiIp = kIp
                konomiPort = kPort

                val isMirakurunReady = mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()
                val isKonomiReady = konomiIp.isNotEmpty() && konomiPort.isNotEmpty()

                if ((isMirakurunReady || isKonomiReady) && !hasInitialFetched) {
                    hasInitialFetched = true
                    viewModelScope.launch { refreshEpgData(type) }
                } else if ((isMirakurunReady || isKonomiReady) && hasInitialFetched) {
                    // 設定情報（または放送波タブ）が変更された場合、再取得を行う
                    refreshEpgData(type)
                }
            }.collectLatest { }
        }
    }

    fun preloadAllEpgData() {
        refreshEpgData()
    }

    /**
     * KonomiTVサーバーから1週間分の番組表データを取得し、メモリ（fullEpgData）に保持します。
     * 取得完了後、UIに渡すために1日分だけのデータをスライス（sliceAndEmitEpgData）します。
     */
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
                    // 1週間分の巨大なデータをメモリに保持
                    fullEpgData = data

                    // スクロール時にUIスレッドをブロックしないよう、ロゴURLの解決を事前に計算しておく
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

    /**
     * ユーザーが番組表上で別の日付・時間にジャンプした際に呼ばれます。
     * 目標時間を更新し、それに合わせた1日分のデータスライスを作り直します。
     */
    fun updateTargetTime(time: OffsetDateTime) {
        currentTargetTime = time
        sliceAndEmitEpgData()
    }

    /**
     * テレビ番組表特有の「1日の区切り（朝4時）」を計算します。
     * 例: 10月2日の午前2時は「10月1日の深夜26時」として扱うため、ベースとなる日付は10月1日の朝4時になります。
     */
    private fun getTvDayStart(time: OffsetDateTime): OffsetDateTime {
        val base = time.withHour(4).withMinute(0).withSecond(0).withNano(0)
        return if (time.hour < 4) base.minusDays(1) else base
    }

    /**
     * メモリ上に保持している数日分・数万件の全番組データ（fullEpgData）から、
     * 「currentTargetTime（目標時間）が属するテレビ的1日（朝4時〜翌朝4時）」の分だけを抽出（スライス）し、
     * UI描画用の EpgUiState.Success として発行します。
     * これにより、Compose層での描画負荷を劇的に軽減しています。
     */
    private fun sliceAndEmitEpgData() {
        if (fullEpgData.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) { // 重いフィルタリング処理なのでDefaultディスパッチャを使用

            val tvDayStart = getTvDayStart(currentTargetTime)
            val tvDayEnd = tvDayStart.plusHours(24)

            val slicedData = fullEpgData.map { wrapper ->
                // そのチャンネルに含まれる番組のうち、表示対象の24時間にかぶっているものだけを残す
                val filteredPrograms = wrapper.programs.filter { prog ->
                    try {
                        val pStart = OffsetDateTime.parse(prog.start_time)
                        val pEnd = OffsetDateTime.parse(prog.end_time)
                        // 番組の終了時間が対象枠の開始より後 かつ 番組の開始時間が対象枠の終了より前
                        pEnd.isAfter(tvDayStart) && pStart.isBefore(tvDayEnd)
                    } catch (e: Exception) {
                        false
                    }
                }
                wrapper.copy(programs = filteredPrograms)
            }

            // スライスした軽量なデータをUIレイヤーに送信
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
     * Mirakurunが設定されている場合はMirakurunから高品質なロゴを、
     * そうでなければKonomiTVからロゴを取得するためのURLを生成します。
     */
    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        return if (mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()) {
            UrlBuilder.getMirakurunLogoUrl(
                mirakurunIp, mirakurunPort, channel.network_id.toLong(), channel.service_id.toLong()
            )
        } else {
            UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.display_channel_id)
        }
    }

    fun updateBroadcastingType(type: String) {
        if (_selectedBroadcastingType.value != type) {
            _selectedBroadcastingType.value = type
        }
    }
}

/**
 * EPG画面の描画状態を表すシールドクラス。
 */
sealed class EpgUiState {
    object Loading : EpgUiState()

    data class Success(
        val data: List<EpgChannelWrapper>, // スライスされた1日分の番組データ
        val logoUrls: List<String>,        // キャッシュ済みのロゴURLリスト
        val mirakurunIp: String,
        val mirakurunPort: String,
        val targetTime: OffsetDateTime     // UIがスクロールの基準とすべき目標時間
    ) : EpgUiState()

    data class Error(val message: String) : EpgUiState()
}