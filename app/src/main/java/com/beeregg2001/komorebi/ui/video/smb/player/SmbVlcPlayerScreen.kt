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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.AudioMode
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

    var customChapters by remember { mutableStateOf<List<ChapterInfo>>(emptyList()) }
    var calculatedTsDurationMs by remember { mutableLongStateOf(0L) }

    var isReadyToPlay by remember { mutableStateOf(false) }

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

    // チャプターの取得（並列処理）
    LaunchedEffect(smbItem.path) {
        val chapters = smbViewModel.loadChaptersForSmbItem(smbItem, currentServer, 0.0)
        if (chapters.isNotEmpty()) {
            customChapters = chapters
        }
    }

    LaunchedEffect(smbItem.path, smbUser, smbPass) {
        if (smbItem.name.endsWith(".ts", ignoreCase = true) || smbItem.name.endsWith(
                ".m2ts",
                ignoreCase = true
            )
        ) {
            calculatedTsDurationMs =
                TsDurationCalculator.calculateDurationMs(smbItem.path, smbUser, smbPass)
        }
        isReadyToPlay = true
    }

    val vlcTargetUri = remember(smbItem.path, smbUser, smbPass) {
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

    // =========================================================================
    // ★ 準備完了(isReadyToPlay)時のみプレイヤーとUIを構築する
    // =========================================================================
    if (isReadyToPlay) {
        var currentProgram by remember { mutableStateOf(program) }
        var vlcChapters by remember { mutableStateOf<List<ChapterInfo>>(emptyList()) }
        var isMetadataLoaded by remember { mutableStateOf(false) }
        var isChapterListOpen by remember { mutableStateOf(false) }

        var isBuffering by remember { mutableStateOf(true) }
        var timeMs by remember { mutableLongStateOf(initialPositionMs) }
        var lengthMs by remember { mutableLongStateOf(0L) }
        var isSeeking by remember { mutableStateOf(false) }

        var preSeekTimeMs by remember { mutableLongStateOf(-1L) }
        var lastSeekTargetMs by remember { mutableLongStateOf(-1L) }
        var seekTimeoutUntil by remember { mutableLongStateOf(0L) }

        val allComments = remember { mutableStateListOf<ArchivedComment>() }

        val mainFocusRequester = remember { FocusRequester() }
        val subMenuFocusRequester = remember { FocusRequester() }
        val playerControlsFocusRequester = remember { FocusRequester() }

        var isProgramInfoOpen by remember { mutableStateOf(false) }
        var isModernSettingsOpen by remember { mutableStateOf(false) }

        val isSubOverlayOpen =
            isSubMenuOpen || isSceneSearchOpen || isProgramInfoOpen || isModernSettingsOpen || isChapterListOpen

        LaunchedEffect(vs.isPlayerPlaying) {
            if (vs.isPlayerPlaying) {
                delay(3000)
                if (vlcChapters.isEmpty() && customChapters.isNotEmpty()) {
                    vlcChapters = customChapters
                }
            }
        }

        val safeLengthMs =
            remember(lengthMs, vlcChapters, customChapters, smbItem.size, calculatedTsDurationMs) {
                if (calculatedTsDurationMs > 0L) {
                    calculatedTsDurationMs
                } else if (lengthMs > 0L) {
                    lengthMs
                } else {
                    val maxChapterTime = (vlcChapters + customChapters).maxOfOrNull {
                        if (it.endTimeMs < 43200000L) it.endTimeMs else it.startTimeMs
                    } ?: 0L
                    if (maxChapterTime > 0L) {
                        maxChapterTime + 30000L
                    } else if (smbItem.size > 0L) {
                        (smbItem.size.toDouble() / 2000000.0 * 1000.0).toLong()
                    } else {
                        0L
                    }
                }
            }

        val vlcComponents = remember(vlcTargetUri) {
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

            val media = Media(libVLC, Uri.parse(vlcTargetUri)).apply {
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
                            if (vlcChapters.isEmpty()) vlcChapters = parsed
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
                                var vFps = 0f
                                var vCodec = "";
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
                        val newTime = event.timeChanged
                        if (newTime > 0L) {
                            val now = System.currentTimeMillis()
                            var shouldUpdate = true

                            if (isSeeking) {
                                shouldUpdate = false
                            } else if (now < seekTimeoutUntil && lastSeekTargetMs >= 0L && preSeekTimeMs >= 0L) {
                                val diffToTarget = kotlin.math.abs(newTime - lastSeekTargetMs)
                                val diffToOld = kotlin.math.abs(newTime - preSeekTimeMs)
                                val jumpSize = kotlin.math.abs(lastSeekTargetMs - preSeekTimeMs)

                                // ★ 新しい時間が「目標位置」よりも「元の位置(古いキャッシュ)」に近い場合のみブロックする
                                if (diffToOld < diffToTarget && jumpSize > 2000L) {
                                    shouldUpdate = false
                                } else {
                                    seekTimeoutUntil = 0L // 目標側に到達したため即座にロック解除
                                }
                            }

                            if (shouldUpdate) {
                                if (newTime < 1000L && timeMs > 5000L) {
                                    // ノイズとして無視
                                } else {
                                    timeMs = newTime
                                    isBuffering = false // ★ 時間が進み始めたらバッファリング完了とみなす
                                }
                            }
                        }
                    }

                    MediaPlayer.Event.PositionChanged -> {
                        if (mediaPlayer.length <= 0L && safeLengthMs > 0L) {
                            val posTime = (event.positionChanged * safeLengthMs).toLong()
                            if (posTime > 0L) {
                                val now = System.currentTimeMillis()
                                var shouldUpdate = true

                                if (isSeeking) {
                                    shouldUpdate = false
                                } else if (now < seekTimeoutUntil && lastSeekTargetMs >= 0L && preSeekTimeMs >= 0L) {
                                    val diffToTarget = kotlin.math.abs(posTime - lastSeekTargetMs)
                                    val diffToOld = kotlin.math.abs(posTime - preSeekTimeMs)
                                    val jumpSize = kotlin.math.abs(lastSeekTargetMs - preSeekTimeMs)

                                    if (diffToOld < diffToTarget && jumpSize > 2000L) {
                                        shouldUpdate = false
                                    } else {
                                        seekTimeoutUntil = 0L
                                    }
                                }

                                if (shouldUpdate) {
                                    if (posTime < 1000L && timeMs > 5000L) {
                                    } else {
                                        timeMs = posTime
                                        isBuffering = false // ★ ここでも解除
                                    }
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
        val getEffectivePositionMs: () -> Long =
            { vs.pendingSeekPositionMs ?: getCurrentPositionMs() }

        var pendingSeekJob by remember { mutableStateOf<Job?>(null) }

        // ★ 修正: SMB経由で再生する際、動画の長さ(safeLengthMs)が 0 と判定された場合でもシークをブロックせず許可。
        // さらにVLCエンジンで time と position の両方をセットすると競合バグが起きるため、正確な time のみに一本化。
        val performSeek: (Long) -> Unit = { targetMs ->
            val safeTarget = if (safeLengthMs > 0L) {
                targetMs.coerceIn(0L, safeLengthMs)
            } else {
                targetMs.coerceAtLeast(0L)
            }

            // ★ 追加: シーク開始時のみ、元の位置を記録する
            if (!isSeeking) {
                preSeekTimeMs = timeMs
            }
            isSeeking = true
            timeMs = safeTarget
            vs.pendingSeekPositionMs = safeTarget

            lastSeekTargetMs = safeTarget
            seekTimeoutUntil = System.currentTimeMillis() + 3000L // 最大3秒のロック

            pendingSeekJob?.cancel()
            pendingSeekJob = scope.launch {
                delay(400)

                if (mediaPlayer.length > 0L) {
                    mediaPlayer.time = safeTarget
                } else if (safeLengthMs > 0L) {
                    mediaPlayer.position = (safeTarget.toFloat() / safeLengthMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    mediaPlayer.time = safeTarget
                }

                vs.pendingSeekPositionMs = null
                delay(500)
                isSeeking = false
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

        // ★ 修正: vs.lastInteractionTime を監視キーに追加し、操作ごとにタイマーがリセットされるように修正
        LaunchedEffect(
            showControls,
            isSubOverlayOpen,
            vs.lCropMode,
            vs.isSeekBarFocused,
            vs.lastInteractionTime
        ) {
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
            if (isSubMenuOpen) subMenuFocusRequester.safeRequestFocus(TAG)
            else if (showControls && isModern && !isSubOverlayOpen) {
                if (!wasControlsVisible) playerControlsFocusRequester.safeRequestFocus(TAG)
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
                        mediaPlayer.attachViews(this, null, false, false)
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
                    // ★ 変更: バッファリング中(キャッシュ待ち)は、UI側のローカルタイマーの進行も止める
                    isPlaying = vs.isPlayerPlaying && !isBuffering,
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
                        val limit = if (safeLengthMs > 0L) safeLengthMs else Long.MAX_VALUE
                        performSeek((basePos + 30_000).coerceAtMost(limit))
                    },
                    onSeekRequested = { performSeek(it) }, // ★ 追加: シークバー操作によるシーク実行
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
                        if (nextChapter != null) performSeek(nextChapter.startTimeMs) else onShowToast(
                            "次のチャプターはありません"
                        )
                    },
                    onChapterListToggle = { isChapterListOpen = true; onShowControlsChange(true) },
                    onInfoToggle = { isProgramInfoOpen = true; onShowControlsChange(true) },
                    onSettingsToggle = {
                        if (isModern) isModernSettingsOpen = true else onSubMenuToggle(true)
                    }
                )

                AnimatedVisibility(
                    visible = isProgramInfoOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
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

                AnimatedVisibility(
                    visible = isModernSettingsOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
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
                            val tracks =
                                mediaPlayer.audioTracks?.filter { it.id != -1 } ?: emptyList()
                            if (tracks.size > 1) {
                                val nextIdx =
                                    (tracks.indexOfFirst { it.id == mediaPlayer.audioTrack } + 1) % tracks.size
                                mediaPlayer.audioTrack = tracks[nextIdx].id
                                onShowToast("音声: ${tracks[nextIdx].name}")
                                vs.currentAudioMode = if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                            } else onShowToast("音声トラックが1つしかありません")
                        },
                        onSpeedToggle = {
                            val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f)
                            vs.currentSpeed =
                                speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]
                            mediaPlayer.rate =
                                vs.currentSpeed; onShowToast("速度: ${vs.currentSpeed}x")
                        },
                        onSubtitleToggle = {
                            val tracks =
                                mediaPlayer.spuTracks?.filter { it.id != -1 } ?: emptyList()
                            if (tracks.isNotEmpty()) {
                                vs.isSubtitleEnabled = !vs.isSubtitleEnabled
                                mediaPlayer.spuTrack =
                                    if (vs.isSubtitleEnabled) tracks.first().id else -1
                                onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                            } else onShowToast("字幕トラックがありません")
                        },
                        onQualitySelect = { isModernSettingsOpen = false },
                        onCommentToggle = { },
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
                            val tracks =
                                mediaPlayer.audioTracks?.filter { it.id != -1 } ?: emptyList()
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
                            mediaPlayer.rate =
                                vs.currentSpeed; onShowToast("速度: ${vs.currentSpeed}x")
                        },
                        onSubtitleToggle = {
                            val tracks =
                                mediaPlayer.spuTracks?.filter { it.id != -1 } ?: emptyList()
                            if (tracks.isNotEmpty()) {
                                vs.isSubtitleEnabled = !vs.isSubtitleEnabled
                                mediaPlayer.spuTrack =
                                    if (vs.isSubtitleEnabled) tracks.first().id else -1
                                onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                            } else onShowToast("字幕トラックがありません")
                        },
                        onQualitySelect = { onSubMenuToggle(false) },
                        onCommentToggle = { },
                        onLCropToggle = {
                            vs.lCropEnabled = !vs.lCropEnabled
                            if (vs.lCropEnabled) {
                                vs.lCropMode =
                                    LCropMode.MENU; onSubMenuToggle(false); onShowControlsChange(
                                    false
                                )
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

                if (!isModern || !showControls) {
                    PlaybackIndicator(vs.indicatorState)
                }
            }
        }
    } else {
        // ★ ローディング画面: VLCもUIも一切構築せず、ただ待つだけ。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "動画ファイルを解析中...",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

object TsDurationCalculator {
    private const val TS_PACKET_SIZE = 188
    private const val CHUNK_SIZE = 2 * 1024 * 1024L // ★ ユーザー指定の2MB

    suspend fun calculateDurationMs(targetPath: String, smbUser: String, smbPass: String): Long =
        withContext(Dispatchers.IO) {
            var file: jcifs.smb.SmbRandomAccessFile? = null
            try {
                val context = com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder.build(
                    smbUser,
                    smbPass
                )
                val smbFile = jcifs.smb.SmbFile(targetPath, context)
                file = jcifs.smb.SmbRandomAccessFile(smbFile, "r")

                val length = file.length()
                if (length < CHUNK_SIZE) return@withContext 0L

                val frontBuffer = ByteArray(CHUNK_SIZE.toInt())
                file.seek(0)
                file.read(frontBuffer)

                val firstPcrMap = mutableMapOf<Int, Long>()
                for (i in 0 until frontBuffer.size - TS_PACKET_SIZE * 2) {
                    if (frontBuffer[i] == 0x47.toByte() && frontBuffer[i + TS_PACKET_SIZE] == 0x47.toByte()) {
                        val pid =
                            ((frontBuffer[i + 1].toInt() and 0x1F) shl 8) or (frontBuffer[i + 2].toInt() and 0xFF)
                        if (pid != 0x1FFF && !firstPcrMap.containsKey(pid)) {
                            val pcr = extractPcr(frontBuffer, i)
                            if (pcr != -1L) firstPcrMap[pid] = pcr
                        }
                    }
                }

                if (firstPcrMap.isEmpty()) return@withContext 0L

                val backBuffer = ByteArray(CHUNK_SIZE.toInt())
                val backPos = java.lang.Long.max(0L, length - CHUNK_SIZE)
                file.seek(backPos)
                file.read(backBuffer)

                for (i in backBuffer.size - 1 downTo TS_PACKET_SIZE) {
                    if (backBuffer[i] == 0x47.toByte() && backBuffer[i - TS_PACKET_SIZE] == 0x47.toByte()) {
                        val pid =
                            ((backBuffer[i + 1].toInt() and 0x1F) shl 8) or (backBuffer[i + 2].toInt() and 0xFF)

                        if (firstPcrMap.containsKey(pid)) {
                            val pcr = extractPcr(backBuffer, i)
                            if (pcr != -1L) {
                                var diffTicks = pcr - firstPcrMap[pid]!!
                                if (diffTicks < 0) diffTicks += (1L shl 33)

                                val durationMs = (diffTicks / 90.0).toLong()
                                if (durationMs in 1000..172800000L) return@withContext durationMs
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    file?.close()
                } catch (e: Exception) {
                }
            }
            return@withContext 0L
        }

    private fun extractPcr(data: ByteArray, offset: Int): Long {
        if (offset + 11 >= data.size) return -1L
        val afc = (data[offset + 3].toInt() and 0x30) shr 4
        if (afc == 2 || afc == 3) {
            val afLength = data[offset + 4].toInt() and 0xFF
            if (afLength > 0 && offset + 5 + afLength < data.size) {
                val flags = data[offset + 5].toInt() and 0xFF
                if ((flags and 0x10) != 0) {
                    val pcr1 = data[offset + 6].toLong() and 0xFF
                    val pcr2 = data[offset + 7].toLong() and 0xFF
                    val pcr3 = data[offset + 8].toLong() and 0xFF
                    val pcr4 = data[offset + 9].toLong() and 0xFF
                    val pcr5 = data[offset + 10].toLong() and 0x80
                    return (pcr1 shl 25) or (pcr2 shl 17) or (pcr3 shl 9) or (pcr4 shl 1) or (pcr5 ushr 7)
                }
            }
        }
        return -1L
    }
}