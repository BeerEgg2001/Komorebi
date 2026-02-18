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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
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
import com.beeregg2001.komorebi.data.model.ReserveItem
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ReserveCard(
    item: ReserveItem,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val program = item.program
    val settings = item.recordSettings

    // --- 配色定義 (モノトーンベース) ---
    val contentColor = if (isFocused) Color.Black else Color.White
    val subTextColor = if (isFocused) Color.DarkGray else Color.LightGray

    // バッジ類
    val badgeBgColor = if (isFocused) Color.Black else Color.White
    val badgeTextColor = if (isFocused) Color.White else Color.Black

    // ステータスカラー定義
    val recordingRed = Color(0xFFE53935)   // 録画中 (鮮やかな赤)
    // ★追加: 録画重複用の暗めの赤
    val errorRed = Color(0xFFC62828)
    val warningYellow = Color(0xFFFFCA28) // 一部のみ (黄色)
    val normalGreen = contentColor        // 正常

    // --- ステータス表示ロジック ---
    val isRecording = item.isRecordingInProgress

    // ステータスの判定
    val (statusText, statusColor, statusIcon) = when {
        isRecording -> Triple("録画中", recordingRed, null) // 録画中はアイコンなしで赤丸
        item.recordingAvailability == "Full" -> Triple("録画可能", normalGreen, Icons.Default.Check)
        item.recordingAvailability == "Partial" -> Triple("一部のみ録画", warningYellow, Icons.Default.Warning)
        // ★修正: 録画重複を新しい赤色(errorRed)に変更
        item.recordingAvailability == "None" || item.recordingAvailability.equals("unavailable", ignoreCase = true) ->
            Triple("録画重複", errorRed, Icons.Default.Warning)
        else -> Triple(item.recordingAvailability, warningYellow, Icons.Default.Warning) // その他は警告
    }

    // --- 時刻フォーマット (OffsetDateTime使用) ---
    val timeInfo = remember(program.startTime, program.endTime) {
        try {
            val start = OffsetDateTime.parse(program.startTime).atZoneSameInstant(ZoneId.systemDefault())
            val end = OffsetDateTime.parse(program.endTime).atZoneSameInstant(ZoneId.systemDefault())

            val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd (E) HH:mm", Locale.JAPAN)
            val endFmt = DateTimeFormatter.ofPattern("HH:mm")
            val durationMin = ChronoUnit.MINUTES.between(start, end)

            "${start.format(dateFmt)} ~ ${end.format(endFmt)} (${durationMin}分)"
        } catch (e: Exception) {
            program.startTime
        }
    }

    // ファイルサイズ表示
    val fileSizeInfo = remember(item.estimatedRecordingFileSize) {
        val gb = item.estimatedRecordingFileSize.toDouble() / (1024 * 1024 * 1024)
        String.format("約 %.1fGB", gb)
    }

    // ロゴURL生成
    val logoUrl = remember(item.channel) {
        UrlBuilder.getKonomiTvLogoUrl(
            konomiIp,
            konomiPort,
            item.channel.displayChannelId ?: item.channel.id
        )
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF202020),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- 左側: 優先度 ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(60.dp)
            ) {
                // 優先度バッジ
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(badgeBgColor, CircleShape)
                    )
                    Text(
                        text = settings.priority.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeTextColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("優先度", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = subTextColor)
            }

            Spacer(modifier = Modifier.width(16.dp))

            // --- 中央: 番組情報 ---
            Column(modifier = Modifier.weight(1f)) {
                // タイトル行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // フォーカス時にマーキー表示
                            .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp) else Modifier)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // チャンネルロゴと名前
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // チャンネルロゴ表示 (Cropで上下カット)
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .width(48.dp)
                            .aspectRatio(16f / 9f)
                            .clipToBounds()
                            .background(
                                if (isFocused) Color.Transparent else Color.White.copy(0.1f),
                                RoundedCornerShape(2.dp)
                            ),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "${item.channel.channelNumber} ${item.channel.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 番組概要
                Text(
                    text = program.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = subTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // --- 右側: ステータスと時間 ---
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxHeight()
            ) {
                // ステータスバッジ (色とアイコンを動的に変更)
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            // フォーカス中でかつ警告・録画中でない場合は黒枠(反転)、それ以外はステータスカラー
                            if (isFocused && statusColor == normalGreen) Color.Black else statusColor,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor, CircleShape)
                            )
                        } else if (statusIcon != null) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 放送時間
                Text(
                    text = timeInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )

                // 容量目安
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storage,
                        null,
                        tint = subTextColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = fileSizeInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = subTextColor
                    )
                }
            }
        }
    }
}