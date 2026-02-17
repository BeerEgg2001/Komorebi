@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.UiChannelState
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.ui.components.ChannelLogo
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.viewmodel.Channel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

private const val TAG = "LiveContent"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LiveContent(
    modifier: Modifier = Modifier,
    channelViewModel: ChannelViewModel,
    groupedChannels: Map<String, List<Channel>>,
    selectedChannel: Channel?,
    onChannelClick: (Channel?) -> Unit,
    onFocusChannelChange: (String) -> Unit,
    mirakurunIp: String, mirakurunPort: String,
    konomiIp: String, konomiPort: String,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onPlayerStateChanged: (Boolean) -> Unit,
    lastFocusedChannelId: String? = null,
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {}
) {
    val liveRows by channelViewModel.liveRows.collectAsState()
    val listState = rememberLazyListState()
    val targetChannelFocusRequester = remember { FocusRequester() }
    val isPlayerActive = selectedChannel != null

    // コンテンツ準備完了フラグ
    var isContentReady by remember { mutableStateOf(false) }

    // プレイヤー状態保持
    var isMiniListOpen by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(true) }
    var isManualOverlay by remember { mutableStateOf(false) }
    var isPinnedOverlay by remember { mutableStateOf(false) }
    var isSubMenuOpen by remember { mutableStateOf(false) }

    // 1. 初期描画の安定化
    LaunchedEffect(Unit) {
        yield()
        delay(300)
        isContentReady = true
    }

    // 2. プレイヤーのアクティブ状態通知
    LaunchedEffect(isPlayerActive) {
        onPlayerStateChanged(isPlayerActive)
    }

    // 3. プレイヤーから戻った際のフォーカス復旧 (safeRequestFocus適用)
    LaunchedEffect(isReturningFromPlayer, isContentReady) {
        if (isReturningFromPlayer && isContentReady) {
            delay(200) // 確実にUIがアタッチされるのを待機
            targetChannelFocusRequester.safeRequestFocus(TAG)
            onReturnFocusConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isContentReady) {
            // ロード中表示
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.1f))
            }
        } else {
            // メインコンテンツ（チャンネルリスト）
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    .focusRequester(contentFirstItemRequester)
                    // プレイヤー表示中はリストのフォーカスを無効化
                    .then(if (isPlayerActive) Modifier.focusProperties { canFocus = false } else Modifier),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(liveRows, key = { it.genreId }) { row ->
                    Column(modifier = Modifier.fillMaxWidth().graphicsLayer(clip = false)) {
                        Text(
                            text = row.genreLabel,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 32.dp),
                            modifier = Modifier.fillMaxWidth().graphicsLayer(clip = false)
                        ) {
                            items(row.channels, key = { it.channel.id }) { uiState ->
                                // 前回復帰ポイントの判定
                                val isTarget = uiState.channel.id == lastFocusedChannelId

                                ChannelWideCard(
                                    uiState = uiState,
                                    mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                    konomiIp = konomiIp, konomiPort = konomiPort,
                                    onClick = { onChannelClick(uiState.channel) },
                                    modifier = Modifier
                                        .then(if (isTarget) Modifier.focusRequester(targetChannelFocusRequester) else Modifier)
                                        .focusProperties {
                                            // 最初の行の場合、上キーでナビゲーションへ
                                            if (row.genreId == liveRows.firstOrNull()?.genreId) {
                                                up = topNavFocusRequester
                                            }
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused) onFocusChannelChange(uiState.channel.id)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. プレイヤーのオーバーレイ表示
        if (selectedChannel != null) {
            LivePlayerScreen(
                channel = selectedChannel,
                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                konomiIp = konomiIp, konomiPort = konomiPort,
                groupedChannels = groupedChannels,
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
                onSubMenuToggle = { isSubMenuOpen = it }
            )
        }
    }
}

/**
 * チャンネルカード（横長）コンポーネント
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelWideCard(
    uiState: UiChannelState,
    mirakurunIp: String, mirakurunPort: String,
    konomiIp: String, konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    // カードの拡大アニメーション
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 150), label = "cardScale"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(160.dp).height(72.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f), // 手動スケールを使うため1.0固定
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // チャンネルロゴ
                ChannelLogo(
                    channel = uiState.channel,
                    mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                    konomiIp = konomiIp, konomiPort = konomiPort,
                    modifier = Modifier.size(36.dp, 22.dp),
                    backgroundColor = Color.Black.copy(0.2f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // チャンネル名と番組タイトル
                Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = uiState.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.5f),
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.programTitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(
                            if (isFocused) Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1000,
                                spacing = MarqueeSpacing(40.dp)
                            ) else Modifier
                        )
                    )
                }
            }

            // 番組の進捗バー
            if (uiState.hasProgram) {
                Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(Color.Gray.copy(0.1f))) {
                    Box(modifier = Modifier
                        .fillMaxWidth(uiState.progress)
                        .fillMaxHeight()
                        .background(if (isFocused) Color(0xFFD32F2F) else Color.White.copy(0.9f))
                    )
                }
            }
        }
    }
}