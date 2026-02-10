package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.*
import kotlinx.coroutines.delay

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, VIDEO }

data class IndicatorState(val icon: ImageVector, val label: String, val timestamp: Long = System.currentTimeMillis())

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onBackPressed: () -> Unit,
    // ★追加: 視聴履歴更新用コールバック
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
    var isSubMenuOpen by remember { mutableStateOf(false) }
    var activeSubMenuCategory by remember { mutableStateOf<SubMenuCategory?>(null) }
    var indicatorState by remember { mutableStateOf<IndicatorState?>(null) }

    // コントロール表示状態
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

    // ★追加: 視聴履歴を定期的に保存する
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(10000) // 10秒ごとに保存
            if (exoPlayer.isPlaying) {
                val currentPosSec = exoPlayer.currentPosition / 1000.0
                onUpdateWatchHistory(program, currentPosSec)
            }
        }
    }

    // ★追加: 画面破棄時に最終位置を保存
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

    // DisposableEffectでリスナーを管理 (onDisposeは上記に統合しても良いが、ExoPlayerのライフサイクルとして分ける)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                showControls = !isPlaying
                // 停止時にも保存
                if (!isPlaying) {
                    val currentPosSec = exoPlayer.currentPosition / 1000.0
                    onUpdateWatchHistory(program, currentPosSec)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    showControls = !exoPlayer.playWhenReady
                }
                // 再生終了時
                if (state == Player.STATE_ENDED) {
                    onUpdateWatchHistory(program, exoPlayer.duration / 1000.0)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            // Player release logic handled in the other DisposableEffect
        }
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

@Composable
fun TopSubMenuUI(
    currentMode: AudioMode,
    activeCategory: SubMenuCategory?,
    onCategorySelect: (SubMenuCategory) -> Unit,
    onAudioModeSelect: (AudioMode) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(0.9f))
            .padding(24.dp)
    ) {
        if (activeCategory == null) {
            // メインメニュー
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MenuTileItem("音声切替", "現在の設定: ${if (currentMode == AudioMode.MAIN) "主音声" else "副音声"}", true) {
                    onCategorySelect(SubMenuCategory.AUDIO)
                }
            }
        } else {
            // サブメニュー
            when (activeCategory) {
                SubMenuCategory.AUDIO -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MenuTileItem("主音声", "", currentMode == AudioMode.MAIN) { onAudioModeSelect(AudioMode.MAIN) }
                        Spacer(Modifier.width(16.dp))
                        MenuTileItem("副音声", "", currentMode == AudioMode.SUB) { onAudioModeSelect(AudioMode.SUB) }
                    }
                }
                else -> {}
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MenuTileItem(title: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(0.1f),
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White
        ),
        modifier = Modifier.size(160.dp, 80.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}