@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.mapper.ReserveMapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.epg.ProgramDetailMode
import com.beeregg2001.komorebi.ui.epg.ProgramDetailScreen
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.ui.video.VideoPlayerScreen
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.ui.video.SeriesListScreen
import com.beeregg2001.komorebi.ui.reserve.ReserveSettingsDialog
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var selectedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var initialPlaybackPositionMs by remember { mutableLongStateOf(0L) }

    var epgSelectedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var selectedReserve by remember { mutableStateOf<ReserveItem?>(null) }

    var editingReserveItem by remember { mutableStateOf<ReserveItem?>(null) }
    var editingNewProgram by remember { mutableStateOf<EpgProgram?>(null) }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var reserveToDelete by remember { mutableStateOf<ReserveItem?>(null) }

    var toastMessage by remember { mutableStateOf<String?>(null) }

    var isEpgJumpMenuOpen by remember { mutableStateOf(false) }
    var triggerHomeBack by remember { mutableStateOf(false) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }

    var isRecordListOpen by remember { mutableStateOf(false) }
    var isSeriesListOpen by remember { mutableStateOf(false) }
    var openedSeriesTitle by remember { mutableStateOf<String?>(null) }

    var isPlayerMiniListOpen by remember { mutableStateOf(false) }
    var playerShowOverlay by remember { mutableStateOf(true) }
    var playerIsManualOverlay by remember { mutableStateOf(false) }
    var playerIsPinnedOverlay by remember { mutableStateOf(false) }
    var playerIsSubMenuOpen by remember { mutableStateOf(false) }

    var showPlayerControls by remember { mutableStateOf(true) }
    var isPlayerSubMenuOpen by remember { mutableStateOf(false) }
    var isPlayerSceneSearchOpen by remember { mutableStateOf(false) }

    var lastSelectedChannelId by remember { mutableStateOf<String?>(null) }
    var lastSelectedProgramId by remember { mutableStateOf<String?>(null) }
    var isReturningFromPlayer by remember { mutableStateOf(false) }

    val detailFocusRequester = remember { FocusRequester() }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val searchHistory by recordViewModel.searchHistory.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()

    // ★修正: 変数名を isRecLoading に統一して Unresolved Reference を解消
    val isRecLoading by recordViewModel.isRecordingLoading.collectAsState()
    val isRecLoadingMore by recordViewModel.isLoadingMore.collectAsState()

    var isDataReady by remember { mutableStateOf(false) }
    var isSplashFinished by remember { mutableStateOf(false) }
    var showConnectionErrorDialog by remember { mutableStateOf(false) }

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    val epgUiState = epgViewModel.uiState
    var hasAppliedStartupTab by rememberSaveable { mutableStateOf(false) }

    // アプリ起動時に一度だけローカルからデフォルトタブを読み込んで適用する
    LaunchedEffect(Unit) {
        if (!hasAppliedStartupTab) {
            val tab = settingsViewModel.getStartupTabOnce()
            currentTabIndex = when (tab) {
                "ホーム" -> 0
                "ライブ" -> 1
                "ビデオ" -> 2
                "番組表" -> 3
                "録画予約" -> 4
                else -> 0
            }
            hasAppliedStartupTab = true
        }
    }

    val closeSettingsAndRefresh = {
        isSettingsOpen = false
        isDataReady = false
        showConnectionErrorDialog = false
        channelViewModel.fetchChannels()
        epgViewModel.preloadAllEpgData()
        homeViewModel.refreshHomeData()
        recordViewModel.fetchRecentRecordings()
        reserveViewModel.fetchReserves()
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3000)
            toastMessage = null
        }
    }

    BackHandler(enabled = true) {
        when {
            editingNewProgram != null -> editingNewProgram = null
            editingReserveItem != null -> editingReserveItem = null
            reserveToDelete != null -> reserveToDelete = null
            showDeleteConfirmDialog -> showDeleteConfirmDialog = false
            isPlayerMiniListOpen -> isPlayerMiniListOpen = false
            playerIsSubMenuOpen -> playerIsSubMenuOpen = false
            isPlayerSubMenuOpen -> isPlayerSubMenuOpen = false
            isPlayerSceneSearchOpen -> { isPlayerSceneSearchOpen = false; showPlayerControls = false }
            selectedChannel != null -> { selectedChannel = null; isReturningFromPlayer = true }
            selectedProgram != null -> { selectedProgram = null; showPlayerControls = true; isReturningFromPlayer = true }
            isSettingsOpen -> closeSettingsAndRefresh()
            epgSelectedProgram != null -> epgSelectedProgram = null
            selectedReserve != null -> selectedReserve = null
            isEpgJumpMenuOpen -> isEpgJumpMenuOpen = false
            isRecordListOpen -> {
                isRecordListOpen = false
                if (openedSeriesTitle != null) {
                    isSeriesListOpen = true
                    openedSeriesTitle = null
                }
                recordViewModel.searchRecordings("")
            }
            isSeriesListOpen -> {
                isSeriesListOpen = false
                recordViewModel.searchRecordings("")
            }
            showConnectionErrorDialog -> onExitApp()
            else -> triggerHomeBack = true
        }
    }

    LaunchedEffect(isChannelLoading, isHomeLoading) {
        delay(500)
        if (!isChannelLoading && !isHomeLoading) {
            if (isChannelError) {
                showConnectionErrorDialog = true
                isDataReady = false
            } else {
                showConnectionErrorDialog = false
                isDataReady = true
            }
        }
    }

    // 起動時の暗転を短縮
    LaunchedEffect(Unit) {
        delay(500)
        isSplashFinished = true
    }

    val isAppReady = ((isDataReady && isSplashFinished) || (!isSettingsInitialized && isSplashFinished)) && hasAppliedStartupTab

    Box(modifier = Modifier.fillMaxSize()) {
        val showMainContent = isAppReady && isSettingsInitialized && !showConnectionErrorDialog

        AnimatedVisibility(
            visible = showMainContent,
            enter = fadeIn(animationSpec = tween(400)),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    selectedChannel != null -> {
                        LivePlayerScreen(
                            channel = selectedChannel!!,
                            mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                            konomiIp = konomiIp, konomiPort = konomiPort,
                            initialQuality = defaultLiveQuality,
                            groupedChannels = groupedChannels,
                            isMiniListOpen = isPlayerMiniListOpen,
                            onMiniListToggle = { isPlayerMiniListOpen = it },
                            showOverlay = playerShowOverlay,
                            onShowOverlayChange = { playerShowOverlay = it },
                            isManualOverlay = playerIsManualOverlay,
                            onManualOverlayChange = { playerIsManualOverlay = it },
                            isPinnedOverlay = playerIsPinnedOverlay,
                            onPinnedOverlayChange = { playerIsPinnedOverlay = it },
                            isSubMenuOpen = playerIsSubMenuOpen,
                            onSubMenuToggle = { playerIsSubMenuOpen = it },
                            onChannelSelect = { newChannel ->
                                selectedChannel = newChannel
                                lastSelectedChannelId = newChannel.id
                                lastSelectedProgramId = null
                                homeViewModel.saveLastChannel(newChannel)
                                isReturningFromPlayer = false
                            },
                            onBackPressed = { selectedChannel = null; isReturningFromPlayer = true },
                            reserveViewModel = reserveViewModel,
                            epgViewModel = epgViewModel,
                            onShowToast = { toastMessage = it }
                        )
                    }
                    selectedProgram != null -> {
                        VideoPlayerScreen(
                            program = selectedProgram!!,
                            initialPositionMs = initialPlaybackPositionMs,
                            initialQuality = defaultVideoQuality,
                            konomiIp = konomiIp, konomiPort = konomiPort,
                            showControls = showPlayerControls,
                            onShowControlsChange = { showPlayerControls = it },
                            isSubMenuOpen = isPlayerSubMenuOpen,
                            onSubMenuToggle = { isPlayerSubMenuOpen = it },
                            isSceneSearchOpen = isPlayerSceneSearchOpen,
                            onSceneSearchToggle = { isPlayerSceneSearchOpen = it },
                            onBackPressed = { selectedProgram = null; isReturningFromPlayer = true },
                            onUpdateWatchHistory = { prog, pos -> recordViewModel.updateWatchHistory(prog, pos) }
                        )
                    }
                    isSeriesListOpen -> {
                        val groupedSeries by recordViewModel.groupedSeries.collectAsState()
                        val isSeriesLoading by recordViewModel.isSeriesLoading.collectAsState()
                        LaunchedEffect(Unit) { recordViewModel.buildSeriesIndex() }
                        SeriesListScreen(
                            groupedSeries = groupedSeries,
                            isLoading = isSeriesLoading,
                            onSeriesClick = { searchKeyword, displayTitle ->
                                recordViewModel.searchRecordings(searchKeyword)
                                openedSeriesTitle = displayTitle
                                isSeriesListOpen = false
                                isRecordListOpen = true
                            },
                            onBack = {
                                isSeriesListOpen = false
                                recordViewModel.searchRecordings("")
                            }
                        )
                    }
                    isRecordListOpen -> {
                        RecordListScreen(
                            recentRecordings = recentRecordings,
                            searchHistory = searchHistory,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            onProgramClick = { program ->
                                if (!program.recordedVideo.hasKeyFrames) return@RecordListScreen
                                val history = watchHistory.find { it.program.id.toString() == program.id.toString() }
                                val duration = program.recordedVideo.duration
                                initialPlaybackPositionMs = if (history != null && history.playback_position > 5 && history.playback_position < (duration - 10)) {
                                    (history.playback_position * 1000).toLong()
                                } else 0L
                                selectedProgram = program
                                lastSelectedProgramId = program.id.toString()
                            },
                            onLoadMore = { recordViewModel.loadNextPage() },
                            isLoadingInitial = isRecLoading, // ★修正済み
                            isLoadingMore = isRecLoadingMore,
                            customTitle = openedSeriesTitle,
                            onBack = {
                                isRecordListOpen = false
                                if (openedSeriesTitle != null) {
                                    isSeriesListOpen = true
                                    openedSeriesTitle = null
                                }
                                recordViewModel.searchRecordings("")
                            },
                            onSearch = { query -> recordViewModel.searchRecordings(query) }
                        )
                    }
                    else -> {
                        HomeLauncherScreen(
                            channelViewModel = channelViewModel, homeViewModel = homeViewModel, epgViewModel = epgViewModel, recordViewModel = recordViewModel,
                            reserveViewModel = reserveViewModel,
                            groupedChannels = groupedChannels, mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort, konomiIp = konomiIp, konomiPort = konomiPort,
                            initialTabIndex = currentTabIndex, onTabChange = { currentTabIndex = it },
                            selectedChannel = selectedChannel,
                            onChannelClick = { channel ->
                                selectedChannel = channel
                                if (channel != null) {
                                    lastSelectedChannelId = channel.id; lastSelectedProgramId = null
                                    homeViewModel.saveLastChannel(channel)
                                    isReturningFromPlayer = false
                                }
                            },
                            selectedProgram = selectedProgram,
                            onProgramSelected = { program ->
                                if (program != null) {
                                    if (!program.recordedVideo.hasKeyFrames) return@HomeLauncherScreen
                                    val history = watchHistory.find { it.program.id.toString() == program.id.toString() }
                                    val duration = program.recordedVideo.duration
                                    initialPlaybackPositionMs = if (history != null && history.playback_position > 5.0 && (duration <= 0.0 || history.playback_position < (duration - 10.0))) {
                                        (history.playback_position * 1000).toLong()
                                    } else 0L
                                    selectedProgram = program
                                    lastSelectedProgramId = program.id.toString()
                                    lastSelectedChannelId = null
                                    showPlayerControls = true
                                    isReturningFromPlayer = false
                                }
                            },
                            onReserveSelected = { reserveItem -> selectedReserve = reserveItem },
                            isReserveOverlayOpen = selectedReserve != null,
                            epgSelectedProgram = epgSelectedProgram,
                            onEpgProgramSelected = { epgSelectedProgram = it },
                            isEpgJumpMenuOpen = isEpgJumpMenuOpen, onEpgJumpMenuStateChanged = { isEpgJumpMenuOpen = it },
                            triggerBack = triggerHomeBack, onBackTriggered = { triggerHomeBack = false }, onFinalBack = onExitApp, onUiReady = { },
                            onNavigateToPlayer = { channelId, _, _ ->
                                val channel = groupedChannels.values.flatten().find { it.id == channelId }
                                if (channel != null) {
                                    selectedChannel = channel; lastSelectedChannelId = channelId; lastSelectedProgramId = null
                                    homeViewModel.saveLastChannel(channel); epgSelectedProgram = null; isEpgJumpMenuOpen = false; isReturningFromPlayer = false
                                }
                            },
                            lastPlayerChannelId = lastSelectedChannelId, lastPlayerProgramId = lastSelectedProgramId,
                            isSettingsOpen = isSettingsOpen, onSettingsToggle = { isSettingsOpen = it },
                            isRecordListOpen = isRecordListOpen, onShowAllRecordings = { isRecordListOpen = true }, onCloseRecordList = { isRecordListOpen = false },
                            onShowSeriesList = { isSeriesListOpen = true },
                            isReturningFromPlayer = isReturningFromPlayer, onReturnFocusConsumed = { isReturningFromPlayer = false }
                            // ★不整合だった Loading 系の引数を削除（HomeLauncherScreen側で直接収集するため）
                        )
                    }
                }
            }
        }

        if (!showMainContent && !showConnectionErrorDialog) {
            LoadingScreen()
        }

        // --- ダイアログ等は変更なし ---
        if (selectedReserve != null) {
            val program = remember(selectedReserve) { ReserveMapper.toEpgProgram(selectedReserve!!) }
            ProgramDetailScreen(
                program = program, mode = ProgramDetailMode.RESERVE, isReserved = true,
                onBackClick = { selectedReserve = null }, onDeleteReserveClick = { _ -> reserveToDelete = selectedReserve },
                onEditReserveClick = { _ -> reserveViewModel.refreshReserveItem(selectedReserve!!.id) { latest -> editingReserveItem = latest ?: selectedReserve } },
                initialFocusRequester = detailFocusRequester
            )
        }

        if (epgSelectedProgram != null) {
            val relatedReserve = reserves.find { it.program.id == epgSelectedProgram!!.id }
            val isReserved = relatedReserve != null
            ProgramDetailScreen(
                program = epgSelectedProgram!!, mode = ProgramDetailMode.EPG, isReserved = isReserved,
                onPlayClick = {
                    val channel = groupedChannels.values.flatten().find { ch -> ch.id == it.channel_id }
                    if (channel != null) {
                        selectedChannel = channel; lastSelectedChannelId = channel.id; lastSelectedProgramId = null
                        homeViewModel.saveLastChannel(channel); epgSelectedProgram = null; isReturningFromPlayer = false
                    }
                },
                onRecordClick = { program ->
                    reserveViewModel.addReserve(program.id) {
                        scope.launch {
                            epgSelectedProgram = null; delay(300)
                            val now = OffsetDateTime.now()
                            val start = try { OffsetDateTime.parse(program.start_time) } catch (e: Exception) { now }
                            val end = try { OffsetDateTime.parse(program.end_time) } catch (e: Exception) { now }
                            val isBroadcasting = now.isAfter(start) && now.isBefore(end)
                            toastMessage = if (isBroadcasting) "録画を開始しました" else "予約しました"
                        }
                    }
                },
                onRecordDetailClick = { program -> editingNewProgram = program },
                onEditReserveClick = { _ -> if (relatedReserve != null) { reserveViewModel.refreshReserveItem(relatedReserve.id) { latest -> editingReserveItem = latest ?: relatedReserve } } },
                onDeleteReserveClick = { _ -> if (relatedReserve != null) { reserveToDelete = relatedReserve } },
                onBackClick = { epgSelectedProgram = null },
                initialFocusRequester = detailFocusRequester
            )
        }

        if (editingReserveItem != null) {
            ReserveSettingsDialog(
                programTitle = editingReserveItem!!.program.title, initialSettings = editingReserveItem!!.recordSettings, isNewReservation = false,
                onConfirm = { newSettings -> reserveViewModel.updateReservation(editingReserveItem!!, newSettings) { scope.launch { editingReserveItem = null; toastMessage = "予約設定を更新しました"; delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") } } },
                onDismiss = { editingReserveItem = null; scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") } }
            )
        }

        if (editingNewProgram != null) {
            val defaultSettings = remember { ReserveRecordSettings(isEnabled = true, priority = 3, recordingMode = "SpecifiedService", isEventRelayFollowEnabled = true) }
            ReserveSettingsDialog(
                programTitle = editingNewProgram!!.title, initialSettings = defaultSettings, isNewReservation = true,
                onConfirm = { newSettings -> reserveViewModel.addReserveWithSettings(editingNewProgram!!.id, newSettings) { scope.launch { editingNewProgram = null; epgSelectedProgram = null; delay(300); toastMessage = "予約しました" } } },
                onDismiss = { editingNewProgram = null; scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") } }
            )
        }

        if (reserveToDelete != null) {
            DeleteConfirmationDialog(
                title = "予約の削除", message = "この予約を削除してもよろしいですか？\n${reserveToDelete?.program?.title ?: ""}",
                onConfirm = {
                    val id = reserveToDelete!!.id
                    reserveViewModel.deleteReservation(id) {
                        scope.launch {
                            reserveToDelete = null
                            if (selectedReserve != null) selectedReserve = null
                            if (epgSelectedProgram != null) epgSelectedProgram = null
                            delay(300)
                            toastMessage = "予約を削除しました"
                        }
                    }
                },
                onCancel = { reserveToDelete = null }
            )
        }

        if (!isSettingsInitialized && !isSettingsOpen && isSplashFinished) { InitialSetupDialog(onConfirm = { isSettingsOpen = true }) }
        if (showConnectionErrorDialog && isSettingsInitialized && !isSettingsOpen) { ConnectionErrorDialog(onGoToSettings = { showConnectionErrorDialog = false; isSettingsOpen = true }, onExit = onExitApp) }
        if (isSettingsOpen) { SettingsScreen(onBack = closeSettingsAndRefresh) }

        GlobalToast(message = toastMessage)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DeleteConfirmationDialog(title: String, message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("DeleteConfirm") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = Color(0xFF222222)), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White), modifier = Modifier.weight(1f)) { Text("キャンセル") }
                    Button(onClick = onConfirm, colors = ButtonDefaults.colors(containerColor = Color(0xFFD32F2F), contentColor = Color.White), modifier = Modifier.weight(1f).focusRequester(focusRequester)) { Text("削除する") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InitialSetupDialog(onConfirm: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("InitialSetup") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = Color(0xFF222222)), modifier = Modifier.width(400.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = AppStrings.SETUP_REQUIRED_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = AppStrings.SETUP_REQUIRED_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onConfirm, colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)) { Text(AppStrings.GO_TO_SETTINGS) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectionErrorDialog(onGoToSettings: () -> Unit, onExit: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("ConnectionError") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = AppStrings.CONNECTION_ERROR_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = AppStrings.CONNECTION_ERROR_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onExit, colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White), modifier = Modifier.weight(1f)) { Text(AppStrings.EXIT_APP) }
                    Button(onClick = onGoToSettings, colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.weight(1f).focusRequester(focusRequester)) { Text(AppStrings.GO_TO_SETTINGS_SHORT) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IncompatibleOsDialog(onExit: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("IncompatibleOS") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "非対応のOSバージョン", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "本アプリの実行には Android 8.0 (API 26) 以上が必要です。\nお使いの端末 (API ${Build.VERSION.SDK_INT}) は現在サポートされていません。", style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onExit, colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White), modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)) { Text("アプリを終了する") }
            }
        }
    }
}