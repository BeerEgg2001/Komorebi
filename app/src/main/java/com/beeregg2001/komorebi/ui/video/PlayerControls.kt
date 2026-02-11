package com.beeregg2001.komorebi.ui.video

import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    title: String,
    isVisible: Boolean
) {
    var currentPosition by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var duration by remember { mutableLongStateOf(exoPlayer.duration) }
    var bufferedPosition by remember { mutableLongStateOf(exoPlayer.bufferedPosition) }

    LaunchedEffect(isVisible) {
        while (isVisible) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(1L)
            bufferedPosition = exoPlayer.bufferedPosition
            delay(500)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    startY = 300f
                )
            ),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 40.dp)) {
                // 番組名のみを表示（マーキー機能付き）
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        // ★修正: delayMillis を initialDelayMillis に変更
                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // プログレスバー
                Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp))) {
                    val bufferProgress = (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    Box(modifier = Modifier.fillMaxWidth(bufferProgress).fillMaxHeight().background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp)))
                    val playProgress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    Box(modifier = Modifier.fillMaxWidth(playProgress).fillMaxHeight().background(Color.White, RoundedCornerShape(3.dp)))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatMillisToTime(currentPosition), color = Color.White.copy(alpha = 0.9f))
                    Text(text = formatMillisToTime(duration), color = Color.White.copy(alpha = 0.9f))
                }
            }
        }
    }
}

private fun formatMillisToTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}