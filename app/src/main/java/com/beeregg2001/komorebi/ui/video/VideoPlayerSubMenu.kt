@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoTopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent))).padding(top = 24.dp, bottom = 60.dp), contentAlignment = Alignment.TopCenter) {
        Row(horizontalArrangement = Arrangement.Center) {
            VideoMenuTileItem(
                title = "音声切替", icon = Icons.Default.PlayArrow,
                subtitle = if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                onClick = onAudioToggle,
                modifier = Modifier.focusRequester(focusRequester).focusProperties { left = FocusRequester.Cancel }
            )
            Spacer(Modifier.width(20.dp))
            VideoMenuTileItem(
                title = "再生速度", icon = Icons.Default.FastForward,
                subtitle = "${currentSpeed}x",
                onClick = onSpeedToggle,
                modifier = Modifier.focusProperties { right = FocusRequester.Cancel }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoMenuTileItem(title: String, icon: ImageVector, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.1f), focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(180.dp, 100.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(28.dp)); Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp))
        }
    }
}