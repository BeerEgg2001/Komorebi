package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
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
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordSeriesGridContent(
    seriesList: List<SeriesInfo>,
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onSeriesClick: (String) -> Unit,
    onOpenNavPane: () -> Unit,
    firstItemFocusRequester: FocusRequester,
    contentContainerFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean,
    onBackPress: () -> Unit,
    gridState: LazyGridState,
    ticketManager: FocusTicketManager,
    onFirstItemBound: (Boolean) -> Unit = {},
    onFocusedSeriesChanged: (SeriesInfo) -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val isListReady by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    val backendType by settingViewModel.backendType.collectAsState()
    val isScrollInProgress = gridState.isScrollInProgress
    val upFocusTarget =
        if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester

    LaunchedEffect(isListReady, seriesList) {
        onFirstItemBound(isListReady && seriesList.isNotEmpty())
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId
            val index = seriesList.indexOfFirst { it.representativeVideoId == targetId }
            if (index != -1) {
                gridState.scrollToItem(maxOf(0, index - 4))
            } else {
                gridState.scrollToItem(0)
            }
        } else if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            gridState.scrollToItem(0)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentContainerFocusRequester)
            // ★ 修正: クラッシュの原因だった focusRestorer を安全な focusGroup に変更
            .focusGroup()
            .simpleGridVerticalScrollbar(state = gridState, color = colors.textPrimary)
    ) {
        itemsIndexed(seriesList) { index, series ->
            var isFocused by remember { mutableStateOf(false) }
            val specificRequester = remember { FocusRequester() }

            LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                val ticket = ticketManager.currentTicket
                if (ticket == FocusTicket.TARGET_ID && series.representativeVideoId == ticketManager.targetProgramId) {
                    specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_SeriesGrid")
                    ticketManager.consume(FocusTicket.TARGET_ID)
                }
            }

            val itemModifier = Modifier
                .aspectRatio(16f / 9f)
                .focusRequester(specificRequester)
                .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        onFocusedSeriesChanged(series)
                    }
                }
                .focusProperties {
                    if (index < 4) {
                        up = upFocusTarget
                    }
                    if (index % 4 == 0) {
                        left = FocusRequester.Cancel
                    }
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.key == Key.DirectionLeft && index % 4 == 0) {
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
                }

            Surface(
                onClick = { onSeriesClick(series.searchKeyword) }, modifier = itemModifier,
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.surface, focusedContainerColor = colors.surface
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        BorderStroke(
                            2.dp,
                            colors.accent
                        )
                    )
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
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

private fun Modifier.simpleGridVerticalScrollbar(
    state: LazyGridState,
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