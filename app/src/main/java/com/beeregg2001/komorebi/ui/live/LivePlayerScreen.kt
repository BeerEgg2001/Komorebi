@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import android.os.Build
import android.util.Log
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections
import android.graphics.Color as AndroidColor
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku

private const val TAG = "LivePlayerScreen"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LivePlayerScreen(
    channel: Channel,
    initialQuality: String = "1080p-60fps",
    isBaseballMode: Boolean = false,
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    showOverlay: Boolean,
    onShowOverlayChange: (Boolean) -> Unit,
    isManualOverlay: Boolean,
    onManualOverlayChange: (Boolean) -> Unit,
    isPinnedOverlay: Boolean,
    onPinnedOverlayChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onBackPressed: () -> Unit,
    onShowToast: (String) -> Unit,
    isPiPMode: Boolean = false,
    onPiPRequested: () -> Unit = {},
    channelViewModel: ChannelViewModel = hiltViewModel(),
    reserveViewModel: ReserveViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    livePlayerViewModel: LivePlayerViewModel = hiltViewModel(),
    timeFormat: String = "24H"
) {
    val uiContext = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    val ps = rememberLivePlayerState(uiContext)

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val baseballGroupedChannels by channelViewModel.baseballGroupedChannels.collectAsState()
    val displayGroupedChannels =
        remember(groupedChannels, baseballGroupedChannels, isBaseballMode) {
            if (isBaseballMode) baseballGroupedChannels else groupedChannels
        }
    val displayFlatChannels =
        remember(displayGroupedChannels) { displayGroupedChannels.values.flatten() }
    val currentChannelItem by remember(channel.id, displayGroupedChannels) {
        derivedStateOf { displayFlatChannels.find { it.id == channel.id } ?: channel }
    }

    val konomiIp by settingsViewModel.konomiIp.collectAsState()
    val konomiPort by settingsViewModel.konomiPort.collectAsState()
    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState()
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState()
    val edcbIp by settingsViewModel.edcbIp.collectAsState()
    val edcbPort by settingsViewModel.edcbPort.collectAsState()

    val availableSources by livePlayerViewModel.availableSources.collectAsState()
    val currentLogoUrl by livePlayerViewModel.currentLogoUrl.collectAsState()
    val shouldCropLogo by livePlayerViewModel.shouldCropLogo.collectAsState()
    val mainBackendType by livePlayerViewModel.mainBackendType.collectAsState()

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val audioOutputMode by settingsViewModel.audioOutputMode.collectAsState()
    val liveSubtitleDefaultStr by settingsViewModel.liveSubtitleDefault.collectAsState()
    val allowMirakurunDual by settingsViewModel.labAllowMirakurunDual.collectAsState()

    val playerUiMode by settingsViewModel.playerUiMode.collectAsState()
    val isModern = playerUiMode == "MODERN"

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    var isCommentEnabled by rememberSaveable(commentDefaultDisplayStr) {
        mutableStateOf(commentDefaultDisplayStr == "ON")
    }
    val subtitleEnabledState =
        rememberSaveable(liveSubtitleDefaultStr) { mutableStateOf(liveSubtitleDefaultStr == "ON") }
    val isSubtitleEnabled by subtitleEnabledState

    val reserves by reserveViewModel.reserves.collectAsState()
    val activeReserve = remember(reserves, currentChannelItem.programPresent?.id) {
        reserves.find { it.program.id == currentChannelItem.programPresent?.id }
    }
    val isRecording = activeReserve != null

    val currentIsManualOverlay by rememberUpdatedState(isManualOverlay)
    val currentIsPinnedOverlay by rememberUpdatedState(isPinnedOverlay)
    val currentIsSubMenuOpen by rememberUpdatedState(isSubMenuOpen)

    var isHeavyUiReady by remember { mutableStateOf(false) }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.PRODUCT == "google_sdk" }

    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val dualWebViewRef = remember { mutableStateOf<WebView?>(null) }

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    val mainPlayer by livePlayerViewModel.mainPlayer.collectAsState()
    val dualPlayer by livePlayerViewModel.dualPlayer.collectAsState()

    val mainError by livePlayerViewModel.mainPlayerError.collectAsState()
    val mainStatus by livePlayerViewModel.mainSseStatus.collectAsState()
    val mainDetail by livePlayerViewModel.mainSseDetail.collectAsState()
    val mainSignal by livePlayerViewModel.mainSignalInfo.collectAsState()
    val dualStatus by livePlayerViewModel.dualSseStatus.collectAsState()
    val dualDetail by livePlayerViewModel.dualSseDetail.collectAsState()

    val availableQualities by livePlayerViewModel.availableQualities.collectAsState(initial = StreamQuality.DEFAULT_QUALITIES)
    val isQualitiesLoaded by livePlayerViewModel.isQualitiesLoaded.collectAsState()

    val currentLiveQualityStr by settingsViewModel.liveQuality.collectAsState()

    LaunchedEffect(mainError, mainStatus, mainDetail, mainSignal) {
        ps.playerError = mainError
        ps.sseStatus = mainStatus
        ps.sseDetail = mainDetail
        ps.signalInfo = mainSignal
    }

    LaunchedEffect(dualStatus, dualDetail) {
        ps.dualSseStatus = dualStatus
        ps.dualSseDetail = dualDetail
    }

    var isSourceInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isSourceInitialized) {
            ps.currentStreamSource = livePlayerViewModel.getInitialStreamSource()
            ps.isEdcbDirect = livePlayerViewModel.getInitialEdcbDirect()
            isSourceInitialized = true
        }
    }

    LaunchedEffect(ps.currentStreamSource, ps.isEdcbDirect) {
        livePlayerViewModel.fetchAvailableQualities(ps.currentStreamSource, ps.isEdcbDirect)
    }

    LaunchedEffect(availableQualities, isQualitiesLoaded, currentLiveQualityStr) {
        if (isQualitiesLoaded && availableQualities.isNotEmpty()) {
            val matched = availableQualities.find { it.value == currentLiveQualityStr }
            if (matched != null) {
                ps.currentQuality = matched
            } else {
                Log.w(
                    TAG,
                    "User's liveQuality ($currentLiveQualityStr) is not in the list. Falling back to default."
                )
                val fallback = availableQualities.first()
                ps.currentQuality = fallback
                livePlayerViewModel.saveLiveQuality(fallback.value)
            }
        }
    }

    LaunchedEffect(isPiPMode) {
        if (isPiPMode) {
            if ((ps.currentStreamSource == StreamSource.MIRAKURUN || ps.currentStreamSource == StreamSource.EDCB) && allowMirakurunDual != "ON") {
                ps.previousStreamSource = ps.currentStreamSource
                if (availableSources.contains(StreamSource.KONOMITV)) {
                    ps.currentStreamSource = StreamSource.KONOMITV
                    onShowToast("負荷軽減のためKonomiTVソースに切り替えました")
                }
            }
        } else {
            if (!ps.isDualDisplayMode && ps.previousStreamSource != null) {
                if (availableSources.contains(ps.previousStreamSource!!)) {
                    ps.currentStreamSource = ps.previousStreamSource!!
                    onShowToast("元のストリーミングソースに復帰しました")
                }
                ps.previousStreamSource = null
            }
        }
    }

    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }
    var dualVideoWidth by remember { mutableIntStateOf(0) }
    var dualVideoHeight by remember { mutableIntStateOf(0) }
    var dualPixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }

    var isMainBuffering by remember { mutableStateOf(false) }
    var isDualBuffering by remember { mutableStateOf(false) }

    DisposableEffect(mainPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ps.isPlayerPlaying = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width; videoHeight =
                    videoSize.height; pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isMainBuffering = (playbackState == Player.STATE_BUFFERING)
            }
        }
        mainPlayer?.addListener(listener)
        isMainBuffering = mainPlayer?.playbackState == Player.STATE_BUFFERING
        onDispose { mainPlayer?.removeListener(listener) }
    }

    DisposableEffect(dualPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                dualVideoWidth = videoSize.width; dualVideoHeight =
                    videoSize.height; dualPixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isDualBuffering = (playbackState == Player.STATE_BUFFERING)
            }
        }
        dualPlayer?.addListener(listener)
        isDualBuffering = dualPlayer?.playbackState == Player.STATE_BUFFERING
        onDispose { dualPlayer?.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        channelViewModel.setPollingPaused(true)
        onDispose {
            Log.d(
                TAG,
                "LivePlayerScreen disposed. Completely releasing players to free hardware decoders."
            )
            livePlayerViewModel.releasePlayers()
            channelViewModel.setPollingPaused(false)
        }
    }

    LaunchedEffect(
        currentChannelItem.id,
        ps.currentStreamSource,
        ps.isEdcbDirect,
        ps.retryKey,
        ps.currentQuality,
        isSourceInitialized,
        isQualitiesLoaded
    ) {
        if (!isSourceInitialized || !isQualitiesLoaded) return@LaunchedEffect
        if (currentChannelItem.displayChannelId.isBlank() || currentChannelItem.displayChannelId == "null") return@LaunchedEffect

        if (ps.currentQuality.value.isBlank()) return@LaunchedEffect

        if (availableQualities.isNotEmpty() && availableQualities.none { it.value == ps.currentQuality.value }) {
            return@LaunchedEffect
        }

        livePlayerViewModel.playMainChannel(
            uiContext = uiContext,
            channel = currentChannelItem,
            source = ps.currentStreamSource,
            isEdcbDirect = ps.isEdcbDirect,
            quality = ps.currentQuality
        )
        delay(300); mainFocusRequester.safeRequestFocus(TAG)
    }

    LaunchedEffect(
        ps.dualRightChannel,
        ps.currentStreamSource,
        ps.isEdcbDirect,
        ps.isDualDisplayMode,
        ps.retryKey,
        ps.currentQuality,
        isSourceInitialized,
        isQualitiesLoaded
    ) {
        if (!isSourceInitialized || !isQualitiesLoaded) return@LaunchedEffect

        val rightChannel = ps.dualRightChannel
        if (ps.isDualDisplayMode && rightChannel != null) {
            if (rightChannel.displayChannelId.isBlank() || rightChannel.displayChannelId == "null") return@LaunchedEffect
            if (ps.currentQuality.value.isBlank()) return@LaunchedEffect

            if (availableQualities.isNotEmpty() && availableQualities.none { it.value == ps.currentQuality.value }) {
                return@LaunchedEffect
            }

            livePlayerViewModel.playDualChannel(
                uiContext = uiContext,
                channel = rightChannel,
                source = ps.currentStreamSource,
                isEdcbDirect = ps.isEdcbDirect,
                quality = ps.currentQuality
            )
        } else {
            livePlayerViewModel.stopDualPlayer()
        }
    }

    LaunchedEffect(ps.isDualDisplayMode, ps.activeDualPlayerIndex, mainPlayer, dualPlayer) {
        val mainVol = if (ps.isDualDisplayMode && ps.activeDualPlayerIndex != 0) 0f else 1f
        val dualVol = if (ps.isDualDisplayMode && ps.activeDualPlayerIndex == 1) 1f else 0f
        livePlayerViewModel.setVolumes(mainVol, dualVol)
    }

    LaunchedEffect(Unit) {
        livePlayerViewModel.subtitleEvents.collect { (pts, base64) ->
            webViewRef.value?.evaluateJavascript(
                "if(window.receiveSubtitleData){ window.receiveSubtitleData($pts, '$base64'); }",
                null
            )
        }
    }

    LaunchedEffect(isSubtitleEnabled) {
        livePlayerViewModel.setSubtitlesEnabled(isSubtitleEnabled)
        while (true) {
            if (isSubtitleEnabled) {
                mainPlayer?.takeIf { it.isPlaying }?.let { player ->
                    webViewRef.value?.post {
                        webViewRef.value?.evaluateJavascript(
                            "if(window.syncClock){ window.syncClock(${player.currentPosition}); }",
                            null
                        )
                    }
                }
                if (ps.isDualDisplayMode) {
                    dualPlayer?.takeIf { it.isPlaying }?.let { player ->
                        dualWebViewRef.value?.post {
                            dualWebViewRef.value?.evaluateJavascript(
                                "if(window.syncClock){ window.syncClock(${player.currentPosition}); }",
                                null
                            )
                        }
                    }
                }
            }
            delay(100)
        }
    }

    LaunchedEffect(Unit) {
        livePlayerViewModel.clearCommentsEvent.collect {
            danmakuViewRef.value?.removeAllDanmakus(true)
        }
    }

    LaunchedEffect(Unit) {
        livePlayerViewModel.liveComments.collect { comment ->
            if (!isCommentEnabled || !isHeavyUiReady || ps.isDualDisplayMode) return@collect

            danmakuViewRef.value?.let { view ->
                (view as? android.view.View)?.post {
                    if (!view.isPrepared) return@post

                    val danmakuType = when (comment.position) {
                        "top" -> BaseDanmaku.TYPE_FIX_TOP
                        "bottom" -> BaseDanmaku.TYPE_FIX_BOTTOM
                        else -> BaseDanmaku.TYPE_SCROLL_RL
                    }
                    val danmaku =
                        view.config.mDanmakuFactory.createDanmaku(danmakuType) ?: return@post
                    danmaku.text = comment.text
                    danmaku.padding = 5

                    val sizeFactor = when (comment.size) {
                        "big" -> 1.5f
                        "small" -> 0.8f
                        else -> 1.0f
                    }
                    danmaku.textSize =
                        (32f * commentFontSizeScale * sizeFactor) * view.context.resources.displayMetrics.density

                    try {
                        danmaku.textColor = AndroidColor.parseColor(comment.color)
                    } catch (e: Exception) {
                        danmaku.textColor = AndroidColor.WHITE
                    }
                    danmaku.textShadowColor = AndroidColor.BLACK
                    danmaku.setTime(view.currentTime + 10)
                    view.addDanmaku(danmaku)
                }
            }
        }
    }

    var hasStoppedByLifecycle by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                hasStoppedByLifecycle = true
                livePlayerViewModel.releasePlayers()
            } else if (event == Lifecycle.Event.ON_START) {
                if (hasStoppedByLifecycle) {
                    hasStoppedByLifecycle = false
                    ps.retryKey++
                    channelViewModel.fetchChannels()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentChannelItem.id, ps.retryKey) {
        onManualOverlayChange(false); onPinnedOverlayChange(false); onShowOverlayChange(true); scrollState.scrollTo(
        0
    )
        delay(4500)
        if (!currentIsManualOverlay && !currentIsPinnedOverlay && !currentIsSubMenuOpen) onShowOverlayChange(
            false
        )
    }

    LaunchedEffect(Unit) {
        delay(800); isHeavyUiReady = true
    }

    val isUiVisible =
        isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay || ps.lCropMode != LCropMode.HIDDEN

    LaunchedEffect(isUiVisible) {
        channelViewModel.setPollingPaused(!isUiVisible)
    }

    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) {
            channelViewModel.fetchChannels(); delay(200); listFocusRequester.safeRequestFocus(TAG)
        } else if (!currentIsManualOverlay && !currentIsSubMenuOpen && !isPiPMode && ps.lCropMode == LCropMode.HIDDEN) {
            delay(100); mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(isSubMenuOpen) {
        if (isSubMenuOpen && !isPiPMode) {
            delay(150); subMenuFocusRequester.safeRequestFocus(TAG)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (isPiPMode) return@onKeyEvent false
                ps.handleKeyEvent(
                    keyEvent = keyEvent,
                    isSubMenuOpen = isSubMenuOpen,
                    isMiniListOpen = isMiniListOpen,
                    showOverlay = showOverlay,
                    isManualOverlay = isManualOverlay,
                    isPinnedOverlay = isPinnedOverlay,
                    currentChannelItem = currentChannelItem,
                    groupedChannels = displayGroupedChannels,
                    scrollState = scrollState,
                    scope = scope,
                    onChannelSelect = onChannelSelect,
                    onShowOverlayChange = onShowOverlayChange,
                    onManualOverlayChange = onManualOverlayChange,
                    onPinnedOverlayChange = onPinnedOverlayChange,
                    onSubMenuToggle = onSubMenuToggle,
                    onMiniListToggle = onMiniListToggle,
                    onShowToast = onShowToast,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed
                )
            }
    ) {
        if (ps.isDualDisplayMode) {
            DualDisplayPlayer(
                state = ps,
                leftChannel = currentChannelItem,
                getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                shouldCropLogo = shouldCropLogo,
                isMiniListOpen = isMiniListOpen,
                isUiVisible = isUiVisible,
                mainPlayer = mainPlayer,
                mainVideoWidth = videoWidth,
                mainVideoHeight = videoHeight,
                mainPixelRatio = pixelWidthHeightRatio,
                mainWebViewRef = webViewRef,
                dualPlayer = dualPlayer,
                dualVideoWidth = dualVideoWidth,
                dualVideoHeight = dualVideoHeight,
                dualPixelRatio = dualPixelWidthHeightRatio,
                dualWebViewRef = dualWebViewRef,
                isSubtitleEnabled = isSubtitleEnabled
            )
        } else {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = mainPlayer
                        useController = false; keepScreenOn = true; resizeMode =
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    if (view.player != mainPlayer) view.player = mainPlayer

                    if (videoWidth > 0 && videoHeight > 0) {
                        val ratio = videoWidth.toFloat() / videoHeight.toFloat()
                        val isAnamorphic =
                            (videoWidth == 1440 && videoHeight == 1080 && pixelWidthHeightRatio == 1.0f)
                        val targetMode =
                            if (isAnamorphic || ratio >= 1.7f) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        if (view.resizeMode != targetMode) view.resizeMode = targetMode
                    }
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (ps.lCropEnabled) {
                            scaleX = ps.lCropZoom / 100f; scaleY = ps.lCropZoom / 100f
                            translationX = size.width * (ps.lCropX / 100f); translationY =
                                size.height * (ps.lCropY / 100f)
                            transformOrigin = when (ps.lCropOrigin) {
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
                    .focusable(!isPiPMode && !isMiniListOpen && !isSubMenuOpen && ps.lCropMode == LCropMode.HIDDEN)
            )

            val isVideoVisible =
                ps.currentStreamSource == StreamSource.MIRAKURUN || ps.currentStreamSource == StreamSource.EDCB || ps.sseStatus == "ONAir"
            if (!isVideoVisible) Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )

            if (!isPiPMode) {
                if (isHeavyUiReady) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.apply {
                                    javaScriptEnabled = true; domStorageEnabled = true
                                }
                                loadUrl("file:///android_asset/subtitle_renderer.html")
                                webViewRef.value = this
                            }
                        },
                        update = { view ->
                            view.visibility =
                                if (isSubtitleEnabled && !isUiVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        },
                        onRelease = { view ->
                            view.destroy()
                            webViewRef.value = null
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isHeavyUiReady && isCommentEnabled) {
                    LiveCommentOverlay(
                        Modifier.fillMaxSize(),
                        isEmulator,
                        commentSpeed,
                        commentOpacity,
                        commentMaxLines
                    ) { view -> danmakuViewRef.value = view; if (!ps.isPlayerPlaying) view.pause() }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && ps.lCropMode != LCropMode.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LCropOverlay(
                state = ps,
                onClose = {
                    ps.lCropMode = LCropMode.HIDDEN; scope.launch {
                    delay(200); mainFocusRequester.safeRequestFocus(
                    TAG
                )
                }
                })
        }

        val showMainLoading = remember(
            ps.currentStreamSource,
            ps.isEdcbDirect,
            ps.sseStatus,
            ps.sseDetail,
            ps.playerError,
            isMainBuffering
        ) {
            if (ps.playerError != null) return@remember false
            if (ps.currentStreamSource == StreamSource.KONOMITV) {
                (ps.sseStatus == "Standby" || ps.sseStatus == "Offline") && ps.sseDetail.isNotEmpty()
            } else if (ps.currentStreamSource == StreamSource.EDCB && !ps.isEdcbDirect) {
                ps.sseStatus == "Standby" || isMainBuffering
            } else {
                isMainBuffering
            }
        }

        val mainLoadingText =
            if (ps.currentStreamSource == StreamSource.KONOMITV) ps.sseDetail
            else if (ps.currentStreamSource == StreamSource.EDCB && !ps.isEdcbDirect && ps.sseStatus == "Standby") ps.sseDetail
            else AppStrings.STATUS_LOADING

        // ★ 修正: バッファリング中の黒画面を回避し、スピナーだけを表示する
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && showMainLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(32.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = mainLoadingText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && ps.isSignalInfoVisible && ps.playerError == null && !isUiVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SignalInfoOverlay(ps.signalInfo)
        }

        androidx.compose.animation.AnimatedVisibility(visible = !isPiPMode && !ps.isDualDisplayMode && isPinnedOverlay && ps.playerError == null) {
            StatusOverlay(
                channel = currentChannelItem,
                logoUrl = currentLogoUrl,
                shouldCropLogo = shouldCropLogo,
                timeFormatSetting = timeFormat
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && showOverlay && ps.playerError == null && !isMiniListOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            LiveOverlayUI(
                channel = currentChannelItem,
                programTitle = currentChannelItem.programPresent?.title
                    ?: AppStrings.PROGRAM_INFO_NONE,
                logoUrl = currentLogoUrl,
                shouldCropLogo = shouldCropLogo,
                showDesc = isManualOverlay,
                isRecording = isRecording,
                scrollState = scrollState,
                timeFormatSetting = timeFormat
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && isMiniListOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ChannelListOverlay(
                groupedChannels = displayGroupedChannels,
                currentChannelId = currentChannelItem.id,
                onChannelSelect = { selectedChannel ->
                    if (!ps.isDualDisplayMode) onChannelSelect(selectedChannel) else {
                        if (ps.activeDualPlayerIndex == 0) onChannelSelect(selectedChannel) else ps.dualRightChannel =
                            selectedChannel
                    }; onMiniListToggle(false); scope.launch {
                    delay(200); mainFocusRequester.safeRequestFocus(
                    TAG
                )
                }
                },
                getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                shouldCropLogo = shouldCropLogo,
                focusRequester = listFocusRequester
            )
        }

        AnimatedVisibility(
            visible = !isPiPMode && isSubMenuOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            LiveTopSubMenuUI(
                mainBackendType = mainBackendType,
                currentStreamSource = ps.currentStreamSource,
                isEdcbDirect = ps.isEdcbDirect,
                availableSources = availableSources,
                currentAudioMode = ps.currentAudioMode,
                isSubtitleEnabled = isSubtitleEnabled,
                currentQuality = ps.currentQuality,
                isCommentEnabled = isCommentEnabled,
                isLCropEnabled = ps.lCropEnabled,
                isRecording = isRecording,
                isSignalInfoVisible = ps.isSignalInfoVisible,
                isDualDisplayMode = ps.isDualDisplayMode,
                onDualDisplayToggle = {
                    ps.isDualDisplayMode = !ps.isDualDisplayMode
                    if (ps.isDualDisplayMode) {
                        ps.activeDualPlayerIndex = 1
                        if ((ps.currentStreamSource == StreamSource.MIRAKURUN || ps.currentStreamSource == StreamSource.EDCB) && allowMirakurunDual != "ON") {
                            ps.previousStreamSource = ps.currentStreamSource
                            if (availableSources.contains(StreamSource.KONOMITV)) {
                                ps.currentStreamSource = StreamSource.KONOMITV
                                onShowToast("負荷軽減のためKonomiTVソースに切り替えました")
                            }
                        }
                    } else {
                        ps.activeDualPlayerIndex = 0
                        ps.dualRightChannel = null
                        ps.leftScreenWeight = 1f
                        ps.rightScreenWeight = 1f
                        if (ps.previousStreamSource != null) {
                            if (availableSources.contains(ps.previousStreamSource!!)) {
                                ps.currentStreamSource = ps.previousStreamSource!!
                                onShowToast("元のストリーミングソースに復帰しました")
                            }
                            ps.previousStreamSource = null
                        }
                    }
                },
                onSwapScreens = {
                    if (ps.dualRightChannel != null) {
                        val temp = currentChannelItem
                        onChannelSelect(ps.dualRightChannel!!)
                        ps.dualRightChannel = temp
                    }
                },
                onRecordToggle = {
                    if (isRecording) {
                        if (activeReserve != null) {
                            reserveViewModel.deleteReservation(activeReserve.id) {}
                            onShowToast("録画を停止しました")
                        }
                    } else {
                        reserveViewModel.addReserve(currentChannelItem.id) {}
                        onShowToast("録画を開始しました")
                    }
                    onSubMenuToggle(false)
                },
                onSignalInfoToggle = {
                    ps.isSignalInfoVisible = !ps.isSignalInfoVisible
                    if (ps.isSignalInfoVisible) {
                        onShowToast("信号情報を表示します")
                    }
                },
                availableQualities = availableQualities,
                focusRequester = subMenuFocusRequester,
                onSourceSelect = { source, isDirect ->
                    ps.currentStreamSource = source
                    ps.isEdcbDirect = isDirect
                    onSubMenuToggle(false)
                },
                onAudioToggle = {
                    ps.currentAudioMode =
                        if (ps.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    mainPlayer?.let { player ->
                        val tracks =
                            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        if (tracks.size >= 2) {
                            player.trackSelectionParameters =
                                player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .addOverride(
                                        TrackSelectionOverride(
                                            tracks[if (ps.currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                                            0
                                        )
                                    )
                                    .build()
                        }
                    }
                    onShowToast("音声: ${if (ps.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                },
                onSubtitleToggle = {
                    subtitleEnabledState.value = !subtitleEnabledState.value
                    onShowToast(
                        String.format(
                            AppStrings.TOAST_SUBTITLE_CHANGED,
                            if (subtitleEnabledState.value) AppStrings.STATE_SHOW else AppStrings.STATE_HIDE
                        )
                    )
                },
                onQualitySelect = {
                    if (ps.currentQuality != it) {
                        ps.currentQuality = it
                        livePlayerViewModel.saveLiveQuality(it.value)
                        ps.retryKey++
                        onShowToast(String.format(AppStrings.TOAST_QUALITY_CHANGED, it.label))
                    }
                    onSubMenuToggle(false)
                },
                onCommentToggle = {
                    isCommentEnabled = !isCommentEnabled
                    onShowToast(
                        String.format(
                            AppStrings.TOAST_COMMENT_CHANGED,
                            if (isCommentEnabled) AppStrings.STATE_SHOW else AppStrings.STATE_HIDE
                        )
                    )
                },
                onLCropToggle = {
                    ps.lCropEnabled = !ps.lCropEnabled
                    if (ps.lCropEnabled) {
                        ps.lCropMode = LCropMode.MENU
                        onSubMenuToggle(false)
                    } else {
                        ps.lCropMode = LCropMode.HIDDEN
                        ps.lCropZoom = 100f; ps.lCropX = 0f; ps.lCropY = 0f; ps.lCropOrigin =
                            ZoomOrigin.TopRight
                    }
                },
                onCloseMenu = {
                    onSubMenuToggle(false)
                }
            )
        }

        if (!isPiPMode && ps.playerError != null) {
            LiveErrorDialog(
                ps.playerError!!,
                { livePlayerViewModel.retry(); ps.retry() },
                onBackPressed
            )
        }
    }
}