package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyGridState
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SeriesInfo

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordSeriesGridContent(
    seriesList: List<SeriesInfo>,
    konomiIp: String,
    konomiPort: String,
    isLoading: Boolean,
    onSeriesClick: (String) -> Unit,
    onOpenNavPane: () -> Unit,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean,
    onBackPress: () -> Unit,
    gridState: TvLazyGridState = rememberTvLazyGridState(),
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isListReady, seriesList) {
        onFirstItemBound(isListReady && seriesList.isNotEmpty())
    }

    val upFocusTarget =
        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && seriesList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else {
            TvLazyVerticalGrid(
                state = gridState,
                columns = TvGridCells.Fixed(4), // 既存のグリッドに合わせて4列
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(seriesList) { index, series ->
                    var isFocused by remember { mutableStateOf(false) }

                    val itemModifier = Modifier
                        .aspectRatio(16f / 9f) // サムネイル比率
                        .onFocusChanged { isFocused = it.isFocused }
                        .then(
                            if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                        )
                        .focusProperties {
                            if (index < 4) {
                                up = upFocusTarget
                            }
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.key == Key.Back || event.key == Key.Escape) {
                                    onBackPress()
                                    true
                                } else if (event.key == Key.DirectionLeft && index % 4 == 0) {
                                    onOpenNavPane()
                                    true
                                } else false
                            } else false
                        }

                    Surface(
                        onClick = { onSeriesClick(series.searchKeyword) },
                        modifier = itemModifier,
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = colors.surface,
                            focusedContainerColor = colors.surface,
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 背景サムネイル
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(
                                        UrlBuilder.getThumbnailUrl(
                                            konomiIp,
                                            konomiPort,
                                            series.representativeVideoId.toString()
                                        )
                                    )
                                    .crossfade(true).build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )

                            // 下部グラデーション（テキストを読みやすくする黒いグラデ）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.9f)
                                            )
                                        )
                                    )
                            )

                            // 右上のエピソード数バッジ
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.7f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${series.programCount}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // 左下のシリーズタイトル
                            Text(
                                text = series.displayTitle,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp)
                                    .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}