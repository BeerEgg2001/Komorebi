@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.mapper.ReserveMapper
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.data.sync.SyncProgress
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
import com.beeregg2001.komorebi.util.UpdateState
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

    val isEpgReady by epgViewModel.isInitialLoadComplete.collectAsState()

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    // ★追加: アップデーターの状態監視
    val updateState by homeViewModel.updateState.collectAsState()

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

    val closeSettingsAndRefresh = {
        state.isSettingsOpen = false
        state.isDataReady = false
        state.isUiReady = false
        state.showConnectionErrorDialog = false
        state.currentTabIndex = 0
        channelViewModel.fetchChannels()
        epgViewModel.preloadAllEpgData()
        homeViewModel.refreshHomeData()
        recordViewModel.fetchRecentRecordings(forceRefresh = false)
        reserveViewModel.fetchReserves()
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3000); state.toastMessage = null
        }
    }

    // ★追加: アップデートエラー時のトースト処理
    LaunchedEffect(updateState) {
        if (updateState is UpdateState.Error) {
            state.toastMessage = (updateState as UpdateState.Error).message
            homeViewModel.dismissUpdate()
        }
    }

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

    LaunchedEffect(isEpgReady, state.isDataReady, isSettingsInitialized, state.currentTabIndex) {
        if (!isSettingsInitialized) {
            delay(500); state.isSplashFinished = true
        } else if (state.currentTabIndex == 3) {
            if (isEpgReady && state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        } else {
            if (state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        }
    }

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
                                onUiReady = { state.isUiReady = true },
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

                    if (state.selectedChannel == null && state.selectedProgram == null && !syncProgress.isInitialBuild) {
                        SyncProgressIndicator(
                            syncProgress = syncProgress,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 40.dp, bottom = 40.dp)
                        )
                    }

                    if (syncProgress.error != null) {
                        SyncErrorDialog(
                            errorMessage = syncProgress.error!!,
                            onRetry = {
                                recordViewModel.clearSyncError()
                                recordViewModel.triggerSmartSync()
                            },
                            onDismiss = { recordViewModel.clearSyncError() }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !state.isUiReady && !state.showConnectionErrorDialog && isSettingsInitialized,
                enter = fadeIn(),
                exit = fadeOut(tween(500))
            ) {
                if (syncProgress.isSyncing && syncProgress.isInitialBuild) {
                    val pRatio =
                        if (syncProgress.total > 0) syncProgress.current.toFloat() / syncProgress.total.toFloat() else 0f
                    LoadingScreen(
                        message = syncProgress.progressText,
                        progressRatio = pRatio
                    )
                } else {
                    LoadingScreen()
                }
            }

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

            // ★追加: アップデート利用可能な場合のダイアログ
            if (updateState is UpdateState.UpdateAvailable) {
                val available = updateState as UpdateState.UpdateAvailable
                UpdateDialog(
                    versionName = available.versionName,
                    releaseNotes = available.releaseNotes,
                    onConfirm = { homeViewModel.startUpdateDownload(available.apkUrl) },
                    onDismiss = { homeViewModel.dismissUpdate() }
                )
            }

            // ★追加: ダウンロード中・準備完了のプログレスバナー（画面右下固定）
            if (updateState is UpdateState.Downloading || updateState is UpdateState.ReadyToInstall) {
                Box(modifier = Modifier.fillMaxSize()) {
                    UpdateProgressBanner(
                        updateState = updateState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(48.dp) // TVのセーフエリアを考慮
                    )
                }
            }

            GlobalToast(message = state.toastMessage)
        }
    }
}

/**
 * 右下に表示される汎用の進捗インジケーターコンポーネント
 */
@Composable
fun SyncProgressIndicator(
    syncProgress: SyncProgress,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val progress = if (syncProgress.total > 0) {
        syncProgress.current.toFloat() / syncProgress.total.toFloat()
    } else {
        0f
    }

    AnimatedVisibility(
        visible = syncProgress.isSyncing,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface.copy(alpha = 0.9f),
                contentColor = colors.textPrimary
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(min = 200.dp, max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = syncProgress.progressText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (syncProgress.total > 0) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = colors.textPrimary.copy(alpha = 0.2f)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = colors.textPrimary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

/**
 * 同期エラー時に表示されるダイアログ（再実行ボタン付き）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SyncErrorDialog(errorMessage: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("SyncError") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "同期エラー",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("閉じる") }

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    ) { Text("再実行") }
                }
            }
        }
    }
}

// ★追加: アプリ内アップデーター用ダイアログ
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateDialog(
    versionName: String,
    releaseNotes: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .zIndex(200f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp), // ★修正: ClickableSurfaceDefaults.shape を削除
            colors = SurfaceDefaults.colors(     // ★修正: ClickableSurfaceDefaults.colors を SurfaceDefaults.colors に変更
                containerColor = colors.surface,
                contentColor = colors.textPrimary
            ),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "アップデートのお知らせ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "最新バージョン ($versionName) が利用可能です。\n\n$releaseNotes\n\nアップデート開始後、Androidのシステム画面が開きますので、「インストール」または「更新」を選択してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("後で") }

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    ) { Text("今すぐ更新") }
                }
            }
        }
    }
}

// ★追加: アプリ内アップデーター用プログレスバナー（画面右下）
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateProgressBanner(
    updateState: UpdateState,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val isReady = updateState is UpdateState.ReadyToInstall
    val progress = if (updateState is UpdateState.Downloading) updateState.progressPercentage else 100

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(8.dp), // ★修正: ClickableSurfaceDefaults.shape を削除
        colors = SurfaceDefaults.colors(    // ★修正: NonInteractiveSurfaceDefaults.colors を SurfaceDefaults.colors に変更
            containerColor = colors.surface.copy(alpha = 0.95f),
            contentColor = colors.textPrimary
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Download,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReady) "インストーラ起動中..." else "アップデートをダウンロード中",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isReady) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(colors.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress / 100f)
                            .fillMaxHeight()
                            .background(colors.accent, RoundedCornerShape(2.dp))
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$progress %",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}