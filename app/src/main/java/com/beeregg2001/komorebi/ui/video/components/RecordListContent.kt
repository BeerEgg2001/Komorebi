package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

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
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val listState = rememberTvLazyListState()

    var focusedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var isMenuFocused by remember { mutableStateOf(false) }
    var isDetailVisible by remember { mutableStateOf(false) }

    val menuFocusRequester = remember { FocusRequester() }
    val detailPanelFocusRequester = remember { FocusRequester() }

    // ★修正: 詳細を閉じた際に安全にフォーカスを戻す
    LaunchedEffect(isDetailVisible) {
        if (!isDetailVisible && isMenuFocused) {
            // UIの更新を待ってから、安全にメニューの「詳細ボタン」へ戻す
            menuFocusRequester.safeRequestFocus("DetailClose")
        }
    }

    androidx.activity.compose.BackHandler(enabled = isDetailVisible) {
        isDetailVisible = false
    }

    // ★パネル幅をさらにスリム化 (詳細時: 320dp)
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
        // ★RowをBoxに変更してオーバーレイ構造を実現
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. 下層: リスト領域 (全画面)
            TvLazyColumn(
                state = listState,
                // メニューに隠れないよう右側に余白を確保
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 64.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
                    .focusProperties {
                        if (isSearchBarVisible || isKeyboardActive) {
                            enter = { FocusRequester.Cancel }
                        }
                        right = if (isDetailVisible) FocusRequester.Cancel else menuFocusRequester
                    }
            ) {
                itemsIndexed(recentRecordings, key = { _, item -> item.id }) { index, program ->
                    LaunchedEffect(index) {
                        if (!isLoadingMore && index >= recentRecordings.size - 4) { onLoadMore() }
                    }
                    RecordListItem(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        // 詳細表示中もリストのハイライトを維持
                        isPersistentFocused = (isMenuFocused || isDetailVisible) && focusedProgram?.id == program.id,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focusedProgram = program }
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties {
                                if (index == 0) {
                                    up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
                            }
                    )
                }
            }

            // 2. 上層: オーバーレイペイン
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd) // 右端固定
                    .onFocusChanged { isMenuFocused = it.hasFocus },
                colors = SurfaceDefaults.colors(
                    containerColor = colors.surface.copy(alpha = 0.95f) // リストが透けるように調整
                ),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
            ) {
                if (isDetailVisible) {
                    // ★詳細パネル (スクロール可能)
                    RecordDetailPanel(
                        program = focusedProgram,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        focusRequester = detailPanelFocusRequester,
                        modifier = Modifier.fillMaxSize()
                    )
                    // 表示されたら強制的にフォーカスさせてスクロールを有効化
                    LaunchedEffect(Unit) {
                        detailPanelFocusRequester.safeRequestFocus("DetailOpen")
                    }
                } else {
                    // サブメニュー (通常モード)
                    Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)) {
                        SideMenuItem(
                            icon = Icons.Default.PlayArrow,
                            label = "再生する",
                            isExpanded = isMenuFocused,
                            modifier = Modifier.focusRequester(menuFocusRequester),
                            enabled = focusedProgram != null,
                            onClick = { focusedProgram?.let { onProgramClick(it) } }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SideMenuItem(
                            icon = Icons.Default.Info,
                            label = "番組詳細",
                            isExpanded = isMenuFocused,
                            enabled = focusedProgram != null,
                            onClick = { isDetailVisible = true }
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
// サブメニューの各ボタン
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideMenuItem(
    icon: ImageVector,
    label: String,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true, // 有効・無効の状態を追加
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.5f), // 無効時は半透明にして視覚的に伝える
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.textSecondary
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
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