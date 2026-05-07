@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.video.smb.player

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.video.player.*
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SmbViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.interfaces.IMedia

private const val TAG = "SmbVlcPlayerScreen"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SmbVlcPlayerScreen(
    program: RecordedProgram,
    smbItem: SmbItem,
    initialPositionMs: Long = 0,
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
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    smbViewModel: SmbViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val smbServerList by settingsViewModel.smbServerList.collectAsState()
    val currentServer = remember(smbServerList, smbItem.path) {
        smbServerList.find { server ->
            val host = server.ip.substringBefore("/")
            val port = server.port.ifEmpty { "445" }
            smbItem.path.startsWith("smb://$host:$port/") || smbItem.path.startsWith("smb://$host/")
        }
    }
    val smbUser = currentServer?.user ?: ""
    val smbPass = currentServer?.password ?: ""

    val vs = rememberVideoPlayerState()
    val playerUiMode by settingsViewModel.playerUiMode.collectAsState()
    val isModern = playerUiMode == "MODERN"

    var currentProgram by remember { mutableStateOf(program) }
    var vlcChapters by remember { mutableStateOf<List<ChapterInfo>>(emptyList()) }
    var customChapters by remember { mutableStateOf<List<ChapterInfo>>(emptyList()) }
    var isMetadataLoaded by remember { mutableStateOf(false) }
    var isChapterListOpen by remember { mutableStateOf(false) }

    var isBuffering by remember { mutableStateOf(true) }
    var timeMs by remember { mutableLongStateOf(initialPositionMs) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }

    var ignoreTimeEventUntil by remember { mutableLongStateOf(0L) }

    val allComments = remember { mutableStateListOf<ArchivedComment>() }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }

    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val playerControlsFocusRequester = remember { FocusRequester() }

    var isProgramInfoOpen by remember { mutableStateOf(false) }
    var isModernSettingsOpen by remember { mutableStateOf(false) }

    val isSubOverlayOpen =
        isSubMenuOpen || isSceneSearchOpen || isProgramInfoOpen || isModernSettingsOpen || isChapterListOpen

    LaunchedEffect(Unit) {
        vs.currentQuality = StreamQuality("非対応 (固定)", "fixed", false)
        vs.isCommentEnabled = false
        delay(800)
    }

    val autoCmSkipStr by settingsViewModel.autoCmSkip.collectAsState()
    LaunchedEffect(autoCmSkipStr) {
        vs.isAutoCmSkipEnabled = (autoCmSkipStr == "ON")
    }

    LaunchedEffect(vs.indicatorState) {
        if (vs.indicatorState != null) {
            delay(2000)
            vs.indicatorState = null
        }
    }

    LaunchedEffect(smbItem.path) {
        val chapters = smbViewModel.loadChaptersForSmbItem(smbItem, currentServer, 0.0)
        if (chapters.isNotEmpty()) {
            customChapters = chapters
            Log.i(TAG, "Successfully loaded ${chapters.size} custom chapters from SMB.")
        }
    }

    LaunchedEffect(vs.isPlayerPlaying) {
        if (vs.isPlayerPlaying) {
            delay(3000)
            if (vlcChapters.isEmpty() && customChapters.isNotEmpty()) {
                vlcChapters = customChapters
                Log.i(TAG, "Applied custom chapters because VLC found nothing.")
            }
        }
    }

    // ★ ターゲットURIの構築を切り出し（VLCとタイムスタンプ解析の両方で使うため）
    val targetUri = remember(smbItem.path, smbUser, smbPass) {
        val parts = smbItem.path.split("/")
        val encodedSmbPath = parts.mapIndexed { index, part ->
            if (index >= 3) Uri.encode(part) else part
        }.joinToString("/")

        if (smbUser.isNotBlank()) {
            val safeUser = Uri.encode(smbUser)
            val safePass = Uri.encode(smbPass)
            val authPrefix = if (safePass.isNotBlank()) "$safeUser:$safePass@" else "$safeUser:@"
            encodedSmbPath.replace("smb://", "smb://$authPrefix")
        } else {
            encodedSmbPath
        }
    }

    // ★ TSファイルのタイムスタンプ(PCR)解析による正確な長さ計算
    var calculatedTsDurationMs by remember { mutableLongStateOf(0L) }
    var isCalculatingDuration by remember { mutableStateOf(false) }

    LaunchedEffect(targetUri) {
        if (smbItem.name.endsWith(".ts", ignoreCase = true) || smbItem.name.endsWith(
                ".m2ts",
                ignoreCase = true
            )
        ) {
            isCalculatingDuration = true
            calculatedTsDurationMs = TsDurationCalculator.calculateDurationMs(targetUri)
            if (calculatedTsDurationMs > 0) {
                Log.i(TAG, "TS Duration exactly calculated: ${calculatedTsDurationMs}ms")
            }
            isCalculatingDuration = false
        }
    }

    // ★ 長さの決定（VLCが取得できればそれ、できなければ独自計算、それでも無理なら概算）
    val safeLengthMs =
        remember(lengthMs, vlcChapters, customChapters, smbItem.size, calculatedTsDurationMs) {
            if (lengthMs > 0L) {
                lengthMs
            } else if (calculatedTsDurationMs > 0L) {
                calculatedTsDurationMs // 独自計算した超正確な時間
            } else {
                val maxChapterTime = (vlcChapters + customChapters).maxOfOrNull {
                    if (it.endTimeMs < 43200000L) it.endTimeMs else it.startTimeMs
                } ?: 0L
                if (maxChapterTime > 0L) {
                    maxChapterTime + 30000L
                } else if (smbItem.size > 0L && !isCalculatingDuration) {
                    // 最終手段の概算（計算がまだ終わっていない場合は0のままにして待つ）
                    (smbItem.size.toDouble() / 2000000.0 * 1000.0).toLong()
                } else {
                    0L
                }
            }
        }

    val vlcComponents = remember(targetUri) {
        val options = arrayListOf(
            "--drop-late-frames",
            "--skip-frames",
            "--network-caching=3000",
            "--file-caching=3000",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--avcodec-skiploopfilter=4",
            "--avcodec-threads=0",
            "--avcodec-hurry-up"
        )
        val libVLC = LibVLC(context, options)
        val mediaPlayer = MediaPlayer(libVLC)

        val media = Media(libVLC, Uri.parse(targetUri)).apply {
            setHWDecoderEnabled(true, true)
            if (initialPositionMs > 0) {
                addOption(":start-time=${initialPositionMs / 1000f}")
            }
        }
        mediaPlayer.media = media
        media.release()

        Pair(libVLC, mediaPlayer)
    }

    val mediaPlayer = vlcComponents.second

    val fetchChaptersSafely: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            try {
                val chapters = mediaPlayer.getChapters(-1)
                if (chapters != null && chapters.isNotEmpty() && vlcChapters.isEmpty()) {
                    val parsed = chapters.mapIndexed { index, ch ->
                        val startTime = ch.timeOffset
                        val endTime =
                            if (index + 1 < chapters.size) chapters[index + 1].timeOffset else mediaPlayer.length.coerceAtLeast(
                                1L
                            )
                        ChapterInfo(
                            startTimeMs = startTime, endTimeMs = endTime,
                            isCm = ch.name?.contains(
                                "CM",
                                ignoreCase = true
                            ) == true || ch.name?.contains(
                                "Sponsor",
                                ignoreCase = true
                            ) == true,
                            isMarkerOnly = false,
                            label = ch.name ?: ""
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (vlcChapters.isEmpty()) {
                            vlcChapters = parsed
                            Log.i(TAG, "Chapters natively loaded via VLC: ${parsed.size} items")
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    DisposableEffect(lifecycleOwner, mediaPlayer) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    vs.isPlayerPlaying = true; isBuffering = false

                    if (!isMetadataLoaded) {
                        scope.launch(Dispatchers.IO) {
                            val media = mediaPlayer.media ?: return@launch
                            val details = mutableMapOf<String, String>()

                            details["ファイル名"] = smbItem.name
                            details["ファイルサイズ"] = "約 ${smbItem.size / (1024 * 1024)} MB"

                            media.getMeta(IMedia.Meta.Title)?.let { details["タイトル"] = it }
                            media.getMeta(IMedia.Meta.ShowName)?.let { details["番組名"] = it }
                            media.getMeta(IMedia.Meta.Date)?.let { details["公開年"] = it }

                            var vWidth = 0;
                            var vHeight = 0;
                            var vFps = 0f;
                            var vCodec = ""
                            var aCodec = "";
                            var aChannels = 0;
                            var aRate = 0

                            for (i in 0 until media.trackCount) {
                                val track = media.getTrack(i) ?: continue
                                val codecStr = track.codec?.uppercase() ?: ""

                                if (track.type == IMedia.Track.Type.Video) {
                                    val videoTrack = track as? IMedia.VideoTrack
                                    if (videoTrack != null) {
                                        vWidth = videoTrack.width; vHeight = videoTrack.height
                                        if (videoTrack.frameRateDen > 0) vFps =
                                            videoTrack.frameRateNum.toFloat() / videoTrack.frameRateDen.toFloat()
                                    }
                                    if (vCodec.isEmpty()) vCodec = codecStr
                                } else if (track.type == IMedia.Track.Type.Audio) {
                                    val audioTrack = track as? IMedia.AudioTrack
                                    if (audioTrack != null) {
                                        aChannels = audioTrack.channels; aRate = audioTrack.rate
                                    }
                                    if (aCodec.isEmpty()) aCodec = codecStr
                                }
                            }

                            if (vWidth > 0) details["映像解像度"] =
                                "$vWidth x $vHeight" + (if (vFps > 0f) " (%.2f fps)".format(vFps) else "")
                            if (vCodec.isNotBlank()) details["映像コーデック"] = vCodec
                            if (aCodec.isNotBlank()) details["音声コーデック"] =
                                aCodec + (if (aRate > 0) " ($aRate Hz) [$aChannels ch]" else "")

                            val desc = media.getMeta(IMedia.Meta.Description)
                                ?: "SMBネットワーク上の動画ファイルです。"
                            withContext(Dispatchers.Main) {
                                currentProgram = program.copy(
                                    detail = details,
                                    description = desc,
                                    genres = emptyList()
                                )
                                isMetadataLoaded = true
                            }

                            if (vlcChapters.isEmpty()) fetchChaptersSafely()
                        }
                    }
                }

                MediaPlayer.Event.TimeChanged -> {
                    if (System.currentTimeMillis() > ignoreTimeEventUntil) {
                        val newTime = event.timeChanged
                        if (newTime > 0L || safeLengthMs == 0L || timeMs < 1000L) {
                            if (kotlin.math.abs(newTime - timeMs) < 5000L || timeMs == 0L) {
                                timeMs = newTime
                            }
                        }
                    }
                }

                MediaPlayer.Event.Paused -> {
                    vs.isPlayerPlaying = false
                }

                MediaPlayer.Event.Buffering -> {
                    isBuffering = event.buffering < 100f
                }

                MediaPlayer.Event.LengthChanged -> {
                    lengthMs = event.lengthChanged
                    if (vlcChapters.isEmpty()) fetchChaptersSafely()
                }

                MediaPlayer.Event.EncounteredError -> {
                    onShowToast("VLCエンジン: 再生エラーが発生しました")
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        mediaPlayer.play()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) mediaPlayer.pause()
            else if (event == Lifecycle.Event.ON_START && vs.isPlayerPlaying) mediaPlayer.play()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mediaPlayer.stop(); mediaPlayer.detachViews(); mediaPlayer.release(); vlcComponents.first.release()
        }
    }

    LaunchedEffect(timeMs) { if (isSeeking) isSeeking = false }
    val getCurrentPositionMs: () -> Long = { timeMs }
    val getEffectivePositionMs: () -> Long = { vs.pendingSeekPositionMs ?: getCurrentPositionMs() }

    var pendingSeekJob by remember { mutableStateOf<Job?>(null) }
    val performSeek: (Long) -> Unit = { targetMs ->
        val limitLength = safeLengthMs.coerceAtLeast(1L)
        if (limitLength > 1L) {
            val safeTarget = targetMs.coerceIn(0L, limitLength)

            isSeeking = true
            timeMs = safeTarget
            vs.pendingSeekPositionMs = safeTarget

            pendingSeekJob?.cancel()
            pendingSeekJob = scope.launch {
                delay(400) // 連打防止

                ignoreTimeEventUntil = System.currentTimeMillis() + 2000L

                if (mediaPlayer.isSeekable) {
                    mediaPlayer.time = safeTarget
                    if (safeLengthMs > 0L) {
                        mediaPlayer.position = safeTarget.toFloat() / safeLengthMs.toFloat()
                    }
                } else {
                    // TSファイル等でシーク不可判定された場合も強制的に飛ばす
                    mediaPlayer.time = safeTarget
                }

                vs.pendingSeekPositionMs = null
                delay(500)
                isSeeking = false
            }
        }
    }

    LaunchedEffect(vs.isAutoCmSkipEnabled, vlcChapters) {
        while (isActive) {
            if (vs.isAutoCmSkipEnabled && vs.isPlayerPlaying && vlcChapters.isNotEmpty()) {
                val currentPos = getCurrentPositionMs()
                val cm =
                    vlcChapters.find { it.isCm && currentPos >= it.startTimeMs && currentPos < (it.endTimeMs - 1500) }
                if (cm != null) {
                    performSeek(cm.endTimeMs)
                    onShowToast("自動CMスキップ: 本編へ移動しました")
                    delay(3000)
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(showControls, isSubOverlayOpen, vs.lCropMode, vs.isSeekBarFocused) {
        if (showControls && !isSubOverlayOpen && !vs.isSeekBarFocused && vs.lCropMode == LCropMode.HIDDEN) {
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

    var isSeekingPreviewVisible by remember { mutableStateOf(false) }
    var seekingPreviewJob by remember { mutableStateOf<Job?>(null) }
    val triggerSeekingPreview: () -> Unit = {
        isSeekingPreviewVisible = true
        seekingPreviewJob?.cancel()
        seekingPreviewJob = scope.launch { delay(2000); isSeekingPreviewVisible = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                vs.handleKeyEvent(
                    keyEvent = keyEvent,
                    isPiPMode = isPiPMode,
                    isModern = isModern,
                    showControls = showControls,
                    isSubOverlayOpen = isSubOverlayOpen,
                    chapters = vlcChapters,
                    totalDurationMs = safeLengthMs,
                    getCurrentPositionMs = getCurrentPositionMs,
                    performSeek = performSeek,
                    triggerSeekingPreview = triggerSeekingPreview,
                    onShowControlsChange = onShowControlsChange,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed,
                    onSceneSearchToggle = onSceneSearchToggle,
                    onChapterListToggle = { isChapterListOpen = it },
                    onSubMenuToggle = onSubMenuToggle,
                    exoPlayerIsPlaying = vs.isPlayerPlaying,
                    onPause = { mediaPlayer.pause() },
                    onPlay = { mediaPlayer.play() }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    keepScreenOn = true
                    mediaPlayer.attachViews(
                        this,
                        null,
                        false,
                        false
                    )
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
                            ZoomOrigin.TopLeft -> TransformOrigin(
                                0f,
                                0f
                            ); ZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                            ZoomOrigin.BottomLeft -> TransformOrigin(
                                0f,
                                1f
                            ); ZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                        }
                    }
                }
                .focusRequester(mainFocusRequester)
                .focusable(!isPiPMode && !isSubOverlayOpen && vs.lCropMode == LCropMode.HIDDEN)
        )

        if (!isPiPMode) {
            if (isBuffering) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )

            PlayerControls(
                exoPlayer = null,
                program = currentProgram,
                tiledThumbnailUrl = null,
                allComments = allComments,
                isVisible = showControls && !isSubOverlayOpen && vs.lCropMode == LCropMode.HIDDEN,
                isSeekingPreviewVisible = isSeekingPreviewVisible,
                isModernUi = isModern,
                isPlaying = vs.isPlayerPlaying,
                hasChapters = vlcChapters.isNotEmpty(),
                externalChapters = vlcChapters,
                currentPositionMs = getEffectivePositionMs(),
                totalDurationMs = safeLengthMs,
                controlsFocusRequester = playerControlsFocusRequester,
                onSeekBarFocusChanged = { vs.isSeekBarFocused = it },
                onPlayPauseToggle = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    vs.togglePlayPause(vs.isPlayerPlaying)
                    if (vs.isPlayerPlaying) mediaPlayer.pause() else mediaPlayer.play()
                },
                onSeekBack = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    performSeek((basePos - 10_000).coerceAtLeast(0L))
                },
                onSeekForward = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    performSeek((basePos + 30_000).coerceAtMost(safeLengthMs))
                },
                onSkipPreviousChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    val reversedChapters = vlcChapters.sortedByDescending { it.startTimeMs }
                    val prevChapter = reversedChapters.find { it.startTimeMs < basePos - 5000 }
                    performSeek(prevChapter?.startTimeMs ?: 0L)
                },
                onSkipNextChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    val nextChapter = vlcChapters.find { it.startTimeMs > basePos + 3000 }
                    if (nextChapter != null) {
                        performSeek(nextChapter.startTimeMs)
                    } else {
                        onShowToast("次のチャプターはありません")
                    }
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
                    chapters = vlcChapters,
                    tiledThumbnailUrl = null,
                    currentPositionMs = getEffectivePositionMs(),
                    onSeekRequested = { performSeek(it); isChapterListOpen = false },
                    onClose = { isChapterListOpen = false })
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
                    availableQualities = emptyList(),
                    isQualitySupported = false,
                    isCommentSupported = false,
                    isAutoCmSkipSupported = vlcChapters.isNotEmpty(),
                    onAudioToggle = {
                        val tracks = mediaPlayer.audioTracks?.filter { it.id != -1 } ?: emptyList()
                        if (tracks.size > 1) {
                            val nextIdx =
                                (tracks.indexOfFirst { it.id == mediaPlayer.audioTrack } + 1) % tracks.size
                            mediaPlayer.audioTrack = tracks[nextIdx].id
                            onShowToast("音声: ${tracks[nextIdx].name}")
                        } else onShowToast("音声トラックが1つしかありません")
                    },
                    onSpeedToggle = {
                        val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f)
                        vs.currentSpeed =
                            speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]
                        mediaPlayer.rate = vs.currentSpeed; onShowToast("速度: ${vs.currentSpeed}x")
                    },
                    onSubtitleToggle = {
                        val tracks = mediaPlayer.spuTracks?.filter { it.id != -1 } ?: emptyList()
                        if (tracks.isNotEmpty()) {
                            vs.isSubtitleEnabled = !vs.isSubtitleEnabled
                            mediaPlayer.spuTrack =
                                if (vs.isSubtitleEnabled) tracks.first().id else -1
                            onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                        } else onShowToast("字幕トラックがありません")
                    },
                    onQualitySelect = { isModernSettingsOpen = false },
                    onCommentToggle = { /* 非対応 */ },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode = LCropMode.MENU; isModernSettingsOpen =
                                false; onShowControlsChange(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f
                        }
                    },
                    onAutoCmSkipToggle = {
                        vs.isAutoCmSkipEnabled = !vs.isAutoCmSkipEnabled
                        onShowToast("自動CMスキップ: ${if (vs.isAutoCmSkipEnabled) "ON" else "OFF"}")
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
                    availableQualities = emptyList(),
                    focusRequester = subMenuFocusRequester,
                    isQualitySupported = false,
                    isCommentSupported = false,
                    isAutoCmSkipSupported = vlcChapters.isNotEmpty(),
                    onAudioToggle = {
                        val tracks = mediaPlayer.audioTracks?.filter { it.id != -1 } ?: emptyList()
                        if (tracks.size > 1) {
                            val nextIdx =
                                (tracks.indexOfFirst { it.id == mediaPlayer.audioTrack } + 1) % tracks.size
                            mediaPlayer.audioTrack = tracks[nextIdx].id
                            onShowToast("音声: ${tracks[nextIdx].name}")
                        } else onShowToast("音声トラックが1つしかありません")
                    },
                    onSpeedToggle = {
                        val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f)
                        vs.currentSpeed =
                            speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]
                        mediaPlayer.rate = vs.currentSpeed; onShowToast("速度: ${vs.currentSpeed}x")
                    },
                    onSubtitleToggle = {
                        val tracks = mediaPlayer.spuTracks?.filter { it.id != -1 } ?: emptyList()
                        if (tracks.isNotEmpty()) {
                            vs.isSubtitleEnabled = !vs.isSubtitleEnabled
                            mediaPlayer.spuTrack =
                                if (vs.isSubtitleEnabled) tracks.first().id else -1
                            onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                        } else onShowToast("字幕トラックがありません")
                    },
                    onQualitySelect = { onSubMenuToggle(false) },
                    onCommentToggle = { /* 非対応 */ },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode =
                                LCropMode.MENU; onSubMenuToggle(false); onShowControlsChange(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f
                        }
                    },
                    onAutoCmSkipToggle = {
                        vs.isAutoCmSkipEnabled = !vs.isAutoCmSkipEnabled
                        onShowToast("自動CMスキップ: ${if (vs.isAutoCmSkipEnabled) "ON" else "OFF"}")
                    }
                )
            }

            if (!isModern) {
                PlaybackIndicator(vs.indicatorState)
            }
        }
    }
}

