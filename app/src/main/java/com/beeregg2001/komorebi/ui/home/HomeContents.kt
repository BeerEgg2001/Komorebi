@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalIcon
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import java.time.OffsetDateTime

private const val TAG = "HomeContents"

data class HomeHeroInfo(
    val title: String,
    val subtitle: String,
    val description: String = "",
    val imageUrl: String? = null,
    val isThumbnail: Boolean = false,
    val tag: String = "",
    val progress: Float? = null
)

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeContents(
    lastWatchedChannels: List<Channel>,
    watchHistory: List<KonomiHistoryProgram>,
    hotChannels: List<UiChannelState>,
    upcomingReserves: List<ReserveItem>,
    genrePickup: List<Pair<EpgProgram, String>>,
    pickupGenreName: String,
    pickupTimeSlot: String,
    groupedChannels: Map<String, List<Channel>>,
    onChannelClick: (Channel) -> Unit,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onReserveClick: (ReserveItem) -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    konomiIp: String, konomiPort: String,
    mirakurunIp: String, mirakurunPort: String,
    tabFocusRequester: FocusRequester,
    externalFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    lastFocusedChannelId: String? = null,
    lastFocusedProgramId: String? = null,
    isTopNavFocused: Boolean = false
) {
    val isKonomiTvMode =
        mirakurunIp.isEmpty() || mirakurunIp == "localhost" || mirakurunIp == "1270.0.1"
    val lazyListState = rememberTvLazyListState()
    val typeLabels =
        mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")

    val welcomeHeroInfo = remember {
        HomeHeroInfo(
            title = "Komorebi へようこそ",
            subtitle = "ホーム",
            description = "十字キーの「下」を押してコンテンツを選択してください。\n現在放送中の人気番組や、録画した番組の続きをここから楽しめます。",
            tag = "Welcome"
        )
    }

    var pendingHeroInfo by remember { mutableStateOf<HomeHeroInfo?>(welcomeHeroInfo) }
    var currentHeroInfo by remember { mutableStateOf<HomeHeroInfo>(welcomeHeroInfo) }
    var isFirstHeroLoad by remember { mutableStateOf(true) }
    val colors = KomorebiTheme.colors

    LaunchedEffect(isTopNavFocused) {
        if (isTopNavFocused) {
            pendingHeroInfo = welcomeHeroInfo
        }
    }

    LaunchedEffect(pendingHeroInfo) {
        if (pendingHeroInfo != null) {
            if (isFirstHeroLoad) {
                currentHeroInfo = pendingHeroInfo!!
                isFirstHeroLoad = false
            } else {
                delay(300)
                currentHeroInfo = pendingHeroInfo!!
            }
        }
    }

    LaunchedEffect(lastWatchedChannels.isNotEmpty()) {
        if (lastWatchedChannels.isNotEmpty()) {
            lazyListState.scrollToItem(0)
        }
    }

    // ★追加: 現在表示されているセクションのうち、どれが一番上かを判定する
    val topSection =
        remember(lastWatchedChannels, hotChannels, genrePickup, watchHistory, upcomingReserves) {
            when {
                lastWatchedChannels.isNotEmpty() -> "lastWatched"
                hotChannels.isNotEmpty() -> "hot"
                genrePickup.isNotEmpty() -> "pickup"
                watchHistory.isNotEmpty() -> "history"
                upcomingReserves.isNotEmpty() -> "upcoming"
                else -> ""
            }
        }

    // ★修正: focusPropertiesの代わりに、安全なキーイベントでタブへフォーカスを戻す処理
    val upToTabModifier = Modifier.onKeyEvent {
        if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
            it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        ) {
            tabFocusRequester.safeRequestFocus(TAG)
            true
        } else {
            false
        }
    }

    val layoutInfo = lazyListState.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo

    val scrollProgress by remember(totalItemsCount, visibleItems) {
        derivedStateOf {
            if (totalItemsCount == 0 || visibleItems.isEmpty()) {
                0f
            } else {
                val firstVisibleIndex = visibleItems.first().index
                (firstVisibleIndex.toFloat() / totalItemsCount.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val animatedScrollProgress by animateFloatAsState(
        targetValue = scrollProgress,
        animationSpec = tween(300),
        label = "ScrollIndicator"
    )

    Column(modifier = modifier.fillMaxSize()) {
        // --- ヒーローゾーン (上部) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 16.dp)
        ) {
            HomeHeroDashboard(info = currentHeroInfo)
        }

        // --- カルーセルリストゾーン (下部) ---
        Box(modifier = Modifier
            .weight(0.55f)
            .fillMaxWidth()) {
            TvLazyColumn(
                state = lazyListState,
                // ★修正: 下キー押下時に安全にフォーカスを受け取れるよう、TvLazyColumnに直接付与
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(externalFocusRequester),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // 1. 前回視聴したチャンネル
                if (lastWatchedChannels.isNotEmpty()) {
                    item(key = "section_last_watched") {
                        Column(modifier = Modifier.animateContentSize()) {
                            SectionHeader(
                                "前回視聴したチャンネル",
                                Icons.Default.History,
                                Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                // ★ 一番上のリストにのみ上キートラップを付与
                                modifier = if (topSection == "lastWatched") upToTabModifier else Modifier,
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    lastWatchedChannels,
                                    key = { "ch_${it.id}" }) { channel ->
                                    val logoUrl = if (isKonomiTvMode) UrlBuilder.getKonomiTvLogoUrl(
                                        konomiIp, konomiPort, channel.displayChannelId
                                    ) else UrlBuilder.getMirakurunLogoUrl(
                                        mirakurunIp,
                                        mirakurunPort,
                                        channel.networkId,
                                        channel.serviceId
                                    )

                                    val liveChannel = remember(groupedChannels, channel.id) {
                                        groupedChannels.values.flatten()
                                            .find { it.id == channel.id }
                                    }
                                    val programTitle =
                                        liveChannel?.programPresent?.title ?: channel.name
                                    val programDesc = liveChannel?.programPresent?.description
                                        ?: "前回視聴していたチャンネルです。"

                                    LastWatchedChannelCard(
                                        channel = channel,
                                        liveChannel = liveChannel,
                                        logoUrl = logoUrl,
                                        onClick = { onChannelClick(channel) },
                                        onFocus = {
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = programTitle,
                                                subtitle = channel.name,
                                                description = programDesc,
                                                imageUrl = logoUrl,
                                                isThumbnail = false,
                                                tag = "前回視聴"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 今、盛り上がっているチャンネル
                if (hotChannels.isNotEmpty()) {
                    item(key = "section_hot") {
                        Column(modifier = Modifier.animateContentSize()) {
                            SectionHeader(
                                "今、盛り上がっているチャンネル",
                                Icons.Default.TrendingUp,
                                Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                modifier = if (topSection == "hot") upToTabModifier else Modifier,
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(hotChannels, key = { "hot_${it.channel.id}" }) { uiState ->
                                    val logoUrl = UrlBuilder.getKonomiTvLogoUrl(
                                        konomiIp,
                                        konomiPort,
                                        uiState.channel.displayChannelId
                                    )
                                    HotChannelCard(
                                        uiState = uiState,
                                        logoUrl = logoUrl,
                                        onClick = { onChannelClick(uiState.channel) },
                                        onFocus = {
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = uiState.programTitle,
                                                subtitle = uiState.name,
                                                description = uiState.channel.programPresent?.description
                                                    ?: "",
                                                imageUrl = logoUrl,
                                                isThumbnail = false,
                                                tag = "盛り上がり"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. ピックアップ
                if (genrePickup.isNotEmpty()) {
                    item(key = "section_pickup") {
                        Column(modifier = Modifier.animateContentSize()) {
                            val timePrefix = when (pickupTimeSlot) {
                                "朝" -> "今朝の"; "昼" -> "今日の"; else -> "今夜の"
                            }
                            val seasonalIcon = getSeasonalIcon(KomorebiTheme.theme)
                            SectionHeader(
                                "${timePrefix}${pickupGenreName}ピックアップ $seasonalIcon",
                                Icons.Default.Star,
                                Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                modifier = if (topSection == "pickup") upToTabModifier else Modifier,
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    genrePickup,
                                    key = { "pick_${it.first.id}" }) { (program, channelName) ->
                                    GenrePickupCard(
                                        program = program,
                                        channelName = channelName,
                                        timeSlot = pickupTimeSlot,
                                        onClick = { onProgramClick(program) },
                                        onFocus = { startFormat ->
                                            val logoUrl = UrlBuilder.getKonomiTvLogoUrl(
                                                konomiIp,
                                                konomiPort,
                                                program.channel_id
                                            )
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = program.title,
                                                subtitle = "$startFormat - $channelName",
                                                description = program.description,
                                                imageUrl = logoUrl,
                                                isThumbnail = false,
                                                tag = "ピックアップ"
                                            )
                                        }
                                    )
                                }
                            }
                            NavigationLinkButton(
                                "番組表を開く",
                                Icons.Default.CalendarToday,
                                onClick = { onNavigateToTab(3) })
                        }
                    }
                }

                // 4. 録画視聴履歴
                if (watchHistory.isNotEmpty()) {
                    item(key = "section_history") {
                        Column(modifier = Modifier.animateContentSize()) {
                            SectionHeader(
                                "録画の視聴履歴",
                                Icons.Default.PlayCircle,
                                Modifier.padding(start = 48.dp, bottom = 12.dp)
                            )
                            TvLazyRow(
                                modifier = if (topSection == "history") upToTabModifier else Modifier,
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(watchHistory, key = { "hist_${it.program.id}" }) { history ->
                                    WatchHistoryCard(
                                        history = history,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { onHistoryClick(history) },
                                        onFocus = { progressVal, thumbnailUrl ->
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = history.program.title,
                                                subtitle = "視聴履歴から再開",
                                                description = history.program.description,
                                                imageUrl = thumbnailUrl,
                                                isThumbnail = true,
                                                tag = "視聴履歴",
                                                progress = progressVal
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. これからの録画予約
                if (upcomingReserves.isNotEmpty()) {
                    item(key = "section_upcoming") {
                        Column(modifier = Modifier.animateContentSize()) {
                            SectionHeader(
                                "これからの録画予約",
                                Icons.Default.RadioButtonChecked,
                                Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                modifier = if (topSection == "upcoming") upToTabModifier else Modifier,
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(upcomingReserves, key = { "res_${it.id}" }) { reserve ->
                                    val logoUrl = UrlBuilder.getKonomiTvLogoUrl(
                                        konomiIp,
                                        konomiPort,
                                        reserve.channel.displayChannelId ?: ""
                                    )
                                    UpcomingReserveCard(
                                        reserve = reserve,
                                        onClick = { onReserveClick(reserve) },
                                        onFocus = { startFormat ->
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = reserve.program.title,
                                                subtitle = "$startFormat - ${reserve.channel.name}",
                                                description = reserve.program.description ?: "",
                                                imageUrl = logoUrl,
                                                isThumbnail = false,
                                                tag = "録画予約"
                                            )
                                        }
                                    )
                                }
                            }
                            NavigationLinkButton(
                                "録画予約リストを表示",
                                Icons.Default.List,
                                onClick = { onNavigateToTab(4) })
                        }
                    }
                }
            }

            // 縦スクロールインジケーター
            if (totalItemsCount > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp, top = 24.dp, bottom = 24.dp)
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(colors.textPrimary.copy(alpha = 0.1f), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.3f)
                            .offset(
                                y = animateDpAsState(
                                    targetValue = (layoutInfo.viewportSize.height * 0.7f * animatedScrollProgress).dp,
                                    animationSpec = tween(150),
                                    label = "ScrollIndicatorOffset"
                                ).value
                            )
                            .background(colors.textPrimary.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
        }
    }
}

// ---------------- 以下のコンポーネントは前回と同じです ----------------

@Composable
fun HomeHeroDashboard(info: HomeHeroInfo) {
    val colors = KomorebiTheme.colors

    AnimatedContent(
        targetState = info,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label = "HomeHeroTransition",
        modifier = Modifier.fillMaxSize()
    ) { state ->
        Box(modifier = Modifier.fillMaxSize()) {

            if (state.tag == "Welcome") {
                val welcomeImageRes =
                    if (colors.isDark) R.drawable.dark_image else R.drawable.light_image

                Image(
                    painter = painterResource(id = welcomeImageRes),
                    contentDescription = "Welcome Background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.8f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            } else if (state.isThumbnail && state.imageUrl != null) {
                AsyncImage(
                    model = state.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.4f)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.6f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            } else if (!state.isThumbnail && state.imageUrl != null) {
                AsyncImage(
                    model = state.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.35f)
                        .aspectRatio(16f / 9f)
                        .alpha(0.12f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.7f),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.tag != "Welcome" && !state.isThumbnail && state.imageUrl != null) {
                        Box(
                            modifier = Modifier
                                .size(64.dp, 36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.textPrimary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = state.imageUrl,
                                contentDescription = "Logo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = state.tag,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.accent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = state.title,
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

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.heightIn(min = 72.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (state.description.isNotEmpty()) {
                        Text(
                            text = state.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            maxLines = if (state.progress != null) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 24.sp
                        )
                        if (state.progress != null) Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (state.progress != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(280.dp)
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
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "続きから再生",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LastWatchedChannelCard(
    channel: Channel,
    liveChannel: Channel?,
    logoUrl: String,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val typeLabels =
        mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .height(96.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                val programTitle = liveChannel?.programPresent?.title
                if (!programTitle.isNullOrEmpty()) {
                    Text(
                        text = programTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                    )
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                    )
                    Text(
                        text = "${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HotChannelCard(
    uiState: UiChannelState,
    logoUrl: String,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(106.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier
                        .size(6.dp)
                        .background(Color(0xFFE53935), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${uiState.jikkyoForce ?: 0} コメ/分",
                        color = if (isFocused) Color(0xFFE53935) else Color(0xFFE53935).copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = uiState.programTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchHistoryCard(
    history: KonomiHistoryProgram,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    onFocus: (progress: Float, thumbnailUrl: String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val program = history.program
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val progress = remember(history) {
        runCatching {
            val start = Instant.parse(program.start_time).epochSecond
            val end = Instant.parse(program.end_time).epochSecond
            val total = (end - start).toDouble()
            if (total > 0) (history.playback_position / total).toFloat().coerceIn(0f, 1f) else 0f
        }.getOrDefault(0f)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(146.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(progress, thumbnailUrl)
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = if (isFocused) 0.95f else 0.85f)
                            ),
                            startY = 0f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    program.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "続きから再生",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.accent
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(if (isFocused) colors.accent else colors.accent.copy(alpha = 0.6f))
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpcomingReserveCard(
    reserve: ReserveItem,
    onClick: () -> Unit,
    onFocus: (startFormat: String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val start = OffsetDateTime.parse(reserve.program.startTime)
    val startFormat = start.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(240.dp)
            .height(106.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(startFormat)
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    start.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary.copy(0.7f)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .background(
                            if (isFocused) colors.accent.copy(alpha = 0.2f) else colors.textPrimary.copy(
                                0.05f
                            ),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "P${reserve.recordSettings.priority}",
                        fontSize = 10.sp,
                        color = if (isFocused) colors.accent else colors.textPrimary.copy(0.8f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                reserve.program.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(0.9f),
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                reserve.channel.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary.copy(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenrePickupCard(
    program: EpgProgram,
    channelName: String,
    timeSlot: String,
    onClick: () -> Unit,
    onFocus: (startFormat: String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val start = OffsetDateTime.parse(program.start_time)
    val startFormat = start.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

    val baseAlpha = if (isFocused) 1f else 0.6f
    val gradientStartColor = when (timeSlot) {
        "朝" -> Color(0xFFE65100).copy(alpha = 0.2f * baseAlpha)
        "昼" -> Color(0xFF006064).copy(alpha = 0.2f * baseAlpha)
        else -> Color(0xFF1A237E).copy(alpha = 0.2f * baseAlpha)
    }
    val timeColor = when (timeSlot) {
        "朝" -> if (colors.isDark) Color(0xFFFFCC80) else Color(0xFFE65100)
        "昼" -> if (colors.isDark) Color(0xFF81D4FA) else Color(0xFF0277BD)
        else -> if (colors.isDark) Color(0xFFB39DDB) else Color(0xFF311B92)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(116.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(startFormat)
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = baseAlpha),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(gradientStartColor, Color.Transparent)))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "$startFormat - $channelName",
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor.copy(alpha = if (isFocused) 1f else 0.8f),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavigationLinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    Box(modifier = Modifier.padding(start = 48.dp, top = 12.dp)) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.colors(
                containerColor = colors.textPrimary.copy(0.05f),
                contentColor = colors.textPrimary,
                focusedContainerColor = colors.textPrimary,
                focusedContentColor = if (colors.isDark) Color.Black else Color.White
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = colors.textPrimary.copy(0.6f))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}