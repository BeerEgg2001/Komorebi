package com.example.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.viewmodel.EpgUiState
import com.example.komorebi.ui.theme.NotoSansJP
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.*

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTextApi::class)
@Composable
fun ModernEpgCanvasEngine(
    uiState: EpgUiState.Success,
    logoUrls: List<String>,
    topTabFocusRequester: FocusRequester,
    contentFocusRequester: FocusRequester,
    entryFocusRequester: FocusRequester,
    onProgramSelected: (EpgProgram) -> Unit,
    jumpTargetTime: OffsetDateTime?,
    onJumpFinished: () -> Unit,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit
) {
    val channelWrappers = uiState.data
    val COLUMNS = channelWrappers.size
    val now = OffsetDateTime.now()

    // --- レイアウト定数 (DP) ---
    val channelWidth = 130.dp
    val hourHeight = 80.dp
    val timeBarWidth = 60.dp
    val headerHeight = 56.dp
    val tabHeight = 48.dp
    val minExpandedHeight = 130.dp
    val bottomPadding = 120.dp
    val scrollPadding = 32.dp
    val broadcastingTypes = listOf("地上波", "BS", "CS", "BS4K", "SKY")

    // --- Density 計算 ---
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val cwPx = with(density) { channelWidth.toPx() }
    val hhPx = with(density) { hourHeight.toPx() }
    val twPx = with(density) { timeBarWidth.toPx() }
    val hhAreaPx = with(density) { headerHeight.toPx() }
    val minExpHPx = with(density) { minExpandedHeight.toPx() }
    val bPadPx = with(density) { bottomPadding.toPx() }
    val sPadPx = with(density) { scrollPadding.toPx() }
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val textMeasurer = rememberTextMeasurer()
    val logoPainters = logoUrls.map { rememberAsyncImagePainter(model = it) }

    // baseTimeは起動時（またはデータ更新時）に固定し、スクロールの原点とする
    val baseTime = remember(uiState) { now.minusHours(1).withMinute(0).withSecond(0).withNano(0) }
    val maxScrollMinutes = 1440 * 8 // 8日分程度のスクロールを許容

    // データ補完
    val filledChannelWrappers = remember(channelWrappers, baseTime) {
        channelWrappers.map { wrapper ->
            val sorted = wrapper.programs.sortedBy { it.start_time }
            val filled = mutableListOf<EpgProgram>()
            var last = baseTime
            sorted.forEach { p ->
                val ps = OffsetDateTime.parse(p.start_time)
                if (ps.isAfter(last)) filled.add(createEmptyProgram(wrapper.channel.id, last, ps))
                filled.add(p)
                last = OffsetDateTime.parse(p.end_time)
            }
            val limit = baseTime.plusMinutes(maxScrollMinutes.toLong())
            if (last.isBefore(limit)) filled.add(createEmptyProgram(wrapper.channel.id, last, limit))
            wrapper.copy(programs = filled)
        }
    }

    // フォーカス状態
    var focusedCol by remember { mutableIntStateOf(0) }
    var focusedMin by remember { mutableIntStateOf(Duration.between(baseTime, now).toMinutes().toInt()) }
    var isContentFocused by remember { mutableStateOf(false) }
    var selectedBroadcastingIndex by remember { mutableIntStateOf(0) }
    var currentFocusedProgram by remember { mutableStateOf<EpgProgram?>(null) }

    // フォーカス管理用のRequester
    val subTabFocusRequesters = remember { List(broadcastingTypes.size) { FocusRequester() } }
    val jumpMenuFocusRequester = remember { FocusRequester() }

    // --- ★修正ポイント: ジャンプ処理のトリガーとリセット ---
    var lastHandledJumpTime by remember { mutableStateOf<OffsetDateTime?>(null) }

    LaunchedEffect(jumpTargetTime) {
        if (jumpTargetTime != null && jumpTargetTime != lastHandledJumpTime) {
            val target = jumpTargetTime
            val minutes = Duration.between(baseTime, target).toMinutes().toInt()

            // 範囲内に収める
            focusedMin = minutes.coerceIn(0, maxScrollMinutes - 60)

            lastHandledJumpTime = target
            onJumpFinished() // 親側で jumpTargetTime = null にしてもらう

            // ジャンプ後は番組表にフォーカスを戻す
            contentFocusRequester.requestFocus()
        } else if (jumpTargetTime == null) {
            lastHandledJumpTime = null
        }
    }

    // スクロール・アニメーション制御
    val scrollX = remember { Animatable(0f) }
    val scrollY = remember { Animatable(-(Duration.between(baseTime, now).toMinutes() / 60f * hhPx)) }
    val animX = remember { Animatable(0f) }
    val animY = remember { Animatable(0f) }
    val animH = remember { Animatable(hhPx) }
    val fastSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)

    LaunchedEffect(focusedCol, focusedMin) {
        val channel = filledChannelWrappers[focusedCol]
        val focusTime = baseTime.plusMinutes(focusedMin.toLong())
        val prog = channel.programs.find { p ->
            val s = OffsetDateTime.parse(p.start_time); val e = OffsetDateTime.parse(p.end_time)
            !focusTime.isBefore(s) && focusTime.isBefore(e)
        }
        currentFocusedProgram = prog

        val targetX = focusedCol * cwPx
        val startMin = prog?.let { Duration.between(baseTime, OffsetDateTime.parse(it.start_time)).toMinutes().toFloat() } ?: focusedMin.toFloat()
        val targetY = (startMin / 60f) * hhPx
        val progDur = prog?.let { Duration.between(OffsetDateTime.parse(it.start_time), OffsetDateTime.parse(it.end_time)).toMinutes() } ?: 30L
        val originalH = (progDur / 60f) * hhPx
        val targetH = if (prog?.title == "（番組情報なし）") originalH else (if (originalH < minExpHPx) minExpHPx else originalH)

        launch { animX.animateTo(targetX, fastSpring) }
        launch { animY.animateTo(targetY, fastSpring) }
        launch { animH.animateTo(targetH, fastSpring) }

        val visibleW = screenWidthPx - twPx
        val visibleH = screenHeightPx - (with(density) { tabHeight.toPx() + hhAreaPx })

        var sX = scrollX.value
        if (targetX < -scrollX.value) sX = -targetX
        else if (targetX + cwPx > -scrollX.value + visibleW) sX = -(targetX + cwPx - visibleW)

        var sY = scrollY.value
        if (targetY + targetH > -scrollY.value + visibleH) sY = -(targetY + targetH - visibleH + sPadPx)
        else if (targetY < -scrollY.value) sY = -targetY

        launch { scrollX.animateTo(sX.coerceIn(-(COLUMNS * cwPx - visibleW).coerceAtLeast(0f), 0f), fastSpring) }
        launch { scrollY.animateTo(sY.coerceIn(-((maxScrollMinutes / 60f) * hhPx + bPadPx - visibleH).coerceAtLeast(0f), 0f), fastSpring) }
    }

    // テキストスタイル
    val programTitleStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp) }
    val programDescStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Normal, lineHeight = 13.sp) }
    val channelNumberStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black) }
    val channelNameStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp) }
    val timeTextStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    val amPmStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    val dateLabelStyle = remember { TextStyle(fontFamily = NotoSansJP, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Left) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- 1. 放送波種別タブエリア (日時指定ボタンを含む) ---
        Box(modifier = Modifier.fillMaxWidth().height(tabHeight).background(Color(0xFF0A0A0A)), contentAlignment = Alignment.Center) {
            Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {

                // ★ 日時指定ボタン
                var isJumpButtonFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .fillMaxHeight()
                        .onFocusChanged { isJumpButtonFocused = it.isFocused }
                        .focusRequester(jumpMenuFocusRequester)
                        .focusProperties {
                            up = topTabFocusRequester
                            right = subTabFocusRequesters[0]
                            down = contentFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.Back, Key.Escape -> { topTabFocusRequester.requestFocus(); true }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onEpgJumpMenuStateChanged(true); true }
                                    else -> false
                                }
                            } else false
                        }
                        .focusable()
                        .background(if (isJumpButtonFocused) Color.White else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "日時指定",
                        color = if (isJumpButtonFocused) Color.Black else Color.White,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    )
                }

                // 放送波タブ
                broadcastingTypes.forEachIndexed { index, type ->
                    var isTabFocused by remember { mutableStateOf(false) }
                    val isSelected = selectedBroadcastingIndex == index

                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .fillMaxHeight()
                            .onFocusChanged {
                                isTabFocused = it.isFocused
                                if (it.isFocused) selectedBroadcastingIndex = index
                            }
                            .focusRequester(subTabFocusRequesters[index])
                            .focusProperties {
                                up = topTabFocusRequester
                                left = if (index == 0) jumpMenuFocusRequester else subTabFocusRequesters[index - 1]
                                down = contentFocusRequester
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                    topTabFocusRequester.requestFocus()
                                    true
                                } else false
                            }
                            .focusable()
                            .background(if (isTabFocused) Color.White else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type,
                            color = if (isTabFocused) Color.Black else Color.White,
                            style = TextStyle(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                        )
                        if (isSelected && !isTabFocused) {
                            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.7f).height(3.dp).background(Color.White))
                        }
                    }
                }
            }
        }

        // --- 2. 番組表 Canvas エリア ---
        Box(modifier = Modifier.fillMaxWidth().weight(1f)
            .focusRequester(contentFocusRequester)
            .onFocusChanged { isContentFocused = it.isFocused }
            .focusProperties {
                up = subTabFocusRequesters[selectedBroadcastingIndex]
            }
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) {
                        subTabFocusRequesters[selectedBroadcastingIndex].requestFocus()
                    }
                    return@onKeyEvent true
                }

                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> { if (focusedCol < COLUMNS - 1) { focusedCol++; true } else false }
                        Key.DirectionLeft -> { if (focusedCol > 0) { focusedCol--; true } else false }
                        Key.DirectionDown -> {
                            val next = currentFocusedProgram?.let { Duration.between(baseTime, OffsetDateTime.parse(it.end_time)).toMinutes().toInt() } ?: (focusedMin + 30)
                            if (next < maxScrollMinutes) { focusedMin = next; true } else false
                        }
                        Key.DirectionUp -> {
                            val prev = currentFocusedProgram?.let { Duration.between(baseTime, OffsetDateTime.parse(it.start_time)).toMinutes().toInt() - 1 } ?: (focusedMin - 30)
                            if (prev >= 0) { focusedMin = prev; true } else false
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            currentFocusedProgram?.let { if (it.title != "（番組情報なし）") onProgramSelected(it) }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val curX = scrollX.value; val curY = scrollY.value

                // メインコンテンツ (番組セル)
                clipRect(left = twPx, top = hhAreaPx) {
                    filledChannelWrappers.forEachIndexed { c, wrapper ->
                        val x = twPx + curX + (c * cwPx)
                        if (x + cwPx < twPx || x > size.width) return@forEachIndexed

                        wrapper.programs.forEach { p ->
                            val (sOff, dur) = calculateOffsetMinutes(p, baseTime)
                            val py = hhAreaPx + curY + (sOff / 60f * hhPx)
                            val ph = (dur / 60f * hhPx)

                            if (py + ph > hhAreaPx && py < size.height) {
                                val isPast = OffsetDateTime.parse(p.end_time).isBefore(now)
                                val isEmpty = p.title == "（番組情報なし）"

                                drawRect(
                                    color = if (isEmpty) Color(0xFF080808) else if (isPast) Color(0xFF151515) else Color(0xFF222222),
                                    topLeft = Offset(x + 1f, py + 1f),
                                    size = Size(cwPx - 2f, (ph - 2f).coerceAtLeast(0f))
                                )

                                val titleLayout = textMeasurer.measure(
                                    text = p.title,
                                    style = programTitleStyle.copy(color = if (isPast || isEmpty) Color.Gray else Color.White),
                                    constraints = Constraints(maxWidth = (cwPx - 16f).toInt(), maxHeight = (ph - 12f).toInt().coerceAtLeast(0)),
                                    overflow = TextOverflow.Ellipsis
                                )
                                drawText(titleLayout, topLeft = Offset(x + 10f, py + 8f))

                                if (ph >= minExpHPx && !isEmpty && p.description != null) {
                                    val descY = py + titleLayout.size.height + 10f
                                    val descLayout = textMeasurer.measure(
                                        text = p.description,
                                        style = programDescStyle,
                                        constraints = Constraints(maxWidth = (cwPx - 16f).toInt(), maxHeight = (ph - titleLayout.size.height - 18f).toInt().coerceAtLeast(0)),
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    drawText(descLayout, topLeft = Offset(x + 10f, descY))
                                }
                            }
                        }
                    }

                    // 現在時刻の赤線
                    val nowOff = Duration.between(baseTime, now).toMinutes().toFloat()
                    val nowY = hhAreaPx + curY + (nowOff / 60f * hhPx)
                    if (nowY in hhAreaPx..size.height) {
                        drawLine(Color.Red, Offset(twPx, nowY), Offset(size.width, nowY), strokeWidth = 3f)
                        drawCircle(Color.Red, radius = 6f, center = Offset(twPx, nowY))
                    }

                    // フォーカスインジケータ
                    if (isContentFocused) {
                        val fx = twPx + curX + animX.value; val fy = hhAreaPx + curY + animY.value; val fh = animH.value
                        drawRect(Color(0xFF333333), Offset(fx + 1f, fy + 1f), Size(cwPx - 2f, fh - 2f))
                        currentFocusedProgram?.let { p ->
                            val titleLayout = textMeasurer.measure(p.title, programTitleStyle, constraints = Constraints(maxWidth = (cwPx - 16f).toInt()))
                            drawText(titleLayout, topLeft = Offset(fx + 10f, fy + 8f))
                            if (fh >= minExpHPx && p.description != null) {
                                val descLayout = textMeasurer.measure(p.description, programDescStyle, constraints = Constraints(maxWidth = (cwPx - 16f).toInt(), maxHeight = (fh - titleLayout.size.height - 18f).toInt()))
                                drawText(descLayout, topLeft = Offset(fx + 10f, fy + titleLayout.size.height + 10f))
                            }
                        }
                        drawRoundRect(Color.White, Offset(fx - 1f, fy - 1f), Size(cwPx + 2f, fh + 2f), CornerRadius(2f), Stroke(3f))
                    }
                }

                // --- 3. 左端の時間軸 ---
                clipRect(left = 0f, top = hhAreaPx, right = twPx) {
                    // 表示範囲に応じてループ回数を調整
                    for (h in 0..maxScrollMinutes/60) {
                        val time = baseTime.plusHours(h.toLong())
                        val hour = time.hour
                        val fy = hhAreaPx + curY + (h * hhPx)

                        if (fy + hhPx < hhAreaPx || fy > size.height) continue

                        val bgColor = when(hour) {
                            in 4..9 -> Color(0xFF241D1D)
                            in 10..15 -> Color(0xFF1D241D)
                            in 16..21 -> Color(0xFF1D1D24)
                            else -> Color(0xFF1A151D)
                        }
                        drawRect(bgColor, Offset(0f, fy), Size(twPx, hhPx))
                        val amPmText = if (hour < 12) "AM" else "PM"
                        val amPmLayout = textMeasurer.measure(amPmText, amPmStyle)
                        drawText(amPmLayout, topLeft = Offset((twPx - amPmLayout.size.width) / 2, fy + 15f))
                        val hourLayout = textMeasurer.measure(hour.toString(), timeTextStyle)
                        drawText(hourLayout, topLeft = Offset((twPx - hourLayout.size.width) / 2, fy + 35f))
                        drawLine(Color(0xFF333333), Offset(0f, fy), Offset(twPx, fy), 1f)
                    }
                }

                // --- 4. チャンネルヘッダー ---
                clipRect(left = twPx, top = 0f, right = size.width, bottom = hhAreaPx) {
                    drawRect(Color(0xFF111111), Offset(twPx, 0f), Size(size.width, hhAreaPx))
                    filledChannelWrappers.forEachIndexed { c, wrapper ->
                        val x = twPx + curX + (c * cwPx)
                        if (x + cwPx < twPx || x > size.width) return@forEachIndexed
                        val lWPx = with(density) { 32.dp.toPx() }; val lHPx = with(density) { 20.dp.toPx() }
                        val numLayout = textMeasurer.measure(wrapper.channel.channel_number ?: "", channelNumberStyle)
                        val startX = x + (cwPx - (lWPx + 8f + numLayout.size.width)) / 2
                        if (c < logoPainters.size) { translate(startX, 8f) { with(logoPainters[c]) { draw(Size(lWPx, lHPx)) } } }
                        drawText(numLayout, topLeft = Offset(startX + lWPx + 8f, 8f + (lHPx - numLayout.size.height) / 2))
                        val nameLayout = textMeasurer.measure(wrapper.channel.name, channelNameStyle, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (cwPx - 12f).toInt()))
                        drawText(nameLayout, topLeft = Offset(x + (cwPx - nameLayout.size.width) / 2, 8f + lHPx + 2f))
                    }
                }

                // 左上 (日付・曜日)
                drawRect(Color.Black, Offset.Zero, Size(twPx, hhAreaPx))

                // 表示中の時間帯に応じた日付を表示
                val displayTime = baseTime.plusMinutes((-curY / hhPx * 60).toLong().coerceAtLeast(0))
                val dateStr = "${displayTime.monthValue}/${displayTime.dayOfMonth}"
                val dayOfWeekStr = "(${displayTime.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPANESE)})"

                val dayColor = when (displayTime.dayOfWeek.value) {
                    7 -> Color(0xFFFF5252) // 日
                    6 -> Color(0xFF448AFF) // 土
                    else -> Color.White
                }

                val fullDateLayout = textMeasurer.measure(
                    text = AnnotatedString(
                        text = "$dateStr\n$dayOfWeekStr",
                        spanStyles = listOf(
                            AnnotatedString.Range(SpanStyle(color = Color.White), 0, dateStr.length),
                            AnnotatedString.Range(SpanStyle(color = dayColor), dateStr.length + 1, dateStr.length + 1 + dayOfWeekStr.length)
                        )
                    ),
                    style = dateLabelStyle
                )

                val paddingLeftPx = with(density) { 8.dp.toPx() }
                drawText(
                    fullDateLayout,
                    topLeft = Offset(paddingLeftPx, (hhAreaPx - fullDateLayout.size.height) / 2)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun createEmptyProgram(cid: String, s: OffsetDateTime, e: OffsetDateTime) = EpgProgram(id = "empty_$cid", channel_id = cid, network_id = 0, service_id = 0, event_id = 0, title = "（番組情報なし）", description = "", extended = null, detail = null, start_time = s.toString(), end_time = e.toString(), genres = null, duration = 0, is_free = true, video_type = "", audio_type = "", audio_sampling_rate = "")

@RequiresApi(Build.VERSION_CODES.O)
private fun calculateOffsetMinutes(p: EpgProgram, base: OffsetDateTime): Pair<Float, Float> {
    val s = OffsetDateTime.parse(p.start_time); val e = OffsetDateTime.parse(p.end_time)
    return Duration.between(base, s).toMinutes().toFloat() to Duration.between(s, e).toMinutes().toFloat()
}