// =========================================================================================
// ★ TSファイルのタイムスタンプ(PCR)を読み取り、正確な動画長を算出するユーティリティクラス
// =========================================================================================
object TsDurationCalculator {
    private const val TS_PACKET_SIZE = 188
    private const val CHUNK_SIZE = 2 * 1024 * 1024L // 両端2MBだけをピンポイントで読み取る

    suspend fun calculateDurationMs(targetUri: String): Long = withContext(Dispatchers.IO) {
        var file: jcifs.smb.SmbRandomAccessFile? = null
        try {
            val smbFile = jcifs.smb.SmbFile(targetUri)
            file = jcifs.smb.SmbRandomAccessFile(smbFile, "r")

            val length = file.length()
            if (length < CHUNK_SIZE) return@withContext 0L

            // 1. ファイル先頭(2MB)から最初のPCRを探す
            val frontBuffer = ByteArray(CHUNK_SIZE.toInt())
            file.seek(0)
            file.read(frontBuffer)
            val firstPcr = findFirstPcr(frontBuffer)

            // 2. ファイル末尾(2MB)から最後のPCRを探す
            val backBuffer = ByteArray(CHUNK_SIZE.toInt())
            val backPos = java.lang.Long.max(0L, length - CHUNK_SIZE)
            file.seek(backPos)
            file.read(backBuffer)
            val lastPcr = findLastPcr(backBuffer)

            if (firstPcr != -1L && lastPcr != -1L) {
                var diffTicks = lastPcr - firstPcr
                if (diffTicks < 0) {
                    // PCRのラップアラウンド（約26.5時間）に対応
                    diffTicks += (1L shl 33)
                }
                // PCRベースは90kHzクロックなので、90で割るとミリ秒単位になる
                return@withContext (diffTicks / 90.0).toLong()
            }
        } catch (e: Exception) {
            Log.e("TsDurationCalculator", "TS length calculation failed: ${e.message}")
        } finally {
            try {
                file?.close()
            } catch (e: Exception) {
            }
        }
        return@withContext 0L
    }

