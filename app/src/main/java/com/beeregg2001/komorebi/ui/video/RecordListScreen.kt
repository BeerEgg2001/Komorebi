@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.*
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

    var categoryBeforeSearch by remember { mutableStateOf<RecordCategory?>(null) }
    var isNavPaneOpen by remember { mutableStateOf(false) }
    var isGenrePaneOpen by remember { mutableStateOf(false) }
    var isSeriesGenrePaneOpen by remember { mutableStateOf(false) }
    var isChannelPaneOpen by remember { mutableStateOf(false) }
    var isDayPaneOpen by remember { mutableStateOf(false) }
    var selectedSeriesGenre by remember { mutableStateOf<String?>(null) }

    var isDetailActive by remember { mutableStateOf(false) }
    var isBackButtonFocused by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var activeSearchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isListView by remember { mutableStateOf(true) }

    var isInitialFocusRequested by remember { mutableStateOf(true) }
    var isNavFocused by remember { mutableStateOf(false) }

    val currentDisplayTitle by remember(customTitle, selectedGenre, selectedDay) {
        mutableStateOf(
            if (!selectedGenre.isNullOrEmpty()) "${selectedGenre}の録画リスト"
            else if (!selectedDay.isNullOrEmpty()) "${selectedDay}の録画リスト"
            else customTitle
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

    val focusTrapRequester = remember { FocusRequester() }
    val paneFirstItemFocusRequester = remember { FocusRequester() }
    var isListFirstItemReady by remember { mutableStateOf(false) }
    var isPaneListReady by remember { mutableStateOf(false) }

    val isPaneOpen = isGenrePaneOpen || isSeriesGenrePaneOpen || isChannelPaneOpen || isDayPaneOpen

    val isCategoryImplemented = remember(selectedCategory) {
        selectedCategory == RecordCategory.ALL ||
                selectedCategory == RecordCategory.UNWATCHED ||
                selectedCategory == RecordCategory.GENRE ||
                selectedCategory == RecordCategory.SERIES ||
                selectedCategory == RecordCategory.CHANNEL ||
                selectedCategory == RecordCategory.TIME
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
                            ?: emptyList()
                        else groupedSeries.values.flatten()
                    list.isNotEmpty()
                }

                else -> recentRecordings.isNotEmpty()
            }
        }
    }

    val topBarDownRequester =
        remember(hasContent, isListFirstItemReady, isCategoryImplemented, isListView) {
            if (!isCategoryImplemented || !hasContent) {
                if (isListView) navPaneFocusRequester else FocusRequester.Cancel
            } else if (!isListFirstItemReady) FocusRequester.Cancel
            else firstItemFocusRequester
        }

    LaunchedEffect(isPaneOpen) {
        if (!isPaneOpen && !isLoadingInitial && !isSearchBarVisible && !isDetailActive) {
            delay(50)
            if (isListView) navPaneFocusRequester.safeRequestFocus("ReturnFromPane")
        }
    }

    LaunchedEffect(isListFirstItemReady) {
        if (isListFirstItemReady && !isPaneOpen && !isDetailActive) {
            delay(100)
            if (isInitialFocusRequested) {
                if (isListView) navPaneFocusRequester.safeRequestFocus("InitialMenuFocus")
                else firstItemFocusRequester.safeRequestFocus("InitialGridFocus")
                isInitialFocusRequested = false
            } else {
                firstItemFocusRequester.safeRequestFocus("ContentReadyHandover")
            }
        }
    }

    LaunchedEffect(isPaneListReady, isPaneOpen) {
        if (isPaneListReady && isPaneOpen) {
            delay(100)
            paneFirstItemFocusRequester.safeRequestFocus("PaneReadyHandover")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        isListFirstItemReady = false
        if (activeSearchQuery.isEmpty()) categoryBeforeSearch = selectedCategory
        activeSearchQuery = query
        viewModel.updateCategory(RecordCategory.ALL)
        viewModel.searchRecordings(query)
        isSearchBarVisible = false
        focusTrapRequester.safeRequestFocus("SearchTrap")
        scope.launch {
            listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(0)
            delay(150)
            searchOpenButtonFocusRequester.safeRequestFocus("SearchDone")
        }
    }

    // ★修正: 明示的なラベル handleCategorySelect@ を付与してビルドエラーを回避
    val handleCategorySelect: (RecordCategory) -> Unit = handleCategorySelect@{ category ->
        val isSameCategory = selectedCategory == category

        if (isSameCategory) {
            when (category) {
                RecordCategory.GENRE, RecordCategory.CHANNEL, RecordCategory.SERIES, RecordCategory.TIME -> {
                    paneFirstItemFocusRequester.safeRequestFocus("SamePaneRefocus")
                }

                else -> {
                    if (hasContent) {
                        firstItemFocusRequester.safeRequestFocus("SameCategoryListJump")
                    }
                }
            }
            return@handleCategorySelect
        }

        isListFirstItemReady = false
        focusTrapRequester.safeRequestFocus("CategoryChangeTrap")
        viewModel.updateCategory(category)
        scope.launch {
            listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(0)
        }

        isGenrePaneOpen = (category == RecordCategory.GENRE)
        isChannelPaneOpen = (category == RecordCategory.CHANNEL)
        isSeriesGenrePaneOpen = (category == RecordCategory.SERIES)
        isDayPaneOpen = (category == RecordCategory.TIME)
    }

    val handleBackPress: () -> Unit = {
        when {
            isDetailActive -> isDetailActive = false
            isGenrePaneOpen -> isGenrePaneOpen = false
            isChannelPaneOpen -> isChannelPaneOpen = false
            isDayPaneOpen -> isDayPaneOpen = false
            isSeriesGenrePaneOpen -> isSeriesGenrePaneOpen = false
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
                isListFirstItemReady = false
                activeSearchQuery = ""
                viewModel.searchRecordings("")
                if (!isListView) viewModel.updateCategory(RecordCategory.ALL)
                else categoryBeforeSearch?.let {
                    viewModel.updateCategory(it); categoryBeforeSearch = null
                }
                scope.launch {
                    listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(
                    0
                )
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
        Box(modifier = Modifier
            .size(1.dp)
            .focusRequester(focusTrapRequester)
            .focusable())

        Box(modifier = Modifier
            .fillMaxSize()
            .padding(top = 88.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = contentStartPadding, end = 28.dp, bottom = 20.dp)
                    .focusProperties { canFocus = !isPaneOpen && !isDetailActive }
            ) {
                if (isListView) {
                    when (selectedCategory) {
                        RecordCategory.SERIES -> {
                            val list =
                                if (!selectedSeriesGenre.isNullOrEmpty()) groupedSeries[selectedSeriesGenre]
                                    ?: emptyList()
                                else groupedSeries.values.flatten()
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
                        recentRecordings = recentRecordings, isLoadingInitial = isLoadingInitial,
                        isLoadingMore = isLoadingMore, konomiIp = konomiIp, konomiPort = konomiPort,
                        gridState = gridState, isSearchBarVisible = isSearchBarVisible,
                        isKeyboardActive = false, firstItemFocusRequester = firstItemFocusRequester,
                        searchInputFocusRequester = searchInputFocusRequester,
                        backButtonFocusRequester = backButtonFocusRequester,
                        onProgramClick = onProgramClick, onLoadMore = { viewModel.loadNextPage() },
                        onFirstItemBound = { isListFirstItemReady = it }
                    )
                }
            }

            if (isListView) {
                AnimatedVisibility(
                    visible = isPaneOpen,
                    enter = slideInHorizontally(animationSpec = tween(350)) { -it },
                    exit = slideOutHorizontally(animationSpec = tween(350)) { -it },
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
                                genres = availableGenres, selectedGenre = selectedGenre,
                                onGenreSelect = { genre ->
                                    viewModel.updateGenre(genre); isListFirstItemReady =
                                    false; isGenrePaneOpen = false
                                },
                                onClosePane = { isGenrePaneOpen = false },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(genrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        right = FocusRequester.Cancel
                                    }
                            )
                        } else if (isChannelPaneOpen) {
                            RecordChannelPane(
                                groupedChannels = groupedChannels,
                                onChannelSelect = { channelId ->
                                    viewModel.updateChannel(channelId); isListFirstItemReady =
                                    false; isChannelPaneOpen = false
                                },
                                onClosePane = { isChannelPaneOpen = false },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(channelPaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        right = FocusRequester.Cancel
                                    }
                            )
                        } else if (isDayPaneOpen) {
                            RecordDayPane(
                                selectedDay = selectedDay,
                                onDaySelect = { day ->
                                    viewModel.updateDay(day); isListFirstItemReady =
                                    false; isDayPaneOpen = false
                                },
                                onClosePane = { isDayPaneOpen = false },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(dayPaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        right = FocusRequester.Cancel
                                    }
                            )
                        } else if (isSeriesGenrePaneOpen) {
                            RecordGenrePane(
                                genres = groupedSeries.keys.toList(),
                                selectedGenre = selectedSeriesGenre,
                                onGenreSelect = { genre ->
                                    selectedSeriesGenre = genre; isListFirstItemReady =
                                    false; isSeriesGenrePaneOpen = false
                                },
                                onClosePane = { isSeriesGenrePaneOpen = false },
                                firstItemFocusRequester = paneFirstItemFocusRequester,
                                onFirstItemBound = { isPaneListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(seriesGenrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        right = FocusRequester.Cancel
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
                        .focusProperties { canFocus = !isPaneOpen }
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
                                canFocus = !isDetailActive && !isPaneOpen
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
                    canFocus = !isDetailActive && !isPaneOpen; up = FocusRequester.Cancel
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
            onSearchQueryChange = { searchQuery = it },
            onExecuteSearch = executeSearch,
            onBackPress = handleBackPress,
            onSearchOpen = { isSearchBarVisible = true },
            onViewToggle = {
                val nextListView = !isListView
                isListView = nextListView
                if (!nextListView && activeSearchQuery.isEmpty()) handleCategorySelect(
                    RecordCategory.ALL
                )
            },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { isBackButtonFocused = it }
        )
    }
}