package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.components.ChannelLogo
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LiveContent(
    modifier: Modifier = Modifier,
    groupedChannels: Map<String, List<Channel>>,
    selectedChannel: Channel?,
    lastWatchedChannel: Channel?,
    onChannelClick: (Channel?) -> Unit,
    onFocusChannelChange: (String) -> Unit,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onPlayerStateChanged: (Boolean) -> Unit,
    lastFocusedChannelId: String? = null,
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {}
) {
    val listState = rememberTvLazyListState()
    val targetChannelFocusRequester = remember { FocusRequester() }
    val isPlayerActive = selectedChannel != null

    // ★最適化1: レンダリングの遅延フラグ
    // タブが切り替わった瞬間の負荷を抑えるため、100msだけ描画を遅らせる
    var isReadyToRender by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        isReadyToRender = true
    }

    LaunchedEffect(isPlayerActive) {
        onPlayerStateChanged(isPlayerActive)
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer) {
            delay(150)
            runCatching {
                targetChannelFocusRequester.requestFocus()
            }
            onReturnFocusConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isReadyToRender) { // ★遅延描画の適用
            TvLazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    .focusRequester(contentFirstItemRequester)
                    .then(if (isPlayerActive) Modifier.focusProperties { canFocus = false } else Modifier),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // ★最適化2: ソート処理のキャッシュ化
                val sortedKeys = groupedChannels.keys.toList()

                sortedKeys.forEachIndexed { rowIndex, key ->
                    val displayCategory = if (key == "GR") "地デジ" else key
                    val channels = groupedChannels[key] ?: emptyList()

                    item(key = key, contentType = "CategoryRow") {
                        Column(modifier = Modifier.fillMaxWidth().graphicsLayer(clip = false)) {
                            Text(
                                text = displayCategory,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)
                            )

                            TvLazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 32.dp),
                                modifier = Modifier.fillMaxWidth().graphicsLayer(clip = false)
                            ) {
                                items(channels, key = { it.id }, contentType = { "ChannelCard" }) { channel ->
                                    val isTarget = channel.id == lastFocusedChannelId

                                    ChannelWideCard(
                                        channel = channel,
                                        mirakurunIp = mirakurunIp,
                                        mirakurunPort = mirakurunPort,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        globalTick = 0,
                                        onClick = { onChannelClick(channel) },
                                        modifier = Modifier
                                            .then(if (isTarget) Modifier.focusRequester(targetChannelFocusRequester) else Modifier)
                                            .focusProperties {
                                                if (rowIndex == 0) {
                                                    up = topNavFocusRequester
                                                }
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    onFocusChannelChange(channel.id)
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 遅延中のプレースホルダー（一瞬なので空のBoxで十分です）
            Box(Modifier.fillMaxSize())
        }

        if (selectedChannel != null) {
            var isMiniListOpen by remember { mutableStateOf(false) }
            var showOverlay by remember { mutableStateOf(true) }
            var isManualOverlay by remember { mutableStateOf(false) }
            var isPinnedOverlay by remember { mutableStateOf(false) }
            var isSubMenuOpen by remember { mutableStateOf(false) }

            LivePlayerScreen(
                channel = selectedChannel,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                groupedChannels = groupedChannels,
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
                onChannelSelect = { onChannelClick(it) },
                onBackPressed = { onChannelClick(null) }
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelWideCard(
    channel: Channel,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    globalTick: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "cardScale"
    )

    val startTime = channel.programPresent?.startTime
    val duration = channel.programPresent?.duration

    // ★最適化3: 日時パース処理の安定化
    val startTimeMillis = remember(startTime) {
        if (startTime.isNullOrEmpty()) 0L
        else try {
            java.time.OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    // ★最適化4: 進捗計算の安定化 (不要な再計算をトリガーしない)
    val progress = remember(startTimeMillis, duration, globalTick) {
        if (startTimeMillis == 0L || duration == null || duration <= 0) 0f
        else {
            val currentTimeMillis = System.currentTimeMillis()
            ((currentTimeMillis - startTimeMillis).toFloat() / (duration * 1000).toFloat()).coerceIn(0f, 1f)
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(160.dp)
            .height(72.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                // ★描画パフォーマンス向上のため、ハードウェアレイヤーを明示的に使用
                clip = true
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelLogo(
                    channel = channel,
                    mirakurunIp = mirakurunIp,
                    mirakurunPort = mirakurunPort,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    modifier = Modifier.size(36.dp, 22.dp),
                    backgroundColor = Color.Black.copy(0.2f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.5f),
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = channel.programPresent?.title ?: "放送休止中",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(
                            if (isFocused) {
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                    initialDelayMillis = 1000,
                                    spacing = MarqueeSpacing(40.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                    )
                }
            }
            if (channel.programPresent != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(2.5.dp)
                        .background(Color.Gray.copy(0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(if (isFocused) Color(0xFFD32F2F) else Color.White.copy(0.9f))
                    )
                }
            }
        }
    }
}