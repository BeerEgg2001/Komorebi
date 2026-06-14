@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.metadata.id3.PrivFrame
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ライブ視聴用の ExoPlayer インスタンスを生成・設定するファクトリクラスです。
 * 音声のダウンミックス、バッファ制御、字幕メタデータの抽出などの複雑な設定を隠蔽します。
 */
@Singleton
class LivePlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * カスタム設定が適用された ExoPlayer を生成します。
     *
     * @param audioOutputMode "PASSTHROUGH" などの音声出力モード設定
     * @param isKonomiTvSource KonomiTVソースかどうかの判定（字幕メタデータ抽出に使用）
     * @param isSubtitleEnabled 現在字幕が有効かどうかの判定
     * @param onSubtitleDataReceived 字幕データ（Base64）を受信した際のコールバック (pts, base64Data)
     * @param onError プレイヤーエラー発生時のコールバック
     */
    fun createExoPlayer(
        audioOutputMode: String,
        isKonomiTvSource: () -> Boolean,
        isSubtitleEnabled: () -> Boolean,
        onSubtitleDataReceived: (Long, String) -> Unit,
        onError: (PlaybackException) -> Unit
    ): ExoPlayer {

        // 5.1ch音声などをステレオ（2ch）にダウンミックスするためのプロセッサ設定
        val audioProcessor = ChannelMixingAudioProcessor().apply {
            // 2ch -> 2ch (そのまま)
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))

            // 6ch(5.1ch) -> 2ch
            // 入力順: L, R, C, LFE, Ls, Rs
            putChannelMixingMatrix(
                ChannelMixingMatrix(
                    6,
                    2,
                    floatArrayOf(
                        // 出力 Left: L(1.0) + C(0.707) + Ls(0.707)
                        1f, 0f, 0.707f, 0f, 0.707f, 0f,
                        // 出力 Right: R(1.0) + C(0.707) + Rs(0.707)
                        0f, 1f, 0.707f, 0f, 0f, 0.707f
                    )
                )
            )
        }

        // レンダラーのファクトリ設定（デコーダーのフォールバック等を有効化）
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                val processors = if (audioOutputMode == "PASSTHROUGH") {
                    emptyArray<AudioProcessor>()
                } else {
                    arrayOf<AudioProcessor>(audioProcessor)
                }
                return DefaultAudioSink.Builder(ctx)
                    .setAudioProcessors(processors)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }

        // ライブ視聴に最適化したバッファ設定
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 10000, 1000, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // ★ 修正: 音ズレ（A/V Sync Drift）の完全防止策
        // ExoPlayerによる「遅延を取り戻すための微小な倍速調整」を完全に封じ、等倍速（1.0f）に固定する
        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMaxPlaybackSpeed(1.0f)
            .setFallbackMinPlaybackSpeed(1.0f)
            .build()

        // プレイヤーのビルドとリスナーの登録
        return ExoPlayer.Builder(context, renderersFactory)
            .setReleaseTimeoutMs(10000)
            .setDetachSurfaceTimeoutMs(10000)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
            .build().apply {
                // 自動フレームレート変更をオフにする（カクつき防止）
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onError(error)
                    }

                    // KonomiTVソースの場合、ID3メタデータからARIB字幕データを抽出
                    override fun onMetadata(metadata: Metadata) {
                        if (!isKonomiTvSource() || !isSubtitleEnabled()) return
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is PrivFrame && (entry.owner.contains(
                                    "aribb24",
                                    true
                                ) || entry.owner.contains("B24", true))
                            ) {
                                val base64Data =
                                    Base64.encodeToString(entry.privateData, Base64.NO_WRAP)
                                val pts =
                                    currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS
                                onSubtitleDataReceived(pts, base64Data)
                            }
                        }
                    }
                })
            }
    }
}