package com.beeregg2001.komorebi.ui.video

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay

@Composable
fun PlaybackIndicator(state: IndicatorState?) {
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        if (state != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(0.7f), MaterialTheme.shapes.large)
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(state.icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(state.label, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoToast(messageState: Pair<String, Long>?) {
    Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = messageState != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(0.85f), RoundedCornerShape(32.dp))
                    .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(text = messageState?.first ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
    }
}