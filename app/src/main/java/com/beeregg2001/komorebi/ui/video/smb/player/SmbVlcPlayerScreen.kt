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

    val safeLengthMs = remember(lengthMs, vlcChapters, customChapters) {
        if (lengthMs > 0L) {
            lengthMs
        } else {
            val maxChapterTime = (vlcChapters + customChapters).maxOfOrNull {
                if (it.endTimeMs < 43200000L) it.endTimeMs else it.startTimeMs
            } ?: 0L
            if (maxChapterTime > 0L) maxChapterTime + 30000L else 0L
        }
    }

    val vlcComponents = remember(smbUser, smbPass) {
        val options = arrayListOf(
            "--drop-late-frames",
            "--skip-frames",
            "--network-caching=10000",
            "--file-caching=10000",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--avcodec-skiploopfilter=4",
            "--avcodec-threads=0",
            "--avcodec-hurry-up"
        )
        val libVLC = LibVLC(context, options)
        val mediaPlayer = MediaPlayer(libVLC)

        val parts = smbItem.path.split("/")
        val encodedSmbPath = parts.mapIndexed { index, part ->
            if (index >= 3) Uri.encode(part) else part
        }.joinToString("/")

        val targetUri = if (smbUser.isNotBlank()) {
            val safeUser = Uri.encode(smbUser)
            val safePass = Uri.encode(smbPass)
            val authPrefix = if (safePass.isNotBlank()) "$safeUser:$safePass@" else "$safeUser:@"
            encodedSmbPath.replace("smb://", "smb://$authPrefix")
        } else {
            encodedSmbPath
        }

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
    val getCurrentPositionMs: () -> Long =
        { if (isSeeking) vs.pendingSeekPositionMs ?: timeMs else timeMs }

    val performSeek: (Long) -> Unit = { targetMs ->
        val limitLength = safeLengthMs.coerceAtLeast(1L)
        if (limitLength > 1L && mediaPlayer.isSeekable) {
            isSeeking = true
            ignoreTimeEventUntil = System.currentTimeMillis() + 2000L
            timeMs = targetMs

            val safeTarget = targetMs.coerceIn(0L, limitLength)
            mediaPlayer.time = safeTarget
            mediaPlayer.position = safeTarget.toFloat() / limitLength.toFloat()
        }
    }

    LaunchedEffect(vs.pendingSeekPositionMs) {
        vs.pendingSeekPositionMs?.let {
            delay(400)
            performSeek(it)
            vs.pendingSeekPositionMs = null
            vs.isRightKeyLongPressed = false; vs.isLeftKeyLongPressed = false
            vs.rightKeyDownTime = 0L; vs.leftKeyDownTime = 0L
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

    LaunchedEffect(isSubOverlayOpen, showControls) {
        if (isPiPMode) return@LaunchedEffect
        delay(150)
        if (isSubMenuOpen) subMenuFocusRequester.safeRequestFocus(TAG)
        else if (showControls && isModern && !isSubOverlayOpen) playerControlsFocusRequester.safeRequestFocus(
            TAG
        )
        else if (!showControls && vs.lCropMode == LCropMode.HIDDEN) mainFocusRequester.safeRequestFocus(
            TAG
        )
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
                    keyEvent,
                    isPiPMode,
                    isModern,
                    showControls,
                    isSubOverlayOpen,
                    vlcChapters,
                    safeLengthMs,
                    getCurrentPositionMs,
                    performSeek,
                    triggerSeekingPreview,
                    onShowControlsChange,
                    onPiPRequested,
                    onBackPressed,
                    onSceneSearchToggle,
                    { isChapterListOpen = it },
                    onSubMenuToggle,
                    vs.isPlayerPlaying,
                    { mediaPlayer.pause() },
                    { mediaPlayer.play() })
            }
    ) {
        AndroidView(
            factory = { ctx ->
                // ★ 修正: スリープ（アンビエントモード）防止のための keepScreenOn = true を設定
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
                currentPositionMs = getCurrentPositionMs(),
                totalDurationMs = safeLengthMs,
                controlsFocusRequester = playerControlsFocusRequester,
                onSeekBarFocusChanged = { vs.isSeekBarFocused = it },
                onPlayPauseToggle = { vs.togglePlayPause(vs.isPlayerPlaying); if (vs.isPlayerPlaying) mediaPlayer.pause() else mediaPlayer.play() },
                onSeekBack = {
                    performSeek((getCurrentPositionMs() - 10_000).coerceAtLeast(0L)); vs.updateIndicator(
                    Icons.Default.FastRewind,
                    "-10s"
                )
                },
                onSeekForward = {
                    performSeek(
                        (getCurrentPositionMs() + 30_000).coerceAtMost(
                            safeLengthMs
                        )
                    ); vs.updateIndicator(Icons.Default.FastForward, "+30s")
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
                    currentPositionMs = getCurrentPositionMs(),
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
                    }
                )
            }
            PlaybackIndicator(vs.indicatorState)
        }
    }
}