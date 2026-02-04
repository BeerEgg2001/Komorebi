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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import coil.compose.rememberAsyncImagePainter
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.viewmodel.EpgUiState
import com.example.komorebi.ui.theme.NotoSansJP
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.*

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTextApi::class)
@Composable
fun ModernEpgCanvasEngine(
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
    onTypeChanged: (String) -> Unit
) {
    // 1. 直近の成功データを保持
    val lastSuccessData = remember { mutableStateOf<EpgUiState.Success?>(null) }
    if (uiState is EpgUiState.Success) {
        lastSuccessData.value = uiState
    }

    val displayData = lastSuccessData.value ?: return

    // 2. 放送波種別の定義
    val availableBroadcastingTypes = remember {
        listOf(
            "地上波" to "GR",
            "BS" to "BS",
            "CS" to "CS",
            "BS4K" to "BS4K",
            "SKY" to "SKY"
        )
    }

    // --- レイアウト定数 ---
    val channelWidth = 130.dp
    val hourHeight = 80.dp
    val timeBarWidth = 60.dp
    val headerHeight = 45.dp
    val tabHeight = 48.dp
    val minExpandedHeight = 140.dp
    val bottomPadding = 120.dp
    val scrollPadding = 32.dp

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

    // --- 状態管理 ---
    var focusedCol by remember { mutableIntStateOf(0) }

    val baseTime = remember(displayData) {
        val earliest = displayData.data.flatMap { it.programs }
            .mapNotNull { runCatching { OffsetDateTime.parse(it.start_time) }.getOrNull() }
            .minByOrNull { it.toEpochSecond() }
            ?: OffsetDateTime.now()
        earliest.withMinute(0).withSecond(0).withNano(0)
    }

    val maxScrollMinutes = 1440 * 14
    val limitTime = remember(baseTime) { baseTime.plusMinutes(maxScrollMinutes.toLong()) }

    val filledChannelWrappers = remember(displayData.data, baseTime) {
        displayData.data.map { wrapper ->
            wrapper.copy(
                programs = EpgDataConverter.getFilledPrograms(
                    wrapper.channel.id, wrapper.programs, baseTime, limitTime
                )
            )
        }
    }
    val COLUMNS = filledChannelWrappers.size

    val initialFocusedMin = remember(baseTime) {
        val now = OffsetDateTime.now()
        if (now.isBefore(baseTime)) 0
        else Duration.between(baseTime, now).toMinutes().toInt().coerceIn(0, maxScrollMinutes)
    }

    var focusedMin by remember { mutableIntStateOf(initialFocusedMin) }
    var isContentFocused by remember { mutableStateOf(false) }

    val selectedBroadcastingIndex = remember(currentType, availableBroadcastingTypes) {
        availableBroadcastingTypes.indexOfFirst { it.second == currentType }.coerceAtLeast(0)
    }

    var currentFocusedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    val subTabFocusRequesters = remember(availableBroadcastingTypes) {
        List(availableBroadcastingTypes.size) { FocusRequester() }
    }
    val jumpMenuFocusRequester = remember { FocusRequester() }

    // ジャンプ中の排他制御用
    var isJumping by remember { mutableStateOf(false) }

    // タブ切り替え後のフォーカス維持
    LaunchedEffect(currentType) {
        if (!isContentFocused) {
            val idx = availableBroadcastingTypes.indexOfFirst { it.second == currentType }.coerceAtLeast(0)
            subTabFocusRequesters.getOrNull(idx)?.requestFocus()
        }
    }

    // --- アニメーション ---
    val fastSpring = spring<Float>(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy)
    val scrollX = remember { Animatable(0f) }
    val initialScrollY = -(initialFocusedMin / 60f * hhPx)
    val scrollY = remember { Animatable(initialScrollY) }
    val animX = remember { Animatable(0f) }
    val animY = remember { Animatable((initialFocusedMin / 60f * hhPx)) }
    val animH = remember { Animatable(hhPx) }

    // --- 日時指定ジャンプ実行ロジック (修正版：フォーカス強制移動を追加) ---
    LaunchedEffect(jumpTargetTime) {
        if (jumpTargetTime != null) {
            isJumping = true

            // 1. ジャンプ先分数を計算
            val diffMinutes = Duration.between(baseTime, jumpTargetTime).toMinutes().toInt()
            val safeMinutes = diffMinutes.coerceIn(0, maxScrollMinutes - 60)

            // 2. インデックス更新（左上のセルを狙うため Col=0）
            focusedCol = 0
            focusedMin = safeMinutes

            // 3. ジャンプ先の番組を即座に特定してアニメーション目標をセット
            if (filledChannelWrappers.isNotEmpty()) {
                val channel = filledChannelWrappers[0]
                val focusTime = baseTime.plusMinutes(safeMinutes.toLong())
                val prog = channel.programs.find { p ->
                    val s = EpgDataConverter.safeParseTime(p.start_time, baseTime)
                    val e = EpgDataConverter.safeParseTime(p.end_time, s.plusMinutes(1))
                    !focusTime.isBefore(s) && focusTime.isBefore(e)
                }
                currentFocusedProgram = prog

                // 座標の即時反映
                val (sOff, dur) = prog?.let { EpgDataConverter.calculateSafeOffsets(it, baseTime) } ?: (safeMinutes.toFloat() to 30f)
                val targetH = if (prog?.title == "（番組情報なし）") (dur/60f*hhPx) else (dur/60f*hhPx).coerceAtLeast(minExpHPx)

                launch { animX.snapTo(0f) }
                launch { animY.snapTo((sOff / 60f) * hhPx) }
                launch { animH.snapTo(targetH) }
            }

            // 4. スクロールアニメーション
            val targetScrollY = -(safeMinutes / 60f * hhPx)
            val visibleH = screenHeightPx - with(density) { tabHeight.toPx() + headerHeight.toPx() }
            val maxScrollYLimit = -((maxScrollMinutes / 60f) * hhPx + bPadPx - visibleH).coerceAtLeast(0f)

            launch { scrollX.animateTo(0f, fastSpring) }
            scrollY.animateTo(targetScrollY.coerceIn(maxScrollYLimit, 0f), fastSpring)

            // 5. 完了通知とフォーカスの強制取得
            onJumpFinished()
            contentFocusRequester.requestFocus()

            delay(200)
            isJumping = false
        }
    }

    // --- フォーカス追従ロジック ---
    LaunchedEffect(focusedCol, focusedMin, filledChannelWrappers) {
        if (isJumping || filledChannelWrappers.isEmpty()) return@LaunchedEffect

        val safeCol = focusedCol.coerceIn(0, (filledChannelWrappers.size - 1).coerceAtLeast(0))
        val channel = filledChannelWrappers[safeCol]
        val focusTime = baseTime.plusMinutes(focusedMin.toLong())

        val prog = channel.programs.find { p ->
            val s = EpgDataConverter.safeParseTime(p.start_time, baseTime)
            val e = EpgDataConverter.safeParseTime(p.end_time, s.plusMinutes(1))
            !focusTime.isBefore(s) && focusTime.isBefore(e)
        }
        currentFocusedProgram = prog

        val (sOff, dur) = prog?.let { EpgDataConverter.calculateSafeOffsets(it, baseTime) } ?: (focusedMin.toFloat() to 30f)
        val targetX = safeCol * cwPx
        val targetY = (sOff / 60f) * hhPx
        val originalH = (dur / 60f) * hhPx
        val targetH = if (prog?.title == "（番組情報なし）") originalH else originalH.coerceAtLeast(minExpHPx)

        launch { animX.animateTo(targetX, fastSpring) }
        launch { animY.animateTo(targetY, fastSpring) }
        launch { animH.animateTo(targetH, fastSpring) }

        val visibleW = screenWidthPx - twPx
        val visibleH = screenHeightPx - (with(density) { tabHeight.toPx() + headerHeight.toPx() })

        var sX = scrollX.value
        if (targetX < -scrollX.value) sX = -targetX
        else if (targetX + cwPx > -scrollX.value + visibleW) sX = -(targetX + cwPx - visibleW)

        var sY = scrollY.value
        if (targetY + targetH > -scrollY.value + visibleH) sY = -(targetY + targetH - visibleH + sPadPx)
        if (targetY < -scrollY.value) sY = -targetY

        val maxScrollYLimit = -((maxScrollMinutes / 60f) * hhPx + bPadPx - visibleH).coerceAtLeast(0f)

        launch { scrollX.animateTo(sX.coerceIn(-(COLUMNS * cwPx - visibleW).coerceAtLeast(0f), 0f), fastSpring) }
        launch { scrollY.animateTo(sY.coerceIn(maxScrollYLimit, 0f), fastSpring) }
    }

    // スタイル定義 (省略せず維持)
    val programTitleStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp) }
    val programDescStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Normal, lineHeight = 13.sp) }
    val channelNumberStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black) }
    val channelNameStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp) }
    val timeTextStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    val amPmStyle = remember { TextStyle(fontFamily = NotoSansJP, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    val dateLabelStyle = remember { TextStyle(fontFamily = NotoSansJP, fontSize = 12.sp, fontWeight = FontWeight.Bold) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- 放送波・日時指定タブエリア ---
        Box(modifier = Modifier.fillMaxWidth().height(tabHeight).background(Color(0xFF0A0A0A))) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                var isJumpButtonFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier.width(130.dp).fillMaxHeight()
                        .onFocusChanged { isJumpButtonFocused = it.isFocused }
                        .focusRequester(jumpMenuFocusRequester)
                        .focusProperties {
                            up = topTabFocusRequester
                            right = if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[0] else FocusRequester.Default
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
                        .focusable().background(if (isJumpButtonFocused) Color.White else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text("日時指定", color = if (isJumpButtonFocused) Color.Black else Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Row(modifier = Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        availableBroadcastingTypes.forEachIndexed { index, (label, apiValue) ->
                            var isTabFocused by remember { mutableStateOf(false) }
                            val isSelected = currentType == apiValue
                            Box(
                                modifier = Modifier.width(110.dp).fillMaxHeight()
                                    .onFocusChanged {
                                        isTabFocused = it.isFocused
                                        if (it.isFocused && currentType != apiValue) {
                                            onTypeChanged(apiValue)
                                        }
                                    }
                                    .focusRequester(subTabFocusRequesters[index])
                                    .focusProperties {
                                        up = topTabFocusRequester
                                        left = if (index == 0) jumpMenuFocusRequester else subTabFocusRequesters[index - 1]
                                        right = if (index == availableBroadcastingTypes.size - 1) FocusRequester.Default else subTabFocusRequesters[index + 1]
                                        down = contentFocusRequester
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                            topTabFocusRequester.requestFocus(); true
                                        } else false
                                    }
                                    .focusable().background(if (isTabFocused) Color.White else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (isTabFocused) Color.Black else Color.White, style = TextStyle(fontSize = 15.sp))
                                if (isSelected && !isTabFocused) {
                                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(3.dp).background(Color.White))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(130.dp))
            }
        }

        // --- 番組表 Canvas ---
        Box(modifier = Modifier.fillMaxWidth().weight(1f)
            .focusRequester(contentFocusRequester)
            .onFocusChanged { isContentFocused = it.isFocused }
            .focusProperties {
                if (subTabFocusRequesters.isNotEmpty()) {
                    up = subTabFocusRequesters[selectedBroadcastingIndex.coerceIn(0, subTabFocusRequesters.size - 1)]
                }
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> { if (focusedCol < COLUMNS - 1) { focusedCol++; true } else false }
                        Key.DirectionLeft -> { if (focusedCol > 0) { focusedCol--; true } else false }
                        Key.DirectionDown -> {
                            val next = currentFocusedProgram?.let { Duration.between(baseTime, EpgDataConverter.safeParseTime(it.end_time, baseTime)).toMinutes().toInt() } ?: (focusedMin + 30)
                            if (next < maxScrollMinutes) { focusedMin = next; true } else false
                        }
                        Key.DirectionUp -> {
                            val prev = currentFocusedProgram?.let { Duration.between(baseTime, EpgDataConverter.safeParseTime(it.start_time, baseTime)).toMinutes().toInt() - 1 } ?: (focusedMin - 30)
                            if (prev >= 0) { focusedMin = prev; true } else false
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            currentFocusedProgram?.let { if (it.title != "（番組情報なし）") onProgramSelected(it) }; true
                        }
                        Key.Back, Key.Escape -> {
                            if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[selectedBroadcastingIndex].requestFocus()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
        ) {
            if (uiState is EpgUiState.Loading) {
                Box(modifier = Modifier.fillMaxSize().zIndex(5f).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val curX = scrollX.value; val curY = scrollY.value

                // A. メインコンテンツ
                clipRect(left = twPx, top = hhAreaPx) {
                    filledChannelWrappers.forEachIndexed { c, wrapper ->
                        val x = twPx + curX + (c * cwPx)
                        if (x + cwPx < twPx || x > size.width) return@forEachIndexed

                        wrapper.programs.forEach { p ->
                            val (sOff, dur) = EpgDataConverter.calculateSafeOffsets(p, baseTime)
                            val py = hhAreaPx + curY + (sOff / 60f * hhPx)
                            val ph = (dur / 60f * hhPx)

                            if (py + ph > hhAreaPx && py < size.height) {
                                val isPast = EpgDataConverter.safeParseTime(p.end_time, baseTime).isBefore(OffsetDateTime.now())
                                val isEmpty = p.title == "（番組情報なし）"

                                drawRect(
                                    color = if (isEmpty) Color(0xFF0C0C0C) else if (isPast) Color(0xFF161616) else Color(0xFF222222),
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

                                if (!isEmpty && !p.description.isNullOrBlank()) {
                                    val titleHeight = titleLayout.size.height.toFloat()
                                    val availableDescHeight = (ph - titleHeight - 20f).coerceAtLeast(0f)
                                    if (availableDescHeight > 24f) {
                                        val descLayout = textMeasurer.measure(
                                            text = p.description,
                                            style = programDescStyle.copy(color = if (isPast) Color.DarkGray else Color.LightGray),
                                            constraints = Constraints(maxWidth = (cwPx - 16f).toInt(), maxHeight = availableDescHeight.toInt()),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        drawText(descLayout, topLeft = Offset(x + 10f, py + titleHeight + 12f))
                                    }
                                }
                            }
                        }
                    }

                    // 現在時刻線
                    val now = OffsetDateTime.now()
                    if (now.isAfter(baseTime) && now.isBefore(limitTime)) {
                        val nowOff = Duration.between(baseTime, now).toMinutes().toFloat()
                        val nowY = hhAreaPx + curY + (nowOff / 60f * hhPx)
                        if (nowY in hhAreaPx..size.height) {
                            drawLine(Color.Red, Offset(twPx, nowY), Offset(size.width, nowY), strokeWidth = 3f)
                            drawCircle(Color.Red, radius = 6f, center = Offset(twPx, nowY))
                        }
                    }

                    // 拡大表示 (フォーカス枠)
                    if (isContentFocused) {
                        val fx = twPx + curX + animX.value
                        val fy = hhAreaPx + curY + animY.value
                        val fh = animH.value
                        drawRect(Color(0xFF383838), Offset(fx + 1f, fy + 1f), Size(cwPx - 2f, fh - 2f))
                        currentFocusedProgram?.let { p ->
                            val titleLayout = textMeasurer.measure(text = p.title, style = programTitleStyle, constraints = Constraints(maxWidth = (cwPx - 20f).toInt()))
                            drawText(titleLayout, topLeft = Offset(fx + 10f, fy + 8f))
                            if (p.title != "（番組情報なし）" && !p.description.isNullOrBlank()) {
                                val titleHeight = titleLayout.size.height.toFloat()
                                val availableDescHeight = (fh - titleHeight - 20f).coerceAtLeast(0f)
                                if (availableDescHeight > 20f) {
                                    val descLayout = textMeasurer.measure(text = p.description, style = programDescStyle, constraints = Constraints(maxWidth = (cwPx - 20f).toInt(), maxHeight = availableDescHeight.toInt()), overflow = TextOverflow.Ellipsis)
                                    drawText(descLayout, topLeft = Offset(fx + 10f, fy + titleHeight + 12f))
                                }
                            }
                        }
                        drawRoundRect(Color.White, Offset(fx - 1f, fy - 1f), Size(cwPx + 2f, fh + 2f), CornerRadius(2f), Stroke(4f))
                    }
                }

                // B. 時間軸
                clipRect(left = 0f, top = hhAreaPx, right = twPx) {
                    for (h in 0..(maxScrollMinutes / 60)) {
                        val time = baseTime.plusHours(h.toLong())
                        val fy = hhAreaPx + curY + (h * hhPx)
                        if (fy + hhPx < hhAreaPx || fy > size.height) continue
                        val hour = time.hour
                        val bgColor = when(hour) { in 4..10 -> Color(0xFF2E2424); in 11..17 -> Color(0xFF242E24); else -> Color(0xFF24242E) }
                        drawRect(bgColor, Offset(0f, fy), Size(twPx, hhPx))
                        val amPmLayout = textMeasurer.measure(if (hour < 12) "AM" else "PM", amPmStyle)
                        drawText(amPmLayout, topLeft = Offset((twPx - amPmLayout.size.width) / 2, fy + 10f))
                        val hourLayout = textMeasurer.measure(hour.toString(), timeTextStyle)
                        drawText(hourLayout, topLeft = Offset((twPx - hourLayout.size.width) / 2, fy + 26f))
                        drawLine(Color(0xFF444444), Offset(0f, fy), Offset(twPx, fy), 1f)
                    }
                }

                // C. チャンネルヘッダー
                clipRect(left = twPx, top = 0f, right = size.width, bottom = hhAreaPx) {
                    drawRect(Color(0xFF111111), Offset(twPx, 0f), Size(size.width, hhAreaPx))
                    filledChannelWrappers.forEachIndexed { c, wrapper ->
                        val x = twPx + curX + (c * cwPx)
                        if (x + cwPx < twPx || x > size.width) return@forEachIndexed
                        val lWPx = with(density) { 30.dp.toPx() }; val lHPx = with(density) { 18.dp.toPx() }
                        val numLayout = textMeasurer.measure(wrapper.channel.channel_number ?: "---", channelNumberStyle)
                        val startX = x + (cwPx - (lWPx + 6f + numLayout.size.width)) / 2

                        if (c < logoPainters.size) { translate(startX, 6f) { with(logoPainters[c]) { draw(Size(lWPx, lHPx)) } } }
                        drawText(numLayout, topLeft = Offset(startX + lWPx + 6f, 6f + (lHPx - numLayout.size.height) / 2))

                        val nameLayout = textMeasurer.measure(wrapper.channel.name, channelNameStyle, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (cwPx - 16f).toInt()))
                        drawText(nameLayout, topLeft = Offset(x + (cwPx - nameLayout.size.width) / 2, 6f + lHPx + 2f))
                    }
                }

                // D. 左上日付
                drawRect(Color.Black, Offset.Zero, Size(twPx, hhAreaPx))
                val displayTime = baseTime.plusMinutes((-curY / hhPx * 60).toLong().coerceAtLeast(0))
                val dateStr = "${displayTime.monthValue}/${displayTime.dayOfMonth}"
                val dayOfWeekStr = "(${displayTime.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPANESE)})"
                val dayColor = when (displayTime.dayOfWeek.value) { 7 -> Color(0xFFFF5252); 6 -> Color(0xFF448AFF); else -> Color.White }
                val fullDateLayout = textMeasurer.measure(
                    text = AnnotatedString(text = "$dateStr\n$dayOfWeekStr", spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.White, fontSize = 11.sp), 0, dateStr.length), AnnotatedString.Range(SpanStyle(color = dayColor, fontSize = 11.sp), dateStr.length + 1, dateStr.length + 1 + dayOfWeekStr.length))),
                    style = dateLabelStyle.copy(textAlign = TextAlign.Center, lineHeight = 14.sp),
                    constraints = Constraints(maxWidth = twPx.toInt())
                )
                drawText(fullDateLayout, topLeft = Offset((twPx - fullDateLayout.size.width) / 2, (hhAreaPx - fullDateLayout.size.height) / 2))
            }
        }
    }
}