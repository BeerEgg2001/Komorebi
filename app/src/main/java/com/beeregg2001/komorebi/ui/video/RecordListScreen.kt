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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.media3.common.util.Log
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
    var isListFirstItemReady by remember { mutableStateOf(false) }

    val isCategoryImplemented = remember(selectedCategory) {
        selectedCategory == RecordCategory.ALL ||
                selectedCategory == RecordCategory.UNWATCHED ||
                selectedCategory == RecordCategory.GENRE ||
                selectedCategory == RecordCategory.SERIES
    }

    val hasContent =
        remember(
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
                        val currentSeriesList = if (!selectedSeriesGenre.isNullOrEmpty()) {
                            groupedSeries[selectedSeriesGenre] ?: emptyList()
                        } else {
                            groupedSeries.values.flatten()
                        }
                        currentSeriesList.isNotEmpty()
                    }

                    else -> recentRecordings.isNotEmpty()
                }
            }
        }

    val topBarDownRequester = remember(hasContent, isListFirstItemReady, isCategoryImplemented) {
        if (!isCategoryImplemented || !hasContent) navPaneFocusRequester
        else if (!isListFirstItemReady) FocusRequester.Cancel
        else firstItemFocusRequester
    }

    LaunchedEffect(isListView, selectedCategory, activeSearchQuery) {
        isListFirstItemReady = false
    }

    LaunchedEffect(isLoadingInitial) {
        if (!isLoadingInitial && recentRecordings.isNotEmpty()) {
            if (selectedCategory == RecordCategory.ALL || selectedCategory == RecordCategory.UNWATCHED) {
                delay(300)
                firstItemFocusRequester.safeRequestFocus(TAG)
            }
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        if (activeSearchQuery.isEmpty()) {
            categoryBeforeSearch = selectedCategory
        }
        val isChanging = activeSearchQuery != query
        activeSearchQuery = query
        viewModel.updateCategory(RecordCategory.ALL)
        viewModel.searchRecordings(query)

        if (isChanging) {
            scope.launch {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
                seriesGridState.scrollToItem(0)
            }
        }

        firstItemFocusRequester.safeRequestFocus(TAG)

        isSearchBarVisible = false
        isGenrePaneOpen = false
        isSeriesGenrePaneOpen = false
        isDetailActive = false

        scope.launch { delay(150); firstItemFocusRequester.safeRequestFocus(TAG) }
    }

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        val isChanging = selectedCategory != category
        viewModel.updateCategory(category)

        if (isChanging) {
            scope.launch {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
                seriesGridState.scrollToItem(0)
            }
        }

        when (category) {
            RecordCategory.GENRE -> {
                isGenrePaneOpen = true
                isSeriesGenrePaneOpen = false
            }

            RecordCategory.SERIES -> {
                isSeriesGenrePaneOpen = true
                isGenrePaneOpen = false
            }

            else -> {
                firstItemFocusRequester.safeRequestFocus(TAG)
                isGenrePaneOpen = false
                isSeriesGenrePaneOpen = false
                if (!isListView) isNavPaneOpen = false
                scope.launch { delay(150); firstItemFocusRequester.safeRequestFocus(TAG) }
            }
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            isDetailActive -> {
                firstItemFocusRequester.safeRequestFocus(TAG)
                isDetailActive = false
            }

            isGenrePaneOpen -> {
                navPaneFocusRequester.safeRequestFocus(TAG)
                isGenrePaneOpen = false
            }

            isSeriesGenrePaneOpen -> {
                navPaneFocusRequester.safeRequestFocus(TAG)
                isSeriesGenrePaneOpen = false
            }

            isNavPaneOpen -> {
                firstItemFocusRequester.safeRequestFocus(TAG)
                isNavPaneOpen = false
            }

            isSearchBarVisible -> {
                firstItemFocusRequester.safeRequestFocus(TAG)
                isSearchBarVisible = false
                searchQuery = ""
            }

            activeSearchQuery.isNotEmpty() -> {
                firstItemFocusRequester.safeRequestFocus(TAG)
                activeSearchQuery = ""
                viewModel.searchRecordings("")
                categoryBeforeSearch?.let {
                    viewModel.updateCategory(it); categoryBeforeSearch = null
                }
                scope.launch {
                    listState.scrollToItem(0)
                    gridState.scrollToItem(0)
                    seriesGridState.scrollToItem(0)
                }
                scope.launch { delay(150); firstItemFocusRequester.safeRequestFocus(TAG) }
            }

            selectedCategory == RecordCategory.SERIES && !isSeriesGenrePaneOpen -> {
                isSeriesGenrePaneOpen = true
            }

            selectedCategory == RecordCategory.GENRE && !isGenrePaneOpen -> {
                isGenrePaneOpen = true
            }

            else -> {
                onBack()
            }
        }
    }

    BackHandler(enabled = !isDetailActive) { handleBackPress() }

    // Boxに変更してTopBarを「上に載せる」構造にする
    Box(modifier = Modifier.fillMaxSize()) {
        // コンテンツレイヤー
        Column(modifier = Modifier.fillMaxSize()) {
            // TopBar用のスペースを確保
            Spacer(Modifier.height(88.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val isPaneOpen = isGenrePaneOpen || isSeriesGenrePaneOpen
                val isNavVisible = isListView && !isSearchBarVisible && activeSearchQuery.isEmpty()
                val navPaneWidth by animateDpAsState(
                    targetValue = if (isNavVisible) 200.dp else 0.dp,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "NavPaneWidth"
                )

                Box(
                    modifier = Modifier
                        .width(navPaneWidth)
                        .fillMaxHeight()
                        .zIndex(2f)
                        .clipToBounds()
                ) {
                    if (navPaneWidth > 0.dp) {
                        RecordNavigationPane(
                            selectedCategory = selectedCategory,
                            onCategorySelect = handleCategorySelect,
                            isOverlay = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp)
                                .focusRequester(navPaneFocusRequester)
                                .focusProperties {
                                    canFocus = !isDetailActive
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

                AnimatedVisibility(
                    visible = isNavVisible && isPaneOpen,
                    enter = slideInHorizontally(initialOffsetX = { -it }) + expandHorizontally(
                        expandFrom = Alignment.Start
                    ),
                    exit = slideOutHorizontally(targetOffsetX = { -it }) + shrinkHorizontally(
                        shrinkTowards = Alignment.Start
                    ),
                    modifier = Modifier.zIndex(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .padding(end = 8.dp, bottom = 20.dp)
                    ) {
                        if (isGenrePaneOpen) {
                            RecordGenrePane(
                                genres = availableGenres,
                                selectedGenre = selectedGenre,
                                onGenreSelect = { genre ->
                                    viewModel.updateGenre(genre)
                                    firstItemFocusRequester.safeRequestFocus(TAG)
                                    isGenrePaneOpen = false
                                },
                                onClosePane = {
                                    navPaneFocusRequester.safeRequestFocus(TAG)
                                    isGenrePaneOpen = false
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(genrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        right =
                                            if (!hasContent || !isListFirstItemReady) FocusRequester.Cancel else firstItemFocusRequester
                                    }
                            )
                        } else if (isSeriesGenrePaneOpen) {
                            RecordGenrePane(
                                genres = groupedSeries.keys.toList(),
                                selectedGenre = selectedSeriesGenre,
                                onGenreSelect = { genre ->
                                    selectedSeriesGenre = genre
                                    firstItemFocusRequester.safeRequestFocus(TAG)
                                    isSeriesGenrePaneOpen = false
                                },
                                onClosePane = {
                                    navPaneFocusRequester.safeRequestFocus(TAG)
                                    isSeriesGenrePaneOpen = false
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(seriesGenrePaneFocusRequester)
                                    .focusProperties {
                                        left = navPaneFocusRequester
                                        right =
                                            if (!hasContent || !isListFirstItemReady) FocusRequester.Cancel else firstItemFocusRequester
                                    }
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = if (isNavVisible && !isPaneOpen) 16.dp else 28.dp,
                            end = 28.dp,
                            bottom = 20.dp
                        )
                ) {
                    when {
                        selectedCategory == RecordCategory.SERIES -> {
                            val currentSeriesList = if (!selectedSeriesGenre.isNullOrEmpty()) {
                                groupedSeries[selectedSeriesGenre] ?: emptyList()
                            } else {
                                groupedSeries.values.flatten()
                            }
                            RecordSeriesContent(
                                seriesList = currentSeriesList,
                                isLoading = isSeriesLoading,
                                onSeriesClick = { keyword -> executeSearch(keyword) },
                                onOpenNavPane = { isNavPaneOpen = true },
                                isListView = isListView,
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                isSearchBarVisible = isSearchBarVisible,
                                onBackPress = handleBackPress,
                                gridState = seriesGridState,
                                onFirstItemBound = { isListFirstItemReady = it }
                            )
                        }

                        selectedCategory == RecordCategory.ALL ||
                                selectedCategory == RecordCategory.UNWATCHED ||
                                selectedCategory == RecordCategory.GENRE -> {
                            if (isListView) {
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
                                    onOpenNavPane = { isNavPaneOpen = true }
                                )
                            }
                        }

                        else -> {
                            // 検索バーが表示されていない時だけ「鋭意作成中」を出す
                            if (!isSearchBarVisible) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "鋭意作成中……",
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = colors.textSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = "今後のアップデートをお楽しみに！",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.textSecondary.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // TopBarレイヤー（最前面に配置）
        RecordScreenTopBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .zIndex(100f) // コンテンツより手前に描画
                .focusProperties {
                    canFocus = !isDetailActive && !isGenrePaneOpen && !isSeriesGenrePaneOpen
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
            onViewToggle = { isListView = !isListView },
            onKeyboardActiveClick = { },
            onBackButtonFocusChanged = { isBackButtonFocused = it }
        )

        // オーバーレイナビゲーション（グリッド表示用）
        if (!isListView) {
            AnimatedVisibility(
                visible = isNavPaneOpen,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it }
            ) {
                RecordNavigationPane(
                    selectedCategory = selectedCategory,
                    onCategorySelect = handleCategorySelect,
                    isOverlay = true,
                    modifier = Modifier
                        .width(200.dp)
                        .padding(bottom = 20.dp)
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