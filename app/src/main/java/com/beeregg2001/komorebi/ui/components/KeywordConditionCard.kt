package com.beeregg2001.komorebi.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun KeywordConditionCard(
    condition: ReservationCondition,
    onClick: () -> Unit,
    konomiIp: String,
    konomiPort: String,
    groupedChannels: Map<String, List<Channel>> = emptyMap(),
    reserves: List<ReserveItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val searchCondition = condition.programSearchCondition
    val settings = condition.recordSettings

    // --- 配色定義 ---
    val contentColor =
        if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary
    val subTextColor =
        if (isFocused) (if (colors.isDark) Color.DarkGray else Color.LightGray) else colors.textSecondary
    val badgeBgColor =
        if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary
    val badgeTextColor =
        if (isFocused) (if (colors.isDark) Color.White else Color.Black) else colors.background

    val keywordText = searchCondition.keyword.ifEmpty { "(キーワード指定なし)" }

    // --- チャンネル情報の抽出ロジック ---
    val serviceRanges = searchCondition.serviceRanges
    val channelName: String
    val channelNumberText: String
    val logoUrl: String?

    if (serviceRanges.isNullOrEmpty()) {
        channelName = "全チャンネル対象"
        channelNumberText = ""
        logoUrl = null
    } else {
        val firstService = serviceRanges.first()
        val targetId = "NID${firstService.networkId}-SID${firstService.serviceId}"

        val reserveMatch = reserves.find { it.channel.id == targetId }?.channel
        val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }
        val channelMatch = flatChannels.find { it.id == targetId }

        channelName = reserveMatch?.name ?: channelMatch?.name ?: "不明なチャンネル"

        val num = reserveMatch?.channelNumber ?: ""
        channelNumberText = if (num.isNotEmpty()) "$num " else ""

        val displayId = reserveMatch?.displayChannelId ?: channelMatch?.displayChannelId ?: targetId

        logoUrl = UrlBuilder.getKonomiTvLogoUrl(
            ip = konomiIp,
            port = konomiPort,
            displayChannelId = displayId
        )
    }

    val extraText = if (serviceRanges != null && serviceRanges.size > 1) {
        " 他${serviceRanges.size - 1}ch"
    } else ""

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp) // ★修正: 88.dp -> 68.dp に大幅縮小してスッキリさせる
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, colors.accent),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp), // ★修正: 上下パディングをさらに削減
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- 左カラム: 優先度 ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.width(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(badgeBgColor, CircleShape)
                    )
                    Text(
                        text = settings.priority.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeTextColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "優先度",
                    fontSize = 9.sp,
                    color = subTextColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))

            // --- 中央カラム: キーワード ＆ チャンネル情報 ---
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 1行目: キーワード
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        null,
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = keywordText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (isFocused) Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    velocity = 40.dp
                                ) else Modifier
                            )
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 2行目: チャンネル情報 ＋ 除外キーワード(横に並べることで行数を節約)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (logoUrl != null) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(width = 28.dp, height = 16.dp)
                                .background(Color.White),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(
                            Icons.Default.Tv,
                            contentDescription = null,
                            tint = subTextColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "$channelNumberText$channelName$extraText",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (searchCondition.excludeKeyword.isNotEmpty()) {
                        Text(
                            text = "   |   除外: ${searchCondition.excludeKeyword}",
                            style = MaterialTheme.typography.bodySmall,
                            color = subTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(16.dp))

            // --- 右カラム: 関連予約数 ＆ 有効フラグ ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isFocused) (if (colors.isDark) Color.Black else Color.White) else subTextColor.copy(
                                alpha = 0.5f
                            ),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            tint = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else subTextColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "予約数${condition.reservationCount}件", // 「関連予約」の文字を削ってスッキリ
                            color = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else subTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (searchCondition.isEnabled) "有効" else "無効",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (searchCondition.isEnabled && !isFocused) colors.accent else contentColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}