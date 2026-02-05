package com.example.komorebi

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelListScreen(
    groupedChannels: Map<String, List<Channel>>,
    activeChannel: Channel? = null,
    onChannelClick: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    isMiniMode: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    val categories = remember(groupedChannels) { groupedChannels.keys.toList() }
    var selectedTabIndex by remember(categories) {
        mutableIntStateOf(if (activeChannel != null) categories.indexOf(activeChannel.type).coerceAtLeast(0) else 0)
    }
    var previousTabIndex by remember { mutableIntStateOf(selectedTabIndex) }
    var tabRowHasFocus by remember { mutableStateOf(false) }

    val firstItemFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember(categories) { List(categories.size) { FocusRequester() } }
    var isListAtTop by remember { mutableStateOf(true) }

    LaunchedEffect(isMiniMode) {
        delay(200)
        runCatching { firstItemFocusRequester.requestFocus() }
    }

    // ChannelListScreen_dev.kt
    Box(
        modifier = modifier
            .fillMaxSize()
            .onKeyEvent { keyEvent ->
                // 1. 戻るキー(BACK)が押された時
                if (keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {

                    if (isMiniMode) {
                        // ミニモード時は自分で閉じる（今まで通り）
                        onDismiss()
                        true
                    } else {
                        // 通常の一覧画面時は「false」を返して MainActivity に任せる
                        // これにより BackHandler が検知して終了ダイアログが出るようになります
                        false
                    }
                } else false
            }
            // clickable はミニモード時のみ有効にする（一覧画面での誤爆防止）
            .then(if (isMiniMode) Modifier.clickable { onDismiss() } else Modifier)
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (isMiniMode) {
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.9f))
                            .padding(vertical = 24.dp)
                            .clickable(enabled = false) { }
                    } else {
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212))
                    }
                )
        ) {
            if (!isMiniMode) {
                Text(
                    text = "チャンネル一覧",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(start = 48.dp, top = 32.dp, bottom = 16.dp)
                )
            }

            // --- 放送波タブ ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier
                        .wrapContentWidth()
                        .onFocusChanged { tabRowHasFocus = it.hasFocus },
                    indicator = { tabPositions, _ ->
                        if (selectedTabIndex < tabPositions.size) {
                            TabRowDefaults.PillIndicator(
                                currentTabPosition = tabPositions[selectedTabIndex],
                                activeColor = Color.White.copy(alpha = 0.2f),
                                inactiveColor = Color.Transparent,
                                doesTabRowHaveFocus = tabRowHasFocus
                            )
                        }
                    }
                ) {
                    categories.forEachIndexed { index, category ->
                        Tab(
                            selected = (selectedTabIndex == index),
                            onFocus = {
                                if (selectedTabIndex != index) {
                                    previousTabIndex = selectedTabIndex
                                    selectedTabIndex = index
                                }
                            },
                            onClick = { firstItemFocusRequester.requestFocus() },
                            modifier = Modifier
                                .focusRequester(tabFocusRequesters[index])
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown &&
                                        keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                        firstItemFocusRequester.requestFocus()
                                        true
                                    } else false
                                }
                        ) {
                            Text(
                                text = category,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selectedTabIndex == index) Color.White else Color.White.copy(alpha = 0.5f),
                                fontWeight = if (selectedTabIndex == index) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // --- リスト表示領域 ---
            AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = {
                    val direction = if (targetState > previousTabIndex) 1 else -1
                    (slideInHorizontally(animationSpec = tween(300)) { it * direction } + fadeIn()).togetherWith(
                        slideOutHorizontally(animationSpec = tween(300)) { -it * direction } + fadeOut())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isMiniMode) Modifier.wrapContentHeight() else Modifier.weight(1f))
                    .focusGroup(),
                label = "SlideAnimation"
            ) { targetIndex ->
                val currentChannels = groupedChannels[categories[targetIndex]] ?: emptyList()

                if (isMiniMode) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 48.dp)
                    ) {
                        itemsIndexed(currentChannels, key = { _, ch -> ch.id }) { index, channel ->
                            MiniChannelCard(
                                channel = channel,
                                onClick = { onChannelClick(channel) },
                                modifier = Modifier
                                    .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                                    .onKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown &&
                                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                            tabFocusRequesters[selectedTabIndex].requestFocus()
                                            true
                                        } else false
                                    }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
                    ) {
                        itemsIndexed(currentChannels, key = { _, ch -> ch.id }) { index, channel ->
                            ChannelCard(
                                channel = channel,
                                onClick = { onChannelClick(channel) },
                                modifier = Modifier
                                    .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                                    .onFocusChanged { if (it.isFocused) isListAtTop = (index == 0) }
                                    .onKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown &&
                                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                                            isListAtTop) {
                                            tabFocusRequesters[selectedTabIndex].requestFocus()
                                            true
                                        } else false
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MiniChannelCard(channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = 160.dp, height = 110.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White))),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.DarkGray.copy(alpha = 0.6f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("http://192.168.100.60:40772/api/services/${buildStreamId(channel)}/logo").build(),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 60.dp, height = 35.dp)
                    .background(Color.White, MaterialTheme.shapes.extraSmall),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Text(
                text = channel.programPresent?.title ?: "放送休止中",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(
                        animationMode = if (isFocused) MarqueeAnimationMode.Immediately else MarqueeAnimationMode.WhileFocused,
                        iterations = if (isFocused) Int.MAX_VALUE else 0,
                        repeatDelayMillis = 1000,
                        spacing = MarqueeSpacing(30.dp)
                    ),
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(80.dp),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.DarkGray.copy(alpha = 0.3f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = "http://192.168.100.60:40772/api/services/${buildStreamId(channel)}/logo",
                contentDescription = null,
                modifier = Modifier.size(width = 80.dp, height = 45.dp).background(Color.White, MaterialTheme.shapes.small),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = channel.name, style = MaterialTheme.typography.labelMedium)
                Text(text = channel.programPresent?.title ?: "放送休止中", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun buildStreamId(channel: Channel): String {
    val networkIdPart = when (channel.type) {
        "GR" -> channel.networkId.toString()
        "BS", "CS", "SKY", "BS4K" -> "${channel.networkId}00"
        else -> channel.networkId.toString()
    }
    return "${networkIdPart}${channel.serviceId}"
}