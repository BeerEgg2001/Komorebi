@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.UiChannelState
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "LiveContent"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LiveContent(
    modifier: Modifier = Modifier,
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    groupedChannels: Map<String, List<Channel>>,
    selectedChannel: Channel?,
    onChannelClick: (Channel?) -> Unit,
    onFocusChannelChange: (String) -> Unit,
    mirakurunIp: String, mirakurunPort: String, konomiIp: String, konomiPort: String,
    topNavFocusRequester: FocusRequester, contentFirstItemRequester: FocusRequester,
    onPlayerStateChanged: (Boolean) -> Unit, lastFocusedChannelId: String? = null,
    isReturningFromPlayer: Boolean = false, onReturnFocusConsumed: () -> Unit = {},
    reserveViewModel: ReserveViewModel
) {
    val liveRows by channelViewModel.liveRows.collectAsState()
    val listState = rememberLazyListState()
    val targetChannelFocusRequester = remember { FocusRequester() }
    val isPlayerActive = selectedChannel != null
    val colors = KomorebiTheme.colors

    var isMiniListOpen by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(true) }
    var isManualOverlay by remember { mutableStateOf(false) }
    var isPinnedOverlay by remember { mutableStateOf(false) }
    var isSubMenuOpen by remember { mutableStateOf(false) }

    var pendingChannel by remember { mutableStateOf<UiChannelState?>(null) }
    var focusedChannel by remember { mutableStateOf<UiChannelState?>(null) }

    LaunchedEffect(pendingChannel) {
        if (pendingChannel != null) {
            delay(300)
            focusedChannel = pendingChannel
        }
    }

    LaunchedEffect(liveRows) {
        if (focusedChannel == null && liveRows.isNotEmpty()) {
            val firstChannel = liveRows.firstOrNull()?.channels?.firstOrNull()
            if (firstChannel != null) {
                pendingChannel = firstChannel
            }
        }
    }

    LaunchedEffect(isPlayerActive) { onPlayerStateChanged(isPlayerActive) }

    LaunchedEffect(isReturningFromPlayer, liveRows.isNotEmpty()) {
        if (isReturningFromPlayer && liveRows.isNotEmpty()) {
            delay(200); targetChannelFocusRequester.safeRequestFocus(TAG); onReturnFocusConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (liveRows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary.copy(alpha = 0.5f))
            }
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .then(if (isPlayerActive) Modifier.focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    } else Modifier)
            ) {
                // 上部 55%: Hero Info エリア
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f)
                        .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 24.dp)
                ) {
                    if (focusedChannel != null) {
                        HeroDashboard(
                            uiState = focusedChannel!!,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort
                        )
                    }
                }

                // 下部 45%: Bottom Carousel (コンパクトなチャンネルリスト)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.45f)
                        .focusRequester(contentFirstItemRequester),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(liveRows, key = { it.genreId }) { row ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer(clip = false)
                        ) {
                            Text(
                                text = row.genreLabel,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer(clip = false)
                            ) {
                                itemsIndexed(
                                    row.channels,
                                    key = { _, item -> item.channel.id }) { index, uiState ->
                                    val isTarget = uiState.channel.id == lastFocusedChannelId
                                    val isLastItem = index == row.channels.lastIndex

                                    CompactChannelCard(
                                        uiState = uiState,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { onChannelClick(uiState.channel) },
                                        modifier = Modifier
                                            .then(
                                                if (isTarget) Modifier.focusRequester(
                                                    targetChannelFocusRequester
                                                ) else Modifier
                                            )
                                            .focusProperties {
                                                if (row.genreId == liveRows.firstOrNull()?.genreId) {
                                                    up = topNavFocusRequester
                                                }
                                                if (isLastItem) {
                                                    right = FocusRequester.Cancel
                                                }
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    pendingChannel = uiState
                                                    onFocusChannelChange(uiState.channel.id)
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedChannel != null) {
            LivePlayerScreen(
                channel = selectedChannel,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                onChannelSelect = { onChannelClick(it) },
                onBackPressed = { onChannelClick(null) },
                isMiniListOpen = isMiniListOpen,
                onMiniListToggle = { isMiniListOpen = it },
                showOverlay = showOverlay,
                onShowOverlayChange = { showOverlay = it },
                isManualOverlay = isManualOverlay,
                onManualOverlayChange = { isManualOverlay = it },
                isPinnedOverlay = isPinnedOverlay,
                onPinnedOverlayChange = { isPinnedOverlay = it },
                isSubMenuOpen = isSubMenuOpen,
                onSubMenuToggle = { isSubMenuOpen = it },
                reserveViewModel = reserveViewModel,
                epgViewModel = epgViewModel,
                onShowToast = { }
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HeroDashboard(
    uiState: UiChannelState,
    konomiIp: String,
    konomiPort: String
) {
    val colors = KomorebiTheme.colors
    val present = uiState.channel.programPresent
    val following = uiState.channel.programFollowing
    val isHot = (uiState.jikkyoForce ?: 0) > 500
    val logoUrl = UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, uiState.displayChannelId)

    val formatTime = { timeStr: String? ->
        if (timeStr.isNullOrEmpty()) ""
        else try {
            OffsetDateTime.parse(timeStr).format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            ""
        }
    }

    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label = "HeroTransition",
        modifier = Modifier.fillMaxSize()
    ) { state ->
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.35f)
                    .aspectRatio(16f / 9f)
                    .alpha(0.12f)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(clip = false)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(64.dp, 36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.textPrimary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = logoUrl,
                                contentDescription = "Channel Logo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = state.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = state.programTitle,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1500,
                            spacing = MarqueeSpacing(48.dp)
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (state.hasProgram && present != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                text = formatTime(present.startTime),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.textSecondary.copy(0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(state.progress)
                                        .fillMaxHeight()
                                        .background(colors.accent)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = formatTime(present.endTime),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary
                            )

                            Spacer(modifier = Modifier.width(28.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Whatshot,
                                    contentDescription = "Hot",
                                    tint = if (isHot) Color(0xFFE53935) else colors.textSecondary.copy(
                                        alpha = 0.7f
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "勢い: ${state.jikkyoForce ?: 0}コメ/分",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isHot) Color(0xFFE53935) else colors.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = present.description.ifEmpty { "番組詳細がありません" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            maxLines = 3,
                            minLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 24.sp,
                            modifier = Modifier.height(72.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(108.dp))
                    }
                }

                Spacer(modifier = Modifier.width(32.dp))

                Column(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Box(modifier = Modifier.height(84.dp)) {
                        if (following != null) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.accent.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "NEXT",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            ),
                                            color = colors.accent
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${formatTime(following.startTime)} - ${
                                            formatTime(
                                                following.endTime
                                            )
                                        }",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = colors.textPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = following.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    minLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CompactChannelCard(
    uiState: UiChannelState,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "cardScale"
    )

    val logoUrl = UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, uiState.displayChannelId)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(140.dp)
            .height(76.dp)
            .graphicsLayer { scaleX = animatedScale; scaleY = animatedScale }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp, 29.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.textPrimary.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = uiState.name,
                        // ★修正: パディングを削除し、ContentScale.Crop に変更して16:9で上下をクロップ
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.8f)
                )
            }

            if (uiState.hasProgram) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .background(colors.textPrimary.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(uiState.progress)
                            .fillMaxHeight()
                            .background(if (isFocused) colors.accent else colors.accent.copy(alpha = 0.6f))
                    )
                }
            }
        }
    }
}