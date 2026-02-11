package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvGridItemSpan
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListScreen(
    recentRecordings: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    onBack: () -> Unit // UI上の戻るボタン用（実際のBackキー処理はMainRootScreenで行う）
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }
    var isInputActive by remember { mutableStateOf(false) }

    // フィルタリングロジック
    val filteredRecordings = remember(searchQuery, recentRecordings) {
        if (searchQuery.isBlank()) recentRecordings
        else recentRecordings.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val gridState = rememberTvLazyGridState()

    // フォーカス制御用
    val searchFocusRequester = remember { FocusRequester() }
    val searchButtonFocusRequester = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }

    // 無限スクロール判定
    val shouldLoadMore = remember {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 4
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && !isLoadingMore && searchQuery.isBlank()) {
            onLoadMore()
        }
    }

    // 検索バーが開いたらフォーカス
    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    // 検索モード中はここだけのBackHandlerを有効にし、検索を閉じる動作を優先させる
    // 検索モードでない場合はこのBackHandlerは無効になり、MainRootScreenのBackHandlerが作動する
    BackHandler(enabled = isSearchBarVisible) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            isSearchBarVisible = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        // --- ヘッダーエリア ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 戻るボタン
            IconButton(
                onClick = {
                    if (isSearchBarVisible) {
                        isSearchBarVisible = false
                        searchQuery = ""
                    } else {
                        onBack()
                    }
                },
                modifier = Modifier.focusProperties {
                    right = if (isSearchBarVisible) searchFocusRequester else searchButtonFocusRequester
                }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "戻る", tint = Color.White)
            }

            Spacer(Modifier.width(16.dp))

            if (isSearchBarVisible) {
                // --- 検索バー表示モード ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchQuery.isEmpty() && !isInputActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = Color.White.copy(0.5f))
                            Spacer(Modifier.width(8.dp))
                            Text("タイトルで検索...", color = Color.White.copy(0.5f))
                        }
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onDone = {
                            isInputActive = false
                            gridFocusRequester.requestFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 40.dp)
                            .focusRequester(searchFocusRequester)
                            .focusProperties { down = gridFocusRequester }
                            .onFocusChanged { isInputActive = it.isFocused }
                    )

                    // 閉じる/クリアボタン
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotEmpty()) {
                                searchQuery = ""
                            } else {
                                isSearchBarVisible = false
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = Color.White.copy(0.7f))
                    }
                }
            } else {
                // --- タイトルと検索ボタン ---
                Text(
                    text = "録画一覧",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { isSearchBarVisible = true },
                    modifier = Modifier.focusRequester(searchButtonFocusRequester)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "検索", tint = Color.White)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // --- リストエリア ---
        if (filteredRecordings.isEmpty() && searchQuery.isNotBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("「$searchQuery」に一致する録画は見つかりませんでした", color = Color.Gray)
            }
        } else {
            TvLazyVerticalGrid(
                state = gridState,
                columns = TvGridCells.Fixed(4),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(gridFocusRequester)
            ) {
                items(filteredRecordings) { program ->
                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier.aspectRatio(16f / 9f)
                    )
                }

                if (isLoadingMore && searchQuery.isBlank()) {
                    item(span = { TvGridItemSpan(4) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
    }
}