package com.beeregg2001.komorebi.ui.video.smb

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import kotlinx.coroutines.delay

@SuppressLint("RememberInComposition")
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbListContent(
    items: List<SmbItem>,
    onItemClick: (SmbItem) -> Unit,
    focuses: com.beeregg2001.komorebi.ui.video.RecordListFocusRequesters,
    ticketManager: FocusTicketManager,
    onOpenRightMenu: (SmbItem) -> Unit,
    onLeftKey: () -> Unit,
    onFocusedItemChanged: (SmbItem) -> Unit,
    isMenuOpen: Boolean,
    targetPathToFocus: String? = null,
    onTargetFocusConsumed: () -> Unit = {},
    onBackPress: () -> Unit = {},
    onTopBarDownRequesterChanged: (FocusRequester) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val colors = KomorebiTheme.colors
    val isScrollInProgress = listState.isScrollInProgress
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val focusPath = ticketManager.targetPath ?: targetPathToFocus

    val itemPathsKey = remember(items) { items.joinToString("|") { it.path } }
    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // ★ 修正: ソート実行時、リストの中身(順序)は変わるが listState のスクロール位置(index)は
    // 保持されたままになる。大量アイテムの場合、保持された index は新しい並び順では
    // 先頭ではない別アイテムを指してしまい、down 遷移先のFocusRequesterが
    // 「コンポーズされていない(画面外の)ノード」を指して失敗する。
    // → ソート(=itemsの中身変化)を検知したら、まずリストを先頭(index 0)へ強制的に戻す。
    LaunchedEffect(itemPathsKey) {
        if (items.isNotEmpty() && listState.firstVisibleItemIndex != 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(firstVisibleItemIndex, itemPathsKey ) {
        val firstVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val path = if (firstVisibleItem != null) items.getOrNull(firstVisibleItem.index)?.path else null
        val requester = if (path != null) {
            // 上記スクロールリセットと合わせ、新しい先頭要素がコンポーズされるまで
            // 数フレーム分リトライしてから確定させる（ソート直後のレースコンディション対策）。
            var resolved = itemFocusRequesters[path]
            if (resolved == null) {
                repeat(5) {
                    delay(50)
                    resolved = itemFocusRequesters[path]
                    if (resolved != null) return@repeat
                }
            }
            resolved ?: focuses.firstItem
        } else {
            focuses.firstItem
        }
        onTopBarDownRequesterChanged(requester)
    }

    LaunchedEffect(focusPath, items) {
        if (focusPath != null && items.isNotEmpty()) {
            val index = items.indexOfFirst { it.path == focusPath }
            if (index != -1) {
                listState.scrollToItem(maxOf(0, index - 2))
                delay(200)
                itemFocusRequesters[focusPath]?.safeRequestFocusWithRetry("SMB_List_Focus")
                onTargetFocusConsumed()
                ticketManager.consume(FocusTicket.TARGET_ID)
            }
        }
    }

    LaunchedEffect(ticketManager.currentTicket, items) {
        if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            if (items.isNotEmpty()) {
                listState.scrollToItem(0)
                delay(150)
                focuses.firstItem.safeRequestFocusWithRetry("SmbList_Top")
                ticketManager.consume(FocusTicket.LIST_TOP)
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 16.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focuses.contentContainer)
            .focusGroup()
            .focusProperties { canFocus = !isMenuOpen }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    onBackPress()
                    return@onKeyEvent true
                }
                false
            }
    ) {
        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
            val specificRequester = itemFocusRequesters.getOrPut(item.path) { FocusRequester() }
            var isFocused by remember { mutableStateOf(false) }

            Surface(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .focusRequester(specificRequester)
                    .then(if (index == 0) Modifier.focusRequester(focuses.firstItem) else Modifier)
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) onFocusedItemChanged(item)
                    }
                    .focusProperties {
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.DirectionRight) {
                                if (!isScrollInProgress) {
                                    onOpenRightMenu(item)
                                }
                                return@onKeyEvent true
                            } else if (event.key == Key.DirectionLeft) {
                                if (!isScrollInProgress) {
                                    onLeftKey()
                                }
                                return@onKeyEvent true
                            }
                        }
                        false
                    },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isFocused) colors.textPrimary else Color.Transparent,
                    focusedContainerColor = colors.textPrimary,
                    contentColor = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (item.isDirectory) colors.accent else colors.textSecondary
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.isDirectory) {
                        Text(
                            text = formatFileSize(item.size),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.7f)
                        )
                    }
                    if (isFocused) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .alpha(0.7f)
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("RememberInComposition")
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbGridContent(
    items: List<SmbItem>,
    onItemClick: (SmbItem) -> Unit,
    focuses: com.beeregg2001.komorebi.ui.video.RecordListFocusRequesters,
    ticketManager: FocusTicketManager,
    onOpenRightMenu: (SmbItem) -> Unit,
    onLeftKey: () -> Unit,
    onFocusedItemChanged: (SmbItem) -> Unit,
    isMenuOpen: Boolean,
    targetPathToFocus: String? = null,
    onTargetFocusConsumed: () -> Unit = {},
    onBackPress: () -> Unit = {},
    onTopBarDownRequesterChanged: (FocusRequester) -> Unit = {}
) {
    val gridState = rememberLazyGridState()
    val colors = KomorebiTheme.colors
    val isScrollInProgress = gridState.isScrollInProgress
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val focusPath = ticketManager.targetPath ?: targetPathToFocus

    val itemPathsKey = remember(items) { items.joinToString("|") { it.path } }
    val firstVisibleItemIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }

    // ★ 修正: SmbListContentと同様、ソート時に gridState のスクロール位置(index)が
    // 保持されてしまい、新しい並び順での「画面外ノード」を down 遷移先にしてしまう問題への対策。
    LaunchedEffect(itemPathsKey) {
        if (items.isNotEmpty() && gridState.firstVisibleItemIndex != 0) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(firstVisibleItemIndex, itemPathsKey ) {
        val firstVisibleItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
        val path = if (firstVisibleItem != null) items.getOrNull(firstVisibleItem.index)?.path else null
        val requester = if (path != null) {
            // ★ 修正: SmbListContentと同様、ソート直後の再コンポーズ待ちレースコンディション対策
            var resolved = itemFocusRequesters[path]
            if (resolved == null) {
                repeat(5) {
                    delay(50)
                    resolved = itemFocusRequesters[path]
                    if (resolved != null) return@repeat
                }
            }
            resolved ?: focuses.firstItem
        } else {
            focuses.firstItem
        }
        onTopBarDownRequesterChanged(requester)
    }

    LaunchedEffect(focusPath, items) {
        if (focusPath != null && items.isNotEmpty()) {
            val index = items.indexOfFirst { it.path == focusPath }
            if (index != -1) {
                val targetRowFirstIndex = index - (index % 4)
                gridState.scrollToItem(maxOf(0, targetRowFirstIndex - 4))
                delay(200)
                itemFocusRequesters[focusPath]?.safeRequestFocusWithRetry("SMB_List_Focus")
                onTargetFocusConsumed()
                ticketManager.consume(FocusTicket.TARGET_ID)
            }
        }
    }

    LaunchedEffect(ticketManager.currentTicket, items) {
        if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            if (items.isNotEmpty()) {
                gridState.scrollToItem(0)
                delay(150)
                focuses.firstItem.safeRequestFocusWithRetry("SmbGrid_Top")
                ticketManager.consume(FocusTicket.LIST_TOP)
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
        contentPadding = PaddingValues(top = 16.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focuses.contentContainer)
            .focusGroup()
            .focusProperties { canFocus = !isMenuOpen }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    onBackPress()
                    return@onKeyEvent true
                }
                false
            }
    ) {
        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
            val specificRequester = itemFocusRequesters.getOrPut(item.path) { FocusRequester() }
            var isFocused by remember { mutableStateOf(false) }
            val inverseColor = if (colors.isDark) Color.Black else Color.White

            Surface(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .focusRequester(specificRequester)
                    .then(if (index == 0) Modifier.focusRequester(focuses.firstItem) else Modifier)
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) {
                            onFocusedItemChanged(item)
                        }
                    }
                    .focusProperties {
                        if (index % 4 == 0) left = FocusRequester.Cancel
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.DirectionLeft && index % 4 == 0) {
                                if (!isScrollInProgress) {
                                    onLeftKey()
                                }
                                return@onKeyEvent true
                            }
                        }
                        false
                    },
                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.surface,
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textPrimary,
                    focusedContentColor = inverseColor
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(width = 2.dp, color = colors.accent),
                        shape = MaterialTheme.shapes.medium
                    )
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.background.copy(alpha = if (isFocused) 0.1f else 0.4f))
                    )

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(bottom = 24.dp),
                            tint = if (item.isDirectory) colors.accent else colors.textSecondary
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(colors.surface.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            // ★ 修正: テキストの色を colors.textPrimary に固定（isFocused による分岐を削除）
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (item.isDirectory) "フォルダ" else "ファイル",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                // ★ 修正: サブテキストの色も colors.textPrimary.copy(alpha = 0.8f) に固定
                                color = colors.textPrimary.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (!item.isDirectory) {
                                Text(
                                    text = formatFileSize(item.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    // ★ 修正: サイズテキストの色も固定
                                    color = colors.textPrimary.copy(alpha = 0.8f),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var digit = size.toDouble()
    var unitIndex = 0
    while (digit >= 1024 && unitIndex < units.size - 1) {
        digit /= 1024
        unitIndex++
    }
    return "%.1f %s".format(digit, units[unitIndex])
}