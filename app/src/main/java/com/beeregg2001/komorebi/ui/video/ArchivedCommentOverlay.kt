package com.beeregg2001.komorebi.ui.video

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.live.LiveCommentOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    // DanmakuViewの実体保持
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    // 最後にコメントを放出した時間を記録
    var lastEmittedTime by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(isPlaying, isCommentEnabled) {
        Log.i(TAG, "State Changed -> isPlaying: $isPlaying, isCommentEnabled: $isCommentEnabled")
        danmakuViewRef.value?.let { view ->
            if (view.isPrepared) {
                if (isPlaying && isCommentEnabled) {
                    Log.i(TAG, "Resuming DanmakuView")
                    view.resume()
                } else {
                    Log.i(TAG, "Pausing DanmakuView")
                    view.pause()
                }
            }
        }
    }

    // コメント同期ロジック
    LaunchedEffect(isPlaying, isCommentEnabled, comments.size) {
        Log.i(TAG, "Sync loop started. Comments count: ${comments.size}")
        if (!isCommentEnabled || comments.isEmpty()) return@LaunchedEffect

        while (isActive) {
            if (isPlaying) {
                val currentSec = currentPositionProvider() / 1000.0

                // シーク検知 (時間が2秒以上ジャンプした場合)
                if (abs(currentSec - lastEmittedTime) > 2.0) {
                    Log.i(TAG, "Seek detected! Jumped from $lastEmittedTime to $currentSec")
                    lastEmittedTime = (currentSec - 0.2).coerceAtLeast(0.0)
                    danmakuViewRef.value?.removeAllDanmakus(true) // 画面の古いコメントを消去
                }

                if (currentSec > lastEmittedTime) {
                    var addedCount = 0
                    comments.forEach { comment ->
                        if (comment.time > lastEmittedTime && comment.time <= currentSec) {
                            danmakuViewRef.value?.let { view ->
                                (view as? android.view.View)?.post {
                                    if (!view.isPrepared) return@post
                                    addDanmakuToView(view, comment, commentFontSizeScale)
                                }
                            }
                            addedCount++
                        }
                    }
                    if (addedCount > 0) {
                        Log.i(TAG, "Added $addedCount comments to view at $currentSec sec")
                    }
                    lastEmittedTime = currentSec
                }
            }
            delay(200) // 0.2秒間隔で同期
        }
    }

    // 既存のLiveCommentOverlayを再利用して描画
    LiveCommentOverlay(
        modifier = modifier,
        useSoftwareRendering = useSoftwareRendering,
        speed = commentSpeed,
        opacity = commentOpacity,
        maxLines = commentMaxLines,
        onViewCreated = { view ->
            Log.i(TAG, "DanmakuView created and prepared")
            danmakuViewRef.value = view
            if (!isPlaying || !isCommentEnabled) view.pause() // 初期表示時に止まっていたら止める
        }
    )
}

/**
 * DanmakuViewに個別のコメントを追加するユーティリティ
 */
private fun addDanmakuToView(view: IDanmakuView, comment: ArchivedComment, fontSizeScale: Float) {
    val danmaku = view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL) ?: return
    danmaku.text = comment.text
    danmaku.padding = 5

    val viewContext = (view as? android.view.View)?.context ?: return
    val density = viewContext.resources.displayMetrics.density

    danmaku.textSize = (32f * fontSizeScale) * density

    try {
        danmaku.textColor = AndroidColor.parseColor(comment.color)
    } catch (e: Exception) {
        danmaku.textColor = AndroidColor.WHITE
    }

    danmaku.textShadowColor = AndroidColor.BLACK
    danmaku.setTime(view.currentTime + 10)
    view.addDanmaku(danmaku)
}