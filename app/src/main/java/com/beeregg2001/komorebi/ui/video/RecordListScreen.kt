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
    val isLoadingInitial by viewModel.isRecordingLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()

    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val availableGenres by viewModel.availableGenres.collectAsState()
    val groupedSeries by viewModel.groupedSeries.collectAsState()
    val isSeriesLoading by viewModel.isSeriesLoading.collectAsState()

    var categoryBeforeSearch by remember { mutableStateOf<RecordCategory?>(null) }
    var isNavPaneOpen by remember { mutableStateOf(false) }
    var isGenrePaneOpen by remember { mutableStateOf(false) }
    var isSeriesGenrePaneOpen by remember { mutableStateOf(false) }
    var selectedSeriesGenre by remember { mutableStateOf<String?>(null) }

    var isDetailActive by remember { mutableStateOf(false) }
    var isBackButtonFocused by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var activeSearchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isListView by remember { mutableStateOf(true) }

    val currentDisplayTitle by remember(customTitle, selectedGenre) {
        mutableStateOf(if (!selectedGenre.isNullOrEmpty()) "${selectedGenre}の録画リスト" else customTitle)
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val seriesGridState = rememberTvLazyGridState()

    // FocusRequesters
    val searchCloseButtonFocusRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    val innerTextFieldFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val searchOpenButtonFocusRequester = remember { FocusRequester() }
    val viewToggleButtonFocusRequester = remember { FocusRequester() }
    val navPaneFocusRequester = remember { FocusRequester() }
    val genrePaneFocusRequester = remember { FocusRequester() }
    val seriesGenrePaneFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }

    val focusTrapRequester = remember { FocusRequester() }
    val genreFirstItemFocusRequester = remember { FocusRequester() }
    var isListFirstItemReady by remember { mutableStateOf(false) }
    var isGenreListReady by remember { mutableStateOf(false) }

    val isPaneOpen = isGenrePaneOpen || isSeriesGenrePaneOpen

    val isCategoryImplemented = remember(selectedCategory) {
        selectedCategory == RecordCategory.ALL ||
                selectedCategory == RecordCategory.UNWATCHED ||
                selectedCategory == RecordCategory.GENRE ||
                selectedCategory == RecordCategory.SERIES
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

    // --- フォーカス管理ロジック ---

    LaunchedEffect(isPaneOpen) {
        if (!isPaneOpen && !isLoadingInitial && !isSearchBarVisible && !isDetailActive) {
            delay(50)
            if (isListView) navPaneFocusRequester.safeRequestFocus("ReturnFromGenrePane")
        }
    }

    LaunchedEffect(isListFirstItemReady) {
        if (isListFirstItemReady && !isPaneOpen && !isDetailActive) {
            delay(100)
            firstItemFocusRequester.safeRequestFocus("ListReadyHandover")
        }
    }

    LaunchedEffect(isGenreListReady, isPaneOpen) {
        if (isGenreListReady && isPaneOpen) {
            delay(100)
            genreFirstItemFocusRequester.safeRequestFocus("GenreReadyHandover")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
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

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        isListFirstItemReady = false
        focusTrapRequester.safeRequestFocus("CategoryChangeTrap")
        viewModel.updateCategory(category)
        scope.launch {
            listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(0)
        }
        when (category) {
            RecordCategory.GENRE -> {
                isGenrePaneOpen = true; isSeriesGenrePaneOpen = false
            }

            RecordCategory.SERIES -> {
                isSeriesGenrePaneOpen = true; isGenrePaneOpen = false
            }

            else -> {
                isGenrePaneOpen = false; isSeriesGenrePaneOpen = false
            }
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            isDetailActive -> isDetailActive = false
            isGenrePaneOpen -> isGenrePaneOpen = false
            isSeriesGenrePaneOpen -> isSeriesGenrePaneOpen = false
            isNavPaneOpen -> {
                firstItemFocusRequester.safeRequestFocus(TAG); isNavPaneOpen = false
            }

            isSearchBarVisible -> {
                firstItemFocusRequester.safeRequestFocus(TAG); isSearchBarVisible = false
            }

            activeSearchQuery.isNotEmpty() -> {
                activeSearchQuery = ""
                viewModel.searchRecordings("")
                if (!isListView) {
                    viewModel.updateCategory(RecordCategory.ALL)
                } else {
                    categoryBeforeSearch?.let {
                        viewModel.updateCategory(it); categoryBeforeSearch = null
                    }
                }
                scope.launch {
                    listState.scrollToItem(0); gridState.scrollToItem(0); seriesGridState.scrollToItem(
                    0
                )
                    delay(150); firstItemFocusRequester.safeRequestFocus(TAG)
                }
            }

            else -> onBack()
        }
    }

    BackHandler(enabled = !isDetailActive) { handleBackPress() }

    val isNavVisible = isListView && !isSearchBarVisible && activeSearchQuery.isEmpty()
    val contentStartPadding by animateDpAsState(
        targetValue = if (isNavVisible) 228.dp else 28.dp,
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

            // 1. メインコンテンツ領域（Layer 1: 背面）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = contentStartPadding, end = 28.dp, bottom = 20.dp)
                    .focusProperties { canFocus = !isPaneOpen && !isDetailActive }
            ) {
                if (isListView) {
                    when {
                        selectedCategory == RecordCategory.SERIES -> {
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

            // 2. ジャンル選択オーバーレイ（Layer 2: 中間 / 背面からスライド）
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
                            .offset(x = 200.dp)
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
                                firstItemFocusRequester = genreFirstItemFocusRequester,
                                onFirstItemBound = { isGenreListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(genrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        // ★修正：右キー操作を無効化し、フォーカスをオーバーレイ内に閉じ込める
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
                                firstItemFocusRequester = genreFirstItemFocusRequester,
                                onFirstItemBound = { isGenreListReady = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(seriesGenrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        // ★修正：右キー操作を無効化
                                        right = FocusRequester.Cancel
                                    }
                            )
                        }
                    }
                }
            }

            // 3. 左固定ナビゲーション（Layer 3: 最前面）
            if (isNavVisible) {
                Box(
                    modifier = Modifier
                        .zIndex(2f)
                        .width(200.dp)
                        .fillMaxHeight()
                        .padding(start = 28.dp, bottom = 20.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.5f))
                        .focusProperties { canFocus = !isPaneOpen }
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
                                    isSeriesGenrePaneOpen -> seriesGenrePaneFocusRequester
                                    !hasContent || !isListFirstItemReady -> FocusRequester.Cancel
                                    else -> firstItemFocusRequester
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
                .focusProperties { canFocus = !isDetailActive && !isPaneOpen },
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
                if (!nextListView && activeSearchQuery.isEmpty()) {
                    handleCategorySelect(RecordCategory.ALL)
                }
            },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { isBackButtonFocused = it }
        )

        if (!isListView) {
            AnimatedVisibility(
                visible = isNavPaneOpen,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.zIndex(150f)
            ) {
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .padding(start = 28.dp, bottom = 20.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.95f))
                ) {
                    RecordNavigationPane(
                        selectedCategory = selectedCategory,
                        onCategorySelect = handleCategorySelect,
                        isOverlay = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(navPaneFocusRequester)
                            .focusProperties {
                                right =
                                    if (!hasContent || !isListFirstItemReady) FocusRequester.Cancel else firstItemFocusRequester
                            }
                    )
                }
            }
        }
    }
}