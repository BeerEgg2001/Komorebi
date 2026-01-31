// ui/home/VideoTabContent.kt
package com.example.komorebi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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

@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    externalFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram) -> Unit
) {
    // ★ 最初のカードにフォーカスを当てるためのリクエスター
    val firstItemFocusRequester = remember { FocusRequester() }

    // 縦スクロール（新着、履歴、マイリストの各行を並べる）
    // ★ データがロードされたら最初のアイテムにフォーカスを移す
    LaunchedEffect(recentRecordings) {
        if (recentRecordings.isNotEmpty()) {
            firstItemFocusRequester.requestFocus()
        }
    }

    TvLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // 新着録画セクション
        item {
            RecordedSection(
                title = "新着の録画",
                items = recentRecordings,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                onProgramClick = onProgramClick,
                firstItemFocusRequester = externalFocusRequester
            )
        }

        // 視聴履歴セクション（ダミー）
        item {
            RecordedSection(
                title = "視聴履歴",
                items = watchHistory, // ViewModelから取得した履歴リスト
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                onProgramClick = onProgramClick
            )
        }
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
    firstItemFocusRequester: FocusRequester? = null // 追加
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.graphicsLayer(clip = false) // 拡大時の見切れ防止
        ) {
            if (isPlaceholder) {
                items(5) {
                    Box(
                        Modifier
                            .size(240.dp, 135.dp)
                            .background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium) // 角を少し丸く、色は薄く
                    )
                }
            }else {
                // ★ items ではなく itemsIndexed を使用する
                itemsIndexed(items) { index, program ->
                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        // ★ ここで index を判定に使う
                        modifier = if (index == 0 && firstItemFocusRequester != null) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}