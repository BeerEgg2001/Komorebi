package com.beeregg2001.komorebi.ui.video

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.live.LiveCommentOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import android.graphics.Color as AndroidColor

@Composable
fun ArchivedCommentOverlay(
    modifier: Modifier = Modifier,
    comments: List<ArchivedComment>,
    currentPositionMs: Long,
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

    // コメント同期ロジック (VideoPlayerScreenから移譲)
    LaunchedEffect(isPlaying, isCommentEnabled, comments.size) {
        if (!isCommentEnabled || comments.isEmpty()) return@LaunchedEffect

        while (isActive) {
            if (isPlaying) {
                val currentSec = currentPositionMs / 1000.0

                // シーク判定 (3秒以上の乖離、または逆行)
                if (Math.abs(currentSec - lastEmittedTime) > 3.0 || currentSec < lastEmittedTime) {
                    lastEmittedTime = currentSec
                } else {
                    // 前回チェック時から現在までのコメントを抽出
                    val commentsToEmit = comments.filter { it.time > lastEmittedTime && it.time <= currentSec }

                    commentsToEmit.forEach { comment ->
                        danmakuViewRef.value?.let { view ->
                            (view as? android.view.View)?.post {
                                if (!view.isPrepared) return@post
                                addDanmakuToView(view, comment, commentFontSizeScale)
                            }
                        }
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
        onViewCreated = { view -> danmakuViewRef.value = view }
    )
}

/**
 * DanmakuViewに個別のコメントを追加するユーティリティ
 */
private fun addDanmakuToView(view: IDanmakuView, comment: ArchivedComment, fontSizeScale: Float) {
    val danmaku = view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL) ?: return
    danmaku.text = comment.text
    danmaku.padding = 5

    // IDanmakuViewをViewにキャストしてcontextを取得する
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