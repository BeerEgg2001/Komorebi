@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.util.EpgUtils
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
    val groupedSeries by viewModel.groupedSeries.collectAsState()
    val isSeriesLoading by viewModel.isSeriesLoading.collectAsState()

    // ★修正: 検索を開始する前のカテゴリを記憶する状態
    var categoryBeforeSearch by remember { mutableStateOf<RecordCategory?>(null) }

    var isNavPaneOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeSearchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isKeyboardActive by remember { mutableStateOf(false) }
    var isBackButtonFocused by remember { mutableStateOf(false) }
    var isListView by remember { mutableStateOf(true) }
    var currentDisplayTitle by remember(customTitle) { mutableStateOf(customTitle) }

    val scope = rememberCoroutineScope()
    val limitedHistory = remember(searchHistory) { searchHistory.take(5) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()

    val searchInputFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val navPaneFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(150)
            searchInputFocusRequester.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(isNavPaneOpen) {
        if (isNavPaneOpen && !isListView) {
            delay(150)
            navPaneFocusRequester.safeRequestFocus("NavPaneOverlay")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        isKeyboardActive = false

        // ★修正: 検索がまだ実行されていない（新規検索）場合、現在のカテゴリを保存
        if (activeSearchQuery.isEmpty()) {
            categoryBeforeSearch = selectedCategory
        }

        activeSearchQuery = query
        currentDisplayTitle = null

        // 検索時は全件（ALL）カテゴリとして扱う
        viewModel.updateCategory(RecordCategory.ALL)
        viewModel.searchRecordings(query)

        keyboardController?.hide()
        isSearchBarVisible = false
        scope.launch {
            delay(150)
            backButtonFocusRequester.safeRequestFocus(TAG)
        }
    }

    val handleCategorySelect: (RecordCategory) -> Unit = { category ->
        Log.i(TAG, "Category Action: UI Select -> ${category.name}")
        viewModel.updateCategory(category)
        if (!isListView) isNavPaneOpen = false
    }

    val handleBackPress: () -> Unit = {
        when {
            isNavPaneOpen -> isNavPaneOpen = false
            isSearchBarVisible -> {
                isSearchBarVisible = false; searchQuery = ""
            }

            activeSearchQuery.isNotEmpty() -> {
                // ★修正: 検索解除時に記憶していたカテゴリを復元
                activeSearchQuery = ""
                viewModel.searchRecordings("")

                categoryBeforeSearch?.let {
                    viewModel.updateCategory(it)
                    categoryBeforeSearch = null // 復元後はリセット
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
                hasHistory = limitedHistory.isNotEmpty(),
                isListView = isListView,
                searchInputFocusRequester = searchInputFocusRequester,
                backButtonFocusRequester = backButtonFocusRequester,
                onSearchQueryChange = { searchQuery = it },
                onExecuteSearch = executeSearch,
                onBackPress = handleBackPress,
                onSearchOpen = { isSearchBarVisible = true },
                onViewToggle = { isListView = !isListView },
                onKeyboardActiveClick = { isKeyboardActive = true },
                onBackButtonFocusChanged = { isBackButtonFocused = it },
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

                Box(modifier = Modifier
                    .width(navPaneWidth)
                    .fillMaxHeight()
                    .clipToBounds()) {
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

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = if (shouldShowNavPane) 16.dp else 28.dp,
                            end = 28.dp,
                            bottom = 20.dp
                        )
                ) {
                    when {
                        selectedCategory == RecordCategory.SERIES -> {
                            RecordSeriesContent(
                                groupedSeries = groupedSeries,
                                isLoading = isSeriesLoading,
                                onSeriesClick = { keyword -> executeSearch(keyword) },
                                onOpenNavPane = { isNavPaneOpen = true }, // ★追加
                                isListView = isListView,                  // ★追加
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
                                isKeyboardActive = isKeyboardActive,
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
                                isKeyboardActive = isKeyboardActive,
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

        if (isSearchBarVisible && limitedHistory.isNotEmpty()) {
            RecordSearchHistoryDropdown(
                limitedHistory = limitedHistory,
                historyListFocusRequester = remember { FocusRequester() },
                searchInputFocusRequester = searchInputFocusRequester,
                firstItemFocusRequester = firstItemFocusRequester,
                onExecuteSearch = executeSearch,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 68.dp, start = 92.dp, end = 92.dp)
            )
        }

        if (!isListView && !isNavPaneOpen && activeSearchQuery.isEmpty() ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

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
                )
            }
        }
    }
}