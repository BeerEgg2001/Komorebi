@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import kotlinx.coroutines.delay

@Composable
fun VideoTopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality,
    // ★追加: コメント機能用パラメータ
    isCommentEnabled: Boolean,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    // ★追加: コメント切り替えコールバック
    onCommentToggle: () -> Unit
) {
    // 展開中のカテゴリ管理
    var selectedCategory by remember { mutableStateOf<SubMenuCategory?>(null) }

    // 画質ボタン（親）へのフォーカス復帰用
    val qualityButtonRequester = remember { FocusRequester() }
    // 画質リスト（子）へのフォーカス移動用
    val qualityListRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        focusRequester.requestFocus()
    }

    // 画質選択モードが開いた時にリストへフォーカスを移す
    LaunchedEffect(selectedCategory) {
        if (selectedCategory == SubMenuCategory.QUALITY) {
            delay(100)
            try { qualityListRequester.requestFocus() } catch (e: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight() // コンテンツに合わせて高さを可変に
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                )
            )
            .padding(top = 24.dp, bottom = 48.dp)
            // Backキーで展開を閉じる制御
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
                    if (selectedCategory != null) {
                        selectedCategory = null
                        // 閉じた時は画質ボタンにフォーカスを戻す
                        try { qualityButtonRequester.requestFocus() } catch (e: Exception) {}
                        true
                    } else {
                        false
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
            // --- 第一階層 (メインメニュー) ---
            Row(
                // 中央揃えかつ間隔を指定
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                VideoMenuTileItem(
                    title = "音声切替",
                    icon = Icons.Default.Audiotrack,
                    subtitle = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusProperties { down = FocusRequester.Cancel } // 展開していない時は下に行かない
                )
                VideoMenuTileItem(
                    title = "再生速度",
                    icon = Icons.Default.Speed,
                    subtitle = "${currentSpeed}x",
                    onClick = onSpeedToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                VideoMenuTileItem(
                    title = "字幕",
                    icon = Icons.Default.Subtitles,
                    subtitle = if (isSubtitleEnabled) "ON" else "OFF",
                    onClick = onSubtitleToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                // ★追加: コメント切り替えボタン
                VideoMenuTileItem(
                    title = "コメント",
                    icon = Icons.Default.Chat,
                    subtitle = if (isCommentEnabled) "ON" else "OFF",
                    onClick = onCommentToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
                )
                // 画質ボタン（トグル動作）
                VideoMenuTileItem(
                    title = "画質",
                    icon = Icons.Default.HighQuality,
                    subtitle = currentQuality.label,
                    onClick = {
                        selectedCategory = if (selectedCategory == SubMenuCategory.QUALITY) null else SubMenuCategory.QUALITY
                    },
                    modifier = Modifier
                        .focusRequester(qualityButtonRequester)
                        .focusProperties {
                            // 展開中なら下キーでリストへ、そうでなければキャンセル
                            if (selectedCategory != SubMenuCategory.QUALITY) down = FocusRequester.Cancel
                        }
                )
            }

            // --- 第二階層 (画質選択) ---
            // ライブ視聴と同じように下に展開
            AnimatedVisibility(
                visible = selectedCategory == SubMenuCategory.QUALITY,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 区切り線
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.width(400.dp).height(2.dp).background(Color.White.copy(0.2f)))
                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                    ) {
                        StreamQuality.entries.forEachIndexed { index, quality ->
                            val isSelected = currentQuality == quality

                            VideoMenuTileItem(
                                title = quality.label,
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (isSelected) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    selectedCategory = null // 選択したら閉じる
                                    try { qualityButtonRequester.requestFocus() } catch (e: Exception) {}
                                },
                                width = 160.dp,
                                height = 100.dp,
                                modifier = Modifier
                                    .then(if (isSelected) Modifier.focusRequester(qualityListRequester) else Modifier)
                                    .focusProperties {
                                        up = qualityButtonRequester // 上キーで親に戻る
                                        down = FocusRequester.Cancel
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ライブ視聴画面 (LivePlayerSubMenu.kt) の MenuTileItem とデザインを統一したタイルコンポーネント
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoMenuTileItem(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 160.dp, // ライブ側に合わせてサイズ調整
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
        modifier = modifier
            .size(width, height)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    }
}