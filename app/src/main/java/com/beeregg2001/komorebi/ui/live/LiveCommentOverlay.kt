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
    // 設定項目
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
        }
    }

    val parser = remember {
        object : BaseDanmakuParser() {
            override fun parse(): IDanmakus = Danmakus()
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

                setLayerType(if (useSoftwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE, null)
                enableDanmakuDrawingCache(true)

                setCallback(object : DrawHandler.Callback {
                    override fun prepared() {
                        start()
                    }
                    override fun updateTimer(timer: DanmakuTimer?) {}
                    override fun danmakuShown(danmaku: master.flame.danmaku.danmaku.model.BaseDanmaku?) {}
                    override fun drawingFinished() {}
                })

                post {
                    // 初期設定の適用
                    danmakuContext.setScrollSpeedFactor(speed)
                    danmakuContext.setDanmakuTransparency(opacity)

                    // 行数制限の適用 (Map形式で指定)
                    if (maxLines > 0) {
                        val maxLinesMap = mapOf(BaseDanmaku.TYPE_SCROLL_RL to maxLines)
                        danmakuContext.setMaximumLines(maxLinesMap)
                    } else {
                        danmakuContext.setMaximumLines(null)
                    }

                    prepare(parser, danmakuContext)
                }

                onViewCreated(this)
            }
        },
        update = { view ->
            // 設定変更をリアルタイムに反映
            danmakuContext.setScrollSpeedFactor(speed)
            danmakuContext.setDanmakuTransparency(opacity)

            // 行数制限の動的更新
            if (maxLines > 0) {
                val maxLinesMap = mapOf(BaseDanmaku.TYPE_SCROLL_RL to maxLines)
                danmakuContext.setMaximumLines(maxLinesMap)
            } else {
                danmakuContext.setMaximumLines(null)
            }

            val targetType = if (useSoftwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE
            if (view.layerType != targetType) {
                view.setLayerType(targetType, null)
            }

            if (view.isPrepared && !view.isShown) {
                view.start()
            }
        },
        onRelease = { view ->
            view.stop()
            view.release()
        }
    )
}