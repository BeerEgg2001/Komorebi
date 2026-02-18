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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.viewmodel.Channel
import com.beeregg2001.komorebi.common.safeRequestFocus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

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
    onChannelClick: (Channel) -> Unit,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onReserveClick: (ReserveItem) -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
    konomiIp: String,
    konomiPort: String,
    mirakurunIp: String,
    mirakurunPort: String,
    tabFocusRequester: FocusRequester,
    externalFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    lastFocusedChannelId: String? = null,
    lastFocusedProgramId: String? = null
) {
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
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        // 1. 前回視聴したチャンネル (既存 + ロゴクロップ)
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
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.1f), focusedContainerColor = Color.White),
                                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
                            ) {
                                Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(72.dp, 40.dp).background(Color.Black.copy(0.2f)), contentAlignment = Alignment.Center) {
                                        val logoUrl = if (isKonomiTvMode) UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
                                        else UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.networkId, channel.serviceId)
                                        // ★KonomiTVソースなら上下をカットして16:9にする
                                        AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = if (isKonomiTvMode) ContentScale.Crop else ContentScale.Fit)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = channel.name, style = MaterialTheme.typography.titleSmall, color = if (isFocused) Color.Black else Color.White, maxLines = 2, fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis)
                                        Text(text = "${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber}", style = MaterialTheme.typography.labelSmall, color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 今、盛り上がっているチャンネル
        if (hotChannels.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("今、盛り上がっているチャンネル", Icons.Default.TrendingUp, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(hotChannels) { uiState ->
                            var isFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { onChannelClick(uiState.channel) },
                                modifier = Modifier.width(280.dp).height(120.dp).onFocusChanged { isFocused = it.isFocused },
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.05f), focusedContainerColor = Color.White),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).background(Color(0xFFD32F2F), CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                        Text("${uiState.jikkyoForce ?: 0} コメント/分", color = if(isFocused) Color.Black else Color(0xFFD32F2F), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(uiState.name, style = MaterialTheme.typography.labelMedium, color = if(isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.5f))
                                    Text(uiState.programTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, color = if(isFocused) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. これからの録画予約
        if (upcomingReserves.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("これからの録画予約", Icons.Default.RadioButtonChecked, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(upcomingReserves) { reserve ->
                            var isFocused by remember { mutableStateOf(false) }
                            val start = OffsetDateTime.parse(reserve.program.startTime)
                            Surface(
                                onClick = { onReserveClick(reserve) },
                                modifier = Modifier.width(240.dp).height(110.dp).onFocusChanged { isFocused = it.isFocused },
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.05f), focusedContainerColor = Color.White),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(start.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelSmall, color = if(isFocused) Color.Black.copy(0.6f) else Color.LightGray)
                                        Spacer(Modifier.weight(1f))
                                        Box(Modifier.background(if(isFocused) Color.Black else Color.White.copy(0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                            Text("P${reserve.recordSettings.priority}", fontSize = 10.sp, color = if(isFocused) Color.White else Color.White.copy(0.8f))
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(reserve.program.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2, color = if(isFocused) Color.Black else Color.White)
                                    Spacer(Modifier.weight(1f))
                                    Text(reserve.channel.name, style = MaterialTheme.typography.labelSmall, color = if(isFocused) Color.Black.copy(0.5f) else Color.White.copy(0.4f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. ジャンル別ピックアップ
        if (genrePickup.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("今夜の${pickupGenreName}ピックアップ", Icons.Default.Star, Modifier.padding(horizontal = 32.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(genrePickup) { (program, channelName) ->
                            var isFocused by remember { mutableStateOf(false) }
                            val start = OffsetDateTime.parse(program.start_time)
                            Surface(
                                onClick = { onProgramClick(program) },
                                modifier = Modifier.width(220.dp).height(100.dp).onFocusChanged { isFocused = it.isFocused },
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.05f), focusedContainerColor = Color.White),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${start.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))} - $channelName", style = MaterialTheme.typography.labelSmall, color = if(isFocused) Color.Black.copy(0.6f) else Color.Cyan.copy(0.7f))
                                    Spacer(Modifier.height(4.dp))
                                    Text(program.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2, color = if(isFocused) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. 録画の視聴履歴 (既存)
        if (watchHistory.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("録画の視聴履歴", Icons.Default.PlayCircle, Modifier.padding(start = 32.dp, bottom = 12.dp))
                    TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(watchHistory, key = { "hist_${it.program.id}" }) { history ->
                            WatchHistoryCard(history, konomiIp, konomiPort, onClick = { onHistoryClick(history) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = Color.White.copy(0.6f))
        Spacer(Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchHistoryCard(history: KonomiHistoryProgram, konomiIp: String, konomiPort: String, onClick: () -> Unit) {
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
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.DarkGray, focusedContainerColor = Color.White)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (isFocused) Color.White.copy(0.9f) else Color.Black.copy(0.8f)))))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(program.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = if (isFocused) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("続きから再生", style = MaterialTheme.typography.labelSmall, color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f))
            }
            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Gray.copy(0.3f))) {
                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(if (isFocused) Color.Black else Color.Red))
            }
        }
    }
}