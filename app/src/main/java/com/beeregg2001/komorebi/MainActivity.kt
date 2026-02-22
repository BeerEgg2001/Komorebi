package com.beeregg2001.komorebi

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.main.MainRootScreen
import com.beeregg2001.komorebi.ui.main.IncompatibleOsDialog
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hiltが自動的にRepositoryを注入済みのViewModelを作成します
    // これらはlazyプロパティであり、アクセスされる（MainRootScreenに渡される）までインスタンス化されません。
    private val channelViewModel: ChannelViewModel by viewModels()
    private val epgViewModel: EpgViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()
    private val recordViewModel: RecordViewModel by viewModels()


    @UnstableApi
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Komorebi)
        super.onCreate(savedInstanceState)

        // OS互換性のチェック (Android 8.0 API 26 以上が必要)
        val isOsCompatible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        setContent {
            KomorebiTheme {
                if (!isOsCompatible) {
                    // 非対応OSの場合、クラッシュを避けるためにメイン画面は呼ばず、警告ダイアログのみ表示
                    IncompatibleOsDialog(
                        onExit = { finish() }
                    )
                } else {
                    // 対応OSの場合のみ、通常のロジックを実行
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
}