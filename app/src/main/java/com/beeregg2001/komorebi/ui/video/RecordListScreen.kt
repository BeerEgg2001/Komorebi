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

    // ★修正: 検索バーが表示されたら少し待ってからフォーカスを移動（クラッシュ対策）
    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(100) // UI構築待ち
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    // 画面が表示されたらグリッドにフォーカスを当てる
    LaunchedEffect(Unit) {
        delay(50)
        if (!isSearchBarVisible) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(horizontal = 40.dp, vertical = 20.dp)) {

        // ★修正: ヘッダー部分を検索モードと通常モードで切り替え
        if (isSearchBarVisible) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 検索モード時の戻るボタン（検索を閉じる）
                IconButton(onClick = {
                    isSearchBarVisible = false
                    searchQuery = ""
                    runCatching { gridFocusRequester.requestFocus() }
                }) {
                    Icon(Icons.Default.ArrowBack, "閉じる", tint = Color.White)
                }

                Spacer(Modifier.width(16.dp))

                // 検索入力フィールド
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
                            .focusRequester(searchFocusRequester) // ★重要: これがないとクラッシュする
                            .onKeyEvent {
                                if (it.key == Key.Back && it.type == KeyEventType.KeyUp) {
                                    // 戻るキーで検索終了
                                    isSearchBarVisible = false
                                    searchQuery = ""
                                    runCatching { gridFocusRequester.requestFocus() }
                                    true
                                } else if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                                    // 下キーでグリッドへ移動
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
                            // 検索実行（決定キー）時はグリッドへ移動して結果を確認しやすくする
                            runCatching { gridFocusRequester.requestFocus() }
                        })
                    )

                    if (searchQuery.isEmpty()) {
                        Text("番組名を検索...", color = Color.Gray, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        } else {
            // 通常ヘッダー
            Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "戻る", tint = Color.White) }
                Spacer(Modifier.width(16.dp))
                Text("録画一覧", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { isSearchBarVisible = true }) { Icon(Icons.Default.Search, "検索", tint = Color.White) }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Grid
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
            items(
                items = filteredRecordings,
                key = { it.id },
                contentType = { "RecordedCard" }
            ) { program ->
                var isFocused by remember { mutableStateOf(false) }

                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort, onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .onFocusChanged { isFocused = it.isFocused }
                        .border(2.dp, if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                )
            }
        }
    }
}