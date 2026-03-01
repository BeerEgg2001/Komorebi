@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
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
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RecordListScreen"

enum class ListFocusTarget { NONE, LIST_TOP, NAV_PANE }

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

    var isNavPaneOpen by remember { mutableStateOf(false) }
    var isGenrePaneOpen by remember { mutableStateOf(false) }
    var isSeriesGenrePaneOpen by remember { mutableStateOf(false) }
    var isChannelPaneOpen by remember { mutableStateOf(false) }
    var isDayPaneOpen by remember { mutableStateOf(false) }

    var isDetailActive by remember { mutableStateOf(false) }
    var isBackButtonFocused by remember { mutableStateOf(false) }
    var isSearchBarVisible by remember { mutableStateOf(false) }

    var isInitialFocusRequested by remember { mutableStateOf(true) }
    var isNavFocused by remember { mutableStateOf(false) }
    var isSelectionMade by remember { mutableStateOf(false) }

    var listResetTrigger by remember { mutableLongStateOf(0L) }
    var autoFocusTarget by remember { mutableStateOf(ListFocusTarget.NONE) }
    var listResetKey by remember { mutableStateOf(0) }

    val isPaneOpen = isGenrePaneOpen || isSeriesGenrePaneOpen || isChannelPaneOpen || isDayPaneOpen
    val paneTransitionState =
        remember { MutableTransitionState(false) }.apply { targetState = isPaneOpen }

    val navPaneFocusRequester = remember { FocusRequester() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (selectedCategory == RecordCategory.UNWATCHED) {
                    navPaneFocusRequester.safeRequestFocus("RetreatToNav")
                    pagedRecordings.refresh()
                    listResetKey++
                    listResetTrigger = System.currentTimeMillis()
                    autoFocusTarget = ListFocusTarget.LIST_TOP
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

    val scope = rememberCoroutineScope()

    val listState = key(listResetKey) { rememberLazyListState() }
    val gridState = key(listResetKey) { rememberTvLazyGridState() }
    val seriesListState = key(listResetKey) { rememberLazyListState() }

    val searchCloseButtonFocusRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    val innerTextFieldFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val searchOpenButtonFocusRequester = remember { FocusRequester() }
    val viewToggleButtonFocusRequester = remember { FocusRequester() }

    val genrePaneFocusRequester = remember { FocusRequester() }
    val channelPaneFocusRequester = remember { FocusRequester() }
    val dayPaneFocusRequester = remember { FocusRequester() }
    val seriesGenrePaneFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val paneFirstItemFocusRequester = remember { FocusRequester() }

    var isPaneListReady by remember { mutableStateOf(false) }

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
            } else {
                gridState.layoutInfo.visibleItemsInfo.isNotEmpty()
            }
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
                RecordCategory.SERIES -> {
                    val list =
                        if (!selectedSeriesGenre.isNullOrEmpty()) groupedSeries[selectedSeriesGenre]
                            ?: emptyList() else groupedSeries.values.flatten()
                    list.isNotEmpty()
                }

                else -> pagedRecordings.itemCount > 0
            }
        }
    }

    val topBarDownRequester =
        remember(hasContent, isCategoryImplemented, isListView, isListFirstItemReady) {
            if (!isCategoryImplemented || !hasContent) {
                if (isListView) navPaneFocusRequester else FocusRequester.Cancel
            } else if (isListFirstItemReady) {
                firstItemFocusRequester
            } else {
                if (isListView) navPaneFocusRequester else FocusRequester.Cancel
            }
        }

    val isNavVisible = isListView && !isSearchBarVisible && activeSearchQuery.isEmpty()
    val isNavOverlayVisible = !isListView && isNavPaneOpen

    LaunchedEffect(isNavOverlayVisible) {
        if (isNavOverlayVisible) {
            delay(50)
            navPaneFocusRequester.safeRequestFocus("OverlayMenuOpened")
        }
    }

    LaunchedEffect(listResetTrigger) {
        if (listResetTrigger > 0L) {
            delay(100)

            if (autoFocusTarget == ListFocusTarget.LIST_TOP && selectedCategory != RecordCategory.SERIES) {
                var waitLoadRetry = 0
                while (waitLoadRetry < 30) {
                    if (pagedRecordings.loadState.refresh is LoadState.NotLoading) break
                    delay(50)
                    waitLoadRetry++
                }
            }

            try {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
                seriesListState.scrollToItem(0)
            } catch (e: Exception) {
            }

            if (autoFocusTarget == ListFocusTarget.LIST_TOP) {
                if (hasContent) {
                    var focusRetry = 0
                    while (focusRetry < 10) {
                        if (isListFirstItemReady) {
                            delay(50)
                            firstItemFocusRequester.safeRequestFocus("FocusToListTop")
                            break
                        }
                        delay(50)
                        focusRetry++
                    }
                } else {
                    if (isListView) navPaneFocusRequester.safeRequestFocus("EmptyListFocusNav")
                }
            } else if (autoFocusTarget == ListFocusTarget.NAV_PANE) {
                delay(50)
                navPaneFocusRequester.safeRequestFocus("FocusToNavPane")
            }

            autoFocusTarget = ListFocusTarget.NONE
        }
    }

    LaunchedEffect(pagedRecordings.itemCount) {
        if (isInitialFocusRequested && pagedRecordings.itemCount > 0 && !isPaneOpen && !isDetailActive) {
            delay(100)
            if (isListView && activeSearchQuery.isEmpty()) navPaneFocusRequester.safeRequestFocus("InitialMenuFocus")
            else firstItemFocusRequester.safeRequestFocus("InitialContentFocus")
            isInitialFocusRequested = false
        }
    }

    LaunchedEffect(isPaneOpen, isPaneListReady) {
        if (isPaneOpen && isPaneListReady) {
            delay(50)
            paneFirstItemFocusRequester.safeRequestFocus("PaneOpenedHandover")
        }
    }

    LaunchedEffect(isPaneOpen) {
        if (!isPaneOpen && !isSelectionMade && !isSearchBarVisible && !isDetailActive) {
            delay(50)
            if (isListView) navPaneFocusRequester.safeRequestFocus("CancelReturnToMenu")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        viewModel.searchRecordings(query)
        isSearchBarVisible = false
        isDetailActive = false
        listResetKey++
        listResetTrigger = System.currentTimeMillis()
        autoFocusTarget = ListFocusTarget.LIST_TOP
    }

    val handleCategorySelect: (RecordCategory) -> Unit = handleCategorySelect@{ category ->
        val isSameCategory = selectedCategory == category
        isSelectionMade = false

        val isPaneCategory =
            category == RecordCategory.GENRE || category == RecordCategory.CHANNEL ||
                    category == RecordCategory.SERIES || category == RecordCategory.TIME

        if (isSameCategory) {
            when (category) {
                RecordCategory.GENRE -> isGenrePaneOpen = true
                RecordCategory.CHANNEL -> isChannelPaneOpen = true
                RecordCategory.SERIES -> isSeriesGenrePaneOpen = true
                RecordCategory.TIME -> isDayPaneOpen = true
                else -> {
                    pagedRecordings.refresh()
                    listResetKey++
                    listResetTrigger = System.currentTimeMillis()
                    autoFocusTarget = ListFocusTarget.LIST_TOP
                    isNavPaneOpen = false
                }
            }
            return@handleCategorySelect
        }

        viewModel.updateCategory(category)

        if (!isPaneCategory) {
            isGenrePaneOpen = false
            isChannelPaneOpen = false
            isSeriesGenrePaneOpen = false
            isDayPaneOpen = false
            listResetKey++
            listResetTrigger = System.currentTimeMillis()
            autoFocusTarget = ListFocusTarget.LIST_TOP
            isNavPaneOpen = false
        } else {
            isGenrePaneOpen = (category == RecordCategory.GENRE)
            isChannelPaneOpen = (category == RecordCategory.CHANNEL)
            isSeriesGenrePaneOpen = (category == RecordCategory.SERIES)
            isDayPaneOpen = (category == RecordCategory.TIME)
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            isDetailActive -> isDetailActive = false
            isGenrePaneOpen -> {
                isGenrePaneOpen = false; navPaneFocusRequester.safeRequestFocus()
            }

            isChannelPaneOpen -> {
                isChannelPaneOpen = false; navPaneFocusRequester.safeRequestFocus()
            }

            isDayPaneOpen -> {
                isDayPaneOpen = false; navPaneFocusRequester.safeRequestFocus()
            }

            isSeriesGenrePaneOpen -> {
                isSeriesGenrePaneOpen = false; navPaneFocusRequester.safeRequestFocus()
            }

            isNavPaneOpen -> {
                isNavPaneOpen = false
                firstItemFocusRequester.safeRequestFocus("CloseNavOverlay")
            }

            isSearchBarVisible -> {
                isSearchBarVisible = false
                scope.launch {
                    delay(50)
                    if (activeSearchQuery.isNotEmpty()) firstItemFocusRequester.safeRequestFocus("SearchHide")
                    else if (isListView) navPaneFocusRequester.safeRequestFocus("SearchHideMenu")
                    else firstItemFocusRequester.safeRequestFocus("SearchHideGrid")
                }
            }

            // ★修正: 検索結果表示中に戻るボタンが押された際の挙動
            activeSearchQuery.isNotEmpty() -> {
                // シリーズ別かつグリッド型の場合、あるいはリスト型の場合の共通処理
                if (isListView) {
                    navPaneFocusRequester.safeRequestFocus("BackToNavPane")
                } else {
                    // グリッド型でシリーズ選択中から戻る場合、シリーズ一覧に戻る
                    firstItemFocusRequester.safeRequestFocus("BackToGrid")
                }
                viewModel.clearSearch()
                listResetKey++
                listResetTrigger = System.currentTimeMillis()

                // シリーズ一覧へ戻る際、左メニューではなくコンテンツ（シリーズ一覧）へフォーカスを向ける設定
                autoFocusTarget =
                    if (isListView && selectedCategory != RecordCategory.SERIES) ListFocusTarget.NAV_PANE else ListFocusTarget.LIST_TOP
            }

            isBackButtonFocused -> onBack()
            isListView && !isNavFocused -> navPaneFocusRequester.safeRequestFocus("BackToNav")
            else -> onBack()
        }
    }

    BackHandler(enabled = !isDetailActive) { handleBackPress() }

    val contentStartPadding by animateDpAsState(
        targetValue = if (isNavVisible) 268.dp else 28.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "ContentStartPadding"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 88.dp)
                .onKeyEvent { if (!paneTransitionState.isIdle) true else false }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = contentStartPadding, end = 28.dp, bottom = 20.dp)
                    .focusProperties {
                        if (isPaneOpen || isDetailActive || isNavOverlayVisible) {
                            up = FocusRequester.Cancel; down = FocusRequester.Cancel
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                        }
                    }
            ) {
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
                                    isNavPaneOpen =
                                        true; navPaneFocusRequester.safeRequestFocus(TAG)
                                },
                                isListView = true,
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                isSearchBarVisible = isSearchBarVisible,
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
                                isSearchBarVisible = isSearchBarVisible,
                                isKeyboardActive = false,
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                onProgramClick = onProgramClick,
                                onSeriesSearch = { executeSearch(it) },
                                isDetailVisible = isDetailActive,
                                onDetailStateChange = { isDetailActive = it },
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
                                    isNavPaneOpen = true
                                    navPaneFocusRequester.safeRequestFocus("OpenGridNav")
                                },
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                isSearchBarVisible = isSearchBarVisible,
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
                                isSearchBarVisible = isSearchBarVisible,
                                isKeyboardActive = false,
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                onProgramClick = onProgramClick,
                                onOpenNavPane = {
                                    isNavPaneOpen = true
                                    navPaneFocusRequester.safeRequestFocus("OpenGridNav")
                                },
                                onFirstItemBound = { }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .zIndex(5f)
                    .fillMaxSize()
            ) {

                AnimatedVisibility(
                    visible = isNavOverlayVisible,
                    enter = fadeIn(animationSpec = tween(350)),
                    exit = fadeOut(animationSpec = tween(350))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isNavPaneOpen = false
                                firstItemFocusRequester.safeRequestFocus()
                            }
                    )
                }

                AnimatedVisibility(
                    visible = isNavOverlayVisible,
                    enter = slideInHorizontally(animationSpec = tween(350)) { -it } + fadeIn(
                        animationSpec = tween(350)
                    ),
                    exit = slideOutHorizontally(animationSpec = tween(350)) { -it } + fadeOut(
                        animationSpec = tween(350)
                    ),
                    modifier = Modifier
                        .zIndex(6f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .width(240.dp)
                            .fillMaxHeight()
                            .padding(start = 28.dp, bottom = 20.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface.copy(alpha = 0.95f))
                    ) {
                        RecordNavigationPane(
                            selectedCategory = selectedCategory,
                            onCategorySelect = handleCategorySelect,
                            isOverlay = true,
                            navPaneFocusRequester = navPaneFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusProperties {
                                    right = when {
                                        isGenrePaneOpen -> genrePaneFocusRequester
                                        isChannelPaneOpen -> channelPaneFocusRequester
                                        isDayPaneOpen -> dayPaneFocusRequester
                                        isSeriesGenrePaneOpen -> seriesGenrePaneFocusRequester
                                        else -> FocusRequester.Cancel
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (event.key == Key.DirectionRight && !isPaneOpen) {
                                            isNavPaneOpen = false
                                            firstItemFocusRequester.safeRequestFocus()
                                            return@onKeyEvent true
                                        } else if (event.key == Key.Back || event.key == Key.Escape) {
                                            isNavPaneOpen = false
                                            firstItemFocusRequester.safeRequestFocus()
                                            return@onKeyEvent true
                                        }
                                    }
                                    false
                                }
                        )
                    }
                }

                AnimatedVisibility(
                    visibleState = paneTransitionState,
                    enter = slideInHorizontally(animationSpec = tween(350)) { -it },
                    exit = slideOutHorizontally(animationSpec = tween(350)) { it } + fadeOut(
                        animationSpec = tween(200)
                    ),
                    modifier = Modifier
                        .zIndex(7f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .offset(x = 240.dp)
                            .width(220.dp)
                            .fillMaxHeight()
                            .padding(bottom = 20.dp)
                            .background(colors.surface.copy(alpha = 0.98f))
                    ) {
                        if (isGenrePaneOpen) {
                            RecordGenrePane(
                                genres = availableGenres,
                                selectedGenre = selectedGenre,
                                onGenreSelect = { genre ->
                                    isSelectionMade = true
                                    viewModel.updateGenre(genre)
                                    isGenrePaneOpen = false
                                    isNavPaneOpen = false
                                    listResetKey++
                                    listResetTrigger = System.currentTimeMillis()
                                    autoFocusTarget = ListFocusTarget.LIST_TOP
                                },
                                onClosePane = {
                                    isGenrePaneOpen =
                                        false; navPaneFocusRequester.safeRequestFocus()
                                },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(genrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester; right = FocusRequester.Cancel
                                    }
                            )
                        } else if (isChannelPaneOpen) {
                            RecordChannelPane(
                                groupedChannels = groupedChannels,
                                onChannelSelect = { channelId ->
                                    isSelectionMade = true
                                    viewModel.updateChannel(channelId)
                                    isChannelPaneOpen = false
                                    isNavPaneOpen = false
                                    listResetKey++
                                    listResetTrigger = System.currentTimeMillis()
                                    autoFocusTarget = ListFocusTarget.LIST_TOP
                                },
                                onClosePane = {
                                    isChannelPaneOpen =
                                        false; navPaneFocusRequester.safeRequestFocus()
                                },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(channelPaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester; right = FocusRequester.Cancel
                                    }
                            )
                        } else if (isDayPaneOpen) {
                            RecordDayPane(
                                selectedDay = selectedDay,
                                onDaySelect = { day ->
                                    isSelectionMade = true
                                    viewModel.updateDay(day)
                                    isDayPaneOpen = false
                                    isNavPaneOpen = false
                                    listResetKey++
                                    listResetTrigger = System.currentTimeMillis()
                                    autoFocusTarget = ListFocusTarget.LIST_TOP
                                },
                                onClosePane = {
                                    isDayPaneOpen = false; navPaneFocusRequester.safeRequestFocus()
                                },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(dayPaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester; right = FocusRequester.Cancel
                                    }
                            )
                        } else if (isSeriesGenrePaneOpen) {
                            RecordGenrePane(
                                genres = groupedSeries.keys.toList(),
                                selectedGenre = selectedSeriesGenre,
                                onGenreSelect = { genre ->
                                    isSelectionMade = true
                                    viewModel.updateSeriesGenre(genre)
                                    isSeriesGenrePaneOpen = false
                                    isNavPaneOpen = false
                                    listResetKey++
                                    listResetTrigger = System.currentTimeMillis()
                                    autoFocusTarget = ListFocusTarget.LIST_TOP
                                },
                                onClosePane = {
                                    isSeriesGenrePaneOpen =
                                        false; navPaneFocusRequester.safeRequestFocus()
                                },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(seriesGenrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester; right = FocusRequester.Cancel
                                    }
                            )
                        }
                    }
                }
            }

            if (isNavVisible) {
                Box(
                    modifier = Modifier
                        .zIndex(2f)
                        .width(240.dp)
                        .fillMaxHeight()
                        .padding(start = 28.dp, bottom = 20.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.5f))
                        .focusProperties {
                            if (isPaneOpen) {
                                up = FocusRequester.Cancel; down = FocusRequester.Cancel
                                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            }
                        }
                        .onFocusChanged { isNavFocused = it.hasFocus }
                ) {
                    RecordNavigationPane(
                        selectedCategory = selectedCategory,
                        onCategorySelect = handleCategorySelect,
                        isOverlay = false,
                        navPaneFocusRequester = navPaneFocusRequester,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties {
                                right = when {
                                    isGenrePaneOpen -> genrePaneFocusRequester
                                    isChannelPaneOpen -> channelPaneFocusRequester
                                    isDayPaneOpen -> dayPaneFocusRequester
                                    isSeriesGenrePaneOpen -> seriesGenrePaneFocusRequester
                                    hasContent && isListFirstItemReady -> firstItemFocusRequester
                                    else -> FocusRequester.Cancel
                                }
                            }
                    )
                }
            }
        }

        RecordScreenTopBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .zIndex(100f)
                .focusProperties {
                    up = FocusRequester.Cancel
                    if (isPaneOpen || isDetailActive || isNavOverlayVisible) {
                        down = FocusRequester.Cancel; left = FocusRequester.Cancel; right =
                            FocusRequester.Cancel
                    }
                },
            isSearchBarVisible = isSearchBarVisible,
            searchQuery = searchQuery,
            activeSearchQuery = activeSearchQuery,
            currentDisplayTitle = currentDisplayTitle,
            searchHistory = searchHistory,
            hasHistory = searchHistory.isNotEmpty(),
            isListView = isListView,
            searchCloseButtonFocusRequester = searchCloseButtonFocusRequester,
            searchInputFocusRequester = searchInputFocusRequester,
            innerTextFieldFocusRequester = innerTextFieldFocusRequester,
            historyListFocusRequester = historyListFocusRequester,
            firstItemFocusRequester = topBarDownRequester,
            backButtonFocusRequester = backButtonFocusRequester,
            searchOpenButtonFocusRequester = searchOpenButtonFocusRequester,
            viewToggleButtonFocusRequester = viewToggleButtonFocusRequester,
            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
            onExecuteSearch = executeSearch,
            onBackPress = handleBackPress,
            onSearchOpen = { isSearchBarVisible = true },
            onViewToggle = {
                val nextListView = !isListView
                viewModel.updateListView(nextListView)
                listResetKey++
                listResetTrigger = System.currentTimeMillis()
                autoFocusTarget = ListFocusTarget.LIST_TOP
            },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { isBackButtonFocused = it }
        )
    }
}