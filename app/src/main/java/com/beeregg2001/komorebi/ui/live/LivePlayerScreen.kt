@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import android.util.Base64
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import java.io.IOException

@ExperimentalComposeUiApi
@Composable
fun LivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String?,
    mirakurunPort: String?,
    konomiIp: String = "192-168-100-60.local.konomi.tv",
    konomiPort: String = "7000",
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onBackPressed: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val currentChannelItem by remember(channel.id, groupedChannels) {
        derivedStateOf { groupedChannels.values.flatten().find { it.id == channel.id } ?: channel }
    }

    val nativeLib = remember { NativeLib() }
    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    val subtitleEnabledState = rememberSaveable { mutableStateOf(false) }
    val isSubtitleEnabled by subtitleEnabledState

    // ★修正：トーストを必ず消去するためのタイムスタンプキー
    var toastState by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(toastState) {
        if (toastState != null) {
            delay(2000)
            toastState = null
        }
    }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    var currentStreamSource by remember {
        mutableStateOf(if (isMirakurunAvailable) StreamSource.MIRAKURUN else StreamSource.KONOMITV)
    }

    var showOverlay by remember { mutableStateOf(false) }
    var isManualOverlay by remember { mutableStateOf(false) }
    var isPinnedOverlay by remember { mutableStateOf(false) }
    var isSubMenuOpen by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }

    val tsDataSourceFactory = remember { TsReadExDataSourceFactory(nativeLib, arrayOf()) }
    val extractorsFactory = remember {
        DefaultExtractorsFactory().apply { setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS) }
    }
    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(
            ChannelMixingMatrix(
                2,
                2,
                floatArrayOf(1f, 0f, 0f, 1f)
            )
        ) }
    }

    // エラー解析ロジック
    fun analyzePlayerError(error: PlaybackException): String {
        val cause = error.cause
        return if (cause is HttpDataSource.InvalidResponseCodeException) {
            when (cause.responseCode) {
                404 -> AppStrings.ERR_CHANNEL_NOT_FOUND
                503 -> AppStrings.ERR_TUNER_FULL
                else -> "サーバーエラー (HTTP ${cause.responseCode})"
            }
        } else if (cause is HttpDataSource.HttpDataSourceException) {
            if (cause.cause is java.net.ConnectException) AppStrings.ERR_CONNECTION_REFUSED
            else if (cause.cause is java.net.SocketTimeoutException) AppStrings.ERR_TIMEOUT
            else AppStrings.ERR_NETWORK
        } else if (cause is IOException) { "データ読み込みエラー: ${cause.message}" }
        else { "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})" }
    }

    val exoPlayer = remember(currentStreamSource, retryKey) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build()
            }
        }
        ExoPlayer.Builder(context, renderersFactory).apply {
            if (currentStreamSource == StreamSource.MIRAKURUN) setMediaSourceFactory(DefaultMediaSourceFactory(tsDataSourceFactory, extractorsFactory))
        }.build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { playerError = analyzePlayerError(error) }
                override fun onMetadata(metadata: Metadata) {
                    if (!subtitleEnabledState.value) return
                    for (i in 0 until metadata.length()) {
                        val entry = metadata.get(i)
                        if (entry is PrivFrame && (entry.owner.contains("aribb24", true) || entry.owner.contains("B24", true))) {
                            val base64Data = Base64.encodeToString(entry.privateData, Base64.NO_WRAP)
                            val ptsMs = currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS
                            webViewRef.value?.post { webViewRef.value?.evaluateJavascript("if(window.receiveSubtitleData){ window.receiveSubtitleData($ptsMs, '$base64Data'); }", null) }
                        }
                    }
                }
            })
        }
    }

    // ハートビート同期
    LaunchedEffect(exoPlayer, isSubtitleEnabled) {
        while(true) {
            if (isSubtitleEnabled && exoPlayer.isPlaying) {
                val currentPos = exoPlayer.currentPosition
                webViewRef.value?.post { webViewRef.value?.evaluateJavascript("if(window.syncClock){ window.syncClock($currentPos); }", null) }
            }
            delay(100)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.stop()
            else if (event == Lifecycle.Event.ON_START) { exoPlayer.prepare(); exoPlayer.play() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release() }
    }

    LaunchedEffect(currentChannelItem.id, currentStreamSource, retryKey) {
        isManualOverlay = false; isPinnedOverlay = false; showOverlay = true; scrollState.scrollTo(0)
        val streamUrl = if (currentStreamSource == StreamSource.MIRAKURUN && isMirakurunAvailable) {
            tsDataSourceFactory.tsArgs = arrayOf("-x", "18/38/39", "-n", currentChannelItem.serviceId.toString(), "-a", "13", "-b", "5", "-c", "5", "-u", "1", "-d", "13")
            UrlBuilder.getMirakurunStreamUrl(mirakurunIp ?: "", mirakurunPort ?: "", currentChannelItem.networkId, currentChannelItem.serviceId, currentChannelItem.type)
        } else {
            UrlBuilder.getKonomiTvLiveStreamUrl(konomiIp, konomiPort, currentChannelItem.displayChannelId)
        }
        exoPlayer.stop(); exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare(); exoPlayer.play()
        if (playerError == null) { mainFocusRequester.requestFocus() }
        delay(4500)
        if (!isManualOverlay && !isPinnedOverlay && !isSubMenuOpen && playerError == null) showOverlay = false
    }

    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) { delay(100); listFocusRequester.requestFocus() }
        else { mainFocusRequester.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        if (playerError != null) return@onKeyEvent false

        when (keyCode) {
            NativeKeyEvent.KEYCODE_BACK -> {
                if (isSubMenuOpen) { isSubMenuOpen = false; mainFocusRequester.requestFocus(); return@onKeyEvent true }
                if (showOverlay || isPinnedOverlay) { showOverlay = false; isPinnedOverlay = false; isManualOverlay = false; return@onKeyEvent true }
                if (isMiniListOpen) { onMiniListToggle(false); mainFocusRequester.requestFocus(); return@onKeyEvent true }
                onBackPressed(); return@onKeyEvent true
            }
            NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                if (!isSubMenuOpen && !isMiniListOpen) {
                    when {
                        showOverlay -> { showOverlay = false; isManualOverlay = false; isPinnedOverlay = true }
                        isPinnedOverlay -> isPinnedOverlay = false
                        else -> { showOverlay = true; isManualOverlay = true; isPinnedOverlay = false }
                    }
                    return@onKeyEvent true
                }
            }
            NativeKeyEvent.KEYCODE_DPAD_UP -> { if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) { isSubMenuOpen = true; return@onKeyEvent true } }
            NativeKeyEvent.KEYCODE_DPAD_DOWN -> { if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) { onMiniListToggle(true); return@onKeyEvent true } }
        }
        false
    }) {
        // ★修正: updateブロックを必ず使い、PlayerViewをExoPlayerに再バインドする
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable()
        )

        // Overlay Elements
        AnimatedVisibility(visible = isPinnedOverlay && playerError == null, enter = fadeIn(), exit = fadeOut()) {
            StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort, konomiIp, konomiPort)
        }
        AnimatedVisibility(visible = showOverlay && playerError == null && !isMiniListOpen, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            LiveOverlayUI(currentChannelItem, currentChannelItem.programPresent?.title ?: "番組情報なし", mirakurunIp?:"", mirakurunPort?:"", konomiIp, konomiPort, isManualOverlay, scrollState)
        }
        AnimatedVisibility(visible = isMiniListOpen && playerError == null, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ChannelListOverlay(groupedChannels = groupedChannels, currentChannelId = currentChannelItem.id, onChannelSelect = { onChannelSelect(it); onMiniListToggle(false); mainFocusRequester.requestFocus() }, mirakurunIp = mirakurunIp ?: "", mirakurunPort = mirakurunPort ?: "", konomiIp = konomiIp, konomiPort = konomiPort, focusRequester = listFocusRequester)
        }
        AnimatedVisibility(visible = isSubMenuOpen && playerError == null, enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()) {
            TopSubMenuUI(
                currentAudioMode = currentAudioMode,
                currentSource = currentStreamSource,
                isMirakurunAvailable = isMirakurunAvailable,
                isSubtitleEnabled = isSubtitleEnabled,
                focusRequester = subMenuFocusRequester,
                onAudioToggle = {
                    currentAudioMode = if(currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val tracks = exoPlayer.currentTracks
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.size >= 2) {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(TrackSelectionOverride(audioGroups[if(currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup, 0))
                            .build()
                    }
                    toastState = ("音声: ${if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}") to System.currentTimeMillis()
                },
                onSourceToggle = {
                    if (isMirakurunAvailable) {
                        currentStreamSource = if(currentStreamSource == StreamSource.MIRAKURUN) StreamSource.KONOMITV else StreamSource.MIRAKURUN
                        toastState = ("ソース: ${if(currentStreamSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV"}") to System.currentTimeMillis()
                    }
                },
                onSubtitleToggle = {
                    subtitleEnabledState.value = !subtitleEnabledState.value
                    toastState = ("字幕: ${if(subtitleEnabledState.value) "表示" else "非表示"}") to System.currentTimeMillis()
                }
            )
        }

        // Subtitles (UI表示時は非表示)
        val isUiVisible = isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(0)
                    settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false }
                    loadUrl("file:///android_asset/subtitle_renderer.html")
                    webViewRef.value = this
                }
            },
            modifier = Modifier.fillMaxSize().alpha(if (isSubtitleEnabled && !isUiVisible) 1f else 0f)
        )

        LiveToast(message = toastState?.first)

        if (playerError != null) {
            LiveErrorDialog(errorMessage = playerError!!, onRetry = { playerError = null; retryKey++ }, onBack = onBackPressed)
        }
    }
}