@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
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
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
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
    isFromVideoTabSearch: Boolean = false
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    val pagedRecordings = viewModel.pagedRecordings.collectAsLazyPagingItems()
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

    val menuState = rememberRecordListMenuState()
    val focuses = rememberRecordListFocusRequesters()
    val ticketManager = rememberFocusTicketManager()

    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }

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

    // ★修正: トップバーから下を押した時のターゲットを contentContainer (枠) ではなく firstItem (最初の項目) に変更
    val topBarDownRequester = remember(isCategoryImplemented, isListView, hasContent) {
        if (!isCategoryImplemented || !hasContent) {
            if (isListView) focuses.navPane else focuses.loadingSafeHouse
        } else {
            focuses.firstItem
        }
    }

    val isNavOverlayVisible = !isListView && menuState.isNavPaneOpen

    val currentTicket = ticketManager.currentTicket
    val issueTime = ticketManager.issueTime

    LaunchedEffect(currentTicket, issueTime, isListFirstItemReady, hasContent, isLoadingAny) {
        when (currentTicket) {
            FocusTicket.LIST_TOP -> {
                if (hasContent && isListFirstItemReady && !isLoadingAny) {
                    delay(50)
                    focuses.firstItem.safeRequestFocus("Ticket_LIST_TOP")
                    ticketManager.consume(FocusTicket.LIST_TOP)
                } else if (!hasContent && !isLoadingAny) {
                    delay(50)
                    focuses.firstItem.safeRequestFocus("Ticket_EmptyBox")
                    ticketManager.consume(FocusTicket.LIST_TOP)
                }
            }

            FocusTicket.NAV_PANE -> {
                delay(50)
                focuses.navPane.safeRequestFocus("Ticket_NAV_PANE")
                ticketManager.consume(FocusTicket.NAV_PANE)
            }

            FocusTicket.PANE -> {
                if (menuState.isPaneListReady) {
                    delay(50)
                    focuses.paneFirstItem.safeRequestFocus("Ticket_PANE")
                    ticketManager.consume(FocusTicket.PANE)
                }
            }

            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        if (menuState.isInitialFocusRequested) {
            delay(200)
            ticketManager.issue(FocusTicket.LIST_TOP)
            menuState.isInitialFocusRequested = false
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        viewModel.searchRecordings(query)
        menuState.isSearchBarVisible = false; menuState.isDetailActive = false
        ticketManager.issue(FocusTicket.LIST_TOP)
    }

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        val isSameCategory = selectedCategory == category
        menuState.isSelectionMade = false

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
        menuState.isNavPaneOpen = true
        ticketManager.issue(FocusTicket.NAV_PANE)
    }

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
                focuses.contentContainer.safeRequestFocus("CloseNavOverlay")
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
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "ContentStartPadding"
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
                    .padding(start = contentStartPadding, end = 28.dp, bottom = 20.dp)
                    .focusProperties {
                        if (menuState.isPaneOpen || menuState.isDetailActive || isNavOverlayVisible) {
                            up = FocusRequester.Cancel; down = FocusRequester.Cancel; left =
                                FocusRequester.Cancel; right = FocusRequester.Cancel
                        }
                    }) {
                if (!isLoadingAny && !hasContent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focuses.contentContainer)
                            .focusRequester(focuses.firstItem)
                            .focusable(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "録画番組がありません",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.textSecondary.copy(alpha = 0.6f)
                        )
                    }
                } else if (isLoadingAny && !hasContent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focuses.contentContainer)
                            .focusRequester(focuses.firstItem)
                            .focusable(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.textPrimary)
                    }
                } else {
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
                                        ticketManager = ticketManager
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
                                            val currentId = focusedProgram?.id
                                            executeSearch(keyword)
                                            ticketManager.issue(FocusTicket.TARGET_ID, currentId)
                                        },
                                        isDetailVisible = menuState.isDetailActive,
                                        onDetailStateChange = { menuState.isDetailActive = it },
                                        onBackPress = handleBackPress,
                                        listState = listState,
                                        fetchedProgramDetail = programDetail,
                                        onFetchDetail = { viewModel.fetchProgramDetail(it) },
                                        onClearDetail = { viewModel.clearProgramDetail() },
                                        ticketManager = ticketManager,
                                        onFocusedItemChanged = { focusedProgram = it }
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
                                        ticketManager = ticketManager
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
                    onSeriesGenreSelect = { viewModel.updateSeriesGenre(it) })
            }
        }

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
            onBackButtonFocusChanged = { menuState.isBackButtonFocused = it })
    }
}