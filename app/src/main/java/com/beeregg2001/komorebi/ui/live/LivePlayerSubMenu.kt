@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.live

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
import androidx.compose.ui.draw.alpha
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
import com.beeregg2001.komorebi.common.AppStrings
import kotlinx.coroutines.delay
import com.beeregg2001.komorebi.data.model.StreamQuality

@Composable
fun TopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSource: StreamSource,
    currentQuality: StreamQuality,
    isMirakurunAvailable: Boolean,
    isSubtitleEnabled: Boolean,
    isSubtitleSupported: Boolean,
    isCommentEnabled: Boolean,
    isRecording: Boolean,
    onRecordToggle: () -> Unit,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSourceToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onCommentToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCloseMenu: () -> Unit
) {
    var isQualityMode by remember { mutableStateOf(false) }
    val qualityFocusRequester = remember { FocusRequester() }
    val mainQualityButtonRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // ★修正: 録画ボタンに focusRequester を付与して初期フォーカスにする
                MenuTileItem(
                    title = if (isRecording) "録画停止" else "録画開始",
                    icon = if (isRecording) Icons.Default.StopCircle else Icons.Default.RadioButtonChecked,
                    subtitle = if (isRecording) "録画中" else "番組を録画",
                    onClick = onRecordToggle,
                    modifier = Modifier
                        .focusRequester(focusRequester) // 初期フォーカス位置
                        .focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (isRecording) Color(0xFFFF5252) else Color.White
                )
                Spacer(Modifier.width(16.dp))

                // 音声切り替え
                MenuTileItem(
                    title = AppStrings.MENU_AUDIO, icon = Icons.Default.PlayArrow,
                    subtitle = if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))

                // 字幕
                MenuTileItem(
                    title = AppStrings.MENU_SUBTITLE, icon = Icons.Default.ClosedCaption,
                    subtitle = if(isSubtitleEnabled) "表示" else "非表示",
                    onClick = onSubtitleToggle,
                    enabled = isSubtitleSupported,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))

                // コメント
                MenuTileItem(
                    title = AppStrings.MENU_COMMENT, icon = Icons.Default.Chat,
                    subtitle = if(isCommentEnabled) "表示" else "非表示",
                    onClick = onCommentToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))

                // 画質
                MenuTileItem(
                    title = AppStrings.MENU_QUALITY, icon = Icons.Default.Settings,
                    subtitle = currentQuality.label,
                    onClick = { isQualityMode = !isQualityMode },
                    enabled = currentSource == StreamSource.KONOMITV,
                    modifier = Modifier
                        .focusRequester(mainQualityButtonRequester)
                        .focusProperties {
                            if (!isQualityMode) down = FocusRequester.Cancel
                        }
                )
                Spacer(Modifier.width(16.dp))

                // ソース
                MenuTileItem(
                    title = AppStrings.MENU_SOURCE, icon = Icons.Default.Build,
                    subtitle = if(currentSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV",
                    onClick = { onSourceToggle(); onCloseMenu() },
                    enabled = isMirakurunAvailable,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
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
                        StreamQuality.entries.forEach { quality ->
                            MenuTileItem(
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
                            Spacer(Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuTileItem(
    title: String, icon: ImageVector, subtitle: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    width: Dp = 160.dp,
    height: Dp = 100.dp,
    contentColor: Color = Color.White // ★追加: 文字色指定用
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            contentColor = if (enabled) contentColor else Color.White.copy(0.3f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(width, height).alpha(if(enabled) 1f else 0.5f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = LocalContentColor.current.copy(0.7f))
            }
        }
    }
}