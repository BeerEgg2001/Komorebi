package com.beeregg2001.komorebi

import android.os.Bundle
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.LocalUiScale
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.main.MainRootScreen
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hiltが自動的にRepositoryを注入済みのViewModelを作成します
    // これらはlazyプロパティであり、アクセスされる（MainRootScreenに渡される）までインスタンス化されません。
    private val channelViewModel: ChannelViewModel by viewModels()
    private val epgViewModel: EpgViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()
    private val recordViewModel: RecordViewModel by viewModels()

    // 「UI の大きさ」設定を density へ反映するために参照する。
    @Inject
    lateinit var settingsRepository: SettingsRepository


    @UnstableApi
    // java.time は desugar により API 24 から利用可能にしている。
    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Komorebi)
        super.onCreate(savedInstanceState)
        ColdStartDiag.mark("MainActivity.onCreate")

        setContent {
            LaunchedEffect(Unit) { ColdStartDiag.mark("setContent (first composition)") }

            // 「UI の大きさ」設定を density の上書きとして適用する。
            //
            // Android TV は 1080p / 4K のどちらでも OS が同じ dp 幅(多くの機種で 960dp)として
            // 扱うため、画面の dp 幅を見て表示項目数を変えるだけでは情報量が増えない。
            // density を下げると論理 dp 空間が広がり、同じ dp 指定の UI が相対的に小さくなるので、
            // 文字サイズと余白を含めた全体が縮み、画面に入る情報量が増える。
            // オーバースキャンを考慮した既存の余白も同時に詰まるため、オーバースキャンしない
            // テレビでは表示領域を有効に使えるようになる。
            //
            // 既定値は "1.0"(等倍)なので、設定を変えていない利用者の見た目は変わらない。
            // DataStore の読み込みは非同期のため、起動直後の一瞬だけ等倍で描画されてから
            // 設定値が反映される。等倍以外を選んでいる場合にレイアウトが一度切り替わるが、
            // 同期読み込みは起動のクリティカルパスをふさぐため避けている。
            val uiScale by settingsRepository.uiScale.collectAsState(initial = "1.0")
            val scale = uiScale.toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: 1.0f
            val baseDensity = LocalDensity.current

            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * scale,
                    fontScale = baseDensity.fontScale
                ),
                // ダイアログの内側では LocalDensity の上書きが引き継がれないため、
                // 倍率そのものも渡して ProvideUiScale で再適用できるようにする。
                LocalUiScale provides scale
            ) {
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
}
