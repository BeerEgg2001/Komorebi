package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.*
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.video.smb.SmbItem

enum class AiFocusTicket { NONE, PANEL_DEFAULT }

@Stable
class AiFocusTicketManager {
    var currentTicket by mutableStateOf(AiFocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set

    fun issue(ticket: AiFocusTicket) {
        currentTicket = ticket
        issueTime = System.currentTimeMillis()
    }

    fun consume(ticket: AiFocusTicket) {
        if (currentTicket == ticket) {
            currentTicket = AiFocusTicket.NONE
        }
    }
}

/**
 * MainRootScreenのすべてのUI状態(変数)を管理するState Holderクラス
 */
@Stable
class MainRootState {
    // タブ・選択状態
    var currentTabIndex by mutableIntStateOf(0)
    var selectedChannel by mutableStateOf<Channel?>(null)
    var selectedProgram by mutableStateOf<RecordedProgram?>(null)

    // SMB動画を保持
    var selectedSmbItem by mutableStateOf<SmbItem?>(null)
    var initialPlaybackPositionMs by mutableLongStateOf(0L)
    var epgSelectedProgram by mutableStateOf<EpgProgram?>(null)

    var backendType by mutableStateOf("KONOMITV")

    // 予約・リスト状態
    var selectedReserve by mutableStateOf<ReserveItem?>(null)
    var editingReserveItem by mutableStateOf<ReserveItem?>(null)
    var editingNewProgram by mutableStateOf<EpgProgram?>(null)
    var reserveToDelete by mutableStateOf<ReserveItem?>(null)
    var openedSeriesTitle by mutableStateOf<String?>(null)

    // 録画リストから自動予約へ進む際のターゲット番組
    var selectedProgramForAutoReserve by mutableStateOf<RecordedProgram?>(null)

    // AIコンシェルジュ
    var isAiConciergeOpen by mutableStateOf(false)
    var showAiKeyboardInput by mutableStateOf(false)
    var toastMessage by mutableStateOf<String?>(null)

    val aiTicketManager = AiFocusTicketManager()

    var aiFocusReturnTick by mutableIntStateOf(0)

    // 各種オーバーレイの開閉状態
    var isEpgJumpMenuOpen by mutableStateOf(false)
    var isSettingsOpen by mutableStateOf(false)

    // ★ 追加: 設定画面を開く際のターゲット指定（ディープリンク用）
    var settingsInitialCategoryIndex by mutableIntStateOf(0)
    var settingsInitialFocusItemIndex by mutableStateOf<Int?>(null)

    var isRecordListOpen by mutableStateOf(false)
    var isSeriesListOpen by mutableStateOf(false)
    var isSmbLibraryOpen by mutableStateOf(false)

    var showDeleteConfirmDialog by mutableStateOf(false)

    var triggerHomeBack by mutableStateOf(false)

    // プレイヤー固有の状態
    var isPlayerMiniListOpen by mutableStateOf(false)
    var playerShowOverlay by mutableStateOf(true)
    var playerIsManualOverlay by mutableStateOf(false)
    var playerIsPinnedOverlay by mutableStateOf(false)
    var playerIsSubMenuOpen by mutableStateOf(false)
    var showPlayerControls by mutableStateOf(true)
    var isPlayerSubMenuOpen by mutableStateOf(false)
    var isPlayerSceneSearchOpen by mutableStateOf(false)

    // アプリ内ミニプレイヤー（PiP）のフラグ
    var isMiniPlayerMode by mutableStateOf(false)

    // 履歴・復帰状態
    var lastSelectedChannelId by mutableStateOf<String?>(null)
    var lastSelectedProgramId by mutableStateOf<String?>(null)
    var isReturningFromPlayer by mutableStateOf(false)

    // プロ野球特化モードのフラグ
    var isBaseballMode by mutableStateOf(false)

    // 再生から戻った際にフォーカスすべき録画番組のID
    var lastPlayedRecordingId by mutableStateOf<Int?>(null)

    // 再生から戻った際にフォーカスすべきSMBファイルのパス
    var lastPlayedSmbPath by mutableStateOf<String?>(null)

    // システム状態
    var isDataReady by mutableStateOf(false)
    var isUiReady by mutableStateOf(false)
    var isSplashFinished by mutableStateOf(false)
    var showConnectionErrorDialog by mutableStateOf(false)
    var hasAppliedStartupTab by mutableStateOf(false)

    var hasAppliedStartupChannel by mutableStateOf(false)

    var editingCondition by mutableStateOf<ReservationCondition?>(null)
    var selectedConditionReserveItem by mutableStateOf<ReserveItem?>(null)

    // 戻るボタンの連打ガード
    private var lastBackPressTime by mutableLongStateOf(0L)
    fun canProcessBackPress(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 500) return false
        lastBackPressTime = currentTime
        return true
    }

    fun isFullScreen(
        channel: Channel?,
        program: RecordedProgram?,
        epgProgram: EpgProgram?,
        settingsOpen: Boolean,
        recordListOpen: Boolean,
        reserveOverlayOpen: Boolean
    ): Boolean {
        if (isMiniPlayerMode) return false

        return channel != null || program != null || selectedSmbItem != null || epgProgram != null ||
                settingsOpen || recordListOpen || reserveOverlayOpen ||
                isSeriesListOpen || isAiConciergeOpen || isSmbLibraryOpen ||
                editingCondition != null || selectedConditionReserveItem != null ||
                selectedReserve != null || editingReserveItem != null ||
                editingNewProgram != null || reserveToDelete != null ||
                selectedProgramForAutoReserve != null
    }

    fun getVisibleTabs(): List<String> {
        return listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
    }
}

@Composable
fun rememberMainRootState(): MainRootState {
    return remember { MainRootState() }
}