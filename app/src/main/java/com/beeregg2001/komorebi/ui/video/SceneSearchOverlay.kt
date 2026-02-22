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
import com.beeregg2001.komorebi.common.safeRequestFocus
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

private const val TAG = "SceneSearchOverlay"

// --- TileSheetLoader クラスは変更なし ---
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

    suspend fun loadTile(url: String, col: Int, row: Int, tileW: Int, tileH: Int): Bitmap? {
        if (isReleased) return null
        val key = "c${col}_r${row}"
        synchronized(tileCache) { tileCache.get(key)?.let { return it } }
        return withContext(decodeDispatcher) {
            if (!isActive || isReleased) return@withContext null
            try {
                val sheet = getOrLoadFullSheet(url) ?: return@withContext null
                val x = col * tileW
                val y = row * tileH
                if (x + tileW > sheet.width || y + tileH > sheet.height) return@withContext null
                val tileBitmap = Bitmap.createBitmap(sheet, x, y, tileW, tileH)
                synchronized(tileCache) { if (!isReleased) tileCache.put(key, tileBitmap) }
                tileBitmap
            } catch (e: Exception) { null }
        }
    }

    private suspend fun getOrLoadFullSheet(url: String): Bitmap? {
        if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) return fullSheetBitmap
        return sheetLoadingMutex.withLock {
            if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) return@withLock fullSheetBitmap
            if (isReleased) return@withLock null
            try {
                val fileName = hashString(url) + ".webp"
                val file = File(context.cacheDir, fileName)
                if (!file.exists() || file.length() == 0L) {
                    withContext(Dispatchers.IO) { URL(url).openStream().use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } } }
                }
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bitmap != null) fullSheetBitmap = bitmap
                bitmap
            } catch (e: Exception) { null }
        }
    }

    private fun hashString(input: String): String = MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
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

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile
    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    val intervals = VideoPlayerConstants.SEARCH_INTERVALS
    var intervalIndex by remember { mutableIntStateOf(1) }
    val currentInterval = intervals[intervalIndex]

    val durationMs = (program.recordedVideo.duration * 1000).toLong()

    // ★改善1: 現在フォーカスされている時間をステートとして保持する
    var focusedTime by remember { mutableLongStateOf(currentPositionMs / 1000) }

    val timePoints = remember(currentInterval, durationMs) {
        val totalSec = durationMs / 1000
        (0..totalSec step currentInterval.toLong()).toList()
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // ★改善2: インターバル切り替え時に、現在の focusedTime に最も近いインデックスを計算する
    val targetIndex = remember(currentInterval) {
        timePoints.indexOfFirst { it >= focusedTime }.coerceAtLeast(0)
    }

    // ★改善3: アイテムを画面中央に配置するためのオフセット計算
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val itemWidthPx = with(density) { 224.dp.toPx() } // TiledThumbnailItemの幅
    val centerOffset = (-(screenWidthPx / 2) + (itemWidthPx / 2)).toInt()

    // インターバル変更や初期表示時にスクロールとフォーカスを行う
    LaunchedEffect(targetIndex) {
        listState.scrollToItem(targetIndex, centerOffset)
        delay(150)
        focusRequester.safeRequestFocus(TAG)
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
                        // ★改善4: フォーカスが当たった時に focusedTime を更新する
                        onFocused = { focusedTime = time },
                        modifier = if (index == targetIndex) Modifier.focusRequester(focusRequester) else Modifier
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
            if (result != null && isActive) { bitmap = result }
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