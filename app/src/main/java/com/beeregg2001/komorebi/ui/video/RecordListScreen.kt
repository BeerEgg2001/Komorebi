@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.ExperimentalComposeUiApi
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
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RecordListScreen"

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
    val searchInputFocusRequester = remember { FocusRequester() }
    val innerTextFieldFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val searchOpenButtonFocusRequester = remember { FocusRequester() }
    val searchCloseButtonFocusRequester = remember { FocusRequester() }

    val executeSearch = { query: String ->
        isKeyboardActive = false
        activeSearchQuery = query
        onSearch(query)
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
                scope.launch {
                    delay(100)
                    searchInputFocusRequester.safeRequestFocus(TAG)
                }
            }
            isSearchBarVisible -> {
                isSearchBarVisible = false
                searchQuery = ""
                scope.launch {
                    delay(50)
                    searchOpenButtonFocusRequester.safeRequestFocus(TAG)
                }
            }
            activeSearchQuery.isNotEmpty() -> {
                activeSearchQuery = ""
                onSearch("")
                scope.launch {
                    delay(50)
                    backButtonFocusRequester.safeRequestFocus(TAG)
                }
            }
            else -> {
                if (isBackButtonFocused) onBack()
                else backButtonFocusRequester.safeRequestFocus(TAG)
            }
        }
    }

    BackHandler(enabled = true) { handleBackPress() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(150)
            // ★修正: 検索ボタンを押した後は、戻る(閉じる)ボタンではなく検索バーにフォーカスする
            searchInputFocusRequester.safeRequestFocus(TAG)
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
            .background(Color(0xFF121212))
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        // --- ヘッダー部分 ---
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (isSearchBarVisible) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { handleBackPress() },
                        modifier = Modifier.focusRequester(searchCloseButtonFocusRequester)
                    ) {
                        Icon(Icons.Default.ArrowBack, "閉じる", tint = Color.White)
                    }
                    Spacer(Modifier.width(16.dp))

                    Surface(
                        onClick = {
                            isKeyboardActive = true
                            // TextFieldにフォーカスを強制してから、キーボードを表示する
                            innerTextFieldFocusRequester.safeRequestFocus(TAG)
                            keyboardController?.show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .focusRequester(searchInputFocusRequester)
                            .onKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                    if (limitedHistory.isNotEmpty()) {
                                        historyListFocusRequester.safeRequestFocus(TAG)
                                        return@onKeyEvent true
                                    } else {
                                        firstItemFocusRequester.safeRequestFocus(TAG)
                                        return@onKeyEvent true
                                    }
                                }
                                false
                            }
                            .focusProperties {
                                left = searchCloseButtonFocusRequester
                                down = if (limitedHistory.isNotEmpty()) historyListFocusRequester else firstItemFocusRequester
                            },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            focusedContainerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))),
                            focusedBorder = Border(BorderStroke(2.dp, Color.White))
                        )
                    ) {
                        // ★修正: fillMaxSize を追加し、decorationBox を使ってテキストの垂直配置を完全に揃える
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(innerTextFieldFocusRequester),
                                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                                cursorBrush = SolidColor(Color.White),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { executeSearch(searchQuery) }),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "番組名を検索...",
                                                color = Color.Gray,
                                                fontSize = 18.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { executeSearch(searchQuery) }) {
                        Icon(Icons.Default.Search, "検索実行", tint = Color.White)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { handleBackPress() },
                        modifier = Modifier
                            .focusRequester(backButtonFocusRequester)
                            .onFocusChanged { isBackButtonFocused = it.isFocused }
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
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties {
                        if (isSearchBarVisible || isKeyboardActive) {
                            enter = { FocusRequester.Cancel }
                        }
                    }
            ) {
                itemsIndexed(items = recentRecordings, key = { _, item -> item.id }) { index, program ->
                    if (!isLoadingMore && index >= recentRecordings.size - 4) {
                        SideEffect { onLoadMore() }
                    }
                    RecordedCard(
                        program = program, konomiIp = konomiIp, konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier
                            .aspectRatio(16f / 9f)
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties {
                                if (index < 4) {
                                    up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
                            }
                    )
                }
                if (isLoadingMore) {
                    item(span = { TvGridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }

            // 履歴オーバーレイ
            if (isSearchBarVisible && limitedHistory.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 64.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .align(Alignment.TopCenter)
                ) {
                    // ★最大高さを 320.dp に拡張
                    TvLazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        itemsIndexed(limitedHistory) { index, historyItem ->
                            Surface(
                                onClick = { executeSearch(historyItem) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(historyListFocusRequester) else Modifier)
                                    .onKeyEvent {
                                        if (index == 0 && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                            searchInputFocusRequester.safeRequestFocus(TAG)
                                            return@onKeyEvent true
                                        }
                                        false
                                    }
                                    .focusProperties {
                                        if (index == limitedHistory.size - 1) down = firstItemFocusRequester
                                    },
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = Border(BorderStroke(2.dp, Color.White))
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Color.White.copy(alpha = 0.15f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.History, null, Modifier.size(18.dp), tint = Color.Gray)
                                    Spacer(Modifier.width(12.dp))
                                    Text(text = historyItem, color = Color.White, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}