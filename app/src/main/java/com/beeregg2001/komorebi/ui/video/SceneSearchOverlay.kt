package com.beeregg2001.komorebi.ui.video

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.beeregg2001.komorebi.common.UrlBuilder
import kotlinx.coroutines.delay

/**
 * タイル画像から特定の1コマを高品質に切り出すための変換クラス
 */
class TileCropTransformation(
    private val col: Int,
    private val row: Int,
    private val totalCols: Int
) : Transformation {
    override val cacheKey: String = "tile_crop_${col}_${row}_${totalCols}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val tileWidth = input.width / totalCols
        val tileHeight = (tileWidth * 9) / 16 // 16:9比率

        val maxRows = input.height / tileHeight
        val targetRow = row.coerceAtMost(maxRows - 1)

        return Bitmap.createBitmap(
            input,
            (col * tileWidth).coerceAtMost(input.width - tileWidth),
            (targetRow * tileHeight).coerceAtMost(input.height - tileHeight),
            tileWidth,
            tileHeight
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SceneSearchOverlay(
    videoId: Int,
    durationMs: Long,
    currentPositionMs: Long,
    konomiIp: String,
    konomiPort: String,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit
) {
    val intervals = VideoPlayerConstants.SEARCH_INTERVALS
    var intervalIndex by remember { mutableIntStateOf(1) }
    val currentInterval = intervals[intervalIndex]

    var focusedTime by remember { mutableLongStateOf(currentPositionMs / 1000) }

    val timePoints = remember(currentInterval, durationMs) {
        val totalSec = durationMs / 1000
        (0..totalSec step currentInterval.toLong()).toList()
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val targetInitialIndex = remember(currentInterval, currentPositionMs) {
        timePoints.indexOfFirst { it >= currentPositionMs / 1000 }.coerceAtLeast(0)
    }

    LaunchedEffect(targetInitialIndex, currentInterval) {
        listState.scrollToItem(targetInitialIndex)
        delay(150)
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f))))
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (intervalIndex < intervals.lastIndex) intervalIndex++; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (intervalIndex > 0) intervalIndex--; true }
                    KeyEvent.KEYCODE_BACK -> { onClose(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ★修正: テキストを大きくし、下の余白を広げました
            Text(
                text = if(currentInterval < 60) "${currentInterval}秒間隔" else "${currentInterval/60}分間隔",
                style = MaterialTheme.typography.headlineSmall, // サイズを大きく
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp) // サムネイルとの間隔を確保
            )

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(126.dp)
            ) {
                itemsIndexed(timePoints) { index, time ->
                    TiledThumbnailItem(
                        time = time,
                        imageUrl = UrlBuilder.getTiledThumbnailUrl(konomiIp, konomiPort, videoId, time),
                        onClick = { onSeekRequested(time * 1000) },
                        onFocused = { focusedTime = time },
                        modifier = if (index == targetInitialIndex) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }

            // フッターエリア (シークバー)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 48.dp, end = 48.dp)
            ) {
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                Row(
                    modifier = Modifier
                        .width(screenWidth / 3)
                        .align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("00:00", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.7f))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        val progress = if (durationMs > 0) focusedTime.toFloat() / (durationMs / 1000).toFloat() else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }

                    Text(
                        text = formatSecondsToTime(durationMs / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TiledThumbnailItem(
    time: Long,
    imageUrl: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val columns = 51
    val sheetDuration = 3600L

    val tileIndex = ((time % sheetDuration) / 10).toInt()
    val col = tileIndex % columns
    val row = tileIndex / columns

    val imageRequest: ImageRequest = remember(imageUrl, col, row) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(Size.ORIGINAL)
            .transformations(TileCropTransformation(col, row, columns))
            .crossfade(true)
            .build()
    }

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        modifier = modifier
            .width(224.dp)
            .height(126.dp)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(0.7f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(text = formatSecondsToTime(time), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatSecondsToTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}