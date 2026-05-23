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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.KeyboardArrowLeft
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordSortOrder
import com.beeregg2001.komorebi.viewmodel.RecordSortType
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SeriesInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RecordListScreen(
    viewModel: RecordViewModel = hiltViewModel(),
    konomiIp: String,
    konomiPort: String,
    customTitle: String? = null,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onBack: () -> Unit,
    isFromVideoTabSearch: Boolean = false,
    isReturningFromPlayer: Boolean = false,
    lastPlayedProgramId: Int? = null,
    onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    autoReserveKeywords: List<String> = emptyList(),
    onAutoReserveClick: (RecordedProgram) -> Unit = {},
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val syncProgress by viewModel.syncProgress.collectAsState()

    if (syncProgress.isInitialSyncPhase) {
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
        return
    }

    val pagedRecordings = viewModel.pagedRecordings.collectAsLazyPagingItems()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val groupedChannels by viewModel.groupedChannels.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()
    val availableGenres by viewModel.availableGenres.collectAsState()
    val groupedSeries by viewModel.groupedSeries.collectAsState()

    // ★ 追加: ViewModelからソート状態を取得
    val sortType by viewModel.sortType.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    val isSeriesLoading by viewModel.isSeriesLoading.collectAsState()
    val activeSearchQuery by viewModel.activeSearchQuery.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isListView by viewModel.isListView.collectAsState()
    val selectedSeriesGenre by viewModel.selectedSeriesGenre.collectAsState()
    val programDetail by viewModel.programDetail.collectAsState()
    val isRecLoading by viewModel.isRecordingLoading.collectAsState()

    val menuState = rememberRecordListMenuState()
    val focuses = rememberRecordListFocusRequesters()
    val ticketManager = rememberFocusTicketManager()

    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var focusedSeries by remember { mutableStateOf<SeriesInfo?>(null) }
    var savedFocusProgramId by remember { mutableStateOf<Int?>(null) }

    var lastKnownFocusedId by remember { mutableStateOf<Int?>(null) }

    val paneTransitionState =
        remember { MutableTransitionState(false) }.apply { targetState = menuState.isPaneOpen }

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

    // ★ 修正: ソート状態の変更時にリストを再構築させるため stateKey に追加
    val stateKey = remember(
        selectedCategory,
        selectedGenre,
        selectedDay,
        selectedSeriesGenre,
        activeSearchQuery,
        sortType,
        sortOrder,
        ticketManager.forceResetTick
    ) {
        "${selectedCategory.name}_${selectedGenre}_${selectedDay}_${selectedSeriesGenre}_${activeSearchQuery}_${sortType}_${sortOrder}_${ticketManager.forceResetTick}"
    }

    val listState = remember(stateKey) { LazyListState() }
    val gridState = remember(stateKey) { LazyGridState() }
    val seriesListState = remember(stateKey) { LazyListState() }

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

    var listContentDownRequester by remember { mutableStateOf<FocusRequester>(focuses.firstItem) }

    val topBarDownRequester = if (isListView && hasContent && isCategoryImplemented) {
        listContentDownRequester
    } else if (!isCategoryImplemented || !hasContent) {
        if (isListView) focuses.navPane else focuses.searchOpenButton
    } else {
        focuses.contentContainer
    }

    val isNavOverlayVisible = !isListView && menuState.isNavPaneOpen

    val currentTicket = ticketManager.currentTicket
    val issueTime = ticketManager.issueTime

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            delay(400)

            if (lastKnownFocusedId != null) {
                ticketManager.issue(FocusTicket.TARGET_ID, lastKnownFocusedId)
            } else {
                ticketManager.issue(FocusTicket.LIST_TOP)
            }
            onAiReturnConsumed()
        }
    }

    LaunchedEffect(currentTicket, issueTime, isListFirstItemReady, hasContent, isLoadingAny) {
        when (currentTicket) {
            FocusTicket.LIST_TOP -> {
                if (hasContent && isListFirstItemReady && !isLoadingAny) {
                    delay(150)
                    focuses.firstItem.safeRequestFocusWithRetry("Ticket_LIST_TOP")
                    ticketManager.consume(FocusTicket.LIST_TOP)
                } else if (!hasContent && !isLoadingAny) {
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

    LaunchedEffect(Unit) {
        if (menuState.isInitialFocusRequested) {
            delay(200)
            if (isReturningFromPlayer && lastPlayedProgramId != null) {
                ticketManager.issue(FocusTicket.TARGET_ID, lastPlayedProgramId)
                onReturnFocusConsumed()
            } else {
                ticketManager.issue(FocusTicket.LIST_TOP)
            }
            menuState.isInitialFocusRequested = false
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        savedFocusProgramId = null
        focusedProgram = null
        focusedSeries = null
        lastKnownFocusedId = null
        viewModel.searchRecordings(query)
        menuState.isSearchBarVisible = false; menuState.isDetailActive = false
        ticketManager.issue(FocusTicket.LIST_TOP)
    }

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        val isSameCategory = selectedCategory == category
        menuState.isSelectionMade = false
        savedFocusProgramId = null
        focusedProgram = null
        focusedSeries = null
        lastKnownFocusedId = null

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

    val handleOpenNavPane = {
        if (selectedCategory == RecordCategory.SERIES) {
            savedFocusProgramId = focusedSeries?.representativeVideoId
        } else {
            savedFocusProgramId = focusedProgram?.id
        }
        menuState.isNavPaneOpen = true
        ticketManager.issue(FocusTicket.NAV_PANE)
    }

    val onRightKeyFromNav = {
        if (savedFocusProgramId != null) {
            ticketManager.issue(FocusTicket.TARGET_ID, savedFocusProgramId)
            savedFocusProgramId = null
        } else {
            ticketManager.issue(FocusTicket.LIST_TOP)
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            menuState.isDetailActive -> menuState.isDetailActive = false
            // ★ 追加: ソートメニューを閉じる処理
            menuState.isSortMenuOpen -> {
                menuState.isSortMenuOpen = false
                focuses.sortButton.safeRequestFocus("CloseSort")
            }

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
                menuState.isNavPaneOpen = false; onRightKeyFromNav()
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

    val contentStartPadding by animateDpAsState(
        targetValue = if (isListView && !menuState.isSearchBarVisible && activeSearchQuery.isEmpty()) 268.dp else 28.dp,
        animationSpec = tween(350, easing = FastOutSlowInEasing), label = "ContentStartPadding"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focuses.loadingSafeHouse)
                .focusable()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 88.dp)
                .onKeyEvent { if (!paneTransitionState.isIdle) true else false }) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = contentStartPadding, bottom = 20.dp)
                    .focusProperties {
                        // ★ 修正: ソートメニューが開いている時は背後のフォーカスをブロック
                        if (menuState.isPaneOpen || menuState.isDetailActive || isNavOverlayVisible || menuState.isSortMenuOpen) {
                            up = FocusRequester.Cancel; down = FocusRequester.Cancel; left =
                                FocusRequester.Cancel; right = FocusRequester.Cancel
                        }
                    }) {

                key(stateKey, isListView) {
                    if (isListView) {
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
                                    onFocusedSeriesChanged = {
                                        focusedSeries = it
                                        if (it != null) lastKnownFocusedId =
                                            it.representativeVideoId
                                    },
                                    onTopBarDownRequesterChanged = {
                                        listContentDownRequester = it
                                    }
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
                                        executeSearch(keyword); focusedProgram?.id?.let {
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
                                    onFocusedItemChanged = {
                                        focusedProgram = it
                                        if (it != null) lastKnownFocusedId = it.id
                                    },
                                    onOpenNavPane = handleOpenNavPane,
                                    onTopBarDownRequesterChanged = {
                                        listContentDownRequester = it
                                    },
                                    timeFormat = timeFormat,
                                    autoReserveKeywords = autoReserveKeywords,
                                    onAutoReserveClick = onAutoReserveClick
                                )
                            }
                        }
                    } else {
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
                                    onFocusedSeriesChanged = {
                                        focusedSeries = it
                                        if (it != null) lastKnownFocusedId =
                                            it.representativeVideoId
                                    }
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
                                    onFocusedItemChanged = {
                                        focusedProgram = it
                                        if (it != null) lastKnownFocusedId = it.id
                                    }
                                )
                            }
                        }
                    }
                }

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

            // ★ 追加: 新しいソートメニューのオーバーレイ
            RecordSortMenuOverlay(
                isOpen = menuState.isSortMenuOpen,
                currentType = sortType,
                currentOrder = sortOrder,
                onClose = { handleBackPress() },
                onSelect = { newType, newOrder ->
                    viewModel.setSort(newType, newOrder)
                    pagedRecordings.refresh() // ソート条件が変わったらPagingをリフレッシュ
                    menuState.isSortMenuOpen = false
                    focuses.sortButton.safeRequestFocus("CloseSort")
                }
            )
        }

        RecordScreenTopBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .zIndex(100f)
                .focusProperties {
                    up = FocusRequester.Cancel
                    // ★ 修正: ソートメニューが開いている時はTopBarのフォーカス移動をブロック
                    if (menuState.isPaneOpen || menuState.isDetailActive || isNavOverlayVisible || menuState.isSortMenuOpen) {
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
            selectedCategory = selectedCategory, // ★ 追加
            searchCloseButtonFocusRequester = focuses.searchCloseButton,
            searchInputFocusRequester = focuses.searchInput,
            innerTextFieldFocusRequester = focuses.innerTextField,
            historyListFocusRequester = focuses.historyList,
            firstItemFocusRequester = topBarDownRequester,
            backButtonFocusRequester = focuses.backButton,
            searchOpenButtonFocusRequester = focuses.searchOpenButton,
            viewToggleButtonFocusRequester = focuses.viewToggleButton,
            sortButtonFocusRequester = focuses.sortButton, // ★ 追加
            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
            onExecuteSearch = executeSearch,
            onBackPress = handleBackPress,
            onSearchOpen = { menuState.isSearchBarVisible = true },
            onViewToggle = {
                val nextListView = !isListView; viewModel.updateListView(nextListView)
                menuState.isNavPaneOpen = false; ticketManager.issue(FocusTicket.LIST_TOP)
            },
            onSortOpen = { menuState.isSortMenuOpen = true }, // ★ 追加
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { menuState.isBackButtonFocused = it }
        )
    }
}

