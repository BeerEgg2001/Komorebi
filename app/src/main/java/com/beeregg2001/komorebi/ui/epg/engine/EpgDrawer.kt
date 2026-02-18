package com.beeregg2001.komorebi.ui.epg.engine

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.util.EpgUtils
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
        logoPainters: List<Painter>,
        isGridFocused: Boolean,
        reserveMap: Map<String, ReserveItem>,
        clockPainter: Painter
    ) {
        with(drawScope) {
            val curX = animValues.scrollX
            val curY = animValues.scrollY
            val nowTime = OffsetDateTime.now()
            val nowMs = System.currentTimeMillis()

            // 1. 番組表メインエリア
            clipRect(left = config.twPx, top = config.hhAreaPx) {
                val startCol = ((-curX) / config.cwPx).toInt().coerceAtLeast(0)
                val endCol = ((-curX + size.width - config.twPx) / config.cwPx).toInt().coerceAtMost(state.uiChannels.lastIndex)

                if (startCol <= endCol && state.uiChannels.isNotEmpty()) {
                    val visibleTopY = -curY - config.hhAreaPx
                    val visibleBottomY = visibleTopY + size.height

                    for (c in startCol..endCol) {
                        val uiChannel = state.uiChannels[c]
                        val x = config.twPx + curX + (c * config.cwPx)

                        for (uiProg in uiChannel.uiPrograms) {
                            if (uiProg.topY + uiProg.height < visibleTopY) continue
                            if (uiProg.topY > visibleBottomY) break

                            val py = config.hhAreaPx + curY + uiProg.topY
                            val ph = uiProg.height
                            val isPast = uiProg.endTimeMs < nowMs
                            val isEmpty = uiProg.isEmpty
                            val p = uiProg.program

                            val reserve = reserveMap[p.id]

                            val isPartial = reserve != null && (
                                    reserve.recordingAvailability == "Partial" ||
                                            reserve.recordingAvailability == "Partially"
                                    )
                            val isDuplicated = reserve != null && (
                                    reserve.recordingAvailability == "None" ||
                                            reserve.recordingAvailability.equals("unavailable", ignoreCase = true)
                                    )

                            clipRect(left = x, top = config.hhAreaPx, right = x + config.cwPx, bottom = size.height) {
                                val bgColor = when {
                                    isPartial -> config.colorReserveBorderPartial.copy(alpha = 0.2f)
                                    isDuplicated -> config.colorReserveBgDuplicated
                                    isEmpty -> config.colorProgramEmpty
                                    isPast -> config.colorProgramPast
                                    else -> config.colorProgramNormal
                                }

                                drawRect(
                                    color = bgColor,
                                    topLeft = Offset(x + 1f, py + 1f),
                                    size = Size(config.cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                                )

                                if (!isEmpty) {
                                    val majorGenre = p.genres?.firstOrNull()?.major
                                    val genreColor = EpgUtils.getGenreColor(majorGenre)
                                    drawRect(
                                        color = genreColor,
                                        topLeft = Offset(x + 1f, py + 1f),
                                        size = Size(6f, (ph - 2f).coerceAtLeast(0f))
                                    )
                                }

                                if (ph > 20f) {
                                    val iconSize = 12.sp.toPx()
                                    val iconPadding = 2.dp.toPx()
                                    val iconOffset = if (reserve != null) iconSize + iconPadding else 0f

                                    val titleLayout = state.textLayoutCache.getOrPut(p.id) {
                                        textMeasurer.measure(
                                            text = p.title,
                                            style = config.styleTitle.copy(color = if (isPast || isEmpty) Color.Gray else Color.White),
                                            constraints = Constraints(maxWidth = (config.cwPx - 16f - iconOffset).toInt().coerceAtLeast(0), maxHeight = (ph - 12f).toInt().coerceAtLeast(0)),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val titleH = titleLayout.size.height.toFloat()

                                    var descLayout: TextLayoutResult? = null
                                    var descH = 0f
                                    if (!isEmpty && (ph - titleH - 12f) > 20f && !p.description.isNullOrBlank()) {
                                        val descKey = p.id + "d"
                                        descLayout = state.textLayoutCache.getOrPut(descKey) {
                                            textMeasurer.measure(
                                                text = p.description,
                                                style = config.styleDesc,
                                                constraints = Constraints(maxWidth = (config.cwPx - 16f).toInt(), maxHeight = (ph - titleH - 16f).toInt().coerceAtLeast(0)),
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        descH = descLayout.size.height.toFloat()
                                    }

                                    val textTotalH = titleH + if (descLayout != null) descH + 2f else 0f
                                    val baseShiftY = maxOf(0f, config.hhAreaPx - py)
                                    val maxShiftY = maxOf(0f, ph - 16f - textTotalH)
                                    val shiftY = minOf(baseShiftY, maxShiftY)

                                    val titleY = py + 8f + shiftY

                                    if (reserve != null) {
                                        val iconY = titleY + (titleH - iconSize) / 2

                                        // ★修正: 録画中は赤丸、それ以外は時計
                                        if (reserve.isRecordingInProgress) {
                                            drawCircle(
                                                color = Color(0xFFE53935), // 鮮やかな赤
                                                radius = iconSize / 2,
                                                center = Offset(x + 10f + iconSize / 2, iconY + iconSize / 2)
                                            )
                                        } else {
                                            val clockColor = if (isPartial) config.colorReserveBorderPartial else Color.Red
                                            translate(left = x + 10f, top = iconY) {
                                                with(clockPainter) {
                                                    draw(size = Size(iconSize, iconSize), colorFilter = ColorFilter.tint(clockColor))
                                                }
                                            }
                                        }
                                    }

                                    if (titleY + titleH > config.hhAreaPx) {
                                        drawText(titleLayout, topLeft = Offset(x + 10f + iconOffset, titleY))
                                    }

                                    if (descLayout != null) {
                                        val descY = titleY + titleH + 2f
                                        if (descY + descH > config.hhAreaPx) {
                                            drawText(descLayout, topLeft = Offset(x + 10f, descY))
                                        }
                                    }
                                }

                                if (reserve != null) {
                                    val borderColor = if (isPartial) {
                                        config.colorReserveBorderPartial
                                    } else {
                                        config.colorReserveBorder
                                    }

                                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)

                                    drawRoundRect(
                                        color = borderColor,
                                        topLeft = Offset(x + 2f, py + 2f),
                                        size = Size(config.cwPx - 4f, (ph - 4f).coerceAtLeast(0f)),
                                        cornerRadius = CornerRadius(4f),
                                        style = Stroke(width = 5f, pathEffect = dashEffect)
                                    )
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
            if (state.currentFocusedProgram != null && isGridFocused) {
                val fx = config.twPx + curX + animValues.animX
                val fy = config.hhAreaPx + curY + animValues.animY
                val fh = animValues.animH

                clipRect(left = 0f, top = config.hhAreaPx, right = size.width, bottom = size.height) {
                    val p = state.currentFocusedProgram!!
                    val reserve = reserveMap[p.id]

                    val isPartial = reserve != null && (
                            reserve.recordingAvailability == "Partial" ||
                                    reserve.recordingAvailability == "Partially"
                            )
                    val isDuplicated = reserve != null && (
                            reserve.recordingAvailability == "None" ||
                                    reserve.recordingAvailability.equals("unavailable", ignoreCase = true)
                            )

                    // 背景色決定 (不透明色を合成)
                    val focusBgColor = when {
                        isPartial -> config.colorReserveBorderPartial.copy(alpha = 0.2f).compositeOver(Color.Black)
                        isDuplicated -> config.colorReserveBgDuplicated
                        else -> config.colorFocusBg
                    }

                    drawRect(focusBgColor, Offset(fx + 1f, fy + 1f), Size(config.cwPx - 2f, fh - 2f))

                    if (p.title != "（番組情報なし）") {
                        val majorGenre = p.genres?.firstOrNull()?.major
                        val genreColor = EpgUtils.getGenreColor(majorGenre)
                        drawRect(
                            color = genreColor,
                            topLeft = Offset(fx + 1f, fy + 1f),
                            size = Size(6f, (fh - 2f).coerceAtLeast(0f))
                        )
                    }

                    val iconSize = 12.sp.toPx()
                    val iconPadding = 2.dp.toPx()
                    val iconOffset = if (reserve != null) iconSize + iconPadding else 0f

                    val cacheKeyF = p.id + "f"
                    val titleLayout = state.textLayoutCache.getOrPut(cacheKeyF) {
                        textMeasurer.measure(
                            text = p.title,
                            style = config.styleTitle,
                            constraints = Constraints(maxWidth = (config.cwPx - 20f - iconOffset).toInt().coerceAtLeast(0))
                        )
                    }
                    val titleH = titleLayout.size.height.toFloat()

                    var descLayout: TextLayoutResult? = null
                    var descH = 0f
                    if (p.title != "（番組情報なし）" && !p.description.isNullOrBlank()) {
                        val descCacheKey = p.id + "fd"
                        descLayout = state.textLayoutCache.getOrPut(descCacheKey) {
                            textMeasurer.measure(
                                text = p.description ?: "",
                                style = config.styleDesc,
                                constraints = Constraints(maxWidth = (config.cwPx - 20f).toInt(), maxHeight = (fh - titleH - 25f).toInt().coerceAtLeast(0)),
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        descH = descLayout.size.height.toFloat()
                    }

                    val textTotalH = titleH + if (descLayout != null) descH + 4f else 0f
                    val baseShiftY = maxOf(0f, config.hhAreaPx - fy)
                    val maxShiftY = maxOf(0f, fh - 16f - textTotalH)
                    val shiftY = minOf(baseShiftY, maxShiftY)

                    val titleY = fy + 8f + shiftY

                    if (reserve != null) {
                        val iconY = titleY + (titleH - iconSize) / 2

                        // ★修正: 録画中は赤丸、それ以外は時計
                        if (reserve.isRecordingInProgress) {
                            drawCircle(
                                color = Color(0xFFE53935),
                                radius = iconSize / 2,
                                center = Offset(fx + 10f + iconSize / 2, iconY + iconSize / 2)
                            )
                        } else {
                            val clockColor = if (isPartial) config.colorReserveBorderPartial else Color.Red
                            translate(left = fx + 10f, top = iconY) {
                                with(clockPainter) {
                                    draw(size = Size(iconSize, iconSize), colorFilter = ColorFilter.tint(clockColor))
                                }
                            }
                        }
                    }

                    drawText(titleLayout, topLeft = Offset(fx + 10f + iconOffset, titleY))

                    if (descLayout != null) {
                        val descY = titleY + titleH + 4f
                        drawText(descLayout, topLeft = Offset(fx + 10f, descY))
                    }

                    if (reserve != null) {
                        val borderColor = if (isPartial) {
                            config.colorReserveBorderPartial
                        } else {
                            config.colorReserveBorder
                        }
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)

                        drawRoundRect(
                            color = borderColor,
                            topLeft = Offset(fx + 2f, fy + 2f),
                            size = Size(config.cwPx - 4f, (fh - 4f).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(4f),
                            style = Stroke(width = 5f, pathEffect = dashEffect)
                        )
                    }

                    drawRoundRect(config.colorFocusBorder, Offset(fx - 1f, fy - 1f), Size(config.cwPx + 2f, fh + 2f), CornerRadius(2f), Stroke(4f))
                }
            }

            // 4. 時間軸 & 5. チャンネルヘッダー & 6. 日付ラベル (変更なし)
            clipRect(left = 0f, top = config.hhAreaPx, right = config.twPx, bottom = size.height) {
                val totalHours = 24 * 14
                val startHour = (-curY / config.hhPx).toInt().coerceAtLeast(0)
                val endHour = ((-curY + size.height - config.hhAreaPx) / config.hhPx).toInt().coerceAtMost(totalHours)

                for (h in startHour..endHour) {
                    val fy = config.hhAreaPx + curY + (h * config.hhPx)
                    val hour = (state.baseTime.hour + h) % 24
                    val bgColor = when (hour) { in 4..10 -> config.colorTimeHourEven; in 11..17 -> config.colorTimeHourOdd; else -> config.colorTimeHourNight }
                    drawRect(bgColor, Offset(0f, fy), Size(config.twPx, config.hhPx))

                    val amPmLayout = textMeasurer.measure(if (hour < 12) "AM" else "PM", config.styleAmPm)
                    val hourLayout = textMeasurer.measure(hour.toString(), config.styleTime)
                    val spacing = 2f
                    val totalTextHeight = amPmLayout.size.height + hourLayout.size.height + spacing
                    val startY = fy + (config.hhPx - totalTextHeight) / 2

                    drawText(amPmLayout, topLeft = Offset((config.twPx - amPmLayout.size.width) / 2, startY))
                    drawText(hourLayout, topLeft = Offset((config.twPx - hourLayout.size.width) / 2, startY + amPmLayout.size.height + spacing))
                    drawLine(config.colorGridLine, Offset(0f, fy), Offset(config.twPx, fy), 3f)
                }
            }

            clipRect(left = config.twPx, top = 0f, right = size.width, bottom = config.hhAreaPx) {
                drawRect(config.colorHeaderBg, Offset(config.twPx, 0f), Size(size.width, config.hhAreaPx))

                val startCol = ((-curX) / config.cwPx).toInt().coerceAtLeast(0)
                val endCol = ((-curX + size.width - config.twPx) / config.cwPx).toInt().coerceAtMost(state.uiChannels.lastIndex)

                if (startCol <= endCol && state.uiChannels.isNotEmpty()) {
                    for (c in startCol..endCol) {
                        val wrapper = state.uiChannels[c].wrapper
                        val x = config.twPx + curX + (c * config.cwPx)

                        val logoW = 30.sp.toPx()
                        val logoH = 18.sp.toPx()
                        val numLayout = textMeasurer.measure(wrapper.channel.channel_number ?: "---", config.styleChNum)
                        val startX = x + (config.cwPx - (logoW + 6f + numLayout.size.width)) / 2

                        if (c < logoPainters.size) {
                            val painter = logoPainters[c]
                            translate(startX, 6f) {
                                val srcSize = painter.intrinsicSize
                                if (srcSize.isSpecified && srcSize.width > 0 && srcSize.height > 0) {
                                    val scale = maxOf(logoW / srcSize.width, logoH / srcSize.height)
                                    val scaledW = srcSize.width * scale
                                    val scaledH = srcSize.height * scale
                                    val dx = (logoW - scaledW) / 2
                                    val dy = (logoH - scaledH) / 2
                                    clipRect(0f, 0f, logoW, logoH) {
                                        translate(dx, dy) { with(painter) { draw(Size(scaledW, scaledH)) } }
                                    }
                                } else {
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
            }

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