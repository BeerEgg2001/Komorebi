package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
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

// チャンネル種別ごとの定数値
private val COLOR_GR = Color(0xFF1E88E5)
private val COLOR_BS = Color(0xFFE53935)
private val COLOR_CS = Color(0xFFFB8C00)
private val COLOR_DEFAULT = Color.Gray

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListItem(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPersistentFocused: Boolean = false // 右メニュー操作中もハイライトを維持するためのフラグ
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    // 再生可能な状態か判定（キーフレームがあるか、または録画中か）
    val isAnalyzed =
        program.recordedVideo.hasKeyFrames || program.recordedVideo.status == "Recording"

    // 自身がフォーカスされているか、または右メニュー操作中でハイライトを維持すべきか
    val isVisualFocused = isFocused || isPersistentFocused

    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val (channelLabel, channelColor) = when (program.channel?.type) {
        "GR" -> "地デジ" to COLOR_GR
        "BS" -> "BS" to COLOR_BS
        "CS" -> "CS" to COLOR_CS
        else -> (program.channel?.type ?: "") to COLOR_DEFAULT
    }

    val currentPosition = program.playbackPosition.toLong()
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val context = LocalContext.current

    val imageRequest = remember(thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(180, 100)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val primaryTextColor = if (isVisualFocused) inverseColor else colors.textPrimary
    val secondaryTextColor =
        if (isVisualFocused) inverseColor.copy(alpha = 0.8f) else colors.textSecondary

    // 日時フォーマット
    val displayDate = remember(program.startTime) {
        try {
            val zdt = ZonedDateTime.parse(program.startTime)
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) a h:mm", Locale.JAPANESE)
            zdt.format(formatter)
        } catch (e: Exception) {
            program.startTime.take(16).replace("-", "/")
        }
    }

    // 放送時間フォーマット
    val durationDisplay = remember(program.recordedVideo.duration) {
        val totalSec = program.recordedVideo.duration.toLong()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        if (h > 0) "(${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')})"
        else "(${m}:${s.toString().padStart(2, '0')})"
    }

    val channelName = program.channel?.name ?: ""

    Surface(
        onClick = { if (isAnalyzed) onClick() },
        enabled = isAnalyzed,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .alpha(if (isAnalyzed) 1f else 0.5f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isVisualFocused) colors.textPrimary else Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = if (isVisualFocused) inverseColor else colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (isPersistentFocused) {
                Border(
                    border = BorderStroke(width = 2.dp, color = colors.accent),
                    shape = RoundedCornerShape(4.dp)
                )
            } else Border.None,
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
            // --- サムネイルエリア ---
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .background(colors.surface)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // チャンネル種別ラベル
                if (channelLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .background(channelColor.copy(alpha = 0.9f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = channelLabel,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // 再生プログレスバー
                if (currentPosition > 5) {
                    val totalDur = program.recordedVideo.duration
                    val progress =
                        if (totalDur > 0) (currentPosition.toFloat() / totalDur.toFloat()).coerceIn(
                            0f,
                            1f
                        ) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
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

            Spacer(modifier = Modifier.width(12.dp))

            // --- 番組情報エリア ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 1.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (isVisualFocused) Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1000
                        ) else Modifier
                    )
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$displayDate $durationDisplay  $channelName",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // ステータス表示
                    if (program.isRecording) {
                        Text(
                            text = "録画中",
                            color = if (isVisualFocused) inverseColor else colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else if (!isAnalyzed) {
                        Text(
                            text = "メタデータ解析中",
                            color = if (isVisualFocused) inverseColor else Color(0xFFFB8C00), // COLOR_CS
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // --- メニュー誘導ガイド ---
            // 直接フォーカス時のみ表示し、右キーでメニューが開けることを示唆
            if (isFocused) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Menu",
                    tint = inverseColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 4.dp)
                )
            }
        }
    }
}