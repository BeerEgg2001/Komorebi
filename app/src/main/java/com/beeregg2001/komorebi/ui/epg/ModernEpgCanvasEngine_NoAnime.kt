package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.rememberAsyncImagePainter
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.ChronoUnit
import java.util.*

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTextApi::class)
@Composable
fun ModernEpgCanvasEngine_NoAnime(
    uiState: EpgUiState,
    logoUrls: List<String>,
    topTabFocusRequester: FocusRequester,
    contentFocusRequester: FocusRequester,
    entryFocusRequester: FocusRequester,
    onProgramSelected: (EpgProgram) -> Unit,
    jumpTargetTime: OffsetDateTime?,
    onJumpFinished: () -> Unit,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    currentType: String,
    onTypeChanged: (String) -> Unit,
    restoreChannelId: String? = null,
    restoreProgramStartTime: String? = null
) {
    val lastSuccessData = remember { mutableStateOf<EpgUiState.Success?>(null) }
    if (uiState is EpgUiState.Success) { lastSuccessData.value = uiState }
    val displayData = lastSuccessData.value ?: return

    val availableBroadcastingTypes = remember {
        listOf("地上波" to "GR", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "SKY")
    }

    // --- レイアウト定数 (Density考慮) ---
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val cwPx = with(density) { 130.dp.toPx() }
    val hhPx = with(density) { 75.dp.toPx() }
    val twPx = with(density) { 60.dp.toPx() }
    val hhAreaPx = with(density) { 45.dp.toPx() }
    val tabHeightPx = with(density) { 48.dp.toPx() }
    val minExpHPx = with(density) { 140.dp.toPx() }
    val bPadPx = with(density) { 120.dp.toPx() }
    val sPadPx = with(density) { 32.dp.toPx() }
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val textMeasurer = rememberTextMeasurer()
    val logoPainters = logoUrls.map { rememberAsyncImagePainter(model = it) }

    // --- スタイル定義 ---
    val styles = remember {
        object {
            val title = TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
            val desc = TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Normal, lineHeight = 13.sp)
            val chNum = TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
            val chName = TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp)
            val time = TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val amPm = TextStyle(fontFamily = NotoSansJP, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            val dateLabel = TextStyle(fontFamily = NotoSansJP, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    // --- 状態管理 ---
    val baseTime = remember(displayData) {
        val earliest = displayData.data.flatMap { it.programs }
            .mapNotNull { runCatching { OffsetDateTime.parse(it.start_time) }.getOrNull() }
            .minByOrNull { it.toEpochSecond() } ?: OffsetDateTime.now()
        earliest.withMinute(0).withSecond(0).withNano(0)
    }
    val maxScrollMinutes = 1440 * 14
    val limitTime = remember(baseTime) { baseTime.plusMinutes(maxScrollMinutes.toLong()) }
    val filledChannelWrappers = remember(displayData.data, baseTime) {
        displayData.data.map { wrapper ->
            wrapper.copy(programs = EpgDataConverter.getFilledPrograms(wrapper.channel.id, wrapper.programs, baseTime, limitTime))
        }
    }
    val COLUMNS = filledChannelWrappers.size
    val getNowMinutes = {
        val now = OffsetDateTime.now()
        Duration.between(baseTime, now).toMinutes().toInt().coerceIn(0, maxScrollMinutes)
    }

    // フォーカス位置の状態
    var focusedCol by remember { mutableIntStateOf(0) }
    var focusedMin by remember { mutableIntStateOf(getNowMinutes()) }

    // --- [アニメーション設定] ---
    // ターゲット座標（ここを目指してアニメーションする）
    var targetScrollX by remember { mutableFloatStateOf(0f) }
    var targetScrollY by remember { mutableFloatStateOf(-(getNowMinutes() / 60f * hhPx)) }
    var targetAnimX by remember { mutableFloatStateOf(0f) }
    var targetAnimY by remember { mutableFloatStateOf((getNowMinutes() / 60f * hhPx)) }
    var targetAnimH by remember { mutableFloatStateOf(hhPx) }

    // アニメーションの挙動設定 (150ms / イージングあり)
    // Springを使う場合は stiffness = Spring.StiffnessMediumLow 程度がTVには合います
    val fastSpec = tween<Float>(durationMillis = 150, easing = LinearOutSlowInEasing)

    // アニメーション適用後の値（Canvasで使用）
    val scrollX by animateFloatAsState(targetValue = targetScrollX, animationSpec = fastSpec)
    val scrollY by animateFloatAsState(targetValue = targetScrollY, animationSpec = fastSpec)
    val animX by animateFloatAsState(targetValue = targetAnimX, animationSpec = fastSpec)
    val animY by animateFloatAsState(targetValue = targetAnimY, animationSpec = fastSpec)
    val animH by animateFloatAsState(targetValue = targetAnimH, animationSpec = fastSpec)

    var isContentFocused by remember { mutableStateOf(false) }
    var currentFocusedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    val subTabFocusRequesters = remember(availableBroadcastingTypes) { List(availableBroadcastingTypes.size) { FocusRequester() } }
    val jumpMenuFocusRequester = remember { FocusRequester() }
    var isLongPressHandled by remember { mutableStateOf(false) }

    // --- 位置計算ロジック (ターゲットを更新する) ---
    fun updatePositions(col: Int, min: Int) {
        val safeCol = col.coerceIn(0, (COLUMNS - 1).coerceAtLeast(0))
        val safeMin = min.coerceIn(0, maxScrollMinutes)

        val channel = filledChannelWrappers[safeCol]
        val focusTime = baseTime.plusMinutes(safeMin.toLong())

        val prog = channel.programs.find { p ->
            val s = EpgDataConverter.safeParseTime(p.start_time, baseTime)
            val e = EpgDataConverter.safeParseTime(p.end_time, s.plusMinutes(1))
            !focusTime.isBefore(s) && focusTime.isBefore(e)
        }
        currentFocusedProgram = prog

        val (sOff, dur) = prog?.let { EpgDataConverter.calculateSafeOffsets(it, baseTime) } ?: (safeMin.toFloat() to 30f)

        // ターゲット座標をセット
        targetAnimX = safeCol * cwPx
        targetAnimY = (sOff / 60f) * hhPx
        targetAnimH = if (prog?.title == "（番組情報なし）") (dur/60f*hhPx) else (dur/60f*hhPx).coerceAtLeast(minExpHPx)

        val visibleW = screenWidthPx - twPx
        val visibleH = screenHeightPx - (tabHeightPx + hhAreaPx)

        // スクロールターゲットの算出
        var nextTargetX = targetScrollX
        if (targetAnimX < -targetScrollX) nextTargetX = -targetAnimX
        else if (targetAnimX + cwPx > -targetScrollX + visibleW) nextTargetX = -(targetAnimX + cwPx - visibleW)

        var nextTargetY = targetScrollY
        if (targetAnimY + targetAnimH > -targetScrollY + visibleH) nextTargetY = -(targetAnimY + targetAnimH - visibleH + sPadPx)
        if (targetAnimY < -targetScrollY) nextTargetY = -targetAnimY

        targetScrollX = nextTargetX.coerceIn(-(COLUMNS * cwPx - visibleW).coerceAtLeast(0f), 0f)
        targetScrollY = nextTargetY.coerceIn(-((maxScrollMinutes / 60f) * hhPx + bPadPx - visibleH).coerceAtLeast(0f), 0f)

        focusedCol = safeCol
        focusedMin = safeMin
    }

    // 復帰・ジャンプ処理
    LaunchedEffect(restoreChannelId, restoreProgramStartTime, filledChannelWrappers) {
        if (restoreChannelId != null && filledChannelWrappers.isNotEmpty()) {
            val colIndex = filledChannelWrappers.indexOfFirst { it.channel.id == restoreChannelId }
            if (colIndex != -1) {
                val t = if (restoreProgramStartTime != null) runCatching { OffsetDateTime.parse(restoreProgramStartTime) }.getOrNull() ?: OffsetDateTime.now() else OffsetDateTime.now()
                updatePositions(colIndex, ChronoUnit.MINUTES.between(baseTime, t).toInt())
                contentFocusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(jumpTargetTime) {
        if (jumpTargetTime != null) {
            updatePositions(0, Duration.between(baseTime, jumpTargetTime).toMinutes().toInt())
            onJumpFinished()
            contentFocusRequester.requestFocus()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- サブタブヘッダー ---
        Box(modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF0A0A0A))) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                var isJumpBtnFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier.width(110.dp).fillMaxHeight()
                        .onFocusChanged { isJumpBtnFocused = it.isFocused }
                        .focusRequester(jumpMenuFocusRequester)
                        .focusProperties {
                            right = if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[0] else FocusRequester.Default
                            down = contentFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> { topTabFocusRequester.requestFocus(); true }
                                    Key.DirectionCenter, Key.Enter -> { onEpgJumpMenuStateChanged(true); true }
                                    Key.DirectionDown -> { contentFocusRequester.requestFocus(); true }
                                    else -> false
                                }
                            } else false
                        }
                        .focusable().background(if (isJumpBtnFocused) Color.White else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text("日時指定", color = if (isJumpBtnFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Row(modifier = Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.Center) {
                        availableBroadcastingTypes.forEachIndexed { index, (label, apiValue) ->
                            var isTabFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier.width(110.dp).fillMaxHeight()
                                    .onFocusChanged { isTabFocused = it.isFocused }
                                    .focusRequester(subTabFocusRequesters[index])
                                    .focusProperties {
                                        left = if (index == 0) jumpMenuFocusRequester else subTabFocusRequesters[index - 1]
                                        right = if (index == availableBroadcastingTypes.size - 1) FocusRequester.Default else subTabFocusRequesters[index + 1]
                                        down = contentFocusRequester
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionUp -> { topTabFocusRequester.requestFocus(); true }
                                                Key.DirectionCenter, Key.Enter -> { onTypeChanged(apiValue); true }
                                                Key.DirectionDown -> { contentFocusRequester.requestFocus(); true }
                                                else -> false
                                            }
                                        } else false
                                    }
                                    .focusable().background(if (isTabFocused) Color.White else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (isTabFocused) Color.Black else Color.White, fontSize = 15.sp)
                                if (currentType == apiValue && !isTabFocused) {
                                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(3.dp).background(Color.White))
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 番組表メイン (Canvas) ---
        Box(modifier = Modifier.fillMaxWidth().weight(1f)
            .focusRequester(contentFocusRequester)
            .onFocusChanged { isContentFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.isLongPress) {
                            updatePositions(0, getNowMinutes())
                            isLongPressHandled = true
                            return@onKeyEvent true
                        }
                    } else if (event.type == KeyEventType.KeyUp) {
                        if (isLongPressHandled) { isLongPressHandled = false; return@onKeyEvent true }
                        jumpMenuFocusRequester.requestFocus(); return@onKeyEvent true
                    }
                }

                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> { updatePositions(focusedCol + 1, focusedMin); true }
                        Key.DirectionLeft -> { updatePositions(focusedCol - 1, focusedMin); true }
                        Key.DirectionDown -> {
                            val next = currentFocusedProgram?.let { Duration.between(baseTime, EpgDataConverter.safeParseTime(it.end_time, baseTime)).toMinutes().toInt() } ?: (focusedMin + 30)
                            updatePositions(focusedCol, next); true
                        }
                        Key.DirectionUp -> {
                            val prev = currentFocusedProgram?.let { Duration.between(baseTime, EpgDataConverter.safeParseTime(it.start_time, baseTime)).toMinutes().toInt() - 1 } ?: (focusedMin - 30)
                            if (prev < 0) { jumpMenuFocusRequester.requestFocus(); true }
                            else { updatePositions(focusedCol, prev); true }
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            currentFocusedProgram?.let { if (it.title != "（番組情報なし）") onProgramSelected(it) }; true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val curX = scrollX
                val curY = scrollY
                val nowTime = OffsetDateTime.now()

                // 1. 番組セル描画
                clipRect(left = twPx, top = hhAreaPx) {
                    filledChannelWrappers.forEachIndexed { c, wrapper ->
                        val x = twPx + curX + (c * cwPx)
                        if (x + cwPx < twPx || x > size.width) return@forEachIndexed

                        wrapper.programs.forEach { p ->
                            val (sOff, dur) = EpgDataConverter.calculateSafeOffsets(p, baseTime)
                            val py = hhAreaPx + curY + (sOff / 60f * hhPx)
                            val ph = (dur / 60f * hhPx)

                            if (py + ph < hhAreaPx || py > size.height) return@forEach

                            val isPast = EpgDataConverter.safeParseTime(p.end_time, baseTime).isBefore(nowTime)
                            val isEmpty = p.title == "（番組情報なし）"

                            drawRect(
                                color = if (isEmpty) Color(0xFF0C0C0C) else if (isPast) Color(0xFF161616) else Color(0xFF222222),
                                topLeft = Offset(x + 1f, py + 1f),
                                size = Size(cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                            )

                            if (ph > 20f) {
                                val titleLayout = textMeasurer.measure(
                                    text = p.title,
                                    style = styles.title.copy(color = if (isPast || isEmpty) Color.Gray else Color.White),
                                    constraints = Constraints(maxWidth = (cwPx - 16f).toInt(), maxHeight = (ph - 12f).toInt().coerceAtLeast(0)),
                                    overflow = TextOverflow.Ellipsis
                                )
                                drawText(titleLayout, topLeft = Offset(x + 10f, py + 8f))
                            }
                        }
                    }

                    // 2. 現在時刻線
                    if (nowTime.isAfter(baseTime) && nowTime.isBefore(limitTime)) {
                        val nowOff = Duration.between(baseTime, nowTime).toMinutes().toFloat()
                        val nowY = hhAreaPx + curY + (nowOff / 60f * hhPx)
                        if (nowY in hhAreaPx..size.height) {
                            drawLine(Color.Red, Offset(twPx, nowY), Offset(size.width, nowY), strokeWidth = 3f)
                            drawCircle(Color.Red, radius = 6f, center = Offset(twPx, nowY))
                        }
                    }

                    // 3. フォーカス枠 & 拡張情報
                    if (isContentFocused && currentFocusedProgram != null) {
                        val fx = twPx + curX + animX
                        val fy = hhAreaPx + curY + animY
                        val fh = animH

                        drawRect(Color(0xFF383838), Offset(fx + 1f, fy + 1f), Size(cwPx - 2f, fh - 2f))

                        val titleLayout = textMeasurer.measure(
                            text = currentFocusedProgram!!.title,
                            style = styles.title,
                            constraints = Constraints(maxWidth = (cwPx - 20f).toInt())
                        )
                        drawText(titleLayout, topLeft = Offset(fx + 10f, fy + 8f))

                        if (currentFocusedProgram!!.title != "（番組情報なし）" && !currentFocusedProgram!!.description.isNullOrBlank()) {
                            val titleH = titleLayout.size.height.toFloat()
                            val descLayout = textMeasurer.measure(
                                text = currentFocusedProgram!!.description ?: "",
                                style = styles.desc,
                                constraints = Constraints(maxWidth = (cwPx - 20f).toInt(), maxHeight = (fh - titleH - 25f).toInt().coerceAtLeast(0)),
                                overflow = TextOverflow.Ellipsis
                            )
                            drawText(descLayout, topLeft = Offset(fx + 10f, fy + titleH + 12f))
                        }
                        drawRoundRect(Color.White, Offset(fx - 1f, fy - 1f), Size(cwPx + 2f, fh + 2f), CornerRadius(2f), Stroke(4f))
                    }
                }

                // 4. 時間軸 (左側)
                clipRect(left = 0f, top = hhAreaPx, right = twPx) {
                    for (h in 0..(maxScrollMinutes / 60)) {
                        val fy = hhAreaPx + curY + (h * hhPx)
                        if (fy + hhPx < hhAreaPx || fy > size.height) continue
                        val hour = (baseTime.hour + h) % 24
                        val bgColor = when(hour) { in 4..10 -> Color(0xFF2E2424); in 11..17 -> Color(0xFF242E24); else -> Color(0xFF24242E) }
                        drawRect(bgColor, Offset(0f, fy), Size(twPx, hhPx))
                        val amPmLayout = textMeasurer.measure(if (hour < 12) "AM" else "PM", styles.amPm)
                        drawText(amPmLayout, topLeft = Offset((twPx - amPmLayout.size.width) / 2, fy + 10f))
                        val hourLayout = textMeasurer.measure(hour.toString(), styles.time)
                        drawText(hourLayout, topLeft = Offset((twPx - hourLayout.size.width) / 2, fy + 26f))
                        drawLine(Color(0xFF444444), Offset(0f, fy), Offset(twPx, fy), 1f)
                    }
                }

                // 5. チャンネルヘッダー (上部)
                clipRect(left = twPx, top = 0f, right = size.width, bottom = hhAreaPx) {
                    drawRect(Color(0xFF111111), Offset(twPx, 0f), Size(size.width, hhAreaPx))
                    filledChannelWrappers.forEachIndexed { c, wrapper ->
                        val x = twPx + curX + (c * cwPx)
                        if (x + cwPx < twPx || x > size.width) return@forEachIndexed

                        val logoW = with(density) { 30.dp.toPx() }
                        val logoH = with(density) { 18.dp.toPx() }
                        val numLayout = textMeasurer.measure(wrapper.channel.channel_number ?: "---", styles.chNum)
                        val startX = x + (cwPx - (logoW + 6f + numLayout.size.width)) / 2

                        if (c < logoPainters.size) {
                            translate(startX, 6f) { with(logoPainters[c]) { draw(Size(logoW, logoH)) } }
                        }
                        drawText(numLayout, topLeft = Offset(startX + logoW + 6f, 6f + (logoH - numLayout.size.height) / 2))

                        val nameLayout = textMeasurer.measure(wrapper.channel.name, styles.chName, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (cwPx - 16f).toInt()))
                        drawText(nameLayout, topLeft = Offset(x + (cwPx - nameLayout.size.width) / 2, 6f + logoH + 2f))
                    }
                }

                // 6. 左上日付
                drawRect(Color.Black, Offset.Zero, Size(twPx, hhAreaPx))
                val disp = baseTime.plusMinutes((-curY / hhPx * 60).toLong().coerceAtLeast(0))
                val dateStr = "${disp.monthValue}/${disp.dayOfMonth}"
                val dayStr = "(${disp.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPANESE)})"
                val dayColor = when (disp.dayOfWeek.value) { 7 -> Color(0xFFFF5252); 6 -> Color(0xFF448AFF); else -> Color.White }
                val dateLayout = textMeasurer.measure(
                    text = AnnotatedString(text = "$dateStr\n$dayStr", spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.White, fontSize = 11.sp), 0, dateStr.length), AnnotatedString.Range(SpanStyle(color = dayColor, fontSize = 11.sp), dateStr.length + 1, dateStr.length + 1 + dayStr.length))),
                    style = styles.dateLabel.copy(textAlign = TextAlign.Center, lineHeight = 14.sp),
                    constraints = Constraints(maxWidth = twPx.toInt())
                )
                drawText(dateLayout, topLeft = Offset((twPx - dateLayout.size.width) / 2, (hhAreaPx - dateLayout.size.height) / 2))
            }
        }
    }
}