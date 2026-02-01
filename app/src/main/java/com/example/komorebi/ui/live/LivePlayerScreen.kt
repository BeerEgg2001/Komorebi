@file:kotlin.OptIn(ExperimentalAnimationApi::class, UnstableApi::class)

package com.example.komorebi.ui.live

import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.komorebi.ChannelListScreen
import com.example.komorebi.buildStreamId
import com.example.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// --- 列挙型定義 ---
enum class AudioMode { MAIN, SUB, STEREO }
enum class SubMenuCategory { AUDIO, VIDEO }

@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String,
    mirakurunPort: String,
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onBackPressed: () -> Unit, // ★親（MainActivity）に戻るためのコールバックを追加
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val currentChannelItem by remember(channel.id, groupedChannels) {
        derivedStateOf { groupedChannels.values.flatten().find { it.id == channel.id } ?: channel }
    }

    val audioProcessor = remember { ChannelMixingAudioProcessor() }
    var currentAudioMode by remember { mutableStateOf(AudioMode.STEREO) }
    var showOverlay by remember { mutableStateOf(false) }
    var isManualOverlay by remember { mutableStateOf(false) }
    var isPinnedOverlay by remember { mutableStateOf(false) }
    var isSubMenuOpen by remember { mutableStateOf(false) }
    var activeSubMenuCategory by remember { mutableStateOf<SubMenuCategory?>(null) }

    var keyDownStartTime by remember { mutableLongStateOf(0L) }
    var isLongPressProcessed by remember { mutableStateOf(false) }
    var ignoreNextKeyUp by remember { mutableStateOf(false) }
    var lastKeyUpTime by remember { mutableLongStateOf(0L) }
    val mainFocusRequester = remember { FocusRequester() }

    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build()
            }
        }
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        }
        ExoPlayer.Builder(context, renderersFactory).setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory)).build().apply { playWhenReady = true }
    }

    LaunchedEffect(currentAudioMode) {
        val matrix = when (currentAudioMode) {
            AudioMode.MAIN -> floatArrayOf(1f, 0f, 1f, 0f)
            AudioMode.SUB -> floatArrayOf(0f, 1f, 0f, 1f)
            AudioMode.STEREO -> floatArrayOf(1f, 0f, 0f, 1f)
        }
        audioProcessor.putChannelMixingMatrix(ChannelMixingMatrix(2, 2, matrix))
    }

    LaunchedEffect(currentChannelItem.id) {
        isManualOverlay = false; isPinnedOverlay = false; showOverlay = true; scrollState.scrollTo(0)
        val streamUrl = "http://$mirakurunIp:$mirakurunPort/api/services/${buildStreamId(currentChannelItem)}/stream"
        exoPlayer.stop(); exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare(); exoPlayer.play()
        mainFocusRequester.requestFocus()
        delay(4500)
        if (!isManualOverlay && !isPinnedOverlay && !isSubMenuOpen) showOverlay = false
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .onPreviewKeyEvent { keyEvent ->
            val keyCode = keyEvent.nativeKeyEvent.keyCode
            val isEnter = keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER || keyCode == NativeKeyEvent.KEYCODE_ENTER
            val isBack = keyCode == NativeKeyEvent.KEYCODE_BACK
            val isUp = keyCode == NativeKeyEvent.KEYCODE_DPAD_UP
            val isDown = keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN

            // 1. サブメニュー・ミニリスト表示中の「戻る」
            if (isSubMenuOpen || isMiniListOpen) {
                if (isBack && keyEvent.type == KeyEventType.KeyDown) {
                    if (isSubMenuOpen) {
                        if (activeSubMenuCategory != null) activeSubMenuCategory = null
                        else { isSubMenuOpen = false; ignoreNextKeyUp = true; mainFocusRequester.requestFocus() }
                    } else { onMiniListToggle(false); mainFocusRequester.requestFocus() }
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }

            // 2. 何も表示されていない時の「戻る」：親のコールバックを呼ぶ
            if (isBack && keyEvent.type == KeyEventType.KeyDown) {
                if (showOverlay || isPinnedOverlay) {
                    showOverlay = false; isPinnedOverlay = false; isManualOverlay = false
                } else {
                    onBackPressed() // 親側の isPlayerMode = false を叩く
                }
                return@onPreviewKeyEvent true
            }

            // 3. スクロール処理
            if (showOverlay && isManualOverlay && (isUp || isDown)) {
                if (keyEvent.type == KeyEventType.KeyDown) {
                    coroutineScope.launch { scrollState.animateScrollBy(if (isUp) -300f else 300f) }
                }
                return@onPreviewKeyEvent true
            }

            // 4. 決定キー (Center)
            if (isEnter) {
                if (keyEvent.type == KeyEventType.KeyDown) {
                    if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                        keyDownStartTime = System.currentTimeMillis(); isLongPressProcessed = false
                    } else if (!isLongPressProcessed && (System.currentTimeMillis() - keyDownStartTime) > 600) {
                        isLongPressProcessed = true; ignoreNextKeyUp = true; isSubMenuOpen = true
                        activeSubMenuCategory = null; showOverlay = false; isManualOverlay = false; isPinnedOverlay = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (keyEvent.type == KeyEventType.KeyUp) {
                    if (ignoreNextKeyUp) { ignoreNextKeyUp = false; return@onPreviewKeyEvent true }
                    val now = System.currentTimeMillis()
                    if (now - lastKeyUpTime < 200) return@onPreviewKeyEvent true
                    lastKeyUpTime = now
                    when {
                        showOverlay -> { showOverlay = false; isManualOverlay = false; isPinnedOverlay = true }
                        isPinnedOverlay -> { isPinnedOverlay = false }
                        else -> { showOverlay = true; isManualOverlay = true; isPinnedOverlay = false }
                    }
                    return@onPreviewKeyEvent true
                }
            }

            // 5. 下キーでミニリスト
            if (!showOverlay && !isPinnedOverlay && keyEvent.type == KeyEventType.KeyDown && isDown) {
                onMiniListToggle(true); return@onPreviewKeyEvent true
            }
            false
        }) {
        // ... (AndroidView 等の描画部分は変更なし)
        AndroidView(factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } }, modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable())

        AnimatedVisibility(visible = isPinnedOverlay, enter = fadeIn(), exit = fadeOut()) {
            StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort)
        }
        AnimatedVisibility(visible = showOverlay, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            LiveOverlayUI(currentChannelItem, currentChannelItem.programPresent?.title ?: "番組情報なし", mirakurunIp, mirakurunPort, isManualOverlay, scrollState)
        }
        AnimatedVisibility(
            visible = isMiniListOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            ChannelListScreen(
                groupedChannels = groupedChannels,
                activeChannel = currentChannelItem,
                isMiniMode = true,
                onChannelClick = { channel ->
                    // 1. チャンネルを切り替える
                    onChannelSelect(channel)
                    // 2. リストを閉じる
                    onMiniListToggle(false)
                    // 3. プレイヤー本体にフォーカスを戻す
                    mainFocusRequester.requestFocus()
                },
                onDismiss = {
                    onMiniListToggle(false)
                    mainFocusRequester.requestFocus()
                }
            )
        }
        AnimatedVisibility(visible = isSubMenuOpen, enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(), exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()) {
            SubMenuOverlay(currentMode = currentAudioMode, activeCategory = activeSubMenuCategory, onCategorySelect = { activeSubMenuCategory = it }, onAudioModeSelect = { currentAudioMode = it; isSubMenuOpen = false; mainFocusRequester.requestFocus() }, onDismiss = { isSubMenuOpen = false; mainFocusRequester.requestFocus() })
        }
    }
}

