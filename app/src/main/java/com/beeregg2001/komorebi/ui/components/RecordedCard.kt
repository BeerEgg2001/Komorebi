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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

// ★チューニング4: 放送波のブランドカラーなど、普遍的な色はトップレベルで一度だけ定義してメモリを節約
private val COLOR_GR = Color(0xFF1E88E5)
private val COLOR_BS = Color(0xFFE53935)
private val COLOR_CS = Color(0xFFFB8C00)
private val COLOR_DEFAULT = Color.Gray

// ★チューニング2: 重い String.format を廃止し、最速の文字列連結に変更
private fun formatTimeFast(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m}:${s.toString().padStart(2, '0')}"
    }
}

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
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val isAnalyzed = program.recordedVideo.hasKeyFrames

    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val (channelLabel, channelColor) = when (program.channel?.type) {
        "GR" -> "地デジ" to COLOR_GR
        "BS" -> "BS" to COLOR_BS
        "CS" -> "CS" to COLOR_CS
        else -> (program.channel?.type ?: "") to COLOR_DEFAULT
    }

    val totalDuration = program.recordedVideo.duration.toLong()
    val currentPosition = program.playbackPosition.toLong()
    val durationDisplay =
        if (currentPosition > 5) "続きから ${formatTimeFast(currentPosition)}" else formatTimeFast(
            totalDuration
        )
    val progress =
        if (totalDuration > 0) (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(
            0f,
            1f
        ) else 0f

    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val context = LocalContext.current

    // ★チューニング3: ImageRequestのキャッシュ
    val imageRequest = remember(thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(300, 168)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    // ★KomorebiTheme完全適用のための動的カラー計算 (フォーカス状態に連動)
    val bgAlpha = if (isFocused) 0.1f else 0.4f
    val bottomBgColor = if (isFocused) colors.textPrimary else colors.surface.copy(alpha = 0.85f)
    val primaryTextColor = if (isFocused) inverseColor else colors.textPrimary
    val secondaryTextColor =
        if (isFocused) inverseColor.copy(alpha = 0.8f) else colors.textPrimary.copy(alpha = 0.8f)
    val badgeBgColor =
        if (isFocused) colors.textPrimary.copy(alpha = 0.9f) else colors.surface.copy(alpha = 0.8f)
    val badgeTextColor = if (isFocused) inverseColor else colors.textPrimary

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
            containerColor = colors.surface,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(width = 2.dp, color = colors.accent),
                shape = MaterialTheme.shapes.medium
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = bgAlpha))
            )

            // 右上のバッジ領域 (録画中 / 解析中)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                if (program.isRecording) {
                    Row(
                        modifier = Modifier
                            .background(
                                color = badgeBgColor,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(colors.accent, CircleShape) // テーマのアクセントカラーを使用
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "録画中",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = badgeTextColor
                        )
                    }
                } else if (!isAnalyzed) {
                    Box(
                        modifier = Modifier
                            .background(color = badgeBgColor, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "メタデータ解析中",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = badgeTextColor
                        )
                    }
                }
            }

            // 下部の情報領域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(bottomBgColor) // フォーカス時に背景色が反転
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryTextColor, // フォーカス時に文字色が反転
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (isFocused) Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1000
                        ) else Modifier
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                                color = Color.White // チャンネルバッジの視認性確保のため白固定
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = program.channel?.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = durationDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (currentPosition > 5) colors.accent else secondaryTextColor,
                        textAlign = TextAlign.End
                    )
                }
            }

            // プログレスバー
            if (currentPosition > 5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(colors.textSecondary.copy(alpha = 0.3f))
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
    }
}