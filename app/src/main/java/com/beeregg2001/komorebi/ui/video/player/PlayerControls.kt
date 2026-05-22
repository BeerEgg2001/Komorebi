@file:kotlin.OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer?,
    program: RecordedProgram,
    allComments: List<ArchivedComment>,
    tiledThumbnailUrl: String?,
    isVisible: Boolean,
    isSeekingPreviewVisible: Boolean,
    isModernUi: Boolean,
    isPlaying: Boolean,
    hasChapters: Boolean,
    externalChapters: List<ChapterInfo> = emptyList(),
    currentPositionMs: Long,
    totalDurationMs: Long,
    controlsFocusRequester: FocusRequester,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekRequested: (Long) -> Unit, // ★ 追加: シークバーでのシーク用コールバック
    onSkipPreviousChapter: () -> Unit = {},
    onSkipNextChapter: () -> Unit = {},
    onChapterListToggle: () -> Unit,
    onInfoToggle: () -> Unit,
    onSettingsToggle: () -> Unit
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val loader = remember { TileSheetLoader(context) }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    var bufferedPosition by remember {
        mutableStateOf(exoPlayer?.bufferedPosition?.coerceAtLeast(0L) ?: 0L)
    }
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
    val graphHeight by animateDpAsState(
        if (isSeekBarFocused) 80.dp else 48.dp,
        label = "graphHeight"
    )

    LaunchedEffect(currentPositionMs) {
        if (kotlin.math.abs(displayPositionMs - currentPositionMs) > 1000) {
            displayPositionMs = currentPositionMs
        }
    }

    LaunchedEffect(isVisible, isModernUi) {
        if (isVisible && isModernUi) {
            delay(100)
            try {
                if (!isSeekBarFocused) {
                    controlsFocusRequester.requestFocus()
                }
            } catch (e: Exception) {
            }
        }
    }

    LaunchedEffect(isVisible, isPlaying) {
        var lastUpdate = System.currentTimeMillis()
        while (isVisible) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                val elapsed = now - lastUpdate
                val safeMax = if (totalDurationMs > 0L) totalDurationMs else Long.MAX_VALUE
                displayPositionMs = (displayPositionMs + elapsed).coerceIn(0L, safeMax)
            }
            bufferedPosition = exoPlayer?.bufferedPosition?.coerceAtLeast(0L) ?: 0L
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
                // ★ 修正: onPreviewKeyEvent から onKeyEvent（ボトムアップ型）に変更
                // これにより、最優先される子要素（早送り/巻き戻しボタン）側で消費されなかった
                // 長押しイベントのみがここへ上昇してキャッチされ、MainRootへのイベントのすり抜けを完璧にブロックします。
                .onKeyEvent { event ->
                    if ((event.key == Key.DirectionCenter || event.key == Key.Enter) && event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.repeatCount > 0) {
                            return@onKeyEvent true
                        }
                    }
                    false
                }
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
                    visible = isSeekingPreviewVisible && isModernUi,
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
                            .focusable(isModernUi)
                            // ★ シークバーでのシークができない問題の修正
                            // フォーカスが当たっている間に左右キーが押された際、onSeekRequested へ新しい時間を渡してシークさせる
                            .onKeyEvent { event ->
                                if (isSeekBarFocused && event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            val newPos =
                                                (currentPositionMs - 10_000L).coerceAtLeast(0L)
                                            onSeekRequested(newPos)
                                            return@onKeyEvent true
                                        }

                                        Key.DirectionRight -> {
                                            val limit =
                                                if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
                                            val newPos =
                                                (currentPositionMs + 10_000L).coerceAtMost(limit)
                                            onSeekRequested(newPos)
                                            return@onKeyEvent true
                                        }
                                    }
                                }
                                false
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (allComments.isNotEmpty() && totalDurationMs > 0) {
                            CommentMomentumGraph(
                                comments = allComments,
                                totalDurationMs = totalDurationMs,
                                currentPositionMs = displayPositionMs,
                                playedColor = colors.accent.copy(alpha = 0.6f),
                                unplayedColor = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .requiredHeight(graphHeight)
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .background(
                                    Color.White.copy(alpha = 0.3f),
                                    RoundedCornerShape(4.dp)
                                )
                        )

                        if (hasChapters && totalDurationMs > 0L) {
                            val apiCmSections = program.recordedVideo.cmSections ?: emptyList()
                            val renderSections = if (apiCmSections.isNotEmpty()) {
                                apiCmSections.map {
                                    ChapterInfo(
                                        (it.startTime * 1000).toLong(),
                                        (it.endTime * 1000).toLong(),
                                        isCm = true
                                    )
                                }
                            } else {
                                externalChapters
                            }

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(trackHeight)
                            ) {
                                val canvasWidth = size.width
                                val canvasHeight = size.height

                                renderSections.filter { it.isCm && !it.isMarkerOnly }
                                    .forEach { section ->
                                        val startRatio =
                                            (section.startTimeMs.toFloat() / totalDurationMs).coerceIn(
                                                0f,
                                                1f
                                            )
                                        val endRatio =
                                            (section.endTimeMs.toFloat() / totalDurationMs).coerceIn(
                                                0f,
                                                1f
                                            )
                                        val startX = startRatio * canvasWidth
                                        val sectionWidth = (endRatio * canvasWidth) - startX

                                        drawRect(
                                            color = Color.Red.copy(alpha = 0.5f),
                                            topLeft = Offset(startX, 0f),
                                            size = Size(sectionWidth, canvasHeight)
                                        )
                                    }
                                renderSections.forEach { section ->
                                    val startRatio =
                                        (section.startTimeMs.toFloat() / totalDurationMs).coerceIn(
                                            0f,
                                            1f
                                        )
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.8f),
                                        topLeft = Offset(startRatio * canvasWidth, 0f),
                                        size = Size(2.dp.toPx(), canvasHeight)
                                    )
                                }
                            }
                        }

                        val exoDuration = exoPlayer?.duration ?: 0L
                        val bufferProgress =
                            if (exoDuration.coerceAtLeast(1L) > 0) (bufferedPosition.toFloat() / exoDuration.coerceAtLeast(
                                1L
                            )).coerceIn(0f, 1f) else 0f

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
                            if (hasChapters) {
                                OsdIconButton(
                                    icon = Icons.Default.SkipPrevious,
                                    label = "前のチャプター",
                                    onClick = onSkipPreviousChapter,
                                    buttonSize = 48.dp,
                                    iconSize = 24.dp,
                                    allowContinuousPress = true
                                )
                            }

                            OsdIconButton(
                                icon = Icons.Default.FastRewind,
                                label = "-10秒",
                                onClick = onSeekBack,
                                buttonSize = 56.dp,
                                iconSize = 32.dp,
                                allowContinuousPress = true
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
                                iconSize = 32.dp,
                                allowContinuousPress = true
                            )

                            if (hasChapters) {
                                OsdIconButton(
                                    icon = Icons.Default.SkipNext,
                                    label = "次のチャプター",
                                    onClick = onSkipNextChapter,
                                    buttonSize = 48.dp,
                                    iconSize = 24.dp,
                                    allowContinuousPress = true
                                )
                            }
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
    isPrimary: Boolean = false,
    allowContinuousPress: Boolean = false
) {
    val colors = KomorebiTheme.colors
    var lastRepeatTime by remember { mutableLongStateOf(0L) }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary) colors.accent else Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        modifier = modifier
            .size(buttonSize)
            // ★ 恩恵のトップダウン処理: SurfaceがKeyDownイベントを内部消費してアニメーションする「直前」に
            // 連打イベントをインターセプトし、onClick を 200ms 間隔で連続発火させます。
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0) {
                        if (allowContinuousPress) {
                            val now = System.currentTimeMillis()
                            if (now - lastRepeatTime > 200) {
                                onClick()
                                lastRepeatTime = now
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
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

@Composable
fun CommentMomentumGraph(
    comments: List<ArchivedComment>,
    totalDurationMs: Long,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    bucketCount: Int = 150,
    playedColor: Color = Color.White.copy(alpha = 0.8f),
    unplayedColor: Color = Color.White.copy(alpha = 0.3f)
) {
    val momentumData = remember(comments, totalDurationMs, bucketCount) {
        if (totalDurationMs <= 0 || comments.isEmpty()) return@remember List(bucketCount) { 0f }

        val buckets = IntArray(bucketCount)
        val bucketDurationMs = totalDurationMs / bucketCount.toFloat()

        comments.forEach { comment ->
            val commentPosMs = (comment.time * 1000).toLong()
            val index = (commentPosMs / bucketDurationMs).toInt().coerceIn(0, bucketCount - 1)
            buckets[index]++
        }

        val maxComments = buckets.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
        buckets.map {
            (it.toFloat() / maxComments).pow(1.5f)
        }
    }

    val currentRatio =
        if (totalDurationMs > 0) currentPositionMs.toFloat() / totalDurationMs else 0f

    Canvas(modifier = modifier) {
        val stepX = size.width / (bucketCount - 1).coerceAtLeast(1)
        val maxBarHeight = size.height

        val wavePath = Path().apply {
            moveTo(0f, size.height)
            val points = momentumData.mapIndexed { index, value ->
                val x = index * stepX
                val y = size.height - (value * maxBarHeight).coerceAtLeast(2f)
                Offset(x, y)
            }
            lineTo(points.first().x, points.first().y)
            var currentPoint = points.first()
            for (i in 1 until points.size) {
                val nextPoint = points[i]
                val midPoint = Offset(
                    (currentPoint.x + nextPoint.x) / 2f,
                    (currentPoint.y + nextPoint.y) / 2f
                )
                if (i == 1) {
                    lineTo(midPoint.x, midPoint.y)
                } else {
                    quadraticBezierTo(currentPoint.x, currentPoint.y, midPoint.x, midPoint.y)
                }
                currentPoint = nextPoint
            }
            lineTo(points.last().x, points.last().y)
            lineTo(size.width, size.height)
            close()
        }

        drawPath(path = wavePath, color = unplayedColor)
        clipRect(right = currentRatio * size.width) {
            drawPath(path = wavePath, color = playedColor)
        }
    }
}