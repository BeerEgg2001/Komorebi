package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells // ★標準のGridCellsを使用
import androidx.compose.foundation.lazy.grid.GridItemSpan // ★標準のGridItemSpanを使用
import androidx.compose.foundation.lazy.grid.LazyGridState // ★標準のLazyGridStateを使用
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid // ★標準のLazyVerticalGridを使用
import androidx.compose.foundation.lazy.grid.itemsIndexed // ★標準のitemsIndexedを使用
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import androidx.compose.ui.input.key.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecordGridContent(
    recentRecordings: List<RecordedProgram>,
    isLoadingInitial: Boolean,
    isLoadingMore: Boolean,
    konomiIp: String,
    konomiPort: String,
    gridState: LazyGridState, // ★修正: 標準のStateを受け取る
    isSearchBarVisible: Boolean,
    isKeyboardActive: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit, // ★修正: シグネチャを統一
    onLoadMore: () -> Unit,
    onOpenNavPane: () -> Unit // ★追加
) {
    val colors = KomorebiTheme.colors

    if (isLoadingInitial && recentRecordings.isEmpty()) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = colors.textPrimary) }
    } else {
        // ★修正: TvLazyVerticalGrid から標準の LazyVerticalGrid へ変更
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(4),
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
            itemsIndexed(
                items = recentRecordings,
                key = { _, item -> item.id } // 標準のLazyコンポーネントにより型推論が正常動作
            ) { index, program ->

                LaunchedEffect(index) {
                    if (!isLoadingMore && index >= recentRecordings.size - 4) {
                        onLoadMore()
                    }
                }

                RecordedCard(
                    program = program,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onProgramClick(program, null) }, // ★修正: nullを渡してレジュームを優先
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .then(
                            if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                        )
                        .focusProperties {
                            if (index < 4) {
                                up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                            }
                        }
                        .onKeyEvent { event ->
                            // ★一番左の列(4列なら index % 4 == 0)で左キーが押されたらメニュー起動
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && index % 4 == 0) {
                                onOpenNavPane()
                                true
                            } else false
                        }
                )
            }

            if (isLoadingMore) {
                // ★修正: 標準の GridItemSpan を使用
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = colors.textPrimary) }
                }
            }
        }
    }
}