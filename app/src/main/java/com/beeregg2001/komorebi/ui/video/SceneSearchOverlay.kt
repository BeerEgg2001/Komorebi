package com.beeregg2001.komorebi.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TAG = "Komorebi_Debug_Overlay"

class TileSheetLoader(private val context: Context) {
    private var isReleased = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val tileCache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var fullSheetBitmap: Bitmap? = null
    private val sheetLoadingMutex = Mutex()

    fun release() {
        isReleased = true
        tileCache.evictAll()
        fullSheetBitmap?.recycle()
        fullSheetBitmap = null
    }

    suspend fun loadTile(
        url: String,
        col: Int,
        row: Int,
        tileW: Int,
        tileH: Int
    ): Bitmap? {
        if (isReleased) return null

        val key = "c${col}_r${row}"

        synchronized(tileCache) {
            tileCache.get(key)?.let { return it }
        }

        return withContext(decodeDispatcher) {
            if (!isActive || isReleased) return@withContext null

            try {
                val sheet = getOrLoadFullSheet(url) ?: return@withContext null

                val x = col * tileW
                val y = row * tileH

                if (x + tileW > sheet.width || y + tileH > sheet.height) {
                    Log.w(TAG, "Tile out of bounds! x=$x, y=$y, tileW=$tileW, tileH=$tileH, sheet=${sheet.width}x${sheet.height}")
                    return@withContext null
                }

                val tileBitmap = Bitmap.createBitmap(sheet, x, y, tileW, tileH)

                synchronized(tileCache) {
                    if (!isReleased) tileCache.put(key, tileBitmap)
                }

                return@withContext tileBitmap

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun getOrLoadFullSheet(url: String): Bitmap? {
        if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) {
            return fullSheetBitmap
        }

        return sheetLoadingMutex.withLock {
            if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) {
                return fullSheetBitmap
            }
            if (isReleased) return null

            try {
                val fileName = hashString(url) + ".webp"
                val file = File(context.cacheDir, fileName)

                if (!file.exists() || file.length() == 0L) {
                    Log.i(TAG, "Downloading sheet: $url")
                    withContext(Dispatchers.IO) {
                        URL(url).openStream().use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } else {
                    Log.i(TAG, "Using cached sheet: $fileName")
                }

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inMutable = true
                }

                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bitmap != null) {
                    fullSheetBitmap = bitmap
                    Log.i(TAG, "Sheet loaded. Size: ${bitmap.width}x${bitmap.height}")
                } else {
                    Log.e(TAG, "Failed to decode sheet bitmap")
                }
                bitmap
            } catch (e: Exception) {
                e.printStackTrace()
                null
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
    program: RecordedProgram,
    currentPositionMs: Long,
    konomiIp: String,
    konomiPort: String,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val loader = remember { TileSheetLoader(context) }

    DisposableEffect(Unit) {
        onDispose { loader.release() }
    }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile
    val tileColumns = tileInfo?.columnCount ?: 1
    val tileRows = tileInfo?.rowCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    // ★重要ログ: シーンサーチ起動時にパラメータが正しいか確認
    LaunchedEffect(Unit) {
        Log.i(TAG, "Overlay Opened. TileInfo: ${tileColumns}x${tileRows}, Interval=$tileInterval, BaseURL=${UrlBuilder.getTiledThumbnailUrl(konomiIp, konomiPort, program.recordedVideo.id)}")
        if (tileInfo == null) {
            Log.e(TAG, "ERROR: ThumbnailInfo is NULL! Program ID: ${program.id}")
        }
    }

    val intervals = VideoPlayerConstants.SEARCH_INTERVALS
    var intervalIndex by remember { mutableIntStateOf(1) }
    val currentInterval = intervals[intervalIndex]

    val durationMs = (program.recordedVideo.duration * 1000).toLong()
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
                    val tiledUrl = remember(program.recordedVideo.id) {
                        UrlBuilder.getTiledThumbnailUrl(konomiIp, konomiPort, program.recordedVideo.id)
                    }

                    TiledThumbnailItem(
                        time = time,
                        imageUrl = tiledUrl,
                        loader = loader,
                        tileColumns = tileColumns,
                        tileInterval = tileInterval,
                        tileWidth = tileWidth,
                        tileHeight = tileHeight,
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
fun TiledThumbnailItem(
    time: Long,
    imageUrl: String,
    loader: TileSheetLoader,
    tileColumns: Int,
    tileInterval: Double,
    tileWidth: Int,
    tileHeight: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val tileIndex = floor(time / tileInterval).toInt()
    val col = tileIndex % tileColumns
    val row = tileIndex / tileColumns

    LaunchedEffect(imageUrl, col, row) {
        delay(50)
        if (isActive) {
            val result = loader.loadTile(imageUrl, col, row, tileWidth, tileHeight)
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