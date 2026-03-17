@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import android.util.Log
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

/**
 * 独自のCanvas描画を用いた超高速なEPG(電子番組表)コンポーネント。
 * 標準のLazyList系では処理落ちする数千件の番組データを、数学的な座標計算とカリング(画面外の描画省略)
 * を用いてシームレスにスクロールできるようにしています。
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ModernEpgCanvasEngine_Smooth(
    uiState: EpgUiState,
    logoUrls: List<String>,
    topTabFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    jumpButtonFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onProgramSelected: (EpgProgram) -> Unit,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    currentType: String,
    onTypeChanged: (String) -> Unit,
    restoreChannelId: String? = null,
    restoreProgramStartTime: String? = null,
    availableTypes: List<String> = emptyList(),
    reserves: List<ReserveItem> = emptyList(),
    onUpdateTargetTime: (OffsetDateTime) -> Unit,
    onRequestJumpToNow: () -> Unit,
    searchButtonFocusRequester: FocusRequester,
    onSearchClick: () -> Unit
) {
    // ==========================================
    // 1. 初期化とリソースの準備
    // ==========================================
    val density = LocalDensity.current
    val colors = KomorebiTheme.colors

    // 描画設定(EpgConfig)と状態管理(EpgState)を初期化
    val config = remember(density, colors) { EpgConfig(density, colors) }
    val epgState = remember { EpgState(config) }

    // Canvas上でテキストや画像を描画するためのツールを準備
    val textMeasurer = rememberTextMeasurer()
    val drawer = remember(config, textMeasurer) { EpgDrawer(config, textMeasurer) }
    val logoPainters = logoUrls.map { rememberAsyncImagePainter(model = it) }
    val clockPainter = rememberVectorPainter(Icons.Default.Schedule)
    val reserveMap = remember(reserves) { reserves.associateBy { it.program.id } }

    // ヘッダーに表示する放送波タブ（地デジ、BSなど）を決定
    val visibleTabs = remember(availableTypes) {
        val all =
            listOf("地デジ" to "GR", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "SKY")
        if (availableTypes.isEmpty()) all else all.filter { it.second in availableTypes }
    }
    val subTabFocusRequesters =
        remember(visibleTabs.size) { List(visibleTabs.size) { FocusRequester() } }

    // ==========================================
    // 2. UI状態・フラグの管理
    // ==========================================
    var isHeaderVisible by remember { mutableStateOf(true) }
    var pendingHeaderFocusIndex by remember { mutableStateOf<Int?>(null) }
    var lastLoadedType by remember { mutableStateOf<String?>(null) }
    var hasRenderedFirstFrame by remember { mutableStateOf(false) }

    // isJumping: 放送波切り替えや日時ジャンプ時など、アニメーションさせずに一瞬で移動させるためのフラグ
    var isJumping by remember { mutableStateOf(false) }
    var isLongPressHandled by remember { mutableStateOf(false) }

    var lastRequestedTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    var isNextUpdateSeamless by remember { mutableStateOf(false) }

    // 起動時の初期スクロール（現在時刻へのジャンプ）
    LaunchedEffect(epgState.hasData) {
        if (epgState.hasData && !hasRenderedFirstFrame) epgState.jumpToNow()
    }

    // 番組表からヘッダーへフォーカスを戻す際の遅延処理（フォーカスロスト防止）
    LaunchedEffect(isHeaderVisible, pendingHeaderFocusIndex) {
        if (isHeaderVisible && pendingHeaderFocusIndex != null) {
            val index = pendingHeaderFocusIndex!!
            delay(50) // アニメーションの完了を待つ
            if (index == -2) topTabFocusRequester.safeRequestFocus("Epg_TopTab")
            else if (index in subTabFocusRequesters.indices) subTabFocusRequesters[index].safeRequestFocus(
                "Epg_SubTab"
            )
            pendingHeaderFocusIndex = null
        }
    }

    // APIから新しい番組データを受け取った時の処理
    LaunchedEffect(uiState) {
        if (uiState is EpgUiState.Success) {
            val isTypeChanged = lastLoadedType != null && lastLoadedType != currentType
            lastLoadedType = currentType
            if (isTypeChanged) hasRenderedFirstFrame = false // タブ切り替え時は描画をリセット

            // 新データ適用時はアニメーションを無効化(isJumping = true)して即座に反映させる
            isJumping = true
            lastRequestedTargetTime = null

            // EpgStateにデータを流し込み、内部の座標計算を更新
            epgState.updateData(
                newData = uiState.data,
                targetTime = uiState.targetTime,
                resetFocus = isTypeChanged
            )
            isNextUpdateSeamless = false

            delay(100)
            isJumping = false // アニメーションを再有効化
        }
    }

    // ==========================================
    // 3. UI コンポジションとアニメーション
    // ==========================================
    BoxWithConstraints {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        // 画面サイズが変更されたらEpgStateに通知して再計算させる
        LaunchedEffect(w, h) { epgState.updateScreenSize(w, h) }

        // スクロールアニメーションの設定
        // isJumping が true の場合は snap() で一瞬で移動し、それ以外は spring() で滑らかに移動する
        val scrollSpec = if (isJumping) snap() else spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 2500f
        )

        // epgStateの目標座標(targetX)に向かって、実際の描画座標(scrollX)をアニメーションさせる
        val scrollX by animateFloatAsState(epgState.targetScrollX, scrollSpec, label = "sX")
        val scrollY by animateFloatAsState(epgState.targetScrollY, scrollSpec, label = "sY")
        val animX by animateFloatAsState(epgState.targetAnimX, scrollSpec, label = "aX")
        val animY by animateFloatAsState(epgState.targetAnimY, scrollSpec, label = "aY")
        val animH by animateFloatAsState(epgState.targetAnimH, scrollSpec, label = "aH")

        val animValues = EpgAnimValues(scrollX, scrollY, animX, animY, animH)

        Column(modifier = Modifier.fillMaxSize()) {
            // 上部のヘッダー（放送波タブや検索ボタン）
            AnimatedVisibility(
                visible = isHeaderVisible,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                EpgHeaderSection(
                    topTabFocusRequester = topTabFocusRequester,
                    headerFocusRequester = headerFocusRequester,
                    jumpMenuFocusRequester = jumpButtonFocusRequester,
                    searchButtonFocusRequester = searchButtonFocusRequester,
                    gridFocusRequester = gridFocusRequester,
                    subTabFocusRequesters = subTabFocusRequesters,
                    availableBroadcastingTypes = visibleTabs,
                    onEpgJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                    onSearchClick = onSearchClick,
                    onTypeChanged = onTypeChanged,
                    currentType = currentType
                )
            }

            var isContentFocused by remember { mutableStateOf(false) }

            // 番組表のメイン描画領域 (CanvasをホストするBox)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(gridFocusRequester)
                    // グリッドにフォーカスが当たったら、上部のヘッダーを隠して画面を広く使う
                    .onFocusChanged {
                        isContentFocused = it.isFocused
                        if (it.isFocused) isHeaderVisible = false
                    }
                    .onKeyEvent { event ->
                        // 戻るボタン操作のハンドリング
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyDown) {
                                // 長押しで現在時刻へ一発ジャンプ
                                if (event.nativeKeyEvent.isLongPress) {
                                    isJumping = true
                                    isLongPressHandled = true
                                    onRequestJumpToNow()
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
                                    // 通常の「戻る」操作：上部のヘッダーを再表示してフォーカスを戻す
                                    isJumping = false
                                    isHeaderVisible = true
                                    pendingHeaderFocusIndex =
                                        visibleTabs.indexOfFirst { it.second == currentType }
                                            .coerceAtLeast(0)
                                    return@onKeyEvent true
                                }
                            }
                        }

                        // D-Pad 十字キーによるフォーカス（スクロール）移動の制御
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                // 左右: チャンネル（列）の移動
                                Key.DirectionRight -> {
                                    epgState.updatePositions(
                                        epgState.focusedCol + 1,
                                        epgState.focusedMin
                                    ); true
                                }

                                Key.DirectionLeft -> {
                                    epgState.updatePositions(
                                        epgState.focusedCol - 1,
                                        epgState.focusedMin
                                    ); true
                                }

                                // 下: 時間帯（行）の移動
                                Key.DirectionDown -> {
                                    val next = epgState.currentFocusedProgram?.let {
                                        Duration.between(
                                            epgState.baseTime,
                                            EpgDataConverter.safeParseTime(
                                                it.end_time,
                                                epgState.baseTime
                                            )
                                        ).toMinutes().toInt()
                                    } ?: (epgState.focusedMin + 30)

                                    // 現在取得している番組表データの一番下まで到達した場合、翌日のデータをAPIから取得要求する
                                    if (next >= epgState.maxScrollMinutes) {
                                        val currentTarget =
                                            (uiState as? EpgUiState.Success)?.targetTime
                                                ?: OffsetDateTime.now()
                                        val nextDayStart =
                                            epgState.baseTime.plusDays(1).plusHours(1)
                                        if (lastRequestedTargetTime != nextDayStart) {
                                            lastRequestedTargetTime = nextDayStart
                                            onUpdateTargetTime(nextDayStart)
                                        }
                                        true
                                    } else {
                                        epgState.updatePositions(epgState.focusedCol, next); true
                                    }
                                }

                                // 上: 時間帯（行）の移動
                                Key.DirectionUp -> {
                                    val prev = epgState.currentFocusedProgram?.let {
                                        Duration.between(
                                            epgState.baseTime,
                                            EpgDataConverter.safeParseTime(
                                                it.start_time,
                                                epgState.baseTime
                                            )
                                        ).toMinutes().toInt() - 1
                                    } ?: (epgState.focusedMin - 30)

                                    // 取得しているデータの一番上まで到達した場合の処理
                                    if (prev < 0) {
                                        val todayStart =
                                            OffsetDateTime.now().withHour(4).withMinute(0)
                                                .withSecond(0).withNano(0).let {
                                                    if (OffsetDateTime.now().hour < 4) it.minusDays(
                                                        1
                                                    ) else it
                                                }
                                        // すでに一番最初（当日朝4時）の場合は、上部のヘッダーにフォーカスを逃がす
                                        if (!epgState.baseTime.isBefore(todayStart)) {
                                            isHeaderVisible = true
                                            pendingHeaderFocusIndex =
                                                visibleTabs.indexOfFirst { it.second == currentType }
                                                    .coerceAtLeast(0)
                                        } else {
                                            // 前日のデータをAPIから取得要求する
                                            val prevDayEnd = epgState.baseTime.minusHours(1)
                                            if (lastRequestedTargetTime != prevDayEnd) {
                                                lastRequestedTargetTime = prevDayEnd
                                                onUpdateTargetTime(prevDayEnd)
                                            }
                                        }
                                        true
                                    } else {
                                        epgState.updatePositions(epgState.focusedCol, prev); true
                                    }
                                }

                                // 決定キー: 選択中の番組詳細画面を開く
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
            ) {
                if (epgState.hasData) {
                    // drawWithCache で Canvas に描画命令を送信 (EpgDrawerに委譲)
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawBehind {
                                    drawer.draw(
                                        drawScope = this,
                                        state = epgState,
                                        animValues = animValues, // アニメーション中のスクロール座標を渡す
                                        logoPainters = logoPainters,
                                        isGridFocused = isContentFocused || epgState.hasData,
                                        reserveMap = reserveMap,
                                        clockPainter = clockPainter
                                    )
                                    hasRenderedFirstFrame = true
                                }
                            })
                }

                // データ読み込み中、または内部計算中のローディング表示
                if (uiState is EpgUiState.Loading || epgState.isCalculating || (!hasRenderedFirstFrame && !isJumping)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(colors.background.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = colors.textPrimary) }
                }
            }
        }
    }
}

/**
 * 画面上部のヘッダー領域（日時指定ボタン、放送波タブ、検索ボタン）
 */
