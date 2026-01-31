package com.example.komorebi.ui.video

import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.example.komorebi.data.model.RecordedProgram
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.media3.common.Player

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() } // フォーカス制御用
    val sessionId = remember { UUID.randomUUID().toString() }
    val quality = "1080p-60fps"
    val playlistUrl = "$konomiIp:$konomiPort/api/streams/video/${program.recordedVideo.id}/$quality/playlist?session_id=$sessionId"

    // ExoPlayerのインスタンス作成（remember内で完結させる）
    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("DTVClient/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Connection" to "keep-alive"))

        val mediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory) // ここでDataSourceを注入
            .build().apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(playlistUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
    }

    var showControls by remember { mutableStateOf(true) }
    // 1. 戻るキーの制御
    BackHandler {
        // 戻るボタンが押されたら、即座に前の画面へ戻る
        onBackPressed()
    }

    // 2. 再生状態の監視（リスナーの登録・解除を正しく行う）
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 明示的に「再生中なら消す」「停止中なら出す」
                showControls = !isPlaying
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.stop()    // 再生を止める
            exoPlayer.release() // リソースを完全に解放する
            android.util.Log.d("VideoPlayer", "Player released")
        }
    }

    // 2. 画面表示時にフォーカスを要求
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // シーク中やバッファリング中(STATE_BUFFERING)は以前の状態を維持
                // 再生準備完了(STATE_READY)で、かつ再生指示がある時だけコントロールを隠す
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    showControls = !exoPlayer.playWhenReady
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 決定ボタンでの一時停止/再生に即座に反応
                showControls = !playWhenReady
            }
        }
        exoPlayer.addListener(listener)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester) // ここにフォーカスを当てる
            .focusable() // フォーカスを受け付け可能にする
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            exoPlayer.seekTo(exoPlayer.currentPosition + 30000)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = exoPlayer
                    // 自作UI（PlayerControls）を使うため、純正コントローラーは無効化
                    this.useController = false
                    this.keepScreenOn = true
                    // TVエミュレータでキーイベントを拾うために必要
                    this.focusable = View.NOT_FOCUSABLE
                    this.isFocusableInTouchMode = true
                    this.requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 4. PlayerControls 側の勝手な表示変更を抑制
        PlayerControls(
            exoPlayer = exoPlayer,
            title = program.title,
            isVisible = showControls,
            onVisibilityChanged = {
                // もし一時停止中なら、強制的に表示し続ける
                if (!exoPlayer.isPlaying) {
                    showControls = true
                } else {
                    showControls = it
                }
            }
        )
    }
}