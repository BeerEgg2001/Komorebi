@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgNavigationContainer(
    uiState: EpgUiState,
    logoUrls: List<String>,
    mirakurunIp: String, mirakurunPort: String,
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
    availableTypes: List<String> = emptyList(),
    onJumpStateChanged: (Boolean) -> Unit,
    reserves: List<ReserveItem> = emptyList()
) {
    var jumpTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    val gridFocusRequester = remember { FocusRequester() }
    val jumpButtonRequester = remember { FocusRequester() }

    var isInternalJumping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = KomorebiTheme.colors

    LaunchedEffect(isInternalJumping) { onJumpStateChanged(isInternalJumping) }

    var previousProgram by remember { mutableStateOf<EpgProgram?>(null) }
    LaunchedEffect(selectedProgram) {
        if (previousProgram != null && selectedProgram == null) {
            delay(50); gridFocusRequester.safeRequestFocus("EpgNav_Restore")
        }
        previousProgram = selectedProgram
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModernEpgCanvasEngine_Smooth(
            uiState = uiState, logoUrls = logoUrls,
            topTabFocusRequester = mainTabFocusRequester,
            headerFocusRequester = contentRequester,
            jumpButtonFocusRequester = jumpButtonRequester,
            gridFocusRequester = gridFocusRequester,
            onProgramSelected = { onProgramSelected(it) },
            jumpTargetTime = jumpTargetTime,
            // ★修正: ModernEpgCanvasEngine 側がアニメーション完了後にこれを呼び出す
            onJumpFinished = { jumpTargetTime = null },
            onEpgJumpMenuStateChanged = onJumpMenuStateChanged, currentType = currentType, onTypeChanged = onTypeChanged,
            availableTypes = availableTypes, restoreChannelId = restoreChannelId,
            reserves = reserves
        )

        if (selectedProgram != null) {
            val detailRequester = remember { FocusRequester() }
            Box(modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.8f)).zIndex(2f)) {
                ProgramDetailScreen(
                    program = selectedProgram,
                    onPlayClick = { onNavigateToPlayer(it.channel_id, mirakurunIp, mirakurunPort) },
                    onRecordClick = {},
                    onBackClick = {
                        gridFocusRequester.safeRequestFocus("EpgNav_UIBack")
                        onProgramSelected(null)
                    },
                    initialFocusRequester = detailRequester
                )
            }
            LaunchedEffect(selectedProgram) { yield(); detailRequester.safeRequestFocus("EpgNav_DetailOpen") }
        }

        AnimatedVisibility(
            visible = isJumpMenuOpen, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.zIndex(10f)
        ) {
            val now = remember { OffsetDateTime.now() }
            EpgJumpMenu(
                dates = remember(now) { List(7) { now.plusDays(it.toLong()) } },
                onSelect = { selectedTime ->
                    scope.launch {
                        isInternalJumping = true
                        jumpTargetTime = selectedTime
                        onJumpMenuStateChanged(false)

                        yield()
                        delay(100)

                        gridFocusRequester.safeRequestFocus("EpgNav_JumpSelect")

                        // フラグを戻すことでトップナビへのフォーカスが可能になる
                        isInternalJumping = false
                    }
                },
                onDismiss = {
                    onJumpMenuStateChanged(false)
                    jumpButtonRequester.safeRequestFocus("EpgNav_JumpDismiss")
                }
            )
        }
    }
}