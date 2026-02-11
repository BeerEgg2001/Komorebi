@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.util.UUID
import androidx.tv.material3.*
import kotlinx.coroutines.delay

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    // ★外部管理される状態
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    isSceneSearchOpen: Boolean,
    onSceneSearchToggle: (Boolean) -> Unit,
    onBackPressed: () -> Unit,
    onUpdateWatchHistory: (RecordedProgram, Double) -> Unit
) {
    val context = LocalContext.current
    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val sessionId = remember { UUID.randomUUID().toString() }

    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var indicatorState by remember { mutableStateOf<IndicatorState?>(null) }
    var toastState by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var isPlayerPlaying by remember { mutableStateOf(false) }

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
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("DTVClient/1.0")
            .setAllowCrossProtocolRedirects(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(HlsMediaSource.Factory(httpDataSourceFactory))
            .build().apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(UrlBuilder.getVideoPlaylistUrl(konomiIp, konomiPort, program.recordedVideo.id, sessionId))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                setMediaItem(mediaItem)
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlayerPlaying = playing }
                })
                prepare(); playWhenReady = true
            }
    }

    // 自動消去タイマー
    LaunchedEffect(showControls, isPlayerPlaying, isSubMenuOpen, isSceneSearchOpen) {
        if (showControls && isPlayerPlaying && !isSubMenuOpen && !isSceneSearchOpen) {
            delay(5000)
            onShowControlsChange(false)
        }
    }

    LaunchedEffect(toastState) { if (toastState != null) { delay(2000); toastState = null } }
    LaunchedEffect(indicatorState) { if (indicatorState != null) { delay(1200); indicatorState = null } }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                if (isSubMenuOpen || isSceneSearchOpen) return@onKeyEvent false

                onShowControlsChange(true)
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
                        indicatorState = IndicatorState(Icons.Default.FastForward, "+30s"); true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
                        indicatorState = IndicatorState(Icons.Default.FastRewind, "-10s"); true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { onSubMenuToggle(true); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { onSceneSearchToggle(true); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable()
        )

        PlayerControls(
            exoPlayer = exoPlayer,
            title = program.title,
            isVisible = showControls && !isSubMenuOpen && !isSceneSearchOpen
        )

        AnimatedVisibility(
            visible = isSceneSearchOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            SceneSearchOverlay(
                videoId = program.recordedVideo.id,
                durationMs = exoPlayer.duration,
                currentPositionMs = exoPlayer.currentPosition,
                konomiIp = konomiIp, konomiPort = konomiPort,
                onSeekRequested = { time ->
                    exoPlayer.seekTo(time)
                    onSceneSearchToggle(false)
                    mainFocusRequester.requestFocus()
                },
                onClose = {
                    // SceneSearchOverlay内部のBackイベントで呼ばれる
                    onSceneSearchToggle(false)
                    mainFocusRequester.requestFocus()
                }
            )
        }

        AnimatedVisibility(visible = isSubMenuOpen, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            VideoTopSubMenuUI(
                currentAudioMode = currentAudioMode, currentSpeed = currentSpeed,
                focusRequester = subMenuFocusRequester,
                onAudioToggle = {
                    currentAudioMode = if(currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val tracks = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (tracks.size >= 2) {
                        val target = if (currentAudioMode == AudioMode.SUB) 1 else 0
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(TrackSelectionOverride(tracks[target].mediaTrackGroup, 0))
                            .build()
                    }
                    toastState = "音声: ${if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}" to System.currentTimeMillis()
                },
                onSpeedToggle = {
                    val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f)
                    currentSpeed = speeds[(speeds.indexOf(currentSpeed) + 1) % speeds.size]
                    exoPlayer.setPlaybackSpeed(currentSpeed)
                    toastState = "再生速度: ${currentSpeed}x" to System.currentTimeMillis()
                }
            )
        }

        PlaybackIndicator(indicatorState)
        VideoToast(toastState)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
                onUpdateWatchHistory(program, exoPlayer.currentPosition / 1000.0)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            onUpdateWatchHistory(program, exoPlayer.currentPosition / 1000.0)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }
}