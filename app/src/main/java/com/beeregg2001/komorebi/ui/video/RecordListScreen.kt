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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
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

    var selectedCategory by remember { mutableStateOf(RecordCategory.ALL) }
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

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(150); searchInputFocusRequester.safeRequestFocus(TAG)
        }
    }
    LaunchedEffect(isNavPaneOpen) {
        if (isNavPaneOpen && !isListView) {
            delay(150); navPaneFocusRequester.safeRequestFocus("NavPaneOverlay")
        }
    }

    val executeSearch: (String) -> Unit = { query ->
        isKeyboardActive = false; activeSearchQuery = query; viewModel.searchRecordings(query)
        isSearchBarVisible =
            false; scope.launch { delay(150); backButtonFocusRequester.safeRequestFocus(TAG) }
    }

    val handleBackPress: () -> Unit = {
        when {
            isNavPaneOpen -> isNavPaneOpen = false
            isSearchBarVisible -> {
                isSearchBarVisible = false; searchQuery = ""
            }

            activeSearchQuery.isNotEmpty() -> {
                activeSearchQuery = ""; viewModel.searchRecordings("")
            }

            else -> onBack()
        }
    }

    BackHandler(enabled = true) { handleBackPress() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. トップバー
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
                // 他のRequesterは内部で使用
                searchCloseButtonFocusRequester = remember { FocusRequester() },
                innerTextFieldFocusRequester = remember { FocusRequester() },
                historyListFocusRequester = remember { FocusRequester() },
                firstItemFocusRequester = remember { FocusRequester() },
                searchOpenButtonFocusRequester = remember { FocusRequester() },
                viewToggleButtonFocusRequester = remember { FocusRequester() }
            )

            // 2. メインエリア
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

                // 左ナビゲーション (高さ調整済み)
                Box(modifier = Modifier
                    .width(navPaneWidth)
                    .fillMaxHeight()
                    .clipToBounds()) {
                    if (navPaneWidth > 0.dp) {
                        RecordNavigationPane(
                            selectedCategory = selectedCategory,
                            onCategorySelect = { selectedCategory = it },
                            isOverlay = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp)
                                .focusRequester(navPaneFocusRequester)
                        )
                    }
                }

                // コンテンツエリア (下部の高さを20.dpに統一)
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
                    if (isListView) {
                        RecordListContent(
                            recentRecordings = recentRecordings,
                            isLoadingInitial = isLoadingInitial,
                            isLoadingMore = isLoadingMore,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            isSearchBarVisible = isSearchBarVisible,
                            isKeyboardActive = isKeyboardActive,
                            onProgramClick = onProgramClick,
                            onSeriesSearch = { executeSearch(it) },
                            onLoadMore = { viewModel.loadNextPage() },
                            // Requester類
                            firstItemFocusRequester = remember { FocusRequester() },
                            searchInputFocusRequester = searchInputFocusRequester,
                            backButtonFocusRequester = backButtonFocusRequester
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
                            isKeyboardActive = isKeyboardActive,
                            onProgramClick = onProgramClick,
                            onLoadMore = { viewModel.loadNextPage() },
                            onOpenNavPane = { isNavPaneOpen = true },
                            firstItemFocusRequester = remember { FocusRequester() },
                            searchInputFocusRequester = searchInputFocusRequester,
                            backButtonFocusRequester = backButtonFocusRequester
                        )
                    }
                }
            }
        }

        // 検索履歴
        if (isSearchBarVisible && limitedHistory.isNotEmpty()) {
            RecordSearchHistoryDropdown(
                limitedHistory = limitedHistory,
                onExecuteSearch = executeSearch,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 68.dp, start = 92.dp, end = 92.dp),
                historyListFocusRequester = remember { FocusRequester() },
                searchInputFocusRequester = searchInputFocusRequester,
                firstItemFocusRequester = remember { FocusRequester() }
            )
        }

        // オーバーレイメニュー
        if (!isListView) {
            AnimatedVisibility(
                visible = isNavPaneOpen,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it }
            ) {
                RecordNavigationPane(
                    selectedCategory = selectedCategory,
                    onCategorySelect = { selectedCategory = it; isNavPaneOpen = false },
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