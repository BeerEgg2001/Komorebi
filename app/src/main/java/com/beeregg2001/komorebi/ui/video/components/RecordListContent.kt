package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

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
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val listState = rememberTvLazyListState()

    // サブメニューのフォーカス管理用
    var isMenuFocused by remember { mutableStateOf(false) }
    val menuFocusRequester = remember { FocusRequester() }

    // サブメニューの幅（フォーカスが当たったら広がる）
    val menuWidth by animateDpAsState(
        targetValue = if (isMenuFocused) 200.dp else 64.dp,
        animationSpec = tween(durationMillis = 200), label = "MenuWidth"
    )

    if (isLoadingInitial && recentRecordings.isEmpty()) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = colors.textPrimary) }
    } else {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // メインのリスト領域
            TvLazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusProperties {
                        if (isSearchBarVisible || isKeyboardActive) {
                            enter = { FocusRequester.Cancel }
                        }
                        right = menuFocusRequester // 右キーでサブメニューへ
                    }
            ) {
                itemsIndexed(
                    items = recentRecordings,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "RecordListItem" }
                ) { index, program ->

                    LaunchedEffect(index) {
                        if (!isLoadingMore && index >= recentRecordings.size - 4) {
                            onLoadMore()
                        }
                    }

                    RecordListItem(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier
                            .then(
                                if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                            )
                            .focusProperties {
                                if (index == 0) {
                                    up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
                            }
                    )
                }
                if (isLoadingMore) {
                    item(contentType = "LoadingIndicator") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = colors.textPrimary) }
                    }
                }
            }

            // 右側のサブメニューペイン
            Column(
                modifier = Modifier
                    .width(menuWidth)
                    .fillMaxHeight()
                    .background(colors.surface.copy(alpha = 0.5f))
                    .padding(vertical = 16.dp, horizontal = 8.dp)
                    .onFocusChanged { isMenuFocused = it.hasFocus } // Column内の要素がフォーカスを持ったら広げる
            ) {
                // 仮のメニュー項目
                SideMenuItem(
                    icon = Icons.Default.PlayArrow,
                    label = "再生する",
                    isExpanded = isMenuFocused,
                    modifier = Modifier.focusRequester(menuFocusRequester),
                    onClick = { /* TODO */ }
                )
                Spacer(modifier = Modifier.height(12.dp))
                SideMenuItem(
                    icon = Icons.Default.Info,
                    label = "番組詳細",
                    isExpanded = isMenuFocused,
                    onClick = { /* TODO */ }
                )
                Spacer(modifier = Modifier.height(12.dp))
                SideMenuItem(
                    icon = Icons.Default.Delete,
                    label = "削除する",
                    isExpanded = isMenuFocused,
                    onClick = { /* TODO */ }
                )
            }
        }
    }
}

// サブメニューの各ボタン
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideMenuItem(
    icon: ImageVector,
    label: String,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
            if (isExpanded) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 16.sp,
                    maxLines = 1
                )
            }
        }
    }
}