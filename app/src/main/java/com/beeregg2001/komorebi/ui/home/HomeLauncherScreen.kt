@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ColdStartDiag
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.reserve.ReserveListScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "HomeLauncher"

/**
 * タブのコンテンツが「表示できる状態になった」ことを通知するヘルパー。
 *
 * 以前は各タブで一律 `delay(500)`〜`delay(800)` を挟んでから準備完了を通知していた。
 * これはコンテンツが描画される前にローディング表示が消えてしまう「一瞬の空表示」を
 * 避けるための対症療法だったが、実際のデータ取得がどれだけ速く終わっても
 * 必ずその時間だけ待たされるため、起動直後や設定画面から戻った直後の
 * ローディング表示が不必要に長引く原因になっていた。
 *
 * ここでは固定時間の代わりに、
 *  1. そのタブが表示に必要とするデータが揃ったか（[isDataAvailable]）
 *  2. 揃った後、実際に1フレーム描画されたか（[withFrameNanos]）
 * の2点を条件にする。データが空のまま（録画0件・予約0件など）でも
 * ローディング表示に固定されないよう、[fallbackTimeoutMs] の保険も併用する。
 *
 * この関数自体が独立したリコンポーズ範囲になるため、内部での状態購読が
 * 巨大な HomeLauncherScreen 全体のリコンポーズを誘発しない点も重要。
 */
@Composable
private fun TabReadyNotifier(
    isDataAvailable: Boolean,
    fallbackTimeoutMs: Long = 1000L,
    onReady: () -> Unit
) {
    LaunchedEffect(isDataAvailable) {
        if (isDataAvailable) {
            // データ到達直後はまだレイアウト・描画が済んでいない。
            // 次フレームまで待ってから通知することで、固定 delay に頼らずに
            // 「中身が出る前にローディングが消える」ちらつきを防ぐ。
            withFrameNanos { }
            onReady()
        }
    }

    // データが最後まで空のままでも操作可能にするための保険。
    LaunchedEffect(Unit) {
        delay(fallbackTimeoutMs)
        onReady()
    }
}

/**
 * 録画予約タブ用。予約一覧の読み込み状態に連動させる。
 * `isLoading` の購読をこの小さな関数の中に閉じ込め、
 * HomeLauncherScreen 全体のリコンポーズを誘発しないようにしている。
 */
