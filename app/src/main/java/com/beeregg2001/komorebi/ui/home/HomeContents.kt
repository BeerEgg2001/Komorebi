@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
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
import com.beeregg2001.komorebi.viewmodel.Channel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private const val TAG = "HomeContents"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeContents(
    // ... 引数は変更なし
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
    konomiIp: String, konomiPort: String, mirakurunIp: String, mirakurunPort: String,
    tabFocusRequester: FocusRequester, externalFocusRequester: FocusRequester, modifier: Modifier = Modifier,
    lastFocusedChannelId: String? = null, lastFocusedProgramId: String? = null
) {
    val colors = KomorebiTheme.colors // ★追加
    val isKonomiTvMode = mirakurunIp.isEmpty() || mirakurunIp == "localhost" || mirakurunIp == "127.0.0.1"
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")
    val channelItemRequester = remember { FocusRequester() }
    val lazyListState = rememberTvLazyListState()

    LaunchedEffect(lastFocusedChannelId, lastFocusedProgramId) {
        if (lastFocusedChannelId != null || lastFocusedProgramId != null) {
            delay(300)
            if (lastFocusedChannelId != null) channelItemRequester.safeRequestFocus(TAG)
        }
    }

    TvLazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize().focusRequester(externalFocusRequester),
        contentPadding = PaddingValues(top = 24.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        if (lastWatchedChannels.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("前回視聴したチャンネル", Icons.Default.History, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(lastWatchedChannels, key = { _, ch -> "ch_${ch.id}" }) { _, channel ->
                            var isFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { onChannelClick(channel) },
                                modifier = Modifier.width(220.dp).height(100.dp).onFocusChanged { isFocused = it.isFocused }
                                    .then(if (channel.id == lastFocusedChannelId) Modifier.focusRequester(channelItemRequester) else Modifier)
                                    .focusProperties { up = tabFocusRequester },
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                                // ★修正: ハードコードを除去
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = colors.surface,
                                    focusedContainerColor = colors.textPrimary,
                                    contentColor = colors.textPrimary,
                                    focusedContentColor = if(colors.isDark) Color.Black else Color.White
                                ),
                                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
                            ) {
                                Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(72.dp, 40.dp).background(colors.textPrimary.copy(0.1f)), contentAlignment = Alignment.Center) { // ★修正
                                        val logoUrl = if (isKonomiTvMode) UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
                                        else UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.networkId, channel.serviceId)
                                        AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = if (isKonomiTvMode) ContentScale.Crop else ContentScale.Fit)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis)
                                        Text(text = "${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber}", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(0.7f)) // ★修正
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hotChannels.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("今、盛り上がっているチャンネル", Icons.Default.TrendingUp, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(hotChannels) { uiState -> HotChannelCard(uiState, konomiIp, konomiPort, onClick = { onChannelClick(uiState.channel) }) }
                    }
                }
            }
        }

        if (upcomingReserves.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("これからの録画予約", Icons.Default.RadioButtonChecked, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(upcomingReserves) { reserve -> UpcomingReserveCard(reserve, onClick = { onReserveClick(reserve) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    NavigationLinkButton("録画予約リストを表示", Icons.Default.List, onClick = { onNavigateToTab(4) })
                }
            }
        }

        if (genrePickup.isNotEmpty()) {
            item {
                Column {
                    val timePrefix = when(pickupTimeSlot) { "朝" -> "今朝の"; "昼" -> "今日の"; else -> "今夜の" }
                    SectionHeader("${timePrefix}${pickupGenreName}ピックアップ", Icons.Default.Star, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(genrePickup) { (program, channelName) -> GenrePickupCard(program = program, channelName = channelName, timeSlot = pickupTimeSlot, onClick = { onProgramClick(program) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    NavigationLinkButton("番組表を開く", Icons.Default.CalendarToday, onClick = { onNavigateToTab(3) })
                }
            }
        }

        if (watchHistory.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("録画の視聴履歴", Icons.Default.PlayCircle, Modifier.padding(start = 32.dp, bottom = 12.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(watchHistory, key = { "hist_${it.program.id}" }) { history -> WatchHistoryCard(history, konomiIp, konomiPort, onClick = { onHistoryClick(history) }) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HotChannelCard(uiState: UiChannelState, konomiIp: String, konomiPort: String, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors // ★追加
    var isFocused by remember { mutableStateOf(false) }
    val liveThumbnailUrl = UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, uiState.channel.displayChannelId)

    Surface(
        onClick = onClick, modifier = Modifier.width(300.dp).height(168.dp).onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface, focusedContainerColor = colors.textPrimary), // ★修正
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(liveThumbnailUrl).size(coil.size.Size(400, 225)).crossfade(true).memoryCachePolicy(CachePolicy.ENABLED).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // ★修正: テーマベースのグラデーション
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (isFocused) colors.textPrimary.copy(0.9f) else colors.background.copy(0.85f)))))
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(colors.accent, CircleShape)); Spacer(Modifier.width(8.dp)) // ★修正
                    Text(text = "${uiState.jikkyoForce ?: 0} コメント/分", color = if(isFocused) (if(colors.isDark) Color.Black else Color.White) else colors.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) // ★修正
                }
                Spacer(Modifier.weight(1f))
                val contentColor = if(isFocused) (if(colors.isDark) Color.Black else Color.White) else colors.textPrimary // ★反転色
                Text(uiState.name, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(0.7f))
                Text(uiState.programTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, color = contentColor)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenrePickupCard(program: EpgProgram, channelName: String, timeSlot: String, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors // ★追加
    var isFocused by remember { mutableStateOf(false) }
    val start = OffsetDateTime.parse(program.start_time)
    val gradientStartColor = when (timeSlot) { "朝" -> Color(0xFFE65100).copy(0.3f); "昼" -> Color(0xFF006064).copy(0.3f); else -> Color(0xFF1A237E).copy(0.3f) }

    Surface(
        onClick = onClick, modifier = Modifier.width(260.dp).height(120.dp).onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface, focusedContainerColor = colors.textPrimary, contentColor = colors.textPrimary, focusedContentColor = if(colors.isDark) Color.Black else Color.White), // ★修正
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(gradientStartColor, Color.Transparent)))) {
            Column(Modifier.padding(16.dp)) {
                Text(text = "${start.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))} - $channelName", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(0.8f), fontWeight = FontWeight.Bold) // ★修正
                Spacer(Modifier.height(8.dp))
                Text(text = program.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpcomingReserveCard(reserve: ReserveItem, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors // ★追加
    var isFocused by remember { mutableStateOf(false) }
    val start = OffsetDateTime.parse(reserve.program.startTime)

    Surface(
        onClick = onClick, modifier = Modifier.width(240.dp).height(110.dp).onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface, focusedContainerColor = colors.textPrimary, contentColor = colors.textPrimary, focusedContentColor = if(colors.isDark) Color.Black else Color.White), // ★修正
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(start.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(0.7f)) // ★修正
                Spacer(Modifier.weight(1f))
                Box(Modifier.background(LocalContentColor.current.copy(0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { // ★修正
                    Text("P${reserve.recordSettings.priority}", fontSize = 10.sp, color = LocalContentColor.current)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(reserve.program.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Text(reserve.channel.name, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(0.5f))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavigationLinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors // ★追加
    Box(modifier = Modifier.padding(start = 32.dp)) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.colors(containerColor = colors.surface, contentColor = colors.textPrimary, focusedContainerColor = colors.textPrimary, focusedContentColor = if(colors.isDark) Color.Black else Color.White), // ★修正
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), modifier = Modifier.height(40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors // ★追加
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = colors.textSecondary) // ★修正
        Spacer(Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary) // ★修正
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchHistoryCard(history: KonomiHistoryProgram, konomiIp: String, konomiPort: String, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors // ★追加
    var isFocused by remember { mutableStateOf(false) }
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
        onClick = onClick, modifier = Modifier.width(260.dp).height(150.dp).onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface, focusedContainerColor = colors.textPrimary) // ★修正
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(thumbnailUrl).size(coil.size.Size(360, 200)).crossfade(true).memoryCachePolicy(CachePolicy.ENABLED).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // ★修正: テーマベースのグラデーション
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (isFocused) colors.textPrimary.copy(0.9f) else colors.background.copy(0.8f)))))
            val contentColor = if(isFocused) (if(colors.isDark) Color.Black else Color.White) else colors.textPrimary
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(program.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("続きから再生", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(0.7f))
            }
            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(colors.textSecondary.copy(0.3f))) { // ★修正
                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accent)) // ★修正
            }
        }
    }
}