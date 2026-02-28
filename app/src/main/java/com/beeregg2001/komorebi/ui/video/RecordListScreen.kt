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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
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
    val recentRecordings by viewModel.recentRecordings.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val groupedChannels by viewModel.groupedChannels.collectAsState()
    val isLoadingInitial by viewModel.isRecordingLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()

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
    // リストへのジャンプ要求を管理するトリガー
    var listJumpTrigger by remember { mutableIntStateOf(0) }

    val isPaneOpen = isGenrePaneOpen || isSeriesGenrePaneOpen || isChannelPaneOpen || isDayPaneOpen
    val paneTransitionState =
        remember { MutableTransitionState(false) }.apply { targetState = isPaneOpen }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (selectedCategory == RecordCategory.UNWATCHED) {
                    viewModel.fetchRecentRecordings(forceRefresh = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentDisplayTitle by remember(
        isListView, selectedCategory, selectedGenre, selectedDay, selectedSeriesGenre, customTitle
    ) {
        mutableStateOf(
            if (!isListView) "録画リスト"
            else {
                when (selectedCategory) {
                    RecordCategory.ALL -> customTitle ?: "録画リスト"
                    RecordCategory.UNWATCHED -> "未視聴の録画リスト"
                    RecordCategory.SERIES -> if (!selectedSeriesGenre.isNullOrEmpty()) "${selectedSeriesGenre}のシリーズ一覧" else "シリーズ一覧"
                    RecordCategory.GENRE -> if (!selectedGenre.isNullOrEmpty()) "${selectedGenre}の録画リスト" else "ジャンル別の録画リスト"
                    RecordCategory.TIME -> if (!selectedDay.isNullOrEmpty()) "${selectedDay}の録画リスト" else "曜日別の録画リスト"
                    RecordCategory.CHANNEL -> "チャンネル別の録画リスト"
                    else -> customTitle ?: "録画リスト"
                }
            }
        )
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val seriesGridState = rememberTvLazyGridState()

    val searchCloseButtonFocusRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    val innerTextFieldFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val searchOpenButtonFocusRequester = remember { FocusRequester() }
    val viewToggleButtonFocusRequester = remember { FocusRequester() }
    val navPaneFocusRequester = remember { FocusRequester() }
    val genrePaneFocusRequester = remember { FocusRequester() }
    val channelPaneFocusRequester = remember { FocusRequester() }
    val dayPaneFocusRequester = remember { FocusRequester() }
    val seriesGenrePaneFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val paneFirstItemFocusRequester = remember { FocusRequester() }

    var isListFirstItemReady by remember { mutableStateOf(false) }
    var isPaneListReady by remember { mutableStateOf(false) }

    val isCategoryImplemented = remember(selectedCategory) {
        selectedCategory == RecordCategory.ALL || selectedCategory == RecordCategory.UNWATCHED ||
                selectedCategory == RecordCategory.GENRE || selectedCategory == RecordCategory.SERIES ||
                selectedCategory == RecordCategory.CHANNEL || selectedCategory == RecordCategory.TIME
    }

    val hasContent = remember(
        selectedCategory,
        recentRecordings,
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

                else -> recentRecordings.isNotEmpty()
            }
        }
    }

    val topBarDownRequester = remember(hasContent, isCategoryImplemented, isListView) {
        if (!isCategoryImplemented || !hasContent) {
            if (isListView) navPaneFocusRequester else FocusRequester.Cancel
        } else {
            firstItemFocusRequester
        }
    }

    // --- フォーカス安定化と確実なリストジャンプ ---

    // 1. 初回フォーカス
    LaunchedEffect(isListFirstItemReady) {
        if (isInitialFocusRequested && isListFirstItemReady && !isPaneOpen && !isDetailActive) {
            delay(100)
            if (isListView && activeSearchQuery.isEmpty()) navPaneFocusRequester.safeRequestFocus("InitialMenuFocus")
            else firstItemFocusRequester.safeRequestFocus("InitialContentFocus")
            isInitialFocusRequested = false
        }
    }

    // ★ 修正 2. 確実なリストジャンプ（リトライ機構付き）
    // リストの描画遅延に対応するため、アタッチされるまで複数回トライする
    LaunchedEffect(listJumpTrigger, isLoadingInitial, hasContent) {
        if (listJumpTrigger > 0 && !isLoadingInitial) {
            if (hasContent && !isPaneOpen && !isDetailActive) {
                for (i in 1..4) { // 最大 400ms まで 100ms 間隔でリトライ
                    delay(100)
                    try {
                        firstItemFocusRequester.requestFocus() // 成功すれば例外は出ない
                        listJumpTrigger = 0
                        break
                    } catch (e: Exception) {
                        // 未アタッチなら次のループへ
                    }
                }
                listJumpTrigger = 0 // 最終的にリセット
            } else if (!hasContent) {
                listJumpTrigger = 0
            }
        }
    }

    // 3. パネルが開いた際のパネル内へのジャンプ
    LaunchedEffect(isPaneOpen, isPaneListReady) {
        if (isPaneOpen && isPaneListReady) {
            delay(50)
            paneFirstItemFocusRequester.safeRequestFocus("PaneOpenedHandover")
        }
    }

    // 4. キャンセルでパネルを閉じた際の復帰
    LaunchedEffect(isPaneOpen) {
        if (!isPaneOpen && !isSelectionMade && !isSearchBarVisible && !isDetailActive) {
            delay(50)
            if (isListView) navPaneFocusRequester.safeRequestFocus("CancelReturnToMenu")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        // ★修正: 戻るボタンへの暴走を防ぐため、存在し続けるボタンにフォーカスを避難
        navPaneFocusRequester.safeRequestFocus("Handoff Before Search")

        viewModel.searchRecordings(query)
        isSearchBarVisible = false
        isDetailActive = false
        scope.launch {
            listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(0)
            listJumpTrigger++
        }
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
                    // ★修正: 同じ通常カテゴリを選択した場合も、確実なジャンプ予約を行う
                    navPaneFocusRequester.safeRequestFocus()
                    viewModel.fetchRecentRecordings(forceRefresh = true)
                    scope.launch {
                        listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(
                        0
                    )
                        listJumpTrigger++
                    }
                }
            }
            return@handleCategorySelect
        }

        viewModel.updateCategory(category)

        if (!isPaneCategory) {
            // ★修正: 新しい通常カテゴリ選択時、戻るボタン暴走防止のフォーカス維持
            navPaneFocusRequester.safeRequestFocus("Handoff Before Jump")
            scope.launch {
                listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(0)
                listJumpTrigger++
            }
        } else {
            scope.launch {
                listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(0)
            }
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

            isNavPaneOpen -> isNavPaneOpen = false
            isSearchBarVisible -> {
                isSearchBarVisible = false
                scope.launch {
                    delay(50)
                    if (activeSearchQuery.isNotEmpty()) firstItemFocusRequester.safeRequestFocus("SearchHide")
                    else if (isListView) navPaneFocusRequester.safeRequestFocus("SearchHideMenu")
                    else firstItemFocusRequester.safeRequestFocus("SearchHideGrid")
                }
            }

            activeSearchQuery.isNotEmpty() -> {
                viewModel.clearSearch()
                scope.launch {
                    listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(
                    0
                )
                    listJumpTrigger++
                }
            }

            isListView && !isNavFocused -> navPaneFocusRequester.safeRequestFocus("BackToNav")
            else -> onBack()
        }
    }

    BackHandler(enabled = !isDetailActive) { handleBackPress() }

    val isNavVisible = isListView && !isSearchBarVisible && activeSearchQuery.isEmpty()
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
                        if (isPaneOpen || isDetailActive) {
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
                                gridState = seriesGridState,
                                onFirstItemBound = { isListFirstItemReady = it }
                            )
                        }

                        else -> {
                            RecordListContent(
                                recentRecordings = recentRecordings,
                                isLoadingInitial = isLoadingInitial,
                                isLoadingMore = isLoadingMore,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                isSearchBarVisible = isSearchBarVisible,
                                isKeyboardActive = false,
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                onProgramClick = onProgramClick,
                                onSeriesSearch = { executeSearch(it) },
                                onLoadMore = { viewModel.loadNextPage() },
                                isDetailVisible = isDetailActive,
                                onDetailStateChange = { isDetailActive = it },
                                onBackPress = handleBackPress,
                                listState = listState,
                                onFirstItemBound = { isListFirstItemReady = it }
                            )
                        }
                    }
                } else {
                    RecordGridContent(
                        recentRecordings = recentRecordings,
                        isLoadingInitial = isLoadingInitial,
                        isLoadingMore = isLoadingMore,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        gridState = gridState,
                        isSearchBarVisible = isSearchBarVisible,
                        isKeyboardActive = false,
                        firstItemFocusRequester = firstItemFocusRequester,
                        searchInputFocusRequester = searchInputFocusRequester,
                        backButtonFocusRequester = backButtonFocusRequester,
                        onProgramClick = onProgramClick,
                        onLoadMore = { viewModel.loadNextPage() },
                        onFirstItemBound = { isListFirstItemReady = it }
                    )
                }
            }

            if (isListView) {
                AnimatedVisibility(
                    visibleState = paneTransitionState,
                    enter = slideInHorizontally(animationSpec = tween(350)) { -it },
                    exit = slideOutHorizontally(animationSpec = tween(350)) { it } + fadeOut(
                        animationSpec = tween(200)
                    ),
                    modifier = Modifier
                        .zIndex(1f)
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
                                    // ★修正: パネルが閉じる前にナビゲーションへフォーカスを避難させ、戻るボタンへの暴走を防ぐ
                                    navPaneFocusRequester.safeRequestFocus("Handoff")
                                    viewModel.updateGenre(genre)
                                    isGenrePaneOpen = false
                                    scope.launch { listState.scrollToItem(0); listJumpTrigger++ }
                                },
                                onClosePane = { isGenrePaneOpen = false },
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
                                    navPaneFocusRequester.safeRequestFocus("Handoff")
                                    viewModel.updateChannel(channelId)
                                    isChannelPaneOpen = false
                                    scope.launch { listState.scrollToItem(0); listJumpTrigger++ }
                                },
                                onClosePane = { isChannelPaneOpen = false },
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
                                    navPaneFocusRequester.safeRequestFocus("Handoff")
                                    viewModel.updateDay(day)
                                    isDayPaneOpen = false
                                    scope.launch { listState.scrollToItem(0); listJumpTrigger++ }
                                },
                                onClosePane = { isDayPaneOpen = false },
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
                                    navPaneFocusRequester.safeRequestFocus("Handoff")
                                    viewModel.updateSeriesGenre(genre)
                                    isSeriesGenrePaneOpen = false
                                    scope.launch { seriesGridState.scrollToItem(0); listJumpTrigger++ }
                                },
                                onClosePane = { isSeriesGenrePaneOpen = false },
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(navPaneFocusRequester)
                            .focusProperties {
                                right = when {
                                    isGenrePaneOpen -> genrePaneFocusRequester
                                    isChannelPaneOpen -> channelPaneFocusRequester
                                    isDayPaneOpen -> dayPaneFocusRequester
                                    isSeriesGenrePaneOpen -> seriesGenrePaneFocusRequester
                                    hasContent -> firstItemFocusRequester
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
                    if (isPaneOpen || isDetailActive) {
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
                if (!nextListView && activeSearchQuery.isEmpty()) handleCategorySelect(
                    RecordCategory.ALL
                )
            },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { isBackButtonFocused = it }
        )
    }
}