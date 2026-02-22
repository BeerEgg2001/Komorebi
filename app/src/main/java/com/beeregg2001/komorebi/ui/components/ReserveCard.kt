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
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加
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
    val colors = KomorebiTheme.colors // ★追加
    var isFocused by remember { mutableStateOf(false) }

    val program = item.program
    val settings = item.recordSettings

    // --- 配色定義 (テーマベース) ---
    val contentColor = if (isFocused) (if(colors.isDark) Color.Black else Color.White) else colors.textPrimary // ★修正
    val subTextColor = if (isFocused) (if(colors.isDark) Color.DarkGray else Color.LightGray) else colors.textSecondary // ★修正

    // バッジ類
    val badgeBgColor = if (isFocused) (if(colors.isDark) Color.Black else Color.White) else colors.textPrimary // ★修正
    val badgeTextColor = if (isFocused) (if(colors.isDark) Color.White else Color.Black) else colors.background // ★修正

    // ステータスカラー定義
    val recordingRed = Color(0xFFE53935)
    val errorRed = Color(0xFFC62828)
    val warningYellow = Color(0xFFFFCA28)
    val normalGreen = contentColor

    // --- ステータス表示ロジック ---
    val isRecording = item.isRecordingInProgress

    val (statusText, statusColor, statusIcon) = when {
        isRecording -> Triple("録画中", recordingRed, null)
        item.recordingAvailability == "Full" -> Triple("録画可能", normalGreen, Icons.Default.Check)
        item.recordingAvailability == "Partial" -> Triple("一部のみ録画", warningYellow, Icons.Default.Warning)
        item.recordingAvailability == "None" || item.recordingAvailability.equals("unavailable", ignoreCase = true) ->
            Triple("録画重複", errorRed, Icons.Default.Warning)
        else -> Triple(item.recordingAvailability, warningYellow, Icons.Default.Warning)
    }

    // 時刻フォーマット
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

    val fileSizeInfo = remember(item.estimatedRecordingFileSize) {
        val gb = item.estimatedRecordingFileSize.toDouble() / (1024 * 1024 * 1024)
        String.format("約 %.1fGB", gb)
    }

    val logoUrl = remember(item.channel) {
        UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, item.channel.displayChannelId ?: item.channel.id)
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
            containerColor = colors.surface, // ★修正: Color(0xFF202020)
            focusedContainerColor = colors.textPrimary, // ★修正: Color.White
            contentColor = colors.textPrimary,
            focusedContentColor = if(colors.isDark) Color.Black else Color.White // ★修正
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, colors.accent), // ★修正: Color.White -> colors.accent
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(24.dp).background(badgeBgColor, CircleShape))
                    Text(text = settings.priority.toString(), style = MaterialTheme.typography.labelSmall, color = badgeTextColor, fontWeight = FontWeight.Bold)
                }
                Text("優先度", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = subTextColor)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = program.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp) else Modifier))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.width(48.dp).aspectRatio(16f / 9f).clipToBounds().background(if (isFocused) Color.Transparent else colors.textPrimary.copy(0.1f), RoundedCornerShape(2.dp)), contentScale = ContentScale.Crop) // ★修正
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${item.channel.channelNumber} ${item.channel.name}", style = MaterialTheme.typography.bodySmall, color = subTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = program.description ?: "", style = MaterialTheme.typography.bodySmall, color = subTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxHeight()) {
                Box(modifier = Modifier.border(1.dp, if (isFocused && statusColor == normalGreen) (if(colors.isDark) Color.Black else Color.White) else statusColor, RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) { // ★修正
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRecording) { Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape)) }
                        else if (statusIcon != null) { Icon(imageVector = statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp)) }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = statusText, color = statusColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(text = timeInfo, style = MaterialTheme.typography.bodyMedium, color = contentColor, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = subTextColor, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = fileSizeInfo, style = MaterialTheme.typography.labelSmall, color = subTextColor)
                }
            }
        }
    }
}