    private fun findFirstPcr(data: ByteArray): Long {
        for (i in 0 until data.size - TS_PACKET_SIZE) {
            if (data[i] == 0x47.toByte()) {
                val pcr = extractPcr(data, i)
                if (pcr != -1L) return pcr
            }
        }
        return -1L
    }

    private fun findLastPcr(data: ByteArray): Long {
        for (i in data.size - TS_PACKET_SIZE downTo 0) {
            if (data[i] == 0x47.toByte()) {
                val pcr = extractPcr(data, i)
                if (pcr != -1L) return pcr
            }
        }
        return -1L
    }

    private fun extractPcr(data: ByteArray, offset: Int): Long {
        if (offset + 11 > data.size) return -1L

        val afc = (data[offset + 3].toInt() and 0x30) shr 4
        // アダプテーションフィールドが存在するか
        if (afc == 2 || afc == 3) {
            val afLength = data[offset + 4].toInt() and 0xFF
            if (afLength > 0 && offset + 5 + afLength <= data.size) {
                val flags = data[offset + 5].toInt() and 0xFF
                // PCRフラグ(0x10)が立っているか
                if ((flags and 0x10) != 0) {
                    val pcr1 = data[offset + 6].toLong() and 0xFF
                    val pcr2 = data[offset + 7].toLong() and 0xFF
                    val pcr3 = data[offset + 8].toLong() and 0xFF
                    val pcr4 = data[offset + 9].toLong() and 0xFF
                    val pcr5 = data[offset + 10].toLong() and 0x80

                    // 33bitのPCRベースを抽出
                    return (pcr1 shl 25) or (pcr2 shl 17) or (pcr3 shl 9) or (pcr4 shl 1) or (pcr5 ushr 7)
                }
            }
        }
        return -1L
    }
}