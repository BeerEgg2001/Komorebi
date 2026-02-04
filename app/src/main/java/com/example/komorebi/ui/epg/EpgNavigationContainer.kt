package com.example.komorebi.ui.epg

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.zIndex
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.viewmodel.EpgUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.time.OffsetDateTime

@OptIn(ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgNavigationContainer(
    uiState: EpgUiState.Success,
    logoUrls: List<String>,
    mirakurunIp: String,
    mirakurunPort: String,
    mainTabFocusRequester: FocusRequester,
    contentRequester: FocusRequester,
    selectedProgram: EpgProgram?,
    onProgramSelected: (EpgProgram?) -> Unit,
    isJumpMenuOpen: Boolean,
    onJumpMenuStateChanged: (Boolean) -> Unit,
    onNavigateToPlayer: (String, String, String) -> Unit
) {
    // ジャンプ先を保持する内部状態
    var jumpTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    val detailInitialFocusRequester = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. メインの番組表（Canvasエンジン）
        // エラー修正：パラメータ名を ModernEpgCanvasEngine の定義に合わせます
        ModernEpgCanvasEngine(
            uiState = uiState,
            logoUrls = logoUrls,
            topTabFocusRequester = mainTabFocusRequester,
            contentFocusRequester = contentRequester,
            entryFocusRequester = contentRequester, // 追加：番組表に入った際の初期フォーカス先
            onProgramSelected = onProgramSelected,
            jumpTargetTime = jumpTargetTime,        // 修正：パラメータ名を一致させる
            onJumpFinished = { jumpTargetTime = null },
            onEpgJumpMenuStateChanged = onJumpMenuStateChanged // 修正：パラメータ名を一致させる
        )

        // 2. 番組詳細オーバーレイ
        if (selectedProgram != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .zIndex(10f)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Back || event.key == Key.Escape)) {
                            onProgramSelected(null)
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
            ) {
                ProgramDetailScreen(
                    program = selectedProgram,
                    onPlayClick = {
                        onNavigateToPlayer(
                            selectedProgram.channel_id,
                            mirakurunIp,
                            mirakurunPort
                        )
                    },
                    onRecordClick = { /* 録画予約ロジック(将来用) */ },
                    onBackClick = { onProgramSelected(null) },
                    initialFocusRequester = detailInitialFocusRequester
                )
            }

            LaunchedEffect(selectedProgram) {
                yield()
                detailInitialFocusRequester.requestFocus()
            }
        }

        // 3. 日時指定メニュー（EPG配信制限：1週間分 = 今日 + 6日）
        if (isJumpMenuOpen) {
            val now = OffsetDateTime.now()
            // EPGは1週間分（今日を入れて7日間）のみ
            val dates = remember { List(7) { now.plusDays(it.toLong()) } }

            EpgJumpMenu(
                dates = dates,
                onSelect = { selectedTime ->
                    jumpTargetTime = selectedTime
                    onJumpMenuStateChanged(false)
                },
                onDismiss = { onJumpMenuStateChanged(false) }
            )
        }
    }
}