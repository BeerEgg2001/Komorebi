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
        "GR" -> "地デジ" to Color(0xFF1E88E5)
        "BS" -> "BS" to Color(0xFFE53935)
        "CS" -> "CS" to Color(0xFFFB8C00)
        else -> (program.channel?.type ?: "") to Color.Gray
    }

    fun formatTime(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    val totalDuration = program.recordedVideo.duration.toLong()
    val currentPosition = program.playbackPosition.toLong()
    val durationDisplay = if (currentPosition > 5) "続きから ${formatTime(currentPosition)}" else formatTime(totalDuration)
    val progress = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f

    val inverseColor = if (colors.isDark) Color.Black else Color.White

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
            focusedContainerColor = colors.textPrimary, // ★背景を反転色で塗りつぶす
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor // ★文字色を反転させる
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(width = 2.dp, color = colors.accent),
                shape = MaterialTheme.shapes.medium
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(thumbnailUrl).size(coil.size.Size(300, 168)).crossfade(true).memoryCachePolicy(CachePolicy.ENABLED).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)

            Box(modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = if (isFocused) 0.1f else 0.4f)))

            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                if (program.isRecording) {
                    Row(modifier = Modifier.background(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape)); Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "録画中", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White)
                    }
                } else if (!isAnalyzed) {
                    Box(modifier = Modifier.background(color = Color(0xFFE65100).copy(alpha = 0.8f), shape = RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(text = "メタデータ解析中", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White)
                    }
                }
            }

            // ★修正: 背景をテーマのSurface色にし、文字は Surface の LocalContentColor（自動反転）に任せる
            Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(colors.surface.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(text = program.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1000) else Modifier))
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (channelLabel.isNotEmpty()) {
                        Box(modifier = Modifier.background(channelColor, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Text(text = channelLabel, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White) // バッジの文字は白固定
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(text = program.channel?.name ?: "", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = colors.textPrimary.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(text = durationDisplay, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = if (currentPosition > 5) colors.accent else colors.textPrimary.copy(alpha = 0.8f), textAlign = TextAlign.End)
                }
            }

            if (currentPosition > 5) {
                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(colors.textSecondary.copy(alpha = 0.3f)).align(Alignment.BottomCenter)) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accent))
                }
            }
        }
    }
}