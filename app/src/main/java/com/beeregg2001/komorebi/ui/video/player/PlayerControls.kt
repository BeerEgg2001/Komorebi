@file:kotlin.OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.floor

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    program: RecordedProgram,
    tiledThumbnailUrl: String?,
    isVisible: Boolean,
    isSeekingPreviewVisible: Boolean,
    isModernUi: Boolean,
    isPlaying: Boolean,
    hasChapters: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    controlsFocusRequester: FocusRequester,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onChapterListToggle: () -> Unit,
    onInfoToggle: () -> Unit,
    onSettingsToggle: () -> Unit
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val loader = remember { TileSheetLoader(context) }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    var bufferedPosition by remember { mutableStateOf(exoPlayer.bufferedPosition.coerceAtLeast(0L)) }

    var displayPositionMs by remember { mutableStateOf(currentPositionMs) }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile
    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    var isSeekBarFocused by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (isSeekBarFocused) 8.dp else 6.dp, label = "trackHeight")
    val playHeadSize by animateDpAsState(
        if (isSeekBarFocused) 16.dp else 12.dp,
        label = "playHeadSize"
    )

    LaunchedEffect(currentPositionMs) {
        if (kotlin.math.abs(displayPositionMs - currentPositionMs) > 2000) {
            displayPositionMs = currentPositionMs
        }
    }

    LaunchedEffect(isVisible, isModernUi, isPlaying) {
        if (isVisible && isModernUi) {
            delay(100)
            try {
                controlsFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }

        var lastUpdate = System.currentTimeMillis()
        while (isVisible) {
            val now = System.currentTimeMillis()

            if (isPlaying) {
                val elapsed = now - lastUpdate
                displayPositionMs = (displayPositionMs + elapsed).coerceIn(0L, totalDurationMs)
            }

            bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            lastUpdate = now
            delay(50)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { fullHeight -> fullHeight } + fadeIn(),
        exit = slideOutVertically { fullHeight -> fullHeight } + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isModernUi) Modifier
                        .focusGroup()
                        .focusRestorer() else Modifier
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)),
                        startY = 500f
                    )
                ),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 40.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000)
                )

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedVisibility(
                    visible = isSeekingPreviewVisible && isModernUi, // URLの有無に関わらず表示試行
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        val progress =
                            if (totalDurationMs > 0) (displayPositionMs.toFloat() / totalDurationMs).coerceIn(
                                0f,
                                1f
                            ) else 0f
                        val horizontalBias = (progress * 2f) - 1f

                        var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

                        val timeSec = displayPositionMs / 1000
                        val tileIndex = floor(timeSec / tileInterval).toInt()
                        val col = tileIndex % tileColumns
                        val row = tileIndex / tileColumns

                        LaunchedEffect(tiledThumbnailUrl, col, row) {
                            if (tiledThumbnailUrl.isNullOrBlank()) {
                                return@LaunchedEffect
                            }
                            val res =
                                loader.loadTile(tiledThumbnailUrl, col, row, tileWidth, tileHeight)
                            if (res != null) {
                                bitmap = res
                            }
                        }

                        // ★ 修正: サムネイル画像がない場合は、時間だけを表示する小さな枠にする
                        if (bitmap != null) {
                            Box(
                                modifier = Modifier
                                    .align(androidx.compose.ui.BiasAlignment(horizontalBias, 1f))
                                    .size(144.dp, 81.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.DarkGray.copy(alpha = 0.8f))
                                    .border(2.dp, colors.accent, RoundedCornerShape(6.dp))
                            ) {
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = "Seek Preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = formatMillisToTime(displayPositionMs),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .background(
                                            Color.Black.copy(alpha = 0.7f),
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            Text(
                                text = formatMillisToTime(displayPositionMs),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(androidx.compose.ui.BiasAlignment(horizontalBias, 1f))
                                    .background(
                                        Color.Black.copy(alpha = 0.8f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        colors.accent.copy(alpha = 0.5f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatMillisToTime(displayPositionMs),
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(64.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .onFocusChanged {
                                isSeekBarFocused = it.isFocused
                                onSeekBarFocusChanged(it.isFocused)
                            }
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .focusable(isModernUi),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .background(
                                    Color.White.copy(alpha = 0.3f),
                                    RoundedCornerShape(4.dp)
                                )
                        )

                        val bufferProgress =
                            if (exoPlayer.duration.coerceAtLeast(1L) > 0) (bufferedPosition.toFloat() / exoPlayer.duration.coerceAtLeast(
                                1L
                            )).coerceIn(
                                0f,
                                1f
                            ) else 0f
                        if (bufferProgress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(bufferProgress)
                                    .height(trackHeight)
                                    .background(
                                        Color.White.copy(alpha = 0.5f),
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                        val playProgress =
                            if (totalDurationMs > 0) (displayPositionMs.toFloat() / totalDurationMs).coerceIn(
                                0f,
                                1f
                            ) else 0f
                        if (playProgress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(playProgress)
                                    .height(trackHeight)
                                    .background(
                                        if (isSeekBarFocused) colors.accent else colors.accent.copy(
                                            alpha = 0.8f
                                        ), RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(playProgress)
                                .height(32.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Box(
                                modifier = Modifier
                                    .offset(x = (playHeadSize / 2))
                                    .size(playHeadSize)
                                    .background(
                                        if (isSeekBarFocused) colors.accent else Color.White,
                                        CircleShape
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = formatMillisToTime(totalDurationMs),
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(64.dp)
                    )
                }

                if (isModernUi) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OsdIconButton(
                                icon = Icons.Default.Info,
                                label = "番組詳細",
                                onClick = onInfoToggle
                            )
                            if (hasChapters) {
                                OsdIconButton(
                                    icon = Icons.Default.FormatListBulleted,
                                    label = "チャプター",
                                    onClick = onChapterListToggle
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OsdIconButton(
                                icon = Icons.Default.FastRewind,
                                label = "-10秒",
                                onClick = onSeekBack,
                                buttonSize = 56.dp,
                                iconSize = 32.dp
                            )
                            OsdIconButton(
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                label = "再生/一時停止",
                                onClick = onPlayPauseToggle,
                                buttonSize = 64.dp,
                                iconSize = 36.dp,
                                isPrimary = true,
                                modifier = Modifier.focusRequester(controlsFocusRequester)
                            )
                            OsdIconButton(
                                icon = Icons.Default.FastForward,
                                label = "+30秒",
                                onClick = onSeekForward,
                                buttonSize = 56.dp,
                                iconSize = 32.dp
                            )
                        }

                        OsdIconButton(
                            icon = Icons.Default.Settings,
                            label = "設定",
                            onClick = onSettingsToggle
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OsdIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    isPrimary: Boolean = false
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        // ★ 修正: isPrimary (再生ボタン等) のフォーカス時・非フォーカス時の色を改善
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary) colors.accent else Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        modifier = modifier.size(buttonSize)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    }
}

private fun formatMillisToTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(
        Locale.getDefault(),
        "%d:%02d:%02d",
        hours,
        minutes,
        seconds
    )
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}