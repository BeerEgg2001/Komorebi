package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.ui.components.ChannelLogo
import com.beeregg2001.komorebi.ui.components.isKonomiTvMode
import com.beeregg2001.komorebi.viewmodel.Channel
import java.time.Instant
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeContents(
    lastWatchedChannels: List<Channel>,
    watchHistory: List<KonomiHistoryProgram>,
    onChannelClick: (Channel) -> Unit,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    konomiIp: String,
    konomiPort: String,
    mirakurunIp: String,
    mirakurunPort: String,
    modifier: Modifier = Modifier,
    tabFocusRequester: FocusRequester,
    externalFocusRequester: FocusRequester,
    lastFocusedChannelId: String? = null,
    lastFocusedProgramId: String? = null
) {
    val isKonomiTvMode = mirakurunIp.isEmpty() || mirakurunIp == "localhost" || mirakurunIp == "127.0.0.1"
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")

    TvLazyColumn(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(externalFocusRequester),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // 前回視聴したチャンネル
        item {
            Column {
                SectionHeader(title = "前回視聴したチャンネル", modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(modifier = Modifier.height(12.dp))

                if (lastWatchedChannels.isNotEmpty()) {
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(lastWatchedChannels) { index, channel ->
                            var isFocused by remember { mutableStateOf(false) }

                            val itemRequester = remember { FocusRequester() }
                            val isTarget = channel.id == lastFocusedChannelId

                            // ★修正: 自動でフォーカスを奪うロジックを削除（トップナビに留まるようにするため）

                            Surface(
                                onClick = { onChannelClick(channel) },
                                modifier = Modifier
                                    .width(220.dp).height(100.dp)
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .then(
                                        if (isTarget) Modifier.focusRequester(itemRequester)
                                        else Modifier
                                    )
                                    .focusProperties {
                                        up = tabFocusRequester
                                        if (index == 0) left = FocusRequester.Cancel
                                        if (index == lastWatchedChannels.lastIndex) right = FocusRequester.Cancel
                                    },
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(0.1f),
                                    focusedContainerColor = Color.White
                                ),
                                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
                            ) {
                                Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp, 40.dp)
                                            .background(Color.Black.copy(0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val logoUrl = if (isKonomiTvMode) {
                                            UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
                                        } else {
                                            UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.networkId, channel.serviceId)
                                        }

                                        AsyncImage(
                                            model = logoUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = if (isKonomiTvMode) ContentScale.Crop else ContentScale.Fit
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(channel.name, style = MaterialTheme.typography.titleSmall, color = if (isFocused) Color.Black else Color.White, maxLines = 2, fontWeight = FontWeight.Bold)
                                        Text("${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber ?: ""}", style = MaterialTheme.typography.labelSmall, color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    EmptyPlaceholder("最近視聴した番組はありません")
                }
            }
        }

        // 録画の視聴履歴
        item {
            Column {
                SectionHeader(title = "録画の視聴履歴", modifier = Modifier.padding(start = 32.dp, bottom = 12.dp))

                if (watchHistory.isNotEmpty()) {
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(watchHistory) { index, history ->
                            val itemRequester = remember { FocusRequester() }
                            val isTarget = history.program.id == lastFocusedProgramId

                            // ★修正: 自動でフォーカスを奪うロジックを削除

                            WatchHistoryCard(
                                history = history,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                onClick = { onHistoryClick(history) },
                                modifier = Modifier
                                    .then(
                                        if (isTarget) Modifier.focusRequester(itemRequester)
                                        else Modifier
                                    )
                                    .focusProperties {
                                        if (index == 0) left = FocusRequester.Cancel
                                        if (index == watchHistory.lastIndex) right = FocusRequester.Cancel
                                    }
                            )
                        }
                    }
                } else {
                    EmptyPlaceholder("視聴履歴はありません")
                }
            }
        }
    }
}

// 以下の Composable 関数は既存のまま維持
@Composable
fun EmptyPlaceholder(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 32.dp)) {
        Surface(
            onClick = {}, enabled = false,
            modifier = Modifier.width(280.dp).height(80.dp),
            colors = ClickableSurfaceDefaults.colors(disabledContainerColor = Color.White.copy(0.05f)),
            shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(message, color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodyMedium)
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
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val program = history.program

    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val progress = remember(history) {
        try {
            val start = Instant.parse(program.start_time).epochSecond
            val end = Instant.parse(program.end_time).epochSecond
            val total = (end - start).toDouble()
            if (total > 0) (history.playback_position / total).toFloat().coerceIn(0f, 1f) else 0f
        } catch (e: Exception) { 0f }
    }

    Surface(
        onClick = onClick,
        modifier = modifier.width(260.dp).height(150.dp).onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.DarkGray, focusedContainerColor = Color.White)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(
                colors = listOf(Color.Transparent, if (isFocused) Color.White.copy(0.9f) else Color.Black.copy(0.8f))
            )))
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

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), modifier = modifier)
}