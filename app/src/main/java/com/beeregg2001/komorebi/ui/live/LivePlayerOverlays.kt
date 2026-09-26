package com.beeregg2001.komorebi.ui.live

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * REGZA風の信号情報オーバーレイ
 */
@Composable
fun SignalInfoOverlay(info: SignalMetadata) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, bottom = 48.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Column(
            modifier = Modifier
                .background(colors.background.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .padding(24.dp)
                .width(320.dp)
        ) {
            Text(
                text = "信号情報",
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SignalRow("解像度", info.videoRes)
            SignalRow("垂直周波数", info.verticalFreq)
            SignalRow("映像形式", info.videoCodec)
            SignalRow("ビットレート", info.videoBitrate)
            SignalRow("音声形式", info.audioCodec)
            SignalRow("音声出力", info.audioChannels)
            SignalRow("サンプリング", info.audioSampleRate)
            SignalRow("バッファ量", info.bufferDuration)
            SignalRow("ドロップ数", info.droppedFrames)
        }
    }
}

@Composable
private fun SignalRow(label: String, value: String) {
    val colors = KomorebiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(100.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = colors.textSecondary.copy(alpha = 0.8f)
        )
        Text(
            text = ": $value",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = colors.textPrimary
        )
    }
}

@Composable
fun StatusOverlay(
    channel: Channel,
    logoUrl: String,
    shouldCropLogo: Boolean, // ★ 追加: クロップフラグ
    timeFormatSetting: String = "24H"
) {
    var currentTime by remember { mutableStateOf("") }

    val displaySdf = remember(timeFormatSetting) {
        if (timeFormatSetting == "12H") SimpleDateFormat("a h:mm", Locale.getDefault())
        else SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = displaySdf.format(Date())
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp, 32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
                // ★ 修正: フラグに基づいてスケールを変更
                contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "${formatChannelType(channel.type)}${channel.channelNumber}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = currentTime,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                color = Color.White
            )
        }
    }
}

@Composable
fun LiveOverlayUI(
    channel: Channel,
    programTitle: String,
    logoUrl: String,
    shouldCropLogo: Boolean, // ★ 追加: クロップフラグ
    showDesc: Boolean,
    isRecording: Boolean,
    scrollState: ScrollState,
    timeFormatSetting: String = "24H"
) {
    val program = channel.programPresent
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()) }
    val displaySdf = remember(timeFormatSetting) {
        if (timeFormatSetting == "12H") SimpleDateFormat("a h:mm", Locale.getDefault())
        else SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    var progress by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(program) {
        if (program != null && !program.startTime.isNullOrEmpty() && !program.endTime.isNullOrEmpty()) {
            val startMs = sdf.parse(program.startTime)?.time ?: 0L
            val endMs = sdf.parse(program.endTime)?.time ?: 0L
            val total = endMs - startMs
            if (total > 0) {
                while (System.currentTimeMillis() < endMs) {
                    progress =
                        ((System.currentTimeMillis() - startMs).toFloat() / total).coerceIn(0f, 1f)
                    delay(5000)
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(0.95f)
                        )
                    )
                )
                .padding(horizontal = 64.dp, vertical = 48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp, 45.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White),
                    // ★ 修正: フラグに基づいてスケールを変更
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    text = "${formatChannelType(channel.type)}${channel.channelNumber}  ${channel.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(0.8f),
                    modifier = Modifier.weight(1f)
                )

                if (isRecording) {
                    RecordingIndicator()
                }
            }
            Text(
                text = programTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = Color.White,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            if (showDesc && program != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        if (!program.description.isNullOrEmpty()) {
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(0.9f),
                                modifier = Modifier.padding(bottom = 20.dp)
                            )
                        }
                        program.detail?.forEach { (k, v) ->
                            Column(Modifier.padding(bottom = 14.dp)) {
                                Text(
                                    text = "◆ $k",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(0.5f)
                                    )
                                )
                                Text(
                                    text = v,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(0.85f),
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (progress >= 0f) {
                val start =
                    program?.startTime?.let { sdf.parse(it) }?.let { displaySdf.format(it) } ?: ""
                val end =
                    program?.endTime?.let { sdf.parse(it) }?.let { displaySdf.format(it) } ?: ""
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = start,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                            .height(4.dp)
                            .background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Text(
                        text = end,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingIndicator() {
    Box(
        modifier = Modifier
            .background(Color.Black.copy(0.5f), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFFF5252).copy(0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFFFF5252), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "録画中",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveErrorDialog(errorMessage: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val retryButtonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryButtonFocusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)),
            modifier = Modifier.width(450.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF5252),
                    modifier = Modifier
                        .size(48.dp)
                        .padding(bottom = 16.dp)
                )
                Text(
                    text = AppStrings.LIVE_PLAYER_ERROR_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(AppStrings.BUTTON_BACK)
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(retryButtonFocusRequester)
                    ) {
                        Text(AppStrings.BUTTON_RETRY)
                    }
                }
            }
        }
    }
}

/**
 * L字クロップ機能の設定・調整用オーバーレイ
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LCropOverlay(
    state: LivePlayerState,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val menuFocusRequester = remember { FocusRequester() }
    val directAdjustFocusRequester = remember { FocusRequester() }

    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

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

                    Column(modifier = Modifier.weight(1f)) {
                        Text("微調整", color = colors.accent, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

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
                                AdjustmentButton(icon = Icons.Default.Remove) {
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
                                AdjustmentButton(icon = Icons.Default.Add) {
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
                                val originLabel = when (state.lCropOrigin) {
                                    ZoomOrigin.TopLeft -> "左上"
                                    ZoomOrigin.TopRight -> "右上"
                                    ZoomOrigin.BottomLeft -> "左下"
                                    ZoomOrigin.BottomRight -> "右下"
                                }
                                Text(originLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 拡大率等の数値を1単位で調整するための小型ボタン
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdjustmentButton(
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

fun formatChannelType(type: String): String = when (type.uppercase()) {
    "GR" -> "地デジ"
    "BS" -> "BS"
    "CS" -> "CS"
    else -> type
}