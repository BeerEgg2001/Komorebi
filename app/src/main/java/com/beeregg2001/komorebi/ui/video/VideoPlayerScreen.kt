package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.*
import kotlinx.coroutines.delay

enum class AudioMode { MAIN, SUB }
// ★修正: SPEEDカテゴリを追加
enum class SubMenuCategory { AUDIO, VIDEO, SPEED }

data class IndicatorState(val icon: ImageVector, val label: String, val timestamp: Long = System.currentTimeMillis())

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onBackPressed: () -> Unit,
    onUpdateWatchHistory: (RecordedProgram, Double) -> Unit
) {
    val context = LocalContext.current
    val mainFocusRequester = remember { FocusRequester() }
    val sessionId = remember { UUID.randomUUID().toString() }
    val quality = "1080p-60fps"

    val playlistUrl = UrlBuilder.getVideoPlaylistUrl(konomiIp, konomiPort, program.recordedVideo.id, sessionId, quality)

    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
        }
    }

    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    // ★追加: 現在の再生速度
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }

    var isSubMenuOpen by remember { mutableStateOf(false) }
    var activeSubMenuCategory by remember { mutableStateOf<SubMenuCategory?>(null) }
    var indicatorState by remember { mutableStateOf<IndicatorState?>(null) }

    var showControls by remember { mutableStateOf(true) }

    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build()
            }
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("DTVClient/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Connection" to "keep-alive"))

        val mediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(playlistUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                setMediaItem(mediaItem)
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
                prepare()
                playWhenReady = true
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

    // ★追加: 再生速度を適用する関数
    fun applyPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        exoPlayer.setPlaybackSpeed(speed)
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(10000)
            if (exoPlayer.isPlaying) {
                val currentPosSec = exoPlayer.currentPosition / 1000.0
                onUpdateWatchHistory(program, currentPosSec)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val currentPosSec = exoPlayer.currentPosition / 1000.0
            if (currentPosSec > 0) {
                onUpdateWatchHistory(program, currentPosSec)
            }
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    BackHandler {
        onBackPressed()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                showControls = !isPlaying
                if (!isPlaying) {
                    val currentPosSec = exoPlayer.currentPosition / 1000.0
                    onUpdateWatchHistory(program, currentPosSec)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    showControls = !exoPlayer.playWhenReady
                }
                if (state == Player.STATE_ENDED) {
                    onUpdateWatchHistory(program, exoPlayer.duration / 1000.0)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {}
    }

    LaunchedEffect(Unit) {
        mainFocusRequester.requestFocus()
    }

    LaunchedEffect(indicatorState) {
        if (indicatorState != null) {
            delay(1200)
            indicatorState = null
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            if (exoPlayer.isPlaying) {
                showControls = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keyCode = keyEvent.nativeKeyEvent.keyCode

                if (isSubMenuOpen) {
                    if (keyCode == NativeKeyEvent.KEYCODE_BACK) {
                        if (activeSubMenuCategory != null) {
                            activeSubMenuCategory = null
                        } else {
                            isSubMenuOpen = false
                        }
                        mainFocusRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }

                showControls = true

                when (keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            indicatorState = IndicatorState(Icons.Default.Pause, "停止")
                        } else {
                            exoPlayer.play()
                            indicatorState = IndicatorState(Icons.Default.PlayArrow, "再生")
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition + 30000)
                        indicatorState = IndicatorState(Icons.Default.FastForward, "+30s")
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
                        indicatorState = IndicatorState(Icons.Default.FastRewind, "-10s")
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                        isSubMenuOpen = true
                        activeSubMenuCategory = null
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = exoPlayer
                    this.useController = false
                    this.keepScreenOn = true
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(mainFocusRequester)
                .focusable()
        )

        PlaybackIndicator(indicatorState)

        PlayerControls(
            exoPlayer = exoPlayer,
            title = program.title,
            isVisible = showControls && !isSubMenuOpen
        )

        AnimatedVisibility(
            visible = isSubMenuOpen,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            // ★修正: 再生速度情報を渡す
            TopSubMenuUI(
                currentMode = currentAudioMode,
                currentSpeed = currentSpeed,
                activeCategory = activeSubMenuCategory,
                onCategorySelect = { activeSubMenuCategory = it },
                onAudioModeSelect = { mode ->
                    currentAudioMode = mode
                    applyAudioStream(mode)
                    isSubMenuOpen = false
                    mainFocusRequester.requestFocus()
                },
                onSpeedSelect = { speed ->
                    applyPlaybackSpeed(speed)
                    isSubMenuOpen = false
                    mainFocusRequester.requestFocus()
                }
            )
        }
    }
}

@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    title: String,
    isVisible: Boolean
) {
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isVisible) {
        while (isVisible) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0)
            duration = exoPlayer.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(0.9f), Color.Transparent)
                        )
                    )
                    .padding(top = 40.dp, bottom = 60.dp, start = 48.dp, end = 48.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(0.95f))
                        )
                    )
                    .padding(start = 48.dp, end = 48.dp, bottom = 48.dp, top = 60.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    LinearProgressIndicator(
                        progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 24.dp)
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(0.3f)
                    )

                    Text(
                        text = formatTime(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackIndicator(state: IndicatorState?) {
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        if (state != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(0.7f), MaterialTheme.shapes.large)
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(state.icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(state.label, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

// ★修正: LivePlayerScreenと統一感のあるUIへ変更
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopSubMenuUI(
    currentMode: AudioMode,
    currentSpeed: Float,
    activeCategory: SubMenuCategory?,
    onCategorySelect: (SubMenuCategory?) -> Unit,
    onAudioModeSelect: (AudioMode) -> Unit,
    onSpeedSelect: (Float) -> Unit
) {
    val categoryFocusRequester = remember { FocusRequester() }
    val itemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(activeCategory) {
        if (activeCategory == null) categoryFocusRequester.requestFocus()
        else itemFocusRequester.requestFocus()
    }

    val chipColors = FilterChipDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = Color.White.copy(0.7f),
        selectedContainerColor = Color.White,
        selectedContentColor = Color.Black,
        focusedContainerColor = Color.White.copy(0.2f),
        focusedContentColor = Color.White,
        focusedSelectedContainerColor = Color.White,
        focusedSelectedContentColor = Color.Black
    )

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
                    title = "再生速度",
                    icon = Icons.Default.FastForward,
                    subtitle = "${currentSpeed}x",
                    isSelected = activeCategory == SubMenuCategory.SPEED,
                    onClick = { onCategorySelect(SubMenuCategory.SPEED) },
                )
            }

            Spacer(Modifier.height(24.dp))

            AnimatedContent(targetState = activeCategory, label = "submenu") { category ->
                when (category) {
                    SubMenuCategory.AUDIO -> {
                        Row(
                            modifier = Modifier.background(Color.White.copy(0.1f), RoundedCornerShape(16.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AudioMode.values().forEachIndexed { index, mode ->
                                FilterChip(
                                    selected = (currentMode == mode),
                                    onClick = { onAudioModeSelect(mode) },
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp)
                                        .then(if(index == 0) Modifier.focusRequester(itemFocusRequester) else Modifier),
                                    colors = chipColors,
                                ){ Text(if(mode == AudioMode.MAIN) "主音声" else "副音声", fontWeight = FontWeight.Medium) }
                            }
                        }
                    }
                    SubMenuCategory.SPEED -> {
                        Row(
                            modifier = Modifier.background(Color.White.copy(0.1f), RoundedCornerShape(16.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val speeds = listOf(0.75f, 1.0f, 1.5f, 2.0f)
                            speeds.forEachIndexed { index, speed ->
                                FilterChip(
                                    selected = (currentSpeed == speed),
                                    onClick = { onSpeedSelect(speed) },
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp)
                                        .then(if(index == 0) Modifier.focusRequester(itemFocusRequester) else Modifier),
                                    colors = chipColors,
                                ){ Text("${speed}x", fontWeight = FontWeight.Medium) }
                            }
                        }
                    }
                    else -> Box(modifier = Modifier.height(56.dp))
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