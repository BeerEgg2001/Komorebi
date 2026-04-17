package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/* 画面中央に表示される再生・一時停止等のオーバーレイを表示するメソッド */
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

/**
 * 画面下部に表示される一時的な通知（トースト）
 */
@Composable
fun VideoToast(messageState: Pair<String, Long>?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
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
                Text(
                    text = messageState?.first ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }
    }
}

/**
 * ★ 追加: L字クロップ機能の設定・調整用オーバーレイ (録画視聴版)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoLCropOverlay(
    state: VideoPlayerState,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val menuFocusRequester = remember { FocusRequester() }
    val directAdjustFocusRequester = remember { FocusRequester() }

    // フォーカス時のコンテンツカラー判定
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

    // モード切り替え時の自動フォーカス制御
    LaunchedEffect(state.lCropMode) {
        if (state.lCropMode == LCropMode.MENU) {
            delay(150)
            try {
                menuFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        } else if (state.lCropMode == LCropMode.DIRECT_ADJUST) {
            delay(150)
            try {
                directAdjustFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    if (state.lCropMode == LCropMode.DIRECT_ADJUST) {
        // --- ダイレクト調整モード (操作ガイド表示) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp)
                .focusRequester(directAdjustFocusRequester)
                .focusable(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(colors.background.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Crop, contentDescription = null, tint = colors.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "L字クロップ: ダイレクト調整中",
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("十字キー: 映像を移動", color = colors.textPrimary)
                Text(
                    "決定ボタン: 倍率切り替え (${state.lCropZoom.toInt()}%)",
                    color = colors.textPrimary
                )
                Text("戻るボタン: メニューへ戻る", color = colors.textSecondary.copy(alpha = 0.7f))
            }
        }
    } else if (state.lCropMode == LCropMode.MENU) {
        // --- メニューモード (ボトムパネル) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                                keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                    ) {
                        onClose()
                        true
                    } else false
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.2f to colors.background.copy(alpha = 0.85f),
                            1f to colors.background.copy(alpha = 0.95f)
                        )
                    )
                    .padding(start = 64.dp, end = 64.dp, top = 64.dp, bottom = 48.dp)
            ) {
                // ヘッダー
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Crop,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "L字クロップ設定",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // --- 左側：操作ボタン ---
                    Column(modifier = Modifier.weight(1.2f)) {
                        Button(
                            onClick = { state.lCropMode = LCropMode.DIRECT_ADJUST },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .focusRequester(menuFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.accent,
                                contentColor = focusedContentColor,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = focusedContentColor
                            )
                        ) {
                            Text("十字キーでダイレクト調整を開始", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth(0.9f),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = focusedContentColor
                            )
                        ) {
                            Text("確定して閉じる", fontWeight = FontWeight.Bold)
                        }
                    }

                    // --- 右側：微調整エリア ---
                    Column(modifier = Modifier.weight(1f)) {
                        Text("微調整", color = colors.accent, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        // 拡大率の +/- 調整
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "拡大率:",
                                color = colors.textSecondary,
                                modifier = Modifier.width(80.dp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                VideoAdjustmentButton(icon = Icons.Default.Remove) {
                                    state.lCropZoom = (state.lCropZoom - 1f).coerceAtLeast(100f)
                                }
                                Text(
                                    text = "${state.lCropZoom.toInt()}%",
                                    color = colors.textPrimary,
                                    modifier = Modifier.width(60.dp),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                VideoAdjustmentButton(icon = Icons.Default.Add) {
                                    state.lCropZoom = (state.lCropZoom + 1f).coerceAtMost(200f)
                                }
                            }
                        }

                        Text(
                            text = "座標: X ${state.lCropX.toInt()}% / Y ${state.lCropY.toInt()}%",
                            color = colors.textSecondary.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 80.dp, top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 拡大起点のトグル
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "拡大起点:",
                                color = colors.textSecondary,
                                modifier = Modifier.width(80.dp)
                            )
                            Button(
                                onClick = {
                                    state.lCropOrigin = when (state.lCropOrigin) {
                                        ZoomOrigin.TopLeft -> ZoomOrigin.TopRight
                                        ZoomOrigin.TopRight -> ZoomOrigin.BottomRight
                                        ZoomOrigin.BottomRight -> ZoomOrigin.BottomLeft
                                        ZoomOrigin.BottomLeft -> ZoomOrigin.TopLeft
                                    }
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                    contentColor = colors.textPrimary,
                                    focusedContainerColor = colors.textPrimary,
                                    focusedContentColor = focusedContentColor
                                )
                            ) {
                                Text(state.lCropOrigin.name, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 拡大率等の数値を調整するための小型円形ボタン
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoAdjustmentButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.1f),
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = focusedContentColor
        ),
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * ★ 修正: 背景はフェード、パネルはスライドインする番組詳細オーバーレイ
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.ProgramInfoOverlay( // ★ 修正: AnimatedVisibilityScopeの拡張関数にする
    program: RecordedProgram,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        delay(150)
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    // 全体の背景 (ここは親の fadeIn に連動してふわっと現れる)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .onKeyEvent {
                if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    when (it.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_BACK,
                        android.view.KeyEvent.KEYCODE_ESCAPE -> {
                            onClose()
                            true
                        }

                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            scope.launch { scrollState.animateScrollTo(scrollState.value + 200) }
                            true
                        }

                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            scope.launch { scrollState.animateScrollTo(scrollState.value - 200) }
                            true
                        }

                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        // メニューパネル本体
        Column(
            modifier = Modifier
                // ★ 追加: パネル部分だけ右からスライドイン・アウトさせる
                .animateEnterExit(
                    enter = slideInHorizontally { fullWidth -> fullWidth },
                    exit = slideOutHorizontally { fullWidth -> fullWidth }
                )
                .fillMaxHeight()
                .width(480.dp)
                .background(colors.surface.copy(alpha = 0.95f))
                .border(1.dp, colors.textPrimary.copy(alpha = 0.1f))
                .focusRequester(focusRequester)
                .focusable()
                .verticalScroll(scrollState)
                .padding(32.dp)
        ) {
            Text(
                text = program.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = program.description,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                lineHeight = 24.sp
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (!program.genres.isNullOrEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    program.genres?.forEach { genre ->
                        Box(
                            modifier = Modifier
                                .background(
                                    colors.accent.copy(alpha = 0.2f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = genre.major,
                                color = colors.accent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            program.detail?.forEach { (key, value) ->
                Text(
                    text = key,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}