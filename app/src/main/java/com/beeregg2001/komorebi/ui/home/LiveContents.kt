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
    lastFocusedChannelId: String? = null
) {
    val listState = rememberTvLazyListState()
    var internalLastFocusedId by rememberSaveable { mutableStateOf<String?>(null) }

    val targetChannelFocusRequester = remember { FocusRequester() }
    val isPlayerActive = selectedChannel != null

    LaunchedEffect(isPlayerActive) {
        onPlayerStateChanged(isPlayerActive)
        if (!isPlayerActive) {
            delay(150)
            runCatching { targetChannelFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvLazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .focusRequester(contentFirstItemRequester)
                .then(if (isPlayerActive) Modifier.focusProperties { canFocus = false } else Modifier),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val sortedKeys = groupedChannels.keys.toList()

            sortedKeys.forEachIndexed { rowIndex, key ->
                val displayCategory = if (key == "GR") "地デジ" else key
                val channels = groupedChannels[key] ?: emptyList()

                // ★最適化: contentType を指定し、Composeエンジンにレイアウトの使い回しを指示
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
                            // ★最適化: こちらにも contentType を指定
                            items(channels, key = { it.id }, contentType = { "ChannelCard" }) { channel ->
                                val isTarget = channel.id == selectedChannel?.id || (selectedChannel == null && channel.id == lastFocusedChannelId)

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
                                                internalLastFocusedId = channel.id
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

        if (selectedChannel != null) {
            var isMiniListOpen by remember { mutableStateOf(false) }
            LivePlayerScreen(
                channel = selectedChannel,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                groupedChannels = groupedChannels,
                isMiniListOpen = isMiniListOpen,
                onMiniListToggle = { isMiniListOpen = it },
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

    // ★最適化: 重いパース処理を文字列が変わった時(番組が切り替わった時)のみ1回だけ実行してキャッシュ
    val startTimeMillis = remember(startTime) {
        if (startTime.isNullOrEmpty()) 0L
        else try {
            java.time.OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    // 計算にはパース済みのミリ秒を使うため、極めて軽量に
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