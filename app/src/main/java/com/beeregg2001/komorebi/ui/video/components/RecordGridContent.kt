package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
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
    gridState: LazyGridState,
    isSearchBarVisible: Boolean,
    isKeyboardActive: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onLoadMore: () -> Unit,
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    val firstVisibleIndex by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0 }
    }

    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    LaunchedEffect(isListReady, recentRecordings) {
        onFirstItemBound(isListReady && recentRecordings.isNotEmpty())
    }

    if (isLoadingInitial && recentRecordings.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.textPrimary)
        }
    } else {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items = recentRecordings, key = { _, item -> item.id }) { index, program ->

                LaunchedEffect(index) {
                    if (!isLoadingMore && index >= recentRecordings.size - 4) onLoadMore()
                }

                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort,
                    onClick = { onProgramClick(program, null) },
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .then(
                            if (index == firstVisibleIndex) Modifier.focusRequester(
                                firstItemFocusRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index < 4) {
                                up =
                                    if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                            }
                        }
                    // ★「引き算」：ここに存在した onKeyEvent(Key.Back) を削除しました。
                    // これにより、画面全体の BackHandler との競合が解消されます。
                )
            }

            if (isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
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