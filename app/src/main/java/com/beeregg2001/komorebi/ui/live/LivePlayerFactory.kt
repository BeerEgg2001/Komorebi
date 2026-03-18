@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.util.Base64
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.TsExtractor
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory

/**
 * ExoPlayer の初期化処理を共通化した Composable 関数
 */
@UnstableApi
@Composable
fun rememberKomorebiPlayer(
    context: Context,
    userAgent: String,
    streamSource: StreamSource,
    quality: StreamQuality,
    audioOutputMode: String,
    retryKey: Int,
    subtitleEnabledState: MutableState<Boolean>,
    webViewRef: MutableState<WebView?>,
    tsDataSourceFactory: TsReadExDataSourceFactory, // ★修正: 引数で受け取る
    onVideoSizeChanged: (VideoSize) -> Unit,
    onIsPlayingChanged: (Boolean) -> Unit,
    onPlayerError: (PlaybackException) -> Unit
): ExoPlayer {
    val httpDataSourceFactory =
        remember(userAgent) { DefaultHttpDataSource.Factory().setUserAgent(userAgent) }
    val defaultDataSourceFactory = remember(context, httpDataSourceFactory) {
        DefaultDataSource.Factory(
            context,
            httpDataSourceFactory
        )
    }

    val extractorsFactory = remember(webViewRef, subtitleEnabledState) {
        ExtractorsFactory {
            arrayOf(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    TimestampAdjuster(C.TIME_UNSET),
                    DirectSubtitlePayloadReaderFactory(webViewRef, subtitleEnabledState),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                )
            )
        }
    }

    val audioProcessor = remember {
        ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
            putChannelMixingMatrix(
                ChannelMixingMatrix(
                    6,
                    2,
                    floatArrayOf(1f, 0f, 0f, 1f, 0.707f, 0.707f, 0f, 0f, 0.707f, 0f, 0f, 0.707f)
                )
            )
        }
    }

    return remember(streamSource, retryKey, quality, audioOutputMode) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                val processors =
                    if (audioOutputMode == "PASSTHROUGH") emptyArray<AudioProcessor>() else arrayOf<AudioProcessor>(
                        audioProcessor
                    )
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(processors).build()
            }
        }.apply {
            if (streamSource == StreamSource.MIRAKURUN) {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            } else {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            }
            setEnableDecoderFallback(true)
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(2000, 10000, 1000, 1500)
                    .setPrioritizeTimeOverSizeThresholds(true).build()
            )
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder().setFallbackMaxPlaybackSpeed(1.04f)
                    .setFallbackMinPlaybackSpeed(0.96f).build()
            )
            .apply {
                if (streamSource == StreamSource.MIRAKURUN) {
                    setMediaSourceFactory(
                        DefaultMediaSourceFactory(
                            tsDataSourceFactory,
                            extractorsFactory
                        )
                    )
                } else {
                    setMediaSourceFactory(
                        DefaultMediaSourceFactory(
                            defaultDataSourceFactory,
                            extractorsFactory
                        )
                    )
                }
            }
            .build().apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                playWhenReady = true
                addAnalyticsListener(EventLogger(null, "ExoPlayerLog"))

                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        onVideoSizeChanged(videoSize)
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        onIsPlayingChanged(playing)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        onPlayerError(error)
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (streamSource == StreamSource.MIRAKURUN || !subtitleEnabledState.value) return
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
                                        "if(window.receiveSubtitleData){ window.receiveSubtitleData(${currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS}, '$base64Data'); }",
                                        null
                                    )
                                }
                            }
                        }
                    }
                })
            }
    }
}