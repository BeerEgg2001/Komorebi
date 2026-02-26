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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.components.*
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RecordListScreen"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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

    var searchQuery by remember { mutableStateOf("") }
    var activeSearchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isListView by remember { mutableStateOf(true) }

    val currentDisplayTitle by remember(customTitle, selectedGenre) {
        mutableStateOf(if (!selectedGenre.isNullOrEmpty()) "${selectedGenre}の録画リスト" else customTitle)
    }

    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val searchInputFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val navPaneFocusRequester = remember { FocusRequester() }
    val genrePaneFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isNavPaneOpen) {
        if (isNavPaneOpen && !isListView) {
            delay(150)
            navPaneFocusRequester.safeRequestFocus("NavPaneOverlay")
        }
    }

    LaunchedEffect(isGenrePaneOpen) {
        if (isGenrePaneOpen) {
            delay(150)
            genrePaneFocusRequester.safeRequestFocus("GenrePane")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        if (activeSearchQuery.isEmpty()) {
            categoryBeforeSearch = selectedCategory
        }
        activeSearchQuery = query
        viewModel.updateCategory(RecordCategory.ALL)
        viewModel.searchRecordings(query)
        isSearchBarVisible = false
        isGenrePaneOpen = false
        scope.launch { delay(150); backButtonFocusRequester.safeRequestFocus(TAG) }
    }

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        viewModel.updateCategory(category)
        if (category == RecordCategory.GENRE) {
            isGenrePaneOpen = true
        } else {
            isGenrePaneOpen = false
            if (!isListView) isNavPaneOpen = false
            scope.launch { delay(200); firstItemFocusRequester.safeRequestFocus(TAG) }
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            isGenrePaneOpen -> {
                isGenrePaneOpen = false
                scope.launch { delay(100); navPaneFocusRequester.safeRequestFocus(TAG) }
            }

            isNavPaneOpen -> isNavPaneOpen = false
            isSearchBarVisible -> {
                isSearchBarVisible = false; searchQuery = ""
            }

            activeSearchQuery.isNotEmpty() -> {
                activeSearchQuery = ""
                viewModel.searchRecordings("")
                categoryBeforeSearch?.let {
                    viewModel.updateCategory(it); categoryBeforeSearch = null
                }
            }

            else -> onBack()
        }
    }

    BackHandler(enabled = true) { handleBackPress() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            RecordScreenTopBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                isSearchBarVisible = isSearchBarVisible,
                searchQuery = searchQuery,
                activeSearchQuery = activeSearchQuery,
                currentDisplayTitle = currentDisplayTitle,
                hasHistory = searchHistory.isNotEmpty(),
                isListView = isListView,
                searchInputFocusRequester = searchInputFocusRequester,
                backButtonFocusRequester = backButtonFocusRequester,
                onSearchQueryChange = { searchQuery = it },
                onExecuteSearch = executeSearch,
                onBackPress = handleBackPress,
                onSearchOpen = { isSearchBarVisible = true },
                onViewToggle = { isListView = !isListView },
                onKeyboardActiveClick = { },
                onBackButtonFocusChanged = { },
                searchCloseButtonFocusRequester = remember { FocusRequester() },
                innerTextFieldFocusRequester = remember { FocusRequester() },
                historyListFocusRequester = remember { FocusRequester() },
                firstItemFocusRequester = firstItemFocusRequester,
                searchOpenButtonFocusRequester = remember { FocusRequester() },
                viewToggleButtonFocusRequester = remember { FocusRequester() }
            )

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()) {
                val shouldShowNavPane =
                    isListView && !isSearchBarVisible && activeSearchQuery.isEmpty()
                val navPaneWidth by animateDpAsState(
                    targetValue = if (shouldShowNavPane) 200.dp else 0.dp,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "NavPaneWidth"
                )

                // 1. 左ナビゲーションパネル (zIndexを高くして前面に)
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
                        )
                    }
                }

                // 2. ジャンルサブメニュー (AnimatedVisibilityをRowScope直下へ)
                // zIndex(1f)で左メニューの裏に配置
                AnimatedVisibility(
                    visible = shouldShowNavPane && isGenrePaneOpen,
                    enter = slideInHorizontally(
                        initialOffsetX = { -it }, // 左からスライド
                        animationSpec = tween(350, easing = FastOutSlowInEasing)
                    ) + expandHorizontally(
                        expandFrom = Alignment.Start, // 右側へ広がることでリストを押し出す
                        animationSpec = tween(350, easing = FastOutSlowInEasing)
                    ),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = tween(350, easing = FastOutSlowInEasing)
                    ) + shrinkHorizontally(
                        shrinkTowards = Alignment.Start, // 左側へ縮むことでリストを引き戻す
                        animationSpec = tween(350, easing = FastOutSlowInEasing)
                    ),
                    modifier = Modifier.zIndex(1f)
                ) {
                    // 幅を固定したBoxで包み、内容を表示
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .padding(end = 8.dp, bottom = 20.dp)
                    ) {
                        RecordGenrePane(
                            genres = availableGenres,
                            selectedGenre = selectedGenre,
                            onGenreSelect = { genre ->
                                viewModel.updateGenre(genre)
                                isGenrePaneOpen = false
                                scope.launch {
                                    delay(200); firstItemFocusRequester.safeRequestFocus(
                                    TAG
                                )
                                }
                            },
                            onClosePane = {
                                isGenrePaneOpen = false
                                scope.launch {
                                    delay(100); navPaneFocusRequester.safeRequestFocus(
                                    TAG
                                )
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(genrePaneFocusRequester)
                        )
                    }
                }

                // 3. コンテンツ表示エリア (weight(1f)により、隣のパネル開閉に合わせてリサイズされる)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = if (shouldShowNavPane && !isGenrePaneOpen) 16.dp else 28.dp,
                            end = 28.dp,
                            bottom = 20.dp
                        )
                        .focusProperties { canFocus = !isGenrePaneOpen }
                ) {
                    when {
                        selectedCategory == RecordCategory.SERIES -> {
                            RecordSeriesContent(
                                groupedSeries = groupedSeries, isLoading = isSeriesLoading,
                                onSeriesClick = { keyword -> executeSearch(keyword) },
                                onOpenNavPane = { isNavPaneOpen = true }, isListView = isListView,
                                firstItemFocusRequester = firstItemFocusRequester,
                                searchInputFocusRequester = searchInputFocusRequester,
                                backButtonFocusRequester = backButtonFocusRequester,
                                isSearchBarVisible = isSearchBarVisible
                            )
                        }

                        isListView -> {
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
                                onLoadMore = { viewModel.loadNextPage() }
                            )
                        }

                        else -> {
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
                }
            }
        }

        // カード型表示用のオーバーレイ（既存ロジック）
        if (!isListView) {
            AnimatedVisibility(
                visible = isNavPaneOpen,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it }
            ) {
                // ...カード型用のメニュー表示 (RecordNavigationPane) ...
                RecordNavigationPane(
                    selectedCategory = selectedCategory,
                    onCategorySelect = handleCategorySelect,
                    isOverlay = true,
                    modifier = Modifier
                        .width(200.dp)
                        .padding(bottom = 20.dp)
                        .focusRequester(navPaneFocusRequester)
                )
            }
        }
    }
}