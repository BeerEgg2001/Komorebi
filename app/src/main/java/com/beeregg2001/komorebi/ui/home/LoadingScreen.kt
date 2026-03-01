package com.beeregg2001.komorebi.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@Composable
fun LoadingScreen(
    message: String = "Loading...",
    progressRatio: Float? = null // ★追加: プログレスバー用
) {
    val colors = KomorebiTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // プログレスが渡された場合はバーを、そうでない場合は円形スピナーを表示
            if (progressRatio != null) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progressRatio,
                    animationSpec = tween(durationMillis = 500),
                    label = "progress_anim"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = colors.accent,
                    trackColor = colors.textPrimary.copy(alpha = 0.2f),
                    modifier = Modifier.width(200.dp)
                )
            } else {
                CircularProgressIndicator(color = colors.textPrimary)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(message, color = colors.textPrimary)
        }
    }
}