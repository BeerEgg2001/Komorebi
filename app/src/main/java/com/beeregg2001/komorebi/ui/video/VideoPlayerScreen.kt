@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video

import android.os.Build
import android.util.Log
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.*
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.common.text.CueGroup
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
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import java.util.UUID
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.common.safeRequestFocus
import android.graphics.Color as AndroidColor
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LocalLifecycleOwner

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
    onUpdateWatchHistory: (RecordedProgram, Double) -> Unit,
    onShowToast: (String) -> Unit,
    recordViewModel: RecordViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    var isCommentEnabled by rememberSaveable(commentDefaultDisplayStr) {
        mutableStateOf(commentDefaultDisplayStr == "ON")
    }

    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(800)
        isHeavyUiReady = true
    }

    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var currentQuality by remember { mutableStateOf(StreamQuality.fromValue(initialQuality)) }
    var isSubtitleEnabled by remember { mutableStateOf(true) }
    val allComments = remember { mutableStateListOf<ArchivedComment>() }
    val isEmulator = remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    val currentSessionId = remember(currentQuality) { UUID.randomUUID().toString() }

    var indicatorState by remember { mutableStateOf<IndicatorState?>(null) }
    var isPlayerPlaying by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var wasPlayingBeforeSceneSearch by remember { mutableStateOf(false) }

    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(program.recordedVideo.id) {
        val fetchedComments = recordViewModel.getArchivedComments(program.recordedVideo.id)
        allComments.clear(); allComments.addAll(fetchedComments)
    }

    val audioProcessor = remember { ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))) } }
    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) { override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? = DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build() }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("DTVClient/1.0").setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context, renderersFactory).setMediaSourceFactory(HlsMediaSource.Factory(httpDataSourceFactory)).build().apply {
            val mediaItem = MediaItem.Builder().setUri(UrlBuilder.getVideoPlaylistUrl(konomiIp, konomiPort, program.recordedVideo.id, currentSessionId, currentQuality.value)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            setMediaItem(mediaItem); setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlayerPlaying = playing }
                override fun onCues(cues: CueGroup) { if (isSubtitleEnabled) { val text = cues.cues.joinToString("\n") { it.text ?: "" }; webViewRef.value?.evaluateJavascript("showSubtitle('${text.replace("'", "\\'").replace("\n", " ")}')", null) } }
            })
            if (initialPositionMs > 0) seekTo(initialPositionMs)
            prepare(); playWhenReady = true
        }
    }

    LaunchedEffect(currentQuality) {
        val currentPos = exoPlayer.currentPosition; val isPlaying = exoPlayer.isPlaying; exoPlayer.stop(); exoPlayer.clearMediaItems()
        val newUrl = UrlBuilder.getVideoPlaylistUrl(konomiIp, konomiPort, program.recordedVideo.id, currentSessionId, currentQuality.value)
        exoPlayer.setMediaItem(MediaItem.Builder().setUri(newUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        exoPlayer.seekTo(currentPos); exoPlayer.prepare(); if (isPlaying) exoPlayer.play()
    }

    LaunchedEffect(isSceneSearchOpen) { if (isSceneSearchOpen) { wasPlayingBeforeSceneSearch = exoPlayer.isPlaying; if (wasPlayingBeforeSceneSearch) exoPlayer.pause() } else if (wasPlayingBeforeSceneSearch) exoPlayer.play() }

    DisposableEffect(currentQuality, currentSessionId) {
        recordViewModel.startStreamMaintenance(program, currentQuality.value, currentSessionId, { exoPlayer.currentPosition / 1000.0 })
        onDispose {
            recordViewModel.stopStreamMaintenance()
        }
    }

    LaunchedEffect(indicatorState) {
        if (indicatorState != null) {
            delay(1500)
            indicatorState = null
        }
    }

    LaunchedEffect(showControls, isSubMenuOpen, isSceneSearchOpen, lastInteractionTime) {
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen) {
            delay(5000)
            onShowControlsChange(false)
        }
    }

    LaunchedEffect(isSubMenuOpen, isSceneSearchOpen, showControls) { delay(150); when { isSubMenuOpen -> subMenuFocusRequester.safeRequestFocus(TAG); !isSceneSearchOpen && !showControls -> mainFocusRequester.safeRequestFocus(TAG) } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background).onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown || isSubMenuOpen || isSceneSearchOpen) return@onKeyEvent false

        onShowControlsChange(true)
        lastInteractionTime = System.currentTimeMillis()

        when (keyEvent.nativeKeyEvent.keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> { if (exoPlayer.isPlaying) { exoPlayer.pause(); indicatorState = IndicatorState(Icons.Default.Pause, "停止") } else { exoPlayer.play(); indicatorState = IndicatorState(Icons.Default.PlayArrow, "再生") }; true }
            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { exoPlayer.seekTo(exoPlayer.currentPosition + 30000); indicatorState = IndicatorState(Icons.Default.FastForward, "+30s"); true }
            NativeKeyEvent.KEYCODE_DPAD_LEFT -> { exoPlayer.seekTo(exoPlayer.currentPosition - 10000); indicatorState = IndicatorState(Icons.Default.FastRewind, "-10s"); true }
            NativeKeyEvent.KEYCODE_DPAD_UP -> { onSubMenuToggle(true); true }
            NativeKeyEvent.KEYCODE_DPAD_DOWN -> { onSceneSearchToggle(true); true }
            else -> false
        }
    }) {
        AndroidView(factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } }, modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable())

        if (isHeavyUiReady && isCommentEnabled) {
            ArchivedCommentOverlay(
                modifier = Modifier.fillMaxSize(),
                comments = allComments,
                currentPositionProvider = { exoPlayer.currentPosition }, // ★修正: 最新時間を返す関数を渡す
                isPlaying = isPlayerPlaying,
                isCommentEnabled = isCommentEnabled,
                commentSpeed = commentSpeed,
                commentFontSizeScale = commentFontSizeScale,
                commentOpacity = commentOpacity,
                commentMaxLines = commentMaxLines,
                useSoftwareRendering = isEmulator
            )
        }

        if (isHeavyUiReady && isSubtitleEnabled) {
            AndroidView(factory = { ctx -> WebView(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1); setBackgroundColor(0); settings.apply { javaScriptEnabled = true; domStorageEnabled = true }; loadUrl("file:///android_asset/subtitle_renderer.html"); webViewRef.value = this } }, modifier = Modifier.fillMaxSize().alpha(if (showControls || isSubMenuOpen || isSceneSearchOpen) 0f else 1f))
        }

        PlayerControls(exoPlayer = exoPlayer, title = program.title, isVisible = showControls && !isSubMenuOpen && !isSceneSearchOpen)

        AnimatedVisibility(visible = isSceneSearchOpen, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            SceneSearchOverlay(program = program, currentPositionMs = exoPlayer.currentPosition, konomiIp = konomiIp, konomiPort = konomiPort, onSeekRequested = { exoPlayer.seekTo(it); onSceneSearchToggle(false); scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) } }, onClose = { onSceneSearchToggle(false); scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) } })
        }

        AnimatedVisibility(visible = isSubMenuOpen, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            VideoTopSubMenuUI(
                currentAudioMode = currentAudioMode, currentSpeed = currentSpeed, isSubtitleEnabled = isSubtitleEnabled, currentQuality = currentQuality, isCommentEnabled = isCommentEnabled, focusRequester = subMenuFocusRequester,
                onAudioToggle = {
                    currentAudioMode = if(currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val tracks = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (tracks.size >= 2) exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(TrackSelectionOverride(tracks[if (currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup, 0)).build()
                    onShowToast("音声: ${if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                },
                onSpeedToggle = { val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f); currentSpeed = speeds[(speeds.indexOf(currentSpeed) + 1) % speeds.size]; exoPlayer.setPlaybackSpeed(currentSpeed); onShowToast("速度: ${currentSpeed}x") },
                onSubtitleToggle = { isSubtitleEnabled = !isSubtitleEnabled; onShowToast("字幕: ${if(isSubtitleEnabled) "表示" else "非表示"}") },
                onQualitySelect = { currentQuality = it; onShowToast("画質: ${it.label}") },
                onCommentToggle = {
                    isCommentEnabled = !isCommentEnabled
                    onShowToast("実況: ${if(isCommentEnabled) "表示" else "非表示"}")
                }
            )
        }
        PlaybackIndicator(indicatorState)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { exoPlayer.pause(); onUpdateWatchHistory(program, exoPlayer.currentPosition / 1000.0) } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { onUpdateWatchHistory(program, exoPlayer.currentPosition / 1000.0); lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release() }
    }
}