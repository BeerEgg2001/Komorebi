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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.data.model.EpgProgram
//import com.beeregg2001.komorebi.ui.video.ProgramDetailScreen
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.OffsetDateTime

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
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
    onJumpStateChanged: (Boolean) -> Unit
) {
    var jumpTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    val gridFocusRequester = remember { FocusRequester() }
    var isInternalJumping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isInternalJumping) { onJumpStateChanged(isInternalJumping) }

    // ★最重要: ジャンプメニューが閉じた際の物理フォーカス吸着ロジック
    LaunchedEffect(isJumpMenuOpen) {
        if (!isJumpMenuOpen && isInternalJumping) {
            // システムのフォーカス逃避を防ぐため、約0.7秒間しつこくリクエストし続ける
            repeat(15) {
                runCatching { gridFocusRequester.requestFocus() }
                delay(48) // リフレッシュレートに合わせた間隔
            }
            isInternalJumping = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ModernEpgCanvasEngine_Smooth(
            uiState = uiState, logoUrls = logoUrls,
            topTabFocusRequester = mainTabFocusRequester, headerFocusRequester = contentRequester, gridFocusRequester = gridFocusRequester,
            onProgramSelected = { onProgramSelected(it) }, jumpTargetTime = jumpTargetTime, onJumpFinished = { jumpTargetTime = null },
            onEpgJumpMenuStateChanged = onJumpMenuStateChanged, currentType = currentType, onTypeChanged = onTypeChanged,
            availableTypes = availableTypes, restoreChannelId = restoreChannelId
        )

        if (selectedProgram != null) {
            val detailRequester = remember { FocusRequester() }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).zIndex(2f)) {
                ProgramDetailScreen(program = selectedProgram, onPlayClick = { onNavigateToPlayer(it.channel_id, mirakurunIp, mirakurunPort) }, onRecordClick = {}, onBackClick = { runCatching { gridFocusRequester.requestFocus() }; onProgramSelected(null) }, initialFocusRequester = detailRequester)
            }
            LaunchedEffect(selectedProgram) { yield(); runCatching { detailRequester.requestFocus() } }
        }

        AnimatedVisibility(
            visible = isJumpMenuOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.zIndex(10f).focusProperties { exit = { gridFocusRequester } }
        ) {
            val now = remember { OffsetDateTime.now() }
            EpgJumpMenu(
                dates = remember(now) { List(7) { now.plusDays(it.toLong()) } },
                onSelect = { selectedTime ->
                    scope.launch {
                        isInternalJumping = true
                        jumpTargetTime = selectedTime
                        // メニューが消える前からターゲットをGridに固定
                        runCatching { gridFocusRequester.requestFocus() }
                        delay(100)
                        onJumpMenuStateChanged(false)
                    }
                },
                onDismiss = {
                    onJumpMenuStateChanged(false)
                    runCatching { contentRequester.requestFocus() }
                }
            )
        }
    }
}