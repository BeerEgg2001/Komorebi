@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import android.os.Build
import android.util.Base64
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
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.TsExtractor
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
import com.beeregg2001.komorebi.data.model.StreamQuality
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

@RequiresApi(Build.VERSION_CODES.O)
@ExperimentalComposeUiApi
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

    // ★State Holderの導入
    val ps = rememberLivePlayerState(context, initialQuality)

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()

    // 設定値の取得
    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    var isCommentEnabled by rememberSaveable(commentDefaultDisplayStr) {
        mutableStateOf(commentDefaultDisplayStr == "ON")
    }

    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }

    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }
    val currentChannelItem by remember(channel.id, groupedChannels) {
        derivedStateOf { flatChannels.find { it.id == channel.id } ?: channel }
    }

    // 番組IDの特定
    val currentProgramId by remember(currentChannelItem.programPresent, epgViewModel.uiState) {
        derivedStateOf {
            currentChannelItem.programPresent?.id ?: run {
                val state = epgViewModel.uiState
                if (state is EpgUiState.Success) {
                    val now = OffsetDateTime.now()
                    state.data.find { it.channel.id == currentChannelItem.id }?.programs?.find { prog ->
                        val start = try {
                            OffsetDateTime.parse(prog.start_time)
                        } catch (e: Exception) {
                            null
                        }
                        val end = try {
                            OffsetDateTime.parse(prog.end_time)
                        } catch (e: Exception) {
                            null
                        }
                        start != null && end != null && now.isAfter(start) && now.isBefore(end)
                    }?.id
                } else null
            }
        }
    }

    val reserves by reserveViewModel.reserves.collectAsState()
    val activeReserve =
        remember(reserves, currentProgramId) { reserves.find { it.program.id == currentProgramId } }
    val isRecording = activeReserve != null

    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
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

    // 初期ソースの決定
    LaunchedEffect(isMirakurunAvailable, preferredStreamSource) {
        ps.currentStreamSource =
            if (isMirakurunAvailable && preferredStreamSource == "MIRAKURUN") StreamSource.MIRAKURUN else StreamSource.KONOMITV
    }

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    val updatedIsManualOverlay by rememberUpdatedState(isManualOverlay)
    val updatedIsPinnedOverlay by rememberUpdatedState(isPinnedOverlay)
    val updatedIsSubMenuOpen by rememberUpdatedState(isSubMenuOpen)

    val tsDataSourceFactory = remember { TsReadExDataSourceFactory(nativeLib, arrayOf()) }
    val extractorsFactory = remember {
        ExtractorsFactory {
            arrayOf(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    TimestampAdjuster(0),
                    DirectSubtitlePayloadReaderFactory(webViewRef, subtitleEnabledState)
                )
            )
        }
    }

    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
        }
    }

    // Playerの初期化
    val exoPlayer = remember(ps.currentStreamSource, ps.retryKey, ps.currentQuality) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: android.content.Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor))
                    .build()
            }
        }
        ExoPlayer.Builder(context, renderersFactory).apply {
            if (ps.currentStreamSource == StreamSource.MIRAKURUN) {
                setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        tsDataSourceFactory,
                        extractorsFactory
                    )
                )
            }
        }.build().apply {
            playWhenReady = true
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
                            val ptsMs =
                                currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS
                            webViewRef.value?.post {
                                webViewRef.value?.evaluateJavascript(
                                    "if(window.receiveSubtitleData){ window.receiveSubtitleData($ptsMs, '$base64Data'); }",
                                    null
                                )
                            }
                        }
                    }
                }
            })
        }
    }

    // ライフサイクル管理
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.stop()
            else if (event == Lifecycle.Event.ON_START) {
                exoPlayer.prepare(); exoPlayer.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release() }
    }

    // 実況(Danmaku)の制御
    LaunchedEffect(ps.isPlayerPlaying) {
        danmakuViewRef.value?.let {
            if (it.isPrepared) {
                if (ps.isPlayerPlaying) it.resume() else it.pause()
            }
        }
    }

    // KonomiTV SSE イベント
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
                    val json = JSONObject(data)
                    ps.sseStatus = json.optString("status", "Unknown")
                    ps.sseDetail = json.optString("detail", "読み込み中...")
                    if (ps.sseStatus == "Error" || (ps.sseStatus == "Offline" && (ps.sseDetail.contains(
                            "失敗"
                        ) || ps.sseDetail.contains("エラー")))
                    ) {
                        ps.playerError = ps.sseDetail.ifEmpty { "チューナーの起動に失敗しました" }
                        exoPlayer.stop(); return@runCatching
                    }
                    when (ps.sseStatus) {
                        "Standby" -> if (exoPlayer.isPlaying) exoPlayer.pause()
                        "ONAir" -> if (!exoPlayer.isPlaying && ps.playerError == null) exoPlayer.play()
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        onDispose { eventSource.cancel(); client.dispatcher.executorService.shutdown() }
    }

    // 字幕クロック同期
    LaunchedEffect(exoPlayer, isSubtitleEnabled) {
        while (true) {
            if (isSubtitleEnabled && exoPlayer.isPlaying) {
                val currentPos = exoPlayer.currentPosition
                webViewRef.value?.post {
                    webViewRef.value?.evaluateJavascript(
                        "if(window.syncClock){ window.syncClock($currentPos); }",
                        null
                    )
                }
            }
            delay(100)
        }
    }

    // オーバーレイ自動非表示
    LaunchedEffect(currentChannelItem.id, ps.retryKey) {
        onManualOverlayChange(false); onPinnedOverlayChange(false); onShowOverlayChange(true)
        scrollState.scrollTo(0); delay(4500)
        if (!updatedIsManualOverlay && !updatedIsPinnedOverlay && !updatedIsSubMenuOpen) onShowOverlayChange(
            false
        )
    }

    // ストリームURL設定
    LaunchedEffect(currentChannelItem.id, ps.currentStreamSource, ps.retryKey, ps.currentQuality) {
        ps.sseStatus = "Standby"; ps.sseDetail = AppStrings.SSE_CONNECTING
        val streamUrl =
            if (ps.currentStreamSource == StreamSource.MIRAKURUN && isMirakurunAvailable) {
                tsDataSourceFactory.tsArgs = arrayOf(
                    "-x",
                    "18/38/39",
                    "-n",
                    currentChannelItem.serviceId.toString(),
                    "-a",
                    "13",
                    "-b",
                    "5",
                    "-c",
                    "5",
                    "-u",
                    "1",
                    "-d",
                    "13"
                )
                UrlBuilder.getMirakurunStreamUrl(
                    mirakurunIp ?: "",
                    mirakurunPort ?: "",
                    currentChannelItem.networkId,
                    currentChannelItem.serviceId
                )
            } else {
                UrlBuilder.getKonomiTvLiveStreamUrl(
                    konomiIp,
                    konomiPort,
                    currentChannelItem.displayChannelId,
                    ps.currentQuality.value
                )
            }
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

    // コメント取得(Jikkyo)
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
                if (processedCommentIds.size > 200) processedCommentIds.remove(
                    processedCommentIds.iterator().next()
                )
                danmakuViewRef.value?.let { view ->
                    (view as? android.view.View)?.post {
                        if (!view.isPrepared) return@post
                        val danmaku =
                            view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
                                ?: return@post
                        danmaku.text = content; danmaku.padding = 5
                        val density = view.context.resources.displayMetrics.density
                        danmaku.textSize = (32f * commentFontSizeScale) * density
                        danmaku.textColor = AndroidColor.WHITE; danmaku.textShadowColor =
                        AndroidColor.BLACK
                        danmaku.setTime(view.currentTime + 10); view.addDanmaku(danmaku)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Parse Error", e)
            }
        }
        onDispose { jikkyoClient.stop() }
    }

    // フォーカス制御
    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) {
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

    Box(modifier = Modifier
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
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        if (showOverlay) {
                            onShowOverlayChange(false); onManualOverlayChange(false); onPinnedOverlayChange(
                                true
                            )
                        } else if (isPinnedOverlay) onPinnedOverlayChange(false)
                        else {
                            onShowOverlayChange(true); onManualOverlayChange(true); onPinnedOverlayChange(
                                false
                            )
                        }
                        return@onKeyEvent true
                    }
                }

                NativeKeyEvent.KEYCODE_DPAD_UP -> {
                    if (showOverlay && isManualOverlay) {
                        scope.launch { scrollState.animateScrollTo(scrollState.value - 200) }; return@onKeyEvent true
                    }
                    if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                        onSubMenuToggle(true); return@onKeyEvent true
                    }
                }

                NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (showOverlay && isManualOverlay) {
                        scope.launch { scrollState.animateScrollTo(scrollState.value + 200) }; return@onKeyEvent true
                    }
                    if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
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

        val isUiVisible = isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay
        val commentLayer = @Composable {
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
        val subtitleLayer = @Composable {
            if (isHeavyUiReady) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                -1,
                                -1
                            ); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                            loadUrl("file:///android_asset/subtitle_renderer.html"); webViewRef.value =
                            this
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

        // 各種オーバーレイの表示 (SSE接続待ち、情報オーバーレイ、ミニリスト、サブメニュー)
        AnimatedVisibility(visible = ps.currentStreamSource == StreamSource.KONOMITV && (ps.sseStatus == "Standby" || ps.sseStatus == "Offline") && ps.playerError == null) {
            Box(Modifier
                .fillMaxSize()
                .background(colors.background)) {
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
                {
                    if (isRecording) activeReserve?.let {
                        reserveViewModel.deleteReservation(it.id) {
                            onShowToast(
                                "録画を停止しました"
                            )
                        }
                    }
                    else currentProgramId?.let { reserveViewModel.addReserve(it) { onShowToast("録画を開始します") } }
                        ?: onShowToast("番組情報不明")
                    onSubMenuToggle(false)
                },
                subMenuFocusRequester,
                {
                    ps.currentAudioMode =
                        if (ps.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val audioGroups =
                        exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.size >= 2) exoPlayer.trackSelectionParameters =
                        exoPlayer.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(
                            TrackSelectionOverride(
                                audioGroups[if (ps.currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                                0
                            )
                        ).build()
                    onShowToast("音声: ${if (ps.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
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