package com.beeregg2001.komorebi.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.components.ChannelLogo
import com.beeregg2001.komorebi.viewmodel.Channel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加

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
    val colors = KomorebiTheme.colors

    // 表示可能なタブをフィルタリング
    val allTabs = listOf("GR", "BS", "CS", "BS4K", "SKY")
    val availableTabKeys = remember(groupedChannels) {
        allTabs.filter { groupedChannels.containsKey(it) }
    }

    // 現在のチャンネルが含まれるタブを初期選択にする
    val initialTab = groupedChannels.entries.find { entry ->
        entry.value.any { it.id == currentChannelId }
    }?.key ?: availableTabKeys.firstOrNull() ?: ""

    var selectedTab by remember { mutableStateOf(initialTab) }
    val currentChannels = groupedChannels[selectedTab] ?: emptyList()
    val listState = rememberLazyListState()

    // タブに対応する FocusRequester リスト
    val tabFocusRequesters = remember(availableTabKeys) {
        List(availableTabKeys.size) { FocusRequester() }
    }
    val selectedTabIndex = availableTabKeys.indexOf(selectedTab).coerceAtLeast(0)

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
                        // ★修正: 黒の固定値からテーマ背景色ベースのグラデーションへ
                        colors.background.copy(alpha = 0.6f),
                        colors.background.copy(alpha = 0.9f),
                        colors.background
                    ),
                    startY = 0f,
                    endY = 500f
                )
            )
            .padding(bottom = 8.dp, top = 16.dp)
    ) {
        // --- 1. 放送波種別タブ (下線タイプ & フォーカス維持修正) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.widthIn(max = 800.dp),
                indicator = { tabPositions, doesTabRowHaveFocus ->
                    TabRowDefaults.UnderlinedIndicator(
                        currentTabPosition = tabPositions[selectedTabIndex],
                        doesTabRowHaveFocus = doesTabRowHaveFocus,
                        activeColor = Color.White
                    )
                }
            ) {
                availableTabKeys.forEachIndexed { index, tabKey ->
                    val label = when (tabKey) {
                        "GR" -> "地デジ"
                        "BS" -> "BS"
                        "CS" -> "CS"
                        "BS4K" -> "BS4K"
                        "SKY" -> "スカパー"
                        else -> tabKey
                    }

                    val isSelected = selectedTab == tabKey
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    Tab(
                        selected = isSelected,
                        onFocus = { selectedTab = tabKey },
                        modifier = Modifier.focusRequester(tabFocusRequesters[index]),
                        interactionSource = interactionSource
                    ) {
                        // ★修正: Unresolved reference を回避するため Text 側で色を指定
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected || isFocused) Color.White else Color.Gray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. チャンネルリスト (上に移動した際に現在選択中のタブへ戻る設定) ---
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                // ★修正: リストから上に移動したとき、常に地デジではなく「現在選択中のタブ」へ移動させる
                .focusProperties {
                    up = tabFocusRequesters[selectedTabIndex]
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        // 戻るキーでも選択中のタブにフォーカスを戻す
                        tabFocusRequesters[selectedTabIndex].requestFocus()
                        return@onKeyEvent true
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
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(200),
        label = "scale"
    )

    // ★修正: カード背景色のテーマ化
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) colors.textPrimary
        else if (isSelected) colors.surface
        else colors.surface.copy(alpha = 0.6f),
        animationSpec = tween(200), label = "bgColor"
    )

    // ★修正: 文字色のテーマ化
    val contentColor by animateColorAsState(
        targetValue = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
        label = "contentColor"
    )

    val borderWidth = if (isFocused) 3.dp else 0.dp
    val borderColor = if (isFocused) colors.accent else Color.Transparent

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

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(8.dp)
                    .background(colors.accent, RoundedCornerShape(50))
            )
        }
    }
}