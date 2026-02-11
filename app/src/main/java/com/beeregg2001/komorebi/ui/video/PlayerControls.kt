package com.beeregg2001.komorebi.ui.video

import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // ★追加
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    title: String,
    isVisible: Boolean,
    onVisibilityChanged: (Boolean) -> Unit = {}
) {
    // 再生位置の更新用
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
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                        startY = 300f
                    )
                ),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 40.dp)
            ) {
                // ★修正: テーマのデフォルト設定に依存せず、fontSize=32.sp を直接指定して確実に大きくする
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = Color.White,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(24.dp))

                // プログレスバー
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                ) {
                    // バッファ済み領域
                    val bufferProgress = (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bufferProgress)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    )

                    // 現在の再生位置
                    val playProgress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(playProgress)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Text(
                        text = formatTime(duration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}