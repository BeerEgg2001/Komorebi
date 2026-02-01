package com.example.komorebi.ui.home

import android.os.Build
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.example.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveContent(
    modifier: Modifier = Modifier,
    groupedChannels: Map<String, List<Channel>>,
    lastWatchedChannel: Channel?,
    lastFocusedChannelId: String?,
    onFocusChannelChange: (String) -> Unit,
    mirakurunIp: String,
    mirakurunPort: String,
    topTabFocusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit
) {
    val categories = remember(groupedChannels) {
        groupedChannels.keys.map { key -> if (key == "GR") "地デジ" else key }
    }

    // ★ ターゲットIDの確定（nullなら最初のチャンネルをデフォルトにする）
    val defaultChannelId = remember(groupedChannels) {
        groupedChannels["GR"]?.firstOrNull()?.id ?: groupedChannels.values.firstOrNull()?.firstOrNull()?.id
    }
    val targetId = lastFocusedChannelId ?: lastWatchedChannel?.id ?: defaultChannelId

    val rowStates = remember { mutableMapOf<String, TvLazyListState>() }
    val channelFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val listState = rememberTvLazyListState()

    // 進捗バー更新
    var globalTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(20000)
            globalTick++
        }
    }

    // ★ 修正：フォーカス復元ロジック（初回表示・視聴後復帰 両対応）
    LaunchedEffect(targetId, groupedChannels) {
        if (targetId == null) return@LaunchedEffect

        var targetCatIndex = -1
        var targetCatName = ""
        categories.forEachIndexed { index, displayCat ->
            val key = if (displayCat == "地デジ") "GR" else displayCat
            if (groupedChannels[key]?.any { it.id == targetId } == true) {
                targetCatIndex = index
                targetCatName = displayCat
            }
        }

        if (targetCatIndex != -1) {
            // 垂直方向の移動
            listState.scrollToItem(targetCatIndex)
            delay(150) // CompositionとRowの生成待ち

            // 水平方向の移動
            val originalKey = if (targetCatName == "地デジ") "GR" else targetCatName
            val chIndex = groupedChannels[originalKey]?.indexOfFirst { it.id == targetId } ?: 0
            rowStates[targetCatName]?.scrollToItem(chIndex)
        }
    }

    TvLazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { state ->
                // 親コンテナがフォーカスを得た（タブから降りてきた）際の救済措置
                if (state.isFocused && targetId != null) {
                    channelFocusRequesters[targetId]?.requestFocus()
                }
            }
            .focusable(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        pivotOffsets = PivotOffsets(parentFraction = 0.15f)
    ) {
        itemsIndexed(categories, key = { _, cat -> cat }) { categoryIndex, displayCategory ->
            val originalKey = if (displayCategory == "地デジ") "GR" else displayCategory
            val channels = groupedChannels[originalKey] ?: emptyList()

            // 初期位置の計算
            val initialChIndex = remember(channels, targetId) {
                val index = channels.indexOfFirst { it.id == targetId }
                if (index >= 0) index else 0
            }

            // 各行のStateを個別に生成
            val rowState = rememberTvLazyListState(initialFirstVisibleItemIndex = initialChIndex)

            // SideEffectを使用してMapを更新（外部のLaunchedEffectから参照可能にする）
            SideEffect {
                rowStates[displayCategory] = rowState
            }

            Column(modifier = Modifier.fillMaxWidth().graphicsLayer(clip = false)) {
                Text(
                    text = displayCategory,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)
                )

                TvLazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    pivotOffsets = PivotOffsets(parentFraction = 0.1f),
                    modifier = Modifier.fillMaxWidth().graphicsLayer(clip = false)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val fr = channelFocusRequesters.getOrPut(channel.id) { FocusRequester() }

                        ChannelWideCard(
                            channel = channel,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            globalTick = globalTick,
                            onClick = { onChannelClick(channel) },
                            modifier = Modifier
                                .focusRequester(fr)
                                .onFocusChanged { if (it.isFocused) onFocusChannelChange(channel.id) }
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown &&
                                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                        if (categoryIndex == 0) {
                                            topTabFocusRequester.requestFocus()
                                            return@onKeyEvent true
                                        }
                                    }
                                    false
                                }
                        )
                    }
                }
            }
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
                Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(Color.Gray.copy(0.1f))) {
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