package com.beeregg2001.komorebi.ui.video

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

@UnstableApi
class CustomPlayerManager(private val context: Context) {
    var player: ExoPlayer? = null
        private set

    fun initializePlayer(videoUrl: String?) {
        // 1. レンダラーファクトリーの作成
        // EXTENSION_RENDERER_MODE_PREFER を設定することで、
        // システム標準(MediaCodec)よりも拡張(FFmpeg)を優先して使用します。
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // 2. プレイヤーの構築
        player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()

        // 3. 音声属性の設定（必要に応じて）
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player!!.setAudioAttributes(audioAttributes, true)

        // 4. 再生ソースの設定
        val mediaItem = MediaItem.fromUri(videoUrl!!)
        player!!.setMediaItem(mediaItem)


        // 5. 準備と再生
        player!!.prepare()
        player!!.setPlayWhenReady(true)
    }

    fun releasePlayer() {
        if (player != null) {
            player!!.release()
            player = null
        }
    }
}