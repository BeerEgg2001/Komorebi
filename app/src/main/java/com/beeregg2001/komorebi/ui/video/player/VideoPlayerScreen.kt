@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "VideoPlayerScreen"

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    smbItem: SmbItem? = null,
    initialPositionMs: Long = 0,
    initialQuality: String = "1080p-60fps",
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
    val scope = rememberCoroutineScope()

    var currentProgram by remember { mutableStateOf(program) }
    val fetchedDetail by videoPlayerViewModel.programDetail.collectAsState()

    val tiledThumbnailUrl by videoPlayerViewModel.tiledThumbnailUrl.collectAsState()
    val chapters by videoPlayerViewModel.chapters.collectAsState()
    val isLiveStream by videoPlayerViewModel.isLiveStream.collectAsState()

    val availableQualities by videoPlayerViewModel.availableQualities.collectAsState()
    val isQualitiesLoaded by videoPlayerViewModel.isQualitiesLoaded.collectAsState()
    val currentVideoQualityStr by settingsViewModel.videoQuality.collectAsState()

    val playerUiMode by settingsViewModel.playerUiMode.collectAsState()
    val isModern = playerUiMode == "MODERN"
    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(program.id) {
        if (smbItem == null) {
            videoPlayerViewModel.fetchProgramDetail(program.id)
            videoPlayerViewModel.fetchAvailableQualities()
        }
    }

    LaunchedEffect(fetchedDetail) {
        if (fetchedDetail != null && fetchedDetail?.id == program.id) {
            currentProgram = fetchedDetail!!
        }
    }

    val vs = rememberVideoPlayerState()

    val autoCmSkipStr by settingsViewModel.autoCmSkip.collectAsState()
    LaunchedEffect(autoCmSkipStr) {
        vs.isAutoCmSkipEnabled = (autoCmSkipStr == "ON")
    }

    LaunchedEffect(availableQualities, isQualitiesLoaded, currentVideoQualityStr) {
        if (isQualitiesLoaded && availableQualities.isNotEmpty()) {
            val matched = availableQualities.find { it.value == currentVideoQualityStr }
            if (matched != null) {
                vs.currentQuality = matched
            } else {
                val fallback = availableQualities.first()
                vs.currentQuality = fallback
                videoPlayerViewModel.saveVideoQuality(fallback.value)
            }
        }
    }

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()
    val videoSubtitleDefaultStr by settingsViewModel.videoSubtitleDefault.collectAsState()

    // ★ 追加: Cloudflare Zero Trust サービストークン (未設定なら空Map)
    val cfAccessClientId by settingsViewModel.cfAccessClientId.collectAsState()
    val cfAccessClientSecret by settingsViewModel.cfAccessClientSecret.collectAsState()
    val cfAccessHeaders = remember(cfAccessClientId, cfAccessClientSecret) {
        SettingsRepository.buildCfAccessHeaders(cfAccessClientId, cfAccessClientSecret)
    }

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
    val playerControlsFocusRequester = remember { FocusRequester() }

    var isProgramInfoOpen by remember { mutableStateOf(false) }
    var isModernSettingsOpen by remember { mutableStateOf(false) }

    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableStateOf(1f) }

    var isChapterListOpen by remember { mutableStateOf(false) }
    var isSeekingPreviewVisible by remember { mutableStateOf(false) }
    var seekingPreviewJob by remember { mutableStateOf<Job?>(null) }

    val isSubOverlayOpen =
        isSubMenuOpen || isSceneSearchOpen || isChapterListOpen || isProgramInfoOpen || isModernSettingsOpen

    val triggerSeekingPreview: () -> Unit = {
        isSeekingPreviewVisible = true
        seekingPreviewJob?.cancel()
        seekingPreviewJob = scope.launch { delay(2000); isSeekingPreviewVisible = false }
    }

    LaunchedEffect(program.recordedVideo.id) {
        if (smbItem == null) {
            allComments.clear()
            allComments.addAll(videoPlayerViewModel.getArchivedComments(program.recordedVideo.id))
        }
    }

    var smbDurationMs by remember { mutableLongStateOf(0L) }
    val isBackground = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = rememberManagedExoPlayer(
        program = program,
        vs = vs,
        scope = scope,
        webViewRef = webViewRef,
        onVideoSizeChanged = { w, h, ratio ->
            videoWidth = w
            videoHeight = h
            pixelWidthHeightRatio = ratio
        },
        onBufferingChanged = { isBuffering = it },
        onDurationChanged = { smbDurationMs = it },
        onStopOrDispose = { player ->
            if (smbItem == null) {
                val posMs =
                    if (isLiveStream) vs.playbackOffsetMs + player.currentPosition else player.currentPosition
                videoPlayerViewModel.updateWatchHistory(program, posMs / 1000.0)
            }
        },
        cfAccessHeaders = cfAccessHeaders,
        onFatalError = { message ->
            onShowToast(message)
            onBackPressed()
        }
    )

    val getCurrentPositionMs: () -> Long = remember(vs, exoPlayer) {
        { if (isLiveStream) vs.playbackOffsetMs + exoPlayer.currentPosition else exoPlayer.currentPosition }
    }

    val backendType by settingsViewModel.backendType.collectAsState()
    val edcbPlayMethod by settingsViewModel.edcbRecordPlayMethod.collectAsState()
    val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")

    val getEffectivePositionMs = { vs.pendingSeekPositionMs ?: getCurrentPositionMs() }

    val totalDurationForControls =
        if (smbItem != null) smbDurationMs.coerceAtLeast(0L) else (currentProgram.recordedVideo.duration * 1000).toLong()

    val performSeek: (Long) -> Unit = { targetMs: Long ->
        val safeTarget = targetMs.coerceIn(
            0L,
            if (totalDurationForControls > 0) totalDurationForControls else Long.MAX_VALUE
        )
        // 一瞬だけpendingSeekに記録してUI表示をサクサク進める
        vs.pendingSeekPositionMs = safeTarget
        scope.launch {
            delay(800)
            if (vs.pendingSeekPositionMs == safeTarget) {
                vs.pendingSeekPositionMs = null
            }
        }

        if (isLiveStream && smbItem == null) {
            scope.launch {
                isBuffering = true; exoPlayer.pause()
                vs.playbackOffsetMs = safeTarget
                val newOffsetSec = safeTarget / 1000.0
                val newUrl = videoPlayerViewModel.resolveStreamUrl(
                    currentProgram.id,
                    vs.currentQuality.value,
                    currentSessionId,
                    newOffsetSec
                )
                if (newUrl.isNotEmpty()) {
                    val mediaItemBuilder = MediaItem.Builder().setUri(newUrl)
                    if (newUrl.contains("/api/streams/") || newUrl.contains("/api/videos/") || newUrl.contains(
                            "konomi.tv"
                        ) || newUrl.contains("m3u8")
                    ) {
                        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                    exoPlayer.setMediaItem(mediaItemBuilder.build())
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } else {
                    if (fetchedDetail != null) onShowToast("シーク先ストリームの取得に失敗しました")
                }
            }
        } else {
            exoPlayer.seekTo(safeTarget)
        }
        Unit
    }

    val skipToNextChapter = {
        val basePos = getEffectivePositionMs()
        val nextChapter = chapters.find { it.startTimeMs > basePos + 3000 }
        if (nextChapter != null) {
            performSeek(nextChapter.startTimeMs)
        } else {
            onShowToast("次のチャプターはありません")
        }
    }

    val skipToPreviousChapter = {
        val basePos = getEffectivePositionMs()
        val reversedChapters = chapters.sortedByDescending { it.startTimeMs }
        val prevChapter = reversedChapters.find { it.startTimeMs < basePos - 5000 }
        if (prevChapter != null) {
            performSeek(prevChapter.startTimeMs)
        } else {
            performSeek(0L)
        }
    }

    LaunchedEffect(vs.isAutoCmSkipEnabled, chapters) {
        var hasWarnedEmptyChapters = false
        while (isActive) {
            if (vs.isAutoCmSkipEnabled && exoPlayer.isPlaying) {
                if (chapters.isNotEmpty()) {
                    val currentPos = getCurrentPositionMs()
                    val cmChapter =
                        chapters.find { it.isCm && currentPos >= it.startTimeMs && currentPos < (it.endTimeMs - 1500) }
                    if (cmChapter != null) {
                        performSeek(cmChapter.endTimeMs)
                        onShowToast("自動CMスキップ: 本編へ移動しました")
                        delay(3000)
                    }
                } else {
                    if (!hasWarnedEmptyChapters) {
                        hasWarnedEmptyChapters = true
                    }
                }
            } else {
                hasWarnedEmptyChapters = false
            }
            delay(500)
        }
    }

    var isFirstLoad by remember { mutableStateOf(true) }

    LaunchedEffect(currentProgram.id, smbItem, vs.currentQuality, availableQualities) {
        if (smbItem != null) {
            isBuffering = true
            vs.playbackOffsetMs = 0L
            val mediaItem = MediaItem.fromUri(smbItem.path)
            exoPlayer.setMediaItem(mediaItem)
            if (isFirstLoad && initialPositionMs > 0) {
                exoPlayer.seekTo(initialPositionMs)
            }
            isFirstLoad = false
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            return@LaunchedEffect
        }

        if (currentProgram.id == 0 || !isQualitiesLoaded || vs.currentQuality.value.isBlank()) return@LaunchedEffect
        if (availableQualities.isNotEmpty() && availableQualities.none { it.value == vs.currentQuality.value }) return@LaunchedEffect

        isBuffering = true
        val offsetSec = if (isFirstLoad && initialPositionMs > 0) {
            vs.playbackOffsetMs = initialPositionMs; initialPositionMs / 1000.0
        } else {
            val currentPos = getCurrentPositionMs()
            vs.playbackOffsetMs = currentPos; currentPos / 1000.0
        }

        val url = videoPlayerViewModel.resolveStreamUrl(
            currentProgram.id,
            vs.currentQuality.value,
            currentSessionId,
            offsetSec
        )

        if (url.isNotEmpty()) {
            val mediaItemBuilder = MediaItem.Builder().setUri(url)
            if (url.contains("/api/streams/") || url.contains("/api/videos/") || url.contains("konomi.tv") || url.contains(
                    "m3u8"
                )
            ) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            val mediaItem = mediaItemBuilder.build()
            exoPlayer.setMediaItem(mediaItem)
            if (isFirstLoad && initialPositionMs > 0 && !isLiveStream) {
                exoPlayer.seekTo(initialPositionMs)
            }
            isFirstLoad = false
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            if (fetchedDetail != null) onShowToast("ストリームURLの取得に失敗しました")
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

    DisposableEffect(vs.currentQuality, currentSessionId, smbItem) {
        if (smbItem == null) {
            videoPlayerViewModel.startStreamMaintenance(
                program,
                vs.currentQuality.value,
                currentSessionId
            ) { getCurrentPositionMs() / 1000.0 }
        }
        onDispose { if (smbItem == null) videoPlayerViewModel.stopStreamMaintenance() }
    }

    LaunchedEffect(
        showControls,
        isSubMenuOpen,
        isSceneSearchOpen,
        isChapterListOpen,
        isProgramInfoOpen,
        isModernSettingsOpen,
        vs.lCropMode,
        vs.lastInteractionTime,
        vs.isSeekBarFocused
    ) {
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen && !isProgramInfoOpen && !isModernSettingsOpen && !vs.isSeekBarFocused && vs.lCropMode == LCropMode.HIDDEN) {
            delay(5000); onShowControlsChange(false)
        }
    }

    var wasControlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(
        isSubMenuOpen,
        isSceneSearchOpen,
        isChapterListOpen,
        isProgramInfoOpen,
        isModernSettingsOpen,
        showControls
    ) {
        if (isPiPMode) return@LaunchedEffect
        delay(150)

        if (isSubMenuOpen) {
            subMenuFocusRequester.safeRequestFocus(TAG)
        } else if (showControls && isModern && !isSubOverlayOpen) {
            if (!wasControlsVisible) {
                playerControlsFocusRequester.safeRequestFocus(TAG)
            }
        } else if (!showControls && vs.lCropMode == LCropMode.HIDDEN) {
            mainFocusRequester.safeRequestFocus(TAG)
        }

        wasControlsVisible = showControls
    }

    val safeHouseFocusRequester = remember { FocusRequester() }
    val sceneSearchFocusRequester = remember { FocusRequester() }
    var isLongPressHandled by remember { mutableStateOf(false) }

    BackHandler(enabled = isPiPMode) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                // ★ UIのボタンにフォーカスがある場合に操作していてもUIが消えてしまう問題の修正
                // キー操作が行われるたびに最終インタラクション時間を更新し、非表示タイマーをリセットする
                if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    vs.lastInteractionTime = System.currentTimeMillis()
                }

                vs.handleKeyEvent(
                    keyEvent = keyEvent,
                    isPiPMode = isPiPMode,
                    isModern = isModern,
                    showControls = showControls,
                    isSubOverlayOpen = isSubOverlayOpen,
                    chapters = chapters,
                    totalDurationMs = totalDurationForControls,
                    getCurrentPositionMs = getCurrentPositionMs,
                    performSeek = performSeek,
                    triggerSeekingPreview = triggerSeekingPreview,
                    onShowControlsChange = onShowControlsChange,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed,
                    onSceneSearchToggle = { onSceneSearchToggle(it) },
                    onChapterListToggle = { isChapterListOpen = it },
                    onSubMenuToggle = onSubMenuToggle,
                    exoPlayerIsPlaying = exoPlayer.playWhenReady,
                    onPause = { exoPlayer.pause() },
                    onPlay = { exoPlayer.play() }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                AspectRatioFrameLayout(ctx).apply {
                    keepScreenOn = true
                    val surfaceView =
                        SurfaceView(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
                    addView(surfaceView)
                }
            },
            update = { view ->
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
                .focusable(!isPiPMode && !isSubOverlayOpen && vs.lCropMode == LCropMode.HIDDEN)
        )

        if (!isPiPMode) {
            val commentLayer = @Composable {
                if (isHeavyUiReady && vs.isCommentEnabled) {
                    ArchivedCommentOverlay(
                        Modifier.fillMaxSize(), allComments, { getCurrentPositionMs() },
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
                            val targetAlpha =
                                if (vs.isSubtitleEnabled && !isSubOverlayOpen) 1f else 0f
                            if (view.alpha != targetAlpha) {
                                view.alpha = targetAlpha
                            }
                        },
                        onRelease = { view -> view.destroy(); webViewRef.value = null },
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
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )

            PlayerControls(
                exoPlayer = exoPlayer,
                program = currentProgram,
                tiledThumbnailUrl = tiledThumbnailUrl,
                allComments = allComments,
                isVisible = showControls && !isSubOverlayOpen && vs.lCropMode == LCropMode.HIDDEN,
                isSeekingPreviewVisible = isSeekingPreviewVisible,
                isModernUi = isModern,
                isPlaying = exoPlayer.playWhenReady,
                hasChapters = chapters.isNotEmpty(),
                externalChapters = chapters,
                currentPositionMs = getEffectivePositionMs(),
                totalDurationMs = totalDurationForControls,
                controlsFocusRequester = playerControlsFocusRequester,
                onSeekBarFocusChanged = { vs.isSeekBarFocused = it },
                onPlayPauseToggle = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    vs.togglePlayPause(exoPlayer.playWhenReady)
                    if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
                },
                onSeekBack = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    performSeek((basePos - 10_000).coerceAtLeast(0L))
                },
                onSeekForward = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    performSeek((basePos + 30_000).coerceAtMost(totalDurationForControls))
                },
                onSeekRequested = { performSeek(it) }, // ★ 追加: シークバー操作によるシーク実行
                onSkipPreviousChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    skipToPreviousChapter()
                },
                onSkipNextChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    skipToNextChapter()
                },
                onChapterListToggle = { isChapterListOpen = true; onShowControlsChange(true) },
                onInfoToggle = { isProgramInfoOpen = true; onShowControlsChange(true) },
                onSettingsToggle = {
                    if (isModern) isModernSettingsOpen = true else onSubMenuToggle(
                        true
                    )
                }
            )

            AnimatedVisibility(visible = isProgramInfoOpen, enter = fadeIn(), exit = fadeOut()) {
                ProgramInfoOverlay(
                    program = currentProgram,
                    onClose = { isProgramInfoOpen = false })
            }

            AnimatedVisibility(
                isChapterListOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()) {
                ChapterListOverlay(
                    program = currentProgram,
                    chapters = chapters,
                    tiledThumbnailUrl = tiledThumbnailUrl,
                    currentPositionMs = getEffectivePositionMs(),
                    onSeekRequested = { performSeek(it); isChapterListOpen = false },
                    onClose = { isChapterListOpen = false },
                    requestHeaders = cfAccessHeaders)
            }

            AnimatedVisibility(visible = isModernSettingsOpen, enter = fadeIn(), exit = fadeOut()) {
                ModernVideoSettingsOverlay(
                    currentAudioMode = vs.currentAudioMode,
                    currentSpeed = vs.currentSpeed,
                    isSubtitleEnabled = vs.isSubtitleEnabled,
                    currentQuality = vs.currentQuality,
                    isCommentEnabled = vs.isCommentEnabled,
                    isLCropEnabled = vs.lCropEnabled,
                    isAutoCmSkipEnabled = vs.isAutoCmSkipEnabled,
                    availableQualities = availableQualities,
                    onAudioToggle = {
                        // Stateを変更するだけ。実際の適用は VideoPlayerManager の LaunchedEffect が検知して行います。
                        vs.currentAudioMode =
                            if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                        onShowToast("音声: ${if (vs.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
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
                    onQualitySelect = {
                        if (smbItem != null) {
                            onShowToast("SMB再生中は画質の変更はできません")
                            isModernSettingsOpen = false
                            return@ModernVideoSettingsOverlay
                        }
                        if (vs.currentQuality != it) {
                            vs.playbackOffsetMs = getCurrentPositionMs()
                            vs.currentQuality = it
                            videoPlayerViewModel.saveVideoQuality(it.value)
                            val player = exoPlayer
                            val currentPos = getCurrentPositionMs()
                            if (isEdcbDirect) {
                                scope.launch {
                                    isBuffering = true;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        program.id,
                                        it.value,
                                        currentSessionId,
                                        0.0
                                    ); player.setMediaItem(MediaItem.fromUri(newUrl)); player.prepare(); player.seekTo(
                                    currentPos
                                ); player.play()
                                }
                            } else {
                                vs.playbackOffsetMs =
                                    currentPos - (initialPositionMs * 1000).toLong()
                                scope.launch {
                                    isBuffering = true;
                                    val offsetSec = currentPos / 1000.0;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        program.id,
                                        it.value,
                                        currentSessionId,
                                        offsetSec
                                    ); player.setMediaItem(MediaItem.fromUri(newUrl)); player.prepare(); player.play()
                                }
                            }
                            onShowToast("画質を ${it.label} に変更しました")
                        }
                        isModernSettingsOpen = false; vs.lastInteractionTime =
                        System.currentTimeMillis()
                    },
                    onCommentToggle = {
                        vs.isCommentEnabled =
                            !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                    },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode =
                                LCropMode.MENU; onSubMenuToggle(false); onShowControlsChange(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f; vs.lCropOrigin = ZoomOrigin.TopRight
                        }
                    },
                    onAutoCmSkipToggle = {
                        vs.isAutoCmSkipEnabled = !vs.isAutoCmSkipEnabled
                        if (vs.isAutoCmSkipEnabled && chapters.size <= 1) onShowToast("チャプター情報がないためスキップできません") else onShowToast(
                            "自動CMスキップ: ${if (vs.isAutoCmSkipEnabled) "ON" else "OFF"}"
                        )
                    },
                    onClose = { isModernSettingsOpen = false }
                )
            }

            AnimatedVisibility(
                isSubMenuOpen,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()) {
                VideoTopSubMenuUI(
                    currentAudioMode = vs.currentAudioMode,
                    currentSpeed = vs.currentSpeed,
                    isSubtitleEnabled = vs.isSubtitleEnabled,
                    currentQuality = vs.currentQuality,
                    isCommentEnabled = vs.isCommentEnabled,
                    isLCropEnabled = vs.lCropEnabled,
                    isAutoCmSkipEnabled = vs.isAutoCmSkipEnabled,
                    availableQualities = availableQualities,
                    focusRequester = subMenuFocusRequester,
                    onAudioToggle = {
                        // Stateを変更するだけ。実際の適用は VideoPlayerManager の LaunchedEffect が検知して行います。
                        vs.currentAudioMode =
                            if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                        onShowToast("音声: ${if (vs.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
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
                    onQualitySelect = {
                        if (smbItem != null) {
                            onShowToast("SMB再生中は画質の変更はできません")
                            onSubMenuToggle(false)
                            return@VideoTopSubMenuUI
                        }
                        if (vs.currentQuality != it) {
                            vs.playbackOffsetMs = getCurrentPositionMs()
                            vs.currentQuality = it
                            videoPlayerViewModel.saveVideoQuality(it.value)
                            val player = exoPlayer
                            val currentPos = getCurrentPositionMs()
                            if (isEdcbDirect) {
                                scope.launch {
                                    isBuffering = true;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        program.id,
                                        it.value,
                                        currentSessionId,
                                        0.0
                                    ); player.setMediaItem(MediaItem.fromUri(newUrl)); player.prepare(); player.seekTo(
                                    currentPos
                                ); player.play()
                                }
                            } else {
                                vs.playbackOffsetMs =
                                    currentPos - (initialPositionMs * 1000).toLong()
                                scope.launch {
                                    isBuffering = true;
                                    val offsetSec = currentPos / 1000.0;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        program.id,
                                        it.value,
                                        currentSessionId,
                                        offsetSec
                                    ); player.setMediaItem(MediaItem.fromUri(newUrl)); player.prepare(); player.play()
                                }
                            }
                            onShowToast("画質を ${it.label} に変更しました")
                        }
                        onSubMenuToggle(false); vs.lastInteractionTime = System.currentTimeMillis()
                    },
                    onCommentToggle = {
                        vs.isCommentEnabled =
                            !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                    },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode =
                                LCropMode.MENU; onSubMenuToggle(false); onShowControlsChange(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f; vs.lCropOrigin = ZoomOrigin.TopRight
                        }
                    },
                    onAutoCmSkipToggle = {
                        vs.isAutoCmSkipEnabled = !vs.isAutoCmSkipEnabled
                        if (vs.isAutoCmSkipEnabled && chapters.size <= 1) onShowToast("チャプター情報がないためスキップできません") else onShowToast(
                            "自動CMスキップ: ${if (vs.isAutoCmSkipEnabled) "ON" else "OFF"}"
                        )
                    },
                )
            }

            if (!isModern) {
                PlaybackIndicator(vs.indicatorState)
            }
        }
    }
}