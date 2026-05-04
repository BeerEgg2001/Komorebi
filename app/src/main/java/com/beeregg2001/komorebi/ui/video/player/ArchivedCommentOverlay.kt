package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.live.LiveCommentOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import android.graphics.Color as AndroidColor
import kotlin.math.abs

private const val TAG = "ArchivedCommentOverlay"

@Composable
fun ArchivedCommentOverlay(
    modifier: Modifier = Modifier,
    comments: List<ArchivedComment>,
    currentPositionProvider: () -> Long,
    isPlaying: Boolean,
    isCommentEnabled: Boolean,
    commentSpeed: Float,
    commentFontSizeScale: Float,
    commentOpacity: Float,
    commentMaxLines: Int,
    useSoftwareRendering: Boolean = false
) {
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    // UIスレッドでしか取得できない画面密度(density)を事前に計算しておく
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }

    LaunchedEffect(isPlaying, isCommentEnabled) {
        danmakuViewRef.value?.let { view ->
            if (view.isPrepared) {
                if (isPlaying && isCommentEnabled) {
                    view.resume()
                } else {
                    view.pause()
                }
            }
        }
    }

    // コメント同期・描画予約ロジック
    LaunchedEffect(isPlaying, isCommentEnabled, comments.size) {
        if (!isCommentEnabled || comments.isEmpty()) return@LaunchedEffect

        var currentIndex = 0
        var lastPlayerSec = withContext(Dispatchers.Main) { currentPositionProvider() / 1000.0 }
        val lookAheadSec = 2.0 // 2秒先まで先読みして描画予約する

        while (isActive) {
            if (isPlaying) {
                // ★ 修正1: ExoPlayerへのアクセス(currentPositionProvider)は必ずメインスレッドで行う
                val currentSec =
                    withContext(Dispatchers.Main) { currentPositionProvider() / 1000.0 }

                // ★ 修正2: 重い検索処理やインスタンス化はバックグラウンドスレッドで行う
                withContext(Dispatchers.Default) {
                    // シーク検知: 現在位置と最後に処理した時間が1.5秒以上乖離している場合
                    if (abs(currentSec - lastPlayerSec) > 1.5) {
                        danmakuViewRef.value?.removeAllDanmakus(true)
                        // バイナリサーチ的に次のインデックスを取得
                        currentIndex = comments.indexOfFirst { it.time >= currentSec }
                            .let { if (it == -1) comments.size else it }
                    }

                    danmakuViewRef.value?.let { view ->
                        if (view.isPrepared) {
                            val targetTimeSec = currentSec + lookAheadSec
                            val danmakusToAdd = mutableListOf<BaseDanmaku>()

                            while (currentIndex < comments.size) {
                                val comment = comments[currentIndex]
                                if (comment.time > targetTimeSec) break // 2秒以上先ならループを抜ける

                                // シーク直後の過去すぎるコメントを捨てる
                                if (comment.time >= currentSec - 0.5) {
                                    val d =
                                        createDanmaku(view, comment, commentFontSizeScale, density)
                                    if (d != null) {
                                        // 現在時刻との差分を計算し、DanmakuViewの内部時計で正確な表示時刻を予約
                                        val futureMs = ((comment.time - currentSec) * 1000).toLong()
                                        d.setTime(view.currentTime + futureMs)
                                        danmakusToAdd.add(d)
                                    }
                                }
                                currentIndex++
                            }

                            // コメントの追加(addDanmaku)は内部的にスレッドセーフなのでバックグラウンドから呼んでもOK
                            danmakusToAdd.forEach { view.addDanmaku(it) }
                        }
                    }
                }
                lastPlayerSec = currentSec
            }
            delay(500)
        }
    }

    LiveCommentOverlay(
        modifier = modifier,
        useSoftwareRendering = useSoftwareRendering,
        speed = commentSpeed,
        opacity = commentOpacity,
        maxLines = commentMaxLines,
        onViewCreated = { view ->
            danmakuViewRef.value = view
            if (!isPlaying || !isCommentEnabled) view.pause()
        }
    )
}

/**
 * コメントのインスタンスを作成する
 * UIスレッド外から呼ばれるため、View(Context)への直接アクセスを避けて引数からdensityを受け取る
 */
private fun createDanmaku(
    view: IDanmakuView,
    comment: ArchivedComment,
    fontSizeScale: Float,
    density: Float
): BaseDanmaku? {
    val danmaku =
        view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL) ?: return null
    danmaku.text = comment.text
    danmaku.padding = 5

    // 引数で受け取った density を使って計算する
    danmaku.textSize = (32f * fontSizeScale) * density

    try {
        danmaku.textColor = AndroidColor.parseColor(comment.color)
    } catch (e: Exception) {
        danmaku.textColor = AndroidColor.WHITE
    }

    danmaku.textShadowColor = AndroidColor.BLACK
    return danmaku
}