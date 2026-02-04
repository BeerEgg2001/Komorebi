package com.example.komorebi

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import com.example.komorebi.ui.theme.KomorebiTheme
import com.example.komorebi.ui.components.ExitDialog
import com.example.komorebi.ui.main.MainRootScreen
import com.example.komorebi.viewmodel.ChannelViewModel
import com.example.komorebi.viewmodel.EpgViewModel
import com.example.komorebi.viewmodel.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hiltが自動的にRepositoryを注入済みのViewModelを作成します
    private val channelViewModel: ChannelViewModel by viewModels()
    private val epgViewModel: EpgViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewModelのinitブロックで初期ロードが走るため、
        // ここで手動でリポジトリを叩く必要はありません。
        // channelViewModel.fetchChannels()
        // epgViewModel.preloadAllEpgData() // これらはViewModel内で実行されます

        setContent {
            KomorebiTheme {
                var showExitDialog by remember { mutableStateOf(false) }

                // アプリのメインナビゲーション
                MainRootScreen(
                    channelViewModel = channelViewModel,
                    epgViewModel = epgViewModel,
                    homeViewModel = homeViewModel,
                    onExitApp = { showExitDialog = true }
                )

                if (showExitDialog) {
                    ExitDialog(
                        onConfirm = { finish() },
                        onDismiss = { showExitDialog = false }
                    )
                }
            }
        }
    }
}