package com.example.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.PivotOffsets
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.komorebi.buildStreamId
import com.example.komorebi.ui.live.LivePlayerScreen
import com.example.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

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
    // --- 修正ポイント：役割を分割 ---
    topNavFocusRequester: FocusRequester,      // 上のタブバーに戻るための出口
    contentFirstItemRequester: FocusRequester, // タブバーから下に降りる時の入口
    onPlayerStateChanged: (Boolean) -> Unit
) {
    val listState = rememberTvLazyListState()
    var internalLastFocusedId by rememberSaveable { mutableStateOf<String?>(null) }

    // 視聴復帰専用のRequester
    val watchedChannelFocusRequester = remember { FocusRequester() }

    val isPlayerActive = selectedChannel != null

    // 視聴から戻った時だけ、見ていたチャンネルにフォーカスを戻す
    LaunchedEffect(isPlayerActive) {
        onPlayerStateChanged(isPlayerActive)
        if (!isPlayerActive && selectedChannel != null) {
            delay(150)
            runCatching { watchedChannelFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvLazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                // タブバーからの「下」入力を受け取る
                .focusRequester(contentFirstItemRequester)
                .then(if (isPlayerActive) Modifier.focusProperties { canFocus = false } else Modifier),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            pivotOffsets = PivotOffsets(parentFraction = 0.2f)
        ) {
            val sortedKeys = groupedChannels.keys.toList()

            sortedKeys.forEachIndexed { rowIndex, key ->
                val displayCategory = if (key == "GR") "地デジ" else key
                val channels = groupedChannels[key] ?: emptyList()

                item(key = key) {
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
                            items(channels, key = { it.id }) { channel ->
                                val isSelected = channel.id == selectedChannel?.id

                                ChannelWideCard(
                                    channel = channel,
                                    mirakurunIp = mirakurunIp,
                                    mirakurunPort = mirakurunPort,
                                    globalTick = 0,
                                    onClick = { onChannelClick(channel) },
                                    modifier = Modifier
                                        // 視聴中だったカードにのみ復帰用Requesterを付与
                                        .then(if (isSelected) Modifier.focusRequester(watchedChannelFocusRequester) else Modifier)
                                        .focusProperties {
                                            // 【重要】地デジ行（一番上の行）なら、上キーでタブバーのRequesterを指定
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

    val progress = remember(channel.programPresent, globalTick) {
        calculateProgress(channel.programPresent?.startTime, channel.programPresent?.duration)
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
                AsyncImage(
                    model = "http://$mirakurunIp:$mirakurunPort/api/services/${buildStreamId(channel)}/logo",
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp, 22.dp)
                        .background(Color.Black.copy(0.2f), MaterialTheme.shapes.extraSmall),
                    contentScale = ContentScale.Fit
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

@RequiresApi(Build.VERSION_CODES.O)
fun calculateProgress(startTimeString: String?, duration: Int?): Float {
    if (startTimeString == null || duration == null || duration <= 0) return 0f
    return try {
        val startTimeMillis = ZonedDateTime.parse(startTimeString).toInstant().toEpochMilli()
        val currentTimeMillis = System.currentTimeMillis()
        val progress = (currentTimeMillis - startTimeMillis).toFloat() / (duration * 1000).toFloat()
        progress.coerceIn(0f, 1f)
    } catch (e: Exception) {
        0f
    }
}