package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.*
import com.beeregg2001.komorebi.data.model.*

/**
 * MainRootScreenのすべてのUI状態（変数）を管理するState Holderクラス
 */
@Stable
class MainRootState {
    // タブ・選択状態
    var currentTabIndex by mutableIntStateOf(0)
    var selectedChannel by mutableStateOf<Channel?>(null)
    var selectedProgram by mutableStateOf<RecordedProgram?>(null)
    var initialPlaybackPositionMs by mutableLongStateOf(0L)
    var epgSelectedProgram by mutableStateOf<EpgProgram?>(null)

    // 予約・リスト状態
    var selectedReserve by mutableStateOf<ReserveItem?>(null)
    var editingReserveItem by mutableStateOf<ReserveItem?>(null)
    var editingNewProgram by mutableStateOf<EpgProgram?>(null)
    var reserveToDelete by mutableStateOf<ReserveItem?>(null)
    var openedSeriesTitle by mutableStateOf<String?>(null)

    // UI開閉フラグ
    var showDeleteConfirmDialog by mutableStateOf(false)
    var isEpgJumpMenuOpen by mutableStateOf(false)
    var triggerHomeBack by mutableStateOf(false)
    var isSettingsOpen by mutableStateOf(false)
    var isRecordListOpen by mutableStateOf(false)
    var isSeriesListOpen by mutableStateOf(false)
    var toastMessage by mutableStateOf<String?>(null)

    // プレイヤー状態
    var isPlayerMiniListOpen by mutableStateOf(false)
    var playerShowOverlay by mutableStateOf(true)
    var playerIsManualOverlay by mutableStateOf(false)
    var playerIsPinnedOverlay by mutableStateOf(false)
    var playerIsSubMenuOpen by mutableStateOf(false)
    var showPlayerControls by mutableStateOf(true)
    var isPlayerSubMenuOpen by mutableStateOf(false)
    var isPlayerSceneSearchOpen by mutableStateOf(false)

    // 履歴・復帰状態
    var lastSelectedChannelId by mutableStateOf<String?>(null)
    var lastSelectedProgramId by mutableStateOf<String?>(null)
    var isReturningFromPlayer by mutableStateOf(false)

    // システム状態
    var isDataReady by mutableStateOf(false)
    var isSplashFinished by mutableStateOf(false)
    var showConnectionErrorDialog by mutableStateOf(false)
    var hasAppliedStartupTab by mutableStateOf(false)
}

@Composable
fun rememberMainRootState(): MainRootState {
    return remember { MainRootState() }
}