@Composable
fun EpgHeaderSection(
    topTabFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    jumpMenuFocusRequester: FocusRequester,
    searchButtonFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    subTabFocusRequesters: List<FocusRequester>,
    availableBroadcastingTypes: List<Pair<String, String>>,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    onSearchClick: () -> Unit,
    onTypeChanged: (String) -> Unit,
    currentType: String
) {
    val colors = KomorebiTheme.colors
    val currentTypeIndex = remember(
        currentType,
        availableBroadcastingTypes
    ) { availableBroadcastingTypes.indexOfFirst { it.second == currentType }.coerceAtLeast(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.surface.copy(alpha = 0.95f))
    ) {
        // 中央の放送波選択タブ（地デジ、BS、CSなど）
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center
        ) {
            availableBroadcastingTypes.forEachIndexed { index, (label, apiValue) ->
                var isTabFocused by remember { mutableStateOf(false) }
                val requester = subTabFocusRequesters[index]
                val isTarget = index == currentTypeIndex

                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .fillMaxHeight()
                        .onFocusChanged { isTabFocused = it.isFocused }
                        .then(if (isTarget) Modifier.focusRequester(headerFocusRequester) else Modifier)
                        .focusRequester(requester)
                        .focusProperties {
                            // 十字キーでのフォーカス移動先を指定
                            left =
                                if (index == 0) jumpMenuFocusRequester else subTabFocusRequesters[index - 1]
                            right =
                                if (index == availableBroadcastingTypes.size - 1) searchButtonFocusRequester else subTabFocusRequesters[index + 1]
                            down = gridFocusRequester
                            up = topTabFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                                if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                                if (event.type == KeyEventType.KeyUp) {
                                    topTabFocusRequester.safeRequestFocus("EpgHeader_Back")
                                    return@onKeyEvent true
                                }
                            }
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        topTabFocusRequester.safeRequestFocus("EpgHeader_Up"); return@onKeyEvent true
                                    }

                                    Key.DirectionCenter, Key.Enter -> {
                                        onTypeChanged(apiValue); return@onKeyEvent true
                                    }
                                }
                            }
                            false
                        }
                        .focusable()
                        .background(
                            if (isTabFocused) colors.textPrimary else Color.Transparent,
                            RectangleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (isTabFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
                        fontSize = 15.sp
                    )
                    // 選択中のタブの下部にインジケーター（線）を描画
                    if (currentType == apiValue && !isTabFocused) Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .background(colors.accent)
                    )
                }
            }
        }

        // ============================
        // 検索ボタン (右端)
        // ============================
        var isSearchBtnFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(60.dp)
                .fillMaxHeight()
                .onFocusChanged { isSearchBtnFocused = it.isFocused }
                .focusRequester(searchButtonFocusRequester)
                .focusProperties {
                    left =
                        if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters.last() else jumpMenuFocusRequester
                    down = gridFocusRequester
                    up = topTabFocusRequester
                }
                .onKeyEvent { event ->
                    if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                        if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                        if (event.type == KeyEventType.KeyUp) {
                            topTabFocusRequester.safeRequestFocus("EpgSearch_Back"); return@onKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                topTabFocusRequester.safeRequestFocus("EpgSearch_Up"); return@onKeyEvent true
                            }

                            Key.DirectionCenter, Key.Enter -> {
                                onSearchClick(); return@onKeyEvent true
                            }
                        }
                    }
                    false
                }
                .focusable()
                .background(if (isSearchBtnFocused) colors.textPrimary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "番組検索",
                tint = if (isSearchBtnFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary
            )
        }

        // ============================
        // 日時指定ボタン (左端)
        // ============================
        var isJumpBtnFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(110.dp)
                .fillMaxHeight()
                .onFocusChanged { isJumpBtnFocused = it.isFocused }
                .focusRequester(jumpMenuFocusRequester)
                .focusProperties {
                    right =
                        if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[0] else searchButtonFocusRequester
                    down = gridFocusRequester; up = topTabFocusRequester
                }
                .onKeyEvent { event ->
                    if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                        if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                        if (event.type == KeyEventType.KeyUp) {
                            topTabFocusRequester.safeRequestFocus("EpgJump_Back"); return@onKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                topTabFocusRequester.safeRequestFocus("EpgJump_Up"); return@onKeyEvent true
                            }

                            Key.DirectionCenter, Key.Enter -> {
                                onEpgJumpMenuStateChanged(true); return@onKeyEvent true
                            }
                        }
                    }
                    false
                }
                .focusable()
                .background(if (isJumpBtnFocused) colors.textPrimary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "日時指定",
                color = if (isJumpBtnFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}