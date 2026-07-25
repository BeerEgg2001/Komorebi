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
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.TsReadExDataSource
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "VideoPlayerManager"
private const val MAX_PLAYER_RETRY_COUNT = 5

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
): ExoPlayer {
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
            player.trackSelectionParameters = builder.build()
        }
    }

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

        // ★ 追加: HTTPリクエスト時に取得したファイルサイズを保持する共有変数
        val fileSizeBytesRef = AtomicLong(0L)

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
                        // ★ 修正: ファイルサイズ格納用の参照とCloudflare Accessヘッダーを渡す
                        TsReadExDataSource(
                            nativeLib,
                            dynamicTsArgs,
                            fileSizeBytesRef,
                            cfAccessHeaders
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

        // ★ 核心: ExoPlayer の Extractor をラップし、自前の SeekMap を強制注入する
        val programDurationUs = ((program?.recordedVideo?.duration ?: 0.0) * 1_000_000.0).toLong()

        val customExtractorsFactory = ExtractorsFactory {
            val defaultExtractors = DefaultExtractorsFactory().apply {
                setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                setTsExtractorTimestampSearchBytes(2 * 1024 * 1024)
                setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            }.createExtractors()

            // ダイレクトTS再生時のみ、TsExtractor をラップして SeekMap を上書き
            if (isEdcbDirect && programDurationUs > 0L) {
                for (i in defaultExtractors.indices) {
                    val extractor = defaultExtractors[i]
                    if (extractor is TsExtractor) {
                        defaultExtractors[i] = object : Extractor {
                            override fun sniff(input: ExtractorInput) = extractor.sniff(input)
                            override fun init(output: ExtractorOutput) {
                                extractor.init(object : ExtractorOutput by output {
                                    override fun seekMap(seekMap: SeekMap) {
                                        // TsExtractor が算出したエラーの SeekMap を無視し、独自の高精度マップを注入
                                        val customSeekMap = object : SeekMap {
                                            override fun isSeekable() = true
                                            override fun getDurationUs() = programDurationUs
                                            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                                                val size = fileSizeBytesRef.get()
                                                if (size <= 0L) return SeekMap.SeekPoints(
                                                    SeekPoint(
                                                        timeUs,
                                                        0L
                                                    )
                                                )
                                                val safeTime =
                                                    timeUs.coerceIn(0L, programDurationUs)
                                                // 時間とファイルサイズから、HTTP Range の要求バイトオフセットを正確に計算する
                                                val position =
                                                    (safeTime.toDouble() / programDurationUs * size).toLong()
                                                return SeekMap.SeekPoints(
                                                    SeekPoint(
                                                        safeTime,
                                                        position
                                                    )
                                                )
                                            }
                                        }
                                        output.seekMap(customSeekMap)
                                    }
                                })
                            }

                            override fun read(input: ExtractorInput, seekPosition: PositionHolder) =
                                extractor.read(input, seekPosition)

                            override fun seek(position: Long, timeUs: Long) =
                                extractor.seek(position, timeUs)

                            override fun release() = extractor.release()
                        }
                    }
                }
            }
            defaultExtractors
        }

        val mediaSourceFactory =
            DefaultMediaSourceFactory(dataSourceFactory, customExtractorsFactory)

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

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
                onStopOrDispose(exoPlayer)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            onStopOrDispose(exoPlayer)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    return exoPlayer
}