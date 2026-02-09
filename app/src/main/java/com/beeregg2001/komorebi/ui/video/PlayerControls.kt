package com.beeregg2001.komorebi.ui.video

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    onVisibilityChanged: (Boolean) -> Unit
) {
    var currentPosition by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var duration by remember { mutableLongStateOf(exoPlayer.duration.coerceAtLeast(0L)) }

    // 再生位置の更新ループ
    LaunchedEffect(isVisible) {
        if (isVisible) {
            while (true) {
                currentPosition = exoPlayer.currentPosition
                duration = exoPlayer.duration.coerceAtLeast(0L)
                delay(500) // UI表示中は細かく更新
            }
        }
    }

    // 自動非表示タイマー
    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(5000)
            onVisibilityChanged(false)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(32.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, color = Color.White)

                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.Gray.copy(alpha = 0.5f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = formatTime(currentPosition), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Text(text = formatTime(duration), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
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