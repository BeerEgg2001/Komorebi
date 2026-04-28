package com.beeregg2001.komorebi.ui.video.components

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.viewmodel.SeriesInfo
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp

@SuppressLint("RememberInComposition")
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordSeriesContent(
    seriesList: List<SeriesInfo>,
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onSeriesClick: (String) -> Unit,
    onOpenNavPane: () -> Unit,
    isListView: Boolean,
    firstItemFocusRequester: FocusRequester,
    contentContainerFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean,
    onBackPress: () -> Unit,
    listState: LazyListState,
    ticketManager: FocusTicketManager,
    onFirstItemBound: (Boolean) -> Unit = {},
    onFocusedSeriesChanged: (SeriesInfo) -> Unit = {},
    // ★ 追加: 現在見えている一番上のアイテムの FocusRequester を親に伝えるコールバック
    onTopBarDownRequesterChanged: (FocusRequester) -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val isListReady by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    val isScrollInProgress = listState.isScrollInProgress
    val backendType by settingViewModel.backendType.collectAsState()

    // ★ 追加: 各アイテムの FocusRequester を保持するマップ
    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    LaunchedEffect(isListReady, seriesList) {
        onFirstItemBound(isListReady && seriesList.isNotEmpty())
    }

    // ★ 追加: スクロール位置を監視し、見えている一番上のアイテムのFocusRequesterを更新する
    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, seriesList.size) {
        val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
        val requester = if (firstVisibleIndex != null && firstVisibleIndex in seriesList.indices) {
            itemFocusRequesters[seriesList[firstVisibleIndex].representativeVideoId]
                ?: firstItemFocusRequester
        } else {
            firstItemFocusRequester
        }
        onTopBarDownRequesterChanged(requester)
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId
            val index = seriesList.indexOfFirst { it.representativeVideoId == targetId }
            if (index != -1) {
                listState.scrollToItem(maxOf(0, index - 1))
            } else {
                listState.scrollToItem(0)
            }
        } else if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            listState.scrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentContainerFocusRequester)
                // ★ 修正: クラッシュの原因だった focusRestorer を安全な focusGroup に変更
                .focusGroup()
                .simpleVerticalScrollbar(state = listState, color = colors.textPrimary)
        ) {
            itemsIndexed(seriesList) { index, series ->
                var isFocused by remember { mutableStateOf(false) }

                // ★ 修正: 保持用のマップから FocusRequester を取得する
                val specificRequester =
                    itemFocusRequesters.getOrPut(series.representativeVideoId) { FocusRequester() }

                val fallbackUrl = series.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
                    backendType,
                    konomiIp,
                    konomiPort,
                    series.representativeVideoId.toString()
                )
                val primaryUrl = series.directThumbnailUrl ?: fallbackUrl
                var currentThumbnailUrl by remember(
                    series.representativeVideoId,
                    primaryUrl
                ) { mutableStateOf(primaryUrl) }

                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    val ticket = ticketManager.currentTicket
                    if (ticket == FocusTicket.TARGET_ID && series.representativeVideoId == ticketManager.targetProgramId) {
                        specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_Series")
                        ticketManager.consume(FocusTicket.TARGET_ID)
                    }
                }

                Surface(
                    onClick = { onSeriesClick(series.searchKeyword) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .focusRequester(specificRequester)
                        .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .onFocusChanged {
                            isFocused = it.isFocused
                            if (it.isFocused) {
                                onFocusedSeriesChanged(series)
                            }
                        }
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            // ★ 修正: 検索バー等への行き来を自然にするため、index==0 の up 制約をそのまま維持
                            if (index == 0) {
                                up =
                                    if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                            }
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.key == Key.DirectionLeft) {
                                    if (!isScrollInProgress) {
                                        onOpenNavPane()
                                    }
                                    return@onKeyEvent true
                                }
                                if (event.key == Key.Back || event.key == Key.Escape) {
                                    onBackPress()
                                    return@onKeyEvent true
                                }
                            }
                            false
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
                        focusedBorder = Border(
                            BorderStroke(
                                2.dp,
                                colors.accent
                            )
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(16f / 9f)
                                .background(Color.DarkGray.copy(alpha = 0.5f))
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(currentThumbnailUrl)
                                    .crossfade(true).build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = {
                                    if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                                        currentThumbnailUrl = fallbackUrl
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = series.displayTitle,
                                modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textSecondary
                                )
                            }
                        }
                        if (isFocused) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(24.dp),
                                tint = if (colors.isDark) Color.Black.copy(alpha = 0.7f) else Color.White.copy(
                                    alpha = 0.7f
                                )
                            )
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

private fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    color: Color,
    width: Dp = 4.dp,
    paddingEnd: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleItems == 0 || visibleItems >= totalItems) return@drawWithContent

    val firstVisible = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
    val thumbHeightRatio = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.05f, 0.8f)
    val thumbHeight = size.height * thumbHeightRatio
    val thumbOffsetRatio = (firstVisible.toFloat() / (totalItems - visibleItems).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val thumbY = (size.height - thumbHeight) * thumbOffsetRatio

    drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(size.width - width.toPx() - paddingEnd.toPx(), thumbY),
        size = Size(width.toPx(), thumbHeight),
        cornerRadius = CornerRadius(width.toPx() / 2f, width.toPx() / 2f)
    )
}