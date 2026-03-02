package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
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
    visibleItemFocusRequester: FocusRequester, // ★追加
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onSeriesSearch: (String) -> Unit,
    isDetailVisible: Boolean,
    onDetailStateChange: (Boolean) -> Unit,
    onBackPress: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    fetchedProgramDetail: RecordedProgram? = null,
    onFetchDetail: (Int) -> Unit = {},
    onClearDetail: () -> Unit = {},
    onFirstItemBound: (Boolean) -> Unit = {}
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

    // ★重要: 現在画面に見えている最初のアイテムのインデックスを監視
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

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

                    RecordListItem(
                        program = program, konomiIp = konomiIp, konomiPort = konomiPort,
                        onClick = { onProgramClick(program, null) },
                        isPersistentFocused = (isSideMenuOpen || isDetailVisible) &&
                                (if (isDetailVisible) detailProgram?.id == program.id else focusedProgram?.id == program.id),
                        modifier = Modifier
                            .focusRequester(specificRequester)
                            // ★修正: index == 0 の時は先頭へ、それ以外で見えている時は帰り道用の visibleItem を貼る
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .then(
                                if (index == firstVisibleIndex) Modifier.focusRequester(
                                    visibleItemFocusRequester
                                ) else Modifier
                            )
                            .onFocusChanged {
                                if (it.isFocused) {
                                    if (!isSideMenuOpen && !isDetailVisible) {
                                        focusedProgram = program
                                    }
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.DirectionRight) {
                                        isSideMenuOpen = true; return@onKeyEvent true
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
                                    val id = focusedProgram?.id
                                    if (id != null && itemFocusRequesters.containsKey(id)) {
                                        itemFocusRequesters[id]?.safeRequestFocus()
                                    } else {
                                        visibleItemFocusRequester.safeRequestFocus() // ★修正
                                    }
                                    isSideMenuOpen = false
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

                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 28.dp)) {
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
                                        val id =
                                            focusedProgram?.id; if (id != null && itemFocusRequesters.containsKey(
                                            id
                                        )
                                    ) {
                                        itemFocusRequesters[id]?.safeRequestFocus()
                                    } else {
                                        visibleItemFocusRequester.safeRequestFocus()
                                    }; focusedProgram?.let {
                                        isSideMenuOpen = false; onSeriesSearch(
                                        TitleNormalizer.extractSearchKeyword(it.title)
                                    )
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