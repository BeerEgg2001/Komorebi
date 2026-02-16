package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.grid.*
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListScreen(
    recentRecordings: List<RecordedProgram>,
    searchHistory: List<String>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeSearchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isKeyboardActive by remember { mutableStateOf(false) }
    var isBackButtonFocused by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val limitedHistory = remember(searchHistory) { searchHistory.take(5) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val gridState = rememberTvLazyGridState()

    // Focus Requesters
    val searchBoxContainerFocusRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val searchOpenButtonFocusRequester = remember { FocusRequester() }
    val closeSearchButtonFocusRequester = remember { FocusRequester() }

    var isFocusInSearchContext by remember { mutableStateOf(false) }

    // 検索実行
    val executeSearch = { query: String ->
        isKeyboardActive = false
        activeSearchQuery = query
        onSearch(query)
        keyboardController?.hide()
        isSearchBarVisible = false
        scope.launch { runCatching { backButtonFocusRequester.requestFocus() } }
    }

    val handleBackPress: () -> Unit = {
        when {
            isKeyboardActive -> {
                isKeyboardActive = false
                keyboardController?.hide()
                runCatching { searchBoxContainerFocusRequester.requestFocus() }
            }
            isSearchBarVisible || activeSearchQuery.isNotEmpty() -> {
                isSearchBarVisible = false
                searchQuery = ""
                activeSearchQuery = ""
                onSearch("")
                keyboardController?.hide()
                runCatching { backButtonFocusRequester.requestFocus() }
            }
            else -> {
                if (isBackButtonFocused) {
                    onBack()
                } else {
                    runCatching { backButtonFocusRequester.requestFocus() }
                }
            }
        }
    }

    BackHandler(enabled = true) { handleBackPress() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            runCatching { searchBoxContainerFocusRequester.requestFocus() }
        } else {
            runCatching { backButtonFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        // --- ヘッダー部分 ---
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (isSearchBarVisible) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { handleBackPress() },
                        modifier = Modifier.focusRequester(closeSearchButtonFocusRequester)
                    ) {
                        Icon(Icons.Default.ArrowBack, "閉じる", tint = Color.White)
                    }

                    Spacer(Modifier.width(16.dp))

                    Surface(
                        onClick = {
                            isKeyboardActive = true
                            scope.launch {
                                runCatching { searchInputFocusRequester.requestFocus() }
                                keyboardController?.show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .focusRequester(searchBoxContainerFocusRequester)
                            .onFocusChanged { if (it.isFocused) isFocusInSearchContext = true }
                            .focusProperties {
                                down = if (limitedHistory.isNotEmpty()) historyListFocusRequester else firstItemFocusRequester
                            },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            focusedContainerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp)),
                            focusedBorder = Border(border = androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)
                    ) {
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 16.dp).fillMaxSize()) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                enabled = isKeyboardActive,
                                modifier = Modifier.fillMaxWidth().focusRequester(searchInputFocusRequester)
                                    .onKeyEvent {
                                        if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) {
                                            executeSearch(searchQuery)
                                            true
                                        } else false
                                    },
                                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                                cursorBrush = SolidColor(Color.White),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { executeSearch(searchQuery) })
                            )
                            if (searchQuery.isEmpty()) { Text("番組名を検索...", color = Color.Gray, fontSize = 14.sp) }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { executeSearch(searchQuery) }) { Icon(Icons.Default.Search, "検索実行", tint = Color.White) }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { handleBackPress() },
                        modifier = Modifier
                            .focusRequester(backButtonFocusRequester)
                            .onFocusChanged { isBackButtonFocused = it.isFocused }
                            .focusProperties { down = firstItemFocusRequester }
                    ) {
                        Icon(Icons.Default.ArrowBack, "戻る", tint = Color.White)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = if (activeSearchQuery.isEmpty()) "録画一覧" else "「${activeSearchQuery}」の検索結果",
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { isSearchBarVisible = true },
                        modifier = Modifier.focusRequester(searchOpenButtonFocusRequester)
                            .focusProperties { down = firstItemFocusRequester }
                    ) {
                        Icon(Icons.Default.Search, "検索", tint = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- コンテンツエリア ---
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TvLazyVerticalGrid(
                state = gridState,
                columns = TvGridCells.Fixed(4),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
                    .focusProperties {
                        canFocus = !isSearchBarVisible && !isKeyboardActive
                    }
            ) {
                itemsIndexed(items = recentRecordings, key = { _, item -> item.id }) { index, program ->
                    if (!isLoadingMore && index >= recentRecordings.size - 4) { SideEffect { onLoadMore() } }
                    RecordedCard(
                        program = program, konomiIp = konomiIp, konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier.aspectRatio(16f / 9f)
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties { if (index < 4) { up = backButtonFocusRequester } }
                    )
                }

                // ★修正箇所: Gridスコープ内へ移動
                if (isLoadingMore) {
                    item(span = { TvGridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }

            // 履歴オーバーレイ
            if (isSearchBarVisible && limitedHistory.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp).background(Color(0xFF1E1E1E), RoundedCornerShape(0.dp, 0.dp, 8.dp, 8.dp)).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(0.dp, 0.dp, 8.dp, 8.dp)).align(Alignment.TopCenter)) {
                    TvLazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(limitedHistory) { index, historyItem ->
                            var isFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { searchQuery = historyItem; executeSearch(historyItem) },
                                modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused; if (it.isFocused) isFocusInSearchContext = true }
                                    .then(if (index == 0) Modifier.focusRequester(historyListFocusRequester) else Modifier)
                                    .focusProperties {
                                        if (index == 0) up = searchBoxContainerFocusRequester
                                        if (index == limitedHistory.size - 1) down = firstItemFocusRequester
                                    },
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.LightGray, focusedContentColor = Color.White),
                                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = if (isFocused) Color.White else Color.Gray)
                                    Spacer(Modifier.width(12.dp)); Text(text = historyItem, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}