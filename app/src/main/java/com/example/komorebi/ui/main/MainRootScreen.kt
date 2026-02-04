package com.example.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.home.HomeLauncherScreen
import com.example.komorebi.ui.home.LoadingScreen
import com.example.komorebi.ui.live.LivePlayerScreen
import com.example.komorebi.ui.theme.SettingsScreen
import com.example.komorebi.viewmodel.*

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootScreen(
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onExitApp: () -> Unit
) {
    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }

    // すべての状態を最上位で管理
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var selectedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var epgSelectedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var isEpgJumpMenuOpen by remember { mutableStateOf(false) }
    var triggerHomeBack by remember { mutableStateOf(false) }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "192.168.100.60")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "40772")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "https://192-168-100-60.local.konomi.tv")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "7000")

    // 統合バックボタン管理
    BackHandler(enabled = true) {
        when {
            // 安全策として残しますが、通常ここを通る前に下層（Container）で処理されます
            epgSelectedProgram != null -> {
                epgSelectedProgram = null
            }
            selectedChannel != null -> { selectedChannel = null }
            selectedProgram != null -> { selectedProgram = null }
            isEpgJumpMenuOpen -> { isEpgJumpMenuOpen = false }

            // 何も開いていない場合、初めて HomeLauncherScreen の「タブ移動・終了」ロジックへ
            else -> {
                triggerHomeBack = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HomeLauncherScreen(
            channelViewModel = channelViewModel,
            homeViewModel = homeViewModel,
            epgViewModel = epgViewModel,
            groupedChannels = groupedChannels,
            mirakurunIp = mirakurunIp,
            mirakurunPort = mirakurunPort,
            konomiIp = konomiIp,
            konomiPort = konomiPort,
            initialTabIndex = currentTabIndex,
            onTabChange = { currentTabIndex = it },
            // ここが重要：再生状態を共有する
            selectedChannel = selectedChannel,
            onChannelClick = { channel ->
                selectedChannel = channel
                if (channel != null) {
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
            onNavigateToPlayer = { channelId, ip, port ->
                // channelId から Channel オブジェクトを見つけてセットする
                // groupedChannels 内を検索して一致するチャンネルを探します
                val channel = groupedChannels.values.flatten().find { it.id == channelId }
                if (channel != null) {
                    selectedChannel = channel
                    homeViewModel.saveLastChannel(channel)
                    // 詳細画面を閉じる（再生を優先するため）
                    epgSelectedProgram = null
                }
            }
        )
    }
}