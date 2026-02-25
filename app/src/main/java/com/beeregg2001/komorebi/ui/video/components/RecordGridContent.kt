package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.*
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
import androidx.tv.foundation.lazy.grid.*
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
    gridState: TvLazyGridState,
    isSearchBarVisible: Boolean,
    isKeyboardActive: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit
) {
    val colors = KomorebiTheme.colors

    if (isLoadingInitial && recentRecordings.isEmpty()) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = colors.textPrimary) }
    } else {
        TvLazyVerticalGrid(
            state = gridState,
            columns = TvGridCells.Fixed(4),
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
                key = { _, item -> item.id },
                contentType = { _, _ -> "RecordedCard" }
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
                    onClick = { onProgramClick(program) },
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
                )
            }
            if (isLoadingMore) {
                item(span = { TvGridItemSpan(maxLineSpan) }, contentType = "LoadingIndicator") {
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