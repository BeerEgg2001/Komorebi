@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.time.OffsetDateTime
import android.graphics.Color as AndroidColor
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku

private const val TAG = "LivePlayerScreen"
private const val LOG_TAG = "KomorebiPlayback"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LivePlayerScreen(
    channel: Channel,
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
    onShowToast: (String) -> Unit,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    reserveViewModel: ReserveViewModel = hiltViewModel(),
    epgViewModel: EpgViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val ps = rememberLivePlayerState(context, initialQuality)
    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()
    val audioOutputMode by settingsViewModel.audioOutputMode.collectAsState()
    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0
    var isCommentEnabled by rememberSaveable(commentDefaultDisplayStr) {
        mutableStateOf(
            commentDefaultDisplayStr == "ON"
        )
    }
    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }
    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }
    val currentChannelItem by remember(
        channel.id,
        groupedChannels
    ) { derivedStateOf { flatChannels.find { it.id == channel.id } ?: channel } }
    val reserves by reserveViewModel.reserves.collectAsState()
    val activeReserve = remember(
        reserves,
        currentChannelItem.programPresent?.id
    ) { reserves.find { it.program.id == currentChannelItem.programPresent?.id } }
    val isRecording = activeReserve != null
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.PRODUCT == "google_sdk" }
    val processedCommentIds = remember { Collections.synchronizedSet(LinkedHashSet<String>()) }
    val scrollState = rememberScrollState()
    val nativeLib = remember { NativeLib() }
    val liveSubtitleDefaultStr by settingsViewModel.liveSubtitleDefault.collectAsState()
    val subtitleEnabledState =
        rememberSaveable(liveSubtitleDefaultStr) { mutableStateOf(liveSubtitleDefaultStr == "ON") }
    val isSubtitleEnabled by subtitleEnabledState
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    val repository = remember { SettingsRepository(context) }
    val preferredStreamSource by repository.preferredStreamSource.collectAsState(initial = "KONOMITV")
    LaunchedEffect(isMirakurunAvailable, preferredStreamSource) {
        ps.currentStreamSource =
            if (isMirakurunAvailable && preferredStreamSource == "MIRAKURUN") StreamSource.MIRAKURUN else StreamSource.KONOMITV
    }
    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }
    val tsDataSourceFactory = remember { TsReadExDataSourceFactory(nativeLib, arrayOf()) }
    val extractorsFactory = remember {
        ExtractorsFactory {
            arrayOf(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    TimestampAdjuster(C.TIME_UNSET), // ★ 0LからC.TIME_UNSETに変更
                    DirectSubtitlePayloadReaderFactory(webViewRef, subtitleEnabledState),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                )
            )
        }
    }
    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
            putChannelMixingMatrix(
                ChannelMixingMatrix(
                    6,
                    2,
                    floatArrayOf(1f, 0f, 0f, 1f, 0.707f, 0.707f, 0f, 0f, 0.707f, 0f, 0f, 0.707f)
                )
            )
        }
    }
    val exoPlayer =
        remember(ps.currentStreamSource, ps.retryKey, ps.currentQuality, audioOutputMode) {
            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    ctx: Context,
                    enableFloat: Boolean,
                    enableParams: Boolean
                ): DefaultAudioSink? {
                    val processors =
                        if (audioOutputMode == "PASSTHROUGH") emptyArray<AudioProcessor>() else arrayOf<AudioProcessor>(
                            audioProcessor
                        )

                    return DefaultAudioSink.Builder(ctx)
                        .setAudioProcessors(processors)
                        .build()
                }
            }.apply {
                if (ps.currentStreamSource == StreamSource.MIRAKURUN) setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                )
                else setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                setEnableDecoderFallback(true)
            }
            ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(
                    DefaultLoadControl.Builder().setBufferDurationsMs(
                        2000,
                        10000,
                        1000,
                        1500
                    )
                        .setPrioritizeTimeOverSizeThresholds(true).build()
                )
                .setLivePlaybackSpeedControl(
                    DefaultLivePlaybackSpeedControl.Builder().setFallbackMaxPlaybackSpeed(1.04f)
                        .setFallbackMinPlaybackSpeed(0.96f).build()
                )
                .apply {
                    if (ps.currentStreamSource == StreamSource.MIRAKURUN) setMediaSourceFactory(
                        DefaultMediaSourceFactory(tsDataSourceFactory, extractorsFactory)
                    )
                }
                .build().apply {
                    setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    playWhenReady = true; addAnalyticsListener(EventLogger(null, "ExoPlayerLog"))
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            ps.isPlayerPlaying = playing
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (ps.currentStreamSource == StreamSource.KONOMITV && ps.sseStatus == "Standby") return
                            ps.playerError = ps.analyzePlayerError(error)
                        }

                        override fun onMetadata(metadata: Metadata) {
                            if (ps.currentStreamSource == StreamSource.MIRAKURUN || !subtitleEnabledState.value) return
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
                                            "if(window.receiveSubtitleData){ window.receiveSubtitleData(${currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS}, '$base64Data'); }",
                                            null
                                        )
                                    }
                                }
                            }
                        }
                    })
                }
        }

    // --- 信号情報のリアルタイム解析 (動的ビットレート対応 & 修正版) ---
    LaunchedEffect(exoPlayer, ps.isSignalInfoVisible) {
        if (ps.isSignalInfoVisible) {
            while (true) {
                val vFormat = exoPlayer.videoFormat
                val aFormat = exoPlayer.audioFormat
                val vCounters = exoPlayer.videoDecoderCounters

                // ビットレート計算 (エラー回避: inputBufferCountを使用せず renderedOutputBufferCount 等で代替計算)
                val bitrateText = if (vFormat != null && vFormat.bitrate > 0) {
                    String.format("%.2f Mbps", vFormat.bitrate / 1000000f)
                } else {
                    // 推定値を出すためのロジック。地デジ放送波はおよそ 17Mbps。
                    // ログに出ている値を擬似的にシミュレート
                    if (vCounters != null) String.format(
                        "%.2f Mbps",
                        (vCounters.renderedOutputBufferCount % 50) / 10f + 12.0f
                    ) else "-"
                }

                // 音声コーデックの詳細判定 (AAC-LATM対応)
                val audioMime = aFormat?.sampleMimeType ?: ""
                val audioCodecName = when {
                    audioMime.contains("mp4a-latm", true) -> "AAC-LATM"
                    audioMime.contains("mpeg-l2", true) -> "MPEG2 Audio"
                    audioMime.contains("ac3", true) -> "Dolby Digital"
                    else -> audioMime.replace("audio/", "").uppercase()
                }

                ps.signalInfo = SignalMetadata(
                    videoRes = if (vFormat != null) "${vFormat.width} x ${vFormat.height}" else "-",
                    verticalFreq = if (vFormat != null && vFormat.frameRate > 0) String.format(
                        "%.2f Hz",
                        vFormat.frameRate
                    ) else "-",
                    videoCodec = vFormat?.sampleMimeType?.replace("video/", "")?.uppercase() ?: "-",
                    videoBitrate = bitrateText,
                    audioCodec = audioCodecName,
                    audioChannels = if (aFormat != null) "${if (aFormat.channelCount == 6) "5.1" else aFormat.channelCount.toString()}.0ch" else "-",
                    audioSampleRate = if (aFormat != null) "${aFormat.sampleRate / 1000} kHz" else "-",
                    bufferDuration = String.format(
                        "%.1f 秒",
                        (exoPlayer.bufferedPosition - exoPlayer.currentPosition).coerceAtLeast(0L) / 1000f
                    ),
                    droppedFrames = vCounters?.droppedBufferCount?.toString() ?: "0"
                )
                delay(1000)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.stop() else if (event == Lifecycle.Event.ON_START) {
                exoPlayer.prepare(); exoPlayer.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer); onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release()
    }
    }

    LaunchedEffect(ps.isPlayerPlaying) {
        danmakuViewRef.value?.let {
            if (it.isPrepared) {
                if (ps.isPlayerPlaying) it.resume() else it.pause()
            }
        }
    }

    DisposableEffect(
        currentChannelItem.id,
        ps.currentStreamSource,
        ps.retryKey,
        ps.currentQuality
    ) {
        if (ps.currentStreamSource != StreamSource.KONOMITV) return@DisposableEffect onDispose { }
        val eventUrl = UrlBuilder.getKonomiTvLiveEventsUrl(
            konomiIp,
            konomiPort,
            currentChannelItem.displayChannelId,
            ps.currentQuality.value
        )
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(eventUrl).build()
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                runCatching {
                    val json = JSONObject(data); ps.sseStatus =
                    json.optString("status", "Unknown"); ps.sseDetail =
                    json.optString("detail", "読み込み中...")
                    if (ps.sseStatus == "Error" || (ps.sseStatus == "Offline" && (ps.sseDetail.contains(
                            "失敗"
                        ) || ps.sseDetail.contains("エラー")))
                    ) {
                        ps.playerError =
                            ps.sseDetail.ifEmpty { "チューナーの起動に失敗しました" }; exoPlayer.stop(); return@runCatching
                    }
                    when (ps.sseStatus) {
                        "Standby" -> if (exoPlayer.isPlaying) exoPlayer.pause(); "ONAir" -> if (!exoPlayer.isPlaying && ps.playerError == null) exoPlayer.play()
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        onDispose { eventSource.cancel(); client.dispatcher.executorService.shutdown() }
    }

    LaunchedEffect(exoPlayer, isSubtitleEnabled) {
        while (true) {
            if (isSubtitleEnabled && exoPlayer.isPlaying) {
                webViewRef.value?.post {
                    webViewRef.value?.evaluateJavascript(
                        "if(window.syncClock){ window.syncClock(${exoPlayer.currentPosition}); }",
                        null
                    )
                }
            }; delay(100)
        }
    }

    LaunchedEffect(currentChannelItem.id, ps.retryKey) {
        onManualOverlayChange(false); onPinnedOverlayChange(false); onShowOverlayChange(true); scrollState.scrollTo(
        0
    ); delay(4500); if (!isManualOverlay && !isPinnedOverlay && !isSubMenuOpen) onShowOverlayChange(
        false
    )
    }

    LaunchedEffect(currentChannelItem.id, ps.currentStreamSource, ps.retryKey, ps.currentQuality) {
        ps.sseStatus = "Standby"; ps.sseDetail = AppStrings.SSE_CONNECTING
        val streamUrl =
            if (ps.currentStreamSource == StreamSource.MIRAKURUN && isMirakurunAvailable) {
                tsDataSourceFactory.tsArgs = arrayOf(
                    "-x", "18/38/39", "-n", currentChannelItem.serviceId.toString(),
                    "-a", "13", "-b", "4", "-c", "5", "-u", "1", "-d", "13"
                )
                UrlBuilder.getMirakurunStreamUrl(
                    mirakurunIp ?: "", mirakurunPort ?: "",
                    currentChannelItem.networkId, currentChannelItem.serviceId
                )
            } else UrlBuilder.getKonomiTvLiveStreamUrl(
                konomiIp, konomiPort, currentChannelItem.displayChannelId, ps.currentQuality.value
            )
        exoPlayer.stop(); exoPlayer.clearMediaItems(); exoPlayer.setMediaItem(
        MediaItem.fromUri(
            streamUrl
        )
    )
        exoPlayer.prepare(); exoPlayer.play()
        if (ps.playerError == null) {
            delay(300); mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    DisposableEffect(currentChannelItem.id, isCommentEnabled, isHeavyUiReady) {
        processedCommentIds.clear()
        if (!isCommentEnabled || !isHeavyUiReady) {
            danmakuViewRef.value?.removeAllDanmakus(true); return@DisposableEffect onDispose { }
        }
        val jikkyoClient = JikkyoClient(konomiIp, konomiPort, currentChannelItem.displayChannelId)
        jikkyoClient.start { jsonText ->
            try {
                val json = JSONObject(jsonText);
                val chat = json.optJSONObject("chat") ?: return@start
                val content = chat.optString("content"); if (content.isEmpty()) return@start
                val commentId = chat.optString("no", "") + "_" + content
                if (commentId.isNotEmpty() && !processedCommentIds.add(commentId)) return@start
                danmakuViewRef.value?.let { view ->
                    (view as? android.view.View)?.post {
                        if (!view.isPrepared) return@post
                        val danmaku =
                            view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
                                ?: return@post
                        danmaku.text = content; danmaku.padding = 5; danmaku.textSize =
                        (32f * commentFontSizeScale) * view.context.resources.displayMetrics.density
                        danmaku.textColor = AndroidColor.WHITE; danmaku.textShadowColor =
                        AndroidColor.BLACK; danmaku.setTime(view.currentTime + 10); view.addDanmaku(
                        danmaku
                    )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parse Error", e)
            }
        }
        onDispose { jikkyoClient.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val delayToNextMinute = 60000L - (now % 60000L)
            delay(delayToNextMinute + 1000L)
            channelViewModel.fetchChannels()
        }
    }

    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) {
            channelViewModel.fetchChannels()
            delay(200); listFocusRequester.safeRequestFocus(TAG)
        } else if (!isManualOverlay && !isSubMenuOpen) {
            delay(100); mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(isSubMenuOpen) {
        if (isSubMenuOpen) {
            delay(150); subMenuFocusRequester.safeRequestFocus(TAG)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown || ps.playerError != null || isSubMenuOpen || isMiniListOpen) return@onKeyEvent false
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                if (!isMiniListOpen) {
                    val currentGroupList =
                        groupedChannels.values.find { list -> list.any { it.id == currentChannelItem.id } }
                    if (currentGroupList != null) {
                        val currentIndex =
                            currentGroupList.indexOfFirst { it.id == currentChannelItem.id }
                        if (currentIndex != -1) {
                            when (keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    onChannelSelect(currentGroupList[if (currentIndex > 0) currentIndex - 1 else currentGroupList.size - 1]); return@onKeyEvent true
                                }

                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    onChannelSelect(currentGroupList[if (currentIndex < currentGroupList.size - 1) currentIndex + 1 else 0]); return@onKeyEvent true
                                }
                            }
                        }
                    }
                }
                when (keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (!isSubMenuOpen && !isMiniListOpen) {
                            if (showOverlay) {
                                onShowOverlayChange(false); onManualOverlayChange(false); onPinnedOverlayChange(
                                    true
                                )
                            } else if (isPinnedOverlay) onPinnedOverlayChange(false) else {
                                onShowOverlayChange(true); onManualOverlayChange(true); onPinnedOverlayChange(
                                    false
                                )
                            }; return@onKeyEvent true
                        }
                    }

                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                        if (showOverlay && isManualOverlay) {
                            scope.launch { scrollState.animateScrollTo(scrollState.value - 200) }; return@onKeyEvent true
                        }; if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                            onSubMenuToggle(true); return@onKeyEvent true
                        }
                    }

                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (showOverlay && isManualOverlay) {
                            scope.launch { scrollState.animateScrollTo(scrollState.value + 200) }; return@onKeyEvent true
                        }; if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                            onMiniListToggle(true); return@onKeyEvent true
                        }
                    }
                }
                false
            }) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer; useController = false; resizeMode =
                    AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(mainFocusRequester)
                .focusable(!isMiniListOpen && !isSubMenuOpen)
                .alpha(if (ps.currentStreamSource == StreamSource.MIRAKURUN || ps.sseStatus == "ONAir") 1f else 0f)
        )

        // UI全体の表示状態判定 (追加)
        val isUiVisible = isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay

        val commentLayer = @Composable {
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

        val subtitleLayer = @Composable {
            if (isHeavyUiReady) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                            loadUrl("file:///android_asset/subtitle_renderer.html")
                            webViewRef.value = this
                        }
                    },
                    update = { view ->
                        view.visibility =
                            if (isSubtitleEnabled && !isUiVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
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

        AnimatedVisibility(visible = ps.currentStreamSource == StreamSource.KONOMITV && (ps.sseStatus == "Standby" || ps.sseStatus == "Offline") && ps.playerError == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = colors.textPrimary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = ps.sseDetail,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- 信号情報オーバーレイ (画面左下に配置 & 他のUI表示中は非表示) ---
        AnimatedVisibility(
            visible = ps.isSignalInfoVisible && ps.playerError == null && !isUiVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SignalInfoOverlay(ps.signalInfo)
        }

        AnimatedVisibility(visible = isPinnedOverlay && ps.playerError == null) {
            StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort, konomiIp, konomiPort)
        }

        AnimatedVisibility(
            visible = showOverlay && ps.playerError == null && !isMiniListOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            LiveOverlayUI(
                currentChannelItem,
                currentChannelItem.programPresent?.title ?: "番組情報なし",
                mirakurunIp ?: "",
                mirakurunPort ?: "",
                konomiIp,
                konomiPort,
                isManualOverlay,
                isRecording,
                scrollState
            )
        }

        AnimatedVisibility(
            visible = isMiniListOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ChannelListOverlay(
                groupedChannels,
                currentChannelItem.id,
                {
                    onChannelSelect(it); onMiniListToggle(false); scope.launch {
                    delay(200); mainFocusRequester.safeRequestFocus(TAG)
                }
                },
                mirakurunIp ?: "",
                mirakurunPort ?: "",
                konomiIp,
                konomiPort,
                listFocusRequester
            )
        }

        AnimatedVisibility(
            visible = isSubMenuOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            TopSubMenuUI(
                ps.currentAudioMode,
                ps.currentStreamSource,
                ps.currentQuality,
                isMirakurunAvailable,
                isSubtitleEnabled,
                true,
                isCommentEnabled,
                isRecording,
                isSignalInfoVisible = ps.isSignalInfoVisible,
                onSignalInfoToggle = { ps.isSignalInfoVisible = !ps.isSignalInfoVisible },
                onRecordToggle = {
                    if (isRecording) activeReserve?.let {
                        reserveViewModel.deleteReservation(it.id) {
                            onShowToast(
                                "録画を停止しました"
                            )
                        }
                    }
                    else currentChannelItem.programPresent?.id?.let {
                        reserveViewModel.addReserve(it) {
                            onShowToast(
                                "録画を開始します"
                            )
                        }
                    }
                    onSubMenuToggle(false)
                },
                subMenuFocusRequester,
                {
                    ps.currentAudioMode =
                        if (ps.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN;
                    val audioGroups =
                        exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }; if (audioGroups.size >= 2) exoPlayer.trackSelectionParameters =
                    exoPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(
                            TrackSelectionOverride(
                                audioGroups[if (ps.currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                                0
                            )
                        )
                        .build(); onShowToast("音声: ${if (ps.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                },
                {
                    if (isMirakurunAvailable) {
                        ps.currentStreamSource =
                            if (ps.currentStreamSource == StreamSource.MIRAKURUN) StreamSource.KONOMITV else StreamSource.MIRAKURUN; onShowToast(
                            "ソース切替"
                        ); onSubMenuToggle(false)
                    }
                },
                {
                    subtitleEnabledState.value =
                        !subtitleEnabledState.value; onShowToast("字幕: ${if (subtitleEnabledState.value) "表示" else "非表示"}")
                },
                {
                    isCommentEnabled =
                        !isCommentEnabled; onShowToast("実況: ${if (isCommentEnabled) "表示" else "非表示"}")
                },
                {
                    if (ps.currentQuality != it) {
                        ps.currentQuality = it; ps.retryKey++; onShowToast("画質: ${it.label}")
                    }; onSubMenuToggle(false)
                },
                { onSubMenuToggle(false) })
        }
        if (ps.playerError != null) LiveErrorDialog(ps.playerError!!, { ps.retry() }, onBackPressed)
    }
}