package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.components.KeywordConditionCard
import com.beeregg2001.komorebi.ui.components.ReserveCard
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ReserveListScreen"

// 🌟 録画予約タブ専用のチケットシステム
enum class ReserveFocusTicket { NONE, TARGET_RESERVE_ID, TARGET_CONDITION_ID, CONTENT_TOP }

@Stable
class ReserveFocusTicketManager {
    var currentTicket by mutableStateOf(ReserveFocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set
    var targetReserveId by mutableStateOf<Int?>(null)
        private set
    var targetConditionId by mutableStateOf<Int?>(null)
        private set

    fun issueForReserve(reserveId: Int) {
        targetReserveId = reserveId
        currentTicket = ReserveFocusTicket.TARGET_RESERVE_ID
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ ReserveTicket ISSUED: TARGET_RESERVE_ID ($reserveId)")
    }

    fun issueForCondition(conditionId: Int) {
        targetConditionId = conditionId
        currentTicket = ReserveFocusTicket.TARGET_CONDITION_ID
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ ReserveTicket ISSUED: TARGET_CONDITION_ID ($conditionId)")
    }

    fun issueForTop() {
        currentTicket = ReserveFocusTicket.CONTENT_TOP
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ ReserveTicket ISSUED: CONTENT_TOP (Fallback)")
    }

    fun consume(ticket: ReserveFocusTicket) {
        if (currentTicket == ticket) {
            Log.i("KomorebiFocus", "🗑️ ReserveTicket CONSUMED: $currentTicket")
            currentTicket = ReserveFocusTicket.NONE
            targetReserveId = null
            targetConditionId = null
        }
    }
}

@Composable
fun rememberReserveFocusTicketManager() = remember { ReserveFocusTicketManager() }

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ReserveListScreen(
    onBack: () -> Unit,
    onProgramClick: (ReserveItem) -> Unit,
    onConditionClick: (ReservationCondition) -> Unit = {},
    konomiIp: String,
    konomiPort: String,
    groupedChannels: Map<String, List<Channel>> = emptyMap(),
    contentFirstItemRequester: FocusRequester? = null,
    topNavFocusRequester: FocusRequester? = null,
    isReserveOverlayOpen: Boolean = false,
    viewModel: ReserveViewModel = hiltViewModel()
) {
    val reserves by viewModel.reserves.collectAsState()
    val normalReserves by viewModel.normalReserves.collectAsState()
    val conditions by viewModel.conditions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val colors = KomorebiTheme.colors

    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    val tabs = listOf("全ての予約", "通常予約", "キーワード自動予約")

    val listFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }

    val localSafeHouse = remember { FocusRequester() }
    val ticketManager = rememberReserveFocusTicketManager()

    val scope = rememberCoroutineScope()

    val allListState = rememberTvLazyListState()
    val normalListState = rememberTvLazyListState()
    val conditionListState = rememberTvLazyListState()

    var previousOverlayOpen by remember { mutableStateOf(isReserveOverlayOpen) }
    LaunchedEffect(isReserveOverlayOpen) {
        if (previousOverlayOpen && !isReserveOverlayOpen) {
            Log.i("KomorebiFocus", "[ReserveList] オーバーレイが閉じました。チケットを発行します。")
            delay(150)
            val conditionId = viewModel.lastClickedConditionId
            val reserveId = viewModel.lastClickedReserveId
            if (conditionId != null) {
                ticketManager.issueForCondition(conditionId)
            } else if (reserveId != null) {
                ticketManager.issueForReserve(reserveId)
            } else {
                contentFirstItemRequester?.safeRequestFocusWithRetry("ReserveListFallback")
            }
        }
        previousOverlayOpen = isReserveOverlayOpen
    }

    var restoreReserveId by remember { mutableStateOf<Int?>(null) }
    var restoreConditionId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            val resId = viewModel.lastClickedReserveId
            val condId = viewModel.lastClickedConditionId

