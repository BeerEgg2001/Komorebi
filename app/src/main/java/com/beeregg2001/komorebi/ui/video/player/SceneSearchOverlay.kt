package com.beeregg2001.komorebi.ui.video.player

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

class TileSheetLoader(
    private val context: Context,
    // ★ 追加: Cloudflare Access 等のリクエストヘッダー
    private val requestHeaders: Map<String, String> = emptyMap()
) {
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

        synchronized(tileCache) {
            tileCache.get(key)?.let {
                // Log.i(TAG, "[TileLoader] Cache hit for tile: $key") // キャッシュヒットはログが膨大になるのでコメントアウト
                return it
            }
        }

        return withContext(decodeDispatcher) {
            if (!isActive || isReleased) return@withContext null
            try {
                // ★ ログ仕込み: タイルの切り出し要求座標
                Log.i(
                    TAG,
                    "[TileLoader] Requesting tile: url=$url, col=$col, row=$row, w=$tileW, h=$tileH"
                )

                val sheet = getOrLoadFullSheet(url) ?: run {
                    Log.w(TAG, "[TileLoader] Failed to get or load full sheet!")
                    return@withContext null
                }

                val x = col * tileW
                val y = row * tileH

                // ★ ログ仕込み: 画像の範囲外を参照していないかチェック
                if (x + tileW > sheet.width || y + tileH > sheet.height) {
                    Log.e(
                        TAG,
                        "[TileLoader] Out of bounds! Request: x=$x, y=$y, w=$tileW, h=$tileH / Sheet Size: ${sheet.width}x${sheet.height}"
                    )
                    return@withContext null
                }

                val tileBitmap = Bitmap.createBitmap(sheet, x, y, tileW, tileH)
                synchronized(tileCache) { if (!isReleased) tileCache.put(key, tileBitmap) }

                Log.i(TAG, "[TileLoader] Successfully cropped and cached tile: $key")
                tileBitmap
            } catch (e: Exception) {
                Log.e(TAG, "[TileLoader] Error creating tile bitmap: col=$col, row=$row", e)
                null
            }
        }
    }

    private suspend fun getOrLoadFullSheet(url: String): Bitmap? {
        if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) return fullSheetBitmap
        return sheetLoadingMutex.withLock {
            if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) return@withLock fullSheetBitmap
            if (isReleased) return@withLock null
            try {
                Log.i(TAG, "[TileLoader] Start loading full sheet from: $url")

                val fileName = hashString(url) + ".webp"
                val file = File(context.cacheDir, fileName)

                if (!file.exists() || file.length() == 0L) {
                    Log.i(
                        TAG,
                        "[TileLoader] Downloading sheet to local cache file: ${file.absolutePath}"
                    )
                    withContext(Dispatchers.IO) {
                        val connection = URL(url).openConnection()
                        // ★ 追加: Cloudflare Access ヘッダーを付与
                        requestHeaders.forEach { (name, value) ->
                            connection.setRequestProperty(name, value)
                        }
                        connection.getInputStream().use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    Log.i(TAG, "[TileLoader] Download complete. File size: ${file.length()} bytes")
                } else {
                    Log.i(
                        TAG,
                        "[TileLoader] Found sheet in local cache file. Size: ${file.length()} bytes"
                    )
                }

                val options = BitmapFactory.Options()
                    .apply { inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)

                if (bitmap != null) {
                    fullSheetBitmap = bitmap
                    Log.i(
                        TAG,
                        "[TileLoader] Successfully decoded sheet. Size: ${bitmap.width}x${bitmap.height}"
                    )
                } else {
                    Log.e(
                        TAG,
                        "[TileLoader] Failed to decode image file! (BitmapFactory returned null)"
                    )
                }

                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "[TileLoader] Exception during sheet download or decoding", e)
                null
            }
        }
    }

    private fun hashString(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SceneSearchOverlay(
    program: RecordedProgram,
    tiledThumbnailUrl: String?, // ★ ViewModelから受け取るように変更
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit,
    requestHeaders: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val loader = remember(requestHeaders) { TileSheetLoader(context, requestHeaders) }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile

    // ★ ログ仕込み: UI層で認識しているタイル情報
    LaunchedEffect(tileInfo) {
        Log.i(TAG, "[SceneSearch] Overlay opened. TileInfo: $tileInfo, URL: $tiledThumbnailUrl")
    }

    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

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

    val targetIndex = remember(currentInterval) {
        timePoints.indexOfFirst { it >= focusedTime }.coerceAtLeast(0)
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val itemWidthPx = with(density) { 224.dp.toPx() }
    val centerOffset = (-(screenWidthPx / 2) + (itemWidthPx / 2)).toInt()

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
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (intervalIndex < intervals.lastIndex) intervalIndex++; true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (intervalIndex > 0) intervalIndex--; true
                    }

                    KeyEvent.KEYCODE_BACK -> {
                        onClose(); true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (currentInterval < 60) "${currentInterval}秒間隔" else "${currentInterval / 60}分間隔",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
            ) {
                itemsIndexed(timePoints) { index, time ->
                    TiledThumbnailItem(
                        time = time,
                        imageUrl = tiledThumbnailUrl ?: "", // ★ 変更
                        loader = loader,
                        tileColumns = tileColumns,
                        tileInterval = tileInterval,
                        tileWidth = tileWidth,
                        tileHeight = tileHeight,
                        onClick = { onSeekRequested(time * 1000) },
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
                    Text(
                        "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        val progress =
                            if (durationMs > 0) focusedTime.toFloat() / (durationMs / 1000).toFloat() else 0f
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
    modifier: Modifier = Modifier,
    imageTimeOffsetSec: Long = 0L,
    overlayContent: @Composable BoxScope.() -> Unit = {}
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val fetchTime = time + imageTimeOffsetSec
    val tileIndex = floor(fetchTime / tileInterval).toInt()
    val col = tileIndex % tileColumns
    val row = tileIndex / tileColumns

    LaunchedEffect(imageUrl, col, row) {
        if (imageUrl.isBlank()) {
            Log.w(TAG, "[TiledItem] Image URL is blank. Cannot load thumbnail for time: $fetchTime")
            return@LaunchedEffect
        }
        delay(50)
        if (isActive) {
            val result = loader.loadTile(imageUrl, col, row, tileWidth, tileHeight)
            if (result != null && isActive) {
                bitmap = result
            } else {
                Log.w(
                    TAG,
                    "[TiledItem] loadTile returned null for time: $fetchTime (col=$col, row=$row)"
                )
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
                // サムネイルがない場合のフォールバック表示 (グレー背景)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                )
            }

            overlayContent()

            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatSecondsToTime(time),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
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


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChapterListOverlay(
    program: RecordedProgram,
    chapters: List<ChapterInfo>, // ★ ViewModelから受け取るように変更
    tiledThumbnailUrl: String?,  // ★ ViewModelから受け取るように変更
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit,
    requestHeaders: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val loader = remember(requestHeaders) { TileSheetLoader(context, requestHeaders) }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile

    // ★ ログ仕込み: UI層で認識しているタイル情報
    LaunchedEffect(tileInfo) {
        Log.i(TAG, "[ChapterList] Overlay opened. TileInfo: $tileInfo, URL: $tiledThumbnailUrl")
    }

    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    val durationMs = (program.recordedVideo.duration * 1000).toLong()

    var focusedTime by remember { mutableLongStateOf(currentPositionMs / 1000) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val targetIndex = remember(chapters) {
        val idx =
            chapters.indexOfFirst { it.startTimeMs <= currentPositionMs && currentPositionMs < it.endTimeMs }
        if (idx != -1) idx else 0
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val itemWidthPx = with(density) { 224.dp.toPx() }
    val centerOffset = (-(screenWidthPx / 2) + (itemWidthPx / 2)).toInt()

    LaunchedEffect(targetIndex) {
        if (chapters.isNotEmpty()) {
            listState.scrollToItem(targetIndex, centerOffset)
            delay(150)
            focusRequester.safeRequestFocus(TAG)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f))))
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP -> {
                        onClose()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "チャプター一覧",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
            ) {
                itemsIndexed(chapters) { index, chapter ->
                    val tagColor = if (chapter.isCm) Color(0xFFE53935) else Color(0xFF1E88E5)
                    val tagText = if (chapter.isCm) "CM" else "本編"

                    val lengthSec = (chapter.endTimeMs - chapter.startTimeMs) / 1000
                    val m = lengthSec / 60
                    val s = lengthSec % 60
                    val lengthText = if (m > 0) "${m}分${s}秒" else "${s}秒"

                    val offsetSec = minOf(5L, maxOf(0L, lengthSec / 2))

                    Box(
                        modifier = if (index == targetIndex) Modifier.focusRequester(focusRequester) else Modifier
                    ) {
                        TiledThumbnailItem(
                            time = chapter.startTimeMs / 1000,
                            imageUrl = tiledThumbnailUrl ?: "", // ★ 変更
                            loader = loader,
                            tileColumns = tileColumns,
                            tileInterval = tileInterval,
                            tileWidth = tileWidth,
                            tileHeight = tileHeight,
                            imageTimeOffsetSec = offsetSec,
                            onClick = { onSeekRequested(chapter.startTimeMs) },
                            onFocused = { focusedTime = chapter.startTimeMs / 1000 },
                            overlayContent = {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(tagColor, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = tagText,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                Color.Black.copy(0.7f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = lengthText,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        )
                    }
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
                    Text(
                        "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        val progress =
                            if (durationMs > 0) focusedTime.toFloat() / (durationMs / 1000).toFloat() else 0f
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