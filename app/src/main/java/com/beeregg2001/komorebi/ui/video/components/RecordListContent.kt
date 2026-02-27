package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
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
    recentRecordings: List<RecordedProgram>,
    isLoadingInitial: Boolean,
    isLoadingMore: Boolean,
    konomiIp: String,
    konomiPort: String,
    isSearchBarVisible: Boolean,
    isKeyboardActive: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onSeriesSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    isDetailVisible: Boolean,
    onDetailStateChange: (Boolean) -> Unit,
    onBackPress: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var detailProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var isSideMenuOpen by remember { mutableStateOf(false) }

    val itemFocusRequesters = remember(recentRecordings) { mutableMapOf<Int, FocusRequester>() }
    val menuFirstItemRequester = remember { FocusRequester() }
    val detailPanelFocusRequester = remember { FocusRequester() }

    val firstVisibleIndex by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        }
    }

    val isListReady by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    LaunchedEffect(isListReady, recentRecordings) {
        onFirstItemBound(isListReady && recentRecordings.isNotEmpty())
    }

    LaunchedEffect(recentRecordings) {
        focusedProgram = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusProperties {
                    canFocus = !isSideMenuOpen && !isDetailVisible
                }
        ) {
            itemsIndexed(items = recentRecordings, key = { _, item -> item.id }) { index, program ->
                val requester = itemFocusRequesters.getOrPut(program.id) { FocusRequester() }

                LaunchedEffect(index) {
                    if (!isLoadingMore && index >= recentRecordings.size - 4) onLoadMore()
                }

                RecordListItem(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort,
                    onClick = { onProgramClick(program, null) },
                    isPersistentFocused = (isSideMenuOpen || isDetailVisible) &&
                            (if (isDetailVisible) detailProgram?.id == program.id else focusedProgram?.id == program.id),
                    modifier = Modifier
                        .focusRequester(requester)
                        .onFocusChanged {
                            if (it.isFocused && !isSideMenuOpen && !isDetailVisible) focusedProgram =
                                program
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
                        .then(
                            if (index == firstVisibleIndex) Modifier.focusRequester(
                                firstItemFocusRequester
                            ) else Modifier
                        )
                )
            }
        }

        val overlayWidth by animateDpAsState(
            targetValue = when {
                isDetailVisible -> 350.dp; isSideMenuOpen -> 180.dp; else -> 0.dp
            },
            animationSpec = tween(250), label = "OverlayWidth"
        )

        LaunchedEffect(isSideMenuOpen) {
            if (isSideMenuOpen) {
                delay(50); menuFirstItemRequester.safeRequestFocus("SideMenu")
            }
        }

        LaunchedEffect(isDetailVisible) {
            if (isDetailVisible) {
                delay(50); detailPanelFocusRequester.safeRequestFocus("Detail")
            } else {
                if (isSideMenuOpen) {
                    delay(10); menuFirstItemRequester.safeRequestFocus("BackToSubMenu")
                } else if (focusedProgram != null) {
                    itemFocusRequesters[focusedProgram!!.id]?.safeRequestFocus("ReturnDetail")
                }
            }
        }

        // ★修正: if 文から AnimatedVisibility に変更し、右側への終了アニメーションを適用
        AnimatedVisibility(
            visible = isSideMenuOpen || isDetailVisible,
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
                    .focusGroup()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val isLeftKey = event.key == Key.DirectionLeft
                            val isBackAction =
                                !isDetailVisible && (event.key == Key.Back || event.key == Key.Escape)

                            if (isLeftKey || isBackAction) {
                                if (!isListReady) return@onKeyEvent true
                                isSideMenuOpen = false
                                onDetailStateChange(false)
                                scope.launch {
                                    delay(10)
                                    focusedProgram?.let { prog ->
                                        itemFocusRequesters[prog.id]?.safeRequestFocus("ReturnFromSide")
                                    } ?: firstItemFocusRequester.safeRequestFocus("ReturnToTop")
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
                if (isDetailVisible) {
                    RecordDetailPanel(
                        program = detailProgram,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        focusRequester = detailPanelFocusRequester,
                        onClose = { onDetailStateChange(false) },
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
                            icon = Icons.Default.PlayArrow, label = "再生する", isExpanded = true,
                            modifier = Modifier.focusRequester(menuFirstItemRequester),
                            onClick = { focusedProgram?.let { onProgramClick(it, null) } }
                        )
                        Spacer(Modifier.height(12.dp))
                        SideMenuItem(
                            icon = Icons.Default.Replay, label = "最初から再生", isExpanded = true,
                            onClick = { focusedProgram?.let { onProgramClick(it, 0.0) } }
                        )
                        Spacer(Modifier.height(12.dp))
                        SideMenuItem(
                            icon = Icons.Default.Info, label = "番組詳細", isExpanded = true,
                            onClick = { detailProgram = focusedProgram; onDetailStateChange(true) }
                        )
                        Spacer(Modifier.height(12.dp))
                        SideMenuItem(
                            icon = Icons.Default.LibraryBooks,
                            label = "シリーズ検索",
                            isExpanded = true,
                            onClick = {
                                focusedProgram?.let {
                                    onSeriesSearch(
                                        TitleNormalizer.extractSearchKeyword(
                                            it.title
                                        )
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        SideMenuItem(
                            icon = Icons.Default.Delete,
                            label = "削除する",
                            isExpanded = true,
                            enabled = false,
                            onClick = { })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideMenuItem(
    icon: ImageVector, label: String, isExpanded: Boolean,
    modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit
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
                Spacer(Modifier.width(12.dp)); Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
    }
}