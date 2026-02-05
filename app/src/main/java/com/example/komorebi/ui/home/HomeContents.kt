package com.example.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.PivotOffsets
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.komorebi.data.model.KonomiHistoryProgram
import com.example.komorebi.data.model.getThumbnailUrl
import com.example.komorebi.viewmodel.Channel
import java.time.Instant
import androidx.tv.foundation.lazy.list.itemsIndexed
import com.example.komorebi.buildStreamId

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
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
    tabFocusRequester: FocusRequester,    // ナビゲーションの「ホーム」タブ
    externalFocusRequester: FocusRequester // コンテンツへの入り口
) {
    fun getLogoUrl(channel: Channel): String {
        val url = "http://$mirakurunIp:$mirakurunPort/api/services/${buildStreamId(channel)}/logo"
        Log.d("DEBUG", "Logo URL: $url")
        return url
    }
    val typeLabels = mapOf(
        "GR" to "地デジ",
        "BS" to "BS",
        "CS" to "CS",
        "BS4K" to "BS4K",
        "SKY" to "スカパー"
    )

    TvLazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        pivotOffsets = PivotOffsets(parentFraction = 0.15f)
    ) {
        item {
            Column {
                SectionHeader(
                    title = "前回視聴したチャンネル",
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (lastWatchedChannels.isNotEmpty()) {
                    TvLazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        pivotOffsets = PivotOffsets(parentFraction = 0.5f)
                    ) {
                        itemsIndexed(lastWatchedChannels) { index, channel ->
                            var isFocused by remember { mutableStateOf(false) }

                            Surface(
                                onClick = { onChannelClick(channel) },
                                modifier = Modifier
                                    .width(200.dp) // 横並びにするため、幅を少し広げる（180->200）とゆとりが出ます
                                    .height(100.dp)
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .then(if (index == 0) Modifier.focusRequester(externalFocusRequester) else Modifier)
                                    .focusProperties { up = tabFocusRequester },
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(0.1f),
                                    focusedContainerColor = Color.White
                                ),
                                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
                            ) {
                                // ★ Row を使って「ロゴ」と「テキスト」を横に並べる
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp), // 全体のパディングを少し詰め
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 1. 左側の局ロゴ（サイズアップ & 背景なし）
                                    AsyncImage(
                                        model = getLogoUrl(channel),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(64.dp) // 48dpから64dpへ拡大
                                            // .background(...) を削除
                                            .padding(2.dp), // 最小限の余白
                                        contentScale = ContentScale.Fit
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // 2. 右側のテキスト情報
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = channel.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isFocused) Color.Black else Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Bold,
                                            lineHeight = 16.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val displayType = typeLabels[channel.type] ?: channel.type
                                        Text(
                                            text = "$displayType ${channel.channelNumber ?: ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(
                                                0.7f
                                            )
                                        )
                                    }
                                }
                            }
                            }
                        }
                } else {
                    // プレースホルダー (ここも padding を調整)
                    Box(modifier = Modifier.padding(horizontal = 32.dp)) {
                        Surface(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.width(240.dp).height(80.dp),
                            colors = ClickableSurfaceDefaults.colors(
                                disabledContainerColor = Color.White.copy(0.05f)
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("最近視聴した番組はありません", color = Color.White.copy(0.5f))
                            }
                        }
                    }
                }
            }
        }

        // --- 2. 録画視聴履歴 ---
        item {
            Column {
                SectionHeader(title = "録画の視聴履歴", modifier = Modifier.padding(start = 32.dp, bottom = 12.dp))

                TvLazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    pivotOffsets = PivotOffsets(parentFraction = 0.5f)
                ) {
                    if (watchHistory.isNotEmpty()) {
                        itemsIndexed(watchHistory) { index, history ->
                            WatchHistoryCard(
                                history = history,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                onClick = { onHistoryClick(history) },
                                modifier = Modifier.focusProperties {
                                    // 1行目にデータがない場合などの保険。必要に応じて追加可能
                                }
                            )
                        }
                    } else {
                        items(5) { DummyHistoryCard() }
                    }
                }
            }
        }
    }
}

// DummyHistoryCard, SectionHeader は変更なし（省略）

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchHistoryCard(
    history: KonomiHistoryProgram,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier // Modifierを受け取れるように拡張
) {
    var isFocused by remember { mutableStateOf(false) }
    val program = history.program
    val thumbnailUrl = getThumbnailUrl(history.program.id, konomiIp, konomiPort)

    val progress = remember(history) {
        try {
            val start = Instant.parse(program.start_time).epochSecond
            val end = Instant.parse(program.end_time).epochSecond
            val total = (end - start).toDouble()
            if (total > 0) (history.playback_position / total).toFloat().coerceIn(0f, 1f) else 0f
        } catch (e: Exception) {
            0f
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(240.dp)
            .height(140.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.DarkGray,
            focusedContainerColor = Color.White
        )
    ) {
        // ... (内部レイアウトは既存のまま)
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                if (isFocused) Color.White.copy(0.7f) else Color.Black.copy(0.8f)
                            )
                        )
                    )
            )
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isFocused) Color.Black else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "続きから再生",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f)
                )
            }
            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Gray.copy(0.5f))) {
                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(if (isFocused) Color.Black else Color.Red))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DummyHistoryCard() {
    Surface(
        onClick = {},
        enabled = false,
        modifier = Modifier.width(240.dp).height(140.dp),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        colors = ClickableSurfaceDefaults.colors(
            disabledContainerColor = Color.White.copy(alpha = 0.05f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("", color = Color.DarkGray)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier
    )
}