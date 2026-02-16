package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    selectedProgram: RecordedProgram?,
    konomiIp: String,
    konomiPort: String,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
    onShowAllRecordings: () -> Unit = {},
    // 検索状態を操作するためのコールバック
    onSearch: (String) -> Unit = {}
) {
    val listState = rememberTvLazyListState()

    var isContentReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 画面表示時に検索キーワードを空にして、最新の録画リストを取得し直す
        onSearch("")

        yield()
        delay(300)
        isContentReady = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isContentReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.1f))
            }
        } else {
            TvLazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 20.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFirstItemRequester)
                    .onKeyEvent { event ->
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                            if (event.type == KeyEventType.KeyUp) {
                                topNavFocusRequester.requestFocus()
                                return@onKeyEvent true
                            }
                        }
                        false
                    }
            ) {
                // 1. 視聴履歴
                item {
                    if (watchHistory.isNotEmpty()) {
                        VideoSectionRow(
                            title = "視聴履歴", items = watchHistory, selectedProgramId = selectedProgram?.id,
                            konomiIp = konomiIp, konomiPort = konomiPort, onProgramClick = onProgramClick,
                            isFirstSection = true, topNavFocusRequester = topNavFocusRequester
                        )
                    } else {
                        Column(modifier = Modifier.padding(start = 32.dp)) {
                            Text("視聴履歴", style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.8f))
                            Spacer(Modifier.height(12.dp)); Text("視聴履歴はありません", color = Color.Gray)
                        }
                    }
                }

                // 2. 最近の録画
                item {
                    VideoSectionRow(
                        title = "最近の録画",
                        // ★修正: 表示を10件に制限
                        items = recentRecordings.take(10),
                        selectedProgramId = selectedProgram?.id,
                        konomiIp = konomiIp, konomiPort = konomiPort, onProgramClick = onProgramClick,
                        isFirstSection = watchHistory.isEmpty(),
                        topNavFocusRequester = if (watchHistory.isEmpty()) topNavFocusRequester else null
                    )
                }

                // 3. 「すべて表示」ボタン
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Button(
                            onClick = onShowAllRecordings,
                            scale = ButtonDefaults.scale(focusedScale = 1.05f),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White,
                                focusedContainerColor = Color.White, focusedContentColor = Color.Black
                            ),
                            modifier = Modifier.width(260.dp)
                        ) {
                            Icon(Icons.Default.List, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text("すべての録画を表示", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoSectionRow(
    title: String,
    items: List<RecordedProgram>,
    selectedProgramId: Int?,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    isFirstSection: Boolean = false,
    topNavFocusRequester: FocusRequester? = null
) {
    val watchedProgramFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedProgramId) {
        if (selectedProgramId != null && items.any { it.id == selectedProgramId }) {
            delay(150); runCatching { watchedProgramFocusRequester.requestFocus() }
        }
    }

    Column {
        if (title.isNotEmpty()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 32.dp, bottom = 12.dp))
        }

        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { _, program -> program.id }) { index, program ->
                val isSelected = program.id == selectedProgramId
                var isFocused by remember { mutableStateOf(false) }

                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort, onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .onFocusChanged { isFocused = it.isFocused }
                        .then(if (isSelected) Modifier.focusRequester(watchedProgramFocusRequester) else Modifier)
                        .focusProperties { if (isFirstSection && topNavFocusRequester != null) up = topNavFocusRequester }
                )
            }
        }
    }
}