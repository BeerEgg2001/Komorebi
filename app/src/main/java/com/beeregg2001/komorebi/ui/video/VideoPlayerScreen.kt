package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.media3.common.Player
import androidx.tv.material3.*

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, VIDEO }

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val mainFocusRequester = remember { FocusRequester() }
    val sessionId = remember { UUID.randomUUID().toString() }
    val quality = "1080p-60fps"
    val playlistUrl = "$konomiIp:$konomiPort/api/streams/video/${program.recordedVideo.id}/$quality/playlist?session_id=$sessionId"

    // 音声処理用のProcessor
    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
        }
    }

    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    var isSubMenuOpen by remember { mutableStateOf(false) }
    var activeSubMenuCategory by remember { mutableStateOf<SubMenuCategory?>(null) }

    // ExoPlayerのインスタンス作成
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

    // 音声ストリームの適用関数
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

    var showControls by remember { mutableStateOf(true) }

    BackHandler {
        onBackPressed()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                showControls = !isPlaying
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    LaunchedEffect(Unit) {
        mainFocusRequester.requestFocus()
    }

    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    showControls = !exoPlayer.playWhenReady
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                showControls = !playWhenReady
            }
        }
        exoPlayer.addListener(listener)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keyCode = keyEvent.nativeKeyEvent.keyCode

                // サブメニューが開いている時の処理
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

                when (keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition + 30000)
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
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

        // 録画番組用コントロールバー
        PlayerControls(
            exoPlayer = exoPlayer,
            title = program.title,
            isVisible = showControls && !isSubMenuOpen,
            onVisibilityChanged = {
                if (!exoPlayer.isPlaying) {
                    showControls = true
                } else {
                    showControls = it
                }
            }
        )

        // 音声切替用サブメニュー (LivePlayerScreenから移植)
        AnimatedVisibility(
            visible = isSubMenuOpen,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
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

    LaunchedEffect(activeCategory) {
        if (activeCategory == null) {
            categoryFocusRequester.requestFocus()
        } else {
            itemFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent)))
            .padding(top = 24.dp, bottom = 60.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center) {
                MenuTileItem(
                    title = "音声切替",
                    icon = Icons.Default.PlayArrow,
                    subtitle = if (currentMode == AudioMode.MAIN) "主音声" else "副音声",
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
                                    .then(if (index == 0) Modifier.focusRequester(itemFocusRequester) else Modifier),
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
                                Text(if (mode == AudioMode.MAIN) "主音声" else "副音声", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.height(56.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MenuTileItem(
    title: String,
    icon: ImageVector,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = if (isSelected) Color.Unspecified else LocalContentColor.current.copy(0.6f)
            )
        }
    }
}