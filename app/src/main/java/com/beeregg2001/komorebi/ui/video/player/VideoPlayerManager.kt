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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER // ★ 修正: OFFからPREFERへ変更
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.audio.ChannelMixingAudioProcessor // ★ 追加
import androidx.media3.common.audio.ChannelMixingMatrix // ★ 追加
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
import com.beeregg2001.komorebi.data.model.AudioMode // ★ 追加
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "VideoPlayerManager"

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberManagedExoPlayer(
    vs: VideoPlayerState,
    scope: CoroutineScope,
    webViewRef: MutableState<WebView?>,
    onVideoSizeChanged: (Int, Int, Float) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit = {},
    onStopOrDispose: (ExoPlayer) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
): ExoPlayer {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ★ 修正: 単一のユーザー名ではなく、複数登録されたサーバーリストを取得
    val smbServerList by settingsViewModel.smbServerList.collectAsState()

    // ★ 追加: デュアルモノラル番組およびモノラル解説放送をリアルタイム分配制御するためのプロセッサ
    val audioProcessor = remember { ChannelMixingAudioProcessor() }

    // ★ 追加: 再生中の音声ストリームのチャンネル構成と、ユーザーが選択した音声モード（主/副）に応じてマトリクスを自動最適化する関数
    val updateAudioMixingMatrix = { mode: AudioMode, player: ExoPlayer ->
        val audioTrackCount = player.currentTracks.groups
            .count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }

        val activeGroup = player.currentTracks.groups
            .find { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }

        val format = activeGroup?.getTrackFormat(0)
        val inputChannels = format?.channelCount ?: 2

        val matrix = if (audioTrackCount >= 2) {
            // -------------------------------------------------------------------------
            // 【ケース1: マルチストリーム番組（音声PIDが主・副で完全に分離して配信されている解説放送等）】
            // -------------------------------------------------------------------------
            if (inputChannels == 1) {
                // 解説音声ストリーム自体が1chモノラルの場合、左右スピーカーへ100%ずつ均等分配（両耳モノラル化）
                ChannelMixingMatrix(1, 2, floatArrayOf(1f, 1f))
            } else {
                // 通常のステレオストリームの場合はそのままステレオ（スルー）出力
                ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))
            }
        } else {
            // ------------------------------------------------=========================
            // 【ケース2: シングルストリーム番組（通常のステレオ、または1つのストリーム内のL/Rに分かれたデュアルモノラル）】
            // -------------------------------------------------------------------------
            if (inputChannels == 2) {
                // 1ストリームかつ2ch構成の時は、日本の二カ国語放送特有のデュアルモノラルを考慮してL/Rを分離分配
                when (mode) {
                    AudioMode.MAIN -> ChannelMixingMatrix(
                        2,
                        2,
                        floatArrayOf(1f, 1f, 0f, 0f)
                    ) // 左チャンネル（主音声）を左右に分配
                    AudioMode.SUB -> ChannelMixingMatrix(
                        2,
                        2,
                        floatArrayOf(0f, 0f, 1f, 1f)
                    )  // 右チャンネル（副音声）を左右に分配
                    else -> ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))
                }
            } else {
                ChannelMixingMatrix(1, 2, floatArrayOf(1f, 1f))
            }
        }
        audioProcessor.putChannelMixingMatrix(matrix)
    }

    val exoPlayer = remember(smbServerList) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                // ★ 修正: 構築した audioProcessor を AudioSink にインジェクション
                val processors = arrayOf<AudioProcessor>(audioProcessor)
                return DefaultAudioSink.Builder(ctx)
                    .setAudioProcessors(processors)
                    .setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }.apply {
            // ★ 修正: EXTENSION_RENDERER_MODE_PREFER に切り替えることで、Fire TV等のハードウェアが持つ
            // 再生中のオーディオデコーダー再初期化バグを完全にバイパスし、FFmpegソフトウェアデコードによって
            // 2往復目以降のトラック切り替えでも絶対に無限ローディングフリーズを起こさない堅牢性を確保します。
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("DTVClient/1.0")
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(90000)
            setReadTimeoutMs(90000)
        }

        val dataSourceFactory = DataSource.Factory {
            object : DataSource {
                private var activeDataSource: DataSource? = null
                private val transferListeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val isSmb = dataSpec.uri.scheme == "smb"

                    val source = if (isSmb) {
                        // ★ 修正: 再生しようとしているURLのホスト名から該当するサーバーを探し出す
                        val host = dataSpec.uri.host ?: ""
                        val server = smbServerList.find { s ->
                            s.ip.substringBefore("/") == host
                        }
                        // 該当サーバーの認証情報をセット（なければ空＝ゲストとして試行）
                        val smbContext =
                            SmbContextBuilder.build(server?.user ?: "", server?.password ?: "")
                        val smbFactory = SmbDataSourceFactory(smbContext)
                        smbFactory.createDataSource()
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

        val extractorsFactory = DefaultExtractorsFactory().apply {
            setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            // ★ 修正: 独自パッチを当てたMedia3の動的PMT検出を正しく活かすため、MODE_MULTI_PMTを指定
            setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            setConstantBitrateSeekingEnabled(true)
            setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // ★ リミッター解除の大容量アロケーター
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

                    // ★ 追加: 日本の放送特有の動的な音声トラック追加・変更イベント発生時にマトリクスを同期更新
                    override fun onTracksChanged(tracks: Tracks) {
                        updateAudioMixingMatrix(vs.currentAudioMode, this@apply)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                        if (playbackState == Player.STATE_READY) {
                            onDurationChanged(duration)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)
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

    // ★ 追加: ユーザーがリモコン操作で主音声/副音声を切り替えた際、IndexOutOfBoundsExceptionの
    // クラッシュを完璧に防ぐ厳密な境界チェックを行いながら、純正のトラック選択パラメーターをオーバーライド更新する処理
    LaunchedEffect(vs.currentAudioMode) {
        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isNotEmpty()) {
            val isSub = vs.currentAudioMode == AudioMode.SUB
            val builder = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)

            if (audioGroups.size > 1) {
                // 【マルチストリーム番組（PID分離形式）の場合】
                // 音声PIDグループ自体を丸ごと切り替えるため、対象のTrackGroupを選択し、内部インデックスは0を固定指定
                val targetGroupIndex = if (isSub) 1 else 0
                if (targetGroupIndex < audioGroups.size) {
                    val group = audioGroups[targetGroupIndex].mediaTrackGroup
                    builder.addOverride(TrackSelectionOverride(group, 0))
                }
            } else {
                // 【デュアルモノラル番組（単一PID形式）の場合】
                // 同一のグループ（PID）の中で、トラックインデックス(0:主 / 1:副)を切り替える
                val group = audioGroups[0].mediaTrackGroup
                if (group.length > 1) {
                    val targetTrackIndex = if (isSub) 1 else 0
                    builder.addOverride(TrackSelectionOverride(group, targetTrackIndex))
                }
            }
            exoPlayer.trackSelectionParameters = builder.build()
        }
        updateAudioMixingMatrix(vs.currentAudioMode, exoPlayer)
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