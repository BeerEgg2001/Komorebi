@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.BaseballGameInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun BaseballDashboardScreen(
    groupedGames: List<Pair<String, List<BaseballGameInfo>>>,
    groupedChannels: Map<String, List<Channel>>,
    dateOffset: Int, // ★追加: 日付オフセット（0, 1, 2）
    onDateOffsetChange: (Int) -> Unit, // ★追加: 日付変更コールバック
    onChannelClick: (Channel) -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onUiReady: suspend () -> Unit
) {
    val colors = KomorebiTheme.colors

    LaunchedEffect(Unit) {
        onUiReady()
    }

    // ★追加: 現在選択されている日付（オフセットから算出）
    val targetDate = remember(dateOffset) {
        val now = OffsetDateTime.now()
        val base = if (now.hour < 4) now.minusDays(1) else now
        base.plusDays(dateOffset.toLong())
    }

    // ★追加: 日付の表示用フォーマット（例: "3月18日(水)"）
    val dateStr = remember(targetDate) {
        val formatter = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)
        targetDate.format(formatter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp)
    ) {
        // ==========================================
        // ★追加: 日付ナビゲーションヘッダー
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 前日ボタン (offset > 0 のみ有効)
            val prevBtnModifier = if (groupedGames.isEmpty() && dateOffset == 2) {
                // 試合がない日かつ、明後日から明日に戻った直後なら、このボタンに初期フォーカスを当てる
                Modifier.focusRequester(contentFirstItemRequester)
            } else Modifier

            Button(
                onClick = { onDateOffsetChange(dateOffset - 1) },
                enabled = dateOffset > 0,
                colors = ButtonDefaults.colors(
                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textPrimary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                ),
                modifier = prevBtnModifier.focusProperties { up = topNavFocusRequester }
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "前日")
                Spacer(Modifier.width(8.dp))
                Text("前日", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(32.dp))

            Text(
                text = dateStr,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )

            Spacer(Modifier.width(32.dp))

            // 翌日ボタン (offset < 2 のみ有効)
            val nextBtnModifier = if (groupedGames.isEmpty() && dateOffset < 2) {
                // 試合がない日で、明日以降に進める状態ならこのボタンに初期フォーカスを当てる
                Modifier.focusRequester(contentFirstItemRequester)
            } else Modifier

            Button(
                onClick = { onDateOffsetChange(dateOffset + 1) },
                enabled = dateOffset < 2,
                colors = ButtonDefaults.colors(
                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textPrimary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                ),
                modifier = nextBtnModifier.focusProperties { up = topNavFocusRequester }
            ) {
                Text("翌日", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = "翌日")
            }
        }

        // ==========================================
        // リストコンテンツ
        // ==========================================
        if (groupedGames.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "この日の放送予定はありません",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textSecondary.copy(alpha = 0.6f)
                )
            }
        } else {
            TvLazyColumn(
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(40.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(groupedGames) { teamIndex, (teamName, teamGames) ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = teamName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )

                        TvLazyRow(
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            itemsIndexed(teamGames) { gameIndex, game ->
                                // 試合がある場合は、一番最初のカードに初期フォーカスを当てる
                                val modifier = if (teamIndex == 0 && gameIndex == 0) {
                                    Modifier.focusRequester(contentFirstItemRequester)
                                } else Modifier

                                BaseballGameCard(
                                    game = game,
                                    onClick = {
                                        val now = OffsetDateTime.now()
                                        val start =
                                            runCatching { OffsetDateTime.parse(game.program.start_time) }.getOrNull()
                                                ?: now
                                        val end =
                                            runCatching { OffsetDateTime.parse(game.program.end_time) }.getOrNull()
                                                ?: now

                                        if (now.isAfter(start) && now.isBefore(end)) {
                                            val channel = Channel(
                                                id = game.channel.id,
                                                name = game.channel.name,
                                                type = game.channel.type,
                                                channelNumber = game.channel.channel_number ?: "",
                                                displayChannelId = game.channel.display_channel_id,
                                                networkId = game.channel.network_id.toLong(),
                                                serviceId = game.channel.service_id.toLong(),
                                                isWatchable = true,
                                                isDisplay = true,
                                                programPresent = null,
                                                programFollowing = null,
                                                remocon_Id = 0
                                            )
                                            onChannelClick(channel)
                                        } else {
                                            onProgramClick(game.program)
                                        }
                                    },
                                    modifier = modifier.width(380.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun BaseballGameCard(
    game: BaseballGameInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val now = OffsetDateTime.now()
    val start = runCatching { OffsetDateTime.parse(game.program.start_time) }.getOrNull() ?: now
    val end = runCatching { OffsetDateTime.parse(game.program.end_time) }.getOrNull() ?: now
    val isLive = now.isAfter(start) && now.isBefore(end)
    val isFinished = now.isAfter(end)

    val statusText = when {
        isLive -> "🔴 放送中"
        isFinished -> "放送終了"
        else -> "放送予定"
    }
    val statusColor = if (isLive) Color(0xFFFF5252) else colors.textSecondary

    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    val timeStr = "${start.format(formatter)} - ${end.format(formatter)}"

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.8f),
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFocused) Color.Unspecified else statusColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFocused) Color.Unspecified else colors.textSecondary
                )
            }

            Text(
                text = game.program.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .then(if (isFocused) Modifier.basicMarquee(initialDelayMillis = 1500) else Modifier),
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(
                    model = game.logoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .width(48.dp)
                        .height(27.dp),
                    contentScale = ContentScale.Crop
                )
                Text(
                    text = game.channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFocused) Color.Unspecified else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}