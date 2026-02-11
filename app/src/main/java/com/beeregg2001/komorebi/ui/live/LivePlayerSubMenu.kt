@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
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
fun TopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSource: StreamSource,
    isMirakurunAvailable: Boolean,
    isSubtitleEnabled: Boolean,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSourceToggle: () -> Unit,
    onSubtitleToggle: () -> Unit
) {
    // メニューを開いた際の初期フォーカス
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent))).padding(top = 24.dp, bottom = 60.dp), contentAlignment = Alignment.TopCenter) {
        Row(horizontalArrangement = Arrangement.Center) {
            MenuTileItem(
                title = "音声切替", icon = Icons.Default.PlayArrow,
                subtitle = if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                onClick = onAudioToggle,
                modifier = Modifier.focusRequester(focusRequester).focusProperties { left = FocusRequester.Cancel }
            )
            Spacer(Modifier.width(20.dp))
            MenuTileItem(
                title = "映像ソース", icon = Icons.Default.Build,
                subtitle = if(currentSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV",
                onClick = onSourceToggle,
                enabled = isMirakurunAvailable // 選択肢がない場合はグレーアウト
            )
            Spacer(Modifier.width(20.dp))
            MenuTileItem(
                title = "字幕設定", icon = Icons.Default.ClosedCaption,
                subtitle = if(isSubtitleEnabled) "表示" else "非表示",
                onClick = onSubtitleToggle,
                modifier = Modifier.focusProperties { right = FocusRequester.Cancel }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MenuTileItem(
    title: String, icon: ImageVector, subtitle: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            contentColor = if (enabled) Color.White else Color.White.copy(0.3f), // 1択時はグレーアウト
            focusedContainerColor = Color.White, // フォーカス時は白
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(180.dp, 100.dp).alpha(if(enabled) 1f else 0.5f)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = LocalContentColor.current.copy(0.7f))
        }
    }
}