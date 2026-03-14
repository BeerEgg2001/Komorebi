package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyGridState
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
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
    contentContainerFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onOpenNavPane: () -> Unit,
    ticketManager: FocusTicketManager,
    onFirstItemBound: (Boolean) -> Unit = {},
    onFocusedItemChanged: (RecordedProgram?) -> Unit = {}
) {
    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    LaunchedEffect(isListReady, pagedRecordings.itemCount) {
        onFirstItemBound(isListReady && pagedRecordings.itemCount > 0)
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId
            val index = (0 until pagedRecordings.itemCount).firstOrNull {
                pagedRecordings.peek(it)?.id == targetId
            }
            if (index != null) {
                gridState.scrollToItem(maxOf(0, index - 4))
            } else {
                gridState.scrollToItem(0)
            }
        } else if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            gridState.scrollToItem(0)
        }
    }

    var isFastScrolling by remember { mutableStateOf(false) }
    val isScrollInProgress = gridState.isScrollInProgress

    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress) {
            delay(300); isFastScrolling = true
        } else {
            isFastScrolling = false
        }
    }
    val isScrollingLambda = remember { { isFastScrolling } }

    val upFocusTarget =
        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester

    TvLazyVerticalGrid(
        state = gridState,
        columns = TvGridCells.Fixed(4),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentContainerFocusRequester)
            // ★修正: focusGroup を削除し、focusRestorer を追加。
            .focusRestorer { firstItemFocusRequester }
    ) {
        items(
            count = pagedRecordings.itemCount,
            key = pagedRecordings.itemKey { it.id },
            contentType = pagedRecordings.itemContentType { "program" }
        ) { index ->
            val program = pagedRecordings[index]
            if (program != null) {
                var modifier = Modifier.aspectRatio(16f / 9f)
                val specificRequester = remember { FocusRequester() }

                if (index == 0) modifier = modifier.focusRequester(firstItemFocusRequester)
                modifier = modifier.focusRequester(specificRequester)

                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    val ticket = ticketManager.currentTicket
                    if (ticket == FocusTicket.TARGET_ID && program.id == ticketManager.targetProgramId) {
                        specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID")
                        ticketManager.consume(FocusTicket.TARGET_ID)
                    }
                }

                if (index < 4) modifier = modifier.focusProperties { up = upFocusTarget }
                if (index % 4 == 0) {
                    modifier = modifier
                        .focusProperties { left = FocusRequester.Cancel }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                if (!isScrollInProgress) {
                                    onOpenNavPane()
                                }
                                return@onKeyEvent true
                            }
                            false
                        }
                }

                RecordedCard(
                    program = program,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    isScrolling = isScrollingLambda,
                    onClick = { onProgramClick(program, null) },
                    modifier = modifier
                        .onFocusChanged {
                            if (it.isFocused) {
                                onFocusedItemChanged(program)
                            }
                        }
                )
            }
        }
    }
}