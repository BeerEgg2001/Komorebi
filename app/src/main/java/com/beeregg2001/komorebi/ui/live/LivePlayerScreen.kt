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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.common.safeRequestFocus
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
import android.os.Build
import androidx.annotation.RequiresApi
import java.time.OffsetDateTime
import java.util.Collections
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

private const val TAG = "LivePlayerScreen"

@RequiresApi(Build.VERSION_CODES.O)
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
    reserveViewModel: ReserveViewModel,
    epgViewModel: EpgViewModel,
    onShowToast: (String) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()

    // ★追加: 設定からレイヤー順序を取得
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()

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

    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }
    val currentChannelItem by remember(channel.id, groupedChannels) {
        derivedStateOf { flatChannels.find { it.id == channel.id } ?: channel }
    }

    val currentProgramId by remember(currentChannelItem.programPresent, epgViewModel.uiState) {
        derivedStateOf {
            currentChannelItem.programPresent?.id ?: run {
                val state = epgViewModel.uiState
                if (state is EpgUiState.Success) {
                    val now = OffsetDateTime.now()
                    state.data.find { it.channel.id == currentChannelItem.id }
                        ?.programs?.find { prog ->
                            val start = try { OffsetDateTime.parse(prog.start_time) } catch(e: Exception) { null }
                            val end = try { OffsetDateTime.parse(prog.end_time) } catch(e: Exception) { null }
                            start != null && end != null && now.isAfter(start) && now.isBefore(end)
                        }?.id
                } else null
            }
        }
    }

    val reserves by reserveViewModel.reserves.collectAsState()
    val activeReserve = remember(reserves, currentProgramId) { reserves.find { it.program.id == currentProgramId } }
    val isRecording = activeReserve != null

    val isEmulator = remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.PRODUCT == "google_sdk" }
    val processedCommentIds = remember { Collections.synchronizedSet(LinkedHashSet<String>()) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val nativeLib = remember { NativeLib() }

    var currentAudioMode by remember { mutableStateOf(AudioMode.MAIN) }
    var currentQuality by remember(initialQuality) { mutableStateOf(StreamQuality.fromValue(initialQuality)) }

    val liveSubtitleDefaultStr by settingsViewModel.liveSubtitleDefault.collectAsState()
    val subtitleEnabledState = rememberSaveable(liveSubtitleDefaultStr) { mutableStateOf(liveSubtitleDefaultStr == "ON") }
    val isSubtitleEnabled by subtitleEnabledState

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    var currentStreamSource by remember { mutableStateOf(if (isMirakurunAvailable) StreamSource.MIRAKURUN else StreamSource.KONOMITV) }

    var playerError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var sseStatus by remember { mutableStateOf("Standby") }
    var sseDetail by remember { mutableStateOf(AppStrings.SSE_CONNECTING) }

    var isPlayerPlaying by remember { mutableStateOf(false) }

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
        } else if (cause is java.io.IOException) { "データ読み込みエラー: ${cause.message}" }
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
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlayerPlaying = playing
                }

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

    LaunchedEffect(isPlayerPlaying) {
        danmakuViewRef.value?.let { view ->
            if (view.isPrepared) {
                if (isPlayerPlaying) view.resume() else view.pause()
            }
        }
    }

    LaunchedEffect(sseStatus, sseDetail) {
        if (sseStatus == "Offline" && playerError == null) {
            delay(6000)
            if (sseStatus == "Offline") { playerError = sseDetail.ifEmpty { AppStrings.SSE_OFFLINE } }
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
                        "Standby" -> { if (exoPlayer.isPlaying) exoPlayer.pause() }
                        "ONAir" -> { if (!exoPlayer.isPlaying && playerError == null) exoPlayer.play() }
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        onDispose { eventSource.cancel(); client.dispatcher.executorService.shutdown() }
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

    LaunchedEffect(currentChannelItem.id, retryKey) {
        onManualOverlayChange(false); onPinnedOverlayChange(false); onShowOverlayChange(true)
        scrollState.scrollTo(0)
        delay(4500)
        if (!updatedIsManualOverlay && !updatedIsPinnedOverlay && !updatedIsSubMenuOpen) { onShowOverlayChange(false) }
    }

    LaunchedEffect(currentChannelItem.id, currentStreamSource, retryKey, currentQuality) {
        sseStatus = "Standby"; sseDetail = AppStrings.SSE_CONNECTING
        val streamUrl = if (currentStreamSource == StreamSource.MIRAKURUN && isMirakurunAvailable) {
            tsDataSourceFactory.tsArgs = arrayOf("-x", "18/38/39", "-n", currentChannelItem.serviceId.toString(), "-a", "13", "-b", "5", "-c", "5", "-u", "1", "-d", "13")
            UrlBuilder.getMirakurunStreamUrl(mirakurunIp ?: "", mirakurunPort ?: "", currentChannelItem.networkId, currentChannelItem.serviceId)
        } else {
            UrlBuilder.getKonomiTvLiveStreamUrl(konomiIp, konomiPort, currentChannelItem.displayChannelId, currentQuality.value)
        }
        exoPlayer.stop(); exoPlayer.clearMediaItems(); exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl)); exoPlayer.prepare(); exoPlayer.play()
        if (playerError == null) { delay(300); mainFocusRequester.safeRequestFocus(TAG) }
    }

    DisposableEffect(currentChannelItem.id, isCommentEnabled, isHeavyUiReady) {
        processedCommentIds.clear()
        if (!isCommentEnabled || !isHeavyUiReady) { danmakuViewRef.value?.removeAllDanmakus(true); return@DisposableEffect onDispose { } }
        val jikkyoClient = JikkyoClient(konomiIp, konomiPort, currentChannelItem.displayChannelId)
        jikkyoClient.start { jsonText ->
            try {
                val json = JSONObject(jsonText)
                val chat = json.optJSONObject("chat") ?: return@start
                val content = chat.optString("content")
                if (content.isEmpty()) return@start
                val commentId = chat.optString("no", "") + "_" + content
                if (commentId.isNotEmpty() && !processedCommentIds.add(commentId)) return@start
                if (processedCommentIds.size > 200) processedCommentIds.remove(processedCommentIds.iterator().next())
                danmakuViewRef.value?.let { view ->
                    (view as? android.view.View)?.post {
                        if (!view.isPrepared) return@post
                        val danmaku = view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL) ?: return@post
                        danmaku.text = content; danmaku.padding = 5
                        val density = view.context.resources.displayMetrics.density
                        danmaku.textSize = (32f * commentFontSizeScale) * density
                        danmaku.textColor = AndroidColor.WHITE; danmaku.textShadowColor = AndroidColor.BLACK
                        danmaku.setTime(view.currentTime + 10); view.addDanmaku(danmaku)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Parse Error", e) }
        }
        onDispose { jikkyoClient.stop() }
    }

    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) { delay(200); listFocusRequester.safeRequestFocus(TAG) }
        else if (!isManualOverlay && !isSubMenuOpen) { delay(100); mainFocusRequester.safeRequestFocus(TAG) }
    }

    LaunchedEffect(isSubMenuOpen) {
        if (isSubMenuOpen) { delay(150); subMenuFocusRequester.safeRequestFocus(TAG) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background).onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown || playerError != null || isSubMenuOpen || isMiniListOpen) return@onKeyEvent false
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        if (!isMiniListOpen) {
            val currentGroupList = groupedChannels.values.find { list -> list.any { it.id == currentChannelItem.id } }
            if (currentGroupList != null) {
                val currentIndex = currentGroupList.indexOfFirst { it.id == currentChannelItem.id }
                if (currentIndex != -1) {
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onChannelSelect(currentGroupList[if (currentIndex > 0) currentIndex - 1 else currentGroupList.size - 1]); return@onKeyEvent true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { onChannelSelect(currentGroupList[if (currentIndex < currentGroupList.size - 1) currentIndex + 1 else 0]); return@onKeyEvent true }
                    }
                }
            }
        }
        when (keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                if (!isSubMenuOpen && !isMiniListOpen) {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }

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
    }) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } },
            update = { it.player = exoPlayer },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(mainFocusRequester)
                .focusable(!isMiniListOpen && !isSubMenuOpen)
                .alpha(if (sseStatus == "ONAir" || currentStreamSource != StreamSource.MIRAKURUN) 1f else 0f)
        )

        // ★追加: 描画レイヤー（コメントと字幕）の順序を動的に切り替える
        val isUiVisible = isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay

        val commentLayer = @Composable {
            if (isHeavyUiReady && isCommentEnabled) {
                LiveCommentOverlay(modifier = Modifier.fillMaxSize(), useSoftwareRendering = isEmulator, speed = commentSpeed, opacity = commentOpacity, maxLines = commentMaxLines, onViewCreated = { view ->
                    danmakuViewRef.value = view
                    if (!isPlayerPlaying) view.pause()
                })
            }
        }

        val subtitleLayer = @Composable {
            if (isHeavyUiReady) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(-1, -1);
                            setBackgroundColor(android.graphics.Color.TRANSPARENT);
                            settings.apply { javaScriptEnabled = true; domStorageEnabled = true };
                            loadUrl("file:///android_asset/subtitle_renderer.html");
                            webViewRef.value = this
                        }
                    },
                    update = { view ->
                        view.visibility = if (isSubtitleEnabled && !isUiVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 後から描画したものが上になる
        if (subtitleCommentLayer == "CommentOnTop") {
            subtitleLayer()
            commentLayer()
        } else {
            commentLayer()
            subtitleLayer()
        }

        AnimatedVisibility(visible = currentStreamSource == StreamSource.KONOMITV && (sseStatus == "Standby" || sseStatus == "Offline") && playerError == null) {
            Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
                Row(modifier = Modifier.align(Alignment.TopStart).padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = colors.textPrimary, modifier = Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(16.dp))
                    Text(text = sseDetail, color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(visible = isPinnedOverlay && playerError == null) { StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort, konomiIp, konomiPort) }

        val displayTitle = currentChannelItem.programPresent?.title ?: "番組情報なし"
        AnimatedVisibility(visible = showOverlay && playerError == null && !isMiniListOpen, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            LiveOverlayUI(currentChannelItem, displayTitle, mirakurunIp ?: "", mirakurunPort ?: "", konomiIp, konomiPort, isManualOverlay, isRecording, scrollState)
        }

        AnimatedVisibility(
            visible = isMiniListOpen && playerError == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            ChannelListOverlay(
                groupedChannels = groupedChannels,
                currentChannelId = currentChannelItem.id,
                onChannelSelect = { onChannelSelect(it); onMiniListToggle(false); scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) } },
                mirakurunIp = mirakurunIp ?: "",
                mirakurunPort = mirakurunPort ?: "",
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                focusRequester = listFocusRequester
            )
        }

        AnimatedVisibility(visible = isSubMenuOpen && playerError == null, enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()) {
            TopSubMenuUI(
                currentAudioMode = currentAudioMode, currentSource = currentStreamSource, currentQuality = currentQuality, isMirakurunAvailable = isMirakurunAvailable,
                isSubtitleEnabled = isSubtitleEnabled, isSubtitleSupported = currentStreamSource != StreamSource.MIRAKURUN, isCommentEnabled = isCommentEnabled, isRecording = isRecording,
                onRecordToggle = {
                    if (isRecording) { activeReserve?.let { reserveViewModel.deleteReservation(it.id) { onShowToast("録画を停止しました") } } }
                    else { currentProgramId?.let { reserveViewModel.addReserve(it) { onShowToast("録画を開始します") } } ?: onShowToast("番組情報不明") }
                    onSubMenuToggle(false)
                },
                focusRequester = subMenuFocusRequester,
                onAudioToggle = {
                    currentAudioMode = if(currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.size >= 2) exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(TrackSelectionOverride(audioGroups[if(currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup, 0)).build()
                    onShowToast("音声: ${if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                },
                onSourceToggle = { if (isMirakurunAvailable) { currentStreamSource = if(currentStreamSource == StreamSource.MIRAKURUN) StreamSource.KONOMITV else StreamSource.MIRAKURUN; onShowToast("ソース切替"); onSubMenuToggle(false) } },
                onSubtitleToggle = { subtitleEnabledState.value = !subtitleEnabledState.value; onShowToast("字幕: ${if(subtitleEnabledState.value) "表示" else "非表示"}") },
                onCommentToggle = {
                    isCommentEnabled = !isCommentEnabled
                    onShowToast("実況: ${if(isCommentEnabled) "表示" else "非表示"}")
                },
                onQualitySelect = { if (currentQuality != it) { currentQuality = it; retryKey++; onShowToast("画質: ${it.label}") }; onSubMenuToggle(false) },
                onCloseMenu = { onSubMenuToggle(false) }
            )
        }

        if (playerError != null) LiveErrorDialog(errorMessage = playerError!!, onRetry = { playerError = null; retryKey++ }, onBack = onBackPressed)
    }
}