@Composable
private fun ReserveTabReadyNotifier(
    reserveViewModel: ReserveViewModel,
    onReady: () -> Unit
) {
    val isReserveLoading by reserveViewModel.isLoading.collectAsState()
    TabReadyNotifier(isDataAvailable = !isReserveLoading, onReady = onReady)
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DigitalClock(timeFormat: String, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
    val formattedTime = currentTime.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))

    Text(
        text = formattedTime,
        color = KomorebiTheme.colors.textPrimary,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
        modifier = modifier
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    settingsViewModel: SettingsViewModel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String, mirakurunPort: String,
    konomiIp: String, konomiPort: String,
    onChannelClick: (Channel?, Boolean) -> Unit,
    selectedChannel: Channel?,
    onTabChange: (Int) -> Unit,
    initialTabIndex: Int = 0,
    selectedProgram: RecordedProgram?,
    onProgramSelected: (RecordedProgram?) -> Unit,
    epgSelectedProgram: EpgProgram?,
    onEpgProgramSelected: (EpgProgram?) -> Unit,
    onReserveSelected: (ReserveItem) -> Unit = {},
    onConditionClick: (ReservationCondition) -> Unit = {},
    isReserveOverlayOpen: Boolean = false,
    isEpgJumpMenuOpen: Boolean,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    triggerBack: Boolean,
    onBackTriggered: () -> Unit,
    onFinalBack: () -> Unit,
    onUiReady: () -> Unit,
    isUiReadyFlag: Boolean,
    onNavigateToPlayer: (String, String, String) -> Unit,
    lastPlayerChannelId: String? = null,
    lastPlayerProgramId: String? = null,
    isSettingsOpen: Boolean = false,
    onSettingsToggle: (Boolean) -> Unit = {},
    isRecordListOpen: Boolean = false,
    onShowAllRecordings: () -> Unit = {},
    onCloseRecordList: () -> Unit = {},
    onShowSeriesList: () -> Unit = {},
    onShowSmbLibrary: () -> Unit = {},
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    hasActivePlayer: Boolean = false,
    onReturnToPlayerClick: () -> Unit = {},
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {},
    // フルスクリーンのプレイヤーが手前に出ている間は true。
    // ホーム画面はプレイヤー表示中も(スクロール位置とフォーカスを保つため)
    // Compose ツリーに残り続けるので、見えていない間の定期通信を明示的に止める。
    isPlayerActiveFullScreen: Boolean = false
) {
    // ★ 追加(診断用): コールドブート計測用。詳細はhandleUiReady定義箇所を参照。
    var hasLoggedFirstUiReady by remember { mutableStateOf(false) }

    val ui = rememberHomeLauncherState(
        initialTabIndex,
        channelViewModel,
        homeViewModel,
        epgViewModel,
        recordViewModel,
        reserveViewModel
    )
    val colors = KomorebiTheme.colors

    val ticketManager = rememberHomeFocusTicketManager()
    val scope = rememberCoroutineScope()

    val favoriteBaseballTeams by homeViewModel.favoriteBaseballTeams.collectAsState()
    val favoriteBaseballGames by homeViewModel.favoriteBaseballGames.collectAsState()
    val baseballDateOffset by homeViewModel.baseballDateOffset.collectAsState()

    val backendType by homeViewModel.backendType.collectAsState()
    val shouldCropLogo = remember(backendType) { backendType == "KONOMITV" }

    val tabs = remember(favoriteBaseballTeams, backendType) {
        val base = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
        if (favoriteBaseballTeams.isNotEmpty()) base + "プロ野球" else base
    }

    val safeTabIndex = ui.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    // ★ 修正: 以前は `isFullScreen(...) && !hasActivePlayer` としていたため、
    // ミニプレイヤー(PiP)表示中は設定画面などのオーバーレイが開いていても
    // isFullScreenMode が false のままになっていた。
    // その結果、設定画面が前面に出ているのに背面のホーム画面がフォーカス迷子検知を
    // 走らせ、設定画面からフォーカスを奪い返してしまい操作不能になっていた。
    // ミニプレイヤーの例外はプレイヤー起因の判定にだけ効かせる(isFullScreen側で処理)。
    val isFullScreenMode = ui.isFullScreen(
        selectedChannel, selectedProgram, epgSelectedProgram,
        isSettingsOpen, isRecordListOpen, isReserveOverlayOpen,
        hasActivePlayer = hasActivePlayer
    )

    // ★ 追加: PR #103でプレイヤー表示中もこのホーム画面自体が破棄されず常駐するようになった影響で、
    // プレイヤーを開く直前にフォーカスしていた項目の論理フォーカスが残留し、プレイヤーから戻った際の
    // 復元用requestFocus()を阻害することがある。明示的にクリアしてから要求し直す。
    val focusManager = LocalFocusManager.current
    val returnPlayerFocusRequester = remember { FocusRequester() }
    val displayFlatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    val translatedLastChannels = remember(ui.lastChannels, displayFlatChannels) {
        ui.lastChannels.map { savedChannel ->
            displayFlatChannels.find {
                it.networkId == savedChannel.networkId && it.serviceId == savedChannel.serviceId
            } ?: savedChannel
        }
    }

    LaunchedEffect(tabs.size) {
        if (ui.selectedTabIndex >= tabs.size) {
            ui.selectedTabIndex = 0
            onTabChange(0)
        }
    }

    LaunchedEffect(lastPlayerChannelId) {
        if (lastPlayerChannelId != null) {
            ui.internalLastPlayerChannelId = lastPlayerChannelId
        }
    }

    // 切替中にコンテンツをいったん全て消すと、現在のフォーカスノードも
    // 破棄されて迷子になるため、選択中のタブを常に同じフレームで描画する。
    val activeRenderIndex = safeTabIndex

    var lastHomeRefreshTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(activeRenderIndex, isPlayerActiveFullScreen) {
        val currentLabel = tabs.getOrNull(activeRenderIndex) ?: "ホーム"
        ui.isCurrentTabContentReady = false

        // プレイヤーが前面に出ている間、ホーム画面は見えていない。
        // ここで定期取得を走らせると、再生と帯域・CPU を奪い合って
        // 再生も UI 操作ももたつく原因になる。
        if (isPlayerActiveFullScreen) {
            channelViewModel.stopPolling()
            return@LaunchedEffect
        }

        if (currentLabel == "ホーム") {
            channelViewModel.startPolling()
            val now = System.currentTimeMillis()
            if (now - lastHomeRefreshTime > 300_000L) {
                homeViewModel.refreshHomeData()
                lastHomeRefreshTime = now
            }
        } else if (currentLabel == "ライブ") {
            channelViewModel.startPolling()
        } else {
            channelViewModel.stopPolling()
        }
    }

    // タブ列の右側にある設定ボタン・再生中ボタンにフォーカスがあるかどうか。
    // タブ切替時の遅延フォーカス要求がこれらのボタンからフォーカスを奪わないように使う。
    var isSettingsFocused by remember { mutableStateOf(false) }
    var isReturnPlayerFocused by remember { mutableStateOf(false) }

    // 遅延実行されるフォーカス要求から参照するため、値を確定させず毎回状態を読み直す。
    val isHeaderActionFocused = { isSettingsFocused || isReturnPlayerFocused }

    // タブの表示ツリーを再構築した直後は、切替前のフォーカスノードが破棄されて
    // フォーカスが消えることがある。新しいタブを必ず操作可能な状態に戻す。
    LaunchedEffect(activeRenderIndex) {
        delay(160)
        ui.tabFocusRequesters.getOrNull(activeRenderIndex)?.safeRequestFocusWithRetry(
            tag = "HomeTab_Rendered",
            maxRetries = 10,
            delayMillis = 50,
            // 設定ボタン等へ意図的に移動した後は、タブへ引き戻さない
            shouldContinue = { activeRenderIndex == safeTabIndex && !isHeaderActionFocused() }
        )
    }

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            delay(150)
            val currentTabName = tabs.getOrNull(safeTabIndex)
            when (currentTabName) {
                "ホーム" -> {
                    val section = homeViewModel.lastClickedSection
                    val itemId = homeViewModel.lastClickedItemId
                    if (section != null && itemId != null) {
                        ticketManager.issueForHomeRestore(section, itemId)
                    } else {
                        ticketManager.issue(HomeFocusTicket.CONTENT_TOP)
                    }
                    onAiReturnConsumed()
                }

                "プロ野球" -> {
                    ui.contentFirstItemRequesters.getOrNull(safeTabIndex)
                        ?.safeRequestFocusWithRetry("BaseballAiReturn")
                    onAiReturnConsumed()
                }

                else -> {
                    ui.tabFocusRequesters.getOrNull(safeTabIndex)
                        ?.safeRequestFocusWithRetry("FallbackAiReturn")
                    onAiReturnConsumed()
                }
            }
        }
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer && !isFullScreenMode) {
            focusManager.clearFocus(force = true)
            ui.safeHouseRequester.safeRequestFocusWithRetry("SafeHouse_Return")
            delay(150)

            val currentTabName = tabs.getOrNull(safeTabIndex)
            if (currentTabName != "ライブ" && currentTabName != "ビデオ") {
                val section = homeViewModel.lastClickedSection
                val itemId = homeViewModel.lastClickedItemId
                if (currentTabName == "ホーム" && section != null && itemId != null) {
                    ticketManager.issueForHomeRestore(section, itemId)
                } else if (currentTabName == "番組表" || currentTabName == "録画予約") {
                    if (currentTabName == "番組表") {
                        onReturnFocusConsumed()
                    }
                } else {
                    ticketManager.issue(HomeFocusTicket.TAB_BAR)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // ホームタブのデータ取得 (refreshHomeData / チャンネル一覧) は、この直上の
        // LaunchedEffect(activeRenderIndex, isPlayerActiveFullScreen) が初回コンポーズ時に
        // 必ず実行している。ここで重ねて呼ぶと、起動直後の一番重いタイミングで
        // 同じ通信・同じ EPG 集計が二重に走ることになるため呼ばない。
        if (!isReturningFromPlayer && !isFullScreenMode) {
            delay(300)
            ticketManager.issue(HomeFocusTicket.TAB_BAR)
        }
    }

    LaunchedEffect(isFullScreenMode) {
        if (!isFullScreenMode && !isReturningFromPlayer) {
            delay(300)
            val currentTabName = tabs.getOrNull(safeTabIndex)
            if (currentTabName == "録画予約") {
            } else if (currentTabName == "ホーム") {
                val section = homeViewModel.lastClickedSection
                val itemId = homeViewModel.lastClickedItemId
                if (section != null && itemId != null) {
                    ticketManager.issueForHomeRestore(section, itemId)
                } else {
                    ticketManager.issue(HomeFocusTicket.TAB_BAR)
                }
            } else if (currentTabName != "番組表") {
                ticketManager.issue(HomeFocusTicket.TAB_BAR)
            }
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            ui.handleBackNavigation(
                onTabChange = onTabChange,
                onFinalBack = onFinalBack,
                onBackTriggered = onBackTriggered,
                requestTopNavFocus = {
                    ticketManager.issue(HomeFocusTicket.TAB_BAR)
                },
                escapeToSafeHouse = {
                    scope.launch {
                        ui.safeHouseRequester.safeRequestFocusWithRetry("Back_SafeHouse")
                    }
                }
            )
        }
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        when (ticketManager.currentTicket) {
            HomeFocusTicket.TAB_BAR -> {
                delay(200)
                if (ticketManager.currentTicket != HomeFocusTicket.TAB_BAR) {
                    return@LaunchedEffect
                }
                ui.tabFocusRequesters.getOrNull(safeTabIndex)
                    ?.safeRequestFocusWithRetry(
                        "HomeTicket_TAB_BAR",
                        shouldContinue = {
                            ticketManager.currentTicket == HomeFocusTicket.TAB_BAR
                        }
                    )
                ticketManager.consume(HomeFocusTicket.TAB_BAR)

                val currentTabName = tabs.getOrNull(safeTabIndex)
                if (isReturningFromPlayer && currentTabName != "ライブ" && currentTabName != "ビデオ") {
                    onReturnFocusConsumed()
                }
            }

            HomeFocusTicket.CONTENT_TOP -> {
                delay(150)
                ui.contentFirstItemRequesters.getOrNull(safeTabIndex)
                    ?.safeRequestFocusWithRetry("HomeTicket_CONTENT_TOP")
                ticketManager.consume(HomeFocusTicket.CONTENT_TOP)
            }

            HomeFocusTicket.HOME_RESTORE -> {
                if (isReturningFromPlayer) {
                    onReturnFocusConsumed()
                }
            }

            else -> {}
        }
    }

    // タブ移動では先に表示対象を切り替える。表示ツリーの再構築前に
    // FocusRequesterへ要求すると、要求先が未接続で失敗することがあるため、
    // 再構築後にフォーカス要求をリトライする。
    var pendingTabFocusJob by remember { mutableStateOf<Job?>(null) }
    var launcherHasFocus by remember { mutableStateOf(false) }

    // 十字キーを高速に操作すると、フォーカス中のカードが破棄された拍子に
    // LazyColumn/LazyRow の focusGroup()（見た目もキー処理も持たないコンテナ）自身へ
    // フォーカスが落ちることがある。この状態では画面上にフォーカス枠が一切描画されず、
    // 十字キーも効かないため、アプリが操作不能になったように見える。
    // コンテナが持つフォーカスは hasFocus では検知できないため、
    // 「コンテンツ領域直下のフォーカス対象そのものが Active（isFocused）」を迷子のサインとして扱う。
    // タブ列や設定ボタンは正当なフォーカス先なので、判定対象はコンテンツ領域だけに限定する。
    var contentFocusStranded by remember { mutableStateOf(false) }

    // 番組表はグリッド全体が1つのフォーカス対象（Canvas描画）であり、
    // そこにフォーカスがあるのは正常な状態なので迷子判定から除外する。
    val isStrandedDetectable = tabs.getOrNull(safeTabIndex) != "番組表"

    LaunchedEffect(
        launcherHasFocus,
        contentFocusStranded,
        ui.selectedTabIndex,
        isFullScreenMode,
        isReturningFromPlayer,
        isStrandedDetectable
    ) {
        if (isFullScreenMode || isReturningFromPlayer) return@LaunchedEffect

        val isLost = { !launcherHasFocus || (contentFocusStranded && isStrandedDetectable) }
        if (!isLost()) return@LaunchedEffect

        Log.i(
            "KomorebiFocus",
            "フォーカス迷子を検知（hasFocus=$launcherHasFocus stranded=$contentFocusStranded）。復帰を試みます"
        )

        // 一度の要求で復帰できないと操作不能のまま固まるため、復帰するまで数回繰り返す。
        var attempt = 0
        while (attempt < 5 && isLost()) {
            delay(if (attempt == 0) 120 else 220)
            if (isFullScreenMode || isReturningFromPlayer || !isLost()) return@LaunchedEffect

            val tabIndex = ui.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
            // 直前までコンテンツを操作していたなら、コンテンツ先頭へ戻す方が違和感が少ない。
            val restoredToContent = if (!ui.topNavHasFocus) {
                ui.contentFirstItemRequesters.getOrNull(tabIndex)
                    ?.safeRequestFocusWithRetry(
                        tag = "HomeFocusRecovery_Content",
                        maxRetries = 4,
                        delayMillis = 60,
                        shouldContinue = { isLost() }
                    ) == true
            } else false

            if (!restoredToContent) {
                ui.tabFocusRequesters.getOrNull(tabIndex)
                    ?.safeRequestFocusWithRetry(
                        tag = "HomeFocusRecovery_Tab",
                        maxRetries = 6,
                        delayMillis = 60,
                        shouldContinue = { isLost() }
                    )
            }
            attempt++
        }
    }

    fun moveTabByDpad(direction: Key): Boolean {
        val currentIndex = ui.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        val targetIndex = when (direction) {
            Key.DirectionRight -> currentIndex + 1
            Key.DirectionLeft -> currentIndex - 1
            else -> return false
        }
        if (targetIndex !in tabs.indices) return false

        ticketManager.cancelForUserNavigation()
        pendingTabFocusJob?.cancel()
        ui.selectedTabIndex = targetIndex
        ui.onTabSelected(
            targetIndex,
            tabs,
            onTabChange,
            homeViewModel,
            channelViewModel,
            recordViewModel,
            reserveViewModel
        )

        pendingTabFocusJob = scope.launch {
            delay(80)
            ui.tabFocusRequesters[targetIndex].safeRequestFocusWithRetry(
                tag = "HomeTab_Dpad",
                maxRetries = 8,
                delayMillis = 50,
                shouldContinue = { ui.selectedTabIndex == targetIndex && !isHeaderActionFocused() }
            )
        }
        return true
    }

    // 設定ボタン(または再生中ボタン)へ移る際は、直前のタブ移動で仕込まれた
    // 遅延フォーカス要求を破棄する。残しておくと、移った直後にタブへフォーカスを
    // 引き戻してしまう。
    //
    // ★ 修正: このメソッドは「最後のタブで右キーを押した」際の着地先を決める目的で
    // 3箇所(このComposable内のonKeyEvent/onPreviewKeyEventハンドラ)から呼ばれている。
    // これらはComposeの標準的なフォーカス探索(focusProperties { right = ... })より先に
    // キーイベントを消費して自前でフォーカス遷移させているため、focusProperties側だけを
    // hasActivePlayer対応させても実際には効かなかった。
    // 再生中(PiPから復帰可能な状態)の場合は、設定ボタンではなく「再生中」ボタンへ
    // 着地させる(再生中ボタン自体のfocusProperties.rightで設定ボタンへは行ける)。
    fun focusSettingsButton(tag: String) {
        pendingTabFocusJob?.cancel()
        ticketManager.cancelForUserNavigation()
        if (hasActivePlayer) {
            returnPlayerFocusRequester.safeRequestFocus(tag)
        } else {
            ui.settingsFocusRequester.safeRequestFocus(tag)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(ui.safeHouseRequester)
                .focusable()
                // 画面切替中にフォーカスが退避ノードへ残っても、最初の十字キー操作で
                // 画面上の操作対象へ復旧させる。退避ノードには通常の隣接フォーカスが
                // 存在しないため、ここで処理しないとアプリが固まったように見える。
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    when (event.key) {
                        Key.DirectionRight -> {
                            if (ui.selectedTabIndex < tabs.lastIndex) {
                                moveTabByDpad(Key.DirectionRight)
                            } else {
                                focusSettingsButton("SafeHouse_Dpad_Settings")
                            }
                            true
                        }

                        Key.DirectionLeft -> {
                            if (ui.selectedTabIndex > 0) moveTabByDpad(Key.DirectionLeft)
                            else ui.tabFocusRequesters[ui.selectedTabIndex].safeRequestFocus("SafeHouse_Dpad_Tab")
                            true
                        }

                        Key.DirectionUp -> {
                            scope.launch {
                                ui.tabFocusRequesters[ui.selectedTabIndex].safeRequestFocusWithRetry(
                                    tag = "SafeHouse_Dpad_Tab",
                                    maxRetries = 8,
                                    delayMillis = 50
                                )
                            }
                            true
                        }

                        Key.DirectionDown -> {
                            scope.launch {
                                if (ui.isCurrentTabContentReady) {
                                    ui.contentFirstItemRequesters[ui.selectedTabIndex]
                                        .safeRequestFocusWithRetry(
                                            tag = "SafeHouse_Dpad_Content",
                                            maxRetries = 8,
                                            delayMillis = 50
                                        )
                                } else {
                                    ui.tabFocusRequesters[ui.selectedTabIndex]
                                        .safeRequestFocusWithRetry(
                                            tag = "SafeHouse_Dpad_Tab",
                                            maxRetries = 8,
                                            delayMillis = 50
                                        )
                                }
                            }
                            true
                        }

                        else -> false
                    }
                }
        )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { launcherHasFocus = it.hasFocus }
            ) {
            if (!isFullScreenMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(top = 8.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { ui.topNavHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            // 左右キーによるタブ切替は、タブ列にフォーカスがあるときだけ行う。
                            // 設定ボタン・再生中ボタンにフォーカスがある状態で拾ってしまうと、
                            // ボタンから離れる操作をしていないのにタブだけが切り替わってしまう。
                            if (event.type == KeyEventType.KeyDown && !isHeaderActionFocused()) {
                                if ((event.key == Key.DirectionRight || event.key == Key.DirectionLeft) &&
                                    moveTabByDpad(event.key)
                                ) {
                                    return@onPreviewKeyEvent true
                                }
                                if (event.key == Key.DirectionRight && ui.selectedTabIndex == tabs.lastIndex) {
                                    focusSettingsButton("HomeTab_Dpad_Settings")
                                    return@onPreviewKeyEvent true
                                }
                            }
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                ui.handleBackNavigation(
                                    onTabChange = onTabChange,
                                    onFinalBack = onFinalBack,
                                    onBackTriggered = onBackTriggered,
                                    requestTopNavFocus = { ticketManager.issue(HomeFocusTicket.TAB_BAR) },
                                    escapeToSafeHouse = {
                                        scope.launch {
                                            ui.safeHouseRequester.safeRequestFocusWithRetry(
                                                "Back_SafeHouse"
                                            )
                                        }
                                    }
                                )
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DigitalClock(timeFormat = timeFormat)
                    Spacer(modifier = Modifier.width(32.dp))
                    TabRow(
                        selectedTabIndex = safeTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties {
                                canFocus = !isReturningFromPlayer
                            },
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            if (safeTabIndex < tabPositions.size) {
                                TabRowDefaults.UnderlinedIndicator(
                                    currentTabPosition = tabPositions[safeTabIndex],
                                    doesTabRowHaveFocus = doesTabRowHaveFocus,
                                    activeColor = colors.accent
                                )
                            }
                        }) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = safeTabIndex == index,
                                onFocus = {
                                    ticketManager.cancelForUserNavigation()
                                    if (!isReturningFromPlayer) {
                                        ui.selectedTabIndex = index
                                        ui.onTabSelected(
                                            index,
                                            tabs,
                                            onTabChange,
                                            homeViewModel,
                                            channelViewModel,
                                            recordViewModel,
                                            reserveViewModel
                                        )
                                    }
                                    ui.topNavHasFocus = true
                                },
                                modifier = Modifier
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            return@onKeyEvent false
                                        }
                                        if (event.key == Key.DirectionRight || event.key == Key.DirectionLeft) {
                                            if (moveTabByDpad(event.key)) {
                                                true
                                            } else if (event.key == Key.DirectionRight && index == tabs.lastIndex) {
                                                focusSettingsButton("HomeTab_Dpad_Settings")
                                                true
                                            } else {
                                                false
                                            }
                                        } else if (event.key == Key.DirectionDown && index == ui.selectedTabIndex) {
                                            // 表示準備フラグの更新を待たず、実体化した先頭アイテムへ
                                            // リトライ付きで遷移する。高速切替直後でもDefault探索に
                                            // フォーカスを逃がさない。
                                            scope.launch {
                                                ui.contentFirstItemRequesters.getOrNull(index)
                                                    ?.safeRequestFocusWithRetry(
                                                        tag = "HomeTab_Dpad_Content",
                                                        maxRetries = 12,
                                                        delayMillis = 40,
                                                        shouldContinue = {
                                                            ui.selectedTabIndex == index &&
                                                                activeRenderIndex == index
                                                        }
                                                    )
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .focusRequester(
                                        ui.tabFocusRequesters.getOrNull(index)
                                            ?: FocusRequester.Default
                                    )
                                    .focusProperties {
                                        down = if (safeTabIndex == index) {
                                            ui.contentFirstItemRequesters.getOrNull(index)
                                                ?: FocusRequester.Default
                                        } else FocusRequester.Default

                                        canFocus = !(title == "番組表" && ui.isEpgJumping)

                                        up = FocusRequester.Cancel
                                        if (index == 0) {
                                            left = FocusRequester.Cancel
                                        }
                                        right =
                                            if (index < tabs.lastIndex) {
                                                ui.tabFocusRequesters.getOrNull(index + 1)
                                                    ?: FocusRequester.Default
                                            } else if (hasActivePlayer) {
                                                // ★ 修正: 再生中(PiPから復帰可能な状態)は、最後のタブから
                                                // 右へ進むと「再生中」ボタンに止まるべきだが、常に設定ボタンを
                                                // 指していたため、設定ボタンを一度通過してから左キーで
                                                // 戻らないと「再生中」ボタンを選択できない不具合があった。
                                                returnPlayerFocusRequester
                                            } else {
                                                ui.settingsFocusRequester
                                            }
                                    }) {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 8.dp
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (safeTabIndex == index) colors.textPrimary else colors.textSecondary
                                )
                            }
                        }
                    }

                    if (hasActivePlayer) {
                        val isEpgJumping =
                            tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                        Button(
                            onClick = onReturnToPlayerClick,
                            modifier = Modifier
                                .focusRequester(returnPlayerFocusRequester)
                                .onFocusChanged { isReturnPlayerFocused = it.isFocused }
                                .focusProperties {
                                    left = ui.tabFocusRequesters.getOrNull(tabs.lastIndex)
                                        ?: FocusRequester.Default
                                    right = ui.settingsFocusRequester
                                    canFocus = !isEpgJumping
                                    up = FocusRequester.Cancel
                                },
                            colors = ButtonDefaults.colors(
                                containerColor = colors.accent.copy(alpha = 0.2f),
                                focusedContainerColor = colors.accent,
                                contentColor = colors.accent,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            ),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(20.dp)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "再生中",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "再生中",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    IconButton(
                        onClick = { onSettingsToggle(true) },
                        modifier = Modifier
                            .focusRequester(ui.settingsFocusRequester)
                            .onFocusChanged { isSettingsFocused = it.isFocused }
                            .focusProperties {
                                val isEpgJumping =
                                    tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                                left =
                                    if (hasActivePlayer) returnPlayerFocusRequester else (ui.tabFocusRequesters.getOrNull(
                                        tabs.lastIndex
                                    ) ?: FocusRequester.Default)
                                canFocus = !isEpgJumping
                                up = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            },
                        colors = IconButtonDefaults.colors(
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White,
                            contentColor = colors.textSecondary
                        )
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    // isFocused=true は「コンテンツ領域直下のコンテナ自身がフォーカスを持った」状態。
                    // 実際に操作できるカードは必ずこれより深い階層にあるため、迷子とみなす。
                    .onFocusChanged { contentFocusStranded = it.isFocused }
            ) {
                val currentTabLabel = tabs.getOrNull(activeRenderIndex) ?: "ホーム"
                    val handleUiReady = {
                        // ★ 追加(診断用): コールドブートで最初のタブの中身が実際に描画され、
                        // 操作可能になった瞬間を計測する(以降のタブ切替では再発火させない)。
                        if (!hasLoggedFirstUiReady) {
                            hasLoggedFirstUiReady = true
                            ColdStartDiag.mark("HomeLauncherScreen first tab content ready")
                        }
                        onUiReady()
                        ui.isCurrentTabContentReady = true
                    }

                    when (currentTabLabel) {
                        "ホーム" -> HomeContents(
                            lastWatchedChannels = translatedLastChannels,
                            watchHistory = ui.watchHistory,
                            hotChannels = ui.hotChannels,
                            upcomingReserves = ui.upcomingReserves,
                            genrePickup = ui.genrePickup,
                            pickupGenreName = ui.pickupGenreLabel,
                            pickupTimeSlot = ui.genrePickupTimeSlot,
                            groupedChannels = groupedChannels,
                            getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                            shouldCropLogo = shouldCropLogo,
                            onChannelClick = {
                                if (it != null) onChannelClick(it, false)
                            },
                            onHistoryClick = { historyItem ->
                                val programId = historyItem.program.id.toIntOrNull()
                                // クリック時にのみ必要な値なので、composition では購読せず
                                // ここで ViewModel の最新値を直接読み出す。
                                val betterProgram =
                                    recordViewModel.recentRecordings.value.find { it.id == programId }
                                onProgramSelected(
                                    betterProgram?.copy(playbackPosition = historyItem.playback_position)
                                        ?: KonomiDataMapper.toDomainModel(historyItem)
                                )
                            },
                            onReserveClick = onReserveSelected,
                            onProgramClick = { onEpgProgramSelected(it) },
                            onNavigateToTab = { targetIndex ->
                                ui.tabFocusRequesters.getOrNull(targetIndex)
                                    ?.safeRequestFocus(TAG); ui.onTabSelected(
                                targetIndex,
                                tabs,
                                onTabChange,
                                homeViewModel,
                                channelViewModel,
                                recordViewModel,
                                reserveViewModel
                            )
                            },
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                            externalFocusRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                            lastFocusedChannelId = ui.internalLastPlayerChannelId
                                ?: lastPlayerChannelId,
                            lastFocusedProgramId = lastPlayerProgramId,
                            isTopNavFocused = ui.topNavHasFocus,
                            onUiReady = handleUiReady,
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            timeFormat = timeFormat
                        )

                        "ライブ" -> {
                            LiveContent(
                                channelViewModel = channelViewModel,
                                epgViewModel = epgViewModel,
                                groupedChannels = groupedChannels,
                                selectedChannel = selectedChannel,
                                onChannelClick = { onChannelClick(it, false) },
                                onFocusChannelChange = { ui.internalLastPlayerChannelId = it },
                                mirakurunIp = mirakurunIp,
                                mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                onPlayerStateChanged = { },
                                lastFocusedChannelId = ui.internalLastPlayerChannelId
                                    ?: lastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "ライブ",
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                reserveViewModel = reserveViewModel,
                                timeFormat = timeFormat,
                                isPiPMode = hasActivePlayer,
                                aiFocusReturnTick = if (currentTabLabel == "ライブ") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed
                            )
                            // ライブタブが表示に必要とするチャンネル一覧・グループ化データは
                            // 呼び出し元で取得済みのものが groupedChannels として渡ってくる。
                            // そのため取得完了を待つ必要はなく、揃っていれば次フレームで準備完了。
                            TabReadyNotifier(
                                isDataAvailable = groupedChannels.isNotEmpty(),
                                onReady = handleUiReady
                            )
                        }

                        "ビデオ" -> {
                            VideoTabContent(
                                recordViewModel = recordViewModel,
                                onProgramClick = { onProgramSelected(it) },
                                onShowAllRecordings = onShowAllRecordings,
                                onShowSeriesList = onShowSeriesList,
                                onShowSmbLibrary = onShowSmbLibrary,
                                openedSeriesTitle = ui.openedSeriesTitle,
                                onOpenedSeriesTitleChange = { ui.openedSeriesTitle = it },
                                tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                isTopNavFocused = ui.topNavHasFocus,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "ビデオ",
                                lastPlayedProgramId = lastPlayerProgramId,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                timeFormat = timeFormat,
                                watchHistory = ui.watchHistory,
                                aiFocusReturnTick = if (currentTabLabel == "ビデオ") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed
                            )
                            // 録画一覧は Room の Flow から供給され、ローカル DB からの
                            // 読み出しなので待ち合わせが必要なほど遅くはない。
                            // ここで recentRecordings を購読すると
                            // 初回同期中の大量の Flow 発火で HomeLauncherScreen 全体が
                            // 作り直されてしまう（HomeLauncherState のコメント参照）ため、
                            // 購読はせず次フレームで準備完了とする。
                            TabReadyNotifier(
                                isDataAvailable = true,
                                onReady = handleUiReady
                            )
                        }

                        "番組表" -> {
                            EpgNavigationContainer(
                                uiState = ui.epgUiState,
                                logoUrls = ui.logoUrls,
                                mainTabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                selectedProgram = epgSelectedProgram,
                                onProgramSelected = onEpgProgramSelected,
                                isJumpMenuOpen = isEpgJumpMenuOpen,
                                onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                onNavigateToPlayer = onNavigateToPlayer,
                                currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                                onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                                restoreChannelId = if (isReturningFromPlayer && currentTabLabel == "番組表") lastPlayerChannelId else null,
                                availableTypes = groupedChannels.keys.toList(),
                                onJumpStateChanged = { ui.isEpgJumping = it },
                                reserves = ui.reserves,
                                onUpdateTargetTime = { epgViewModel.updateTargetTime(it) },
                                searchQuery = epgViewModel.searchQuery.collectAsState().value,
                                searchHistory = epgViewModel.searchHistory.collectAsState().value,
                                onSearchQueryChange = { epgViewModel.updateSearchQuery(it) },
                                onExecuteSearch = { epgViewModel.executeSearch(it) },
                                activeSearchQuery = epgViewModel.activeSearchQuery.collectAsState().value,
                                searchResults = epgViewModel.searchResults.collectAsState().value,
                                isSearching = epgViewModel.isSearching.collectAsState().value,
                                onClearSearch = { epgViewModel.clearSearch() },
                                timeFormat = timeFormat
                            )
                            // 番組表は Canvas でグリッド全体を描画するため他タブより重い。
                            // 他タブが 500ms なのに対しここだけ 800ms だったのはその描画コストを
                            // 固定時間で吸収しようとしたためだが、EPG の読み込み完了状態
                            // (EpgUiState) を直接見れば実態に即した待ち合わせになる。
                            // グリッド描画が重い分、保険のタイムアウトだけ長めに取る。
                            TabReadyNotifier(
                                isDataAvailable = ui.epgUiState is EpgUiState.Success,
                                fallbackTimeoutMs = 1500L,
                                onReady = handleUiReady
                            )
                        }

                        "録画予約" -> {
                            ReserveListScreen(
                                onBack = {
                                    ui.tabFocusRequesters[activeRenderIndex].safeRequestFocus(
                                        TAG
                                    )
                                },
                                onProgramClick = onReserveSelected,
                                onConditionClick = onConditionClick,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                groupedChannels = groupedChannels,
                                isReserveOverlayOpen = isReserveOverlayOpen,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "録画予約",
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (currentTabLabel == "録画予約") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed
                            )
                            // 予約一覧の読み込み状態に連動させる。
                            ReserveTabReadyNotifier(
                                reserveViewModel = reserveViewModel,
                                onReady = handleUiReady
                            )
                        }

                        "プロ野球" -> {
                            BaseballDashboardScreen(
                                groupedGames = favoriteBaseballGames,
                                groupedChannels = groupedChannels,
                                dateOffset = baseballDateOffset,
                                onDateOffsetChange = { homeViewModel.updateBaseballDateOffset(it) },
                                onChannelClick = { channel ->
                                    val matchedChannel = displayFlatChannels.find {
                                        it.networkId == channel.networkId && it.serviceId == channel.serviceId
                                    } ?: channel
                                    onChannelClick(matchedChannel, true)
                                },
                                onProgramClick = { onEpgProgramSelected(it) },
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                // 野球データ(favoriteBaseballGames)は呼び出し元で
                                // 取得済みのものを渡しているため待ち合わせは不要。
                                // 1フレーム描画されてから準備完了を通知する。
                                onUiReady = { withFrameNanos { }; handleUiReady() },
                                timeFormat = timeFormat
                            )
                        }
                }
            }
        }
    }
}
