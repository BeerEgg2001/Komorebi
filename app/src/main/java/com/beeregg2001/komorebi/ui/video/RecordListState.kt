package com.beeregg2001.komorebi.ui.video

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.ui.video.components.RecordCategory

enum class FocusTicket { NONE, LIST_TOP, NAV_PANE, PANE, TARGET_ID }

@Stable
class FocusTicketManager {
    var currentTicket by mutableStateOf(FocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set
    var targetProgramId by mutableStateOf<Int?>(null)
        private set

    var targetPath by mutableStateOf<String?>(null)
        private set
    var forceResetTick by mutableIntStateOf(0)
        private set

    fun issue(ticket: FocusTicket, programId: Int? = null, path: String? = null) {
        targetProgramId = programId
        targetPath = path
        currentTicket = ticket
        issueTime = System.currentTimeMillis()
        Log.i(
            "KomorebiFocus",
            "🎟️ Ticket ISSUED: $ticket (TargetID: $programId, TargetPath: $path)"
        )
    }

    fun consume(ticket: FocusTicket) {
        if (currentTicket == ticket) {
            Log.i("KomorebiFocus", "🗑️ Ticket CONSUMED: $currentTicket")
            currentTicket = FocusTicket.NONE
            targetProgramId = null
            targetPath = null
        }
    }

    fun triggerHardReset() {
        forceResetTick++
        Log.i("KomorebiFocus", "♻️ HARD RESET Triggered: tick=$forceResetTick")
    }
}

@Composable
fun rememberFocusTicketManager() = remember { FocusTicketManager() }

val RecordCategory.isPaneCategory: Boolean
    get() = this == RecordCategory.GENRE ||
            this == RecordCategory.CHANNEL ||
            this == RecordCategory.SERIES ||
            this == RecordCategory.TIME

@Stable
class RecordListFocusRequesters {
    val searchCloseButton = FocusRequester()
    val searchInput = FocusRequester()
    val innerTextField = FocusRequester()
    val historyList = FocusRequester()
    val backButton = FocusRequester()
    val searchOpenButton = FocusRequester()
    val viewToggleButton = FocusRequester()

    val sortButton = FocusRequester() // ★ 追加: ソートボタン用のフォーカス

    val navPane = FocusRequester()
    val genrePane = FocusRequester()
    val channelPane = FocusRequester()
    val dayPane = FocusRequester()
    val seriesGenrePane = FocusRequester()

    val firstItem = FocusRequester()
    val paneFirstItem = FocusRequester()

    val contentContainer = FocusRequester()
    val loadingSafeHouse = FocusRequester()
}

@Composable
fun rememberRecordListFocusRequesters() = remember { RecordListFocusRequesters() }

@Stable
class RecordListMenuState {
    var isNavPaneOpen by mutableStateOf(false)
    var isGenrePaneOpen by mutableStateOf(false)
    var isSeriesGenrePaneOpen by mutableStateOf(false)
    var isChannelPaneOpen by mutableStateOf(false)
    var isDayPaneOpen by mutableStateOf(false)
    var isDetailActive by mutableStateOf(false)
    var isSearchBarVisible by mutableStateOf(false)
    var isSortMenuOpen by mutableStateOf(false) // ★ 追加: ソートメニューの開閉状態
    var isSelectionMade by mutableStateOf(false)
    var isBackButtonFocused by mutableStateOf(false)
    var isInitialFocusRequested by mutableStateOf(true)
    var isNavFocused by mutableStateOf(false)
    var isPaneListReady by mutableStateOf(false)

    // ★ 追加: リスト項目の右側に出るサブメニュー（再生する／最初から再生 等）の開閉状態。
    // 以前は RecordListContent 内の remember で保持していたが、プレイヤー表示中も
    // 録画一覧が Compose ツリーに残るようになったため、画面側から明示的に
    // 閉じられるようここへホイストしている。
    var isSideMenuOpen by mutableStateOf(false)

    val isPaneOpen: Boolean
        get() = isGenrePaneOpen || isSeriesGenrePaneOpen || isChannelPaneOpen || isDayPaneOpen

    /**
     * 開いているメニュー・ペイン類をすべて閉じる。
     *
     * プレイヤーへ遷移する直前と、プレイヤーから戻った直後に呼び出す。
     * 録画一覧はプレイヤー表示中も破棄されずコンポーズされ続けるため、
     * これを行わないとサブメニューが開いたまま復帰し、
     * リスト側のフォーカス（LazyColumn の canFocus）がブロックされたままになる。
     *
     * 検索バーの表示状態（isSearchBarVisible）は検索結果の文脈を保つため意図的に触らない。
     */
    fun closeAllMenus() {
        isNavPaneOpen = false
        isGenrePaneOpen = false
        isSeriesGenrePaneOpen = false
        isChannelPaneOpen = false
        isDayPaneOpen = false
        isDetailActive = false
        isSortMenuOpen = false
        isSideMenuOpen = false
    }
}

@Composable
fun rememberRecordListMenuState() = remember { RecordListMenuState() }
