package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.example.komorebi.data.model.EpgChannel
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DateHeaderBox(
    baseTime: OffsetDateTime,
    width: Dp,
    headerHeight: Dp
) {
    val dayOfWeekJapan = listOf("日", "月", "火", "水", "木", "金", "土")
    val dayOfWeekIndex = baseTime.dayOfWeek.value % 7
    val dayColor = when (dayOfWeekIndex) {
        0 -> Color(0xFFFF5252) // 日曜: 赤
        6 -> Color(0xFF448AFF) // 土曜: 青
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .width(width)
            .height(headerHeight)
            .background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${baseTime.monthValue}/${baseTime.dayOfMonth}",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "(${dayOfWeekJapan[dayOfWeekIndex]})",
                color = dayColor,
                fontSize = 10.sp
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgChannelHeader(
    channel: EpgChannel,
    logoUrl: String
) {
    val config = LocalEpgConfig.current
    Box(
        modifier = Modifier
            .width(config.channelWidth)
            .height(config.headerHeight)
            .background(Color(0xFF111111))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp, 24.dp)
                    .background(Color.DarkGray),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(8.dp))
            Column {
                // チャンネル番号 (サービスIDの下3桁を表示することが多いです)
                Text(
                    text = channel.channel_number.padStart(3, '0'),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp
                )
                // チャンネル名 (サイズを少し小さくし、太字を強調)
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgTimeColumn(baseTime: OffsetDateTime) {
    val config = LocalEpgConfig.current
    Column {
        repeat(24) { hourIndex ->
            val time = baseTime.plusHours(hourIndex.toLong())
            Box(
                modifier = Modifier
                    .width(config.timeColumnWidth)
                    .height(config.hourHeight),
                contentAlignment = Alignment.TopCenter
            ) {
                // 「時」表記に変更し、少し明るめのグレーに
                Text(
                    text = "${time.hour}時",
                    color = Color(0xFFCCCCCC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}