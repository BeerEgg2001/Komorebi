package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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

    // 🌟 修正1: pagedRecordings.itemCount をキーに含めることで、非同期ロード後にも再評価されるようにする
    LaunchedEffect(
        ticketManager.currentTicket,
        ticketManager.issueTime,
        pagedRecordings.itemCount
    ) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId
            val index = (0 until pagedRecordings.itemCount).firstOrNull {
                pagedRecordings.peek(it)?.id == targetId
            }
            if (index != null) {
                // グリッドの場合、対象アイテムの1行（4アイテム）上が見えるようにスクロールする
                val targetRowFirstIndex = index - (index % 4)
                gridState.scrollToItem(maxOf(0, targetRowFirstIndex - 4))
            }
            // 🌟 修正2: else ブロック( scrollToItem(0) )を削除。
            // データがまだロードされていない時に強制的に一番上に戻されてしまうバグを防止します。

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
            // 🌟 修正3: focusRestorer を削除し、安全な focusGroup() に変更。
            // 画面外の FocusRequester を参照しようとして発生するクラッシュを完全に防ぎます。
            .focusGroup()
    ) {
        items(
            count = pagedRecordings.itemCount,
            key = pagedRecordings.itemKey { it.id },
            contentType = pagedRecordings.itemContentType { "program" }
        ) { index ->
            val program = pagedRecordings[index]
            if (program != null) {
                var itemModifier = Modifier.aspectRatio(16f / 9f)
                val specificRequester = remember { FocusRequester() }

                // 複数の FocusRequester を繋げる形に修正
                if (index == 0) itemModifier = itemModifier.focusRequester(firstItemFocusRequester)
                itemModifier = itemModifier.focusRequester(specificRequester)

                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    val ticket = ticketManager.currentTicket
                    if (ticket == FocusTicket.TARGET_ID && program.id == ticketManager.targetProgramId) {
                        // 🌟 修正4: スクロールやレイアウトが完了するのを少し待ってから確実にフォーカスを要求する
                        delay(100)
                        specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_Grid")
                        ticketManager.consume(FocusTicket.TARGET_ID)
                    }
                }

                if (index < 4) itemModifier = itemModifier.focusProperties { up = upFocusTarget }
                if (index % 4 == 0) {
                    itemModifier = itemModifier
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
                    modifier = itemModifier
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