// =========================================================================================
// ★ 追加: 新しいソートメニューのオーバーレイと専用アイテムコンポーネント
// =========================================================================================
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.RecordSortMenuOverlay(
    isOpen: Boolean,
    currentType: RecordSortType,
    currentOrder: RecordSortOrder,
    onClose: () -> Unit,
    onSelect: (RecordSortType, RecordSortOrder) -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            delay(150); try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(10f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .zIndex(11f)
    ) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.Back || it.key == Key.Escape)) {
                        onClose(); true
                    } else false
                },
            colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.98f)),
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.4f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(24.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 28.dp, top = 24.dp, end = 12.dp, bottom = 24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "並び替え",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
                    )

                    val options = listOf(
                        Triple(RecordSortType.DATE, RecordSortOrder.DESC, "録画日時 (新しい順)"),
                        Triple(RecordSortType.DATE, RecordSortOrder.ASC, "録画日時 (古い順)"),
                        Triple(RecordSortType.TITLE, RecordSortOrder.ASC, "名前 (A→Z)"),
                        Triple(RecordSortType.TITLE, RecordSortOrder.DESC, "名前 (Z→A)"),
                        Triple(RecordSortType.DURATION, RecordSortOrder.DESC, "録画時間 (長い順)"),
                        Triple(RecordSortType.DURATION, RecordSortOrder.ASC, "録画時間 (短い順)")
                    )

                    var isFirstItem = true
                    options.forEach { (type, order, label) ->
                        val isSelected = currentType == type && currentOrder == order
                        val reqModifier =
                            if (isSelected || (isFirstItem && !options.any { it.first == currentType && it.second == currentOrder })) {
                                isFirstItem = false
                                Modifier.focusRequester(focusRequester)
                            } else Modifier

                        SortMenuItem(
                            icon = if (isSelected) Icons.Default.Check else Icons.Default.Circle,
                            iconTint = if (isSelected) colors.accent else Color.Transparent,
                            label = label,
                            onClick = { onSelect(type, order) },
                            modifier = reqModifier
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SortMenuItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val contentColor = if (isFocused) inverseColor else colors.textPrimary

    val finalIconTint = when {
        iconTint == Color.Transparent -> Color.Transparent
        isFocused -> inverseColor
        iconTint != Color.Unspecified -> iconTint
        else -> colors.textPrimary
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = finalIconTint
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                maxLines = 1,
                color = contentColor,
                modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier)
            )
        }
    }
}