package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordListContent(
    pagedRecordings: LazyPagingItems<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    isSearchBarVisible: Boolean,
    isKeyboardActive: Boolean,
    firstItemFocusRequester: FocusRequester,
    contentContainerFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onSeriesSearch: (String) -> Unit,
    isDetailVisible: Boolean,
    onDetailStateChange: (Boolean) -> Unit,
    onBackPress: () -> Unit,
    ticketManager: FocusTicketManager,
    listState: LazyListState = rememberLazyListState(),
    fetchedProgramDetail: RecordedProgram? = null,
    onFetchDetail: (Int) -> Unit = {},
    onClearDetail: () -> Unit = {},
    onFirstItemBound: (Boolean) -> Unit = {},
    onFocusedItemChanged: (RecordedProgram?) -> Unit = {},
    onOpenNavPane: () -> Unit = {},
    // TopBar の下キー時に使うべき FocusRequester を親へ通知。先頭可視アイテムが変わるたびに呼ばれる。
    onTopBarDownRequesterChanged: (FocusRequester) -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var detailProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var isSideMenuOpen by remember { mutableStateOf(false) }

    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val menuFirstItemRequester = remember { FocusRequester() }
    val detailButtonFocusRequester = remember { FocusRequester() }
    val detailPanelFocusRequester = remember { FocusRequester() }
    val menuAnchorRequester = remember { FocusRequester() }

    val isAnyMenuOpen = isSideMenuOpen || isDetailVisible
    val menuTransitionState =
        remember { MutableTransitionState(false) }.apply { targetState = isAnyMenuOpen }
    val isScrollInProgress = listState.isScrollInProgress

    // 先頭可視アイテムが変わるたびに、TopBar の下キー用 requester を親に通知する。
    // listState.firstVisibleItemIndex が変わると再実行され、対応する specificRequester を返す。
    // itemFocusRequesters はアイテムが描画されるたびに埋まるため、
    // 先頭アイテムが画面に入った直後は null になる可能性があるが、その場合は firstItemFocusRequester にフォールバック。
    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, pagedRecordings.itemCount) {
        val firstVisibleId = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Int
        val requester = if (firstVisibleId != null) {
            itemFocusRequesters[firstVisibleId] ?: firstItemFocusRequester
        } else {
            firstItemFocusRequester
        }
        onTopBarDownRequesterChanged(requester)
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId
            val index = (0 until pagedRecordings.itemCount).firstOrNull {
                pagedRecordings.peek(it)?.id == targetId
            }
            if (index != null) {
                listState.scrollToItem(maxOf(0, index - 1))
            } else {
                listState.scrollToItem(0)
            }
        } else if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(pagedRecordings.itemCount) {
        if (pagedRecordings.itemCount == 0) focusedProgram = null
    }

    LaunchedEffect(isSideMenuOpen, menuTransitionState.isIdle) {
        if (isSideMenuOpen && !isDetailVisible && menuTransitionState.isIdle) {
            menuFirstItemRequester.safeRequestFocus("SideMenuOpened")
        }
    }

    LaunchedEffect(isDetailVisible) {
        if (isDetailVisible) {
            delay(100); detailPanelFocusRequester.safeRequestFocus("DetailPanelOpened")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentContainerFocusRequester)
                .focusGroup()
                .focusProperties { canFocus = !isAnyMenuOpen }
                .onKeyEvent { if (!menuTransitionState.isIdle) true else false }
        ) {
            items(
                count = pagedRecordings.itemCount,
                key = pagedRecordings.itemKey { it.id },
                contentType = pagedRecordings.itemContentType { "program_list" }
            ) { index ->
                val program = pagedRecordings[index]

                if (program != null) {
                    val specificRequester =
                        itemFocusRequesters.getOrPut(program.id) { FocusRequester() }

                    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                        val ticket = ticketManager.currentTicket
                        if (ticket == FocusTicket.TARGET_ID && program.id == ticketManager.targetProgramId) {
                            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID")
                            ticketManager.consume(FocusTicket.TARGET_ID)
                        }
                    }

                    RecordListItem(
                        program = program, konomiIp = konomiIp, konomiPort = konomiPort,
                        onClick = { onProgramClick(program, null) },
                        isPersistentFocused = (isSideMenuOpen || isDetailVisible) &&
                                (if (isDetailVisible) detailProgram?.id == program.id else focusedProgram?.id == program.id),
                        modifier = Modifier
                            .focusRequester(specificRequester)
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties {
                                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    if (!isSideMenuOpen && !isDetailVisible) {
                                        focusedProgram = program
                                        onFocusedItemChanged(program)
                                    }
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.DirectionRight) {
                                        if (!isScrollInProgress) isSideMenuOpen = true
                                        return@onKeyEvent true
                                    } else if (event.key == Key.DirectionLeft) {
                                        if (!isScrollInProgress) onOpenNavPane()
                                        return@onKeyEvent true
                                    }
                                    if (event.key == Key.Back || event.key == Key.Escape) {
                                        onBackPress(); return@onKeyEvent true
                                    }
                                }
                                false
                            }
                    )
                }
            }
        }

        val overlayWidth by animateDpAsState(
            targetValue = when {
                isDetailVisible -> 350.dp; isSideMenuOpen -> 210.dp; else -> 0.dp
            },
            animationSpec = tween(250), label = "OverlayWidth"
        )

        AnimatedVisibility(
            visibleState = menuTransitionState,
            enter = slideInHorizontally(animationSpec = tween(250)) { it },
            exit = slideOutHorizontally(animationSpec = tween(250)) { it } + fadeOut(
                animationSpec = tween(
                    150
                )
            ),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(50f)
        ) {
            Surface(
                modifier = Modifier
                    .width(overlayWidth)
                    .fillMaxHeight()
                    .focusRequester(menuAnchorRequester)
                    .focusable()
                    .focusGroup()
                    .focusProperties { left = FocusRequester.Cancel }
                    .onKeyEvent { event ->
                        if (!menuTransitionState.isIdle) return@onKeyEvent true

                        if (event.type == KeyEventType.KeyDown) {
                            val isLeftKey = event.key == Key.DirectionLeft
                            val isBackAction = event.key == Key.Back || event.key == Key.Escape

                            if (isLeftKey || isBackAction) {
                                if (isDetailVisible) {
                                    menuAnchorRequester.safeRequestFocus()
                                    onDetailStateChange(false)
                                    onClearDetail()
                                    scope.launch { delay(50); detailButtonFocusRequester.safeRequestFocus() }
                                } else {
                                    isSideMenuOpen = false
                                    val id = focusedProgram?.id
                                    if (id != null) {
                                        ticketManager.issue(FocusTicket.TARGET_ID, id)
                                    } else {
                                        ticketManager.issue(FocusTicket.LIST_TOP)
                                    }
                                }
                                return@onKeyEvent true
                            }
                        }
                        false
                    },
                colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.98f)),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = colors.textPrimary.copy(alpha = 0.4f),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp)
                            .size(24.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 28.dp)
                    ) {
                        if (isDetailVisible) {
                            RecordDetailPanel(
                                program = fetchedProgramDetail ?: detailProgram,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                focusRequester = detailPanelFocusRequester,
                                onClose = {
                                    menuAnchorRequester.safeRequestFocus(); onDetailStateChange(
                                    false
                                ); onClearDetail()
                                    scope.launch { delay(50); detailButtonFocusRequester.safeRequestFocus() }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                SideMenuItem(
                                    icon = Icons.Default.PlayArrow,
                                    label = "再生する",
                                    isExpanded = true,
                                    modifier = Modifier
                                        .focusRequester(menuFirstItemRequester)
                                        .focusProperties {
                                            up = FocusRequester.Cancel; right =
                                            FocusRequester.Cancel
                                        },
                                    onClick = { focusedProgram?.let { onProgramClick(it, null) } })
                                Spacer(Modifier.height(12.dp))
                                SideMenuItem(
                                    icon = Icons.Default.Replay,
                                    label = "最初から再生",
                                    isExpanded = true,
                                    modifier = Modifier.focusProperties {
                                        right = FocusRequester.Cancel
                                    },
                                    onClick = { focusedProgram?.let { onProgramClick(it, 0.0) } })
                                Spacer(Modifier.height(12.dp))
                                SideMenuItem(
                                    icon = Icons.Default.Info,
                                    label = "番組詳細",
                                    isExpanded = true,
                                    modifier = Modifier
                                        .focusRequester(detailButtonFocusRequester)
                                        .focusProperties { right = FocusRequester.Cancel },
                                    onClick = {
                                        detailProgram = focusedProgram; focusedProgram?.id?.let {
                                        onFetchDetail(
                                            it
                                        )
                                    }; detailPanelFocusRequester.safeRequestFocus(); onDetailStateChange(
                                        true
                                    )
                                    })
                                Spacer(Modifier.height(12.dp))
                                SideMenuItem(
                                    icon = Icons.Default.LibraryBooks,
                                    label = "シリーズ検索",
                                    isExpanded = true,
                                    modifier = Modifier.focusProperties {
                                        right = FocusRequester.Cancel
                                    },
                                    onClick = {
                                        focusedProgram?.let {
                                            val displayTitle =
                                                TitleNormalizer.extractDisplayTitle(it.title)
                                            val keyword =
                                                TitleNormalizer.toSqlSearchQuery(displayTitle)
                                            onSeriesSearch(keyword)
                                            isSideMenuOpen = false
                                        }
                                    })
                                Spacer(Modifier.height(12.dp))
                                SideMenuItem(
                                    icon = Icons.Default.Delete,
                                    label = "削除する",
                                    isExpanded = true,
                                    modifier = Modifier.focusProperties {
                                        down = FocusRequester.Cancel; right = FocusRequester.Cancel
                                    },
                                    enabled = false,
                                    onClick = { })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideMenuItem(
    icon: ImageVector,
    label: String,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp))
            if (isExpanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
    }
}