package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.rememberAsyncImagePainter
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.epg.engine.*
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
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
    availableTypes: List<String> = emptyList()
) {
    val density = LocalDensity.current
    val config = remember(density) { EpgConfig(density) }
    val epgState = remember { EpgState(config) }
    val textMeasurer = rememberTextMeasurer()
    val drawer = remember(config, textMeasurer) { EpgDrawer(config, textMeasurer) }
    val logoPainters = logoUrls.map { rememberAsyncImagePainter(model = it) }

    val allTabTypes = remember {
        listOf("地デジ" to "GR", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "SKY")
    }
    val visibleTabs = remember(availableTypes) {
        if (availableTypes.isEmpty()) allTabTypes else allTabTypes.filter { it.second in availableTypes }
    }
    val subTabFocusRequesters = remember(visibleTabs.size) { List(visibleTabs.size) { FocusRequester() } }

    var isHeaderVisible by remember { mutableStateOf(true) }
    var pendingHeaderFocusIndex by remember { mutableStateOf<Int?>(null) }
    var lastLoadedType by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isHeaderVisible, pendingHeaderFocusIndex) {
        if (isHeaderVisible && pendingHeaderFocusIndex != null) {
            val index = pendingHeaderFocusIndex!!
            if (index == -2) {
                topTabFocusRequester.requestFocus()
            } else if (index in subTabFocusRequesters.indices) {
                subTabFocusRequesters[index].requestFocus()
            } else if (subTabFocusRequesters.isNotEmpty()) {
                subTabFocusRequesters[0].requestFocus()
            }
            pendingHeaderFocusIndex = null
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is EpgUiState.Success) {
            val isTypeChanged = lastLoadedType != null && lastLoadedType != currentType
            lastLoadedType = currentType
            epgState.updateData(uiState.data, resetFocus = isTypeChanged)
            if (restoreChannelId == null) {
                epgState.updatePositions(0, epgState.getNowMinutes())
            }
        }
    }

    BoxWithConstraints {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LaunchedEffect(w, h) { epgState.updateScreenSize(w, h) }

        LaunchedEffect(restoreChannelId, restoreProgramStartTime, epgState.filledChannelWrappers) {
            if (restoreChannelId != null && epgState.hasData) {
                val colIndex = epgState.filledChannelWrappers.indexOfFirst { it.channel.id == restoreChannelId }
                if (colIndex != -1) {
                    val t = if (restoreProgramStartTime != null) {
                        runCatching { OffsetDateTime.parse(restoreProgramStartTime) }.getOrNull() ?: OffsetDateTime.now()
                    } else OffsetDateTime.now()
                    val targetTime = if (t.isBefore(epgState.baseTime)) OffsetDateTime.now() else t
                    epgState.updatePositions(colIndex, ChronoUnit.MINUTES.between(epgState.baseTime, targetTime).toInt())
                    runCatching { gridFocusRequester.requestFocus() }
                }
            }
        }

        LaunchedEffect(jumpTargetTime) {
            if (jumpTargetTime != null) {
                val jumpMin = Duration.between(epgState.baseTime, jumpTargetTime).toMinutes().toInt()
                epgState.updatePositions(0, jumpMin)
                onJumpFinished()
                gridFocusRequester.requestFocus()
            }
        }

        val snappySpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2500f)
        val scrollX by animateFloatAsState(epgState.targetScrollX, snappySpring, label = "sX")
        val scrollY by animateFloatAsState(epgState.targetScrollY, snappySpring, label = "sY")
        val animX by animateFloatAsState(epgState.targetAnimX, snappySpring, label = "aX")
        val animY by animateFloatAsState(epgState.targetAnimY, snappySpring, label = "aY")
        val animH by animateFloatAsState(epgState.targetAnimH, snappySpring, label = "aH")

        val animValues = EpgAnimValues(scrollX, scrollY, animX, animY, animH)

        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            AnimatedVisibility(
                visible = isHeaderVisible,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                EpgHeaderSection(
                    topTabFocusRequester = topTabFocusRequester,
                    headerFocusRequester = headerFocusRequester,
                    gridFocusRequester = gridFocusRequester,
                    subTabFocusRequesters = subTabFocusRequesters,
                    availableBroadcastingTypes = visibleTabs,
                    onEpgJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                    onTypeChanged = onTypeChanged,
                    currentType = currentType
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                var isContentFocused by remember { mutableStateOf(false) }
                var isBackLongPressed by remember { mutableStateOf(false) }

                if (epgState.hasData) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(gridFocusRequester)
                            .onFocusChanged {
                                isContentFocused = it.isFocused
                                if (it.isFocused) {
                                    isHeaderVisible = false
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.key == Key.Back) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (event.nativeKeyEvent.isLongPress) {
                                            isBackLongPressed = true
                                            epgState.updatePositions(0, epgState.getNowMinutes())
                                            return@onKeyEvent true
                                        }
                                        return@onKeyEvent true
                                    } else if (event.type == KeyEventType.KeyUp) {
                                        if (isBackLongPressed) {
                                            isBackLongPressed = false
                                            return@onKeyEvent true
                                        }
                                        isHeaderVisible = true
                                        val currentIndex = visibleTabs.indexOfFirst { it.second == currentType }
                                        pendingHeaderFocusIndex = currentIndex
                                        return@onKeyEvent true
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
                                                val currentIndex = visibleTabs.indexOfFirst { it.second == currentType }
                                                pendingHeaderFocusIndex = currentIndex
                                                true
                                            } else { epgState.updatePositions(epgState.focusedCol, prev); true }
                                        }
                                        Key.DirectionCenter, Key.Enter -> {
                                            epgState.currentFocusedProgram?.let {
                                                if (it.title != "（番組情報なし）") onProgramSelected(it)
                                            }; true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .focusable()
                            .drawWithCache {
                                onDrawBehind {
                                    // ★修正: 描画時にフォーカス状態を渡す
                                    drawer.draw(this, epgState, animValues, logoPainters, isContentFocused)
                                }
                            }
                    )
                }

                if (uiState is EpgUiState.Loading || epgState.isCalculating) {
                    val bgColor = if (epgState.hasData) Color.Black.copy(alpha = 0.5f) else Color.Black
                    Box(Modifier.fillMaxSize().background(bgColor), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                if (uiState is EpgUiState.Error) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        Text(text = "エラーが発生しました: ${uiState.message}", color = Color.Red)
                    }
                }
            }
        }
    }
}

