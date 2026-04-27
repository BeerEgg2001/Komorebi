package com.beeregg2001.komorebi.ui.live

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

@Composable
fun LiveCommentOverlay(
    modifier: Modifier = Modifier,
    useSoftwareRendering: Boolean = false,
    speed: Float = 1.0f,
    opacity: Float = 1.0f,
    maxLines: Int = 0,
    onViewCreated: (IDanmakuView) -> Unit
) {
    val context = LocalContext.current

    val customTypeface = remember {
        runCatching {
            Typeface.createFromAsset(context.assets, "fonts/notosansjp_bold.ttf")
        }.getOrDefault(Typeface.create("sans-serif", Typeface.BOLD))
    }

    val danmakuContext = remember {
        DanmakuContext.create().apply {
            setDanmakuStyle(1, 8.0f)
            setTypeface(customTypeface)
            setDanmakuBold(true)
            setDuplicateMergingEnabled(false)

            // ★ パフォーマンス究極チューニング (エラーになる非公開APIを削除)
            setDanmakuSync(null) // デフォルトの時計同期アルゴリズムを使用

            // キャッシュのメモリ管理を最適化（カクツキの原因であるBitmap確保を裏で行う）
            setCacheStuffer(
                master.flame.danmaku.danmaku.model.android.SimpleTextCacheStuffer(),
                null
            )

            // 最大表示数を制限して極端な負荷スパイクを防ぐ
            setMaximumVisibleSizeInScreen(200)

            val overlappingEnablePair = mapOf(
                BaseDanmaku.TYPE_SCROLL_RL to true,
                BaseDanmaku.TYPE_FIX_TOP to true
            )
            preventOverlapping(overlappingEnablePair)
        }
    }

    val parser = remember {
        object : BaseDanmakuParser() {
            override fun parse(): IDanmakus = Danmakus()
        }
    }

    LaunchedEffect(speed, opacity, maxLines) {
        val speedFactor = if (speed > 0f) 1.0f / speed else 1.0f
        danmakuContext.setScrollSpeedFactor(speedFactor)
        danmakuContext.setDanmakuTransparency(opacity)

        if (maxLines > 0) {
            val maxLinesMap = mapOf(BaseDanmaku.TYPE_SCROLL_RL to maxLines)
            danmakuContext.setMaximumLines(maxLinesMap)
        } else {
            danmakuContext.setMaximumLines(emptyMap())
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DanmakuView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)

                // ★ レイヤータイプをハードウェアアクセラレーションに強制
                val initialLayerType =
                    if (useSoftwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE
                setLayerType(initialLayerType, null)

                // ★ 描画キャッシュを有効化（これで文字列->画像の変換処理が裏で行われる）
                enableDanmakuDrawingCache(true)

                setCallback(object : DrawHandler.Callback {
                    override fun prepared() {
                        start()
                    }

                    override fun updateTimer(timer: DanmakuTimer?) {}
                    override fun danmakuShown(danmaku: BaseDanmaku?) {}
                    override fun drawingFinished() {}
                })

                post {
                    prepare(parser, danmakuContext)
                }

                onViewCreated(this)
            }
        },
        update = { view ->
            val targetType =
                if (useSoftwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE
            if (view.layerType != targetType) {
                view.setLayerType(targetType, null)
            }
        },
        onRelease = { view ->
            view.stop()
            view.release()
        }
    )
}