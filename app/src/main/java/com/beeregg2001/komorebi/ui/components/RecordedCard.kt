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

    // UrlBuilderを使用してサムネイルURLを生成
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    // チャンネル種別と表示色の決定
    val (channelLabel, channelColor) = when (program.channel?.type) {
        "GR" -> "地デジ" to Color(0xFF1E88E5) // 青系
        "BS" -> "BS" to Color(0xFFE53935)     // 赤系
        "CS" -> "CS" to Color(0xFFFB8C00)     // オレンジ系
        else -> (program.channel?.type ?: "") to Color.Gray
    }

    // 放送局名
    val stationName = program.channel?.name ?: ""

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(185.dp)
            .height(104.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.DarkGray,
            focusedContainerColor = Color.Transparent, // 背景画像を見せるため透過
            contentColor = Color.White,
            focusedContentColor = Color.White // ★フォーカス時も白文字を維持
        ),
        // ★フォーカス時の枠線を「白」に変更し、太さを2.dpに調整
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

            // フォーカス時に画像を少し見やすくするためのオーバーレイ調整
            val overlayAlpha = if (isFocused) 0.1f else 0.5f

            // 背景を暗くするオーバーレイ
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = overlayAlpha))
            )

            // ★録画中インジケータ (右上)
            if (program.isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd) // 右上に配置
                        .padding(4.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color.Red, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "録画中",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = Color.White
                    )
                }
            }

            // 番組情報エリア (下部)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    // フォーカス時も背景を少し暗くして白文字の可読性を確保
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // ★番組名 (フォーカス時にマーキー、常に白文字)
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (isFocused) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                repeatDelayMillis = 1000,
                                velocity = 30.dp
                            )
                        } else {
                            Modifier
                        }
                    )
                )

                Spacer(modifier = Modifier.height(2.dp))

                // ★放送波アイコン + 放送局名 + 時間(右揃え)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 放送波アイコン (GR/BS等)
                    if (channelLabel.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .background(channelColor, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = channelLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // 放送局名
                    Text(
                        text = stationName,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // ★時間 (右揃え)
                    Text(
                        text = "${(program.duration / 60).toInt()}分",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f) // 残りのスペースを埋めて右端に寄せる
                    )
                }
            }
        }
    }
}