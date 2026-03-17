@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.foundation.lazy.grid.TvLazyGridState
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SeriesInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「ビデオ」タブから遷移する、すべての録画番組・シリーズを探すための総合リスト画面。
 * Paging3を用いた無限スクロールリスト、サイドナビゲーション（カテゴリやジャンルでの絞り込み）、
 * 検索バー、そしてリスト/グリッド表示の切り替えなどを統括します。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RecordListScreen(
    viewModel: RecordViewModel = hiltViewModel(),
    konomiIp: String,
    konomiPort: String,
    customTitle: String? = null, // 特定のシリーズだけを開く際に外部から指定されるタイトル
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onBack: () -> Unit,
    isFromVideoTabSearch: Boolean = false
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val syncProgress by viewModel.syncProgress.collectAsState()

    // ==========================================
    // 1. 初回DB構築中のブロッキングUI
    // ==========================================
    // アプリ初回起動時の「全件同期」が終わるまでは、リストを描画せずにプログレス画面を表示して待機させる
    if (syncProgress.isInitialSyncPhase) {
        // フォーカスが他へ逃げないようにトラップを仕掛ける
        val blockFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); blockFocusRequester.safeRequestFocus("InitialSyncBlock") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .focusRequester(blockFocusRequester)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 6.dp
                )
                Text(
                    text = "データベースを構築しています...",
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        if (syncProgress.total > 0) {
                            Text(
                                text = "進捗: ${syncProgress.current} / ${syncProgress.total} 件",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        } else {
                            Text(
                                text = "進捗: ${syncProgress.current} 件取得済み",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "※この処理は初回のみ発生します。\n完了するまでビデオタブは操作できませんが、\n「ライブ視聴」など他の機能をご利用いただけます。",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp
                        )
                    }
                }
            }
        }
        return // 初回構築中はこの画面を描画してここでリターン（以降の複雑なリストUIを描画しない）
    }

    // ==========================================
    // 2. ViewModelからのデータ・Stateの収集
    // ==========================================
    // Paging3のストリームをComposeのLazyリストで扱える形に変換
    val pagedRecordings = viewModel.pagedRecordings.collectAsLazyPagingItems()

    // サイドメニューや検索に必要な各種メタデータ群
    val searchHistory by viewModel.searchHistory.collectAsState()
    val groupedChannels by viewModel.groupedChannels.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()
    val availableGenres by viewModel.availableGenres.collectAsState()
    val groupedSeries by viewModel.groupedSeries.collectAsState()

    val isSeriesLoading by viewModel.isSeriesLoading.collectAsState()
    val activeSearchQuery by viewModel.activeSearchQuery.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isListView by viewModel.isListView.collectAsState()
    val selectedSeriesGenre by viewModel.selectedSeriesGenre.collectAsState()
    val programDetail by viewModel.programDetail.collectAsState()
    val isRecLoading by viewModel.isRecordingLoading.collectAsState()

    // UI開閉状態（ペイン、詳細画面など）を管理するカスタムStateクラス
    val menuState = rememberRecordListMenuState()

    // テレビUI特有の「フォーカス位置」を強制指定するためのFocusRequester群
    val focuses = rememberRecordListFocusRequesters()

    // FocusTicketManager: "非同期的にリストが描画された直後にフォーカスを当てる" ためのチケット発行システム
    val ticketManager = rememberFocusTicketManager()

    // ==========================================
    // 3. ローカルUI Stateの管理
    // ==========================================
    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var focusedSeries by remember { mutableStateOf<SeriesInfo?>(null) }
    var savedFocusProgramId by remember { mutableStateOf<Int?>(null) } // サイドメニュー等へ移動した際に元の位置を記憶

    val paneTransitionState =
        remember { MutableTransitionState(false) }.apply { targetState = menuState.isPaneOpen }

    // ライフサイクル監視: 他画面からこの画面に戻ってきた際、「未視聴」カテゴリならリストを更新する
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (selectedCategory == RecordCategory.UNWATCHED) {
                    focuses.navPane.safeRequestFocus("RetreatToNav")
                    pagedRecordings.refresh()
                    ticketManager.issue(FocusTicket.LIST_TOP)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 画面上部に表示するタイトルを、現在の絞り込み条件（カテゴリや曜日等）に応じて動的に生成
    val currentDisplayTitle by remember(
        selectedCategory, selectedGenre, selectedDay, selectedSeriesGenre, customTitle
    ) {
        mutableStateOf(
            when (selectedCategory) {
                RecordCategory.ALL -> customTitle ?: "録画リスト"
                RecordCategory.UNWATCHED -> "未視聴の録画リスト"
                RecordCategory.SERIES -> if (!selectedSeriesGenre.isNullOrEmpty()) "${selectedSeriesGenre}のシリーズ一覧" else "シリーズ一覧"
                RecordCategory.GENRE -> if (!selectedGenre.isNullOrEmpty()) "${selectedGenre}の録画リスト" else "ジャンル別の録画リスト"
                RecordCategory.TIME -> if (!selectedDay.isNullOrEmpty()) "${selectedDay}の録画リスト" else "曜日別の録画リスト"
                RecordCategory.CHANNEL -> "チャンネル別の録画リスト"
                else -> customTitle ?: "録画リスト"
            }
        )
    }

    // ==========================================
    // 4. リスト・グリッドのスクロール状態管理
    // ==========================================
    // カテゴリや検索条件が変わった際、スクロール位置をリセットするために一意のキー(stateKey)を生成してStateを再生成する
    val stateKey = remember(
        selectedCategory,
        selectedGenre,
        selectedDay,
        selectedSeriesGenre,
        activeSearchQuery,
        ticketManager.forceResetTick
    ) {
        "${selectedCategory.name}_${selectedGenre}_${selectedDay}_${selectedSeriesGenre}_${activeSearchQuery}_${ticketManager.forceResetTick}"
    }

    val listState = remember(stateKey) { LazyListState() }
    val gridState = remember(stateKey) { TvLazyGridState() }
    val seriesListState = remember(stateKey) { LazyListState() }

    // フォーカスチケットを発行して良いか（最初の要素が描画されているか）を判定するロジック
    val isListFirstItemReady by remember(
        selectedCategory, isListView, pagedRecordings.itemCount, groupedSeries
    ) {
        derivedStateOf {
            if (isListView) {
                if (selectedCategory == RecordCategory.SERIES) seriesListState.layoutInfo.visibleItemsInfo.isNotEmpty()
                else listState.layoutInfo.visibleItemsInfo.isNotEmpty()
            } else gridState.layoutInfo.visibleItemsInfo.isNotEmpty()
        }
    }

    val isCategoryImplemented = remember(selectedCategory) {
        selectedCategory == RecordCategory.ALL || selectedCategory == RecordCategory.UNWATCHED || selectedCategory == RecordCategory.GENRE || selectedCategory == RecordCategory.SERIES || selectedCategory == RecordCategory.CHANNEL || selectedCategory == RecordCategory.TIME
    }

    // 現在の条件に合致するコンテンツが存在するかどうかのフラグ
    val hasContent by remember(
        selectedCategory,
        pagedRecordings.itemCount,
        groupedSeries,
        selectedSeriesGenre,
        isCategoryImplemented
    ) {
        derivedStateOf {
            if (!isCategoryImplemented) false
            else {
                when (selectedCategory) {
                    RecordCategory.SERIES -> (if (!selectedSeriesGenre.isNullOrEmpty()) groupedSeries[selectedSeriesGenre]
                        ?: emptyList() else groupedSeries.values.flatten()).isNotEmpty()

                    else -> pagedRecordings.itemCount > 0
                }
            }
        }
    }

    val isLoadingAny by remember(isRecLoading, isSeriesLoading, pagedRecordings.loadState.refresh) {
        derivedStateOf { isRecLoading || isSeriesLoading || pagedRecordings.loadState.refresh is LoadState.Loading }
    }

    // 上部の検索バー等から下キーを押した際に、リスト内の適切な要素へフォーカスを流すための設定
    var listContentDownRequester by remember { mutableStateOf<FocusRequester>(focuses.firstItem) }

    val topBarDownRequester = if (isListView && hasContent && isCategoryImplemented) {
        listContentDownRequester
    } else if (!isCategoryImplemented || !hasContent) {
        if (isListView) focuses.navPane else focuses.searchOpenButton
    } else {
        focuses.contentContainer
    }

    val isNavOverlayVisible = !isListView && menuState.isNavPaneOpen

    // ==========================================
    // 5. 非同期フォーカスチケットの消費ループ
    // ==========================================
    val currentTicket = ticketManager.currentTicket
    val issueTime = ticketManager.issueTime

    LaunchedEffect(currentTicket, issueTime, isListFirstItemReady, hasContent, isLoadingAny) {
        when (currentTicket) {
            FocusTicket.LIST_TOP -> {
                // コンテンツがあり、かつ描画が終わったら、リストの先頭へフォーカスを移動
                if (hasContent && isListFirstItemReady && !isLoadingAny) {
                    delay(150)
                    focuses.firstItem.safeRequestFocusWithRetry("Ticket_LIST_TOP")
                    ticketManager.consume(FocusTicket.LIST_TOP)
                } else if (!hasContent && !isLoadingAny) {
                    // コンテンツがない場合はサイドメニューや検索ボタンへ逃す
                    delay(150)
                    if (isListView) focuses.navPane.safeRequestFocusWithRetry("Ticket_EmptyNav")
                    else focuses.searchOpenButton.safeRequestFocusWithRetry("Ticket_EmptySearch")
                    ticketManager.consume(FocusTicket.LIST_TOP)
                }
            }

            FocusTicket.NAV_PANE -> {
                focuses.navPane.safeRequestFocusWithRetry("Ticket_NAV_PANE")
                ticketManager.consume(FocusTicket.NAV_PANE)
            }

            FocusTicket.PANE -> {
                if (menuState.isPaneListReady) {
                    focuses.paneFirstItem.safeRequestFocusWithRetry("Ticket_PANE")
                    ticketManager.consume(FocusTicket.PANE)
                }
            }

            else -> {}
        }
    }

    // 初回起動時のフォーカス要求
    LaunchedEffect(Unit) {
        if (menuState.isInitialFocusRequested) {
            delay(200)
            ticketManager.issue(FocusTicket.LIST_TOP)
            menuState.isInitialFocusRequested = false
        }
    }

    // ==========================================
    // 6. ユーザーアクションのハンドリング関数
    // ==========================================

    // 検索実行時の処理
    val executeSearch: (String) -> Unit = { query ->
        savedFocusProgramId = null
        focusedProgram = null
        focusedSeries = null
        viewModel.searchRecordings(query)
        menuState.isSearchBarVisible = false; menuState.isDetailActive = false
        ticketManager.issue(FocusTicket.LIST_TOP)
    }

    // 左サイドメニューでカテゴリ（全ての録画、未視聴、ジャンル別など）を選択した時の処理
    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        val isSameCategory = selectedCategory == category
        menuState.isSelectionMade = false

        savedFocusProgramId = null
        focusedProgram = null
        focusedSeries = null

        // 既に選択済みのカテゴリを再タップした場合は、サブペインを開くか、全件リストをリフレッシュする
        if (isSameCategory) {
            when (category) {
                RecordCategory.GENRE -> {
                    menuState.isGenrePaneOpen = true; ticketManager.issue(FocusTicket.PANE)
                }

                RecordCategory.CHANNEL -> {
                    menuState.isChannelPaneOpen = true; ticketManager.issue(FocusTicket.PANE)
                }

                RecordCategory.SERIES -> {
                    menuState.isSeriesGenrePaneOpen = true; ticketManager.issue(FocusTicket.PANE)
                }

                RecordCategory.TIME -> {
                    menuState.isDayPaneOpen = true; ticketManager.issue(FocusTicket.PANE)
                }

                else -> {
                    ticketManager.triggerHardReset()
                    pagedRecordings.refresh()
                    ticketManager.issue(FocusTicket.LIST_TOP)
                    menuState.isNavPaneOpen = false
                }
            }
        } else {
            // 別のカテゴリを選択した場合
            // フォーカス迷子を防ぐため一時的にSafeHouse(画面外の1px)へフォーカスを逃がす
            focuses.loadingSafeHouse.safeRequestFocus("SafeHouse_CategoryChange")
            viewModel.updateCategory(category)

            if (!category.isPaneCategory) {
                menuState.isGenrePaneOpen = false; menuState.isChannelPaneOpen =
                    false; menuState.isSeriesGenrePaneOpen = false; menuState.isDayPaneOpen = false
                ticketManager.issue(FocusTicket.LIST_TOP)
                menuState.isNavPaneOpen = false
            } else {
                menuState.isGenrePaneOpen = (category == RecordCategory.GENRE)
                menuState.isChannelPaneOpen = (category == RecordCategory.CHANNEL)
                menuState.isSeriesGenrePaneOpen = (category == RecordCategory.SERIES)
                menuState.isDayPaneOpen = (category == RecordCategory.TIME)
                ticketManager.issue(FocusTicket.PANE)
            }
        }
    }

    // 左キーなどでメインリストからサイドメニューを展開する際の処理
    val handleOpenNavPane = {
        if (selectedCategory == RecordCategory.SERIES) {
            savedFocusProgramId = focusedSeries?.representativeVideoId
        } else {
            savedFocusProgramId = focusedProgram?.id
        }
        menuState.isNavPaneOpen = true
        ticketManager.issue(FocusTicket.NAV_PANE)
    }

    // サイドメニューから右キーでメインリストに戻る際の処理（元の位置を復元する）
    val onRightKeyFromNav = {
        if (savedFocusProgramId != null) {
            ticketManager.issue(FocusTicket.TARGET_ID, savedFocusProgramId)
            savedFocusProgramId = null
        } else {
            ticketManager.issue(FocusTicket.LIST_TOP)
        }
    }

    // リモコンの戻るボタンが押された際の階層的ルーティング
    val handleBackPress: () -> Unit = {
        when {
            menuState.isDetailActive -> menuState.isDetailActive = false
            menuState.isGenrePaneOpen -> {
                menuState.isGenrePaneOpen = false; ticketManager.issue(FocusTicket.NAV_PANE)
            }

            menuState.isChannelPaneOpen -> {
                menuState.isChannelPaneOpen = false; ticketManager.issue(FocusTicket.NAV_PANE)
            }

            menuState.isDayPaneOpen -> {
                menuState.isDayPaneOpen = false; ticketManager.issue(FocusTicket.NAV_PANE)
            }

            menuState.isSeriesGenrePaneOpen -> {
                menuState.isSeriesGenrePaneOpen = false; ticketManager.issue(FocusTicket.NAV_PANE)
            }

            menuState.isNavPaneOpen -> {
                menuState.isNavPaneOpen = false
                onRightKeyFromNav()
            }

            menuState.isSearchBarVisible -> {
                menuState.isSearchBarVisible = false
                scope.launch {
                    delay(50)
                    if (activeSearchQuery.isNotEmpty()) focuses.contentContainer.safeRequestFocus("SearchHide")
                    else if (isListView) ticketManager.issue(FocusTicket.NAV_PANE)
                    else focuses.contentContainer.safeRequestFocus("SearchHideGrid")
                }
            }

            activeSearchQuery.isNotEmpty() -> {
                if (isListView) ticketManager.issue(FocusTicket.NAV_PANE) else focuses.contentContainer.safeRequestFocus(
                    "BackToGrid"
                )
                viewModel.clearSearch()
                ticketManager.issue(if (isListView && selectedCategory != RecordCategory.SERIES) FocusTicket.NAV_PANE else FocusTicket.LIST_TOP)
            }

            menuState.isBackButtonFocused -> onBack()
            isListView && !menuState.isNavFocused -> ticketManager.issue(FocusTicket.NAV_PANE)
            else -> onBack()
        }
    }

    BackHandler(enabled = !menuState.isDetailActive) { handleBackPress() }

    // リスト/グリッド表示形式による左パディングのアニメーション
    val contentStartPadding by animateDpAsState(
        targetValue = if (isListView && !menuState.isSearchBarVisible && activeSearchQuery.isEmpty()) 268.dp else 28.dp,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "ContentStartPadding"
    )

    // ==========================================
    // 7. UI コンポジション (画面描画)
    // ==========================================
    Box(modifier = Modifier.fillMaxSize()) {
        // SafeHouse: 画面全体が再構築される際、フォーカスがシステムによって強奪されないよう、
        // 画面外に隠した1x1ピクセルの透明なBoxに一時的に避難させます。
        Box(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focuses.loadingSafeHouse)
                .focusable()
        )

        // メインコンテンツエリア (リスト・グリッド本体)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 88.dp)
                .onKeyEvent { if (!paneTransitionState.isIdle) true else false }) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = contentStartPadding, end = 28.dp, bottom = 20.dp)
                    .focusProperties {
                        // サイドメニューや詳細ペインが開いている間は、背後のリストへのフォーカス移動をブロック
                        if (menuState.isPaneOpen || menuState.isDetailActive || isNavOverlayVisible) {
                            up = FocusRequester.Cancel; down = FocusRequester.Cancel; left =
                                FocusRequester.Cancel; right = FocusRequester.Cancel
                        }
                    }) {

                key(stateKey, isListView) {
                    if (isListView) {
                        // ▼ リスト表示モード
                        when (selectedCategory) {
                            RecordCategory.SERIES -> {
                                val list =
                                    if (!selectedSeriesGenre.isNullOrEmpty()) groupedSeries[selectedSeriesGenre]
                                        ?: emptyList() else groupedSeries.values.flatten()
                                RecordSeriesContent(
                                    seriesList = list,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    onSeriesClick = { executeSearch(it) },
                                    onOpenNavPane = handleOpenNavPane,
                                    isListView = true,
                                    firstItemFocusRequester = focuses.firstItem,
                                    contentContainerFocusRequester = focuses.contentContainer,
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    isSearchBarVisible = menuState.isSearchBarVisible,
                                    onBackPress = handleBackPress,
                                    listState = seriesListState,
                                    ticketManager = ticketManager,
                                    onFocusedSeriesChanged = { focusedSeries = it }
                                )
                            }

                            else -> {
                                RecordListContent(
                                    pagedRecordings = pagedRecordings,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    isSearchBarVisible = menuState.isSearchBarVisible,
                                    isKeyboardActive = false,
                                    firstItemFocusRequester = focuses.firstItem,
                                    contentContainerFocusRequester = focuses.contentContainer,
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    onProgramClick = onProgramClick,
                                    onSeriesSearch = { keyword ->
                                        executeSearch(keyword)
                                        focusedProgram?.id?.let {
                                            ticketManager.issue(
                                                FocusTicket.TARGET_ID,
                                                it
                                            )
                                        }
                                    },
                                    isDetailVisible = menuState.isDetailActive,
                                    onDetailStateChange = { menuState.isDetailActive = it },
                                    onBackPress = handleBackPress,
                                    ticketManager = ticketManager,
                                    listState = listState,
                                    fetchedProgramDetail = programDetail,
                                    onFetchDetail = { viewModel.fetchProgramDetail(it) },
                                    onClearDetail = { viewModel.clearProgramDetail() },
                                    onFocusedItemChanged = { focusedProgram = it },
                                    onOpenNavPane = handleOpenNavPane,
                                    onTopBarDownRequesterChanged = { listContentDownRequester = it }
                                )
                            }
                        }
                    } else {
                        // ▼ グリッド（カード）表示モード
                        when (selectedCategory) {
                            RecordCategory.SERIES -> {
                                val list =
                                    if (!selectedSeriesGenre.isNullOrEmpty()) groupedSeries[selectedSeriesGenre]
                                        ?: emptyList() else groupedSeries.values.flatten()
                                RecordSeriesGridContent(
                                    seriesList = list,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    onSeriesClick = { executeSearch(it) },
                                    onOpenNavPane = handleOpenNavPane,
                                    firstItemFocusRequester = focuses.firstItem,
                                    contentContainerFocusRequester = focuses.contentContainer,
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    isSearchBarVisible = menuState.isSearchBarVisible,
                                    onBackPress = handleBackPress,
                                    gridState = gridState,
                                    ticketManager = ticketManager,
                                    onFocusedSeriesChanged = { focusedSeries = it }
                                )
                            }

                            else -> {
                                RecordGridContent(
                                    pagedRecordings = pagedRecordings,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    gridState = gridState,
                                    isSearchBarVisible = menuState.isSearchBarVisible,
                                    isKeyboardActive = false,
                                    firstItemFocusRequester = focuses.firstItem,
                                    contentContainerFocusRequester = focuses.contentContainer,
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    onProgramClick = onProgramClick,
                                    onOpenNavPane = handleOpenNavPane,
                                    ticketManager = ticketManager,
                                    onFocusedItemChanged = { focusedProgram = it }
                                )
                            }
                        }
                    }
                }

                // 番組が存在しない、またはAPI読み込み中の表示
                AnimatedVisibility(
                    visible = !hasContent,
                    enter = fadeIn(tween(300)), exit = fadeOut(tween(300))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.background),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingAny) CircularProgressIndicator(color = colors.textPrimary)
                        else Text(
                            "録画番組がありません",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.textSecondary.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // 左側に表示されるサイドナビゲーション・カテゴリ選択ペイン群
            Box(
                modifier = Modifier
                    .zIndex(5f)
                    .fillMaxSize()
            ) {
                RecordListOverlay(
                    menuState = menuState,
                    focuses = focuses,
                    ticketManager = ticketManager,
                    selectedCategory = selectedCategory,
                    availableGenres = availableGenres,
                    selectedGenre = selectedGenre,
                    groupedChannels = groupedChannels,
                    selectedDay = selectedDay,
                    groupedSeries = groupedSeries,
                    selectedSeriesGenre = selectedSeriesGenre,
                    isListView = isListView,
                    isSearchActive = activeSearchQuery.isNotEmpty() || menuState.isSearchBarVisible,
                    isNavOverlayVisible = isNavOverlayVisible,
                    paneTransitionState = paneTransitionState,
                    hasContent = hasContent,
                    onCategorySelect = handleCategorySelect,
                    onGenreSelect = { viewModel.updateGenre(it) },
                    onChannelSelect = { viewModel.updateChannel(it) },
                    onDaySelect = { viewModel.updateDay(it) },
                    onSeriesGenreSelect = { viewModel.updateSeriesGenre(it) },
                    onRightKeyFromNav = onRightKeyFromNav
                )
            }
        }

        // 画面上部に固定されるヘッダーバー (検索バー、戻るボタン、リスト/グリッド切替ボタン等)
        RecordScreenTopBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .zIndex(100f)
                .focusProperties {
                    up = FocusRequester.Cancel
                    if (menuState.isPaneOpen || menuState.isDetailActive || isNavOverlayVisible) {
                        down = FocusRequester.Cancel; left = FocusRequester.Cancel; right =
                            FocusRequester.Cancel
                    }
                },
            isSearchBarVisible = menuState.isSearchBarVisible,
            searchQuery = searchQuery,
            activeSearchQuery = activeSearchQuery,
            currentDisplayTitle = currentDisplayTitle,
            searchHistory = searchHistory,
            hasHistory = searchHistory.isNotEmpty(),
            isListView = isListView,
            searchCloseButtonFocusRequester = focuses.searchCloseButton,
            searchInputFocusRequester = focuses.searchInput,
            innerTextFieldFocusRequester = focuses.innerTextField,
            historyListFocusRequester = focuses.historyList,
            firstItemFocusRequester = topBarDownRequester,
            backButtonFocusRequester = focuses.backButton,
            searchOpenButtonFocusRequester = focuses.searchOpenButton,
            viewToggleButtonFocusRequester = focuses.viewToggleButton,
            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
            onExecuteSearch = executeSearch,
            onBackPress = handleBackPress,
            onSearchOpen = { menuState.isSearchBarVisible = true },
            onViewToggle = {
                val nextListView = !isListView; viewModel.updateListView(nextListView)
                menuState.isNavPaneOpen = false; ticketManager.issue(FocusTicket.LIST_TOP)
            },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { menuState.isBackButtonFocused = it }
        )
    }
}