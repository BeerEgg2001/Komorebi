@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import android.util.Base64
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

private const val TAG_SUBTITLE = "SubtitleDebug"
// ★ラグ補正値（ミリ秒）。字幕が遅れる場合はこの値を大きくします（例: -200など）
// デフォルトで少し早め（-100ms）に送信することで、デコード時間を稼ぎます。
private const val SUBTITLE_LATENCY_OFFSET_MS = -100L

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, VIDEO, SUBTITLE }
enum class StreamSource { MIRAKURUN, KONOMITV }

@ExperimentalComposeUiApi
@OptIn(ExperimentalTvMaterial3Api::class)
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

    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2000)
            toastMessage = null
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
        DefaultExtractorsFactory().apply {
            setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        }
    }

    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
        }
    }

    val exoPlayer = remember(currentStreamSource, retryKey) {
        playerError = null
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(audioProcessor)).build()
            }
        }
        val builder = ExoPlayer.Builder(context, renderersFactory)
        if (currentStreamSource == StreamSource.MIRAKURUN) {
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(tsDataSourceFactory, extractorsFactory))
        }

        val player = builder.build()
        player.apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val cause = error.cause
                    playerError = if (cause is HttpDataSource.InvalidResponseCodeException) {
                        when (cause.responseCode) {
                            404 -> AppStrings.ERR_CHANNEL_NOT_FOUND
                            503 -> AppStrings.ERR_TUNER_FULL
                            else -> "サーバーエラー (HTTP ${cause.responseCode})"
                        }
                    } else if (cause is HttpDataSource.HttpDataSourceException) {
                        if (cause.cause is java.net.ConnectException) AppStrings.ERR_CONNECTION_REFUSED
                        else if (cause.cause is java.net.SocketTimeoutException) AppStrings.ERR_TIMEOUT
                        else AppStrings.ERR_NETWORK
                    } else if (cause is IOException) {
                        "データ読み込みエラー: ${cause.message}"
                    } else {
                        "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})"
                    }
                }
                override fun onMetadata(metadata: Metadata) {
                    if (!subtitleEnabledState.value) return
                    for (i in 0 until metadata.length()) {
                        val entry = metadata.get(i)
                        if (entry is PrivFrame) {
                            if (entry.owner.equals("aribb24.js", ignoreCase = true) ||
                                entry.owner.equals("ARIB_STD_B24", ignoreCase = true)) {
                                val base64Data = Base64.encodeToString(entry.privateData, Base64.NO_WRAP)

                                // ★改善：PTSに補正値を適用して同期精度を高める
                                val ptsMs = player.currentPosition + SUBTITLE_LATENCY_OFFSET_MS

                                webViewRef.value?.post {
                                    webViewRef.value?.evaluateJavascript("if(window.receiveSubtitleData){ window.receiveSubtitleData($ptsMs, '$base64Data'); }", null)
                                }
                            }
                        }
                    }
                }
            })
        }
        player
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { exoPlayer.stop() }
            else if (event == Lifecycle.Event.ON_START) { exoPlayer.prepare(); exoPlayer.play() }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) }
    }

    fun applyAudioStream(mode: AudioMode) {
        val tracks = exoPlayer.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isNotEmpty()) {
            val targetIndex = if (mode == AudioMode.SUB && audioGroups.size >= 2) 1 else 0
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(TrackSelectionOverride(audioGroups[targetIndex].mediaTrackGroup, 0))
                .build()
        }
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

    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    LaunchedEffect(isMiniListOpen) {
        if (isMiniListOpen) { delay(100); listFocusRequester.requestFocus() }
        else { mainFocusRequester.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        val isEnter = keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER || keyCode == NativeKeyEvent.KEYCODE_ENTER
        val isBack = keyCode == NativeKeyEvent.KEYCODE_BACK
        val isUp = keyCode == NativeKeyEvent.KEYCODE_DPAD_UP
        val isDown = keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN

        if (playerError != null) return@onKeyEvent false

        if (isSubMenuOpen) {
            if (isBack) { isSubMenuOpen = false; mainFocusRequester.requestFocus(); return@onKeyEvent true }
            return@onKeyEvent false
        }

        if (isBack) {
            if (showOverlay || isPinnedOverlay) {
                showOverlay = false; isPinnedOverlay = false; isManualOverlay = false
            } else if (isMiniListOpen) {
                onMiniListToggle(false)
                mainFocusRequester.requestFocus()
            } else {
                onBackPressed()
            }
            return@onKeyEvent true
        }

        if (isEnter) {
            when {
                showOverlay -> { showOverlay = false; isManualOverlay = false; isPinnedOverlay = true }
                isPinnedOverlay -> { isPinnedOverlay = false }
                else -> { showOverlay = true; isManualOverlay = true; isPinnedOverlay = false }
            }
            return@onKeyEvent true
        }

        if (isUp && !showOverlay && !isPinnedOverlay && !isMiniListOpen) { isSubMenuOpen = true; return@onKeyEvent true }
        if (isDown && !showOverlay && !isPinnedOverlay && !isMiniListOpen) { onMiniListToggle(true); return@onKeyEvent true }
        false
    }) {

        // --- レイヤー1: 映像 (最背面) ---
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true } },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize().focusRequester(mainFocusRequester).focusable()
        )

        // --- レイヤー2: UI要素 ---
        AnimatedVisibility(visible = isPinnedOverlay && playerError == null, enter = fadeIn(), exit = fadeOut()) {
            StatusOverlay(currentChannelItem, mirakurunIp, mirakurunPort, konomiIp, konomiPort)
        }

        AnimatedVisibility(visible = showOverlay && playerError == null && !isMiniListOpen, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            LiveOverlayUI(currentChannelItem, currentChannelItem.programPresent?.title ?: "番組情報なし", mirakurunIp?:"", mirakurunPort?:"", konomiIp, konomiPort, isManualOverlay, scrollState)
        }

        AnimatedVisibility(
            visible = isMiniListOpen && playerError == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
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
                    applyAudioStream(currentAudioMode)
                    toastMessage = "音声: ${if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}"
                },
                onSourceToggle = {
                    if (isMirakurunAvailable) {
                        currentStreamSource = if(currentStreamSource == StreamSource.MIRAKURUN) StreamSource.KONOMITV else StreamSource.MIRAKURUN
                        toastMessage = "ソース: ${if(currentStreamSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV"}"
                    }
                },
                onSubtitleToggle = {
                    subtitleEnabledState.value = !subtitleEnabledState.value
                    toastMessage = "字幕: ${if(subtitleEnabledState.value) "表示" else "非表示"}"
                }
            )
        }

        // --- レイヤー3: 字幕 (映像の上、UIより背面) ---
        val isUiVisible = isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(0)
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean { return true }
                    }
                    loadUrl("file:///android_asset/subtitle_renderer.html")
                    webViewRef.value = this
                }
            },
            modifier = Modifier.fillMaxSize().alpha(if (isSubtitleEnabled && !isUiVisible) 1f else 0f)
        )

        // --- レイヤー4: トースト通知 ---
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(visible = toastMessage != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(modifier = Modifier.background(Color.Black.copy(0.85f), RoundedCornerShape(32.dp)).border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(32.dp)).padding(horizontal = 28.dp, vertical = 14.dp)) {
                    Text(text = toastMessage ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }

        if (playerError != null) {
            LiveErrorDialog(errorMessage = playerError!!, onRetry = { playerError = null; retryKey++ }, onBack = onBackPressed)
        }
    }
}

@ExperimentalComposeUiApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSource: StreamSource,
    isMirakurunAvailable: Boolean,
    isSubtitleEnabled: Boolean,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSourceToggle: () -> Unit,
    onSubtitleToggle: () -> Unit
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent))).padding(top = 24.dp, bottom = 60.dp), contentAlignment = Alignment.TopCenter) {
        Row(horizontalArrangement = Arrangement.Center) {
            MenuTileItem(
                title = "音声切替", icon = Icons.Default.PlayArrow,
                subtitle = if(currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                onClick = onAudioToggle,
                modifier = Modifier.focusRequester(focusRequester).focusProperties { left = FocusRequester.Cancel }
            )
            Spacer(Modifier.width(20.dp))
            MenuTileItem(
                title = "映像ソース", icon = Icons.Default.Build,
                subtitle = if(currentSource == StreamSource.MIRAKURUN) "Mirakurun" else "KonomiTV",
                onClick = onSourceToggle,
                enabled = isMirakurunAvailable
            )
            Spacer(Modifier.width(20.dp))
            MenuTileItem(
                title = "字幕設定", icon = Icons.Default.ClosedCaption,
                subtitle = if(isSubtitleEnabled) "表示" else "非表示",
                onClick = onSubtitleToggle,
                modifier = Modifier.focusProperties { right = FocusRequester.Cancel }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MenuTileItem(
    title: String, icon: ImageVector, subtitle: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            contentColor = if (enabled) Color.White else Color.White.copy(0.3f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(180.dp, 100.dp).alpha(if(enabled) 1f else 0.5f)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = LocalContentColor.current.copy(0.7f))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveErrorDialog(errorMessage: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val retryButtonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryButtonFocusRequester.requestFocus() }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)), modifier = Modifier.width(450.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(48.dp).padding(bottom = 16.dp))
                Text(text = AppStrings.LIVE_PLAYER_ERROR_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp)); Text(text = errorMessage, style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(32.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onBack, colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White), modifier = Modifier.weight(1f)) { Text(AppStrings.BUTTON_BACK) }
                Button(onClick = onRetry, colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.weight(1f).focusRequester(retryButtonFocusRequester)) { Text(AppStrings.BUTTON_RETRY) }
            }
            }
        }
    }
}