// EpgHeaderSectionは変更なし
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
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center
        ) {
            availableBroadcastingTypes.forEachIndexed { index, (label, apiValue) ->
                var isTabFocused by remember { mutableStateOf(false) }

                val requester = if (index in subTabFocusRequesters.indices) subTabFocusRequesters[index] else FocusRequester()
                val isTargetForParentFocus = index == currentTypeIndex

                Box(
                    modifier = Modifier.width(110.dp).fillMaxHeight()
                        .onFocusChanged { isTabFocused = it.isFocused }
                        .then(if (isTargetForParentFocus) Modifier.focusRequester(headerFocusRequester) else Modifier)
                        .focusRequester(requester)
                        .focusProperties {
                            left = if (index == 0) jumpMenuFocusRequester else {
                                if (index - 1 in subTabFocusRequesters.indices) subTabFocusRequesters[index - 1] else FocusRequester.Default
                            }
                            right = if (index == availableBroadcastingTypes.size - 1) FocusRequester.Default else {
                                if (index + 1 in subTabFocusRequesters.indices) subTabFocusRequesters[index + 1] else FocusRequester.Default
                            }
                            down = gridFocusRequester
                            up = topTabFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                                topTabFocusRequester.requestFocus(); true
                            } else if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                onTypeChanged(apiValue); true
                            } else false
                        }
                        .focusable()
                        .background(if (isTabFocused) Color.White else Color.Transparent, shape = RectangleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (isTabFocused) Color.Black else Color.White, fontSize = 15.sp)
                    if (currentType == apiValue && !isTabFocused) {
                        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(3.dp).background(Color.White))
                    }
                }
            }
        }

        var isJumpBtnFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(110.dp).fillMaxHeight()
                .onFocusChanged { isJumpBtnFocused = it.isFocused }
                .focusRequester(jumpMenuFocusRequester)
                .focusProperties {
                    right = if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[0] else FocusRequester.Default
                    down = gridFocusRequester
                    up = topTabFocusRequester
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                        onEpgJumpMenuStateChanged(true); true
                    } else false
                }
                .focusable().background(if (isJumpBtnFocused) Color.White else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text("日時指定", color = if (isJumpBtnFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}