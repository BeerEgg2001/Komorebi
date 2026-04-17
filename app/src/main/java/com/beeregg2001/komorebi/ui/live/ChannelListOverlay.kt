@file:OptIn(ExperimentalTvMaterial3Api::class)

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelListOverlay(
    groupedChannels: Map<String, List<Channel>>,
    currentChannelId: String,
    onChannelSelect: (Channel) -> Unit,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean, // ★ 追加: クロップフラグ
    focusRequester: FocusRequester
) {
    val focusManager = LocalFocusManager.current
    val colors = KomorebiTheme.colors

    val allTabs = listOf("GR", "BS", "CS", "BS4K", "SKY")
    val availableTabKeys = remember(groupedChannels) {
        allTabs.filter { groupedChannels.containsKey(it) }
    }

    val initialTab = groupedChannels.entries.find { entry ->
        entry.value.any { it.id == currentChannelId }
    }?.key ?: availableTabKeys.firstOrNull() ?: ""

    var selectedTab by remember { mutableStateOf(initialTab) }
    val currentChannels = groupedChannels[selectedTab] ?: emptyList()
    val listState = rememberLazyListState()

    val tabFocusRequesters = remember(availableTabKeys) {
        availableTabKeys.associateWith { FocusRequester() }
    }

    val selectedTabIndex = availableTabKeys.indexOf(selectedTab).coerceAtLeast(0)

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

                    val requester = tabFocusRequesters[tabKey] ?: FocusRequester()

                    Tab(
                        selected = isSelected,
                        onFocus = { selectedTab = tabKey },
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                down = FocusRequester.Default
                            },
                        interactionSource = interactionSource
                    ) {
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

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        tabFocusRequesters[selectedTab]?.requestFocus()
                        return@onKeyEvent true
                    }
                    false
                }
        ) {
            items(currentChannels, key = { it.id }) { channel ->
                val isSelected = channel.id == currentChannelId
                val itemRequester =
                    if (isSelected) focusRequester else remember { FocusRequester() }

                ChannelCardItem(
                    channel = channel,
                    isSelected = isSelected,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo, // ★ 修正: クロップフラグを渡す
                    onClick = { onChannelSelect(channel) },
                    modifier = Modifier
                        .focusRequester(itemRequester)
                        .focusProperties {
                            val currentTabRequester = tabFocusRequesters[selectedTab]
                            if (currentTabRequester != null) {
                                up = currentTabRequester
                            }
                        }
                )
            }
        }
    }
}

@Composable
fun ChannelCardItem(
    channel: Channel,
    isSelected: Boolean,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean, // ★ 追加: クロップフラグ
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

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) colors.textPrimary
        else if (isSelected) colors.surface
        else colors.surface.copy(alpha = 0.6f),
        animationSpec = tween(200), label = "bgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
        label = "contentColor"
    )

    val borderWidth = if (isFocused) 3.dp else 0.dp
    val borderColor = if (isFocused) colors.accent else Color.Transparent

    var logoUrl by remember(channel.id) { mutableStateOf<String>("") }
    LaunchedEffect(channel.id) {
        logoUrl = getLogoUrl(channel.id)
    }

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
            Surface(
                modifier = Modifier
                    .size(48.dp, 27.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isFocused) Color.LightGray else Color.White),
                colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logoUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // ★ 修正: フラグに基づいてスケールを変更
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
            }

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