// --- 以下、各UIコンポーネントの実装 ---

@Composable
fun StatusOverlay(channel: Channel, mirakurunIp: String, mirakurunPort: String) {
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while(true) { currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) } }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
        Row(modifier = Modifier.background(Color(0xFF1C1B1F).copy(0.8f), RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = "http://$mirakurunIp:$mirakurunPort/api/services/${buildStreamId(channel)}/logo", contentDescription = null, modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(2.dp)).background(Color.White), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(16.dp)); Text("${formatChannelType(channel.type)}${channel.channelNumber}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp)); Text(currentTime, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp), color = Color.White)
        }
    }
}

@Composable
fun LiveOverlayUI(channel: Channel, programTitle: String, mirakurunIp: String, mirakurunPort: String, showDesc: Boolean, scrollState: ScrollState) {
    val program = channel.programPresent
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var progress by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(program) {
        if (program != null && !program.startTime.isNullOrEmpty() && !program.endTime.isNullOrEmpty()) {
            val startMs = sdf.parse(program.startTime)?.time ?: 0L
            val endMs = sdf.parse(program.endTime)?.time ?: 0L
            val total = endMs - startMs
            if (total > 0) {
                while (System.currentTimeMillis() < endMs) {
                    progress = ((System.currentTimeMillis() - startMs).toFloat() / total).coerceIn(0f, 1f)
                    delay(5000)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f)))).padding(horizontal = 60.dp, vertical = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = "http://$mirakurunIp:$mirakurunPort/api/services/${buildStreamId(channel)}/logo", contentDescription = null, modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(4.dp)).background(Color.White), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(20.dp)); Text("${formatChannelType(channel.type)}${channel.channelNumber}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(0.9f))
            }
            Text(programTitle, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.padding(vertical = 12.dp))

            if (showDesc && program != null) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        if (!program.description.isNullOrEmpty()) Text(program.description, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(0.9f), modifier = Modifier.padding(bottom = 16.dp))
                        program.detail?.forEach { (k, v) ->
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Text("◇ $k", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(0.6f)))
                                Text(v, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.85f), modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                            }
                        }
                    }
                }
            }
            if (progress >= 0f) {
                val start = program?.startTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""
                val end = program?.endTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(start, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.7f))
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp).height(6.dp).background(Color.White.copy(0.2f), RoundedCornerShape(3.dp))) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White, RoundedCornerShape(3.dp)))
                    }
                    Text(end, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.7f))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SubMenuOverlay(currentMode: AudioMode, activeCategory: SubMenuCategory?, onCategorySelect: (SubMenuCategory?) -> Unit, onAudioModeSelect: (AudioMode) -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(activeCategory) { focusRequester.requestFocus() }
    Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onDismiss() }, contentAlignment = Alignment.CenterEnd) {
        Column(modifier = Modifier.fillMaxHeight().width(360.dp).background(Color(0xFF1C1B1F), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)).padding(24.dp).clickable(enabled = false) {}, verticalArrangement = Arrangement.Top) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                if (activeCategory != null) IconButton(onClick = { onCategorySelect(null) }) { Icon(Icons.Default.ArrowBack, "戻る", tint = Color.White) }
                Text(if (activeCategory == null) "設定" else "音声切替", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            if (activeCategory == null) {
                SubMenuListItem("音声切替", when(currentMode){ AudioMode.MAIN -> "主音声"; AudioMode.SUB -> "副音声"; else -> "ステレオ" }, { onCategorySelect(SubMenuCategory.AUDIO) }, focusRequester)
            } else {
                AudioMode.entries.forEachIndexed { i, m ->
                    Surface(onClick = { onAudioModeSelect(m) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if(i==0) Modifier.focusRequester(focusRequester) else Modifier), shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.05f), focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = (currentMode == m), onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(when(m){ AudioMode.MAIN -> "主音声"; AudioMode.SUB -> "副音声"; else -> "主+副 (ステレオ)" })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SubMenuListItem(title: String, subtitle: String, onClick: () -> Unit, fr: FocusRequester?) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (fr != null) Modifier.focusRequester(fr) else Modifier), shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.05f), focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(0.7f))
        }
    }
}

fun formatChannelType(type: String): String = when (type.uppercase()) { "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; else -> type }