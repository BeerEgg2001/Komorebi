@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.util.Base64
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "VideoPlayerScreen"

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    initialPositionMs: Long = 0,
    initialQuality: String = "1080p-60fps",
    konomiIp: String,
    konomiPort: String,
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    isSceneSearchOpen: Boolean,
    onSceneSearchToggle: (Boolean) -> Unit,
    onBackPressed: () -> Unit,
    onShowToast: (String) -> Unit,
    recordViewModel: RecordViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    // ★State Holder
    val vs = rememberVideoPlayerState(initialQuality)

    // 設定値の監視
    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()
    val videoSubtitleDefaultStr by settingsViewModel.videoSubtitleDefault.collectAsState()

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    // 初期化設定
    LaunchedEffect(commentDefaultDisplayStr) {
        vs.isCommentEnabled = commentDefaultDisplayStr == "ON"
    }
    LaunchedEffect(videoSubtitleDefaultStr) {
        vs.isSubtitleEnabled = videoSubtitleDefaultStr == "ON"
    }

    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }

    val allComments =
        remember { mutableStateListOf<com.beeregg2001.komorebi.data.model.ArchivedComment>() }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    val currentSessionId = remember(vs.currentQuality) { UUID.randomUUID().toString() }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }

    // コメント取得
    LaunchedEffect(program.recordedVideo.id) {
        allComments.clear()
        allComments.addAll(recordViewModel.getArchivedComments(program.recordedVideo.id))
    }

    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
        }
    }

    // ExoPlayer初期化
    val exoPlayer = remember(vs.currentQuality) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            init {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            }

            override fun buildAudioSink(
                ctx: android.content.Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? =
                DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build()
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("DTVClient/1.0")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(HlsMediaSource.Factory(httpDataSourceFactory)).build().apply {
            val mediaItem = MediaItem.Builder().setUri(
                UrlBuilder.getVideoPlaylistUrl(
                    konomiIp,
                    konomiPort,
                    program.recordedVideo.id,
                    currentSessionId,
                    vs.currentQuality.value
                )
            ).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            setMediaItem(mediaItem)
            setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA).build(), true
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    vs.isPlayerPlaying = playing
                }

                override fun onMetadata(metadata: Metadata) {
                    if (!vs.isSubtitleEnabled) return
                    for (i in 0 until metadata.length()) {
                        val entry = metadata.get(i)
                        if (entry is PrivFrame && (entry.owner.contains(
                                "aribb24",
                                true
                            ) || entry.owner.contains("B24", true))
                        ) {
                            val base64Data =
                                Base64.encodeToString(entry.privateData, Base64.NO_WRAP)
                            webViewRef.value?.post {
                                webViewRef.value?.evaluateJavascript(
                                    "if(window.receiveSubtitleData){ window.receiveSubtitleData($currentPosition, '$base64Data'); }",
                                    null
                                )
                            }
                        }
                    }
                }
            })
            if (initialPositionMs > 0) seekTo(initialPositionMs)
            prepare(); playWhenReady = true
        }
    }

    // シーンサーチ連動
    LaunchedEffect(isSceneSearchOpen) {
        if (isSceneSearchOpen) {
            vs.wasPlayingBeforeSceneSearch =
                exoPlayer.isPlaying; if (vs.wasPlayingBeforeSceneSearch) exoPlayer.pause()
        } else if (vs.wasPlayingBeforeSceneSearch) exoPlayer.play()
    }

    // ストリーム維持
    DisposableEffect(vs.currentQuality, currentSessionId) {
        recordViewModel.startStreamMaintenance(
            program,
            vs.currentQuality.value,
            currentSessionId
        ) { exoPlayer.currentPosition / 1000.0 }
        onDispose { recordViewModel.stopStreamMaintenance() }
    }

    // コントロール自動非表示
    LaunchedEffect(showControls, isSubMenuOpen, isSceneSearchOpen, vs.lastInteractionTime) {
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen) {
            delay(5000); onShowControlsChange(false)
        }
    }

    // フォーカス管理
    LaunchedEffect(isSubMenuOpen, isSceneSearchOpen, showControls) {
        delay(150); if (isSubMenuOpen) subMenuFocusRequester.safeRequestFocus(TAG) else if (!isSceneSearchOpen && !showControls) mainFocusRequester.safeRequestFocus(
        TAG
    )
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(colors.background)
        .onKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown || isSubMenuOpen || isSceneSearchOpen) return@onKeyEvent false
            onShowControlsChange(true); vs.lastInteractionTime = System.currentTimeMillis()
            when (keyEvent.nativeKeyEvent.keyCode) {
                NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                    vs.togglePlayPause(exoPlayer.isPlaying); if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play(); true
                }

                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    exoPlayer.seekTo(exoPlayer.currentPosition + 30000); vs.updateIndicator(
                        Icons.Default.FastForward,
                        "+30s"
                    ); true
                }

                NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                    exoPlayer.seekTo(exoPlayer.currentPosition - 10000); vs.updateIndicator(
                        Icons.Default.FastRewind,
                        "-10s"
                    ); true
                }

                NativeKeyEvent.KEYCODE_DPAD_UP -> {
                    onSubMenuToggle(true); true
                }

                NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                    onSceneSearchToggle(true); true
                }

                else -> false
            }
        }) {
        AndroidView(factory = {
            PlayerView(it).apply {
                player = exoPlayer; useController = false; resizeMode =
                AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true
            }
        }, modifier = Modifier
            .fillMaxSize()
            .focusRequester(mainFocusRequester)
            .focusable())

        val commentLayer = @Composable {
            if (isHeavyUiReady) {
                ArchivedCommentOverlay(
                    Modifier.fillMaxSize(),
                    allComments,
                    { exoPlayer.currentPosition },
                    vs.isPlayerPlaying,
                    vs.isCommentEnabled,
                    commentSpeed,
                    commentFontSizeScale,
                    commentOpacity,
                    commentMaxLines,
                    isEmulator
                )
            }
        }
        val subtitleLayer = @Composable {
            if (isHeavyUiReady) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                -1,
                                -1
                            ); setBackgroundColor(android.graphics.Color.TRANSPARENT); settings.apply {
                            javaScriptEnabled = true; domStorageEnabled = true
                        }; loadUrl("file:///android_asset/subtitle_renderer.html"); webViewRef.value =
                            this
                        }
                    },
                    update = {
                        it.visibility =
                            if (vs.isSubtitleEnabled) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (subtitleCommentLayer == "CommentOnTop") {
            subtitleLayer(); commentLayer()
        } else {
            commentLayer(); subtitleLayer()
        }

        PlayerControls(
            exoPlayer,
            program.title,
            showControls && !isSubMenuOpen && !isSceneSearchOpen
        )

        AnimatedVisibility(
            isSceneSearchOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            SceneSearchOverlay(
                program,
                exoPlayer.currentPosition,
                konomiIp,
                konomiPort,
                {
                    exoPlayer.seekTo(it); onSceneSearchToggle(false); scope.launch {
                    delay(200); mainFocusRequester.safeRequestFocus(TAG)
                }
                },
                {
                    onSceneSearchToggle(false); scope.launch {
                    delay(200); mainFocusRequester.safeRequestFocus(TAG)
                }
                })
        }

        AnimatedVisibility(
            isSubMenuOpen,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()) {
            VideoTopSubMenuUI(
                vs.currentAudioMode,
                vs.currentSpeed,
                vs.isSubtitleEnabled,
                vs.currentQuality,
                vs.isCommentEnabled,
                subMenuFocusRequester,
                {
                    vs.currentAudioMode =
                        if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN;
                    val tracks =
                        exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }; if (tracks.size >= 2) exoPlayer.trackSelectionParameters =
                    exoPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(
                        TrackSelectionOverride(
                            tracks[if (vs.currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                            0
                        )
                    )
                        .build(); onShowToast("音声: ${if (vs.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                },
                {
                    val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f); vs.currentSpeed =
                    speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]; exoPlayer.setPlaybackSpeed(
                    vs.currentSpeed
                ); onShowToast("速度: ${vs.currentSpeed}x")
                },
                {
                    vs.isSubtitleEnabled =
                        !vs.isSubtitleEnabled; onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                },
                { vs.currentQuality = it; onShowToast("画質: ${it.label}") },
                {
                    vs.isCommentEnabled =
                        !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                }
            )
        }
        PlaybackIndicator(vs.indicatorState)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause(); recordViewModel.updateWatchHistory(
                    program,
                    exoPlayer.currentPosition / 1000.0
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            recordViewModel.updateWatchHistory(
                program,
                exoPlayer.currentPosition / 1000.0
            ); lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release()
        }
    }
}