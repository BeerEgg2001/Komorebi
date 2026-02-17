package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordedCard(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val isAnalyzed = program.recordedVideo.hasKeyFrames

    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val (channelLabel, channelColor) = when (program.channel?.type) {
        "GR" -> "地デジ" to Color(0xFF1E88E5)
        "BS" -> "BS" to Color(0xFFE53935)
        "CS" -> "CS" to Color(0xFFFB8C00)
        else -> (program.channel?.type ?: "") to Color.Gray
    }

    // 時刻フォーマット関数
    fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    val totalDuration = program.recordedVideo.duration.toLong()
    val currentPosition = program.playbackPosition.toLong()

    // 表示テキストの決定：履歴があれば「続きから 12:34」、なければ「30:00」
    val durationDisplay = if (currentPosition > 5) {
        "続きから ${formatTime(currentPosition)}"
    } else {
        formatTime(totalDuration)
    }

    // 進捗率の計算
    val progress = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        onClick = { if (isAnalyzed) onClick() },
        enabled = isAnalyzed,
        modifier = modifier
            .width(185.dp)
            .height(104.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .alpha(if (isAnalyzed) 1f else 0.5f),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isAnalyzed) 1.05f else 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.DarkGray,
            focusedContainerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(width = 2.dp, color = Color.White),
                shape = MaterialTheme.shapes.medium
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isFocused) 0.1f else 0.5f))
            )

            // 右上のインジケータ
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                if (program.isRecording) {
                    Row(
                        modifier = Modifier
                            .background(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "録画中", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White)
                    }
                } else if (!isAnalyzed) {
                    Box(
                        modifier = Modifier
                            .background(color = Color(0xFFE65100).copy(alpha = 0.8f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(text = "メタデータ解析中", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White)
                    }
                }
            }

            // 下部番組情報エリア
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1000) else Modifier
                    )
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (channelLabel.isNotEmpty()) {
                        Box(modifier = Modifier.background(channelColor, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Text(text = channelLabel, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = program.channel?.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = durationDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (currentPosition > 5) Color(0xFFFFCDD2) else Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.End
                    )
                }
            }

            // 進捗バー (カードの最下部)
            if (currentPosition > 5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Gray.copy(alpha = 0.3f))
                        .align(Alignment.BottomCenter)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color.Red)
                    )
                }
            }
        }
    }
}