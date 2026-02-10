package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
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
    onShowAllRecordings: () -> Unit = {}
) {
    val listState = rememberTvLazyListState()

    TvLazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentFirstItemRequester)
    ) {
        // 1. 視聴履歴セクション
        item {
            if (watchHistory.isNotEmpty()) {
                VideoSectionRow(
                    title = "視聴履歴",
                    items = watchHistory,
                    selectedProgramId = selectedProgram?.id,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onProgramClick = onProgramClick,
                    isFirstSection = true,
                    topNavFocusRequester = topNavFocusRequester,
                    firstItemFocusRequester = null
                )
            } else {
                Column(modifier = Modifier.padding(start = 32.dp)) {
                    Text("視聴履歴", style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(12.dp))
                    Text("視聴履歴はありません", color = Color.Gray)
                }
            }
        }

        // 2. 最近の録画セクション
        item {
            Column {
                Text(
                    text = "最近の録画",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)
                )

                VideoSectionRow(
                    title = "", // タイトルは上で表示済み
                    items = recentRecordings,
                    selectedProgramId = selectedProgram?.id,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onProgramClick = onProgramClick,
                    isFirstSection = watchHistory.isEmpty(),
                    topNavFocusRequester = if (watchHistory.isEmpty()) topNavFocusRequester else null,
                    firstItemFocusRequester = null
                )
            }
        }

        // 3. 「すべて表示」ボタン (リストの下に配置)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onShowAllRecordings, // MainRootScreenで処理
                    scale = ButtonDefaults.scale(focusedScale = 1.05f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    modifier = Modifier.width(240.dp)
                ) {
                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("すべての録画を表示", style = MaterialTheme.typography.labelLarge)
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
    topNavFocusRequester: FocusRequester? = null,
    firstItemFocusRequester: FocusRequester? = null,
    isPlaceholder: Boolean = false
) {
    val watchedProgramFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedProgramId) {
        if (selectedProgramId != null && items.any { it.id == selectedProgramId }) {
            delay(150)
            runCatching { watchedProgramFocusRequester.requestFocus() }
        }
    }

    Column {
        if (title.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)
            )
        }

        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer(clip = false)
        ) {
            if (isPlaceholder) {
                items(6) {
                    Box(Modifier.size(185.dp, 104.dp).background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium))
                }
            } else {
                itemsIndexed(items, key = { _, program -> program.id }) { index, program ->
                    val isSelected = program.id == selectedProgramId

                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier
                            .then(if (index == 0 && isFirstSection && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .then(if (isSelected) Modifier.focusRequester(watchedProgramFocusRequester) else Modifier)
                            .focusProperties {
                                if (isFirstSection && topNavFocusRequester != null) up = topNavFocusRequester
                            }
                    )
                }
            }
        }
    }
}