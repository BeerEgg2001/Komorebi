package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvGridItemSpan
import androidx.tv.foundation.lazy.grid.TvLazyGridState
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecordGridContent(
    pagedRecordings: LazyPagingItems<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    gridState: TvLazyGridState,
    isSearchBarVisible: Boolean,
    isKeyboardActive: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onOpenNavPane: () -> Unit,
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    LaunchedEffect(isListReady, pagedRecordings.itemCount) {
        onFirstItemBound(isListReady && pagedRecordings.itemCount > 0)
    }

    var isFastScrolling by remember { mutableStateOf(false) }
    val isScrollInProgress = gridState.isScrollInProgress

    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress) {
            delay(300) // ★判定を少し早くする
            isFastScrolling = true
        } else {
            isFastScrolling = false
        }
    }
    val isScrollingLambda = remember { { isFastScrolling } }

    val upFocusTarget =
        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester

    // ★修正: 毎回Modifierを再計算するのではなく、極力シンプルにする
    val isInitialLoading = pagedRecordings.loadState.refresh is LoadState.Loading

    if (isInitialLoading && pagedRecordings.itemCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.textPrimary)
        }
    } else {
        TvLazyVerticalGrid(
            state = gridState,
            columns = TvGridCells.Fixed(4),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                count = pagedRecordings.itemCount,
                key = pagedRecordings.itemKey { it.id },
                contentType = pagedRecordings.itemContentType { "program" }
            ) { index ->
                val program = pagedRecordings[index]
                if (program != null) {

                    // ★修正: Modifierのチェーンを最小限にし、不要なメモリ割り当てを防ぐ
                    var modifier = Modifier.aspectRatio(16f / 9f)

                    if (index == 0) {
                        modifier = modifier.focusRequester(firstItemFocusRequester)
                    }
                    if (index < 4) {
                        modifier = modifier.focusProperties { up = upFocusTarget }
                    }
                    if (index % 4 == 0) {
                        modifier = modifier.onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                onOpenNavPane()
                                true
                            } else false
                        }
                    }

                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        isScrolling = isScrollingLambda,
                        onClick = { onProgramClick(program, null) },
                        modifier = modifier
                    )
                }
            }

            if (pagedRecordings.loadState.append is LoadState.Loading) {
                item(span = { TvGridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.textPrimary)
                    }
                }
            }
        }
    }
}