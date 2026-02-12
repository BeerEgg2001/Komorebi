@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.ui.video.VideoPlayerScreen
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootScreen(
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    recordViewModel: RecordViewModel,
    onExitApp: () -> Unit
) {
    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }

    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var selectedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var epgSelectedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var isEpgJumpMenuOpen by remember { mutableStateOf(false) }
    var triggerHomeBack by remember { mutableStateOf(false) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isRecordListOpen by remember { mutableStateOf(false) }

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

    // プレイヤーから戻ったことを通知するフラグ
    var isReturningFromPlayer by remember { mutableStateOf(false) }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isRecLoading by recordViewModel.isRecordingLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()

    var isDataReady by remember { mutableStateOf(false) }
    var isSplashFinished by remember { mutableStateOf(false) }
    var showConnectionErrorDialog by remember { mutableStateOf(false) }

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "192.168.100.60")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "40772")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "https://192-168-100-60.local.konomi.tv")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "7000")

    BackHandler(enabled = true) {
        when {
            isPlayerMiniListOpen -> isPlayerMiniListOpen = false
            playerIsSubMenuOpen -> playerIsSubMenuOpen = false
            isPlayerSubMenuOpen -> isPlayerSubMenuOpen = false

            isPlayerSceneSearchOpen -> {
                isPlayerSceneSearchOpen = false
                showPlayerControls = false
            }

            selectedChannel != null -> {
                selectedChannel = null
                isReturningFromPlayer = true
            }
            selectedProgram != null -> {
                selectedProgram = null
                showPlayerControls = true
                isReturningFromPlayer = true
            }
            isSettingsOpen -> isSettingsOpen = false
            epgSelectedProgram != null -> epgSelectedProgram = null
            isEpgJumpMenuOpen -> isEpgJumpMenuOpen = false
            isRecordListOpen -> isRecordListOpen = false
            showConnectionErrorDialog -> onExitApp()
            else -> triggerHomeBack = true
        }
    }

    LaunchedEffect(isChannelLoading, isHomeLoading, isRecLoading) {
        delay(500)
        if (!isChannelLoading && !isHomeLoading && !isRecLoading) {
            if (isChannelError) {
                showConnectionErrorDialog = true
                isDataReady = false
            } else {
                showConnectionErrorDialog = false
                isDataReady = true
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(2000)
        isSplashFinished = true
    }

    val isAppReady = (isDataReady && isSplashFinished) || (!isSettingsInitialized && isSplashFinished)

    Box(modifier = Modifier.fillMaxSize()) {
        val showMainContent = isAppReady && isSettingsInitialized && !showConnectionErrorDialog

        AnimatedVisibility(visible = showMainContent, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedChannel != null) {
                    LivePlayerScreen(
                        channel = selectedChannel!!,
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
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
                        onBackPressed = {
                            selectedChannel = null
                            isReturningFromPlayer = true
                        }
                    )
                } else if (selectedProgram != null) {
                    VideoPlayerScreen(
                        program = selectedProgram!!,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        showControls = showPlayerControls,
                        onShowControlsChange = { showPlayerControls = it },
                        isSubMenuOpen = isPlayerSubMenuOpen,
                        onSubMenuToggle = { isPlayerSubMenuOpen = it },
                        isSceneSearchOpen = isPlayerSceneSearchOpen,
                        onSceneSearchToggle = { isPlayerSceneSearchOpen = it },
                        onBackPressed = {
                            selectedProgram = null
                            isReturningFromPlayer = true
                        },
                        onUpdateWatchHistory = { prog, pos ->
                            recordViewModel.updateWatchHistory(prog, pos)
                        }
                    )
                } else {
                    HomeLauncherScreen(
                        channelViewModel = channelViewModel,
                        homeViewModel = homeViewModel,
                        epgViewModel = epgViewModel,
                        recordViewModel = recordViewModel,
                        groupedChannels = groupedChannels,
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        initialTabIndex = currentTabIndex,
                        onTabChange = { currentTabIndex = it },
                        selectedChannel = selectedChannel,
                        onChannelClick = { channel ->
                            selectedChannel = channel
                            if (channel != null) {
                                lastSelectedChannelId = channel.id
                                lastSelectedProgramId = null
                                homeViewModel.saveLastChannel(channel)
                                isReturningFromPlayer = false
                            }
                        },
                        selectedProgram = selectedProgram,
                        onProgramSelected = { program ->
                            selectedProgram = program
                            if (program != null) {
                                lastSelectedProgramId = program.id.toString()
                                lastSelectedChannelId = null
                                showPlayerControls = true
                                isReturningFromPlayer = false
                            }
                        },
                        epgSelectedProgram = epgSelectedProgram,
                        onEpgProgramSelected = { epgSelectedProgram = it },
                        isEpgJumpMenuOpen = isEpgJumpMenuOpen,
                        onEpgJumpMenuStateChanged = { isEpgJumpMenuOpen = it },
                        triggerBack = triggerHomeBack,
                        onBackTriggered = { triggerHomeBack = false },
                        onFinalBack = onExitApp,
                        onUiReady = { },
                        onNavigateToPlayer = { channelId, _, _ ->
                            val channel = groupedChannels.values.flatten().find { it.id == channelId }
                            if (channel != null) {
                                selectedChannel = channel
                                lastSelectedChannelId = channelId
                                lastSelectedProgramId = null
                                homeViewModel.saveLastChannel(channel)
                                epgSelectedProgram = null
                                isEpgJumpMenuOpen = false
                                isReturningFromPlayer = false
                            }
                        },
                        lastPlayerChannelId = lastSelectedChannelId,
                        lastPlayerProgramId = lastSelectedProgramId,
                        isSettingsOpen = isSettingsOpen,
                        onSettingsToggle = { isSettingsOpen = it },
                        isRecordListOpen = isRecordListOpen,
                        onShowAllRecordings = { isRecordListOpen = true },
                        onCloseRecordList = { isRecordListOpen = false },
                        isReturningFromPlayer = isReturningFromPlayer,
                        onReturnFocusConsumed = { isReturningFromPlayer = false }
                    )
                }
            }
        }

        if (!isSettingsInitialized && !isSettingsOpen && isSplashFinished) {
            InitialSetupDialog(onConfirm = { isSettingsOpen = true })
        }

        if (showConnectionErrorDialog && isSettingsInitialized && !isSettingsOpen) {
            ConnectionErrorDialog(
                onGoToSettings = {
                    showConnectionErrorDialog = false
                    isSettingsOpen = true
                },
                onExit = onExitApp
            )
        }

        if (isSettingsOpen) {
            SettingsScreen(
                onBack = {
                    isSettingsOpen = false
                    isDataReady = false
                    showConnectionErrorDialog = false
                    channelViewModel.fetchChannels()
                    epgViewModel.preloadAllEpgData()
                    homeViewModel.refreshHomeData()
                    recordViewModel.fetchRecentRecordings()
                }
            )
        }

        AnimatedVisibility(
            visible = !isAppReady && isSettingsInitialized && !isSettingsOpen && !showConnectionErrorDialog,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LoadingScreen()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InitialSetupDialog(onConfirm: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF222222)),
            modifier = Modifier.width(400.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = AppStrings.SETUP_REQUIRED_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = AppStrings.SETUP_REQUIRED_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onConfirm, colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth()) {
                    Text(AppStrings.GO_TO_SETTINGS)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectionErrorDialog(onGoToSettings: () -> Unit, onExit: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)),
            modifier = Modifier.width(420.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = AppStrings.CONNECTION_ERROR_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = AppStrings.CONNECTION_ERROR_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onExit, colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White), modifier = Modifier.weight(1f)) {
                        Text(AppStrings.EXIT_APP)
                    }
                    Button(onClick = onGoToSettings, colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.weight(1f)) {
                        Text(AppStrings.GO_TO_SETTINGS_SHORT)
                    }
                }
            }
        }
    }
}

/**
 * ★追加: 非対応OSバージョンを通知するダイアログ
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IncompatibleOsDialog(onExit: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)),
            modifier = Modifier.width(420.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "非対応のOSバージョン",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "本アプリの実行には Android 8.0 (API 26) 以上が必要です。\nお使いの端末 (API ${Build.VERSION.SDK_INT}) は現在サポートされていません。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("アプリを終了する")
                }
            }
        }
    }
}