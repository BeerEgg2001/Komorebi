package com.beeregg2001.komorebi.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.UrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 巨大なタイル画像を効率的に扱うための専用ローダー
 */
class TiledImageLoader(private val context: Context) {
    private var isReleased = false

    // メモリキャッシュを少し増量 (16MB)
    // 1920x1080の画像1枚で約8MBなので、余裕を持たせる
    private val bitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val decoderCache = mutableMapOf<String, BitmapRegionDecoder>()
    private val loadingLocks = mutableMapOf<String, Any>()

    fun release() {
        synchronized(this) {
            isReleased = true
            decoderCache.values.forEach {
                try { it.recycle() } catch (e: Exception) { e.printStackTrace() }
            }
            decoderCache.clear()
            bitmapCache.evictAll()
        }
    }

    suspend fun loadTile(url: String, col: Int, row: Int, columns: Int, targetWidthPx: Int): Bitmap? {
        synchronized(this) { if (isReleased) return null }

        // キャッシュキー生成（URLと座標）
        // ※inSampleSizeが変わる可能性は低いのでキーには含めない運用とするが、
        // 厳密には含めたほうが良い。ここではキャッシュヒット率優先。
        val key = "$url-$col-$row"

        bitmapCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            if (!isActive) return@withContext null

            try {
                val decoder = getOrLoadDecoder(url) ?: return@withContext null
                synchronized(this@TiledImageLoader) { if (isReleased) return@withContext null }

                val width = decoder.width
                val height = decoder.height
                val tileW = width / columns
                val tileH = (tileW * 9) / 16

                val left = (col * tileW).coerceAtMost(width - 1)
                val top = (row * tileH).coerceAtMost(height - 1)
                val right = (left + tileW).coerceAtMost(width)
                val bottom = (top + tileH).coerceAtMost(height)

                if (left >= right || top >= bottom) return@withContext null

                // ★高速化: ターゲットサイズに合わせて最適な inSampleSize (2の累乗) を計算
                // 表示サイズより極端に大きな画像を読み込まないようにする
                val inSampleSize = calculateInSampleSize(tileW, tileH, targetWidthPx, (targetWidthPx * 9 / 16))

                val rect = Rect(left, top, right, bottom)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    this.inSampleSize = inSampleSize
                }

                val bitmap = try {
                    if (!decoder.isRecycled) decoder.decodeRegion(rect, options) else null
                } catch (e: Exception) {
                    null
                }

                if (bitmap != null) {
                    synchronized(this@TiledImageLoader) {
                        if (!isReleased) bitmapCache.put(key, bitmap)
                    }
                }
                bitmap
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 表示サイズに合わせて最適な2の累乗の縮小率を計算する
     */
    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight: Int = srcHeight / 2
            val halfWidth: Int = srcWidth / 2
            // 2の累乗で縮小していく
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getOrLoadDecoder(url: String): BitmapRegionDecoder? {
        synchronized(this) {
            if (isReleased) return null
            decoderCache[url]?.let { if (!it.isRecycled) return it }
        }

        val lock = synchronized(loadingLocks) {
            loadingLocks.computeIfAbsent(url) { Any() }
        }

        synchronized(lock) {
            synchronized(this) {
                if (isReleased) return null
                decoderCache[url]?.let { if (!it.isRecycled) return it }
            }

            try {
                val fileName = hashString(url) + ".jpg"
                val file = File(context.cacheDir, fileName)

                if (!file.exists()) {
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 10000
                    connection.getInputStream().use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)

                if (decoder != null) {
                    synchronized(this) {
                        if (!isReleased) {
                            decoderCache[url] = decoder
                        } else {
                            decoder.recycle()
                            return null
                        }
                    }
                }
                return decoder
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            } finally {
                synchronized(loadingLocks) {
                    loadingLocks.remove(url)
                }
            }
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
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
    val context = LocalContext.current
    val tiledLoader = remember { TiledImageLoader(context) }

    // サムネイルの表示幅(dp)
    val thumbWidthDp = 224.dp
    // ピクセル単位に変換（ImageLoaderに渡すため）
    val density = LocalDensity.current
    val thumbWidthPx = remember(density) { with(density) { thumbWidthDp.toPx().roundToInt() } }

    DisposableEffect(Unit) {
        onDispose {
            tiledLoader.release()
        }
    }

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
        // 初期フォーカスは少し待つ（UI構築待ち）
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
            Text(
                text = if(currentInterval < 60) "${currentInterval}秒間隔" else "${currentInterval/60}分間隔",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(126.dp)
            ) {
                itemsIndexed(timePoints) { index, time ->
                    TiledThumbnailItemWithRegionDecoder(
                        time = time,
                        videoId = videoId,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        loader = tiledLoader,
                        targetWidthPx = thumbWidthPx, // 表示サイズを渡す
                        onClick = { onSeekRequested(time * 1000) },
                        onFocused = { focusedTime = time },
                        modifier = if (index == targetInitialIndex) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }

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
fun TiledThumbnailItemWithRegionDecoder(
    time: Long,
    videoId: Int,
    konomiIp: String,
    konomiPort: String,
    loader: TiledImageLoader,
    targetWidthPx: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val columns = 51
    val sheetDuration = 3600L
    val tileIndex = ((time % sheetDuration) / 10).toInt()
    val col = tileIndex % columns
    val row = tileIndex / columns

    val imageUrl = remember(time) {
        UrlBuilder.getTiledThumbnailUrl(konomiIp, konomiPort, videoId, time)
    }

    LaunchedEffect(imageUrl, col, row) {
        // ★修正: デバウンス時間を 50ms に短縮
        // スクロール停止後の表示レスポンスを向上させる
        delay(50)

        if (isActive) {
            val result = loader.loadTile(imageUrl, col, row, columns, targetWidthPx)
            if (result != null && isActive) {
                bitmap = result
            }
        }
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
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize())
            }

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