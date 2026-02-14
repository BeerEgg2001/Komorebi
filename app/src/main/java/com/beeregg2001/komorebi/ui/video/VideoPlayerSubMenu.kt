@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.live.StreamQuality
import kotlinx.coroutines.delay

@Composable
fun VideoTopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality, // ★追加
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit, // ★追加
    onCloseMenu: () -> Unit // ★追加 (Back制御用)
) {
    var isQualityMode by remember { mutableStateOf(false) }
    val qualityFocusRequester = remember { FocusRequester() }
    val mainQualityButtonRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (e: Exception) {}
    }

    LaunchedEffect(isQualityMode) {
        if (isQualityMode) {
            delay(100)
            try { qualityFocusRequester.requestFocus() } catch (e: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent)))
            .padding(top = 24.dp, bottom = 60.dp)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
                    if (isQualityMode) {
                        isQualityMode = false
                        try { mainQualityButtonRequester.requestFocus() } catch (e: Exception) {}
                        true
                    } else {
                        onCloseMenu()
                        true
                    }
                } else {
                    false
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                VideoMenuTileItem(
                    title = "音声切替", icon = Icons.Default.PlayArrow,
                    subtitle = if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier.focusRequester(focusRequester).focusProperties { left = FocusRequester.Cancel; down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))
                VideoMenuTileItem(
                    title = "字幕表示", icon = Icons.Default.ClosedCaption,
                    subtitle = if(isSubtitleEnabled) "表示" else "非表示",
                    onClick = onSubtitleToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))
                VideoMenuTileItem(
                    title = "再生速度", icon = Icons.Default.FastForward,
                    subtitle = "${currentSpeed}x",
                    onClick = onSpeedToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))
                // ★画質切り替えボタンを追加
                VideoMenuTileItem(
                    title = "画質設定", icon = Icons.Default.Settings,
                    subtitle = currentQuality.label,
                    onClick = { isQualityMode = !isQualityMode },
                    modifier = Modifier
                        .focusRequester(mainQualityButtonRequester)
                        .focusProperties {
                            right = FocusRequester.Cancel
                            if (!isQualityMode) down = FocusRequester.Cancel
                        }
                )
            }

            AnimatedVisibility(visible = isQualityMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.width(400.dp).height(2.dp).background(Color.White.copy(0.2f)))
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        StreamQuality.entries.forEachIndexed { index, quality ->
                            VideoMenuTileItem(
                                title = quality.label,
                                icon = if (currentQuality == quality) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (currentQuality == quality) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    isQualityMode = false
                                    try { mainQualityButtonRequester.requestFocus() } catch (e: Exception) {}
                                },
                                modifier = Modifier
                                    .then(if (currentQuality == quality) Modifier.focusRequester(qualityFocusRequester) else Modifier)
                                    .focusProperties {
                                        up = mainQualityButtonRequester
                                        down = FocusRequester.Cancel
                                    },
                                width = 140.dp,
                                height = 90.dp
                            )
                            if (index < StreamQuality.entries.size - 1) {
                                Spacer(Modifier.width(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoMenuTileItem(
    title: String, icon: ImageVector, subtitle: String,
    onClick: () -> Unit, modifier: Modifier = Modifier,
    width: Dp = 180.dp, height: Dp = 100.dp
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(width, height)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(28.dp)); Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = LocalContentColor.current.copy(0.7f))
        }
    }
}