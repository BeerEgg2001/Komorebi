package com.example.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.viewmodel.EpgUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.time.OffsetDateTime

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
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
    onNavigateToPlayer: (String, String, String) -> Unit,
    currentType: String,
    onTypeChanged: (String) -> Unit,
    restoreChannelId: String? = null
) {
    var jumpTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    val detailInitialFocusRequester = remember { FocusRequester() }

    // 詳細画面から戻る際のフォーカス復旧用
    var internalRestoreChannelId by remember { mutableStateOf<String?>(restoreChannelId) }
    var internalRestoreStartTime by remember { mutableStateOf<String?>(null) }

    // 外部からの restoreChannelId が変わったら内部状態も更新
    LaunchedEffect(restoreChannelId) {
        internalRestoreChannelId = restoreChannelId
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        ModernEpgCanvasEngine(
            uiState = uiState,
            logoUrls = logoUrls,
            topTabFocusRequester = mainTabFocusRequester,
            contentFocusRequester = contentRequester,
            entryFocusRequester = contentRequester,
            onProgramSelected = { prog ->
                onProgramSelected(prog)
            },
            jumpTargetTime = jumpTargetTime,
            onJumpFinished = { jumpTargetTime = null },
            onEpgJumpMenuStateChanged = onJumpMenuStateChanged,
            currentType = currentType,
            onTypeChanged = onTypeChanged,
            restoreChannelId = internalRestoreChannelId,
            restoreProgramStartTime = internalRestoreStartTime
        )

        if (selectedProgram != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .zIndex(10f)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Back || event.key == Key.Escape)) {
                            // 戻る際に場所を記録
                            internalRestoreChannelId = selectedProgram.channel_id
                            internalRestoreStartTime = selectedProgram.start_time
                            onProgramSelected(null)
                            true
                        } else false
                    }
                    .focusable()
            ) {
                ProgramDetailScreen(
                    program = selectedProgram,
                    onPlayClick = {
                        onNavigateToPlayer(selectedProgram.channel_id, mirakurunIp, mirakurunPort)
                    },
                    onRecordClick = { /* 予約 */ },
                    onBackClick = {
                        // 戻る際に場所を記録
                        internalRestoreChannelId = selectedProgram.channel_id
                        internalRestoreStartTime = selectedProgram.start_time
                        onProgramSelected(null)
                    },
                    initialFocusRequester = detailInitialFocusRequester
                )
            }
            LaunchedEffect(selectedProgram) {
                yield()
                detailInitialFocusRequester.requestFocus()
            }
        }

        // 復旧用IDがある場合、少し遅延させてから確実にコンテンツへフォーカスを固定する
        LaunchedEffect(internalRestoreChannelId) {
            if (internalRestoreChannelId != null) {
                delay(800)
                contentRequester.requestFocus()
                // 一度復旧したらクリアする（再描画時のループ防止。必要に応じてコメントアウト）
                // internalRestoreChannelId = null
                // internalRestoreStartTime = null
            }
        }

        if (isJumpMenuOpen) {
            val now = OffsetDateTime.now()
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