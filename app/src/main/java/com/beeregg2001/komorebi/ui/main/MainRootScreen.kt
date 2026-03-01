@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.mapper.ReserveMapper
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.epg.ProgramDetailMode
import com.beeregg2001.komorebi.ui.epg.ProgramDetailScreen
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.ui.video.player.VideoPlayerScreen
import com.beeregg2001.komorebi.ui.reserve.ReserveSettingsDialog
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.OffsetDateTime

private const val TAG = "MainRootScreen"

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootScreen(
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel = hiltViewModel(),
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMainRootState()

    val themeName by settingsViewModel.appTheme.collectAsState(initial = "MONOTONE")
    val currentTheme = remember(themeName) {
        runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.MONOTONE)
    }

    val themeSeason = remember(themeName) {
        when (themeName) {
            "SPRING", "SPRING_LIGHT" -> "SPRING"
            "SUMMER", "SUMMER_LIGHT" -> "SUMMER"
            "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"
            "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"
            else -> "DEFAULT"
        }
    }

    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val detailFocusRequester = remember { FocusRequester() }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()
    val syncProgress by recordViewModel.syncProgress.collectAsState()

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    LaunchedEffect(Unit) {
        if (!state.hasAppliedStartupTab) {
            val tab = settingsViewModel.getStartupTabOnce()
            state.currentTabIndex = when (tab) {
                "ホーム" -> 0; "ライブ" -> 1; "ビデオ" -> 2; "番組表" -> 3; "録画予約" -> 4; else -> 0
            }
            state.hasAppliedStartupTab = true
        }
    }

    LaunchedEffect(state.isRecordListOpen) {
        if (state.isRecordListOpen) {
            recordViewModel.triggerSmartSync()
        }
    }

    // 設定画面から戻る時のリフレッシュ処理
    val closeSettingsAndRefresh = {
        state.isSettingsOpen = false
        state.isDataReady = false
        state.isUiReady = false // UI準備フラグもリセット
        state.showConnectionErrorDialog = false
        state.currentTabIndex = 0
        channelViewModel.fetchChannels()
        epgViewModel.preloadAllEpgData()
        homeViewModel.refreshHomeData()
        recordViewModel.fetchRecentRecordings(forceRefresh = true)
        reserveViewModel.fetchReserves()
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3000); state.toastMessage = null
        }
    }

    // 戻るボタンの処理（連打ガード付き）
    BackHandler(enabled = true) {
        if (!state.canProcessBackPress()) return@BackHandler

        when {
            state.editingNewProgram != null -> state.editingNewProgram = null
            state.editingReserveItem != null -> state.editingReserveItem = null
            state.reserveToDelete != null -> state.reserveToDelete = null
            state.showDeleteConfirmDialog -> state.showDeleteConfirmDialog = false
            state.isPlayerMiniListOpen -> state.isPlayerMiniListOpen = false
            state.playerIsSubMenuOpen -> state.playerIsSubMenuOpen = false
            state.isPlayerSubMenuOpen -> state.isPlayerSubMenuOpen = false
            state.isPlayerSceneSearchOpen -> {
                state.isPlayerSceneSearchOpen = false; state.showPlayerControls = false
            }

            state.selectedChannel != null -> {
                state.selectedChannel = null; state.isReturningFromPlayer = true
            }

            state.selectedProgram != null -> {
                state.selectedProgram = null; state.showPlayerControls =
                    true; state.isReturningFromPlayer = true
            }

            state.isSettingsOpen -> closeSettingsAndRefresh()
            state.epgSelectedProgram != null -> state.epgSelectedProgram = null
            state.selectedReserve != null -> state.selectedReserve = null
            state.isEpgJumpMenuOpen -> state.isEpgJumpMenuOpen = false
            state.isRecordListOpen -> {
                state.isRecordListOpen = false
                if (state.openedSeriesTitle != null) {
                    state.isSeriesListOpen = true; state.openedSeriesTitle = null
                }
                recordViewModel.searchRecordings("")
            }

            state.isSeriesListOpen -> {
                state.isSeriesListOpen = false; recordViewModel.searchRecordings("")
            }

            state.showConnectionErrorDialog -> onExitApp()
            // Loading中（準備中）はホームへ戻る挙動を抑制
            !(state.isDataReady && state.isUiReady) -> {}
            else -> state.triggerHomeBack = true
        }
    }

    LaunchedEffect(isChannelLoading, isHomeLoading) {
        if (!isChannelLoading && !isHomeLoading) {
            delay(300)
            if (isChannelError) {
                state.showConnectionErrorDialog = true; state.isDataReady = false
            } else {
                state.showConnectionErrorDialog = false; state.isDataReady = true
            }
        }
    }

    LaunchedEffect(Unit) { delay(500); state.isSplashFinished = true }

    // データおよびOS設定が読み込めているか（UI準備はここには含めないことでデッドロック回避）
    val isSystemReady =
        ((state.isDataReady && state.isSplashFinished) || (!isSettingsInitialized && state.isSplashFinished)) && state.hasAppliedStartupTab

    KomorebiTheme(theme = currentTheme) {
        val colors = KomorebiTheme.colors
        val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .background(backgroundBrush)
        ) {
            if (state.selectedChannel == null && state.selectedProgram == null) {
                SeasonalDecor(
                    season = themeSeason,
                    isDark = colors.isDark,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            // メインコンテンツを表示する（DB同期中はブロック）
            val showMainContent =
                isSystemReady && isSettingsInitialized && !state.showConnectionErrorDialog && !(syncProgress.isSyncing && syncProgress.isInitialBuild)

            if (showMainContent) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.selectedChannel != null -> {
                            LivePlayerScreen(
                                channel = state.selectedChannel!!,
                                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                initialQuality = defaultLiveQuality,
                                isMiniListOpen = state.isPlayerMiniListOpen,
                                onMiniListToggle = { state.isPlayerMiniListOpen = it },
                                showOverlay = state.playerShowOverlay,
                                onShowOverlayChange = { state.playerShowOverlay = it },
                                isManualOverlay = state.playerIsManualOverlay,
                                onManualOverlayChange = { state.playerIsManualOverlay = it },
                                isPinnedOverlay = state.playerIsPinnedOverlay,
                                onPinnedOverlayChange = { state.playerIsPinnedOverlay = it },
                                isSubMenuOpen = state.playerIsSubMenuOpen,
                                onSubMenuToggle = { state.playerIsSubMenuOpen = it },
                                onChannelSelect = { newChannel ->
                                    state.selectedChannel = newChannel
                                    state.lastSelectedChannelId = newChannel.id
                                    state.lastSelectedProgramId = null
                                    homeViewModel.saveLastChannel(newChannel)
                                    state.isReturningFromPlayer = false
                                },
                                onBackPressed = {
                                    state.selectedChannel = null; state.isReturningFromPlayer = true
                                },
                                onShowToast = { state.toastMessage = it })
                        }

                        state.selectedProgram != null -> {
                            VideoPlayerScreen(
                                program = state.selectedProgram!!,
                                initialPositionMs = state.initialPlaybackPositionMs,
                                initialQuality = defaultVideoQuality,
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                showControls = state.showPlayerControls,
                                onShowControlsChange = { state.showPlayerControls = it },
                                isSubMenuOpen = state.isPlayerSubMenuOpen,
                                onSubMenuToggle = { state.isPlayerSubMenuOpen = it },
                                isSceneSearchOpen = state.isPlayerSceneSearchOpen,
                                onSceneSearchToggle = { state.isPlayerSceneSearchOpen = it },
                                onBackPressed = {
                                    state.selectedProgram = null; state.isReturningFromPlayer = true
                                },
                                onShowToast = { state.toastMessage = it })
                        }

                        state.isRecordListOpen -> {
                            RecordListScreen(
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                customTitle = state.openedSeriesTitle,
                                onProgramClick = { program, forcedPosition ->
                                    if (!program.recordedVideo.hasKeyFrames) return@RecordListScreen
                                    val duration = program.recordedVideo.duration
                                    val history =
                                        watchHistory.find { it.program.id.toString() == program.id.toString() }
                                    val resumePos = when {
                                        forcedPosition != null -> forcedPosition
                                        program.playbackPosition > 5.0 && (duration <= 0 || program.playbackPosition < (duration - 10)) -> program.playbackPosition
                                        history != null && history.playback_position > 5.0 && (duration <= 0 || history.playback_position < (duration - 10)) -> history.playback_position
                                        else -> 0.0
                                    }
                                    state.initialPlaybackPositionMs = (resumePos * 1000).toLong()
                                    state.selectedProgram = program
                                    state.lastSelectedProgramId = program.id.toString()
                                    state.showPlayerControls = true
                                    state.isReturningFromPlayer = false
                                },
                                onBack = {
                                    state.isRecordListOpen = false
                                    if (state.openedSeriesTitle != null) {
                                        state.isSeriesListOpen = true; state.openedSeriesTitle =
                                            null
                                    }
                                    recordViewModel.searchRecordings("")
                                })
                        }

                        else -> {
                            HomeLauncherScreen(
                                channelViewModel = channelViewModel,
                                homeViewModel = homeViewModel,
                                epgViewModel = epgViewModel,
                                recordViewModel = recordViewModel,
                                reserveViewModel = reserveViewModel,
                                groupedChannels = groupedChannels,
                                mirakurunIp = mirakurunIp,
                                mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                initialTabIndex = state.currentTabIndex,
                                onTabChange = { state.currentTabIndex = it },
                                selectedChannel = state.selectedChannel,
                                onChannelClick = { channel ->
                                    state.selectedChannel = channel
                                    if (channel != null) {
                                        state.lastSelectedChannelId = channel.id
                                        state.lastSelectedProgramId = null
                                        homeViewModel.saveLastChannel(channel)
                                        state.isReturningFromPlayer = false
                                    }
                                },
                                selectedProgram = state.selectedProgram,
                                onProgramSelected = { program ->
                                    if (program != null) {
                                        if (!program.recordedVideo.hasKeyFrames) return@HomeLauncherScreen
                                        val history =
                                            watchHistory.find { it.program.id.toString() == program.id.toString() }
                                        val duration = program.recordedVideo.duration
                                        state.initialPlaybackPositionMs =
                                            if (history != null && history.playback_position > 5.0 && (duration <= 0.0 || history.playback_position < (duration - 10.0))) {
                                                (history.playback_position * 1000).toLong()
                                            } else 0L
                                        state.selectedProgram = program
                                        state.lastSelectedProgramId = program.id.toString()
                                        state.lastSelectedChannelId = null
                                        state.showPlayerControls = true
                                        state.isReturningFromPlayer = false
                                    }
                                },
                                onReserveSelected = { reserveItem ->
                                    state.selectedReserve = reserveItem
                                },
                                isReserveOverlayOpen = state.selectedReserve != null,
                                epgSelectedProgram = state.epgSelectedProgram,
                                onEpgProgramSelected = { state.epgSelectedProgram = it },
                                isEpgJumpMenuOpen = state.isEpgJumpMenuOpen,
                                onEpgJumpMenuStateChanged = { state.isEpgJumpMenuOpen = it },
                                triggerBack = state.triggerHomeBack,
                                onBackTriggered = { state.triggerHomeBack = false },
                                onFinalBack = onExitApp,
                                onUiReady = { state.isUiReady = true }, // ここでUI準備完了を通知
                                onNavigateToPlayer = { channelId, _, _ ->
                                    val channel =
                                        groupedChannels.values.flatten().find { it.id == channelId }
                                    if (channel != null) {
                                        state.selectedChannel =
                                            channel; state.lastSelectedChannelId = channelId
                                        state.lastSelectedProgramId =
                                            null; homeViewModel.saveLastChannel(channel)
                                        state.epgSelectedProgram = null; state.isEpgJumpMenuOpen =
                                            false
                                        state.isReturningFromPlayer = false
                                    }
                                },
                                lastPlayerChannelId = state.lastSelectedChannelId,
                                lastPlayerProgramId = state.lastSelectedProgramId,
                                isSettingsOpen = state.isSettingsOpen,
                                onSettingsToggle = { state.isSettingsOpen = it },
                                isRecordListOpen = state.isRecordListOpen,
                                onShowAllRecordings = { state.isRecordListOpen = true },
                                onCloseRecordList = { state.isRecordListOpen = false },
                                onShowSeriesList = { state.isSeriesListOpen = true },
                                isReturningFromPlayer = state.isReturningFromPlayer,
                                onReturnFocusConsumed = { state.isReturningFromPlayer = false },
                                isUiReadyFlag = state.isUiReady
                            )
                        }
                    }
                }
            }

            // ローディング画面の制御（UIが準備できるまでオーバーレイし続ける）
            AnimatedVisibility(
                visible = !state.isUiReady && !state.showConnectionErrorDialog && isSettingsInitialized,
                enter = fadeIn(),
                exit = fadeOut(tween(500))
            ) {
                if (syncProgress.isSyncing && syncProgress.isInitialBuild) {
                    LoadingScreen(
                        message = syncProgress.progressText,
                        progressRatio = syncProgress.progressRatio
                    )
                } else {
                    LoadingScreen()
                }
            }

            // ダイアログ・トースト系 (変更なし)
            if (state.selectedReserve != null) {
                val program =
                    remember(state.selectedReserve) { ReserveMapper.toEpgProgram(state.selectedReserve!!) }
                ProgramDetailScreen(
                    program = program, mode = ProgramDetailMode.RESERVE, isReserved = true,
                    onBackClick = { state.selectedReserve = null },
                    onDeleteReserveClick = { _ -> state.reserveToDelete = state.selectedReserve },
                    onEditReserveClick = { _ ->
                        reserveViewModel.refreshReserveItem(state.selectedReserve!!.id) { latest ->
                            state.editingReserveItem = latest ?: state.selectedReserve
                        }
                    },
                    initialFocusRequester = detailFocusRequester
                )
            }
            if (state.epgSelectedProgram != null) {
                val relatedReserve =
                    reserves.find { it.program.id == state.epgSelectedProgram!!.id }
                ProgramDetailScreen(
                    program = state.epgSelectedProgram!!,
                    mode = ProgramDetailMode.EPG,
                    isReserved = relatedReserve != null,
                    onPlayClick = {
                        val channel =
                            groupedChannels.values.flatten().find { ch -> ch.id == it.channel_id }
                        if (channel != null) {
                            state.selectedChannel = channel; state.lastSelectedChannelId =
                                channel.id
                            state.lastSelectedProgramId = null; homeViewModel.saveLastChannel(
                                channel
                            )
                            state.epgSelectedProgram = null; state.isReturningFromPlayer = false
                        }
                    },
                    onRecordClick = { program ->
                        reserveViewModel.addReserve(program.id) {
                            scope.launch {
                                state.epgSelectedProgram = null; delay(300)
                                val now = OffsetDateTime.now()
                                val start = try {
                                    OffsetDateTime.parse(program.start_time)
                                } catch (e: Exception) {
                                    now
                                }
                                val end = try {
                                    OffsetDateTime.parse(program.end_time)
                                } catch (e: Exception) {
                                    now
                                }
                                val isBroadcasting = now.isAfter(start) && now.isBefore(end)
                                state.toastMessage =
                                    if (isBroadcasting) AppStrings.TOAST_RECORDING_STARTED else AppStrings.TOAST_RESERVED
                            }
                        }
                    },
                    onRecordDetailClick = { program -> state.editingNewProgram = program },
                    onEditReserveClick = { _ ->
                        if (relatedReserve != null) reserveViewModel.refreshReserveItem(
                            relatedReserve.id
                        ) { state.editingReserveItem = it ?: relatedReserve }
                    },
                    onDeleteReserveClick = { _ ->
                        if (relatedReserve != null) state.reserveToDelete = relatedReserve
                    },
                    onBackClick = { state.epgSelectedProgram = null },
                    initialFocusRequester = detailFocusRequester
                )
            }
            if (state.editingReserveItem != null) {
                ReserveSettingsDialog(
                    programTitle = state.editingReserveItem!!.program.title,
                    initialSettings = state.editingReserveItem!!.recordSettings,
                    isNewReservation = false,
                    onConfirm = { newSettings ->
                        reserveViewModel.updateReservation(
                            state.editingReserveItem!!,
                            newSettings
                        ) {
                            scope.launch {
                                state.editingReserveItem = null; state.toastMessage =
                                AppStrings.TOAST_RESERVE_UPDATED; delay(200); detailFocusRequester.safeRequestFocus(
                                "ProgramDetail"
                            )
                            }
                        }
                    },
                    onDismiss = {
                        state.editingReserveItem =
                            null; scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") }
                    })
            }
            if (state.editingNewProgram != null) {
                val defaultSettings = remember {
                    ReserveRecordSettings(
                        isEnabled = true,
                        priority = 3,
                        recordingMode = "SpecifiedService",
                        isEventRelayFollowEnabled = true
                    )
                }
                ReserveSettingsDialog(
                    programTitle = state.editingNewProgram!!.title,
                    initialSettings = defaultSettings,
                    isNewReservation = true,
                    onConfirm = { newSettings ->
                        reserveViewModel.addReserveWithSettings(
                            state.editingNewProgram!!.id,
                            newSettings
                        ) {
                            scope.launch {
                                state.editingNewProgram = null; state.epgSelectedProgram =
                                null; delay(300); state.toastMessage = AppStrings.TOAST_RESERVED
                            }
                        }
                    },
                    onDismiss = {
                        state.editingNewProgram =
                            null; scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") }
                    })
            }
            if (state.reserveToDelete != null) {
                DeleteConfirmationDialog(
                    title = AppStrings.DIALOG_DELETE_RESERVE_TITLE,
                    message = String.format(
                        AppStrings.DIALOG_DELETE_RESERVE_MESSAGE,
                        state.reserveToDelete?.program?.title ?: ""
                    ),
                    onConfirm = {
                        val id = state.reserveToDelete!!.id
                        reserveViewModel.deleteReservation(id) {
                            scope.launch {
                                state.reserveToDelete =
                                    null; if (state.selectedReserve != null) state.selectedReserve =
                                null; if (state.epgSelectedProgram != null) state.epgSelectedProgram =
                                null; delay(300); state.toastMessage =
                                AppStrings.TOAST_RESERVE_DELETED
                            }
                        }
                    },
                    onCancel = { state.reserveToDelete = null })
            }
            if (!isSettingsInitialized && !state.isSettingsOpen && state.isSplashFinished) {
                InitialSetupDialog(onConfirm = { state.isSettingsOpen = true })
            }
            if (state.showConnectionErrorDialog && isSettingsInitialized && !state.isSettingsOpen) {
                ConnectionErrorDialog(onGoToSettings = {
                    state.showConnectionErrorDialog = false; state.isSettingsOpen = true
                }, onExit = onExitApp)
            }
            if (state.isSettingsOpen) {
                SettingsScreen(
                    onBack = closeSettingsAndRefresh,
                    onClearLastChannel = {
                        homeViewModel.clearLastChannelHistory(); state.toastMessage =
                        AppStrings.TOAST_CHANNEL_HISTORY_DELETED
                    },
                    onClearWatchHistory = {
                        recordViewModel.clearWatchHistory(); state.toastMessage =
                        AppStrings.TOAST_WATCH_HISTORY_DELETED
                    })
            }
            GlobalToast(message = state.toastMessage)
        }
    }
}