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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.Log
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
import androidx.tv.material3.*
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.graphics.Color as AndroidColor
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import android.graphics.Typeface
import android.os.Build
import java.util.Collections

@ExperimentalComposeUiApi
@Composable
fun LivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String?,
    mirakurunPort: String?,
    konomiIp: String = "192-168-100-60.local.konomi.tv",
    konomiPort: String = "7000",
    initialQuality: String = "1080p-60fps",
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
) {
    val isEmulator = remember {
        Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT
    }

    var forceSoftwareRendering by remember { mutableStateOf(isEmulator) }
    val processedCommentIds = remember { Collections.synchronizedSet(LinkedHashSet<String>()) }
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    val currentChannelItem by remember(channel.id, groupedChannels) {
        derivedStateOf { flatChannels.find { it.id == channel.id } ?: channel }
    }

    val nativeLib = remember { NativeLib() }
    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    var currentQuality by remember(initialQuality) { mutableStateOf(StreamQuality.fromValue(initialQuality)) }
    val subtitleEnabledState = rememberSaveable { mutableStateOf(false) }
    val isSubtitleEnabled by subtitleEnabledState
    // ★追加: コメント有効状態
    val commentEnabledState = rememberSaveable { mutableStateOf(true) }
    val isCommentEnabled by commentEnabledState

    var toastState by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    var currentStreamSource by remember { mutableStateOf(if (isMirakurunAvailable) StreamSource.MIRAKURUN else StreamSource.KONOMITV) }

    var playerError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    var sseStatus by remember { mutableStateOf("Standby") }
    var sseDetail by remember { mutableStateOf(AppStrings.SSE_CONNECTING) }

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }

    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    val updatedIsManualOverlay by rememberUpdatedState(isManualOverlay)
    val updatedIsPinnedOverlay by rememberUpdatedState(isPinnedOverlay)
    val updatedIsSubMenuOpen by rememberUpdatedState(isSubMenuOpen)

    val tsDataSourceFactory = remember { TsReadExDataSourceFactory(nativeLib, arrayOf()) }
    val extractorsFactory = remember { DefaultExtractorsFactory().apply { setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS) } }
    val audioProcessor = remember { ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))) } }

    fun analyzePlayerError(error: PlaybackException): String {
        val cause = error.cause
        return if (cause is HttpDataSource.InvalidResponseCodeException) {
            when (cause.responseCode) { 404 -> AppStrings.ERR_CHANNEL_NOT_FOUND; 503 -> AppStrings.ERR_TUNER_FULL; else -> "サーバーエラー (HTTP ${cause.responseCode})" }
        } else if (cause is HttpDataSource.HttpDataSourceException) {
            if (cause.cause is java.net.ConnectException) AppStrings.ERR_CONNECTION_REFUSED else if (cause.cause is java.net.SocketTimeoutException) AppStrings.ERR_TIMEOUT else AppStrings.ERR_NETWORK
        } else if (cause is IOException) { "データ読み込みエラー: ${cause.message}" }
        else { "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})" }
    }

    val exoPlayer = remember(currentStreamSource, retryKey, currentQuality) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? { return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build() }
        }
        ExoPlayer.Builder(context, renderersFactory).apply {
            if (currentStreamSource == StreamSource.MIRAKURUN) setMediaSourceFactory(DefaultMediaSourceFactory(tsDataSourceFactory, extractorsFactory))
        }.build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (currentStreamSource == StreamSource.KONOMITV && sseStatus == "Standby") return
                    playerError = analyzePlayerError(error)
                }
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

    LaunchedEffect(sseStatus, sseDetail) {
        if (sseStatus == "Offline") {
            if (playerError != null) return@LaunchedEffect
            delay(6000)
            if (sseStatus == "Offline" && playerError == null) {
                playerError = sseDetail.ifEmpty { AppStrings.SSE_OFFLINE }
            }
        }
    }

    DisposableEffect(currentChannelItem.id, currentStreamSource, retryKey, currentQuality) {
        if (currentStreamSource != StreamSource.KONOMITV) return@DisposableEffect onDispose { }
        val eventUrl = UrlBuilder.getKonomiTvLiveEventsUrl(konomiIp, konomiPort, currentChannelItem.displayChannelId, currentQuality.value)
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(eventUrl).build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching {
                    val json = JSONObject(data)
                    sseStatus = json.optString("status", "Unknown")
                    sseDetail = json.optString("detail", "読み込み中...")
                    if (sseStatus == "Error" || (sseStatus == "Offline" && (sseDetail.contains("失敗") || sseDetail.contains("エラー")))) {
                        playerError = sseDetail.ifEmpty { "チューナーの起動に失敗しました" }
                        exoPlayer.stop()
                        return@runCatching
                    }
                    when (sseStatus) {
                        "Offline" -> { }
                        "Standby" -> { if (exoPlayer.isPlaying) exoPlayer.pause() }
                        "ONAir" -> { if (!exoPlayer.isPlaying && playerError == null) exoPlayer.play() }
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        onDispose {
            eventSource.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }

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

    LaunchedEffect(currentChannelItem.id) {
        onManualOverlayChange(false)
        onPinnedOverlayChange(false)
        onShowOverlayChange(true)
        scrollState.scrollTo(0)
        delay(4500)
        if (!updatedIsManualOverlay && !updatedIsPinnedOverlay && !updatedIsSubMenuOpen && playerError == null) {
            onShowOverlayChange(false)
        }
    }

    LaunchedEffect(currentChannelItem.id, currentStreamSource, retryKey, currentQuality) {
        sseStatus = "Standby"
        sseDetail = AppStrings.SSE_CONNECTING
        val streamUrl = if (currentStreamSource == StreamSource.MIRAKURUN && isMirakurunAvailable) {
            tsDataSourceFactory.tsArgs = arrayOf("-x", "18/38/39", "-n", currentChannelItem.serviceId.toString(), "-a", "13", "-b", "5", "-c", "5", "-u", "1", "-d", "13")
            UrlBuilder.getMirakurunStreamUrl(mirakurunIp ?: "", mirakurunPort ?: "", currentChannelItem.networkId, currentChannelItem.serviceId, currentChannelItem.type)
        } else {
            UrlBuilder.getKonomiTvLiveStreamUrl(konomiIp, konomiPort, currentChannelItem.displayChannelId, currentQuality.value)
        }
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare()
        exoPlayer.play()
        if (playerError == null) { mainFocusRequester.requestFocus() }
    }

    // ★修正: 実況機能 (isCommentEnabled に連動)
    DisposableEffect(currentChannelItem.id, isCommentEnabled) {
        processedCommentIds.clear()

        // コメント無効時は接続しない
        if (!isCommentEnabled) {
            danmakuViewRef.value?.removeAllDanmakus(true)
            return@DisposableEffect onDispose { }
        }

        danmakuViewRef.value?.removeAllDanmakus(true)
        val jikkyoClient = JikkyoClient(konomiIp, konomiPort, currentChannelItem.displayChannelId)

        jikkyoClient.start { jsonText ->
            try {
                val json = JSONObject(jsonText)
                val chat = json.optJSONObject("chat")
                if (chat != null) {
                    val commentId = chat.optString("no", "") + "_" + chat.optString("content", "")

                    if (commentId.isNotEmpty() && !processedCommentIds.add(commentId)) {
                        return@start
                    }

                    if (processedCommentIds.size > 200) {
                        val first = processedCommentIds.iterator().next()
                        processedCommentIds.remove(first)
                    }

                    val content = chat.optString("content")
                    if (content.isNotEmpty()) {
                        danmakuViewRef.value?.let { view ->
                            (view as? android.view.View)?.post {
                                if (!view.isPrepared) return@post
                                val danmaku = view.config.mDanmakuFactory.createDanmaku(master.flame.danmaku.danmaku.model.BaseDanmaku.TYPE_SCROLL_RL)
                                if (danmaku != null) {
                                    danmaku.text = content
                                    danmaku.padding = 5
                                    val density = view.context.resources.displayMetrics.density
                                    danmaku.textSize = 32f * density
                                    danmaku.textColor = AndroidColor.WHITE
                                    danmaku.textShadowColor = AndroidColor.BLACK
                                    danmaku.setTime(view.currentTime + 10)
                                    view.addDanmaku(danmaku)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { android.util.Log.e("LivePlayerScreen", "Parse Error", e) }
        }
        onDispose { jikkyoClient.stop() }
    }

    LaunchedEffect(isMiniListOpen) { if (isMiniListOpen) { delay(100); listFocusRequester.requestFocus() } else if (!isManualOverlay) { mainFocusRequester.requestFocus() } }
    LaunchedEffect(isSubMenuOpen) { if (isSubMenuOpen) { delay(100); subMenuFocusRequester.requestFocus() } }
    LaunchedEffect(toastState) { if (toastState != null) { delay(2000); toastState = null } }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .onKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (playerError != null) return@onKeyEvent false
            if (isSubMenuOpen) return@onKeyEvent false
            val keyCode = keyEvent.nativeKeyEvent.keyCode
            if (!isMiniListOpen) {
                val currentGroupList = groupedChannels.values.find { list -> list.any { it.id == currentChannelItem.id } }
                if (currentGroupList != null) {
                    val currentIndex = currentGroupList.indexOfFirst { it.id == currentChannelItem.id }
                    if (currentIndex != -1) {
                        when (keyCode) {
                            NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                                onChannelSelect(currentGroupList[if (currentIndex > 0) currentIndex - 1 else currentGroupList.size - 1])
                                return@onKeyEvent true
                            }
                            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onChannelSelect(currentGroupList[if (currentIndex < currentGroupList.size - 1) currentIndex + 1 else 0])
                                return@onKeyEvent true
                            }
                        }
                    }
                }
            }
            when (keyCode) {
                NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                    if (!isSubMenuOpen && !isMiniListOpen) {
                        when {
                            showOverlay -> { onShowOverlayChange(false); onManualOverlayChange(false); onPinnedOverlayChange(true) }
                            isPinnedOverlay -> onPinnedOverlayChange(false)
                            else -> { onShowOverlayChange(true); onManualOverlayChange(true); onPinnedOverlayChange(false) }
                        }
                        return@onKeyEvent true
                    }
                }
                NativeKeyEvent.KEYCODE_DPAD_UP -> {
                    if (showOverlay && isManualOverlay) { coroutineScope.launch { scrollState.animateScrollTo(scrollState.value - 200) }; return@onKeyEvent true }
                    if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) { onSubMenuToggle(true); return@onKeyEvent true }
                }
                NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (showOverlay && isManualOverlay) { coroutineScope.launch { scrollState.animateScrollTo(scrollState.value + 200) }; return@onKeyEvent true }
                    if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) { onMiniListToggle(true); return@onKeyEvent true }
                }
            }
            false
        }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable().alpha(if (sseStatus == "ONAir" || currentStreamSource != StreamSource.KONOMITV) 1f else 0f)
        )

        // ★修正: コメント表示
        if (isCommentEnabled) {
            LiveCommentOverlay(
                modifier = Modifier.fillMaxSize(),
                useSoftwareRendering = forceSoftwareRendering,
                onViewCreated = { view -> danmakuViewRef.value = view }
            )
        }

        AnimatedVisibility(visible = currentStreamSource == StreamSource.KONOMITV && (sseStatus == "Standby" || sseStatus == "Offline") && playerError == null, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Row(modifier = Modifier.align(Alignment.TopStart).padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = sseDetail, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(visible = isPinnedOverlay && playerError == null, enter = fadeIn(), exit = fadeOut()) { StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort, konomiIp, konomiPort) }

        AnimatedVisibility(visible = showOverlay && playerError == null && !isMiniListOpen, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            LiveOverlayUI(currentChannelItem, currentChannelItem.programPresent?.title ?: "番組情報なし", mirakurunIp ?: "", mirakurunPort ?: "", konomiIp, konomiPort, isManualOverlay, scrollState)
        }

        AnimatedVisibility(visible = isMiniListOpen && playerError == null, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ChannelListOverlay(groupedChannels, currentChannelItem.id, { onChannelSelect(it); onMiniListToggle(false); runCatching { mainFocusRequester.requestFocus() } }, mirakurunIp ?: "", mirakurunPort ?: "", konomiIp, konomiPort, listFocusRequester)
        }

        // ★修正: サブメニュー呼び出し
        AnimatedVisibility(visible = isSubMenuOpen && playerError == null, enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()) {
            TopSubMenuUI(
                currentAudioMode = currentAudioMode,
                currentSource = currentStreamSource,
                currentQuality = currentQuality,
                isMirakurunAvailable = isMirakurunAvailable,
                isSubtitleEnabled = isSubtitleEnabled,
                isSubtitleSupported = currentStreamSource != StreamSource.MIRAKURUN,
                isCommentEnabled = isCommentEnabled,
                focusRequester = subMenuFocusRequester,
                onAudioToggle = {
                    currentAudioMode = if(currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.size >= 2) exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(TrackSelectionOverride(audioGroups[if(currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup, 0)).build()
                    toastState = ("音声: ${if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}") to System.currentTimeMillis()
                },
                onSourceToggle = {
                    if (isMirakurunAvailable) {
                        currentStreamSource = if(currentStreamSource == StreamSource.MIRAKURUN) StreamSource.KONOMITV else StreamSource.MIRAKURUN
                        toastState = ("ソース: ${if(currentStreamSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV"}") to System.currentTimeMillis()
                        onSubMenuToggle(false)
                    }
                },
                onSubtitleToggle = {
                    subtitleEnabledState.value = !subtitleEnabledState.value
                    toastState = ("字幕: ${if(subtitleEnabledState.value) "表示" else "非表示"}") to System.currentTimeMillis()
                },
                onCommentToggle = {
                    commentEnabledState.value = !commentEnabledState.value
                    toastState = ("コメント: ${if(commentEnabledState.value) "表示" else "非表示"}") to System.currentTimeMillis()
                },
                onQualitySelect = {
                    if (currentQuality != it) {
                        currentQuality = it
                        retryKey++
                        toastState = ("画質: ${it.label}") to System.currentTimeMillis()
                    }
                    onSubMenuToggle(false)
                },
                onCloseMenu = { onSubMenuToggle(false) }
            )
        }

        val isUiVisible = isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(0)
                settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false }
                loadUrl("file:///android_asset/subtitle_renderer.html")
                webViewRef.value = this
            }
        }, modifier = Modifier.fillMaxSize().alpha(if (isSubtitleEnabled && !isUiVisible) 1f else 0f))

        LiveToast(message = toastState?.first)
        if (playerError != null) LiveErrorDialog(errorMessage = playerError!!, onRetry = { playerError = null; retryKey++ }, onBack = onBackPressed)
    }
}