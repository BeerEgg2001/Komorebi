@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.MaterialTheme
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RecordListScreen"

@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RecordListScreen(
    viewModel: RecordViewModel = hiltViewModel(),
    konomiIp: String,
    konomiPort: String,
    customTitle: String? = null,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onBack: () -> Unit
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
    val resetState = rememberListResetState()

    val paneTransitionState =
        remember { MutableTransitionState(false) }.apply { targetState = menuState.isPaneOpen }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (selectedCategory == RecordCategory.UNWATCHED) {
                    focuses.navPane.safeRequestFocus("RetreatToNav")
                    pagedRecordings.refresh()
                    resetState.reset(ListFocusTarget.LIST_TOP)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentDisplayTitle by remember(
        selectedCategory,
        selectedGenre,
        selectedDay,
        selectedSeriesGenre,
        customTitle
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

    val listState = key(resetState.key) { rememberLazyListState() }
    val gridState = key(resetState.key) { rememberTvLazyGridState() }
    val seriesListState = key(resetState.key) { rememberLazyListState() }

    val isListFirstItemReady by remember(
        selectedCategory,
        isListView,
        pagedRecordings.itemCount,
        groupedSeries
    ) {
        derivedStateOf {
            if (isListView) {
                if (selectedCategory == RecordCategory.SERIES) seriesListState.layoutInfo.visibleItemsInfo.isNotEmpty()
                else listState.layoutInfo.visibleItemsInfo.isNotEmpty()
            } else gridState.layoutInfo.visibleItemsInfo.isNotEmpty()
        }
    }

    val isCategoryImplemented = remember(selectedCategory) {
        selectedCategory == RecordCategory.ALL || selectedCategory == RecordCategory.UNWATCHED ||
                selectedCategory == RecordCategory.GENRE || selectedCategory == RecordCategory.SERIES ||
                selectedCategory == RecordCategory.CHANNEL || selectedCategory == RecordCategory.TIME
    }

    val hasContent = remember(
        selectedCategory,
        pagedRecordings.itemCount,
        groupedSeries,
        selectedSeriesGenre,
        isCategoryImplemented
    ) {
        if (!isCategoryImplemented) false
        else {
            when (selectedCategory) {
                RecordCategory.SERIES -> (if (!selectedSeriesGenre.isNullOrEmpty()) groupedSeries[selectedSeriesGenre]
                    ?: emptyList() else groupedSeries.values.flatten()).isNotEmpty()

                else -> pagedRecordings.itemCount > 0
            }
        }
    }

    val topBarDownRequester =
        remember(hasContent, isCategoryImplemented, isListView, isListFirstItemReady) {
            if (!isCategoryImplemented || !hasContent) {
                if (isListView) focuses.navPane else FocusRequester.Cancel
            } else if (isListFirstItemReady) {
                focuses.firstItem
            } else {
                if (isListView) focuses.navPane else FocusRequester.Cancel
            }
        }

    val isNavOverlayVisible = !isListView && menuState.isNavPaneOpen

    LaunchedEffect(isNavOverlayVisible) {
        if (isNavOverlayVisible) {
            delay(50); focuses.navPane.safeRequestFocus("OverlayMenuOpened")
        }
    }

    LaunchedEffect(resetState.trigger) {
        if (resetState.trigger > 0L) {
            delay(100)
            if (resetState.autoFocusTarget == ListFocusTarget.LIST_TOP && selectedCategory != RecordCategory.SERIES) {
                var waitLoadRetry = 0
                while (waitLoadRetry < 30) {
                    if (pagedRecordings.loadState.refresh is LoadState.NotLoading) break
                    delay(50); waitLoadRetry++
                }
            }
            try {
                listState.scrollToItem(0); gridState.scrollToItem(0); seriesListState.scrollToItem(0)
            } catch (e: Exception) {
            }

            if (resetState.autoFocusTarget == ListFocusTarget.LIST_TOP) {
                if (hasContent) {
                    var focusRetry = 0
                    while (focusRetry < 10) {
                        if (isListFirstItemReady) {
                            delay(50); focuses.firstItem.safeRequestFocus("FocusToListTop"); break
                        }
                        delay(50); focusRetry++
                    }
                } else {
                    if (isListView) focuses.navPane.safeRequestFocus("EmptyListFocusNav")
                }
            } else if (resetState.autoFocusTarget == ListFocusTarget.NAV_PANE) {
                delay(50); focuses.navPane.safeRequestFocus("FocusToNavPane")
            }
            resetState.autoFocusTarget = ListFocusTarget.NONE
        }
    }

    LaunchedEffect(pagedRecordings.itemCount) {
        if (menuState.isInitialFocusRequested && pagedRecordings.itemCount > 0 && !menuState.isPaneOpen && !menuState.isDetailActive) {
            delay(100)
            if (isListView && activeSearchQuery.isEmpty()) focuses.navPane.safeRequestFocus("InitialMenuFocus")
            else focuses.firstItem.safeRequestFocus("InitialContentFocus")
            menuState.isInitialFocusRequested = false
        }
    }

    LaunchedEffect(menuState.isPaneOpen, menuState.isPaneListReady) {
        if (menuState.isPaneOpen && menuState.isPaneListReady) {
            delay(50); focuses.paneFirstItem.safeRequestFocus("PaneOpenedHandover")
        }
    }

    LaunchedEffect(menuState.isPaneOpen) {
        if (!menuState.isPaneOpen && !menuState.isSelectionMade && !menuState.isSearchBarVisible && !menuState.isDetailActive && isListView) {
            delay(50); focuses.navPane.safeRequestFocus("CancelReturnToMenu")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        viewModel.searchRecordings(query)
        menuState.isSearchBarVisible = false; menuState.isDetailActive = false
        resetState.reset(ListFocusTarget.LIST_TOP)
    }

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        val isSameCategory = selectedCategory == category
        menuState.isSelectionMade = false
        if (isSameCategory) {
            when (category) {
                RecordCategory.GENRE -> menuState.isGenrePaneOpen = true
                RecordCategory.CHANNEL -> menuState.isChannelPaneOpen = true
                RecordCategory.SERIES -> menuState.isSeriesGenrePaneOpen = true
                RecordCategory.TIME -> menuState.isDayPaneOpen = true
                else -> {
                    pagedRecordings.refresh(); resetState.reset(ListFocusTarget.LIST_TOP); menuState.isNavPaneOpen =
                        false
                }
            }
        } else {
            viewModel.updateCategory(category)
            if (!category.isPaneCategory) {
                menuState.isGenrePaneOpen = false; menuState.isChannelPaneOpen =
                    false; menuState.isSeriesGenrePaneOpen = false; menuState.isDayPaneOpen = false
                resetState.reset(ListFocusTarget.LIST_TOP); menuState.isNavPaneOpen = false
            } else {
                menuState.isGenrePaneOpen = (category == RecordCategory.GENRE)
                menuState.isChannelPaneOpen = (category == RecordCategory.CHANNEL)
                menuState.isSeriesGenrePaneOpen = (category == RecordCategory.SERIES)
                menuState.isDayPaneOpen = (category == RecordCategory.TIME)
            }
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            menuState.isDetailActive -> menuState.isDetailActive = false
            menuState.isGenrePaneOpen -> {
                menuState.isGenrePaneOpen = false; focuses.navPane.safeRequestFocus()
            }

            menuState.isChannelPaneOpen -> {
                menuState.isChannelPaneOpen = false; focuses.navPane.safeRequestFocus()
            }

            menuState.isDayPaneOpen -> {
                menuState.isDayPaneOpen = false; focuses.navPane.safeRequestFocus()
            }

            menuState.isSeriesGenrePaneOpen -> {
                menuState.isSeriesGenrePaneOpen = false; focuses.navPane.safeRequestFocus()
            }

            menuState.isNavPaneOpen -> {
                menuState.isNavPaneOpen =
                    false; focuses.firstItem.safeRequestFocus("CloseNavOverlay")
            }

            menuState.isSearchBarVisible -> {
                menuState.isSearchBarVisible = false
                scope.launch {
                    delay(50)
                    if (activeSearchQuery.isNotEmpty()) focuses.firstItem.safeRequestFocus("SearchHide")
                    else if (isListView) focuses.navPane.safeRequestFocus("SearchHideMenu")
                    else focuses.firstItem.safeRequestFocus("SearchHideGrid")
                }
            }

            activeSearchQuery.isNotEmpty() -> {
                if (isListView) focuses.navPane.safeRequestFocus("BackToNavPane") else focuses.firstItem.safeRequestFocus(
                    "BackToGrid"
                )
                viewModel.clearSearch()
                resetState.reset(if (isListView && selectedCategory != RecordCategory.SERIES) ListFocusTarget.NAV_PANE else ListFocusTarget.LIST_TOP)
            }

            menuState.isBackButtonFocused -> onBack()
            isListView && !menuState.isNavFocused -> focuses.navPane.safeRequestFocus("BackToNav")
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
                    }
            ) {
                if (!isRecLoading && !hasContent) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "録画番組がありません",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.textSecondary.copy(alpha = 0.6f)
                        )
                    }
                } else {
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
                                    isLoading = isSeriesLoading,
                                    onSeriesClick = { executeSearch(it) },
                                    onOpenNavPane = {
                                        menuState.isNavPaneOpen =
                                            true; focuses.navPane.safeRequestFocus(TAG)
                                    },
                                    isListView = true,
                                    firstItemFocusRequester = focuses.firstItem,
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    isSearchBarVisible = menuState.isSearchBarVisible,
                                    onBackPress = handleBackPress,
                                    listState = seriesListState,
                                    onFirstItemBound = { }
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
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    onProgramClick = onProgramClick,
                                    onSeriesSearch = { executeSearch(it) },
                                    isDetailVisible = menuState.isDetailActive,
                                    onDetailStateChange = { menuState.isDetailActive = it },
                                    onBackPress = handleBackPress,
                                    listState = listState,
                                    fetchedProgramDetail = programDetail,
                                    onFetchDetail = { viewModel.fetchProgramDetail(it) },
                                    onClearDetail = { viewModel.clearProgramDetail() },
                                    onFirstItemBound = { }
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
                                    isLoading = isSeriesLoading,
                                    onSeriesClick = { executeSearch(it) },
                                    onOpenNavPane = {
                                        menuState.isNavPaneOpen =
                                            true; focuses.navPane.safeRequestFocus("OpenGridNav")
                                    },
                                    firstItemFocusRequester = focuses.firstItem,
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    isSearchBarVisible = menuState.isSearchBarVisible,
                                    onBackPress = handleBackPress,
                                    gridState = gridState,
                                    onFirstItemBound = { }
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
                                    searchInputFocusRequester = focuses.searchInput,
                                    backButtonFocusRequester = focuses.backButton,
                                    onProgramClick = onProgramClick,
                                    onOpenNavPane = {
                                        menuState.isNavPaneOpen =
                                            true; focuses.navPane.safeRequestFocus("OpenGridNav")
                                    },
                                    onFirstItemBound = { }
                                )
                            }
                        }
                    }
                }
            }

            // 抽出したオーバーレイコンポーネントを配置
            Box(modifier = Modifier
                .zIndex(5f)
                .fillMaxSize()) {
                RecordListOverlay(
                    menuState = menuState,
                    focuses = focuses,
                    resetState = resetState,
                    selectedCategory = selectedCategory,
                    availableGenres = availableGenres,
                    selectedGenre = selectedGenre,
                    groupedChannels = groupedChannels,
                    selectedDay = selectedDay,
                    groupedSeries = groupedSeries,
                    selectedSeriesGenre = selectedSeriesGenre,
                    isNavOverlayVisible = isNavOverlayVisible,
                    paneTransitionState = paneTransitionState,
                    hasContent = hasContent,
                    isListFirstItemReady = isListFirstItemReady,
                    onCategorySelect = handleCategorySelect,
                    onGenreSelect = { viewModel.updateGenre(it) },
                    onChannelSelect = { viewModel.updateChannel(it) },
                    onDaySelect = { viewModel.updateDay(it) },
                    onSeriesGenreSelect = { viewModel.updateSeriesGenre(it) }
                )
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
                val nextListView =
                    !isListView; viewModel.updateListView(nextListView); resetState.reset(
                ListFocusTarget.LIST_TOP
            )
            },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { menuState.isBackButtonFocused = it }
        )
    }
}