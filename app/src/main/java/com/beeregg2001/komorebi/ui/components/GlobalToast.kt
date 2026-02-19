package com.beeregg2001.komorebi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// ★修正: アプリ共通のトーストUI (文字色を白に強制、下から出現)
@Composable
fun GlobalToast(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it }, // 下からイン
        exit = fadeOut() + slideOutVertically { it }, // 下へアウト
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.BottomCenter) // 画面下部
            .padding(bottom = 60.dp)
    ) {
        Surface(
            color = Color(0xFF202020),
            contentColor = Color.White,
            shape = RoundedCornerShape(32.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message ?: "",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White // ★重要: 文字色を明示的に白に指定
                )
            }
        }
    }
}