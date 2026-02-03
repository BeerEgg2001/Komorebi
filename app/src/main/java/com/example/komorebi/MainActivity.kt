package com.example.komorebi

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.*
import com.example.komorebi.ui.theme.KomorebiTheme
import com.example.komorebi.ui.theme.SettingsScreen
import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.ui.components.ExitDialog
import com.example.komorebi.ui.home.HomeLauncherScreen
import com.example.komorebi.ui.home.LoadingScreen
import com.example.komorebi.ui.live.LivePlayerScreen
import com.example.komorebi.viewmodel.Channel
import com.example.komorebi.viewmodel.ChannelViewModel
import com.example.komorebi.viewmodel.EpgViewModel
import com.example.komorebi.viewmodel.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ViewModelの定義（重複を削除し整理）
    private val channelViewModel: ChannelViewModel by viewModels()
    private val epgViewModel: EpgViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    @RequiresApi(Build.VERSION_CODES.O)
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初期データの取得開始
        channelViewModel.fetchChannels()
        epgViewModel.preloadAllEpgData()
        channelViewModel.fetchRecentRecordings()

        setContent {
            KomorebiTheme {
                val context = LocalContext.current
                val activity = context as? Activity

                // --- データ状態の監視 ---
                val isChannelLoading by channelViewModel.isLoading.collectAsState()
                val isEpgLoading by epgViewModel.isPreloading.collectAsState()
                val groupedChannels by channelViewModel.groupedChannels.collectAsState()

                // --- アプリ画面の状態 ---
                var selectedChannel by remember { mutableStateOf<Channel?>(null) }
                var isSettingsMode by remember { mutableStateOf(false) }
                var isMiniListOpen by remember { mutableStateOf(false) }
                var showExitDialog by remember { mutableStateOf(false) }

                // タブ位置の保持
                var currentTabIndex by remember { mutableIntStateOf(0) }

                // 設定リポジトリの準備
                val repository = remember { SettingsRepository(context) }
                val mirakurunIp by repository.mirakurunIp.collectAsState(initial = "192.168.100.60")
                val mirakurunPort by repository.mirakurunPort.collectAsState(initial = "40772")
                val konomiIp by repository.konomiIp.collectAsState(initial = "https://192-168-100-60.local.konomi.tv")
                val konomiPort by repository.konomiPort.collectAsState(initial = "7000")

                // データ準備が整うまで全画面Loading
                val isInitialLoading = isChannelLoading || isEpgLoading

                // バックボタン制御
                BackHandler(enabled = true) {
                    when {
                        selectedChannel != null -> { selectedChannel = null }
                        isSettingsMode -> { isSettingsMode = false }
                        else -> { showExitDialog = true }
                    }
                }

                if (isInitialLoading) {
                    LoadingScreen()
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            // 1. ライブ視聴画面
                            selectedChannel != null -> {
                                key(selectedChannel!!.id) {
                                    LivePlayerScreen(
                                        channel = selectedChannel!!,
                                        groupedChannels = groupedChannels,
                                        mirakurunIp = mirakurunIp,
                                        mirakurunPort = mirakurunPort,
                                        isMiniListOpen = isMiniListOpen,
                                        onMiniListToggle = { isMiniListOpen = it },
                                        onChannelSelect = { selectedChannel = it },
                                        onBackPressed = { selectedChannel = null }
                                    )
                                }
                            }
                            // 2. 設定画面
                            isSettingsMode -> {
                                SettingsScreen(onBack = { isSettingsMode = false })
                            }
                            // 3. メインランチャー（ホーム、番組表、ビデオ等）
                            else -> {
                                HomeLauncherScreen(
                                    channelViewModel = channelViewModel,
                                    homeViewModel = homeViewModel,
                                    epgViewModel = epgViewModel,
                                    groupedChannels = groupedChannels,
                                    lastWatchedChannel = null, // 必要に応じて状態管理に追加してください
                                    mirakurunIp = mirakurunIp,
                                    mirakurunPort = mirakurunPort,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    initialTabIndex = currentTabIndex,
                                    onTabChange = { currentTabIndex = it },
                                    onChannelClick = { channel ->
                                        selectedChannel = channel
                                        homeViewModel.saveLastChannel(channel)
                                    },
                                    onUiReady = {
                                        // 内部でロード完了を待機するため、ここでは特別な処理は不要
                                    }
                                )
                            }
                        }
                    }
                }

                // 終了確認ダイアログ
                if (showExitDialog) {
                    ExitDialog(
                        onConfirm = { activity?.finish() },
                        onDismiss = { showExitDialog = false }
                    )
                }
            }
        }
    }
}