@Composable
fun StatusOverlay(channel: Channel, mirakurunIp: String?, mirakurunPort: String?, konomiIp: String, konomiPort: String) {
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while(true) { currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) } }
    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    val logoUrl = if (isMirakurunAvailable) UrlBuilder.getMirakurunLogoUrl(mirakurunIp ?: "", mirakurunPort ?: "", channel.networkId, channel.serviceId, channel.type) else UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
        Row(modifier = Modifier.background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(2.dp)).background(Color.White), contentScale = if (isMirakurunAvailable) ContentScale.Fit else ContentScale.Crop)
            Spacer(Modifier.width(16.dp)); Text("${formatChannelType(channel.type)}${channel.channelNumber}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp)); Text(currentTime, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp), color = Color.White)
        }
    }
}

@Composable
fun LiveOverlayUI(channel: Channel, programTitle: String, mirakurunIp: String, mirakurunPort: String, konomiIp: String, konomiPort: String, showDesc: Boolean, scrollState: ScrollState) {
    val program = channel.programPresent
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var progress by remember { mutableFloatStateOf(-1f) }
    val isMirakurunAvailable = mirakurunIp.isNotBlank() && mirakurunPort.isNotBlank()
    val logoUrl = if (isMirakurunAvailable) UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.networkId, channel.serviceId, channel.type) else UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
    LaunchedEffect(program) { if (program != null && !program.startTime.isNullOrEmpty() && !program.endTime.isNullOrEmpty()) { val startMs = sdf.parse(program.startTime)?.time ?: 0L; val endMs = sdf.parse(program.endTime)?.time ?: 0L; val total = endMs - startMs; if (total > 0) { while (System.currentTimeMillis() < endMs) { progress = ((System.currentTimeMillis() - startMs).toFloat() / total).coerceIn(0f, 1f); delay(5000) } } } }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f)))).padding(horizontal = 64.dp, vertical = 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(4.dp)).background(Color.White), contentScale = if (isMirakurunAvailable) ContentScale.Fit else ContentScale.Crop); Spacer(Modifier.width(24.dp)); Text("${formatChannelType(channel.type)}${channel.channelNumber}  ${channel.name}", style = MaterialTheme.typography.titleLarge, color = Color.White.copy(0.8f)) }
            Text(programTitle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp), color = Color.White, modifier = Modifier.padding(vertical = 16.dp))
            if (showDesc && program != null) { Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(vertical = 8.dp)) { Column(modifier = Modifier.verticalScroll(scrollState)) { if (!program.description.isNullOrEmpty()) Text(program.description, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(0.9f), modifier = Modifier.padding(bottom = 20.dp)); program.detail?.forEach { (k, v) -> Column(Modifier.padding(bottom = 14.dp)) { Text("◆ $k", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(0.5f))); Text(v, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.85f), modifier = Modifier.padding(start = 12.dp, top = 4.dp)) } } } } }
            if (progress >= 0f) { val start = program?.startTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""; val end = program?.endTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""; Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(start, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.5f)); Box(modifier = Modifier.weight(1f).padding(horizontal = 20.dp).height(4.dp).background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))) { Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White, RoundedCornerShape(2.dp))) }; Text(end, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.5f)) } }
        }
    }
}

fun formatChannelType(type: String): String = when (type.uppercase()) { "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; else -> type }