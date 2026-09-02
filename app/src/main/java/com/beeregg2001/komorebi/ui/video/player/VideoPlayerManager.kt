@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.TsFilterStateSnapshot
import com.beeregg2001.komorebi.util.TsReadExDataSource
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VideoPlayerManager"
private const val MAX_PLAYER_RETRY_COUNT = 5

// ★ 追加: 直接TS再生(EDCB DIRECT)向けのシーク機構を作り直すにあたって導入したハンドル群。
// 以前はExoPlayerのSeekMap機構(自前SeekMap注入+sniff()バイパス)にシークを委ねていたが、
// Media3内部のTimeline/SeekMapキャッシュと衝突し、原因不明のタイミングで自前SeekMapが
// 全く適用されない不具合が実機で確認された。この方式は廃止し、シーク要求時にアプリ側で
// 目標バイト位置を計算してMediaItemを作り直す方式(epcltvapp参考)に置き換える。
// - fileSizeBytesRef: 直近のHTTPレスポンスから得たファイル全体サイズ(バイト位置計算に必要)
// - pendingSeekByteRef: 次にTsReadExDataSource.open()が呼ばれた際、position=0の代わりに
//   使うべき「シーク先バイト位置」の予約値。-1は「予約なし(通常の先頭再生)」を意味する。
data class ManagedPlayerHandles(
    val player: ExoPlayer,
    val fileSizeBytesRef: AtomicLong,
    val pendingSeekByteRef: AtomicLong
)

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun rememberManagedExoPlayer(
    program: RecordedProgram?,
    vs: VideoPlayerState,
    scope: CoroutineScope,
    webViewRef: MutableState<WebView?>,
    onVideoSizeChanged: (Int, Int, Float) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit = {},
    onStopOrDispose: (ExoPlayer) -> Unit,
    // ★ 追加: Cloudflare Zero Trust サービストークン (未設定なら空Map)
    cfAccessHeaders: Map<String, String> = emptyMap(),
    onFatalError: (String) -> Unit = {},
    settingsViewModel: SettingsViewModel = hiltViewModel()
): ManagedPlayerHandles {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val backendType by settingsViewModel.backendType.collectAsState()
    val edcbPlayMethod by settingsViewModel.edcbRecordPlayMethod.collectAsState()
    val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")

    val applyAudioSelectionAndMatrix = { mode: AudioMode, player: ExoPlayer ->
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (audioGroups.isNotEmpty()) {
            val sortedAudioGroups = audioGroups.sortedBy { group ->
                group.mediaTrackGroup.getFormat(0).id?.toIntOrNull() ?: Int.MAX_VALUE
            }

            val isSub = mode == AudioMode.SUB
            val builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)

            if (sortedAudioGroups.size > 1) {
                val targetGroupIndex = if (isSub) 1 else 0
                val targetGroup =
                    sortedAudioGroups[targetGroupIndex.coerceAtMost(sortedAudioGroups.size - 1)]
                builder.addOverride(TrackSelectionOverride(targetGroup.mediaTrackGroup, 0))
            } else {
                val targetGroup = sortedAudioGroups.firstOrNull()
                if ((targetGroup?.mediaTrackGroup?.length ?: 0) > 1) {
                    val targetTrackIndex = if (isSub) 1 else 0
                    builder.addOverride(
                        TrackSelectionOverride(targetGroup!!.mediaTrackGroup, targetTrackIndex)
                    )
                }
            }
            val newParameters = builder.build()
            // ★ 修正: onTracksChanged() は録画開始直後のPMT解析途中(映像のみ→映像+音声、等)で
            // 複数回発火することがあるが、内容が変わっていなくても毎回 trackSelectionParameters を
            // 再代入すると、そのたびにMedia3内部の「隠れリシーク」が誘発される(不要な音ズレ再発の
            // 引き金になり得る)ため、実際に内容が変化する時だけ代入するよう冪等化する。
            if (newParameters != player.trackSelectionParameters) {
                player.trackSelectionParameters = newParameters
            }
        }
    }

    // ★ 追加: HTTPレスポンスから得たファイル全体サイズ(シーク先バイト位置の計算に必要)。
    // VideoPlayerScreen.kt側のシーク処理からも参照できるよう、ExoPlayer本体とは別にremember する。
    val fileSizeBytesRef = remember { AtomicLong(0L) }

    // ★ 追加: 直接TS再生のシーク先バイト位置の予約値(-1=予約なし)。ExoPlayerのSeekMap機構には
    // 頼らず、シーク要求のたびにアプリ側でこの値をセットしてからMediaItemを作り直すことで、
    // TsReadExDataSource.open()がposition=0の代わりにこの値から読み始めるようにする。
    val pendingSeekByteRef = remember { AtomicLong(-1L) }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("DTVClient/1.0")
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(90000)
            setReadTimeoutMs(90000)
            // ★ 追加: Cloudflare Access ヘッダーを付与
            setDefaultRequestProperties(cfAccessHeaders)
        }

        val nativeLib = NativeLib()

        // ★ 追加: 再生エラーの連続リトライ回数を保持(ファイル消失以外の一時的エラー用)
        var playerRetryCount = 0

        // ★ 追加: シークのたびに作り直される CServiceFilter の学習済み状態(PID構成/音声PTS-PCR差分)を
        // DataSourceの再オープンをまたいで引き継ぐための共有変数。音声トラック切替時にMedia3内部が
        // 発火させる「隠れシーク」で TsReadExDataSource.open() が再実行されても音ズレが再発しないようにする。
        val filterStateRef = AtomicReference<TsFilterStateSnapshot?>(null)

        val dataSourceFactory = DataSource.Factory {
            object : DataSource {
                private var activeDataSource: DataSource? = null
                private val transferListeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val isEdcbScheme = dataSpec.uri.scheme == "edcb"
                    val isDirectTs = dataSpec.uri.path?.endsWith(
                        ".ts",
                        ignoreCase = true
                    ) == true || dataSpec.uri.path?.endsWith("m2ts", ignoreCase = true) == true

                    val sid = program?.channel?.serviceId ?: -1
                    val nValue = sid.toString()

                    val dynamicTsArgs = arrayOf(
                        "tsreadex", "-x", "18/38/39", "-n", nValue,
                        "-a", "13", "-b", "5", "-c", "5", "-u", "1", "-d", "13"
                    )

                    val source = if (isEdcbScheme || isDirectTs || isEdcbDirect) {
                        // ★ 修正: ファイルサイズ格納用の参照とCloudflare Accessヘッダー、
                        // 音声PTS-PCR学習状態の引き継ぎ用参照、シーク先バイト位置の予約値を渡す
                        TsReadExDataSource(
                            nativeLib,
                            dynamicTsArgs,
                            fileSizeBytesRef,
                            cfAccessHeaders,
                            filterStateRef,
                            pendingSeekByteRef
                        )
                    } else {
                        httpDataSourceFactory.createDataSource()
                    }

                    transferListeners.forEach { source.addTransferListener(it) }
                    activeDataSource = source
                    return source.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                }

                override fun getUri(): Uri? = activeDataSource?.uri

                override fun close() {
                    activeDataSource?.close()
                    activeDataSource = null
                }
            }
        }

        // ★ 修正: 以前はTsExtractorをラップして自前SeekMapを強制注入し、ExoPlayerネイティブの
        // seekTo()でシークバードラッグを実現していたが、Media3内部のTimeline/SeekMapキャッシュと
        // 衝突し、原因不明のタイミングで自前SeekMapが全く適用されない(=シーク不可能になる)不具合が
        // 実機で確認された。この方式は廃止し、TsExtractorの自動判定(sniff)・初期化をそのまま信頼する。
        // シークは ExoPlayer の SeekMap 経由ではなく、アプリ側(VideoPlayerScreen.kt)で目標バイト位置を
        // 計算して MediaItem を作り直す方式(pendingSeekByteRef 経由)で行う。
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            setTsExtractorTimestampSearchBytes(2 * 1024 * 1024)
            setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        }

        val mediaSourceFactory =
            DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(150 * 1024 * 1024)
            .setBufferDurationsMs(30000, 120000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(),
                    true
                )
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        onVideoSizeChanged(
                            videoSize.width,
                            videoSize.height,
                            videoSize.pixelWidthHeightRatio
                        )
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        vs.isPlayerPlaying = playing
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        applyAudioSelectionAndMatrix(vs.currentAudioMode, this@apply)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                        if (playbackState == Player.STATE_READY) {
                            onDurationChanged(duration)
                            // ★ 正常に再生再開できたのでリトライ回数をリセット
                            playerRetryCount = 0
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)

                        // ★ 原因チェーンをたどり、録画ファイル消失(HTTP 404)かどうかを判定
                        var cause: Throwable? = error
                        var isFileMissing = false
                        while (cause != null) {
                            if (cause is java.io.FileNotFoundException) {
                                isFileMissing = true
                                break
                            }
                            cause = cause.cause
                        }

                        if (isFileMissing) {
                            Log.e(TAG, "Recording file is missing. Aborting retry.")
                            onFatalError("録画ファイルが見つかりません。削除された可能性があります。")
                            return
                        }

                        playerRetryCount++
                        if (playerRetryCount > MAX_PLAYER_RETRY_COUNT) {
                            Log.e(TAG, "Max retry count exceeded. Aborting.")
                            onFatalError("再生エラーが発生しました。通信状況をご確認ください。")
                            return
                        }

                        scope.launch {
                            onBufferingChanged(true)
                            delay(3000L)
                            prepare()
                            playWhenReady = true
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (!vs.isSubtitleEnabled) return
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
                                        "if(window.receiveSubtitleData){ window.receiveSubtitleData($currentPosition, '$base64Data'); }",
                                        null
                                    )
                                }
                            }
                        }
                    }
                })
            }
    }

    LaunchedEffect(vs.currentAudioMode) {
        applyAudioSelectionAndMatrix(vs.currentAudioMode, exoPlayer)
    }

    // DisposableEffect はキーが変わらない限り初回のラムダを保持し続けるため、
    // onStopOrDispose を直接captureすると再生開始後に変化した状態 (再生位置のオフセット判定など) が
    // 反映されない。常に最新のラムダを呼ぶよう rememberUpdatedState を挟む。
    val currentOnStopOrDispose by rememberUpdatedState(onStopOrDispose)

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
                currentOnStopOrDispose(exoPlayer)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            currentOnStopOrDispose(exoPlayer)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    return ManagedPlayerHandles(exoPlayer, fileSizeBytesRef, pendingSeekByteRef)
}