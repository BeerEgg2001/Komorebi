package com.example.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.tv.material3.*
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.components.RecordedCard
import com.example.komorebi.ui.video.VideoPlayerScreen

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    selectedProgram: RecordedProgram?,
    externalFocusRequester: FocusRequester, // 「ビデオ」タブから下を押した時の到達点
    onProgramClick: (RecordedProgram?) -> Unit
) {
    // TV専用の TvLazyColumn を使用。
    // modifier から focusRequester を削除し、子要素に任せる
    Box(modifier = Modifier.fillMaxSize()) {
        TvLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(externalFocusRequester)
                .focusProperties {
                    exit = { FocusRequester.Default }
                },
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // 1. 新着録画（ここが最初の行）
            item {
                RecordedSection(
                    title = "新着の録画",
                    items = recentRecordings,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onProgramClick = onProgramClick,
                    // 最初のセクションの最初のアイテムにだけ、親から引き継いだRequesterを渡す
                    firstItemFocusRequester = externalFocusRequester
                )
            }

            // 2. 視聴履歴
            if (watchHistory.isNotEmpty()) {
                item {
                    RecordedSection(
                        title = "視聴履歴",
                        items = watchHistory,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onProgramClick = onProgramClick,
                        firstItemFocusRequester = null // 2行目以降は自動フォーカスに任せる
                    )
                }
            }
        }
    }

    if (selectedProgram != null) {
        VideoPlayerScreen(
            program = selectedProgram,
            konomiIp = konomiIp, konomiPort = konomiPort,
            onBackPressed = { onProgramClick(null) }
        )
    }
}

@Composable
fun RecordedSection(
    title: String,
    items: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    isPlaceholder: Boolean = false,
    firstItemFocusRequester: FocusRequester? = null
) {
    Column(modifier = Modifier.graphicsLayer(clip = false)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)
        )

        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer(clip = false)
        ) {
            if (isPlaceholder) {
                items(6) {
                    Box(
                        Modifier
                            .size(185.dp, 104.dp)
                            .background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
                    )
                }
            } else {
                itemsIndexed(items, key = { _, program -> program.id }) { index, program ->
                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier
                            // ★ 修正ポイント:
                            // 最初の行(Section) かつ 最初のアイテム(Index 0) の時だけRequesterを適用
                            .then(
                                if (index == 0 && firstItemFocusRequester != null) {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }
}