package com.beeregg2001.komorebi.ui.main

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.video.VideoPlayerScreen
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay

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
    var isPlayerMiniListOpen by remember { mutableStateOf(false) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }

    // 追加: 録画一覧画面の状態管理
    var isRecordListOpen by remember { mutableStateOf(false) }

    var lastSelectedChannelId by remember { mutableStateOf<String?>(null) }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val epgUiState = epgViewModel.uiState
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()

    var isDataReady by remember { mutableStateOf(false) }
    var isSplashFinished by remember { mutableStateOf(false) }

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "192.168.100.60")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "40772")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "https://192-168-100-60.local.konomi.tv")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "7000")

    val context = LocalContext.current
    val splashDelay = remember {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

        when {
            totalMemGb < 3.5 -> 4000L
            totalMemGb < 6.5 -> 2500L
            else -> 1500L
        }
    }

    LaunchedEffect(groupedChannels) {
        if (groupedChannels.isNotEmpty()) {
            isDataReady = true
        }
    }

    LaunchedEffect(Unit) {
        delay(splashDelay)
        isSplashFinished = true
    }

    val isAppReady = isDataReady && isSplashFinished

    BackHandler(enabled = true) {
        when {
            selectedChannel != null -> selectedChannel = null
            selectedProgram != null -> selectedProgram = null
            isSettingsOpen -> isSettingsOpen = false
            epgSelectedProgram != null -> epgSelectedProgram = null
            isEpgJumpMenuOpen -> isEpgJumpMenuOpen = false
            // 録画一覧が開いている場合は閉じる
            isRecordListOpen -> isRecordListOpen = false
            else -> triggerHomeBack = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isAppReady,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
                        onChannelSelect = { newChannel ->
                            selectedChannel = newChannel
                            lastSelectedChannelId = newChannel.id
                            homeViewModel.saveLastChannel(newChannel)
                        },
                        onBackPressed = {
                            selectedChannel = null
                        }
                    )
                } else if (selectedProgram != null) {
                    VideoPlayerScreen(
                        program = selectedProgram!!,
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        onBackPressed = { selectedProgram = null }
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
                                homeViewModel.saveLastChannel(channel)
                            }
                        },
                        selectedProgram = selectedProgram,
                        onProgramSelected = { selectedProgram = it },
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
                                homeViewModel.saveLastChannel(channel)
                                epgSelectedProgram = null
                                isEpgJumpMenuOpen = false
                            }
                        },
                        lastPlayerChannelId = lastSelectedChannelId,
                        isSettingsOpen = isSettingsOpen,
                        onSettingsToggle = { isSettingsOpen = it },
                        // コールバック接続
                        onShowAllRecordings = { isRecordListOpen = true }
                    )

                    LaunchedEffect(selectedChannel, epgSelectedProgram) {
                        if (selectedChannel == null && epgSelectedProgram == null) {
                            // 復帰処理後のクリーンアップなど
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isAppReady,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LoadingScreen()
        }
    }
}