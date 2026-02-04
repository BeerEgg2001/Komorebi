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
    onJumpMenuStateChanged: (Boolean) -> Unit
) {
    // ★追加: ジャンプ先の時間を保持する状態
    var jumpTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    val contentFocusRequester = remember { FocusRequester() }
    var isReturningFromDetail by remember { mutableStateOf(false) }
    val subTabEntryRequester = remember { FocusRequester() }

    LaunchedEffect(selectedProgram) {
        if (selectedProgram == null && isReturningFromDetail) {
            // 詳細から戻った際に番組表へフォーカスを戻す
            delay(50)
            contentRequester.requestFocus()
            isReturningFromDetail = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 番組表本体
        ModernEpgCanvasEngine(
            uiState = uiState,
            logoUrls = logoUrls,
            topTabFocusRequester = mainTabFocusRequester,
            contentFocusRequester = contentRequester,
            entryFocusRequester = subTabEntryRequester,
            onProgramSelected = { program ->
                isReturningFromDetail = true
                onProgramSelected(program)
            },
            jumpTargetTime = jumpTargetTime,
            onJumpFinished = { jumpTargetTime = null },
            onEpgJumpMenuStateChanged = onJumpMenuStateChanged
        )

        // 2. 詳細画面オーバーレイ (selectedProgram がある時のみ表示)
        if (selectedProgram != null) {
            val detailInitialFocusRequester = remember { FocusRequester() }

            LaunchedEffect(selectedProgram) {
                yield()
                detailInitialFocusRequester.requestFocus()
            }

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
                    program = selectedProgram!!,
                    onPlayClick = { /* ... */ },
                    onRecordClick = { /* ... */ },
                    onBackClick = { onProgramSelected(null) },
                    initialFocusRequester = detailInitialFocusRequester
                )
            }
        }

        // 3. 日時指定メニュー (isJumpMenuOpen が true なら、詳細画面が開いていなくても表示)
        // ここを if (selectedProgram != null) の外に出しました
        if (isJumpMenuOpen) {
            val now = OffsetDateTime.now()
            // 今日から8日分の日付リストを作成
            val dates = remember { List(8) { now.plusDays(it.toLong()) } }

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