@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
// ★ 変更: TvLazy 系のインポートを削除し、標準の Lazy 系パッケージに変更しました
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
    dateOffset: Int,
    onDateOffsetChange: (Int) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onUiReady: suspend () -> Unit,
    timeFormat: String = "24H"
) {
    val colors = KomorebiTheme.colors

    LaunchedEffect(Unit) {
        onUiReady()
    }

    val targetDate = remember(dateOffset) {
        val now = OffsetDateTime.now()
        val base = if (now.hour < 4) now.minusDays(1) else now
        base.plusDays(dateOffset.toLong())
    }

    val dateStr = remember(targetDate) {
        val formatter = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)
        targetDate.format(formatter)
    }

    // ★ 修正: 画面全体をくるむ Column に contentFirstItemRequester と focusGroup() を付与。
    // LazyRow の中身が破棄されても、親コンテナが安全にフォーカスを受け止め、内部の適切な子へフォーカスを流すためクラッシュしません。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp)
            .focusRequester(contentFirstItemRequester)
            .focusGroup()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onDateOffsetChange(dateOffset - 1) },
                enabled = dateOffset > 0,
                colors = ButtonDefaults.colors(
                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textPrimary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                ),
                // ★ 修正: 直接的な Requester 割り当てを削除し、純粋なフォーカス移動のプロパティのみにする
                modifier = Modifier.focusProperties {
                    up = topNavFocusRequester
                    left = FocusRequester.Cancel
                }
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

            Button(
                onClick = { onDateOffsetChange(dateOffset + 1) },
                enabled = dateOffset < 2,
                colors = ButtonDefaults.colors(
                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textPrimary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                ),
                // ★ 修正: 直接的な Requester 割り当てを削除
                modifier = Modifier.focusProperties {
                    up = topNavFocusRequester
                    right = FocusRequester.Cancel
                }
            ) {
                Text("翌日", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = "翌日")
            }
        }

        if (groupedGames.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // ★ 修正: フォーカスを受け取れるようにし、上キーで確実に戻れるようにする
                    .focusable()
                    .focusProperties { up = topNavFocusRequester },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.EventBusy,
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .padding(bottom = 16.dp),
                        tint = colors.textSecondary.copy(alpha = 0.3f)
                    )
                    Text(
                        text = "この日の放送予定はありません",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textSecondary.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            // ★ 変更: LazyColumn -> LazyColumn に置換
            LazyColumn(
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

                        // ★ 変更: LazyRow -> LazyRow に置換
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            itemsIndexed(teamGames) { gameIndex, game ->
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
                                                remocon_Id = 0,
                                                is_subchannel = game.channel.is_subchannel
                                            )
                                            onChannelClick(channel)
                                        } else {
                                            onProgramClick(game.program)
                                        }
                                    },
                                    // ★ 修正: 不安定なRequesterの割り当てを削除。端のガード処理のみ残す
                                    modifier = Modifier
                                        .width(380.dp)
                                        .focusProperties {
                                            if (gameIndex == 0) left = FocusRequester.Cancel
                                            if (gameIndex == teamGames.lastIndex) right =
                                                FocusRequester.Cancel
                                        },
                                    timeFormat = timeFormat
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
    modifier: Modifier = Modifier,
    timeFormat: String = "24H"
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

    val timeStr = remember(start, end, timeFormat) {
        val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE)
        "${start.format(formatter)} - ${end.format(formatter)}"
    }

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