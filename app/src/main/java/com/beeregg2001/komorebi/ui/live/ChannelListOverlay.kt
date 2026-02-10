package com.beeregg2001.komorebi.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.components.ChannelLogo
import com.beeregg2001.komorebi.viewmodel.Channel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelListOverlay(
    groupedChannels: Map<String, List<Channel>>,
    currentChannelId: String,
    onChannelSelect: (Channel) -> Unit,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    focusRequester: FocusRequester
) {
    val focusManager = LocalFocusManager.current

    // タブの定義順序
    val tabs = listOf("GR", "BS", "CS", "BS4K", "SKY")

    // 現在のチャンネルが含まれるタブを初期選択にする
    val initialTab = groupedChannels.entries.find { entry ->
        entry.value.any { it.id == currentChannelId }
    }?.key ?: tabs.first()

    var selectedTab by remember { mutableStateOf(initialTab) }
    val currentChannels = groupedChannels[selectedTab] ?: emptyList()
    val listState = rememberLazyListState()

    // タブ切り替え時にリストを先頭（または選択中チャンネル）に戻す
    LaunchedEffect(selectedTab) {
        val index = currentChannels.indexOfFirst { it.id == currentChannelId }
        if (index >= 0) {
            listState.scrollToItem(index)
        } else {
            listState.scrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.6f),
                        Color.Black.copy(alpha = 0.9f),
                        Color.Black
                    ),
                    startY = 0f,
                    endY = 500f
                )
            )
            .padding(bottom = 8.dp, top = 24.dp)
    ) {
        // --- 1. 放送波種別タブ ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tabKey ->
                if (groupedChannels.containsKey(tabKey)) {
                    val label = when (tabKey) {
                        "GR" -> "地デジ"
                        "BS" -> "BS"
                        "CS" -> "CS"
                        "BS4K" -> "BS4K"
                        "SKY" -> "スカパー"
                        else -> tabKey
                    }

                    TypeTabItem(
                        label = label,
                        isSelected = selectedTab == tabKey,
                        onFocus = { selectedTab = tabKey }
                    )

                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 2. チャンネルリスト ---
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                // ★追加: 戻るキーで上のタブへフォーカス移動
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        focusManager.moveFocus(FocusDirection.Up)
                        return@onKeyEvent true // イベントを消費して、ここでは閉じない
                    }
                    false
                }
        ) {
            items(currentChannels, key = { it.id }) { channel ->
                val isSelected = channel.id == currentChannelId

                // 初期フォーカスを現在のチャンネルに合わせるためのRequester
                val itemRequester = if (isSelected) focusRequester else FocusRequester()

                ChannelCardItem(
                    channel = channel,
                    isSelected = isSelected,
                    mirakurunIp = mirakurunIp,
                    mirakurunPort = mirakurunPort,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onChannelSelect(channel) },
                    modifier = Modifier.focusRequester(itemRequester)
                )
            }
        }
    }
}

@Composable
fun ChannelCardItem(
    channel: Channel,
    isSelected: Boolean,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // フォーカス時のスケールアニメーション
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(200),
        label = "scale"
    )

    // 背景色: フォーカス時は白、視聴中はグレー、通常は暗いグレー
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) Color.White
        else if (isSelected) Color(0xFF424242)
        else Color(0xFF222222),
        animationSpec = tween(200),
        label = "bgColor"
    )

    // 文字色: フォーカス時は黒、それ以外は白
    val contentColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black else Color.White,
        label = "contentColor"
    )

    // ボーダー設定: フォーカス時のみ太い白枠を表示
    val borderWidth = if (isFocused) 3.dp else 0.dp
    val borderColor = if (isFocused) Color.White else Color.Transparent

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .width(220.dp)
            .height(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            ChannelLogo(
                channel = channel,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                modifier = Modifier
                    .width(80.dp)
                    .height(45.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isFocused) Color.LightGray else Color.White),
                backgroundColor = Color.Transparent
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = channel.programPresent?.title ?: "放送情報なし",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }

        // 視聴中インジケータ
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(8.dp)
                    .background(Color.White, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
fun TypeTabItem(
    label: String,
    isSelected: Boolean,
    onFocus: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    if (isFocused) {
        LaunchedEffect(Unit) {
            onFocus()
        }
    }

    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isFocused) Color.White
                else if (isSelected) Color(0xFF616161)
                else Color.White.copy(0.1f)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onFocus() }
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isFocused) Color.Black else Color.White
        )
    }
}