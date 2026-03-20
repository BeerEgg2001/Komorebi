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
import com.beeregg2001.komorebi.data.model.AudioMode
import kotlinx.coroutines.delay
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

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
    isSignalInfoVisible: Boolean,
    isDualDisplayMode: Boolean, // 二画面モードフラグ
    onDualDisplayToggle: () -> Unit, // 二画面トグル
    onSignalInfoToggle: () -> Unit,
    onRecordToggle: () -> Unit,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSourceToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onCommentToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCloseMenu: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isQualityMode by remember { mutableStateOf(false) }
    val qualityFocusRequester = remember { FocusRequester() }
    val mainQualityButtonRequester = remember { FocusRequester() }

    // ★追加: 二画面モード時に1080pを除外した選択肢を生成
    val availableQualities = remember(isDualDisplayMode) {
        if (isDualDisplayMode) {
            StreamQuality.entries.filter { !it.name.contains("1080") && !it.label.contains("1080") }
        } else {
            StreamQuality.entries.toList()
        }
    }

    // ★追加: 二画面モード中に現在の設定が1080pだった場合、UI上の表示を720pに補正する
    val effectiveQuality = remember(currentQuality, isDualDisplayMode, availableQualities) {
        if (isDualDisplayMode && (currentQuality.name.contains("1080") || currentQuality.label.contains(
                "1080"
            ))
        ) {
            availableQualities.find { it.name.contains("720") || it.label.contains("720") }
                ?: availableQualities.firstOrNull()
                ?: currentQuality
        } else {
            currentQuality
        }
    }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    LaunchedEffect(isQualityMode) {
        if (isQualityMode) {
            delay(100)
            try {
                qualityFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.background.copy(0.9f),
                        Color.Transparent
                    )
                )
            )
            .padding(top = 24.dp, bottom = 60.dp)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                ) {
                    if (isQualityMode) {
                        isQualityMode = false
                        try {
                            mainQualityButtonRequester.requestFocus()
                        } catch (e: Exception) {
                        }
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
                if (isDualDisplayMode) {
                    // 二画面ボタン (終了)
                    MenuTileItem(
                        title = "二画面", icon = Icons.Default.PictureInPicture,
                        subtitle = "終了",
                        onClick = { onDualDisplayToggle(); onCloseMenu() },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    // 字幕切り替え
                    MenuTileItem(
                        title = AppStrings.MENU_SUBTITLE, icon = Icons.Default.ClosedCaption,
                        subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                        onClick = onSubtitleToggle,
                        enabled = isSubtitleSupported,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    // ★修正: 画質切り替え (720p上限に補正された品質を表示)
                    MenuTileItem(
                        title = AppStrings.MENU_QUALITY, icon = Icons.Default.Settings,
                        subtitle = effectiveQuality.label,
                        onClick = { isQualityMode = !isQualityMode },
                        enabled = currentSource == StreamSource.KONOMITV,
                        modifier = Modifier
                            .focusRequester(mainQualityButtonRequester)
                            .focusProperties {
                                if (!isQualityMode) down = FocusRequester.Cancel
                            },
                        contentColor = colors.textPrimary
                    )
                } else {
                    // --- 通常モード時のフルメニュー ---
                    MenuTileItem(
                        title = if (isRecording) "録画停止" else "録画開始",
                        icon = if (isRecording) Icons.Default.StopCircle else Icons.Default.RadioButtonChecked,
                        subtitle = if (isRecording) "録画中" else "番組を録画",
                        onClick = onRecordToggle,
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .focusProperties { down = FocusRequester.Cancel },
                        contentColor = if (isRecording) Color(0xFFFF5252) else colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    MenuTileItem(
                        title = "二画面", icon = Icons.Default.PictureInPicture,
                        subtitle = "開始",
                        onClick = { onDualDisplayToggle(); onCloseMenu() },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    MenuTileItem(
                        title = AppStrings.MENU_AUDIO, icon = Icons.Default.PlayArrow,
                        subtitle = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                        onClick = onAudioToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    MenuTileItem(
                        title = "信号情報", icon = Icons.Default.Info,
                        subtitle = if (isSignalInfoVisible) "表示中" else "非表示",
                        onClick = { onSignalInfoToggle(); onCloseMenu() },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    MenuTileItem(
                        title = AppStrings.MENU_SUBTITLE, icon = Icons.Default.ClosedCaption,
                        subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                        onClick = onSubtitleToggle,
                        enabled = isSubtitleSupported,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    MenuTileItem(
                        title = AppStrings.MENU_COMMENT, icon = Icons.Default.Chat,
                        subtitle = if (isCommentEnabled) "表示" else "非表示",
                        onClick = onCommentToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    // ★修正: 画質
                    MenuTileItem(
                        title = AppStrings.MENU_QUALITY, icon = Icons.Default.Settings,
                        subtitle = effectiveQuality.label,
                        onClick = { isQualityMode = !isQualityMode },
                        enabled = currentSource == StreamSource.KONOMITV,
                        modifier = Modifier
                            .focusRequester(mainQualityButtonRequester)
                            .focusProperties {
                                if (!isQualityMode) down = FocusRequester.Cancel
                            },
                        contentColor = colors.textPrimary
                    )
                    Spacer(Modifier.width(16.dp))

                    MenuTileItem(
                        title = AppStrings.MENU_SOURCE, icon = Icons.Default.Build,
                        subtitle = if (currentSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV",
                        onClick = { onSourceToggle(); onCloseMenu() },
                        enabled = isMirakurunAvailable,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )
                }
            }

            AnimatedVisibility(visible = isQualityMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(2.dp)
                            .background(colors.textPrimary.copy(0.2f))
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // ★修正: 二画面時は1080pが除外されたavailableQualitiesでループを回す
                        availableQualities.forEach { quality ->
                            MenuTileItem(
                                title = quality.label,
                                icon = if (effectiveQuality == quality) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (effectiveQuality == quality) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    isQualityMode = false
                                    try {
                                        mainQualityButtonRequester.requestFocus()
                                    } catch (e: Exception) {
                                    }
                                },
                                modifier = Modifier
                                    .then(
                                        if (effectiveQuality == quality) Modifier.focusRequester(
                                            qualityFocusRequester
                                        ) else Modifier
                                    )
                                    .focusProperties {
                                        up = mainQualityButtonRequester
                                        down = FocusRequester.Cancel
                                    },
                                width = 140.dp,
                                height = 90.dp,
                                contentColor = colors.textPrimary
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
    contentColor: Color = Color.White
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(0.1f),
            contentColor = if (enabled) contentColor else colors.textPrimary.copy(0.3f),
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier
            .size(width, height)
            .alpha(if (enabled) 1f else 0.5f)
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
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = LocalContentColor.current.copy(0.7f)
                )
            }
        }
    }
}