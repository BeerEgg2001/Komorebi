package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.time.OffsetDateTime

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgNavigationContainer(
    uiState: EpgUiState,
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
    restoreChannelId: String? = null,
    // ★追加: 存在するチャンネルタイプのリスト
    availableTypes: List<String> = emptyList()
) {
    var jumpTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    var internalRestoreChannelId by remember { mutableStateOf(restoreChannelId) }
    var internalRestoreStartTime by remember { mutableStateOf<String?>(null) }

    val currentLogoUrls = remember(logoUrls) {
        if (logoUrls.isNotEmpty()) logoUrls else emptyList()
    }
    val displayLogoUrls = if (logoUrls.isNotEmpty()) logoUrls else currentLogoUrls

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Menu -> {
                            onJumpMenuStateChanged(!isJumpMenuOpen)
                            return@onKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
        ModernEpgCanvasEngine_Smooth(
            uiState = uiState,
            logoUrls = displayLogoUrls,
            topTabFocusRequester = mainTabFocusRequester,
            contentFocusRequester = contentRequester,
            onProgramSelected = onProgramSelected,
            jumpTargetTime = jumpTargetTime,
            onJumpFinished = { jumpTargetTime = null },
            onEpgJumpMenuStateChanged = onJumpMenuStateChanged,
            currentType = currentType,
            onTypeChanged = onTypeChanged,
            restoreChannelId = internalRestoreChannelId,
            restoreProgramStartTime = internalRestoreStartTime,
            availableTypes = availableTypes // ★追加: エンジンに渡す
        )

        if (selectedProgram != null) {
            val detailInitialFocusRequester = remember { FocusRequester() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .zIndex(2f)
            ) {
                ProgramDetailScreen(
                    program = selectedProgram,
                    onPlayClick = { program ->
                        onNavigateToPlayer(program.channel_id, mirakurunIp, mirakurunPort)
                    },
                    onRecordClick = { /* 予約 */ },
                    onBackClick = {
                        contentRequester.requestFocus()
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

        LaunchedEffect(internalRestoreChannelId) {
            if (internalRestoreChannelId != null) {
                delay(100)
                contentRequester.requestFocus()
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