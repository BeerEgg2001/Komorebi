@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.rememberAsyncImagePainter
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.epg.engine.*
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.common.safeRequestFocus
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

private const val TAG = "EPG_DEBUG_ENGINE"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ModernEpgCanvasEngine_Smooth(
    uiState: EpgUiState,
    logoUrls: List<String>,
    topTabFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onProgramSelected: (EpgProgram) -> Unit,
    jumpTargetTime: OffsetDateTime?,
    onJumpFinished: () -> Unit,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    currentType: String,
    onTypeChanged: (String) -> Unit,
    restoreChannelId: String? = null,
    restoreProgramStartTime: String? = null,
    availableTypes: List<String> = emptyList(),
    reserves: List<ReserveItem> = emptyList()
) {
    val density = LocalDensity.current
    val config = remember(density) { EpgConfig(density) }
    val epgState = remember { EpgState(config) }
    val textMeasurer = rememberTextMeasurer()
    val drawer = remember(config, textMeasurer) { EpgDrawer(config, textMeasurer) }
    val logoPainters = logoUrls.map { rememberAsyncImagePainter(model = it) }

    // ★追加: 時計アイコンのPainterを生成
    val clockPainter = rememberVectorPainter(Icons.Default.Schedule)

    val reserveMap = remember(reserves) { reserves.associateBy { it.program.id } }

    val visibleTabs = remember(availableTypes) {
        val all = listOf("地デジ" to "GR", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "SKY")
        if (availableTypes.isEmpty()) all else all.filter { it.second in availableTypes }
    }
    val subTabFocusRequesters = remember(visibleTabs.size) { List(visibleTabs.size) { FocusRequester() } }

    var isHeaderVisible by remember { mutableStateOf(true) }
    var pendingHeaderFocusIndex by remember { mutableStateOf<Int?>(null) }
    var lastLoadedType by remember { mutableStateOf<String?>(null) }
    var hasRenderedFirstFrame by remember { mutableStateOf(false) }
    var isJumping by remember { mutableStateOf(false) }

    var isLongPressHandled by remember { mutableStateOf(false) }

    // 初期配置
    LaunchedEffect(epgState.hasData) {
        if (epgState.hasData && !hasRenderedFirstFrame) epgState.jumpToNow()
    }

    // ヘッダー表示時のフォーカス制御
    LaunchedEffect(isHeaderVisible, pendingHeaderFocusIndex) {
        if (isHeaderVisible && pendingHeaderFocusIndex != null) {
            val index = pendingHeaderFocusIndex!!
            delay(50) // 再構築を待機
            if (index == -2) {
                topTabFocusRequester.safeRequestFocus(TAG)
            } else if (index in subTabFocusRequesters.indices) {
                subTabFocusRequesters[index].safeRequestFocus(TAG)
            }
            pendingHeaderFocusIndex = null
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is EpgUiState.Success) {
            val isTypeChanged = lastLoadedType != null && lastLoadedType != currentType
            lastLoadedType = currentType
            if (isTypeChanged) hasRenderedFirstFrame = false
            epgState.updateData(uiState.data, resetFocus = isTypeChanged)
        }
    }

    BoxWithConstraints {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LaunchedEffect(w, h) { epgState.updateScreenSize(w, h) }

        // ジャンプ処理
        LaunchedEffect(jumpTargetTime) {
            if (jumpTargetTime != null && epgState.hasData) {
                isJumping = true
                epgState.jumpToTime(jumpTargetTime)
                val targetMin = ChronoUnit.MINUTES.between(epgState.baseTime, jumpTargetTime).toInt()
                epgState.updatePositions(epgState.focusedCol, targetMin)
                onJumpFinished()
                delay(150)
                isJumping = false
            }
        }

        val scrollSpec = if (isJumping) snap() else spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2500f)
        val scrollX by animateFloatAsState(epgState.targetScrollX, scrollSpec, label = "sX")
        val scrollY by animateFloatAsState(epgState.targetScrollY, scrollSpec, label = "sY")
        val animX by animateFloatAsState(epgState.targetAnimX, scrollSpec, label = "aX")
        val animY by animateFloatAsState(epgState.targetAnimY, scrollSpec, label = "aY")
        val animH by animateFloatAsState(epgState.targetAnimH, scrollSpec, label = "aH")

        val animValues = EpgAnimValues(scrollX, scrollY, animX, animY, animH)

        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AnimatedVisibility(visible = isHeaderVisible, enter = expandVertically(), exit = shrinkVertically()) {
                EpgHeaderSection(
                    topTabFocusRequester = topTabFocusRequester, headerFocusRequester = headerFocusRequester, gridFocusRequester = gridFocusRequester,
                    subTabFocusRequesters = subTabFocusRequesters, availableBroadcastingTypes = visibleTabs,
                    onEpgJumpMenuStateChanged = onEpgJumpMenuStateChanged, onTypeChanged = onTypeChanged, currentType = currentType
                )
            }

            var isContentFocused by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .weight(1f).fillMaxWidth().focusRequester(gridFocusRequester)
                    .onFocusChanged {
                        isContentFocused = it.isFocused
                        if (it.isFocused) isHeaderVisible = false
                    }
                    .onKeyEvent { event ->
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.nativeKeyEvent.isLongPress) {
                                    isJumping = true
                                    isLongPressHandled = true
                                    epgState.jumpToNow()
                                    epgState.updatePositions(0, ChronoUnit.MINUTES.between(epgState.baseTime, OffsetDateTime.now()).toInt())
                                    return@onKeyEvent true
                                }
                                return@onKeyEvent true
                            }
                            if (event.type == KeyEventType.KeyUp) {
                                if (isLongPressHandled) {
                                    isLongPressHandled = false
                                    isJumping = false
                                    return@onKeyEvent true
                                } else {
                                    isJumping = false
                                    isHeaderVisible = true
                                    pendingHeaderFocusIndex = visibleTabs.indexOfFirst { it.second == currentType }.coerceAtLeast(0)
                                    return@onKeyEvent true
                                }
                            }
                        }

                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionRight -> { epgState.updatePositions(epgState.focusedCol + 1, epgState.focusedMin); true }
                                Key.DirectionLeft -> { epgState.updatePositions(epgState.focusedCol - 1, epgState.focusedMin); true }
                                Key.DirectionDown -> {
                                    val next = epgState.currentFocusedProgram?.let {
                                        Duration.between(epgState.baseTime, EpgDataConverter.safeParseTime(it.end_time, epgState.baseTime)).toMinutes().toInt()
                                    } ?: (epgState.focusedMin + 30)
                                    epgState.updatePositions(epgState.focusedCol, next); true
                                }
                                Key.DirectionUp -> {
                                    val prev = epgState.currentFocusedProgram?.let {
                                        Duration.between(epgState.baseTime, EpgDataConverter.safeParseTime(it.start_time, epgState.baseTime)).toMinutes().toInt() - 1
                                    } ?: (epgState.focusedMin - 30)
                                    if (prev < 0) {
                                        isHeaderVisible = true
                                        pendingHeaderFocusIndex = visibleTabs.indexOfFirst { it.second == currentType }.coerceAtLeast(0)
                                        true
                                    } else { epgState.updatePositions(epgState.focusedCol, prev); true }
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    epgState.currentFocusedProgram?.let { if (it.title != "（番組情報なし）") onProgramSelected(it) }
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .focusable()
            ) {
                if (epgState.hasData) {
                    Spacer(modifier = Modifier.fillMaxSize().drawWithCache {
                        onDrawBehind {
                            drawer.draw(
                                drawScope = this,
                                state = epgState,
                                animValues = animValues,
                                logoPainters = logoPainters,
                                isGridFocused = isContentFocused || epgState.hasData,
                                reserveMap = reserveMap,
                                clockPainter = clockPainter // ★追加: 時計アイコンを渡す
                            )
                            hasRenderedFirstFrame = true
                        }
                    })
                }

                if (uiState is EpgUiState.Loading || epgState.isCalculating || (!hasRenderedFirstFrame && !isJumping)) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EpgHeaderSection(
    topTabFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    subTabFocusRequesters: List<FocusRequester>,
    availableBroadcastingTypes: List<Pair<String, String>>,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    onTypeChanged: (String) -> Unit,
    currentType: String
) {
    val jumpMenuFocusRequester = remember { FocusRequester() }
    val currentTypeIndex = remember(currentType, availableBroadcastingTypes) {
        availableBroadcastingTypes.indexOfFirst { it.second == currentType }.coerceAtLeast(0)
    }

    Box(modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF0A0A0A))) {
        Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.Center) {
            availableBroadcastingTypes.forEachIndexed { index, (label, apiValue) ->
                var isTabFocused by remember { mutableStateOf(false) }
                val requester = subTabFocusRequesters[index]
                val isTarget = index == currentTypeIndex

                Box(
                    modifier = Modifier.width(110.dp).fillMaxHeight()
                        .onFocusChanged { isTabFocused = it.isFocused }
                        .then(if (isTarget) Modifier.focusRequester(headerFocusRequester) else Modifier)
                        .focusRequester(requester)
                        .focusProperties {
                            left = if (index == 0) jumpMenuFocusRequester else subTabFocusRequesters[index - 1]
                            right = if (index == availableBroadcastingTypes.size - 1) FocusRequester.Default else subTabFocusRequesters[index + 1]
                            down = gridFocusRequester
                            up = topTabFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.key == Key.Back) {
                                if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                                if (event.type == KeyEventType.KeyUp) {
                                    topTabFocusRequester.safeRequestFocus("EpgHeader")
                                    return@onKeyEvent true
                                }
                            }
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                onTypeChanged(apiValue); true
                            } else false
                        }
                        .focusable().background(if (isTabFocused) Color.White else Color.Transparent, RectangleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (isTabFocused) Color.Black else Color.White, fontSize = 15.sp)
                    if (currentType == apiValue && !isTabFocused) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(3.dp).background(Color.White))
                    }
                }
            }
        }

        var isJumpBtnFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.align(Alignment.CenterStart).width(110.dp).fillMaxHeight()
                .onFocusChanged { isJumpBtnFocused = it.isFocused }
                .focusRequester(jumpMenuFocusRequester)
                .focusProperties {
                    right = if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[0] else FocusRequester.Default
                    down = gridFocusRequester
                    up = topTabFocusRequester
                }
                .onKeyEvent { event ->
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                        if (event.type == KeyEventType.KeyUp) {
                            topTabFocusRequester.safeRequestFocus("EpgJumpBtn")
                            return@onKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                        onEpgJumpMenuStateChanged(true); true
                    } else false
                }
                .focusable().background(if (isJumpBtnFocused) Color.White else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text("日時指定", color = if (isJumpBtnFocused) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}