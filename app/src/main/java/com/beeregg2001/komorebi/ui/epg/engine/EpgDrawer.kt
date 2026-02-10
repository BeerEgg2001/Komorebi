package com.beeregg2001.komorebi.ui.epg.engine

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.beeregg2001.komorebi.ui.epg.EpgDataConverter
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.*

data class EpgAnimValues(
    val scrollX: Float,
    val scrollY: Float,
    val animX: Float,
    val animY: Float,
    val animH: Float
)

class EpgDrawer(
    private val config: EpgConfig,
    private val textMeasurer: TextMeasurer
) {
    @RequiresApi(Build.VERSION_CODES.O)
    fun draw(
        drawScope: DrawScope,
        state: EpgState,
        animValues: EpgAnimValues,
        logoPainters: List<Painter>
    ) {
        with(drawScope) {
            val curX = animValues.scrollX
            val curY = animValues.scrollY
            val nowTime = OffsetDateTime.now()

            // 1. 番組表メインエリア
            clipRect(left = config.twPx, top = config.hhAreaPx) {
                state.filledChannelWrappers.forEachIndexed { c, wrapper ->
                    val x = config.twPx + curX + (c * config.cwPx)
                    if (x + config.cwPx < config.twPx || x > size.width) return@forEachIndexed

                    wrapper.programs.forEach { p ->
                        val (sOff, dur) = EpgDataConverter.calculateSafeOffsets(p, state.baseTime)
                        val py = config.hhAreaPx + curY + (sOff / 60f * config.hhPx)
                        val ph = (dur / 60f * config.hhPx)

                        if (py + ph < config.hhAreaPx || py > size.height) return@forEach

                        val isPast = EpgDataConverter.safeParseTime(p.end_time, state.baseTime).isBefore(nowTime)
                        val isEmpty = p.title == "（番組情報なし）"

                        clipRect(left = x, top = config.hhAreaPx, right = x + config.cwPx, bottom = size.height) {
                            drawRect(
                                color = if (isEmpty) config.colorProgramEmpty else if (isPast) config.colorProgramPast else config.colorProgramNormal,
                                topLeft = Offset(x + 1f, py + 1f),
                                size = Size(config.cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                            )

                            if (ph > 20f) {
                                val titleLayout = state.textLayoutCache.getOrPut(p.id) {
                                    textMeasurer.measure(
                                        text = p.title,
                                        style = config.styleTitle.copy(color = if (isPast || isEmpty) Color.Gray else Color.White),
                                        constraints = Constraints(maxWidth = (config.cwPx - 16f).toInt(), maxHeight = (ph - 12f).toInt().coerceAtLeast(0)),
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                val titleY = py + 8f
                                if (titleY + titleLayout.size.height > config.hhAreaPx) {
                                    drawText(titleLayout, topLeft = Offset(x + 10f, titleY))
                                }

                                val titleH = titleLayout.size.height.toFloat()
                                if (!isEmpty && (ph - titleH - 12f) > 20f && !p.description.isNullOrBlank()) {
                                    val descKey = p.id + "d"
                                    val descLayout = state.textLayoutCache.getOrPut(descKey) {
                                        textMeasurer.measure(
                                            text = p.description,
                                            style = config.styleDesc,
                                            constraints = Constraints(maxWidth = (config.cwPx - 16f).toInt(), maxHeight = (ph - titleH - 16f).toInt().coerceAtLeast(0)),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val descY = py + 8f + titleH + 2f
                                    if (descY + descLayout.size.height > config.hhAreaPx) {
                                        drawText(descLayout, topLeft = Offset(x + 10f, descY))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 現在時刻線
            if (nowTime.isAfter(state.baseTime) && nowTime.isBefore(state.limitTime)) {
                val nowOff = Duration.between(state.baseTime, nowTime).toMinutes().toFloat()
                val nowY = config.hhAreaPx + curY + (nowOff / 60f * config.hhPx)
                if (nowY > config.hhAreaPx && nowY < size.height) {
                    drawLine(config.colorCurrentTimeLine, Offset(config.twPx, nowY), Offset(size.width, nowY), strokeWidth = 3f)
                    drawCircle(config.colorCurrentTimeLine, radius = 6f, center = Offset(config.twPx, nowY))
                }
            }

            // 3. フォーカス枠
            if (state.currentFocusedProgram != null) {
                val fx = config.twPx + curX + animValues.animX
                val fy = config.hhAreaPx + curY + animValues.animY
                val fh = animValues.animH

                clipRect(left = 0f, top = config.hhAreaPx, right = size.width, bottom = size.height) {
                    drawRect(config.colorFocusBg, Offset(fx + 1f, fy + 1f), Size(config.cwPx - 2f, fh - 2f))

                    val p = state.currentFocusedProgram!!
                    val cacheKeyF = p.id + "f"
                    val titleLayout = state.textLayoutCache.getOrPut(cacheKeyF) {
                        textMeasurer.measure(text = p.title, style = config.styleTitle, constraints = Constraints(maxWidth = (config.cwPx - 20f).toInt()))
                    }
                    drawText(titleLayout, topLeft = Offset(fx + 10f, fy + 8f))

                    if (p.title != "（番組情報なし）" && !p.description.isNullOrBlank()) {
                        val titleH = titleLayout.size.height.toFloat()
                        val descCacheKey = p.id + "fd"
                        val descLayout = state.textLayoutCache.getOrPut(descCacheKey) {
                            textMeasurer.measure(
                                text = p.description ?: "",
                                style = config.styleDesc,
                                constraints = Constraints(maxWidth = (config.cwPx - 20f).toInt(), maxHeight = (fh - titleH - 25f).toInt().coerceAtLeast(0)),
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        drawText(descLayout, topLeft = Offset(fx + 10f, fy + titleH + 12f))
                    }
                    drawRoundRect(config.colorFocusBorder, Offset(fx - 1f, fy - 1f), Size(config.cwPx + 2f, fh + 2f), CornerRadius(2f), Stroke(4f))
                }
            }

            // 4. 時間軸
            clipRect(left = 0f, top = config.hhAreaPx, right = config.twPx, bottom = size.height) {
                val totalHours = 24 * 14
                for (h in 0..totalHours) {
                    val fy = config.hhAreaPx + curY + (h * config.hhPx)
                    if (fy + config.hhPx < config.hhAreaPx || fy > size.height) continue

                    val hour = (state.baseTime.hour + h) % 24
                    val bgColor = when (hour) { in 4..10 -> config.colorTimeHourEven; in 11..17 -> config.colorTimeHourOdd; else -> config.colorTimeHourNight }
                    drawRect(bgColor, Offset(0f, fy), Size(config.twPx, config.hhPx))

                    val amPmLayout = textMeasurer.measure(if (hour < 12) "AM" else "PM", config.styleAmPm)
                    drawText(amPmLayout, topLeft = Offset((config.twPx - amPmLayout.size.width) / 2, fy + (config.hhPx / 4) - (amPmLayout.size.height / 2)))

                    val hourLayout = textMeasurer.measure(hour.toString(), config.styleTime)
                    drawText(hourLayout, topLeft = Offset((config.twPx - hourLayout.size.width) / 2, fy + (config.hhPx / 2) + ((config.hhPx / 2) - hourLayout.size.height) / 2))
                    drawLine(config.colorGridLine, Offset(0f, fy), Offset(config.twPx, fy), 3f)
                }
            }

            // 5. チャンネルヘッダー
            clipRect(left = config.twPx, top = 0f, right = size.width, bottom = config.hhAreaPx) {
                drawRect(config.colorHeaderBg, Offset(config.twPx, 0f), Size(size.width, config.hhAreaPx))
                state.filledChannelWrappers.forEachIndexed { c, wrapper ->
                    val x = config.twPx + curX + (c * config.cwPx)
                    if (x + config.cwPx < config.twPx || x > size.width) return@forEachIndexed

                    // ロゴ描画サイズ (横長の枠)
                    val logoW = 30.sp.toPx()
                    val logoH = 18.sp.toPx()
                    val numLayout = textMeasurer.measure(wrapper.channel.channel_number ?: "---", config.styleChNum)
                    val startX = x + (config.cwPx - (logoW + 6f + numLayout.size.width)) / 2

                    if (c < logoPainters.size) {
                        val painter = logoPainters[c]
                        translate(startX, 6f) {
                            val srcSize = painter.intrinsicSize
                            // 画像サイズが取得できている場合のみCrop処理を行う
                            if (srcSize.isSpecified && srcSize.width > 0 && srcSize.height > 0) {
                                // ContentScale.Crop 相当の計算
                                // 描画枠(logoW, logoH)を埋めるために必要な倍率を計算（大きい方に合わせる）
                                val scale = maxOf(logoW / srcSize.width, logoH / srcSize.height)

                                val scaledW = srcSize.width * scale
                                val scaledH = srcSize.height * scale

                                // 中心に配置するためのオフセット
                                val dx = (logoW - scaledW) / 2
                                val dy = (logoH - scaledH) / 2

                                // 描画枠でクリップして描画
                                clipRect(0f, 0f, logoW, logoH) {
                                    translate(dx, dy) {
                                        with(painter) { draw(Size(scaledW, scaledH)) }
                                    }
                                }
                            } else {
                                // サイズ不明時やロード前は枠に合わせて描画（引き伸ばし）
                                with(painter) { draw(Size(logoW, logoH)) }
                            }
                        }
                    }
                    drawText(numLayout, topLeft = Offset(startX + logoW + 6f, 6f + (logoH - numLayout.size.height) / 2))

                    val nameLayout = textMeasurer.measure(wrapper.channel.name, config.styleChName, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (config.cwPx - 16f).toInt()))
                    drawText(nameLayout, topLeft = Offset(x + (config.cwPx - nameLayout.size.width) / 2, 6f + logoH + 2f))
                    drawLine(config.colorGridLine, Offset(x, 0f), Offset(x, config.hhAreaPx), strokeWidth = 2f)
                }
            }

            // 6. 日付ラベル
            drawRect(config.colorBg, Offset.Zero, Size(config.twPx, config.hhAreaPx))
            val disp = state.baseTime.plusMinutes((-curY / config.hhPx * 60).toLong().coerceAtLeast(0))
            val dateStr = "${disp.monthValue}/${disp.dayOfMonth}"
            val dayStr = "(${disp.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPANESE)})"
            val dayColor = when (disp.dayOfWeek.value) { 7 -> Color(0xFFFF5252); 6 -> Color(0xFF448AFF); else -> Color.White }

            val dateLayout = textMeasurer.measure(
                text = AnnotatedString(text = "$dateStr\n$dayStr", spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.White, fontSize = 11.sp), 0, dateStr.length), AnnotatedString.Range(SpanStyle(color = dayColor, fontSize = 11.sp), dateStr.length + 1, dateStr.length + 1 + dayStr.length))),
                style = config.styleDateLabel.copy(textAlign = TextAlign.Center, lineHeight = 14.sp),
                constraints = Constraints(maxWidth = config.twPx.toInt())
            )
            drawText(dateLayout, topLeft = Offset((config.twPx - dateLayout.size.width) / 2, (config.hhAreaPx - dateLayout.size.height) / 2))

            drawLine(config.colorGridLine, Offset(config.twPx, 0f), Offset(config.twPx, size.height), strokeWidth = 4f)
            drawLine(config.colorGridLine, Offset(0f, config.hhAreaPx), Offset(size.width, config.hhAreaPx), strokeWidth = 4f)
        }
    }
}