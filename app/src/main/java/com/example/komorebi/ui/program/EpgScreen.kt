package com.example.komorebi.ui.program

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.example.komorebi.data.model.*
import com.example.komorebi.viewmodel.*
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgScreen(
    viewModel: EpgViewModel,
    topTabFocusRequester: FocusRequester,
    tabFocusRequester: FocusRequester,
    onBroadcastingTabFocusChanged: (Boolean) -> Unit = {},
    firstCellFocusRequester: FocusRequester,
    isJumpMenuOpen: Boolean,
    onJumpMenuStateChanged: (Boolean) -> Unit,
    selectedProgram: EpgProgram?,
    onProgramSelected: (EpgProgram?) -> Unit,
    onChannelSelected: (String) -> Unit,
) {
    val uiState = viewModel.uiState
    val configuration = LocalConfiguration.current
    val isLoading = uiState is EpgUiState.Loading

    // ★ 修正1: コンテナ自体のフォーカス制御
    val containerFocusRequester = remember { FocusRequester() }
    var isGridReady by remember { mutableStateOf(false) }

    val selectedBroadcastingType by viewModel.selectedBroadcastingType.collectAsState()
    var lastFocusedProgramId by remember { mutableStateOf<String?>(null) }
    val baseTime by viewModel.baseTime.collectAsState()
    var isFirstLoad by remember { mutableStateOf(true) }

    val epgConfig = remember(configuration.screenWidthDp) {
        val timeColumnWidth = 55.dp
        val visibleChannelCount = 7
        EpgConfig(
            dpPerMinute = 1.3f,
            timeColumnWidth = timeColumnWidth,
            headerHeight = 48.dp,
            channelWidth = (configuration.screenWidthDp.dp - timeColumnWidth) / visibleChannelCount
        )
    }

    val displayChannels by remember(selectedBroadcastingType, uiState) {
        derivedStateOf {
            if (uiState is EpgUiState.Success) {
                uiState.data.filter { it.channel.type == selectedBroadcastingType }
            } else emptyList()
        }
    }

    // ★ 修正2: Loadingが始まった瞬間に、フォーカスを安全なコンテナへ避難させる
    LaunchedEffect(isLoading) {
        if (isLoading) {
            isGridReady = false
            try {
                containerFocusRequester.requestFocus()
            } catch (e: Exception) {
                Log.e("EpgScreen", "Focus detour failed")
            }
        }
    }

    CompositionLocalProvider(LocalEpgConfig provides epgConfig) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // ★ 修正3: コンテナ自体をフォーカス可能にし、Grid消滅時の受け皿にする
                .focusRequester(containerFocusRequester)
                .focusGroup()
                .focusable()
                .onKeyEvent {
                    // Loading中やGrid未準備の間は、内部へのフォーカス探索（キー入力）を完全に遮断
                    isLoading || !isGridReady
                }
        ) {
            Column(Modifier.fillMaxSize()) {
                BroadcastingTypeTabs(
                    selectedType = selectedBroadcastingType,
                    onTypeSelected = { viewModel.updateBroadcastingType(it) },
                    tabFocusRequester = tabFocusRequester,
                    onFocusChanged = onBroadcastingTabFocusChanged,
                    firstCellFocusRequester = firstCellFocusRequester,
                    categories = if (uiState is EpgUiState.Success) {
                        val order = listOf("GR", "BS", "CS", "BS4K", "SKY")
                        val availableTypes = uiState.data.map { it.channel.type }.distinct()
                        order.filter { it in availableTypes } + (availableTypes - order.toSet())
                    } else listOf("GR"),
                    onJumpToDateClick = { onJumpMenuStateChanged(true) }
                )

                Box(Modifier.fillMaxSize()) {
                    // ★ 修正4: Success かつ データがある時のみ Grid を接続
                    if (uiState is EpgUiState.Success && displayChannels.isNotEmpty()) {
                        EpgGrid(
                            channels = displayChannels,
                            baseTime = baseTime,
                            viewModel = viewModel,
                            onProgramClick = { onProgramSelected(it) },
                            firstCellFocusRequester = firstCellFocusRequester,
                            tabFocusRequester = tabFocusRequester,
                            skipAnimation = isFirstLoad,
                            lastFocusedId = lastFocusedProgramId,
                            onFocusProgram = { id -> lastFocusedProgramId = id }
                        )

                        // 描画が完了したことを確認してからフラグを立てる
                        SideEffect {
                            isGridReady = true
                            isFirstLoad = false
                        }
                    }

                    // Loading表示（Gridを消さずに上に重ねるのではなく、今回は安全のため排他表示）
                    if (isLoading) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(color = Color.Cyan)
                        }
                    }

                    if (uiState is EpgUiState.Error) {
                        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                            androidx.compose.material3.Text("Error: ${uiState.message}", color = Color.Red)
                        }
                    }
                }
            }

            if (selectedProgram != null) {
                ProgramDetailModal(
                    program = selectedProgram,
                    onPrimaryAction = onChannelSelected,
                    onDismiss = { onProgramSelected(null) }
                )
            }

            if (isJumpMenuOpen) {
                val jumpDates = remember { (0..7).map { OffsetDateTime.now().plusDays(it.toLong()) } }
                val jumpTimeSlots = remember { listOf(5, 8, 11, 14, 17, 20, 23, 2) }
                EpgJumpMenu(
                    dates = jumpDates,
                    timeSlots = jumpTimeSlots,
                    onSelect = { selectedTime ->
                        // 日時ジャンプ直前にフラグを落として入力を切る
                        isGridReady = false
                        viewModel.updateBaseTime(selectedTime)
                        onJumpMenuStateChanged(false)
                    },
                    onDismiss = { onJumpMenuStateChanged(false) }
                )
            }
        }
    }
}