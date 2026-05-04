@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
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

    val exoPlayer = remember(smbServerList) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx).setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
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