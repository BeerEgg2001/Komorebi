package com.beeregg2001.komorebi.ui.video

import android.os.Build
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.grid.*
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import kotlinx.coroutines.delay

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
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }

    val filteredRecordings = remember(searchQuery, recentRecordings) {
        if (searchQuery.isBlank()) recentRecordings
        else recentRecordings.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val gridState = rememberTvLazyGridState()
    val searchFocusRequester = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(150)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        delay(300)
        if (!isSearchBarVisible) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(horizontal = 40.dp, vertical = 20.dp)) {

        if (isSearchBarVisible) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    isSearchBarVisible = false
                    searchQuery = ""
                    runCatching { gridFocusRequester.requestFocus() }
                }) {
                    Icon(Icons.Default.ArrowBack, "閉じる", tint = Color.White)
                }

                Spacer(Modifier.width(16.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .focusRequester(searchFocusRequester)
                            .onKeyEvent {
                                if (it.key == Key.Back && it.type == KeyEventType.KeyUp) {
                                    isSearchBarVisible = false
                                    searchQuery = ""
                                    runCatching { gridFocusRequester.requestFocus() }
                                    true
                                } else if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                                    runCatching { gridFocusRequester.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            runCatching { gridFocusRequester.requestFocus() }
                        })
                    )

                    if (searchQuery.isEmpty()) {
                        Text("番組名を検索...", color = Color.Gray, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backButtonFocusRequester)
                ) {
                    Icon(Icons.Default.ArrowBack, "戻る", tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Text("録画一覧", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { isSearchBarVisible = true }) { Icon(Icons.Default.Search, "検索", tint = Color.White) }
            }
        }

        Spacer(Modifier.height(24.dp))

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
            // ★修正: itemsIndexedを使って現在の表示位置を監視し、ページネーションをトリガーする
            itemsIndexed(
                items = filteredRecordings,
                key = { _, item -> item.id }
            ) { index, program ->
                var isFocused by remember { mutableStateOf(false) }

                // 追加読み込みのトリガー判定
                // 検索中はクライアントサイドフィルタリングのため、ページネーションを行わない（またはViewModel側で検索APIのページネーション実装が必要）
                // ここでは「検索クエリが空」かつ「リストの末尾に近い」場合に次のページを読み込む
                if (searchQuery.isBlank() && !isLoadingMore && index >= filteredRecordings.size - 4) {
                    SideEffect {
                        onLoadMore()
                    }
                }

                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort, onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .onFocusChanged { isFocused = it.isFocused }
                        .border(2.dp, if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                )
            }

            // ★追加: 読み込み中のインジケーターを表示（最下部）
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
    }
}