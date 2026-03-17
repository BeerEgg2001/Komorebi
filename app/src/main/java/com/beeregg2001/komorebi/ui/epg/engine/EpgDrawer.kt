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

/**
 * 番組表のアニメーション・スクロール状態を保持するデータクラス
 */
data class EpgAnimValues(
    val scrollX: Float,
    val scrollY: Float,
    val animX: Float,
    val animY: Float,
    val animH: Float
)

/**
 * 番組表の描画を担うコアクラス。
 * Compose標準のLazyList等を使わず、Canvas(DrawScope)上で直接矩形やテキストを描画することで、
 * 膨大な番組データでも処理落ちしない高速で滑らかなスクロールを実現しています。
 */
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

            // ==========================================
            // 1. 番組表メインエリア (Program Grid)
            // ==========================================
            // 左側の時間軸(twPx)と上部のヘッダー(hhAreaPx)を除外した領域にのみ番組を描画します。
            clipRect(left = config.twPx, top = config.hhAreaPx) {
                // スクロール量に基づいて、画面内に見えているチャンネル（列）のインデックスを計算
                val startCol = ((-curX) / config.cwPx).toInt().coerceAtLeast(0)
                val endCol = ((-curX + size.width - config.twPx) / config.cwPx).toInt()
                    .coerceAtMost(state.uiChannels.lastIndex)

                if (startCol <= endCol && state.uiChannels.isNotEmpty()) {
                    // スクロール量に基づいて、画面内に見えている時間（Y座標）の範囲を計算
                    val visibleTopY = -curY - config.hhAreaPx
                    val visibleBottomY = visibleTopY + size.height

                    for (c in startCol..endCol) {
                        val uiChannel = state.uiChannels[c]
                        // このチャンネル列のX座標（左端）
                        val x = config.twPx + curX + (c * config.cwPx)

                        for (uiProg in uiChannel.uiPrograms) {
                            // プログラムが画面外（上すぎる、または下すぎる）場合は描画をスキップ（カリング）
                            if (uiProg.topY + uiProg.height < visibleTopY) continue
                            if (uiProg.topY > visibleBottomY) break

                            // プログラムの絶対Y座標と高さ
                            val py = config.hhAreaPx + curY + uiProg.topY
                            val ph = uiProg.height
                            val isPast = uiProg.endTimeMs < nowMs
                            val isEmpty = uiProg.isEmpty
                            val p = uiProg.program

                            val reserve = reserveMap[p.id]

                            // 録画予約の競合状態チェック
                            val isPartial =
                                reserve != null && (reserve.recordingAvailability == "Partial" || reserve.recordingAvailability == "Partially")
                            val isDuplicated =
                                reserve != null && (reserve.recordingAvailability == "None" || reserve.recordingAvailability.equals(
                                    "unavailable",
                                    ignoreCase = true
                                ))

                            // 隣の列にはみ出さないよう、列の幅でクリッピング
                            clipRect(
                                left = x,
                                top = config.hhAreaPx,
                                right = x + config.cwPx,
                                bottom = size.height
                            ) {
                                // 背景色の決定（過去、予約競合、空き領域など）
                                val bgColor = when {
                                    isPartial -> config.colorReserveBorderPartial.copy(alpha = 0.2f)
                                    isDuplicated -> config.colorReserveBgDuplicated
                                    isEmpty -> config.colorProgramEmpty
                                    isPast -> config.colorProgramPast
                                    else -> config.colorProgramNormal
                                }

                                // 番組の枠（矩形）を描画
                                drawRect(
                                    color = bgColor,
                                    topLeft = Offset(x + 1f, py + 1f),
                                    size = Size(config.cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                                )

                                // ジャンル別の左端カラーバーを描画
                                if (!isEmpty) {
                                    val majorGenre = p.genres?.firstOrNull()?.major
                                    val genreColor = EpgUtils.getGenreColor(majorGenre)
                                    drawRect(
                                        color = genreColor,
                                        topLeft = Offset(x + 1f, py + 1f),
                                        size = Size(6f, (ph - 2f).coerceAtLeast(0f))
                                    )
                                }

                                // 番組の高さが十分にある場合のみテキストを描画（短すぎる番組は文字を省略）
                                if (ph > 20f) {
                                    val iconSize = 12.sp.toPx()
                                    val iconPadding = 2.dp.toPx()
                                    val iconOffset =
                                        if (reserve != null) iconSize + iconPadding else 0f

                                    // タイトルテキストのサイズ計測とキャッシュ（パフォーマンス最適化）
                                    // ★修正: 白やグレーの固定を排除し、config のテーマカラーを参照する
                                    val titleLayout = state.textLayoutCache.getOrPut(p.id) {
                                        textMeasurer.measure(
                                            text = p.title,
                                            style = config.styleTitle.copy(color = if (isPast || isEmpty) config.colorTextPast else config.colorTextPrimary),
                                            constraints = Constraints(
                                                maxWidth = (config.cwPx - 16f - iconOffset).toInt()
                                                    .coerceAtLeast(0),
                                                maxHeight = (ph - 12f).toInt().coerceAtLeast(0)
                                            ),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val titleH = titleLayout.size.height.toFloat()

                                    // 説明文（あらすじ）のサイズ計測とキャッシュ
                                    var descLayout: TextLayoutResult? = null
                                    var descH = 0f
                                    if (!isEmpty && (ph - titleH - 12f) > 20f && !p.description.isNullOrBlank()) {
                                        val descKey = p.id + "d"
                                        descLayout = state.textLayoutCache.getOrPut(descKey) {
                                            // ★修正: 説明文の色もテーマに同期
                                            textMeasurer.measure(
                                                text = p.description,
                                                style = config.styleDesc.copy(color = if (isPast) config.colorTextPast else config.colorTextSecondary),
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

                                    // スクロール時に、番組の枠が画面外にはみ出しても、テキストが常に画面内に留まるように追従させる計算（Sticky Header風の挙動）
                                    val textTotalH =
                                        titleH + if (descLayout != null) descH + 2f else 0f
                                    val baseShiftY = maxOf(0f, config.hhAreaPx - py)
                                    val maxShiftY = maxOf(0f, ph - 16f - textTotalH)
                                    val shiftY = minOf(baseShiftY, maxShiftY)
                                    val titleY = py + 8f + shiftY

                                    // 録画予約アイコン（赤丸 または 時計）の描画
                                    if (reserve != null) {
                                        val iconY = titleY + (titleH - iconSize) / 2
                                        if (reserve.isRecordingInProgress) {
                                            // 録画中の場合は赤丸
                                            drawCircle(
                                                color = Color(0xFFE53935),
                                                radius = iconSize / 2,
                                                center = Offset(
                                                    x + 10f + iconSize / 2,
                                                    iconY + iconSize / 2
                                                )
                                            )
                                        } else {
                                            // 予約済みの場合は時計アイコン（一部競合時は色を変える）
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

                                    // ヘッダーに隠れない部分のみテキストを描画
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

                                // 予約済み番組の外枠（破線）を描画
                                if (reserve != null) {
                                    val borderColor =
                                        if (isPartial) config.colorReserveBorderPartial else config.colorReserveBorder
                                    val dashEffect =
                                        PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)
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

            // ==========================================
            // 2. 現在時刻線 (Current Time Line)
            // ==========================================
            if (nowTime.isAfter(state.baseTime) && nowTime.isBefore(state.limitTime)) {
                // 番組表の基準時間から現在時刻までのオフセット分（ピクセル）を計算
                val nowOff = Duration.between(state.baseTime, nowTime).toMinutes().toFloat()
                val nowY = config.hhAreaPx + curY + (nowOff / 60f * config.hhPx)
                // 現在時刻のY座標が画面内にあれば横線を引く
                if (nowY > config.hhAreaPx && nowY < size.height) {
                    drawLine(
                        config.colorCurrentTimeLine,
                        Offset(config.twPx, nowY),
                        Offset(size.width, nowY),
                        strokeWidth = 3f
                    )
                    // 左端の装飾丸
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
            // 最前面に描画するため、他プログラムの描画ループとは別に処理します。
            if (state.currentFocusedProgram != null && isGridFocused) {
                // アニメーション中の座標と高さ
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

                    // フォーカス枠の背景色（通常、競合中などで変化）
                    val focusBgColor = when {
                        isPartial -> config.colorReserveBorderPartial.copy(alpha = 0.2f)
                            .compositeOver(config.colorBg) // ★修正: Color.Black を排除
                        isDuplicated -> config.colorReserveBgDuplicated
                        else -> config.colorFocusBg
                    }

                    // 枠全体の塗りつぶし
                    drawRect(
                        focusBgColor,
                        Offset(fx + 1f, fy + 1f),
                        Size(config.cwPx - 2f, fh - 2f)
                    )

                    // ジャンルカラーバー
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

                    // フォーカス時のテキストは通常時とスタイル（色など）が異なるため別キーでキャッシュ
                    val titleLayout = state.textLayoutCache.getOrPut(cacheKeyF) {
                        textMeasurer.measure(
                            text = p.title,
                            style = config.styleTitle,
                            constraints = Constraints(
                                maxWidth = (config.cwPx - 20f - iconOffset).toInt().coerceAtLeast(0)
                            )
                        )
                    }
                    val titleH = titleLayout.size.height.toFloat()

                    var descLayout: TextLayoutResult? = null
                    if (p.title != "（番組情報なし）" && !p.description.isNullOrBlank()) {
                        val descCacheKey = p.id + "fd"
                        descLayout = state.textLayoutCache.getOrPut(descCacheKey) {
                            textMeasurer.measure(
                                text = p.description ?: "",
                                style = config.styleDesc,
                                constraints = Constraints(
                                    maxWidth = (config.cwPx - 20f).toInt(),
                                    maxHeight = (fh - titleH - 25f).toInt().coerceAtLeast(0)
                                ),
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // テキストの追従（Sticky Header）計算
                    val textTotalH =
                        titleH + if (descLayout != null) descLayout.size.height + 4f else 0f
                    val baseShiftY = maxOf(0f, config.hhAreaPx - fy)
                    val maxShiftY = maxOf(0f, fh - 16f - textTotalH)
                    val shiftY = minOf(baseShiftY, maxShiftY)
                    val titleY = fy + 8f + shiftY

                    // 予約アイコンの描画（フォーカス時も最前面に描画）
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
                            translate(
                                left = fx + 10f,
                                top = iconY
                            ) {
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
                    if (descLayout != null) {
                        drawText(descLayout, topLeft = Offset(fx + 10f, titleY + titleH + 4f))
                    }

                    // 予約済みの破線枠（フォーカス時）
                    if (reserve != null) {
                        val borderColor =
                            if (isPartial) config.colorReserveBorderPartial else config.colorReserveBorder
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)
                        drawRoundRect(
                            color = borderColor,
                            topLeft = Offset(fx + 2f, fy + 2f),
                            size = Size(config.cwPx - 4f, (fh - 4f).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(4f),
                            style = Stroke(width = 5f, pathEffect = dashEffect)
                        )
                    }

                    // 最も外側のハイライト枠線（太線）
                    drawRoundRect(
                        config.colorFocusBorder,
                        Offset(fx - 1f, fy - 1f),
                        Size(config.cwPx + 2f, fh + 2f),
                        CornerRadius(2f),
                        Stroke(4f)
                    )
                }
            }

            // ==========================================
            // 4. 左側の時間軸 (Time Axis)
            // ==========================================
            clipRect(left = 0f, top = config.hhAreaPx, right = config.twPx, bottom = size.height) {
                val totalHours = 24 * 14
                // スクロール量から見えている時間帯（時間）を算出
                val startHour = (-curY / config.hhPx).toInt().coerceAtLeast(0)
                val endHour = ((-curY + size.height - config.hhAreaPx) / config.hhPx).toInt()
                    .coerceAtMost(totalHours)

                for (h in startHour..endHour) {
                    val fy = config.hhAreaPx + curY + (h * config.hhPx)
                    val hour = (state.baseTime.hour + h) % 24

                    // 時間帯に応じた背景色（朝/昼/夜）
                    val bgColor = when (hour) {
                        in 4..10 -> config.colorTimeHourEven
                        in 11..17 -> config.colorTimeHourOdd
                        else -> config.colorTimeHourNight
                    }
                    drawRect(bgColor, Offset(0f, fy), Size(config.twPx, config.hhPx))

                    // AM/PM と数字の描画
                    val amPmLayout =
                        textMeasurer.measure(if (hour < 12) "AM" else "PM", config.styleAmPm)
                    val hourLayout = textMeasurer.measure(hour.toString(), config.styleTime)
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

                    // 1時間ごとの区切り線
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

                        val numLayout = textMeasurer.measure(
                            wrapper.channel.channel_number ?: "---",
                            config.styleChNum
                        )
                        val startX = x + (config.cwPx - (logoW + 6f + numLayout.size.width)) / 2

                        // チャンネルロゴ（画像）の描画処理。アスペクト比を維持しつつスケーリング
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
                                                draw(Size(srcSize.width * sc, srcSize.height * sc))
                                            }
                                        }
                                    }
                                } else {
                                    with(painter) { draw(Size(logoW, logoH)) }
                                }
                            }
                        }

                        // チャンネル番号と放送局名の描画
                        drawText(
                            numLayout,
                            topLeft = Offset(
                                startX + logoW + 6f,
                                6f + (logoH - numLayout.size.height) / 2
                            )
                        )
                        val nameLayout = textMeasurer.measure(
                            wrapper.channel.name,
                            config.styleChName,
                            overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(maxWidth = (config.cwPx - 16f).toInt())
                        )
                        drawText(
                            nameLayout,
                            topLeft = Offset(
                                x + (config.cwPx - nameLayout.size.width) / 2,
                                6f + logoH + 2f
                            )
                        )

                        // 縦の区切り線
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

            // スクロール量（Y座標）から、現在画面最上部に見えている日時を逆算して表示
            val disp =
                state.baseTime.plusMinutes((-curY / config.hhPx * 60).toLong().coerceAtLeast(0))
            val dateStr = "${disp.monthValue}/${disp.dayOfMonth}"
            val dayStr = "(${disp.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPANESE)})"

            // ★修正: 白固定をテーマカラーに
            val dayColor = when (disp.dayOfWeek.value) {
                7 -> Color(0xFFFF5252); 6 -> Color(0xFF448AFF); else -> config.colorTextPrimary
            }

            // 日付と曜日の色を変えるためのリッチテキスト処理
            val dateLayout = textMeasurer.measure(
                text = AnnotatedString(
                    text = "$dateStr\n$dayStr",
                    spanStyles = listOf(
                        AnnotatedString.Range(
                            SpanStyle(color = config.colorTextPrimary, fontSize = 11.sp),
                            0,
                            dateStr.length
                        ), // ★修正
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
            drawText(
                dateLayout,
                topLeft = Offset(
                    (config.twPx - dateLayout.size.width) / 2,
                    (config.hhAreaPx - dateLayout.size.height) / 2
                )
            )

            // 境界の太線
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