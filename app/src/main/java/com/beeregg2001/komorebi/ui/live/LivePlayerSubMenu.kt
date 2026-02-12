@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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

@Composable
fun TopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSource: StreamSource,
    currentQuality: StreamQuality,
    isMirakurunAvailable: Boolean,
    isSubtitleEnabled: Boolean,
    // ★追加: 字幕機能がサポートされているか（Mirakurun以外か）
    isSubtitleSupported: Boolean,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSourceToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCloseMenu: () -> Unit
) {
    var isQualityMode by remember { mutableStateOf(false) }
    val qualityFocusRequester = remember { FocusRequester() }
    val mainQualityButtonRequester = remember { FocusRequester() }

    // メニューを開いた際の初期フォーカス
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (e: Exception) {}
    }

    // 画質選択モードになった際、2階層目の選択中アイテムへフォーカスを自動移動
    LaunchedEffect(isQualityMode) {
        if (isQualityMode) {
            delay(100) // 展開アニメーションを待つ
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
                // テレビのリモコンの「戻る」ボタン制御
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
                    if (isQualityMode) {
                        isQualityMode = false // 画質選択中なら2階層目を閉じて1階層目に戻る
                        try { mainQualityButtonRequester.requestFocus() } catch (e: Exception) {}
                        true
                    } else {
                        onCloseMenu() // メインメニューならサブメニュー自体を閉じる
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
            // ==========================================
            // 1階層目 (メインメニュー)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                MenuTileItem(
                    title = AppStrings.MENU_AUDIO, icon = Icons.Default.PlayArrow,
                    subtitle = if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusProperties { down = FocusRequester.Cancel } // 下にフォーカスが逃げないように制限
                )
                Spacer(Modifier.width(16.dp))
                MenuTileItem(
                    title = AppStrings.MENU_SUBTITLE, icon = Icons.Default.ClosedCaption,
                    subtitle = if(isSubtitleEnabled) "表示" else "非表示",
                    onClick = onSubtitleToggle,
                    // ★修正: Mirakurunの場合は無効化
                    enabled = isSubtitleSupported,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))
                MenuTileItem(
                    title = AppStrings.MENU_SOURCE, icon = Icons.Default.Build,
                    subtitle = if(currentSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV",
                    onClick = onSourceToggle,
                    enabled = isMirakurunAvailable,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                Spacer(Modifier.width(16.dp))
                MenuTileItem(
                    title = AppStrings.MENU_QUALITY, icon = Icons.Default.Settings,
                    subtitle = currentQuality.label,
                    onClick = {
                        isQualityMode = !isQualityMode
                    },
                    enabled = currentSource == StreamSource.KONOMITV,
                    modifier = Modifier
                        .focusRequester(mainQualityButtonRequester)
                        .focusProperties {
                            // もし2階層目が開いていなければ下移動をキャンセル
                            if (!isQualityMode) down = FocusRequester.Cancel
                        }
                )
            }

            // ==========================================
            // 2階層目 (画質サブメニューの子要素)
            // ==========================================
            AnimatedVisibility(visible = isQualityMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    // 親子関係を視覚的に分かりやすくするための境界線
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
                            MenuTileItem(
                                title = quality.label,
                                icon = if (currentQuality == quality) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (currentQuality == quality) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    isQualityMode = false // 選んだら自動で閉じる
                                    try { mainQualityButtonRequester.requestFocus() } catch (e: Exception) {}
                                },
                                modifier = Modifier
                                    .then(if (currentQuality == quality) Modifier.focusRequester(qualityFocusRequester) else Modifier)
                                    .focusProperties {
                                        up = mainQualityButtonRequester // 上キーで親要素に確実に戻る
                                        down = FocusRequester.Cancel
                                    },
                                width = 140.dp,  // 子要素であることを強調するため少し小さく
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

@Composable
fun MenuTileItem(
    title: String, icon: ImageVector, subtitle: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    width: Dp = 160.dp,
    height: Dp = 100.dp
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            contentColor = if (enabled) Color.White else Color.White.copy(0.3f),
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