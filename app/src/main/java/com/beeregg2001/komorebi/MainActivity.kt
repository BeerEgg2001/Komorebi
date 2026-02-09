package com.beeregg2001.komorebi

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.main.MainRootScreen
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hiltが自動的にRepositoryを注入済みのViewModelを作成します
    private val channelViewModel: ChannelViewModel by viewModels()
    private val epgViewModel: EpgViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()
    private val recordViewModel: RecordViewModel by viewModels()


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
                    recordViewModel = recordViewModel,
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