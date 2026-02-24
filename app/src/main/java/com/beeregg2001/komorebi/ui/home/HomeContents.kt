@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
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
    lastFocusedProgramId: String? = null
) {
    // ★修正：クラッシュ防止のため、FocusRequesterが紐付くTvLazyColumnを即座に表示する
    // delay(600)によるコンポーネントの隠蔽を削除し、常にUIツリーに存在させる
    val isKonomiTvMode = mirakurunIp.isEmpty() || mirakurunIp == "localhost" || mirakurunIp == "127.0.0.1"
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")
    val channelItemRequester = remember { FocusRequester() }
    val lazyListState = rememberTvLazyListState()
    val colors = KomorebiTheme.colors

    LaunchedEffect(lastFocusedChannelId) {
        if (lastFocusedChannelId != null) {
            delay(300)
            channelItemRequester.safeRequestFocus(TAG)
        }
    }

    // TvLazyColumnをAnimatedVisibilityの外に出すことで、フォーカス要求を常に受け入れ可能にする
    TvLazyColumn(
        state = lazyListState,
        modifier = modifier
            .fillMaxSize()
            .focusRequester(externalFocusRequester),
        contentPadding = PaddingValues(top = 24.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // --- 1. 前回視聴したチャンネル ---
        if (lastWatchedChannels.isNotEmpty()) {
            item(key = "section_last_watched") {
                Column(modifier = Modifier.animateContentSize()) {
                    SectionHeader("前回視聴したチャンネル", Icons.Default.History, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(lastWatchedChannels, key = { _, ch -> "ch_${ch.id}" }) { _, channel ->
                            val inverseColor = if (colors.isDark) Color.Black else Color.White
                            val logoUrl = if (isKonomiTvMode) UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
                            else UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.networkId, channel.serviceId)

                            Surface(
                                onClick = { onChannelClick(channel) },
                                modifier = Modifier
                                    .width(220.dp)
                                    .height(100.dp)
                                    .then(if (channel.id == lastFocusedChannelId) Modifier.focusRequester(channelItemRequester) else Modifier)
                                    .focusProperties { up = tabFocusRequester },
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = colors.textPrimary.copy(0.05f),
                                    focusedContainerColor = colors.textPrimary,
                                    contentColor = colors.textPrimary,
                                    focusedContentColor = inverseColor
                                ),
                                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
                            ) {
                                Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(72.dp, 40.dp).clip(RoundedCornerShape(4.dp)).background(colors.background.copy(0.5f)), contentAlignment = Alignment.Center) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current).data(logoUrl).size(coil.size.Size(144, 80)).build(),
                                            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis)
                                        Text("${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber}", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(0.7f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 2. 今、盛り上がっているチャンネル ---
        if (hotChannels.isNotEmpty()) {
            item(key = "section_hot") {
                Column(modifier = Modifier.animateContentSize()) {
                    SectionHeader("今、盛り上がっているチャンネル", Icons.Default.TrendingUp, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(hotChannels, key = { "hot_${it.channel.id}" }) { uiState ->
                            HotChannelCard(uiState, konomiIp, konomiPort, onClick = { onChannelClick(uiState.channel) })
                        }
                    }
                }
            }
        }

        // --- 3. これからの録画予約 ---
        if (upcomingReserves.isNotEmpty()) {
            item(key = "section_upcoming") {
                Column(modifier = Modifier.animateContentSize()) {
                    SectionHeader("これからの録画予約", Icons.Default.RadioButtonChecked, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(upcomingReserves, key = { "res_${it.id}" }) { reserve ->
                            UpcomingReserveCard(reserve, onClick = { onReserveClick(reserve) })
                        }
                    }
                    NavigationLinkButton("録画予約リストを表示", Icons.Default.List, onClick = { onNavigateToTab(4) })
                }
            }
        }

        // --- 4. ピックアップ ---
        if (genrePickup.isNotEmpty()) {
            item(key = "section_pickup") {
                Column(modifier = Modifier.animateContentSize()) {
                    val timePrefix = when (pickupTimeSlot) { "朝" -> "今朝の"; "昼" -> "今日の"; else -> "今夜の" }
                    val seasonalIcon = getSeasonalIcon(KomorebiTheme.theme)
                    SectionHeader("${timePrefix}${pickupGenreName}ピックアップ $seasonalIcon", Icons.Default.Star, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(genrePickup, key = { "pick_${it.first.id}" }) { (program, channelName) ->
                            GenrePickupCard(program = program, channelName = channelName, timeSlot = pickupTimeSlot, onClick = { onProgramClick(program) })
                        }
                    }
                    NavigationLinkButton("番組表を開く", Icons.Default.CalendarToday, onClick = { onNavigateToTab(3) })
                }
            }
        }

        // --- 5. 録画視聴履歴 ---
        if (watchHistory.isNotEmpty()) {
            item(key = "section_history") {
                Column(modifier = Modifier.animateContentSize()) {
                    SectionHeader("録画の視聴履歴", Icons.Default.PlayCircle, Modifier.padding(start = 32.dp, bottom = 12.dp))
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(watchHistory, key = { "hist_${it.program.id}" }) { history ->
                            WatchHistoryCard(history, konomiIp, konomiPort, onClick = { onHistoryClick(history) })
                        }
                    }
                }
            }
        }
    }
}

// ... 以降のコンポーネント (HotChannelCard 等) は変更なし

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HotChannelCard(
    uiState: UiChannelState,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val logoUrl =
        UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, uiState.channel.displayChannelId)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(110.dp)
            .onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.05f),
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
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
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.background.copy(0.5f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(logoUrl)
                        .size(coil.size.Size(144, 80)).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(Color(0xFFD32F2F), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${uiState.jikkyoForce ?: 0} コメ/分",
                        color = if (isFocused) inverseColor.copy(alpha = 0.8f) else Color(0xFFFF5252),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (isFocused) inverseColor else colors.textPrimary).copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = uiState.programTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) inverseColor else colors.textPrimary
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavigationLinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    Box(modifier = Modifier.padding(start = 32.dp, top = 12.dp)) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.colors(
                containerColor = colors.textPrimary.copy(0.05f),
                contentColor = colors.textPrimary,
                focusedContainerColor = colors.textPrimary,
                focusedContentColor = inverseColor
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

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenrePickupCard(
    program: EpgProgram,
    channelName: String,
    timeSlot: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val start = OffsetDateTime.parse(program.start_time)

    val gradientStartColor = when (timeSlot) {
        "朝" -> Color(0xFFE65100).copy(alpha = 0.3f)
        "昼" -> Color(0xFF006064).copy(alpha = 0.3f)
        else -> Color(0xFF1A237E).copy(alpha = 0.3f)
    }

    // ★修正: ライトモード(背景が明るい時)は、コントラストが強くなるようにより濃い色を指定
    val timeColor = when (timeSlot) {
        "朝" -> if (colors.isDark) Color(0xFFFFCC80) else Color(0xFFE65100) // 深いオレンジ
        "昼" -> if (colors.isDark) Color(0xFF81D4FA) else Color(0xFF0277BD) // 深いブルー
        else -> if (colors.isDark) Color(0xFFB39DDB) else Color(0xFF311B92) // 深いインディゴ (夜用カラーの分離)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(120.dp)
            .onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(gradientStartColor, Color.Transparent))
                )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "${start.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))} - $channelName",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) LocalContentColor.current.copy(0.7f) else timeColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = LocalContentColor.current,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpcomingReserveCard(reserve: ReserveItem, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val start = OffsetDateTime.parse(reserve.program.startTime)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(240.dp)
            .height(110.dp)
            .onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    start.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(0.7f)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .background(
                            if (isFocused) inverseColor.copy(0.2f) else colors.textPrimary.copy(
                                0.1f
                            ), RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "P${reserve.recordSettings.priority}",
                        fontSize = 10.sp,
                        color = LocalContentColor.current
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                reserve.program.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                color = LocalContentColor.current
            )
            Spacer(Modifier.weight(1f))
            Text(
                reserve.channel.name,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(0.6f)
            )
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
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val inverseColor = if (colors.isDark) Color.Black else Color.White
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
            .height(150.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .size(coil.size.Size(360, 200))
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                if (isFocused) colors.textPrimary.copy(0.9f) else colors.background.copy(
                                    0.85f
                                )
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "続きから再生",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(0.7f)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(colors.textSecondary.copy(0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(colors.accent)
                )
            }
        }
    }
}