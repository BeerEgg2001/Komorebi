package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SeriesInfo

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordSeriesContent(
    seriesList: List<SeriesInfo>,
    konomiIp: String,
    konomiPort: String,
    isLoading: Boolean,
    onSeriesClick: (String) -> Unit,
    onOpenNavPane: () -> Unit,
    isListView: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean,
    onBackPress: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onFirstItemBound: (Boolean) -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    val isListReady by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isListReady, seriesList) {
        onFirstItemBound(isListReady && seriesList.isNotEmpty())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && seriesList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 16.dp,
                    bottom = 32.dp
                ),
                // 高さを抑えた分、アイテム間の隙間も少しコンパクトに
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(seriesList) { index, series ->
                    var isFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSeriesClick(series.searchKeyword) },
                        modifier = Modifier
                            .fillMaxWidth()
                            // ★修正: 76.dp -> 64.dp にさらに縮小。1画面に8〜9個表示できるようになります。
                            .height(64.dp)
                            .onFocusChanged { isFocused = it.isFocused }
                            .then(
                                if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                            )
                            .focusProperties {
                                if (index == 0) {
                                    up =
                                        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.Back || event.key == Key.Escape) {
                                        onBackPress()
                                        true
                                    } else if (event.key == Key.DirectionLeft) {
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
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左側：サムネイル画像
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(16f / 9f)
                                    .background(Color.DarkGray.copy(alpha = 0.5f))
                            ) {
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
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // 中央：テキスト情報
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    // 高さに合わせてパディングをギリギリまで切り詰め
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = series.displayTitle,
                                    modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // テキストサイズは維持して見やすさを確保
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${series.programCount} エピソード",
                                        // サブテキストを少し小さく
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textSecondary
                                    )
                                }
                            }

                            // 右端：誘導アイコン
                            if (isFocused) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp), // アイコンも高さに合わせて少し縮小
                                    tint = if (colors.isDark) Color.Black.copy(alpha = 0.7f) else Color.White.copy(
                                        alpha = 0.7f
                                    )
                                )
                            }
                        }
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