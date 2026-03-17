package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.ChannelProjection
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.dao.SeriesProjection
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.video.components.RecordCategory
import com.beeregg2001.komorebi.util.TitleNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject

private const val TAG = "Komorebi_RecordVM"
private const val PREF_NAME = "search_history_pref"
private const val KEY_HISTORY = "history_list"

/**
 * 録画リストの検索・絞り込み状態をカプセル化するデータクラス。
 * Paging3のクエリ再生成タイミングを判定するために使用されます。
 */
private data class FilterState(
    val category: RecordCategory,
    val channelId: String?,
    val genre: String?,
    val day: String?,
    val query: String
)

/**
 * 「シリーズ別」表示のためのメタデータを保持するデータクラス。
 * 名寄せされた番組群の代表画像や、検索用のキーワードを管理します。
 */
data class SeriesInfo(
    val displayTitle: String,
    val searchKeyword: String,
    val programCount: Int,
    val representativeVideoId: Int,
    val isEpisodic: Boolean = false
)

/**
 * 録画タブ（ビデオタブおよび録画リスト画面）のUI状態とビジネスロジックを管理するViewModel。
 * KonomiTVからのメタデータ同期、Paging3による無限スクロールリストの生成、
 * 検索履歴の管理、AI名寄せによるシリーズグループ化などを統括します。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository,
    private val historyRepository: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val programDao: RecordedProgramDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // KonomiTVから録画データをローカルDBに同期するエンジンの進行状況
    val syncProgress: StateFlow<SyncProgress> = syncEngine.syncProgress

    // ==========================================
    // 録画リストの絞り込み状態 (Filter States)
    // ==========================================
    private val _selectedCategory = MutableStateFlow(RecordCategory.ALL)
    val selectedCategory: StateFlow<RecordCategory> = _selectedCategory.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId: StateFlow<String?> = _selectedChannelId.asStateFlow()

    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()

    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // 検索モードに入る前のカテゴリ状態を保持（検索解除時に復帰するため）
    private val _categoryBeforeSearch = MutableStateFlow<RecordCategory?>(null)
    val categoryBeforeSearch: StateFlow<RecordCategory?> = _categoryBeforeSearch.asStateFlow()

    private val _selectedSeriesGenre = MutableStateFlow<String?>(null)
    val selectedSeriesGenre: StateFlow<String?> = _selectedSeriesGenre.asStateFlow()

    // ユーザーによる一時的なリストビュー/グリッドビューの切り替え状態
    private val _manualListViewOverride = MutableStateFlow<Boolean?>(null)

    /**
     * 現在の表示モードが「リスト形式」かどうかを判定します。
     * 設定のデフォルト値をベースに、ユーザーがUI上で手動切り替えを行ったらそちらを優先します。
     */
    val isListView: StateFlow<Boolean> = combine(
        settingsRepository.defaultRecordListView,
        _manualListViewOverride
    ) { defaultType, manualOverride ->
        manualOverride ?: (defaultType == "LIST")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ローディング状態の管理
    private val _isRecordingLoading = MutableStateFlow(false)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()

    // 検索履歴
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    // ジャンル一覧（DBにあるものから動的に生成）
    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres: StateFlow<List<String>> = _availableGenres.asStateFlow()

    // シリーズ（名寄せ）およびチャンネルのグループ化データ
    private val _groupedSeries = MutableStateFlow<Map<String, List<SeriesInfo>>>(emptyMap())
    val groupedSeries: StateFlow<Map<String, List<SeriesInfo>>> = _groupedSeries.asStateFlow()

    private val _groupedChannels =
        MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Pair<String, String>>>> =
        _groupedChannels.asStateFlow()

    private var currentSearchQuery: String = ""

    // KonomiTVのストリーム維持（KeepAlive）用のコルーチンジョブ
    private var maintenanceJob: Job? = null

    // フォーカス時の番組詳細データ（あらすじ等、APIから都度取得するもの）
    private val _programDetail = MutableStateFlow<RecordedProgram?>(null)
    val programDetail: StateFlow<RecordedProgram?> = _programDetail.asStateFlow()

    // 詳細データ取得APIの連打防止用Job（フォーカスが高速移動した際にAPIを叩きすぎないための遅延処理用）
    private var detailFetchJob: Job? = null

    /**
     * 同期エラーの状態をクリアします。
     */
    fun clearSyncError() {
        syncEngine.clearError()
    }

    /**
     * 指定された録画番組の詳細情報（CMセクションや詳細なあらすじなど）をKonomiTV APIから取得します。
     * フォーカスが当たってから0.3秒間その場に留まった場合のみ実際にAPI通信を行います（連打防止）。
     */
    fun fetchProgramDetail(videoId: Int) {
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // 0.3秒フォーカスが留まったらAPIを叩く
            repository.getRecordedProgram(videoId).onSuccess {
                _programDetail.value = it
            }.onFailure { Log.e(TAG, "Failed to fetch program detail", it) }
        }
    }

    fun clearProgramDetail() {
        _programDetail.value = null
    }

    /**
     * ホーム画面やビデオタブのトップに表示するための、最近録画された番組のリスト（Pagingなし）。
     */
    val recentRecordings: StateFlow<List<RecordedProgram>> = programDao.getRecentRecordingsFlow()
        .map { entities -> entities.map { RecordDataMapper.toDomainModel(it) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // 初期化時にSharedPreferencesから検索履歴をロード
        loadSearchHistory()

        // 同期エンジンの進行状態を監視し、同期が完了（非アクティブ化）したら
        // シリーズやチャンネルのグループ化インデックスを再構築します。
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.syncProgress.map { it.isSyncing }.distinctUntilChanged()
                .collect { isSyncing ->
                    if (!isSyncing) {
                        val seriesList = programDao.getGroupedSeries()
                        val channelsList = programDao.getDistinctChannels()
                        if (seriesList.isNotEmpty() || channelsList.isNotEmpty()) {
                            buildSeriesAndChannelMaps(seriesList, channelsList)
                        }
                    }
                }
        }

        // アプリ起動時にローカルDBとKonomiTVサーバーの録画データを同期（差分更新）
        syncEngine.launchSyncAllRecords()
    }

    /**
     * Android TVのリモコンの「戻る（Back）」ボタンが押された際のルーティング。
     * 検索中であれば検索を解除し、カテゴリ絞り込み中であれば全件表示に戻し、
     * それ以外であればアクティビティを終了(onExit)させます。
     */
    fun handleBackNavigation(onExit: () -> Unit) {
        when {
            _activeSearchQuery.value.isNotEmpty() -> clearSearch()
            _selectedCategory.value != RecordCategory.ALL -> updateCategory(RecordCategory.ALL)
            else -> onExit()
        }
    }

    fun triggerSmartSync() {
        syncEngine.launchSmartSync()
    }

    /**
     * 録画リストのコアとなる Paging3 データストリーム。
     * カテゴリ、チャンネル、ジャンル、曜日、検索クエリの状態を監視し、
     * いずれかが変更されるたびに新しい PagingData を生成して UI に流します。
     */
    val pagedRecordings: Flow<PagingData<RecordedProgram>> = combine(
        _selectedCategory, _selectedChannelId, _selectedGenre, _selectedDay, _activeSearchQuery
    ) { category, channelId, genre, day, query ->
        FilterState(category, channelId, genre, day, query)
    }.flatMapLatest { state ->
        flow {
            // ★修正: 検索条件が変わった瞬間に空のPagingDataを流し、UI上の古いリスト（残像）を強制的に消去する
            // これにより、遷移時に一瞬全件リストが見えてフォーカスが飛ぶバグを完全に防ぎます。
            emit(PagingData.empty())
            delay(50) // UIが空リストを描画してフォーカスをリセットする隙を作る

            // 状態（FilterState）に基づいて、RoomのDAOから適切なPagingSourceを取得
            val pager = Pager(
                config = PagingConfig(
                    pageSize = 30,
                    prefetchDistance = 10,
                    initialLoadSize = 20,
                    enablePlaceholders = false
                )
            ) {
                when {
                    state.query.isNotBlank() -> programDao.searchPagingSource(state.query)
                    state.category == RecordCategory.CHANNEL && !state.channelId.isNullOrEmpty() -> programDao.getPagingSourceByChannel(
                        state.channelId
                    )

                    state.category == RecordCategory.GENRE && !state.genre.isNullOrEmpty() -> programDao.getPagingSourceByGenre(
                        state.genre
                    )

                    state.category == RecordCategory.TIME && !state.day.isNullOrEmpty() -> {
                        // "月曜日" などの文字列を Calendar の曜日数値（0=日, 1=月...）にマッピング
                        val dayOfWeekStr = when (state.day.replace("曜日", "")) {
                            "日" -> "0"
                            "月" -> "1"
                            "火" -> "2"
                            "水" -> "3"
                            "木" -> "4"
                            "金" -> "5"
                            "土" -> "6"
                            else -> "0"
                        }
                        programDao.getPagingSourceByDayOfWeek(dayOfWeekStr)
                    }

                    state.category == RecordCategory.UNWATCHED -> programDao.getPagingSourceUnwatched()
                    else -> programDao.getAllPagingSource()
                }
            }

            // Roomから取得したEntityを、UI層で扱うDomainModel(RecordedProgram)に変換してEmit
            emitAll(pager.flow.map { pagingData ->
                pagingData.map { entity -> RecordDataMapper.toDomainModel(entity) }
            })
        }
    }.cachedIn(viewModelScope)

    fun updateListView(isList: Boolean) {
        _manualListViewOverride.value = isList
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSeriesGenre(genre: String?) {
        _selectedSeriesGenre.value = genre
    }

    // ==========================================
    // カテゴリ・絞り込み状態の更新メソッド群
    // ==========================================
    fun updateCategory(category: RecordCategory) {
        if (_selectedCategory.value == category) return
        _selectedCategory.value = category
        _selectedGenre.value = null
        _selectedChannelId.value = null
        _selectedDay.value = null
        if (category == RecordCategory.SERIES) buildSeriesIndex()
    }

    fun updateGenre(genre: String?) {
        _selectedGenre.value = genre
        _selectedCategory.value = RecordCategory.GENRE
    }

    fun updateDay(day: String?) {
        _selectedDay.value = day
        _selectedCategory.value = RecordCategory.TIME
    }

    fun updateChannel(channelId: String?) {
        _selectedChannelId.value = channelId
        _selectedCategory.value = RecordCategory.CHANNEL
        _selectedGenre.value = null
        _selectedDay.value = null
        currentSearchQuery = ""
    }

    /**
     * 検索を実行し、履歴に保存します。
     * 検索モードに入る前のカテゴリ（ALLなど）を保持しておき、クリア時に復元できるようにします。
     */
    fun searchRecordings(query: String) {
        if (_activeSearchQuery.value.isEmpty() && query.isNotEmpty()) {
            _categoryBeforeSearch.value = _selectedCategory.value
        }
        _activeSearchQuery.value = query
        _searchQuery.value = query
        currentSearchQuery = query
        if (query.isNotBlank()) addSearchHistory(query)
        _selectedCategory.value = RecordCategory.ALL
        _selectedGenre.value = null
        _selectedChannelId.value = null
        _selectedDay.value = null
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        currentSearchQuery = ""
        _categoryBeforeSearch.value?.let {
            _selectedCategory.value = it
            _categoryBeforeSearch.value = null
        } ?: run { _selectedCategory.value = RecordCategory.ALL }
    }

    fun fetchRecentRecordings(forceRefresh: Boolean = false) {
        syncEngine.launchSyncAllRecords(forceFullSync = forceRefresh)
    }

    fun loadNextPage() {}

    // ==========================================
    // 検索履歴の管理 (SharedPreferences)
    // ==========================================
    private fun loadSearchHistory() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_HISTORY, "[]")
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
        currentList.remove(query); currentList.add(0, query) // 重複排除と先頭への追加
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
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
            }
        }
    }

    // ==========================================
    // 視聴履歴（レジュームポイント）の管理
    // ==========================================
    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch { historyRepository.saveWatchHistory(program, positionSeconds) }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            try {
                historyRepository.clearWatchHistory()
            } catch (e: Exception) {
            }
        }
    }

    // ==========================================
    // KonomiTV ストリーミング維持 (Keep Alive)
    // ==========================================
    /**
     * KonomiTVの動画ストリーミング（HLS）のセッションを維持するためのメソッドです。
     * 定期的にAPIを叩かないと、サーバー側で視聴終了とみなされエンコードプロセスが破棄されてしまいます。
     * 同時に視聴履歴（どこまで見たか）も更新します。
     */
    @UnstableApi
    fun startStreamMaintenance(
        program: RecordedProgram,
        quality: String,
        sessionId: String,
        getPositionSeconds: () -> Double
    ) {
        stopStreamMaintenance()
        maintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentPos = getPositionSeconds()
                    repository.keepAlive(program.recordedVideo.id, quality, sessionId)
                    historyRepository.saveWatchHistory(program, currentPos)
                } catch (e: Exception) {
                }
                delay(20000) // 20秒間隔で生存信号を送信
            }
        }
    }

    fun stopStreamMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopStreamMaintenance()
    }

    // ==========================================
    // ニコニコ実況過去ログの取得
    // ==========================================
    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            repository.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }
    }

    fun buildSeriesIndex() {}

    // ==========================================
    // サイドメニュー用データの構築 (シリーズ・チャンネル)
    // ==========================================
    /**
     * 録画番組リストから、「チャンネル別」と「シリーズ別（AI名寄せ）」の
     * サイドナビゲーション用データを構築します。
     * 重い処理のため、Dispatchers.IO を通じてバックグラウンドで実行されます。
     */
    private suspend fun buildSeriesAndChannelMaps(
        seriesList: List<SeriesProjection>,
        channelsList: List<ChannelProjection>
    ) {
        _isSeriesLoading.value = true
        try {
            // --- チャンネル一覧の構築 ---
            val allChannelMap =
                mutableMapOf<String, MutableMap<String, Triple<String, String, String>>>()
            channelsList.forEach { ch ->
                val type = if (ch.channelType == "GR") "地デジ" else ch.channelType ?: "その他"
                val channelTypeMap = allChannelMap.getOrPut(type) { mutableMapOf() }
                if (!channelTypeMap.containsKey(ch.channelId)) {
                    channelTypeMap[ch.channelId] =
                        Triple(ch.channelName ?: "", ch.channelId, ch.channelId)
                }
            }

            // 放送波ごとのソート順序を定義し、さらに各波の中ではチャンネル番号順にソート
            val typePriority = listOf("地デジ", "BS", "BS4K", "CS", "SKY", "その他")
            val extractNumber = { idStr: String ->
                Regex("\\d+").find(idStr)?.value?.toIntOrNull() ?: Int.MAX_VALUE
            }
            _groupedChannels.value = allChannelMap.entries
                .sortedBy { (type, _) ->
                    typePriority.indexOf(type).let { if (it != -1) it else typePriority.size }
                }
                .associate { entry ->
                    entry.key to entry.value.values.sortedWith(
                        compareBy({ extractNumber(it.third) }, { it.third })
                    ).map { Pair(it.first, it.second) }
                }

            // --- シリーズ（名寄せ）一覧の構築 ---
            val genresSet = mutableSetOf<String>()
            val finalGroupedSeries = mutableMapOf<String, MutableList<SeriesInfo>>()

            seriesList.forEach { proj ->
                // 番組数が2つ以上、またはアニメなどのエピソード形式（isEpisodic = true）のものだけをシリーズとして扱う
                if (proj.programCount >= 2 || proj.isEpisodic) {
                    val majorGenre = proj.genres?.firstOrNull()?.major ?: "その他"
                    genresSet.add(majorGenre)

                    // SQLでのLIKE検索用にキーワードを正規化（全角半角、記号のブレを吸収）
                    val searchKeyword = TitleNormalizer.toSqlSearchQuery(proj.seriesName)

                    val seriesInfo = SeriesInfo(
                        displayTitle = proj.seriesName,
                        searchKeyword = searchKeyword,
                        programCount = proj.programCount,
                        representativeVideoId = proj.representativeVideoId, // サムネイル用
                        isEpisodic = proj.isEpisodic
                    )

                    val list = finalGroupedSeries.getOrPut(majorGenre) { mutableListOf() }
                    list.add(seriesInfo)
                }
            }

            _availableGenres.value = genresSet.sorted()

            // ジャンルごとにあいうえお順（タイトル順）でソート
            _groupedSeries.value = finalGroupedSeries.mapValues { entry ->
                entry.value.sortedBy { it.displayTitle }
            }.filterValues { it.isNotEmpty() }

        } catch (e: Exception) {
            Log.e(TAG, "Map Build Error", e)
        } finally {
            _isSeriesLoading.value = false
        }
    }
}