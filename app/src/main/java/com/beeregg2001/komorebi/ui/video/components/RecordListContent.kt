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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.delay

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
    onLoadMore: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val listState = rememberLazyListState()

    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var isMenuFocused by remember { mutableStateOf(false) }
    var isDetailVisible by remember { mutableStateOf(false) }

    val menuFocusRequester = remember { FocusRequester() }
    val detailPanelFocusRequester = remember { FocusRequester() }
    val listItemReturnFocusRequester = remember { FocusRequester() }

    androidx.activity.compose.BackHandler(enabled = isDetailVisible || isMenuFocused) {
        listItemReturnFocusRequester.safeRequestFocus("BackToItem")
        if (isDetailVisible) isDetailVisible = false
    }

    val panelWidth by animateDpAsState(
        targetValue = when {
            isDetailVisible -> 320.dp
            isMenuFocused -> 180.dp
            else -> 48.dp
        },
        animationSpec = tween(durationMillis = 250), label = "PanelWidth"
    )

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
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties {
                        if (isSearchBarVisible || isKeyboardActive) enter = { FocusRequester.Cancel }
                        right = if (isDetailVisible) FocusRequester.Cancel else menuFocusRequester
                    }
            ) {
                itemsIndexed(items = recentRecordings, key = { _, item -> item.id }) { index, program ->
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
                            .onFocusChanged { if (it.isFocused) focusedProgram = program }
                            .then(if (focusedProgram?.id == program.id) Modifier.focusRequester(listItemReturnFocusRequester) else Modifier)
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties {
                                if (index == 0) {
                                    up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
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
                    .focusProperties {
                        left = listItemReturnFocusRequester
                        if (isDetailVisible) up = FocusRequester.Cancel
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
                            listItemReturnFocusRequester.safeRequestFocus("DetailBack")
                            isDetailVisible = false
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    LaunchedEffect(Unit) { detailPanelFocusRequester.safeRequestFocus("DetailOpen") }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 左端中央に固定された「＜」マーク
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

                        // メニュー項目
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                // ★修正: メニュー展開時のみパディングを適用し、閉じている時は中央に配置されるように
                                .padding(
                                    start = if (isMenuFocused) 36.dp else 0.dp,
                                    end = if (isMenuFocused) 8.dp else 0.dp
                                ),
                            verticalArrangement = Arrangement.Center,
                            // ★修正: 閉じている時は中央揃え
                            horizontalAlignment = if (isMenuFocused) Alignment.Start else Alignment.CenterHorizontally
                        ) {
                            SideMenuItem(
                                icon = Icons.Default.PlayArrow,
                                label = "再生する",
                                isExpanded = isMenuFocused,
                                modifier = Modifier.focusRequester(menuFocusRequester),
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
                                onClick = { isDetailVisible = true })
                            Spacer(modifier = Modifier.height(12.dp))
                            SideMenuItem(
                                icon = Icons.Default.LibraryBooks,
                                label = "シリーズ検索",
                                isExpanded = isMenuFocused,
                                enabled = focusedProgram != null,
                                onClick = {
                                    focusedProgram?.let {
                                        isDetailVisible = false
                                        val keyword = TitleNormalizer.extractSearchKeyword(it.title)
                                        onSeriesSearch(keyword)
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
        onClick = onClick,
        enabled = enabled,
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
            horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center
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