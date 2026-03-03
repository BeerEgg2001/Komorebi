package com.beeregg2001.komorebi.ui.video

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.ui.video.components.RecordCategory

// 🎫 チケット種類の拡張
enum class FocusTicket { NONE, LIST_TOP, NAV_PANE, PANE, TARGET_ID }

@Stable
class FocusTicketManager {
    var currentTicket by mutableStateOf(FocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set
    var targetProgramId by mutableStateOf<Int?>(null) // ★特定の番組を狙い撃ちするためのID
        private set
    var forceResetTick by mutableIntStateOf(0) // ★強制リセット用のカウンター
        private set

    fun issue(ticket: FocusTicket, programId: Int? = null) {
        targetProgramId = programId
        currentTicket = ticket
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ Ticket ISSUED: $ticket (TargetID: $programId)")
    }

    fun consume(ticket: FocusTicket) {
        if (currentTicket == ticket) {
            Log.i("KomorebiFocus", "🗑️ Ticket CONSUMED: $currentTicket")
            currentTicket = FocusTicket.NONE
            targetProgramId = null
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
    var isSelectionMade by mutableStateOf(false)
    var isBackButtonFocused by mutableStateOf(false)
    var isInitialFocusRequested by mutableStateOf(true)
    var isNavFocused by mutableStateOf(false)
    var isPaneListReady by mutableStateOf(false)

    val isPaneOpen: Boolean
        get() = isGenrePaneOpen || isSeriesGenrePaneOpen || isChannelPaneOpen || isDayPaneOpen
}

@Composable
fun rememberRecordListMenuState() = remember { RecordListMenuState() }