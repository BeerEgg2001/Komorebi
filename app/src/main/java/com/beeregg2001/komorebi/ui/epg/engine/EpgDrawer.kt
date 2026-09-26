package com.beeregg2001.komorebi.ui.epg.engine

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
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
    // ★ 最適化: 毎フレーム生成されていたオブジェクトをインスタンス field へ退避する。
    //   dashPathEffect は予約枠のある番組ごと・毎フレーム生成されていた。
    private val reserveDashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)

    // ★ 最適化: フォーカス中番組の終了時刻の 1 件メモ。
    //   従来はフォーカス枠の描画で毎フレーム OffsetDateTime.parse() を呼んでおり、
    //   フォーカス移動アニメーション中に最も重い処理になっていた。
    //   描画はメインスレッド単独なので単純な 1 件キャッシュで十分。
    private var focusEndTimeKey: String? = null
    private var focusEndTimeMs: Long = 0L

    // ★ 最適化: baseTime / limitTime のエポックミリ秒メモ。
    //   現在時刻線の判定で毎フレーム OffsetDateTime.now() と Duration.between を
    //   呼んでいたのを、ミリ秒同士の比較・減算に置き換えるため。
    private var cachedBaseTime: OffsetDateTime? = null
    private var cachedBaseTimeMs: Long = 0L
    private var cachedLimitTime: OffsetDateTime? = null
    private var cachedLimitTimeMs: Long = 0L

    private fun baseTimeMs(baseTime: OffsetDateTime): Long {
        if (cachedBaseTime != baseTime) {
            cachedBaseTime = baseTime
            cachedBaseTimeMs = baseTime.toInstant().toEpochMilli()
        }
        return cachedBaseTimeMs
    }

    private fun limitTimeMs(limitTime: OffsetDateTime): Long {
        if (cachedLimitTime != limitTime) {
            cachedLimitTime = limitTime
            cachedLimitTimeMs = limitTime.toInstant().toEpochMilli()
        }
        return cachedLimitTimeMs
    }

    private fun focusProgramEndMs(endTime: String?): Long {
        val key = endTime ?: return 0L
        if (focusEndTimeKey != key) {
            focusEndTimeKey = key
            focusEndTimeMs = try {
                OffsetDateTime.parse(key).toInstant().toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }
        return focusEndTimeMs
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun draw(
        drawScope: DrawScope,
        state: EpgState,
        animValues: EpgAnimValues,
        logoPainters: List<Painter>,
        isGridFocused: Boolean,
        reserveMap: Map<String, ReserveItem>,
        clockPainter: Painter,
        timeFormat: String // ★ 追加: SettingsViewModelから渡される時間フォーマット("12H" or "24H")
    ) {
        with(drawScope) {
            val curX = animValues.scrollX
            val curY = animValues.scrollY
            // ★ 最適化: 毎フレームの OffsetDateTime.now() を廃止し、ミリ秒だけで判定する
            val nowMs = System.currentTimeMillis()

            val isDarkTheme = config.colorBg.luminance() < 0.5f

            // ==========================================
            // 1. 番組表メインエリア (Program Grid)
            // ==========================================
            clipRect(left = config.twPx, top = config.hhAreaPx) {
                val startCol = ((-curX) / config.cwPx).toInt().coerceAtLeast(0)
                val endCol = ((-curX + size.width - config.twPx) / config.cwPx).toInt()
                    .coerceAtMost(state.uiChannels.lastIndex)

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
                            val isPartial =
                                reserve != null && (reserve.recordingAvailability == "Partial" || reserve.recordingAvailability == "Partially")
                            val isDuplicated =
                                reserve != null && (reserve.recordingAvailability == "None" || reserve.recordingAvailability.equals(
                                    "unavailable",
                                    ignoreCase = true
                                ))

                            clipRect(
                                left = x,
                                top = config.hhAreaPx,
                                right = x + config.cwPx,
                                bottom = size.height
                            ) {
                                val bgColor = when {
                                    isPartial -> config.colorReserveBorderPartial.copy(alpha = 0.2f)
                                    isDuplicated -> config.colorReserveBgDuplicated
                                    isEmpty -> config.colorProgramEmpty
                                    isPast -> config.colorProgramPast
                                    else -> config.colorProgramNormal
                                }

                                if (!isEmpty) {
                                    val majorGenre = p.genres?.firstOrNull()?.major
                                    val genreColor = EpgUtils.getGenreColor(majorGenre)

                                    val startAlpha =
                                        if (isPast) (if (isDarkTheme) 0.1f else 0.03f) else (if (isDarkTheme) 0.2f else 0.06f)
                                    val endAlpha =
                                        if (isPast) 0.0f else (if (isDarkTheme) 0.05f else 0.01f)

                                    val baseColor = bgColor.compositeOver(config.colorBg)
                                    val startColor =
                                        genreColor.copy(alpha = startAlpha).compositeOver(baseColor)
                                    val endColor =
                                        genreColor.copy(alpha = endAlpha).compositeOver(baseColor)

                                    val gradientBrush = Brush.horizontalGradient(
                                        colors = listOf(startColor, endColor),
                                        startX = x + 1f,
                                        endX = x + config.cwPx - 1f
                                    )

                                    drawRect(
                                        brush = gradientBrush,
                                        topLeft = Offset(x + 1f, py + 1f),
                                        size = Size(config.cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                                    )

                                    drawRect(
                                        color = genreColor,
                                        topLeft = Offset(x + 1f, py + 1f),
                                        size = Size(6f, (ph - 2f).coerceAtLeast(0f))
                                    )
                                } else {
                                    drawRect(
                                        color = bgColor,
                                        topLeft = Offset(x + 1f, py + 1f),
                                        size = Size(config.cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                                    )
                                }

                                if (ph > 20f) {
                                    val iconSize = 12.sp.toPx()
                                    val iconPadding = 2.dp.toPx()
                                    val iconOffset =
                                        if (reserve != null) iconSize + iconPadding else 0f

                                    val titleColor =
                                        if (isPast || isEmpty) config.colorTextPast else config.colorTextPrimary
                                    val titleLayout = state.textLayoutCache.getOrPut(p.id) {
                                        textMeasurer.measure(
                                            text = p.title,
                                            style = config.styleTitle.copy(color = titleColor),
                                            constraints = Constraints(
                                                maxWidth = (config.cwPx - 16f - iconOffset).toInt()
                                                    .coerceAtLeast(0),
                                                maxHeight = (ph - 12f).toInt().coerceAtLeast(0)
                                            ),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val titleH = titleLayout.size.height.toFloat()

                                    var descLayout: TextLayoutResult? = null
                                    var descH = 0f
                                    if (!isEmpty && (ph - titleH - 12f) > 20f && !p.description.isNullOrBlank()) {
                                        val descColor =
                                            if (isPast) config.colorTextPast else config.colorTextSecondary
                                        val descKey = p.id + "d"
                                        descLayout = state.textLayoutCache.getOrPut(descKey) {
                                            textMeasurer.measure(
                                                text = p.description,
                                                style = config.styleDesc.copy(color = descColor),
                                                constraints = Constraints(
                                                    maxWidth = (config.cwPx - 16f).toInt(),
                                                    maxHeight = (ph - titleH - 16f).toInt()
                                                        .coerceAtLeast(0)
                                                ),
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        descH = descLayout.size.height.toFloat()
                                    }

                                    val textTotalH =
                                        titleH + if (descLayout != null) descH + 2f else 0f
                                    val baseShiftY = maxOf(0f, config.hhAreaPx - py)
                                    val maxShiftY = maxOf(0f, ph - 16f - textTotalH)
                                    val shiftY = minOf(baseShiftY, maxShiftY)
                                    val titleY = py + 8f + shiftY

                                    if (reserve != null) {
                                        val iconY = titleY + (titleH - iconSize) / 2
                                        if (reserve.isRecordingInProgress) {
                                            drawCircle(
                                                color = Color(0xFFE53935),
                                                radius = iconSize / 2,
                                                center = Offset(
                                                    x + 10f + iconSize / 2,
                                                    iconY + iconSize / 2
                                                )
                                            )
                                        } else {
                                            val clockColor =
                                                if (isPartial) config.colorReserveBorderPartial else Color.Red
                                            translate(left = x + 10f, top = iconY) {
                                                with(clockPainter) {
                                                    draw(
                                                        size = Size(iconSize, iconSize),
                                                        colorFilter = ColorFilter.tint(clockColor)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (titleY + titleH > config.hhAreaPx) {
                                        drawText(
                                            titleLayout,
                                            topLeft = Offset(x + 10f + iconOffset, titleY)
                                        )
                                    }
                                    if (descLayout != null) {
                                        val descY = titleY + titleH + 2f
                                        if (descY + descH > config.hhAreaPx) {
                                            drawText(descLayout, topLeft = Offset(x + 10f, descY))
                                        }
                                    }
                                }

                                if (reserve != null) {
                                    val borderColor =
                                        if (isPartial) config.colorReserveBorderPartial else config.colorReserveBorder
                                    drawRoundRect(
                                        color = borderColor,
                                        topLeft = Offset(x + 2f, py + 2f),
                                        size = Size(config.cwPx - 4f, (ph - 4f).coerceAtLeast(0f)),
                                        cornerRadius = CornerRadius(4f),
                                        style = Stroke(width = 5f, pathEffect = reserveDashEffect)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 2. 現在時刻線 (Current Time Line)
            // ==========================================
            val baseMs = baseTimeMs(state.baseTime)
            val limitMs = limitTimeMs(state.limitTime)
            if (nowMs > baseMs && nowMs < limitMs) {
                val nowOff = ((nowMs - baseMs) / 60_000L).toFloat()
                val nowY = config.hhAreaPx + curY + (nowOff / 60f * config.hhPx)
                if (nowY > config.hhAreaPx && nowY < size.height) {
                    drawLine(
                        config.colorCurrentTimeLine,
                        Offset(config.twPx, nowY),
                        Offset(size.width, nowY),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        config.colorCurrentTimeLine,
                        radius = 6f,
                        center = Offset(config.twPx, nowY)
                    )
                }
            }

            // ==========================================
            // 3. フォーカス枠 (Focused Program Overlay)
            // ==========================================
            if (state.currentFocusedProgram != null && isGridFocused) {
                val fx = config.twPx + curX + animValues.animX
                val fy = config.hhAreaPx + curY + animValues.animY
                val fh = animValues.animH

                clipRect(
                    left = 0f,
                    top = config.hhAreaPx,
                    right = size.width,
                    bottom = size.height
                ) {
                    val p = state.currentFocusedProgram!!
                    val reserve = reserveMap[p.id]
                    val isPartial =
                        reserve != null && (reserve.recordingAvailability == "Partial" || reserve.recordingAvailability == "Partially")
                    val isDuplicated =
                        reserve != null && (reserve.recordingAvailability == "None" || reserve.recordingAvailability.equals(
                            "unavailable",
                            ignoreCase = true
                        ))

                    // ★ 最適化: 毎フレームの OffsetDateTime.parse を 1 件メモに置換
                    val endTimeMs = focusProgramEndMs(p.end_time)
                    val isPast = endTimeMs > 0 && endTimeMs < nowMs
                    val isEmpty = p.title == "（番組情報なし）"

                    val opaqueBase = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White
                    val bgColor = when {
                        isPartial -> config.colorReserveBorderPartial.copy(alpha = 0.2f)
                            .compositeOver(opaqueBase)

                        isDuplicated -> config.colorReserveBgDuplicated.compositeOver(opaqueBase)
                        isEmpty -> config.colorProgramEmpty.compositeOver(opaqueBase)
                        isPast -> config.colorProgramPast.compositeOver(opaqueBase)
                        else -> config.colorProgramNormal.compositeOver(opaqueBase)
                    }

                    if (!isEmpty) {
                        val majorGenre = p.genres?.firstOrNull()?.major
                        val genreColor = EpgUtils.getGenreColor(majorGenre)

                        val startAlpha =
                            if (isPast) (if (isDarkTheme) 0.1f else 0.03f) else (if (isDarkTheme) 0.2f else 0.06f)
                        val endAlpha = if (isPast) 0.0f else (if (isDarkTheme) 0.05f else 0.01f)

                        val baseColor = bgColor
                        val startColor =
                            genreColor.copy(alpha = startAlpha).compositeOver(baseColor)
                        val endColor = genreColor.copy(alpha = endAlpha).compositeOver(baseColor)

                        val gradientBrush = Brush.horizontalGradient(
                            colors = listOf(startColor, endColor),
                            startX = fx + 1f,
                            endX = fx + config.cwPx - 1f
                        )

                        drawRect(
                            brush = gradientBrush,
                            topLeft = Offset(fx + 1f, fy + 1f),
                            size = Size(config.cwPx - 2f, (fh - 2f).coerceAtLeast(0f))
                        )
                        drawRect(
                            color = genreColor,
                            topLeft = Offset(fx + 1f, fy + 1f),
                            size = Size(6f, (fh - 2f).coerceAtLeast(0f))
                        )
                    } else {
                        drawRect(
                            color = bgColor,
                            topLeft = Offset(fx + 1f, fy + 1f),
                            size = Size(config.cwPx - 2f, (fh - 2f).coerceAtLeast(0f))
                        )
                    }

                    val shadowColor = if (isDarkTheme) config.colorFocusBorder else Color.Black
                    for (i in 6 downTo 1) {
                        val alpha = if (isDarkTheme) (0.5f - (i * 0.08f)).coerceIn(
                            0f,
                            1f
                        ) else (0.25f - (i * 0.04f)).coerceIn(0f, 1f)
                        drawRoundRect(
                            color = shadowColor.copy(alpha = alpha),
                            topLeft = Offset(fx - i * 1.5f, fy - i * 1.5f),
                            size = Size(config.cwPx + i * 3f, fh + i * 3f),
                            cornerRadius = CornerRadius(6f),
                            style = Stroke(width = 3f)
                        )
                    }

                    val iconSize = 12.sp.toPx()
                    val iconPadding = 2.dp.toPx()
                    val iconOffset = if (reserve != null) iconSize + iconPadding else 0f

                    val titleColor =
                        if (isPast || isEmpty) config.colorTextPast else config.colorTextPrimary
                    val cacheKeyF = p.id + "f"

                    val titleLayout = state.textLayoutCache.getOrPut(cacheKeyF) {
                        textMeasurer.measure(
                            text = p.title,
                            style = config.styleTitle.copy(color = titleColor),
                            constraints = Constraints(
                                maxWidth = (config.cwPx - 20f - iconOffset).toInt().coerceAtLeast(0)
                            )
                        )
                    }
                    val titleH = titleLayout.size.height.toFloat()

                    var descLayout: TextLayoutResult? = null
                    if (!isEmpty && !p.description.isNullOrBlank()) {
                        val descColor =
                            if (isPast) config.colorTextPast else config.colorTextSecondary
                        val descCacheKey = p.id + "fd"
                        descLayout = state.textLayoutCache.getOrPut(descCacheKey) {
                            textMeasurer.measure(
                                text = p.description ?: "",
                                style = config.styleDesc.copy(color = descColor),
                                constraints = Constraints(
                                    maxWidth = (config.cwPx - 20f).toInt(),
                                    maxHeight = (fh - titleH - 25f).toInt().coerceAtLeast(0)
                                ),
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val textTotalH =
                        titleH + if (descLayout != null) descLayout.size.height + 4f else 0f
                    val baseShiftY = maxOf(0f, config.hhAreaPx - fy)
                    val maxShiftY = maxOf(0f, fh - 16f - textTotalH)
                    val shiftY = minOf(baseShiftY, maxShiftY)
                    val titleY = fy + 8f + shiftY

                    if (reserve != null) {
                        val iconY = titleY + (titleH - iconSize) / 2
                        if (reserve.isRecordingInProgress) {
                            drawCircle(
                                color = Color(0xFFE53935),
                                radius = iconSize / 2,
                                center = Offset(fx + 10f + iconSize / 2, iconY + iconSize / 2)
                            )
                        } else {
                            val clockColor =
                                if (isPartial) config.colorReserveBorderPartial else Color.Red
                            translate(left = fx + 10f, top = iconY) {
                                with(clockPainter) {
                                    draw(
                                        size = Size(iconSize, iconSize),
                                        colorFilter = ColorFilter.tint(clockColor)
                                    )
                                }
                            }
                        }
                    }

                    drawText(titleLayout, topLeft = Offset(fx + 10f + iconOffset, titleY))
                    if (descLayout != null) drawText(
                        descLayout,
                        topLeft = Offset(fx + 10f, titleY + titleH + 4f)
                    )

                    if (reserve != null) {
                        val borderColor =
                            if (isPartial) config.colorReserveBorderPartial else config.colorReserveBorder
                        drawRoundRect(
                            color = borderColor,
                            topLeft = Offset(fx + 2f, fy + 2f),
                            size = Size(config.cwPx - 4f, (fh - 4f).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(4f),
                            style = Stroke(width = 5f, pathEffect = reserveDashEffect)
                        )
                    }

                    drawRoundRect(
                        config.colorFocusBorder,
                        Offset(fx - 1f, fy - 1f),
                        Size(config.cwPx + 2f, fh + 2f),
                        CornerRadius(4f),
                        Stroke(4f)
                    )
                }
            }

            // ==========================================
            // 4. 左側の時間軸 (Time Axis)
            // ==========================================
            clipRect(left = 0f, top = config.hhAreaPx, right = config.twPx, bottom = size.height) {
                val totalHours = 24 * 14
                val startHour = (-curY / config.hhPx).toInt().coerceAtLeast(0)
                val endHour = ((-curY + size.height - config.hhAreaPx) / config.hhPx).toInt()
                    .coerceAtMost(totalHours)

                for (h in startHour..endHour) {
                    val fy = config.hhAreaPx + curY + (h * config.hhPx)
                    val hour = (state.baseTime.hour + h) % 24

                    val bgColor = when (hour) {
                        in 4..10 -> config.colorTimeHourEven
                        in 11..17 -> config.colorTimeHourOdd
                        else -> config.colorTimeHourNight
                    }
                    drawRect(bgColor, Offset(0f, fy), Size(config.twPx, config.hhPx))

                    // ★ 修正: timeFormat の設定に応じて描画を出し分ける
                    // ★ 最適化: 時刻ラベルの計測結果をキャッシュする。
                    //   従来は表示中の時間帯ぶん(6〜10 行)を毎フレーム measure し直しており、
                    //   TextMeasurer 内蔵のキャッシュ(既定 8 件)も番組タイトル計測と奪い合って
                    //   ほとんど効いていなかった。
                    if (timeFormat == "12H") {
                        // 12時間表記 (AM/PM 付き)
                        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                        val amPmText = if (hour < 12) "AM" else "PM"
                        val amPmLayout = state.textLayoutCache.getOrPut("##ampm:$amPmText") {
                            textMeasurer.measure(amPmText, config.styleAmPm)
                        }
                        val hourLayout = state.textLayoutCache.getOrPut("##hour:$displayHour") {
                            textMeasurer.measure(displayHour.toString(), config.styleTime)
                        }

                        val startY =
                            fy + (config.hhPx - (amPmLayout.size.height + hourLayout.size.height + 2f)) / 2
                        drawText(
                            amPmLayout,
                            topLeft = Offset((config.twPx - amPmLayout.size.width) / 2, startY)
                        )
                        drawText(
                            hourLayout,
                            topLeft = Offset(
                                (config.twPx - hourLayout.size.width) / 2,
                                startY + amPmLayout.size.height + 2f
                            )
                        )
                    } else {
                        // 24時間表記 (AM/PM なしで数字だけを中央に大きく表示)
                        val hourLayout = state.textLayoutCache.getOrPut("##hour:$hour") {
                            textMeasurer.measure(hour.toString(), config.styleTime)
                        }
                        val startY = fy + (config.hhPx - hourLayout.size.height) / 2
                        drawText(
                            hourLayout,
                            topLeft = Offset((config.twPx - hourLayout.size.width) / 2, startY)
                        )
                    }

                    drawLine(config.colorGridLine, Offset(0f, fy), Offset(config.twPx, fy), 3f)
                }
            }

            // ==========================================
            // 5. 上部のチャンネルヘッダー (Channel Header)
            // ==========================================
            clipRect(left = config.twPx, top = 0f, right = size.width, bottom = config.hhAreaPx) {
                drawRect(
                    config.colorHeaderBg,
                    Offset(config.twPx, 0f),
                    Size(size.width, config.hhAreaPx)
                )
                val startCol = ((-curX) / config.cwPx).toInt().coerceAtLeast(0)
                val endCol = ((-curX + size.width - config.twPx) / config.cwPx).toInt()
                    .coerceAtMost(state.uiChannels.lastIndex)

                if (startCol <= endCol && state.uiChannels.isNotEmpty()) {
                    for (c in startCol..endCol) {
                        val wrapper = state.uiChannels[c].wrapper
                        val x = config.twPx + curX + (c * config.cwPx)
                        val logoW = 30.sp.toPx()
                        val logoH = 18.sp.toPx()

                        // ★ 最適化: チャンネル番号／局名の計測結果をキャッシュ。
                        //   従来は表示中の列(7〜12 列)ぶんを毎フレーム 2 回ずつ measure しており、
                        //   横スクロール中は 1 フレームあたり 20 回以上のテキスト計測が
                        //   メインスレッドで走っていた。内容は列ごとに固定なのでキャッシュできる。
                        val chNumText = wrapper.channel.channel_number ?: "---"
                        val numLayout =
                            state.textLayoutCache.getOrPut("##chnum:${wrapper.channel.id}") {
                                textMeasurer.measure(chNumText, config.styleChNum)
                            }
                        val startX = x + (config.cwPx - (logoW + 6f + numLayout.size.width)) / 2

                        if (c < logoPainters.size) {
                            val painter = logoPainters[c]
                            translate(startX, 6f) {
                                val srcSize = painter.intrinsicSize
                                if (srcSize.isSpecified && srcSize.width > 0 && srcSize.height > 0) {
                                    val sc = maxOf(logoW / srcSize.width, logoH / srcSize.height)
                                    clipRect(0f, 0f, logoW, logoH) {
                                        translate(
                                            (logoW - srcSize.width * sc) / 2,
                                            (logoH - srcSize.height * sc) / 2
                                        ) {
                                            with(painter) {
                                                draw(
                                                    Size(
                                                        srcSize.width * sc,
                                                        srcSize.height * sc
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    with(painter) { draw(Size(logoW, logoH)) }
                                }
                            }
                        }

                        drawText(
                            numLayout,
                            topLeft = Offset(
                                startX + logoW + 6f,
                                6f + (logoH - numLayout.size.height) / 2
                            )
                        )
                        val nameLayout =
                            state.textLayoutCache.getOrPut("##chname:${wrapper.channel.id}") {
                                textMeasurer.measure(
                                    wrapper.channel.name,
                                    config.styleChName,
                                    overflow = TextOverflow.Ellipsis,
                                    constraints = Constraints(maxWidth = (config.cwPx - 16f).toInt())
                                )
                            }
                        drawText(
                            nameLayout,
                            topLeft = Offset(
                                x + (config.cwPx - nameLayout.size.width) / 2,
                                6f + logoH + 2f
                            )
                        )
                        drawLine(
                            config.colorGridLine,
                            Offset(x, 0f),
                            Offset(x, config.hhAreaPx),
                            strokeWidth = 2f
                        )
                    }
                }
            }

            // ==========================================
            // 6. 左上隅の固定ラベル (Top-Left Date Area)
            // ==========================================
            drawRect(config.colorBg, Offset.Zero, Size(config.twPx, config.hhAreaPx))

            val disp =
                state.baseTime.plusMinutes((-curY / config.hhPx * 60).toLong().coerceAtLeast(0))
            val dateStr = "${disp.monthValue}/${disp.dayOfMonth}"
            val dayStr = "(${disp.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPANESE)})"

            val dayColor = when (disp.dayOfWeek.value) {
                7 -> Color(0xFFFF5252); 6 -> Color(0xFF448AFF); else -> config.colorTextPrimary
            }

            // ★ 最適化: 日付ラベルも計測結果をキャッシュ。
            //   従来は AnnotatedString と SpanStyle 2 個を毎フレーム組み立てて measure しており、
            //   スクロール中は表示内容が変わらないのに毎フレーム再計測していた。
            val dateLayout = state.textLayoutCache.getOrPut("##date:$dateStr$dayStr") {
                textMeasurer.measure(
                    text = AnnotatedString(
                        text = "$dateStr\n$dayStr",
                        spanStyles = listOf(
                            AnnotatedString.Range(
                                SpanStyle(
                                    color = config.colorTextPrimary,
                                    fontSize = 11.sp
                                ), 0, dateStr.length
                            ),
                            AnnotatedString.Range(
                                SpanStyle(color = dayColor, fontSize = 11.sp),
                                dateStr.length + 1,
                                dateStr.length + 1 + dayStr.length
                            )
                        )
                    ),
                    style = config.styleDateLabel.copy(
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    ),
                    constraints = Constraints(maxWidth = config.twPx.toInt())
                )
            }
            drawText(
                dateLayout,
                topLeft = Offset(
                    (config.twPx - dateLayout.size.width) / 2,
                    (config.hhAreaPx - dateLayout.size.height) / 2
                )
            )

            drawLine(
                config.colorGridLine,
                Offset(config.twPx, 0f),
                Offset(config.twPx, size.height),
                strokeWidth = 4f
            )
            drawLine(
                config.colorGridLine,
                Offset(0f, config.hhAreaPx),
                Offset(size.width, config.hhAreaPx),
                strokeWidth = 4f
            )
        }
    }
}