@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class)

package com.example.komorebi.ui.live

import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
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
import androidx.tv.material3.* import coil.compose.AsyncImage
import com.example.komorebi.ChannelListScreen
import com.example.komorebi.NativeLib
import com.example.komorebi.buildStreamId
import com.example.komorebi.util.TsReadExDataSourceFactory
import com.example.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, VIDEO }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String,
    mirakurunPort: String,
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onBackPressed: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val currentChannelItem by remember(channel.id, groupedChannels) {
        derivedStateOf { groupedChannels.values.flatten().find { it.id == channel.id } ?: channel }
    }

    val nativeLib = remember { NativeLib() }
    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    var showOverlay by remember { mutableStateOf(false) }
    var isManualOverlay by remember { mutableStateOf(false) }
    var isPinnedOverlay by remember { mutableStateOf(false) }
    var isSubMenuOpen by remember { mutableStateOf(false) }
    var activeSubMenuCategory by remember { mutableStateOf<SubMenuCategory?>(null) }

    val mainFocusRequester = remember { FocusRequester() }
    val tsDataSourceFactory = remember { TsReadExDataSourceFactory(nativeLib, arrayOf()) }

    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
        }
    }

    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build()
            }
        }
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        }
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(tsDataSourceFactory, extractorsFactory))
            .build().apply {
                playWhenReady = true
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
            }
    }

    fun applyAudioStream(mode: AudioMode) {
        val tracks = exoPlayer.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isNotEmpty()) {
            val targetIndex = if (mode == AudioMode.SUB && audioGroups.size >= 2) 1 else 0
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(TrackSelectionOverride(audioGroups[targetIndex].mediaTrackGroup, 0))
                .build()
        }
        audioProcessor.putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
    }

    LaunchedEffect(currentChannelItem.id) {
        isManualOverlay = false; isPinnedOverlay = false; showOverlay = true; scrollState.scrollTo(0)
        tsDataSourceFactory.tsArgs = arrayOf("-x", "18/38/39", "-n", currentChannelItem.serviceId.toString(), "-a", "13", "-b", "5", "-c", "5")
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
            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val keyCode = keyEvent.nativeKeyEvent.keyCode
            val isEnter = keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER || keyCode == NativeKeyEvent.KEYCODE_ENTER
            val isBack = keyCode == NativeKeyEvent.KEYCODE_BACK
            val isUp = keyCode == NativeKeyEvent.KEYCODE_DPAD_UP
            val isDown = keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN

            if (isSubMenuOpen || isMiniListOpen) {
                if (isBack) {
                    if (isSubMenuOpen) {
                        if (activeSubMenuCategory != null) activeSubMenuCategory = null
                        else isSubMenuOpen = false
                    } else onMiniListToggle(false)
                    mainFocusRequester.requestFocus()
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }

            if (isBack) {
                if (showOverlay || isPinnedOverlay) {
                    showOverlay = false; isPinnedOverlay = false; isManualOverlay = false
                } else onBackPressed()
                return@onPreviewKeyEvent true
            }

            if (showOverlay && isManualOverlay && (isUp || isDown)) {
                coroutineScope.launch { scrollState.animateScrollBy(if (isUp) -300f else 300f) }
                return@onPreviewKeyEvent true
            }

            if (isEnter) {
                when {
                    showOverlay -> { showOverlay = false; isManualOverlay = false; isPinnedOverlay = true }
                    isPinnedOverlay -> { isPinnedOverlay = false }
                    else -> { showOverlay = true; isManualOverlay = true; isPinnedOverlay = false }
                }
                return@onPreviewKeyEvent true
            }

            if (isUp && !showOverlay && !isPinnedOverlay) {
                isSubMenuOpen = true
                activeSubMenuCategory = null
                return@onPreviewKeyEvent true
            }

            if (isDown && !showOverlay && !isPinnedOverlay) {
                onMiniListToggle(true)
                return@onPreviewKeyEvent true
            }
            false
        }) {

        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable()
        )

        AnimatedVisibility(visible = isPinnedOverlay, enter = fadeIn(), exit = fadeOut()) {
            StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort)
        }

        AnimatedVisibility(visible = showOverlay, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            LiveOverlayUI(currentChannelItem, currentChannelItem.programPresent?.title ?: "番組情報なし", mirakurunIp, mirakurunPort, isManualOverlay, scrollState)
        }

        AnimatedVisibility(visible = isMiniListOpen, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            ChannelListScreen(groupedChannels = groupedChannels, activeChannel = currentChannelItem, isMiniMode = true,
                onChannelClick = { onChannelSelect(it); onMiniListToggle(false); mainFocusRequester.requestFocus() },
                onDismiss = { onMiniListToggle(false); mainFocusRequester.requestFocus() }
            )
        }

        AnimatedVisibility(visible = isSubMenuOpen, enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()) {
            TopSubMenuUI(
                currentMode = currentAudioMode,
                activeCategory = activeSubMenuCategory,
                onCategorySelect = { activeSubMenuCategory = it },
                onAudioModeSelect = { mode ->
                    currentAudioMode = mode
                    applyAudioStream(mode)
                    isSubMenuOpen = false
                    mainFocusRequester.requestFocus()
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopSubMenuUI(
    currentMode: AudioMode,
    activeCategory: SubMenuCategory?,
    onCategorySelect: (SubMenuCategory?) -> Unit,
    onAudioModeSelect: (AudioMode) -> Unit
) {
    val categoryFocusRequester = remember { FocusRequester() }
    val itemFocusRequester = remember { FocusRequester() }

    // カテゴリ選択時に下のアイテムにフォーカスを移す制御
    LaunchedEffect(activeCategory) {
        if (activeCategory == null) {
            categoryFocusRequester.requestFocus()
        } else {
            // アニメーション完了を待たずに即座にリクエスト（必要に応じて微小なdelayを入れる）
            itemFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent)))
            .padding(top = 24.dp, bottom = 60.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center) {
                MenuTileItem(
                    title = "音声切替",
                    icon = Icons.Default.PlayArrow,
                    subtitle = if(currentMode == AudioMode.MAIN) "主音声" else "副音声",
                    isSelected = activeCategory == SubMenuCategory.AUDIO,
                    onClick = { onCategorySelect(SubMenuCategory.AUDIO) },
                    modifier = Modifier.focusRequester(categoryFocusRequester)
                )
                Spacer(Modifier.width(20.dp))
                MenuTileItem(
                    title = "映像設定",
                    icon = Icons.Default.Settings,
                    subtitle = "標準",
                    isSelected = activeCategory == SubMenuCategory.VIDEO,
                    onClick = { /* 今後実装可能 */ },
                )
            }

            Spacer(Modifier.height(24.dp))

            AnimatedContent(targetState = activeCategory, label = "submenu") { category ->
                if (category == SubMenuCategory.AUDIO) {
                    Row(
                        modifier = Modifier
                            .background(Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AudioMode.values().forEachIndexed { index, mode ->
                            FilterChip(
                                selected = (currentMode == mode),
                                onClick = { onAudioModeSelect(mode) },
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    // 最初の要素にフォーカスリクエスターを付与
                                    .then(if(index == 0) Modifier.focusRequester(itemFocusRequester) else Modifier),
                                colors = FilterChipDefaults.colors(
                                    containerColor = Color.Transparent,
                                    contentColor = Color.White.copy(0.7f),
                                    selectedContainerColor = Color.White,
                                    selectedContentColor = Color.Black,
                                    focusedContainerColor = Color.White.copy(0.2f),
                                    focusedContentColor = Color.White,
                                    focusedSelectedContainerColor = Color.White,
                                    focusedSelectedContentColor = Color.Black
                                )
                            ) {
                                Text(if(mode == AudioMode.MAIN) "主音声" else "副音声", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                } else {
                    // 非表示時はプレースホルダーで高さを維持（ガタつき防止）
                    Box(modifier = Modifier.height(56.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MenuTileItem(title: String, icon: ImageVector, subtitle: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(0.15f) else Color.White.copy(0.05f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(160.dp, 84.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = if (isSelected) Color.Unspecified else LocalContentColor.current.copy(0.6f))
        }
    }
}

// --- 以下の Overlay 類はモノトーンを強調して維持 ---

@Composable
fun StatusOverlay(channel: Channel, mirakurunIp: String, mirakurunPort: String) {
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while(true) { currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) } }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
        Row(modifier = Modifier.background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Column(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f)))).padding(horizontal = 64.dp, vertical = 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = "http://$mirakurunIp:$mirakurunPort/api/services/${buildStreamId(channel)}/logo", contentDescription = null, modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(4.dp)).background(Color.White), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(24.dp)); Text("${formatChannelType(channel.type)}${channel.channelNumber}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(0.8f))
            }
            Text(programTitle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp), color = Color.White, modifier = Modifier.padding(vertical = 16.dp))

            if (showDesc && program != null) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        if (!program.description.isNullOrEmpty()) Text(program.description, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(0.9f), modifier = Modifier.padding(bottom = 20.dp))
                        program.detail?.forEach { (k, v) ->
                            Column(Modifier.padding(bottom = 14.dp)) {
                                Text("◆ $k", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(0.5f)))
                                Text(v, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.85f), modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                            }
                        }
                    }
                }
            }
            if (progress >= 0f) {
                val start = program?.startTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""
                val end = program?.endTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(start, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.5f))
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 20.dp).height(4.dp).background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White, RoundedCornerShape(2.dp)))
                    }
                    Text(end, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.5f))
                }
            }
        }
    }
}

fun formatChannelType(type: String): String = when (type.uppercase()) { "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; else -> type }