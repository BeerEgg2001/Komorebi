package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val COLOR_CS = Color(0xFFFB8C00)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListItem(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val isAnalyzed = program.recordedVideo.hasKeyFrames

    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val totalDuration = program.recordedVideo.duration.toLong()
    val currentPosition = program.playbackPosition.toLong()
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val context = LocalContext.current

    val imageRequest = remember(thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(320, 180)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    // ★チューニング: Focus状態に応じた文字色の反転
    val primaryTextColor = if (isFocused) inverseColor else colors.textPrimary
    val secondaryTextColor = if (isFocused) inverseColor.copy(alpha = 0.8f) else colors.textSecondary

    // ★パース処理: 放送日時のフォーマット (例: "2021/12/31(金) PM 9:00")
    val displayDate = remember(program.startTime) {
        try {
            // ISO 8601形式 (2024-01-01T12:00:00+09:00 など) をパース
            val zdt = ZonedDateTime.parse(program.startTime)
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) a h:mm", Locale.JAPANESE)
            zdt.format(formatter)
        } catch (e: Exception) {
            // パース失敗時のフォールバック (先頭の文字列だけ切り出す)
            program.startTime.take(16).replace("-", "/")
        }
    }

    // ★パース処理: 録画時間のフォーマット (例: "(00:55)")
    val durationDisplay = remember(program.duration) {
        val totalMinutes = (program.duration / 60).toInt()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        "(${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')})"
    }

    // ★パース処理: チャンネル情報の結合 (例: "地デジ021-1 ｍｒｎV")
    val channelString = remember(program.channel) {
        val typeStr = when (program.channel?.type) {
            "GR" -> "地デジ"
            "BS" -> "BS"
            "CS" -> "CS"
            else -> program.channel?.type ?: ""
        }
        val numStr = program.channel?.channelNumber ?: ""
        val nameStr = program.channel?.name ?: ""
        "$typeStr$numStr $nameStr"
    }

    Surface(
        onClick = { if (isAnalyzed) onClick() },
        enabled = isAnalyzed,
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp) // REGZA風のスマートな高さに調整
            .onFocusChanged { isFocused = it.isFocused }
            .alpha(if (isAnalyzed) 1f else 0.5f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)), // 少し角張らせてシャープな印象に
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(width = 2.dp, color = colors.accent),
                shape = RoundedCornerShape(4.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左側: サムネイル領域 (16:9)
            Box(
                modifier = Modifier
                    .width(185.dp)
                    .fillMaxHeight()
                    .background(colors.surface)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // プログレスバー（続きから再生の場合）
                if (currentPosition > 5) {
                    val progress = (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .align(Alignment.BottomCenter)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(colors.accent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 右側: 詳細情報領域
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.Center // 上下中央揃え
            ) {
                // 上段: タイトル
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleLarge, // REGZA風に少し大きめの文字
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (isFocused) Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1000
                        ) else Modifier
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 下段: メタデータ (日時、時間、チャンネル)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // "2021/12/31(金) PM 9:00 (00:55) 地デジ021-1 ｍｒｎV" を一行で表現
                    Text(
                        text = "$displayDate $durationDisplay  $channelString",
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // 右端のステータス表示（録画中・解析中など）
                    if (program.isRecording) {
                        Text(
                            text = "録画中",
                            color = if (isFocused) inverseColor else colors.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else if (!isAnalyzed) {
                        Text(
                            text = "メタデータ解析中",
                            color = if (isFocused) inverseColor else COLOR_CS,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}