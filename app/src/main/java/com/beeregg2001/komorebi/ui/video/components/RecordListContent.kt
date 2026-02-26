package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
    firstItemFocusRequester: FocusRequester, // ★元通りアイテム用のRequesterとして使用
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
    var isMenuFocused by remember { mutableStateOf(false) }

    val menuFirstItemRequester = remember { FocusRequester() }
    val listItemReturnRequester = remember { FocusRequester() }
    val detailPanelFocusRequester = remember { FocusRequester() }

    // ★重要: 現在「完全に」画面に表示されている最上位のアイテムのインデックスを計算する
    val firstFullyVisibleIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) -1
            else {
                val first = visibleItems.first()
                // アイテムの上端が画面外に少しでも隠れていて、次にアイテムがある場合は次を対象とする
                if (first.offset < 0 && visibleItems.size > 1) {
                    visibleItems[1].index
                } else {
                    first.index
                }
            }
        }
    }

    val isListReady by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isListReady) {
        onFirstItemBound(isListReady)
    }

    val panelWidth by animateDpAsState(
        targetValue = when {
            isDetailVisible -> 320.dp
            isMenuFocused -> 180.dp
            else -> 48.dp
        },
        animationSpec = tween(durationMillis = 250), label = "PanelWidth"
    )

    LaunchedEffect(isMenuFocused) {
        if (isMenuFocused && !isDetailVisible) {
            delay(50)
            menuFirstItemRequester.safeRequestFocus("SideMenuEntry")
        }
    }

    LaunchedEffect(isDetailVisible) {
        if (isDetailVisible) {
            delay(50)
            detailPanelFocusRequester.safeRequestFocus("DetailPanelEntry")
        }
    }

    if (isLoadingInitial && recentRecordings.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.textPrimary)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 60.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = recentRecordings,
                    key = { _, item -> item.id }) { index, program ->

                    LaunchedEffect(index) {
                        if (!isLoadingMore && index >= recentRecordings.size - 4) onLoadMore()
                    }
                    RecordListItem(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program, null) },
                        isPersistentFocused = (isMenuFocused || isDetailVisible) && focusedProgram?.id == program.id,
                        modifier = Modifier
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                    onBackPress()
                                    true
                                } else false
                            }
                            .onFocusChanged { if (it.isFocused) focusedProgram = program }
                            .then(
                                if (focusedProgram?.id == program.id) Modifier.focusRequester(
                                    listItemReturnRequester
                                ) else Modifier
                            )
                            // ★修正: 他から飛んでくるターゲットは、完全に表示されている一番上のアイテムに付与する
                            .then(
                                if (index == firstFullyVisibleIndex) Modifier.focusRequester(
                                    firstItemFocusRequester
                                ) else Modifier
                            )
                            .focusProperties {
                                // ★修正: 上へ飛び出せるのは「本当にリストの最初の要素(index 0)」のみに限定！
                                // これで高速スクロール中に上へすっぽ抜ける現象がなくなります
                                if (index == 0) {
                                    up =
                                        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
                                right = menuFirstItemRequester
                            }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .onFocusChanged { isMenuFocused = it.hasFocus }
                    .focusGroup()
                    .focusProperties { left = listItemReturnRequester }
                    .onKeyEvent { event ->
                        if (isMenuFocused && !isDetailVisible && event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                            scope.launch { listItemReturnRequester.safeRequestFocus("BackFromSideMenuKey") }
                            true
                        } else false
                    },
                colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
            ) {
                if (isDetailVisible) {
                    RecordDetailPanel(
                        program = focusedProgram,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        focusRequester = detailPanelFocusRequester,
                        onClose = {
                            listItemReturnRequester.safeRequestFocus("BackFromDetailToListItem")
                            onDetailStateChange(false)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isMenuFocused) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                                tint = colors.textPrimary.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 4.dp)
                                    .size(32.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = if (isMenuFocused) 36.dp else 0.dp, end = 8.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = if (isMenuFocused) Alignment.Start else Alignment.CenterHorizontally
                        ) {
                            SideMenuItem(
                                icon = Icons.Default.PlayArrow,
                                label = "再生する",
                                isExpanded = isMenuFocused,
                                modifier = Modifier.focusRequester(menuFirstItemRequester),
                                enabled = focusedProgram != null,
                                onClick = { focusedProgram?.let { onProgramClick(it, null) } }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SideMenuItem(
                                icon = Icons.Default.Replay,
                                label = "最初から再生",
                                isExpanded = isMenuFocused,
                                enabled = focusedProgram != null,
                                onClick = { focusedProgram?.let { onProgramClick(it, 0.0) } }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SideMenuItem(
                                icon = Icons.Default.Info,
                                label = "番組詳細",
                                isExpanded = isMenuFocused,
                                enabled = focusedProgram != null,
                                onClick = { onDetailStateChange(true) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SideMenuItem(
                                icon = Icons.Default.LibraryBooks,
                                label = "シリーズ検索",
                                isExpanded = isMenuFocused,
                                enabled = focusedProgram != null,
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
                            Spacer(modifier = Modifier.height(12.dp))
                            SideMenuItem(
                                icon = Icons.Default.Delete,
                                label = "削除する",
                                isExpanded = isMenuFocused,
                                enabled = false,
                                onClick = { }
                            )
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp))
            if (isExpanded) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }
    }
}