            if (resId != null || condId != null) {
                Log.i(
                    "KomorebiFocus",
                    "[ReserveList] リロード開始。コンテンツにフォーカスがあるため、IDを退避してSafeHouseへ逃がします。"
                )
                restoreReserveId = resId
                restoreConditionId = condId
                localSafeHouse.safeRequestFocusWithRetry("ReserveLocalSafeHouse")
            } else {
                Log.i(
                    "KomorebiFocus",
                    "[ReserveList] リロード開始。退避するIDがないためSafeHouseへの退避をスキップします。"
                )
            }
        } else {
            if (restoreConditionId != null || restoreReserveId != null) {
                Log.i(
                    "KomorebiFocus",
                    "[ReserveList] リロード完了。退避していたIDでチケットを発行します。"
                )
                delay(200)
                if (restoreConditionId != null) {
                    ticketManager.issueForCondition(restoreConditionId!!)
                    restoreConditionId = null
                } else if (restoreReserveId != null) {
                    ticketManager.issueForReserve(restoreReserveId!!)
                    restoreReserveId = null
                }
            } else {
                Log.i("KomorebiFocus", "[ReserveList] リロード完了。復元すべきIDはありません。")
            }
        }
    }

    LaunchedEffect(
        ticketManager.currentTicket,
        ticketManager.issueTime,
        isLoading,
        reserves.size,
        normalReserves.size,
        conditions.size
    ) {
        if (isLoading) return@LaunchedEffect

        when (ticketManager.currentTicket) {
            ReserveFocusTicket.TARGET_RESERVE_ID -> {
                val targetId = ticketManager.targetReserveId
                if (targetId != null) {
                    var isFound = false
                    when (selectedTabIndex) {
                        0 -> {
                            val index = reserves.indexOfFirst { it.id == targetId }
                            if (index != -1) {
                                allListState.scrollToItem(maxOf(0, index - 1))
                                isFound = true
                            }
                        }

                        1 -> {
                            val index = normalReserves.indexOfFirst { it.id == targetId }
                            if (index != -1) {
                                normalListState.scrollToItem(maxOf(0, index - 1))
                                isFound = true
                            }
                        }
                    }
                    if (!isFound) {
                        Log.w(
                            "KomorebiFocus",
                            "[ReserveList] 対象の予約が見つかりません(削除済)。リスト先頭へ戻ります。"
                        )
                        ticketManager.issueForTop()
                    }
                }
            }

            ReserveFocusTicket.TARGET_CONDITION_ID -> {
                val targetId = ticketManager.targetConditionId
                if (targetId != null) {
                    var isFound = false
                    when (selectedTabIndex) {
                        // 🌟 修正: 条件IDの検索は「自動予約タブ（2）」のみで行うように変更
                        2 -> {
                            val index = conditions.indexOfFirst { it.id == targetId }
                            if (index != -1) {
                                conditionListState.scrollToItem(maxOf(0, index - 1))
                                isFound = true
                            }
                        }
                    }
                    if (!isFound) {
                        Log.w(
                            "KomorebiFocus",
                            "[ReserveList] 対象の条件が見つかりません(削除済)。リスト先頭へ戻ります。"
                        )
                        ticketManager.issueForTop()
                    }
                }
            }

            ReserveFocusTicket.CONTENT_TOP -> {
                Log.i(
                    "KomorebiFocus",
                    "[ReserveList] CONTENT_TOP へのスクロールとフォーカスを実行します。"
                )
                when (selectedTabIndex) {
                    0 -> allListState.scrollToItem(0)
                    1 -> normalListState.scrollToItem(0)
                    2 -> conditionListState.scrollToItem(0)
                }
                delay(150)
                listFocusRequester.safeRequestFocusWithRetry("ReserveContentTop")
                ticketManager.consume(ReserveFocusTicket.CONTENT_TOP)
                viewModel.clearFocusMemory()
            }

            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .onKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                    if (event.type == KeyEventType.KeyUp) {
                        Log.i(
                            "KomorebiFocus",
                            "[ReserveList] 自前で戻るキーを処理 -> TopNavへ移動し、記憶をリセット"
                        )

                        scope.launch {
                            topNavFocusRequester?.safeRequestFocusWithRetry("BackToTopNav")
                        }

                        viewModel.clearFocusMemory()
                        restoreReserveId = null
                        restoreConditionId = null
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        Box(modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(localSafeHouse)
            .focusable())

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .focusGroup()
                .focusProperties {
                    enter = { tabFocusRequesters[selectedTabIndex] }
                },
            contentAlignment = Alignment.Center
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                indicator = { tabPositions, doesTabRowHaveFocus ->
                    TabRowDefaults.UnderlinedIndicator(
                        currentTabPosition = tabPositions[selectedTabIndex],
                        doesTabRowHaveFocus = doesTabRowHaveFocus,
                        activeColor = colors.accent,
                        inactiveColor = Color.Transparent
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = index == selectedTabIndex,
                        onFocus = {
                            viewModel.updateTabIndex(index)
                            viewModel.clearFocusMemory()
                        },
                        onClick = {
                            viewModel.updateTabIndex(index)
                            viewModel.clearFocusMemory()
                        },
                        modifier = Modifier
                            .focusRequester(tabFocusRequesters[index])
                            .then(
                                if (index == selectedTabIndex && contentFirstItemRequester != null) {
                                    Modifier.focusRequester(contentFirstItemRequester)
                                } else Modifier
                            )
                            .focusProperties {
                                down = listFocusRequester
                                up = topNavFocusRequester ?: FocusRequester.Default
                            }
                            .onKeyEvent { event ->
                                if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                    Log.i(
                                        "KomorebiFocus",
                                        "[ReserveList] 内部タブから上キー -> TopNavへ強制移動"
                                    )
                                    topNavFocusRequester?.requestFocus()
                                    viewModel.clearFocusMemory()
                                    return@onKeyEvent true
                                }
                                false
                            },
                        colors = TabDefaults.underlinedIndicatorTabColors(
                            contentColor = colors.textSecondary,
                            selectedContentColor = colors.accent,
                            focusedContentColor = colors.textPrimary,
                            focusedSelectedContentColor = colors.textPrimary
                        )
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else {
            AnimatedContent(
                targetState = selectedTabIndex,
                label = "ReserveTabContent"
            ) { targetIndex ->
                when (targetIndex) {
                    // 🌟 修正: 「すべての予約」タブ
                    0 -> {
                        if (reserves.isEmpty()) { // 条件(conditions)のチェックを削除
                            EmptyMessage(
                                message = "現在、予約されている番組はありません。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[0]
                            )
                        } else {
                            TvLazyColumn(
                                state = allListState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(listFocusRequester)
                                    .focusRestorer()
                                    .focusProperties { up = tabFocusRequesters[0] }
                            ) {
                                // 予約番組（reserves）のみを表示する
                                items(reserves, key = { "all_res_${it.id}" }) { program ->
                                    val specificRequester = remember { FocusRequester() }
                                    LaunchedEffect(
                                        ticketManager.currentTicket,
                                        ticketManager.issueTime
                                    ) {
                                        if (ticketManager.currentTicket == ReserveFocusTicket.TARGET_RESERVE_ID && program.id == ticketManager.targetReserveId) {
                                            delay(150)
                                            specificRequester.safeRequestFocusWithRetry("TargetReserve_All")
                                            ticketManager.consume(ReserveFocusTicket.TARGET_RESERVE_ID)
                                        }
                                    }

                                    ReserveCard(
                                        item = program,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = {
                                            viewModel.lastClickedReserveId = program.id
                                            viewModel.lastClickedConditionId = null
                                            onProgramClick(program)
                                        },
                                        modifier = Modifier
                                            .focusRequester(specificRequester)
                                            .onFocusChanged {
                                                if (it.isFocused || it.hasFocus) {
                                                    viewModel.lastClickedReserveId = program.id
                                                    viewModel.lastClickedConditionId = null
                                                }
                                            }
                                    )
                                }
                                // 🌟 修正: ここにあった `items(conditions)` ブロックを完全に削除しました。
                            }
                        }
                    }

                    // 「通常予約」タブ
                    1 -> {
                        if (normalReserves.isEmpty()) {
                            EmptyMessage(
                                message = "現在、手動で予約されている番組はありません。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[1]
                            )
                        } else {
                            TvLazyColumn(
                                state = normalListState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(listFocusRequester)
                                    .focusRestorer()
                                    .focusProperties { up = tabFocusRequesters[1] }
                            ) {
                                items(normalReserves, key = { "norm_${it.id}" }) { program ->
                                    val specificRequester = remember { FocusRequester() }
                                    LaunchedEffect(
                                        ticketManager.currentTicket,
                                        ticketManager.issueTime
                                    ) {
                                        if (ticketManager.currentTicket == ReserveFocusTicket.TARGET_RESERVE_ID && program.id == ticketManager.targetReserveId) {
                                            delay(150)
                                            specificRequester.safeRequestFocusWithRetry("TargetReserve_Normal")
                                            ticketManager.consume(ReserveFocusTicket.TARGET_RESERVE_ID)
                                        }
                                    }

                                    ReserveCard(
                                        item = program,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = {
                                            viewModel.lastClickedReserveId = program.id
                                            viewModel.lastClickedConditionId = null
                                            onProgramClick(program)
                                        },
                                        modifier = Modifier
                                            .focusRequester(specificRequester)
                                            .onFocusChanged {
                                                if (it.isFocused || it.hasFocus) {
                                                    viewModel.lastClickedReserveId = program.id
                                                    viewModel.lastClickedConditionId = null
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }

                    // 「キーワード自動予約」タブ
                    2 -> {
                        if (conditions.isEmpty()) {
                            EmptyMessage(
                                message = "キーワード自動予約の条件は登録されていません。\n番組表の番組詳細から「EPG予約」が可能です。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[2]
                            )
                        } else {
                            TvLazyColumn(
                                state = conditionListState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(listFocusRequester)
                                    .focusRestorer()
                                    .focusProperties { up = tabFocusRequesters[2] }
                            ) {
                                items(conditions, key = { "cond_${it.id}" }) { condition ->
                                    val specificRequester = remember { FocusRequester() }
                                    LaunchedEffect(
                                        ticketManager.currentTicket,
                                        ticketManager.issueTime
                                    ) {
                                        if (ticketManager.currentTicket == ReserveFocusTicket.TARGET_CONDITION_ID && condition.id == ticketManager.targetConditionId) {
                                            delay(150)
                                            specificRequester.safeRequestFocusWithRetry("TargetCondition_Tab2")
                                            ticketManager.consume(ReserveFocusTicket.TARGET_CONDITION_ID)
                                        }
                                    }

                                    KeywordConditionCard(
                                        condition = condition,
                                        onClick = {
                                            viewModel.lastClickedConditionId = condition.id
                                            viewModel.lastClickedReserveId = null
                                            onConditionClick(condition)
                                        },
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        groupedChannels = groupedChannels,
                                        reserves = reserves,
                                        modifier = Modifier
                                            .focusRequester(specificRequester)
                                            .onFocusChanged {
                                                if (it.isFocused || it.hasFocus) {
                                                    viewModel.lastClickedConditionId = condition.id
                                                    viewModel.lastClickedReserveId = null
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EmptyMessage(
    message: String,
    listFocusRequester: FocusRequester,
    targetTabRequester: FocusRequester
) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocusRequester)
            .focusRestorer()
            .focusProperties { up = targetTabRequester }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}