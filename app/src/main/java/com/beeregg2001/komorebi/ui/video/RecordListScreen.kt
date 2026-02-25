@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.components.RecordGridContent
import com.beeregg2001.komorebi.ui.video.components.RecordScreenTopBar
import com.beeregg2001.komorebi.ui.video.components.RecordSearchHistoryDropdown
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
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
    onProgramClick: (RecordedProgram) -> Unit,
    onBack: () -> Unit
) {
    val recentRecordings by viewModel.recentRecordings.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val isLoadingInitial by viewModel.isRecordingLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var activeSearchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isKeyboardActive by remember { mutableStateOf(false) }
    var isBackButtonFocused by remember { mutableStateOf(false) }

    var currentDisplayTitle by remember(customTitle) { mutableStateOf(customTitle) }

    val scope = rememberCoroutineScope()
    val limitedHistory = remember(searchHistory) { searchHistory.take(5) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val gridState = rememberTvLazyGridState()

    val searchInputFocusRequester = remember { FocusRequester() }
    val innerTextFieldFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val searchOpenButtonFocusRequester = remember { FocusRequester() }
    val searchCloseButtonFocusRequester = remember { FocusRequester() }

    val executeSearch: (String) -> Unit = { query ->
        isKeyboardActive = false
        activeSearchQuery = query
        currentDisplayTitle = null
        viewModel.searchRecordings(query)
        keyboardController?.hide()
        isSearchBarVisible = false
        scope.launch {
            delay(150)
            backButtonFocusRequester.safeRequestFocus(TAG)
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            isKeyboardActive -> {
                isKeyboardActive = false
                keyboardController?.hide()
                scope.launch { delay(100); searchInputFocusRequester.safeRequestFocus(TAG) }
            }
            isSearchBarVisible -> {
                isSearchBarVisible = false
                searchQuery = ""
                scope.launch { delay(50); searchOpenButtonFocusRequester.safeRequestFocus(TAG) }
            }
            activeSearchQuery.isNotEmpty() -> {
                activeSearchQuery = ""
                viewModel.searchRecordings("")
                scope.launch { delay(50); backButtonFocusRequester.safeRequestFocus(TAG) }
            }
            else -> {
                if (isBackButtonFocused) onBack() else backButtonFocusRequester.safeRequestFocus(TAG)
            }
        }
    }

    BackHandler(enabled = true) { handleBackPress() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(150); searchInputFocusRequester.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(Unit) {
        delay(300)
        if (!isSearchBarVisible && activeSearchQuery.isEmpty()) {
            backButtonFocusRequester.safeRequestFocus(TAG)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        RecordScreenTopBar(
            isSearchBarVisible = isSearchBarVisible,
            searchQuery = searchQuery,
            activeSearchQuery = activeSearchQuery,
            currentDisplayTitle = currentDisplayTitle,
            hasHistory = limitedHistory.isNotEmpty(),
            searchCloseButtonFocusRequester = searchCloseButtonFocusRequester,
            searchInputFocusRequester = searchInputFocusRequester,
            innerTextFieldFocusRequester = innerTextFieldFocusRequester,
            historyListFocusRequester = historyListFocusRequester,
            firstItemFocusRequester = firstItemFocusRequester,
            backButtonFocusRequester = backButtonFocusRequester,
            searchOpenButtonFocusRequester = searchOpenButtonFocusRequester,
            onSearchQueryChange = { searchQuery = it },
            onExecuteSearch = executeSearch,
            onBackPress = handleBackPress,
            onSearchOpen = { isSearchBarVisible = true },
            onKeyboardActiveClick = { isKeyboardActive = true },
            onBackButtonFocusChanged = { isBackButtonFocused = it }
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // 将来的にここでリストとグリッドを切り替えることができます
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
                onLoadMore = { viewModel.loadNextPage() }
            )

            if (isSearchBarVisible && limitedHistory.isNotEmpty()) {
                RecordSearchHistoryDropdown(
                    limitedHistory = limitedHistory,
                    historyListFocusRequester = historyListFocusRequester,
                    searchInputFocusRequester = searchInputFocusRequester,
                    firstItemFocusRequester = firstItemFocusRequester,
                    onExecuteSearch = executeSearch,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}