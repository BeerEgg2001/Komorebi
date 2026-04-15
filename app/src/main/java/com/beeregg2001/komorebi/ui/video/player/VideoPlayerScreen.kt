@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.SurfaceView // ★ TextureViewからSurfaceViewに変更
import android.view.View
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.ui.AspectRatioFrameLayout
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "VideoPlayerScreen"
private const val DEBUG_TAG = "ChapterDebug"

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
    isPiPMode: Boolean = false,
    onPiPRequested: () -> Unit = {},
    videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentProgram by remember { mutableStateOf(program) }
    val fetchedDetail by videoPlayerViewModel.programDetail.collectAsState()

    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(program.id) {
        videoPlayerViewModel.fetchProgramDetail(program.id)
    }

    LaunchedEffect(fetchedDetail) {
        if (fetchedDetail != null && fetchedDetail?.id == program.id) {
            currentProgram = fetchedDetail!!
        }
    }

    val vs = rememberVideoPlayerState(initialQuality)

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

    LaunchedEffect(commentDefaultDisplayStr) {
        vs.isCommentEnabled = commentDefaultDisplayStr == "ON"
    }
    LaunchedEffect(videoSubtitleDefaultStr) {
        vs.isSubtitleEnabled = videoSubtitleDefaultStr == "ON"
    }

    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }

    val allComments = remember { mutableStateListOf<ArchivedComment>() }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    val currentSessionId = remember(vs.currentQuality) { UUID.randomUUID().toString() }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }

    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }

    var isChapterListOpen by remember { mutableStateOf(false) }
    var rightKeyDownTime by remember { mutableLongStateOf(0L) }
    var isRightKeyLongPressed by remember { mutableStateOf(false) }
    var leftKeyDownTime by remember { mutableLongStateOf(0L) }
    var isLeftKeyLongPressed by remember { mutableStateOf(false) }
    var downKeyDownTime by remember { mutableLongStateOf(0L) }
    var isDownKeyLongPressed by remember { mutableStateOf(false) }

    LaunchedEffect(program.recordedVideo.id) {
        allComments.clear()
        allComments.addAll(videoPlayerViewModel.getArchivedComments(program.recordedVideo.id))
    }

    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(true)
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("DTVClient/1.0"); setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(90000); setReadTimeoutMs(90000)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)
        val loadControl =
            DefaultLoadControl.Builder().setBufferDurationsMs(30000, 30000, 1500, 3000)
                .setPrioritizeTimeOverSizeThresholds(true).build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(), true
                )
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        videoWidth = videoSize.width; videoHeight =
                            videoSize.height; pixelWidthHeightRatio =
                            videoSize.pixelWidthHeightRatio
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        vs.isPlayerPlaying = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = (playbackState == Player.STATE_BUFFERING)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)
                        scope.launch {
                            isBuffering = true; delay(3000L); prepare(); playWhenReady = true
                        }
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
            }
    }

    LaunchedEffect(program.recordedVideo.id, vs.currentQuality) {
        isBuffering = true
        val url = videoPlayerViewModel.resolveStreamUrl(
            program.recordedVideo.id,
            vs.currentQuality.value,
            currentSessionId
        )
        if (url.isNotEmpty()) {
            val mediaItemBuilder = MediaItem.Builder().setUri(url)

            if (url.contains("/api/streams/video/")) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                Log.i(TAG, "Playing as HLS (KonomiTV): $url")
            } else if (url.endsWith(".ts", ignoreCase = true)) {
                Log.i(TAG, "Playing as Raw TS (EDCB): $url")
            }

            val mediaItem = mediaItemBuilder.build()

            exoPlayer.setMediaItem(mediaItem)
            if (initialPositionMs > 0 && exoPlayer.currentPosition == 0L) {
                exoPlayer.seekTo(initialPositionMs)
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            onShowToast("ストリームURLの取得に失敗しました")
        }
    }

    LaunchedEffect(isSceneSearchOpen, isChapterListOpen) {
        if (isSceneSearchOpen || isChapterListOpen) {
            vs.wasPlayingBeforeSceneSearch = exoPlayer.isPlaying
            if (vs.wasPlayingBeforeSceneSearch) exoPlayer.pause()
        } else if (vs.wasPlayingBeforeSceneSearch) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(vs.indicatorState) {
        if (vs.indicatorState != null) {
            delay(2000); vs.indicatorState = null
        }
    }

    DisposableEffect(vs.currentQuality, currentSessionId) {
        videoPlayerViewModel.startStreamMaintenance(
            program,
            vs.currentQuality.value,
            currentSessionId
        ) { exoPlayer.currentPosition / 1000.0 }
        onDispose { videoPlayerViewModel.stopStreamMaintenance() }
    }

    LaunchedEffect(
        showControls,
        isSubMenuOpen,
        isSceneSearchOpen,
        isChapterListOpen,
        vs.lastInteractionTime
    ) {
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen) {
            delay(5000); onShowControlsChange(false)
        }
    }

    LaunchedEffect(isSubMenuOpen, isSceneSearchOpen, isChapterListOpen, showControls) {
        if (isPiPMode) return@LaunchedEffect
        delay(150)
        if (isSubMenuOpen) {
            subMenuFocusRequester.safeRequestFocus(TAG)
        } else if (!isSceneSearchOpen && !isChapterListOpen && !showControls && vs.lCropMode == LCropMode.HIDDEN) {
            mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (isPiPMode) return@onKeyEvent false

                if (vs.lCropMode == LCropMode.DIRECT_ADJUST) {
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    val isTargetKey = keyCode in listOf(
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_BACK,
                        android.view.KeyEvent.KEYCODE_ESCAPE
                    )
                    if (isTargetKey) {
                        val isActionDown = keyEvent.type == KeyEventType.KeyDown
                        if (!isActionDown) return@onKeyEvent true
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> vs.lCropY -= 2f
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> vs.lCropY += 2f
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> vs.lCropX -= 2f
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> vs.lCropX += 2f
                            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                                vs.lCropZoom = when {
                                    vs.lCropZoom < 125f -> 125f
                                    vs.lCropZoom < 150f -> 150f
                                    vs.lCropZoom < 175f -> 175f
                                    vs.lCropZoom < 200f -> 200f
                                    else -> 100f
                                }
                            }

                            android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE -> {
                                vs.lCropMode = LCropMode.MENU
                            }
                        }
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }

                if (vs.lCropMode == LCropMode.MENU) return@onKeyEvent false
                if (isSubMenuOpen || isSceneSearchOpen || isChapterListOpen) return@onKeyEvent false

                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                val isActionDown = keyEvent.type == KeyEventType.KeyDown
                val isActionUp = keyEvent.type == KeyEventType.KeyUp

                if (isActionDown) {
                    vs.lastInteractionTime = System.currentTimeMillis()
                }

                when (keyCode) {
                    NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_ESCAPE -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                vs.backKeyDownTime =
                                    System.currentTimeMillis(); vs.isBackKeyLongPressed = false
                            } else {
                                val elapsed = System.currentTimeMillis() - vs.backKeyDownTime
                                if (!vs.isBackKeyLongPressed && elapsed > 500) {
                                    vs.isBackKeyLongPressed = true; onPiPRequested()
                                }
                            }
                            return@onKeyEvent true
                        } else if (isActionUp) {
                            val elapsed = System.currentTimeMillis() - vs.backKeyDownTime
                            if (!vs.isBackKeyLongPressed && elapsed < 500) {
                                onBackPressed()
                            }
                            vs.backKeyDownTime = 0L; vs.isBackKeyLongPressed = false
                            return@onKeyEvent true
                        }
                        false
                    }

                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (isActionDown) {
                            onShowControlsChange(true); vs.togglePlayPause(exoPlayer.isPlaying); if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                rightKeyDownTime =
                                    System.currentTimeMillis(); isRightKeyLongPressed = false
                            } else {
                                val elapsed = System.currentTimeMillis() - rightKeyDownTime
                                if (!isRightKeyLongPressed && elapsed > 500) {
                                    isRightKeyLongPressed = true; onShowControlsChange(true)
                                    val duration = exoPlayer.duration.coerceAtLeast(1L)
                                    val boundaries = getChapterBoundaries(
                                        duration,
                                        mergeCmSections(currentProgram.recordedVideo.cmSections)
                                    )
                                    if (boundaries.size <= 2) {
                                        exoPlayer.seekTo(
                                            (exoPlayer.currentPosition + 180_000).coerceAtMost(
                                                duration
                                            )
                                        ); vs.updateIndicator(Icons.Default.FastForward, "+3m")
                                    } else {
                                        val next =
                                            boundaries.firstOrNull { it > exoPlayer.currentPosition + 1000 }; exoPlayer.seekTo(
                                            next ?: duration
                                        ); vs.updateIndicator(
                                            Icons.Default.SkipNext,
                                            "次チャプター"
                                        )
                                    }
                                }
                            }
                        } else if (isActionUp) {
                            if (!isRightKeyLongPressed) {
                                onShowControlsChange(true); exoPlayer.seekTo(exoPlayer.currentPosition + 30000); vs.updateIndicator(
                                    Icons.Default.FastForward,
                                    "+30s"
                                )
                            }
                            rightKeyDownTime = 0L; isRightKeyLongPressed = false
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                leftKeyDownTime = System.currentTimeMillis(); isLeftKeyLongPressed =
                                    false
                            } else {
                                val elapsed = System.currentTimeMillis() - leftKeyDownTime
                                if (!isLeftKeyLongPressed && elapsed > 500) {
                                    isLeftKeyLongPressed = true; onShowControlsChange(true)
                                    val duration = exoPlayer.duration.coerceAtLeast(1L)
                                    val boundaries = getChapterBoundaries(
                                        duration,
                                        mergeCmSections(currentProgram.recordedVideo.cmSections)
                                    )
                                    if (boundaries.size <= 2) {
                                        exoPlayer.seekTo(
                                            (exoPlayer.currentPosition - 60_000).coerceAtLeast(
                                                0L
                                            )
                                        ); vs.updateIndicator(Icons.Default.FastRewind, "-1m")
                                    } else {
                                        val prev =
                                            boundaries.lastOrNull { it < exoPlayer.currentPosition - 1000 }; exoPlayer.seekTo(
                                            prev ?: 0L
                                        ); vs.updateIndicator(
                                            Icons.Default.SkipPrevious,
                                            "前チャプター"
                                        )
                                    }
                                }
                            }
                        } else if (isActionUp) {
                            if (!isLeftKeyLongPressed) {
                                onShowControlsChange(true); exoPlayer.seekTo(exoPlayer.currentPosition - 10000); vs.updateIndicator(
                                    Icons.Default.FastRewind,
                                    "-10s"
                                )
                            }
                            leftKeyDownTime = 0L; isLeftKeyLongPressed = false
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                downKeyDownTime = System.currentTimeMillis(); isDownKeyLongPressed =
                                    false
                            } else {
                                val elapsed = System.currentTimeMillis() - downKeyDownTime
                                if (!isDownKeyLongPressed && elapsed > 500) {
                                    isDownKeyLongPressed = true
                                    if (getChapterBoundaries(
                                            exoPlayer.duration.coerceAtLeast(1L),
                                            mergeCmSections(currentProgram.recordedVideo.cmSections)
                                        ).size > 2
                                    ) {
                                        isChapterListOpen = true; onShowControlsChange(true)
                                    }
                                }
                            }
                        } else if (isActionUp) {
                            if (!isDownKeyLongPressed) {
                                onShowControlsChange(true); onSceneSearchToggle(true)
                            }
                            downKeyDownTime = 0L; isDownKeyLongPressed = false
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                        if (isActionDown) {
                            onShowControlsChange(true); onSubMenuToggle(true)
                        }; true
                    }

                    else -> false
                }
            }) {

        AndroidView(
            factory = { ctx ->
                AspectRatioFrameLayout(ctx).apply {
                    keepScreenOn = true
                    // ★ 変更: TextureView を SurfaceView に変更し、低レイヤーエラーを根絶
                    val surfaceView =
                        SurfaceView(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
                    addView(surfaceView)
                }
            },
            update = { view ->
                // ★ 変更: TextureView を SurfaceView として扱う
                val surfaceView = view.getChildAt(0) as SurfaceView
                exoPlayer.setVideoSurfaceView(surfaceView)

                if (videoWidth > 0 && videoHeight > 0) {
                    val ratio =
                        (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
                    view.setAspectRatio(ratio)
                    val targetMode =
                        if (ratio >= 1.7f) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (view.resizeMode != targetMode) view.resizeMode = targetMode
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (vs.lCropEnabled) {
                        scaleX = vs.lCropZoom / 100f; scaleY = vs.lCropZoom / 100f
                        translationX = size.width * (vs.lCropX / 100f); translationY =
                            size.height * (vs.lCropY / 100f)
                        transformOrigin = when (vs.lCropOrigin) {
                            ZoomOrigin.TopLeft -> TransformOrigin(0f, 0f)
                            ZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                            ZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f)
                            ZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                        }
                    } else {
                        scaleX = 1f; scaleY = 1f; translationX = 0f; translationY =
                            0f; transformOrigin = TransformOrigin.Center
                    }
                }
                .focusRequester(mainFocusRequester)
                .focusable(!isPiPMode && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen && vs.lCropMode == LCropMode.HIDDEN)
        )

        if (!isPiPMode) {
            val commentLayer = @Composable {
                if (isHeavyUiReady && vs.isCommentEnabled) {
                    ArchivedCommentOverlay(
                        Modifier.fillMaxSize(), allComments, { exoPlayer.currentPosition },
                        vs.isPlayerPlaying, vs.isCommentEnabled, commentSpeed,
                        commentFontSizeScale, commentOpacity, commentMaxLines, isEmulator
                    )
                }
            }
            val subtitleLayer = @Composable {
                if (isHeavyUiReady) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1); setBackgroundColor(
                                android.graphics.Color.TRANSPARENT
                            ); settings.apply {
                                javaScriptEnabled = true; domStorageEnabled = true
                            }; loadUrl("file:///android_asset/subtitle_renderer.html"); webViewRef.value =
                                this
                            }
                        },
                        update = {
                            it.visibility =
                                if (vs.isSubtitleEnabled) View.VISIBLE else View.INVISIBLE
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
            if (isBuffering) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center), color = Color.White
            )

            PlayerControls(
                exoPlayer,
                currentProgram.title,
                showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen
            )

            AnimatedVisibility(
                isSceneSearchOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                SceneSearchOverlay(
                    currentProgram, exoPlayer.currentPosition, konomiIp, konomiPort,
                    {
                        exoPlayer.seekTo(it); onSceneSearchToggle(false); scope.launch {
                        delay(200); mainFocusRequester.safeRequestFocus(
                        TAG
                    )
                    }
                    },
                    {
                        onSceneSearchToggle(false); scope.launch {
                        delay(200); mainFocusRequester.safeRequestFocus(
                        TAG
                    )
                    }
                    }
                )
            }
            AnimatedVisibility(
                isChapterListOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                ChapterListOverlay(
                    currentProgram, exoPlayer.currentPosition, konomiIp, konomiPort,
                    {
                        exoPlayer.seekTo(it); isChapterListOpen =
                        false; scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) }
                    },
                    {
                        isChapterListOpen = false; scope.launch {
                        delay(200); mainFocusRequester.safeRequestFocus(
                        TAG
                    )
                    }
                    }
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = vs.lCropMode != LCropMode.HIDDEN, enter = fadeIn(), exit = fadeOut()
            ) {
                VideoLCropOverlay(
                    state = vs,
                    onClose = {
                        vs.lCropMode = LCropMode.HIDDEN; scope.launch {
                        delay(200); mainFocusRequester.safeRequestFocus(
                        TAG
                    )
                    }
                    }
                )
            }

            AnimatedVisibility(
                isSubMenuOpen,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                VideoTopSubMenuUI(
                    currentAudioMode = vs.currentAudioMode, currentSpeed = vs.currentSpeed,
                    isSubtitleEnabled = vs.isSubtitleEnabled, currentQuality = vs.currentQuality,
                    isCommentEnabled = vs.isCommentEnabled, isLCropEnabled = vs.lCropEnabled,
                    focusRequester = subMenuFocusRequester,
                    onAudioToggle = {
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
                    onSpeedToggle = {
                        val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f); vs.currentSpeed =
                        speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]; exoPlayer.setPlaybackSpeed(
                        vs.currentSpeed
                    ); onShowToast("速度: ${vs.currentSpeed}x")
                    },
                    onSubtitleToggle = {
                        vs.isSubtitleEnabled =
                            !vs.isSubtitleEnabled; onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                    },
                    onQualitySelect = { vs.currentQuality = it; onShowToast("画質: ${it.label}") },
                    onCommentToggle = {
                        vs.isCommentEnabled =
                            !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                    },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode = LCropMode.MENU; onSubMenuToggle(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f; vs.lCropOrigin = ZoomOrigin.TopRight
                        }
                    }
                )
            }
            PlaybackIndicator(vs.indicatorState)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause(); videoPlayerViewModel.updateWatchHistory(
                    program,
                    exoPlayer.currentPosition / 1000.0
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            videoPlayerViewModel.updateWatchHistory(
                program,
                exoPlayer.currentPosition / 1000.0
            ); lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release()
        }
    }
}