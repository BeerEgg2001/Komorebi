package com.example.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.home.HomeLauncherScreen
import com.example.komorebi.ui.live.LivePlayerScreen
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
    var isPlayerMiniListOpen by remember { mutableStateOf(false) }

    // 視聴画面から戻った時のフォーカス復旧用
    var lastSelectedChannelId by remember { mutableStateOf<String?>(null) }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "192.168.100.60")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "40772")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "https://192-168-100-60.local.konomi.tv")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "7000")

    // 統合バックボタン管理
    BackHandler(enabled = true) {
        when {
            // 再生中の動画を閉じる
            selectedChannel != null -> {
                selectedChannel = null
                // プレイヤーから戻った時は、EPGタブなら復旧処理を走らせるため ID は維持
            }
            selectedProgram != null -> { selectedProgram = null }

            epgSelectedProgram != null -> { epgSelectedProgram = null }
            isEpgJumpMenuOpen -> { isEpgJumpMenuOpen = false }

            else -> {
                triggerHomeBack = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedChannel != null) {
            // 1. 視聴画面を最前面に表示
            LivePlayerScreen(
                channel = selectedChannel!!,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                groupedChannels = groupedChannels,
                isMiniListOpen = isPlayerMiniListOpen,
                onMiniListToggle = { isPlayerMiniListOpen = it },
                onChannelSelect = { newChannel ->
                    selectedChannel = newChannel
                    lastSelectedChannelId = newChannel.id // プレイヤー内での選局時も更新
                    homeViewModel.saveLastChannel(newChannel)
                },
                onBackPressed = {
                    selectedChannel = null
                }
            )
        } else {
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
                onNavigateToPlayer = { channelId, ip, port ->
                    val channel = groupedChannels.values.flatten().find { it.id == channelId }
                    if (channel != null) {
                        selectedChannel = channel
                        lastSelectedChannelId = channelId // 復旧用IDを保存
                        homeViewModel.saveLastChannel(channel)
                        epgSelectedProgram = null
                        isEpgJumpMenuOpen = false
                    }
                },
                // ★ 修正ポイント: 復旧用IDを渡し、内部で復旧処理が走ったらクリアするようにする
                lastPlayerChannelId = lastSelectedChannelId
            )

            // EPG画面が表示されている状態で、lastSelectedChannelId が残っている場合
            // 次回描画以降で重複して復旧が走らないよう、少し遅らせてクリアする（またはEngine側で消費する）
            // 今回は Engine 側で LaunchedEffect(restoreChannelId) を使っているので、
            // チャンネルが切り替わったタイミングなどで適宜 null に戻す運用が安全です。
            LaunchedEffect(selectedChannel, epgSelectedProgram) {
                if (selectedChannel == null && epgSelectedProgram == null) {
                    // 復帰処理が終わったとみなせるタイミングでクリア（必要に応じて）
                    // lastSelectedChannelId = null
                }
            }
        }
    }
}