package com.beeregg2001.komorebi.ui.video

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.ui.video.components.RecordCategory

enum class ListFocusTarget { NONE, LIST_TOP, NAV_PANE }

// ★修正: 不足していた拡張プロパティを定義
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

@Stable
class ListResetState {
    var trigger by mutableLongStateOf(0L)
    var key by mutableIntStateOf(0)
    var autoFocusTarget by mutableStateOf(ListFocusTarget.NONE)

    fun reset(target: ListFocusTarget = ListFocusTarget.LIST_TOP) {
        key++
        trigger = System.currentTimeMillis()
        autoFocusTarget = target
    }
}

@Composable
fun rememberListResetState() = remember { ListResetState() }