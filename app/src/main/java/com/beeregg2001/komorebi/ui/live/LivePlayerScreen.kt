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
import android.graphics.Color as AndroidColor
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku

private const val TAG = "LivePlayerScreen"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LivePlayerScreen(
    channel: Channel,
    mirakurunIp: String?,
    mirakurunPort: String?,
    konomiIp: String = "192-168-100-60.local.konomi.tv",
    konomiPort: String = "7000",
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
    epgViewModel: EpgViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    timeFormat: String = "24H"
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    // プレイヤーの状態を一元管理するStateオブジェクト
    val ps = rememberLivePlayerState(context, initialQuality)

    // チャンネルリストの取得と加工
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

    // 各種設定の読み込み
    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()
    val audioOutputMode by settingsViewModel.audioOutputMode.collectAsState()
    val liveSubtitleDefaultStr by settingsViewModel.liveSubtitleDefault.collectAsState()

    val allowMirakurunDual by settingsViewModel.labAllowMirakurunDual.collectAsState()

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

    var isHeavyUiReady by remember { mutableStateOf(false) }

    val currentIsManualOverlay by rememberUpdatedState(isManualOverlay)
    val currentIsPinnedOverlay by rememberUpdatedState(isPinnedOverlay)
    val currentIsSubMenuOpen by rememberUpdatedState(isSubMenuOpen)

    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }

    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.PRODUCT == "google_sdk" }
    val processedCommentIds = remember { Collections.synchronizedSet(LinkedHashSet<String>()) }
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val dualWebViewRef = remember { mutableStateOf<WebView?>(null) }

    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    val repository = remember { SettingsRepository(context) }
    val preferredStreamSource by repository.preferredStreamSource.collectAsState(initial = "KONOMITV")

    // 初期ストリームソースの決定
    LaunchedEffect(isMirakurunAvailable, preferredStreamSource) {
        if (ps.previousStreamSource == null) {
            ps.currentStreamSource =
                if (isMirakurunAvailable && preferredStreamSource == "MIRAKURUN") StreamSource.MIRAKURUN else StreamSource.KONOMITV
        }
    }

    // PiPモード移行・復帰時のソース切り替え制御
    LaunchedEffect(isPiPMode) {
        if (isPiPMode) {
            if (ps.currentStreamSource == StreamSource.MIRAKURUN && allowMirakurunDual != "ON") {
                ps.previousStreamSource = ps.currentStreamSource
                ps.currentStreamSource = StreamSource.KONOMITV
                onShowToast("負荷軽減のためKonomiTVソースに切り替えました")
            }
        } else {
            if (!ps.isDualDisplayMode && ps.previousStreamSource != null) {
                ps.currentStreamSource = ps.previousStreamSource!!
                ps.previousStreamSource = null
                onShowToast("元のストリーミングソースに復帰しました")
            }
        }
    }

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val nativeLib = remember { NativeLib() }

    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }

    // メインプレイヤー用 TSデータソースとエクストラクターの設定
    val tsDataSourceFactory =
        remember(nativeLib) { TsReadExDataSourceFactory(nativeLib, arrayOf()) }
    val extractorsFactory = remember(subtitleEnabledState) {
        ExtractorsFactory {
            arrayOf(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    TimestampAdjuster(C.TIME_UNSET),
                    DirectSubtitlePayloadReaderFactory(webViewRef, subtitleEnabledState),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                )
            )
        }
    }

    // オーディオ出力制御用（5.1chのダウンミックスなど）
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

    // メインプレイヤーの初期化
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
                    return DefaultAudioSink.Builder(ctx).setAudioProcessors(processors).build()
                }
            }.apply {
                if (ps.currentStreamSource == StreamSource.MIRAKURUN) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                } else {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                }
                setEnableDecoderFallback(true)
            }

            ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(2000, 10000, 1000, 1500)
                        .setPrioritizeTimeOverSizeThresholds(true).build()
                )
                .setLivePlaybackSpeedControl(
                    DefaultLivePlaybackSpeedControl.Builder()
                        .setFallbackMaxPlaybackSpeed(1.04f)
                        .setFallbackMinPlaybackSpeed(0.96f).build()
                )
                .apply {
                    if (ps.currentStreamSource == StreamSource.MIRAKURUN) {
                        setMediaSourceFactory(
                            DefaultMediaSourceFactory(
                                tsDataSourceFactory,
                                extractorsFactory
                            )
                        )
                    }
                }
                .build().apply {
                    setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    playWhenReady = true
                    addAnalyticsListener(EventLogger(null, "ExoPlayerLog"))

                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            videoWidth = videoSize.width
                            videoHeight = videoSize.height
                            pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
                        }

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

    // サブプレイヤー（二画面用）の各種設定
    var dualVideoWidth by remember { mutableIntStateOf(0) }
    var dualVideoHeight by remember { mutableIntStateOf(0) }
    var dualPixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }

    val dualTsDataSourceFactory =
        remember(nativeLib) { TsReadExDataSourceFactory(nativeLib, arrayOf()) }
    val dualExtractorsFactory = remember(subtitleEnabledState) {
        ExtractorsFactory {
            arrayOf(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    TimestampAdjuster(C.TIME_UNSET),
                    DirectSubtitlePayloadReaderFactory(dualWebViewRef, subtitleEnabledState),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                )
            )
        }
    }
    val dualAudioProcessor = remember {
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

    // サブプレイヤーの初期化
    val dualExoPlayer = remember(
        ps.isDualDisplayMode,
        ps.currentStreamSource,
        ps.retryKey,
        ps.currentQuality,
        audioOutputMode
    ) {
        if (!ps.isDualDisplayMode) return@remember null

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                val processors =
                    if (audioOutputMode == "PASSTHROUGH") emptyArray<AudioProcessor>() else arrayOf<AudioProcessor>(
                        dualAudioProcessor
                    )
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(processors).build()
            }
        }.apply {
            if (ps.currentStreamSource == StreamSource.MIRAKURUN) {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            } else {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            }
            setEnableDecoderFallback(true)
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2000, 10000, 1000, 1500)
                    .setPrioritizeTimeOverSizeThresholds(true).build()
            )
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.04f)
                    .setFallbackMinPlaybackSpeed(0.96f).build()
            )
            .apply {
                if (ps.currentStreamSource == StreamSource.MIRAKURUN) {
                    setMediaSourceFactory(
                        DefaultMediaSourceFactory(
                            dualTsDataSourceFactory,
                            dualExtractorsFactory
                        )
                    )
                }
            }
            .build().apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        dualVideoWidth = videoSize.width
                        dualVideoHeight = videoSize.height
                        dualPixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
                    }
                })
            }
    }

    DisposableEffect(dualExoPlayer) {
        onDispose { dualExoPlayer?.release() }
    }

    // 二画面モード時の音声出力の切り替え（選択している側の音のみ出す）
    LaunchedEffect(ps.isDualDisplayMode, ps.activeDualPlayerIndex, exoPlayer, dualExoPlayer) {
        if (ps.isDualDisplayMode) {
            exoPlayer.volume = if (ps.activeDualPlayerIndex == 0) 1f else 0f
            dualExoPlayer?.volume = if (ps.activeDualPlayerIndex == 1) 1f else 0f
        } else {
            exoPlayer.volume = 1f
            dualExoPlayer?.volume = 0f
        }
    }

    var hasStoppedByLifecycle by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // メインプレイヤーのライフサイクル管理
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                hasStoppedByLifecycle = true
                runCatching {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
            } else if (event == Lifecycle.Event.ON_START) {
                if (hasStoppedByLifecycle) {
                    hasStoppedByLifecycle = false
                    ps.retryKey++
                    channelViewModel.fetchChannels()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                exoPlayer.stop()
                exoPlayer.release()
            }
        }
    }

    // サブプレイヤーのライフサイクル管理
    DisposableEffect(lifecycleOwner, dualExoPlayer) {
        if (dualExoPlayer == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching {
                    dualExoPlayer.stop()
                    dualExoPlayer.clearMediaItems()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 再生状態とニコニコ実況(Danmaku)の同期
    LaunchedEffect(ps.isPlayerPlaying) {
        danmakuViewRef.value?.let {
            if (it.isPrepared) {
                if (ps.isPlayerPlaying) it.resume() else it.pause()
            }
        }
    }

    // メインプレイヤーへのURLセットと再生開始処理
    LaunchedEffect(
        currentChannelItem.id,
        ps.currentStreamSource,
        ps.retryKey,
        ps.currentQuality,
        ps.isDualDisplayMode,
        isPiPMode
    ) {
        if (currentChannelItem.displayChannelId.isBlank() || currentChannelItem.displayChannelId == "null") {
            return@LaunchedEffect
        }

        ps.sseStatus = "Standby"
        ps.sseDetail = AppStrings.SSE_CONNECTING

        val targetQuality =
            if ((ps.isDualDisplayMode || isPiPMode) && ps.currentQuality.value.contains("1080")) "720p" else ps.currentQuality.value

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
            } else {
                UrlBuilder.getKonomiTvLiveStreamUrl(
                    konomiIp, konomiPort, currentChannelItem.displayChannelId, targetQuality
                )
            }

        runCatching {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            if (ps.isDualDisplayMode) delay(200)
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            exoPlayer.prepare()
            exoPlayer.play()
        }.onFailure {
            ps.playerError = AppStrings.LIVE_PLAYER_INIT_ERROR
        }

        if (ps.playerError == null) {
            delay(300); mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    // サブプレイヤーへのURLセットと再生開始処理
    LaunchedEffect(
        ps.dualRightChannel,
        ps.currentStreamSource,
        ps.isDualDisplayMode,
        ps.retryKey,
        ps.currentQuality,
        dualExoPlayer
    ) {
        val rightChannel = ps.dualRightChannel
        if (ps.isDualDisplayMode && rightChannel != null && dualExoPlayer != null) {
            if (rightChannel.displayChannelId.isBlank() || rightChannel.displayChannelId == "null") {
                return@LaunchedEffect
            }

            ps.dualSseStatus = "Standby"
            ps.dualSseDetail = AppStrings.SSE_CONNECTING

            val targetQuality =
                if (ps.isDualDisplayMode && ps.currentQuality.value.contains("1080")) "720p" else ps.currentQuality.value

            val streamUrl =
                if (ps.currentStreamSource == StreamSource.MIRAKURUN && isMirakurunAvailable) {
                    dualTsDataSourceFactory.tsArgs = arrayOf(
                        "-x", "18/38/39", "-n", rightChannel.serviceId.toString(),
                        "-a", "13", "-b", "4", "-c", "5", "-u", "1", "-d", "13"
                    )
                    UrlBuilder.getMirakurunStreamUrl(
                        mirakurunIp ?: "", mirakurunPort ?: "",
                        rightChannel.networkId, rightChannel.serviceId
                    )
                } else {
                    UrlBuilder.getKonomiTvLiveStreamUrl(
                        konomiIp, konomiPort, rightChannel.displayChannelId, targetQuality
                    )
                }

            runCatching {
                dualExoPlayer.stop()
                dualExoPlayer.clearMediaItems()
                delay(300)
                dualExoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                dualExoPlayer.prepare()
                dualExoPlayer.play()
            }
        } else {
            runCatching {
                dualExoPlayer?.stop()
                dualExoPlayer?.clearMediaItems()
            }
        }
    }

    // サブプレイヤー用のKonomiTV SSE(Server-Sent Events)通信
    DisposableEffect(
        ps.dualRightChannel,
        ps.currentStreamSource,
        ps.isDualDisplayMode,
        ps.retryKey,
        ps.currentQuality,
        dualExoPlayer
    ) {
        if (ps.currentStreamSource != StreamSource.KONOMITV || !ps.isDualDisplayMode || ps.dualRightChannel == null || dualExoPlayer == null) return@DisposableEffect onDispose { }

        val rightChannel = ps.dualRightChannel!!
        if (rightChannel.displayChannelId.isBlank() || rightChannel.displayChannelId == "null") return@DisposableEffect onDispose { }

        val targetQuality =
            if (ps.isDualDisplayMode && ps.currentQuality.value.contains("1080")) "720p" else ps.currentQuality.value

        val eventUrl = UrlBuilder.getKonomiTvLiveEventsUrl(
            konomiIp,
            konomiPort,
            rightChannel.displayChannelId,
            targetQuality
        )
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder()
            .url(eventUrl)
            .header("User-Agent", "Komorebi/1.0 (Dual)")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                runCatching {
                    val json = JSONObject(data)
                    val status = json.optString("status", "Unknown")
                    ps.dualSseStatus = status
                    ps.dualSseDetail = json.optString("detail", AppStrings.STATUS_LOADING)

                    when (status) {
                        "Standby", "Restart" -> if (dualExoPlayer.isPlaying) dualExoPlayer.pause()
                        "ONAir" -> {
                            if (dualExoPlayer.playerError != null) {
                                dualExoPlayer.prepare()
                            }
                            if (!dualExoPlayer.isPlaying) dualExoPlayer.play()
                        }

                        "Offline", "Error" -> dualExoPlayer.stop()
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        onDispose { eventSource.cancel(); client.dispatcher.executorService.shutdown() }
    }

    // 字幕(WebView)への時刻同期クロック
    LaunchedEffect(exoPlayer, dualExoPlayer, isSubtitleEnabled) {
        while (true) {
            if (isSubtitleEnabled) {
                if (exoPlayer.isPlaying) {
                    webViewRef.value?.post {
                        webViewRef.value?.evaluateJavascript(
                            "if(window.syncClock){ window.syncClock(${exoPlayer.currentPosition}); }",
                            null
                        )
                    }
                }
                if (ps.isDualDisplayMode && dualExoPlayer?.isPlaying == true) {
                    dualWebViewRef.value?.post {
                        dualWebViewRef.value?.evaluateJavascript(
                            "if(window.syncClock){ window.syncClock(${dualExoPlayer.currentPosition}); }",
                            null
                        )
                    }
                }
            }
            delay(100)
        }
    }

    // メインプレイヤー用のKonomiTV SSE通信
    DisposableEffect(
        currentChannelItem.id,
        ps.currentStreamSource,
        ps.retryKey,
        ps.currentQuality,
        ps.isDualDisplayMode,
        isPiPMode
    ) {
        if (ps.currentStreamSource != StreamSource.KONOMITV) return@DisposableEffect onDispose { }
        if (currentChannelItem.displayChannelId.isBlank() || currentChannelItem.displayChannelId == "null") {
            return@DisposableEffect onDispose { }
        }

        val targetQuality =
            if ((ps.isDualDisplayMode || isPiPMode) && ps.currentQuality.value.contains("1080")) "720p" else ps.currentQuality.value

        val eventUrl = UrlBuilder.getKonomiTvLiveEventsUrl(
            konomiIp,
            konomiPort,
            currentChannelItem.displayChannelId,
            targetQuality
        )
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder()
            .url(eventUrl)
            .header("User-Agent", "Komorebi/1.0 (Main)")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                runCatching {
                    val json = JSONObject(data)
                    ps.sseStatus = json.optString("status", "Unknown")

                    val detailMsg = json.optString("detail", AppStrings.STATUS_LOADING)
                    ps.sseDetail = if (detailMsg.contains("OnAirです")) "" else detailMsg

                    if (ps.sseStatus == "Error" || (ps.sseStatus == "Offline" && (ps.sseDetail.contains(
                            "失敗"
                        ) || ps.sseDetail.contains("エラー")))
                    ) {
                        ps.playerError = ps.sseDetail.ifEmpty { AppStrings.ERR_TUNER_START_FAILED }
                        exoPlayer.stop()
                        return@runCatching
                    }

                    when (ps.sseStatus) {
                        "Standby", "Restart" -> if (exoPlayer.isPlaying) exoPlayer.pause()
                        "ONAir" -> {
                            if (exoPlayer.playerError != null || ps.playerError != null) {
                                ps.playerError = null
                                exoPlayer.prepare()
                            }
                            if (!exoPlayer.isPlaying && ps.playerError == null) exoPlayer.play()
                        }

                        "Offline" -> exoPlayer.stop()
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        onDispose { eventSource.cancel(); client.dispatcher.executorService.shutdown() }
    }

    // ニコニコ実況 (WebSocket) の購読
    DisposableEffect(
        currentChannelItem.id,
        isCommentEnabled,
        isHeavyUiReady,
        ps.isDualDisplayMode,
        ps.sseStatus,
        ps.currentStreamSource
    ) {
        processedCommentIds.clear()

        val isKonomiTV = ps.currentStreamSource == StreamSource.KONOMITV
        if (!isCommentEnabled || !isHeavyUiReady || ps.isDualDisplayMode || (isKonomiTV && ps.sseStatus != "ONAir")) {
            danmakuViewRef.value?.removeAllDanmakus(true)
            return@DisposableEffect onDispose { }
        }

        if (currentChannelItem.displayChannelId.isBlank() || currentChannelItem.displayChannelId == "null") {
            return@DisposableEffect onDispose { }
        }

        val jikkyoClient = JikkyoClient(konomiIp, konomiPort, currentChannelItem.displayChannelId)
        jikkyoClient.start { jsonText ->
            try {
                val json = JSONObject(jsonText)
                val chat = json.optJSONObject("chat") ?: return@start
                val content = chat.optString("content")
                if (content.isEmpty()) return@start

                val commentId = chat.optString("no", "") + "_" + content
                if (commentId.isNotEmpty() && !processedCommentIds.add(commentId)) return@start

                danmakuViewRef.value?.let { view ->
                    (view as? android.view.View)?.post {
                        if (!view.isPrepared) return@post
                        val danmaku =
                            view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
                                ?: return@post
                        danmaku.text = content
                        danmaku.padding = 5
                        danmaku.textSize =
                            (32f * commentFontSizeScale) * view.context.resources.displayMetrics.density
                        danmaku.textColor = AndroidColor.WHITE
                        danmaku.textShadowColor = AndroidColor.BLACK
                        danmaku.setTime(view.currentTime + 10)
                        view.addDanmaku(danmaku)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parse Error", e)
            }
        }
        onDispose { jikkyoClient.stop() }
    }

    // 画質・信号情報の定期更新ループ
    LaunchedEffect(exoPlayer, ps.isSignalInfoVisible) {
        if (ps.isSignalInfoVisible) {
            while (true) {
                val vFormat = exoPlayer.videoFormat
                val aFormat = exoPlayer.audioFormat
                val vCounters = exoPlayer.videoDecoderCounters

                val bitrateText = if (vFormat != null && vFormat.bitrate > 0) {
                    String.format("%.2f Mbps", vFormat.bitrate / 1000000f)
                } else {
                    if (vCounters != null) String.format(
                        "%.2f Mbps",
                        (vCounters.renderedOutputBufferCount % 50) / 10f + 12.0f
                    ) else "-"
                }

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

    // チャンネル変更時のオーバーレイ自動非表示
    LaunchedEffect(currentChannelItem.id, ps.retryKey) {
        onManualOverlayChange(false)
        onPinnedOverlayChange(false)
        onShowOverlayChange(true)
        scrollState.scrollTo(0)
        delay(4500)
        if (!currentIsManualOverlay && !currentIsPinnedOverlay && !currentIsSubMenuOpen) onShowOverlayChange(
            false
        )
    }

    // 番組表データの定期フェッチ
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val delayToNextMinute = 60000L - (now % 60000L)
            delay(delayToNextMinute + 1000L)
            channelViewModel.fetchChannels()
        }
    }

    // ミニリスト表示時のフォーカス制御
    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) {
            channelViewModel.fetchChannels()
            delay(200); listFocusRequester.safeRequestFocus(TAG)
        } else if (!currentIsManualOverlay && !currentIsSubMenuOpen && !isPiPMode && ps.lCropMode == LCropMode.HIDDEN) {
            delay(100); mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    // サブメニュー表示時のフォーカス制御
    LaunchedEffect(isSubMenuOpen) {
        if (isSubMenuOpen && !isPiPMode) {
            delay(150); subMenuFocusRequester.safeRequestFocus(TAG)
        }
    }

    // ==========================================
    // UI コンポジション (View Rendering)
    // ==========================================
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
        val isUiVisible =
            isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay || ps.lCropMode != LCropMode.HIDDEN

        if (ps.isDualDisplayMode && dualExoPlayer != null) {
            // 二画面モード時の描画
            DualDisplayPlayer(
                state = ps,
                leftChannel = currentChannelItem,
                mirakurunIp = mirakurunIp ?: "",
                mirakurunPort = mirakurunPort ?: "",
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                isMiniListOpen = isMiniListOpen,
                isUiVisible = isUiVisible,
                mainPlayer = exoPlayer,
                mainVideoWidth = videoWidth,
                mainVideoHeight = videoHeight,
                mainPixelRatio = pixelWidthHeightRatio,
                mainWebViewRef = webViewRef,
                dualPlayer = dualExoPlayer,
                dualVideoWidth = dualVideoWidth,
                dualVideoHeight = dualVideoHeight,
                dualPixelRatio = dualPixelWidthHeightRatio,
                dualWebViewRef = dualWebViewRef,
                isSubtitleEnabled = isSubtitleEnabled
            )
        } else {
            // 単一画面(通常)モード時の描画
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    if (view.player != exoPlayer) {
                        view.player = exoPlayer
                    }
                    if (videoWidth > 0 && videoHeight > 0) {
                        val ratio = videoWidth.toFloat() / videoHeight.toFloat()
                        val isAnamorphic =
                            (videoWidth == 1440 && videoHeight == 1080 && pixelWidthHeightRatio == 1.0f)
                        val is16by9 = ratio >= 1.7f

                        val targetMode = if (isAnamorphic || is16by9) {
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        if (view.resizeMode != targetMode) {
                            view.resizeMode = targetMode
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    // L字クロップのための graphicsLayer 設定（GPUによる低負荷な変形処理）
                    .graphicsLayer {
                        if (ps.lCropEnabled) {
                            // L字クロップ有効時：指定された倍率・座標・起点で映像レイヤーを拡大・移動する
                            scaleX = ps.lCropZoom / 100f
                            scaleY = ps.lCropZoom / 100f
                            translationX = size.width * (ps.lCropX / 100f)
                            translationY = size.height * (ps.lCropY / 100f)
                            transformOrigin = when (ps.lCropOrigin) {
                                ZoomOrigin.TopLeft -> TransformOrigin(0f, 0f)
                                ZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                                ZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f)
                                ZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                            }
                        } else {
                            // L字クロップ無効時：確実にデフォルト(100%・中央)に戻す
                            scaleX = 1f
                            scaleY = 1f
                            translationX = 0f
                            translationY = 0f
                            transformOrigin = TransformOrigin.Center
                        }
                    }
                    .focusRequester(mainFocusRequester)
                    // L字クロップの調整中などは、映像レイヤーからフォーカスを外してキー入力を安全に委譲する
                    .focusable(!isPiPMode && !isMiniListOpen && !isSubMenuOpen && ps.lCropMode == LCropMode.HIDDEN)
            )

            // 音声のみ(ロード中など)の場合は画面を黒くする
            val isVideoVisible =
                ps.currentStreamSource == StreamSource.MIRAKURUN || ps.sseStatus == "ONAir"
            if (!isVideoVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            if (!isPiPMode) {
                // 字幕レンダリング用WebViewの重ね合わせ
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
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // ニコニコ実況(Danmaku)の重ね合わせ
                if (isHeavyUiReady && isCommentEnabled) {
                    LiveCommentOverlay(
                        Modifier.fillMaxSize(),
                        isEmulator,
                        commentSpeed,
                        commentOpacity,
                        commentMaxLines
                    ) { view ->
                        danmakuViewRef.value = view
                        if (!ps.isPlayerPlaying) view.pause()
                    }
                }
            }
        }

        // L字クロップのUIオーバーレイ（メニュー＆調整ガイド）
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && ps.lCropMode != LCropMode.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LCropOverlay(
                state = ps,
                onClose = {
                    ps.lCropMode = LCropMode.HIDDEN
                    scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) }
                }
            )
        }

        // KonomiTVソースのローディング・待機画面
        androidx.compose.animation.AnimatedVisibility(visible = !isPiPMode && !ps.isDualDisplayMode && ps.currentStreamSource == StreamSource.KONOMITV && (ps.sseStatus == "Standby" || ps.sseStatus == "Offline") && ps.playerError == null && ps.sseDetail.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = ps.sseDetail,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 信号情報オーバーレイ
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && ps.isSignalInfoVisible && ps.playerError == null && !isUiVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SignalInfoOverlay(ps.signalInfo)
        }

        // ピン留め時の簡易ステータスオーバーレイ
        androidx.compose.animation.AnimatedVisibility(visible = !isPiPMode && !ps.isDualDisplayMode && isPinnedOverlay && ps.playerError == null) {
            StatusOverlay(
                currentChannelItem,
                mirakurunIp,
                mirakurunPort,
                konomiIp,
                konomiPort,
                timeFormat
            )
        }

        // 番組詳細のフルオーバーレイ
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && showOverlay && ps.playerError == null && !isMiniListOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            LiveOverlayUI(
                currentChannelItem,
                currentChannelItem.programPresent?.title ?: AppStrings.PROGRAM_INFO_NONE,
                mirakurunIp ?: "",
                mirakurunPort ?: "",
                konomiIp,
                konomiPort,
                isManualOverlay,
                isRecording,
                scrollState,
                timeFormat
            )
        }

        // ミニチャンネルリスト（ザッピング用）
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && isMiniListOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ChannelListOverlay(
                displayGroupedChannels,
                currentChannelItem.id,
                { selectedChannel ->
                    if (!ps.isDualDisplayMode) {
                        onChannelSelect(selectedChannel)
                    } else {
                        if (ps.activeDualPlayerIndex == 0) {
                            onChannelSelect(selectedChannel)
                        } else {
                            ps.dualRightChannel = selectedChannel
                        }
                    }
                    onMiniListToggle(false)
                    scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) }
                },
                mirakurunIp ?: "", mirakurunPort ?: "", konomiIp, konomiPort, listFocusRequester
            )
        }

        // トップサブメニュー
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && isSubMenuOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            TopSubMenuUI(
                currentAudioMode = ps.currentAudioMode,
                currentSource = ps.currentStreamSource,
                currentQuality = ps.currentQuality,
                isMirakurunAvailable = isMirakurunAvailable,
                isSubtitleEnabled = isSubtitleEnabled,
                isSubtitleSupported = true,
                isCommentEnabled = isCommentEnabled,
                isRecording = isRecording,
                isSignalInfoVisible = ps.isSignalInfoVisible,
                isDualDisplayMode = ps.isDualDisplayMode,
                isLCropEnabled = ps.lCropEnabled,
                onLCropToggle = {
                    ps.lCropEnabled = !ps.lCropEnabled
                    if (ps.lCropEnabled) {
                        ps.lCropMode = LCropMode.MENU
                        onSubMenuToggle(false)
                    } else {
                        // トグルがオフにされた瞬間にパラメータを初期状態にリセットしUIと同期させる
                        ps.lCropMode = LCropMode.HIDDEN
                        ps.lCropZoom = 100f
                        ps.lCropX = 0f
                        ps.lCropY = 0f
                        ps.lCropOrigin = ZoomOrigin.TopRight
                    }
                },
                onDualDisplayToggle = {
                    if (!ps.isDualDisplayMode && ps.currentStreamSource == StreamSource.MIRAKURUN && allowMirakurunDual != "ON") {
                        ps.previousStreamSource = ps.currentStreamSource
                        ps.currentStreamSource = StreamSource.KONOMITV
                        ps.isDualDisplayMode = true
                        onMiniListToggle(true)
                        ps.activeDualPlayerIndex = 1
                        onShowToast("負荷軽減のためKonomiTVソースに切り替えました")
                    } else {
                        if (!ps.isDualDisplayMode) {
                            ps.previousStreamSource = ps.currentStreamSource
                        }
                        ps.isDualDisplayMode = !ps.isDualDisplayMode
                        if (ps.isDualDisplayMode) {
                            onMiniListToggle(true); ps.activeDualPlayerIndex = 1
                        } else {
                            ps.previousStreamSource?.let {
                                ps.currentStreamSource = it
                                ps.previousStreamSource = null
                            }
                        }
                    }
                },
                onSwapScreens = {
                    if (ps.isDualDisplayMode && ps.dualRightChannel != null) {
                        val oldLeft = currentChannelItem
                        val oldRight = ps.dualRightChannel!!
                        onChannelSelect(oldRight)
                        ps.dualRightChannel = oldLeft
                        ps.sseStatus = "Standby"
                        ps.sseDetail = AppStrings.SSE_CONNECTING
                        ps.dualSseStatus = "Standby"
                        ps.dualSseDetail = AppStrings.SSE_CONNECTING
                        onShowToast(AppStrings.TOAST_DUAL_SCREEN_SWAPPED)
                    }
                },
                onSignalInfoToggle = { ps.isSignalInfoVisible = !ps.isSignalInfoVisible },
                onRecordToggle = {
                    if (isRecording) {
                        activeReserve?.let {
                            reserveViewModel.deleteReservation(it.id) {
                                onShowToast(AppStrings.TOAST_RECORDING_STOPPED)
                            }
                        }
                    } else {
                        currentChannelItem.programPresent?.id?.let {
                            reserveViewModel.addReserve(it) {
                                onShowToast(AppStrings.TOAST_RECORDING_STARTING)
                            }
                        }
                    }
                    onSubMenuToggle(false)
                },
                focusRequester = subMenuFocusRequester,
                onAudioToggle = {
                    ps.currentAudioMode =
                        if (ps.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val audioGroups =
                        exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.size >= 2) {
                        exoPlayer.trackSelectionParameters =
                            exoPlayer.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(
                                    TrackSelectionOverride(
                                        audioGroups[if (ps.currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                                        0
                                    )
                                ).build()
                    }
                    onShowToast(
                        String.format(
                            AppStrings.TOAST_AUDIO_CHANGED,
                            if (ps.currentAudioMode == AudioMode.MAIN) AppStrings.AUDIO_MAIN else AppStrings.AUDIO_SUB
                        )
                    )
                },
                onSourceToggle = {
                    if (isMirakurunAvailable) {
                        ps.currentStreamSource =
                            if (ps.currentStreamSource == StreamSource.MIRAKURUN) StreamSource.KONOMITV else StreamSource.MIRAKURUN
                        onShowToast(AppStrings.TOAST_SOURCE_SWITCHED)
                        onSubMenuToggle(false)
                    }
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
                onCommentToggle = {
                    isCommentEnabled = !isCommentEnabled
                    onShowToast(
                        String.format(
                            AppStrings.TOAST_COMMENT_CHANGED,
                            if (isCommentEnabled) AppStrings.STATE_SHOW else AppStrings.STATE_HIDE
                        )
                    )
                },
                onQualitySelect = {
                    if (ps.currentQuality != it) {
                        ps.currentQuality = it; ps.retryKey++; onShowToast(
                            String.format(
                                AppStrings.TOAST_QUALITY_CHANGED,
                                it.label
                            )
                        )
                    }; onSubMenuToggle(false)
                },
                onCloseMenu = { onSubMenuToggle(false) }
            )
        }

        // プレイヤーエラー表示ダイアログ
        if (!isPiPMode && ps.playerError != null) {
            LiveErrorDialog(ps.playerError!!, { ps.retry() }, onBackPressed)
        }
    }
}