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
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

@Composable
fun LiveCommentOverlay(
    modifier: Modifier = Modifier,
    useSoftwareRendering: Boolean = false, // ★ソフトウェア描画切り替え用
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
            setScrollSpeedFactor(1.0f)
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

                // ★ レンダリングモードの設定
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

                // ★重要: Viewがシステムにアタッチされ、サイズが確定してから初期化を実行する
                post {
                    prepare(parser, danmakuContext)
                }

                onViewCreated(this)
            }
        },
        update = { view ->
            // レンダリングモードの動的更新
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