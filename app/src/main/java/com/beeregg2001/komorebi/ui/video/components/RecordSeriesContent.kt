package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyGridState
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordSeriesContent(
    seriesList: List<Pair<String, String>>,
    isLoading: Boolean,
    onSeriesClick: (String) -> Unit,
    onOpenNavPane: () -> Unit,
    isListView: Boolean,
    firstItemFocusRequester: FocusRequester, // ★元通りアイテム用のRequesterとして使用
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean,
    onBackPress: () -> Unit,
    gridState: TvLazyGridState = rememberTvLazyGridState(),
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    // ★重要: 現在「完全に」画面に表示されている最上位のアイテムのインデックスを計算する
    val firstFullyVisibleIndex by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) -1
            else {
                val first = visibleItems.first()
                // アイテムの上端が画面外に少しでも隠れていて、次にアイテムがある場合は完全に見えている行の先頭を取得
                if (first.offset.y < 0) {
                    visibleItems.firstOrNull { it.offset.y >= 0 }?.index ?: first.index
                } else {
                    first.index
                }
            }
        }
    }

    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isListReady) {
        onFirstItemBound(isListReady)
    }

    if (isLoading && seriesList.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.textPrimary)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvLazyVerticalGrid(
            state = gridState,
            columns = TvGridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 16.dp,
                end = 12.dp,
                bottom = 32.dp
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(seriesList) { index, pair ->
                var isFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onSeriesClick(pair.second) },
                    modifier = Modifier
                        .onFocusChanged { isFocused = it.isFocused }
                        // ★修正: 他から飛んでくるターゲットは、完全に表示されている一番上のアイテムに付与する
                        .then(
                            if (index == firstFullyVisibleIndex) Modifier.focusRequester(
                                firstItemFocusRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            // ★修正: 上へ飛び出せるのは「本当にリストの先頭行(indexが3未満)」のみ！
                            // 高速スクロール中にワープするのを防ぎます
                            if (index < 3) {
                                up =
                                    if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                            }
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                onBackPress()
                                true
                            } else if (!isListView && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && index % 3 == 0) {
                                onOpenNavPane()
                                true
                            } else false
                        },
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.textPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textPrimary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                    )
                ) {
                    Text(
                        text = pair.first, modifier = Modifier
                            .padding(16.dp)
                            .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                        maxLines = 1, style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        if (!isListView) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft, contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.5f), modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}