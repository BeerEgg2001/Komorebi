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
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean,
    onBackPress: () -> Unit,
    gridState: TvLazyGridState = rememberTvLazyGridState(),
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    // 描画されている最初のアイテムのインデックスを取得
    val firstVisibleIndex by remember {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        }
    }

    // リストが物理的に表示されているか判定
    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isListReady, seriesList) {
        // リストが存在し、かつ1件以上データがある場合に「準備完了」を親に通知
        onFirstItemBound(isListReady && seriesList.isNotEmpty())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && seriesList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else {
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
                            // 最初の可視アイテムにリクエスターを付与
                            .then(
                                if (index == firstVisibleIndex) Modifier.focusRequester(
                                    firstItemFocusRequester
                                ) else Modifier
                            )
                            .focusProperties {
                                if (index < 3) {
                                    up =
                                        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.Back || event.key == Key.Escape) {
                                        onBackPress()
                                        true
                                    } else if (event.key == Key.DirectionLeft && index % 3 == 0) {
                                        onOpenNavPane()
                                        true
                                    } else false
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
                            text = pair.first,
                            modifier = Modifier
                                .padding(16.dp)
                                .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
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
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}