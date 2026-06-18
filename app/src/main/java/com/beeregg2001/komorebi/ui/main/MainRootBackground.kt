@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.reserve.ConditionEditDialog
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootBackground(
    state: MainRootState,
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    settingsViewModel: SettingsViewModel,
    groupedChannels: Map<String, List<Channel>>,
    watchHistory: List<KonomiHistoryProgram>,
    conditions: List<ReservationCondition>,
    reserves: List<ReserveItem>,
    autoReserveKeywords: List<String>,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    timeFormat: String,
    safeTabIndex: Int,
    isSyncingInitial: Boolean,
    backgroundBrush: Brush,
    onExitApp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colors = KomorebiTheme.colors

    // ★ 修正: SMBアイテムが選択されている場合も背面を非表示（ホームレイヤー維持）にする
    val showHomeLayer =
        (state.selectedChannel == null && state.selectedProgram == null && state.selectedSmbItem == null) || state.isMiniPlayerMode

    if (showHomeLayer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
        ) {
            when {
                state.isRecordListOpen -> {
                    RecordListScreen(
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        customTitle = state.openedSeriesTitle,
                        onProgramClick = { program, forcedPosition ->
                            val isAnalyzed = program.recordedVideo.hasKeyFrames?: true
                            if (!isAnalyzed) return@RecordListScreen
                            val duration = program.recordedVideo.duration
                            val history =
                                watchHistory.find { it.program.id.toString() == program.id.toString() }

                            val resumePos = when {
                                forcedPosition != null -> forcedPosition
                                program.playbackPosition > 5.0 && (duration <= 0 || program.playbackPosition < (duration - 10)) -> program.playbackPosition
                                history != null && history.playback_position > 5.0 && (duration <= 0 || history.playback_position < (duration - 10)) -> history.playback_position
                                else -> 0.0
                            }
                            state.initialPlaybackPositionMs = (resumePos * 1000).toLong()
                            state.selectedProgram = program
                            state.lastSelectedProgramId = program.id.toString()

                            state.lastPlayedRecordingId = program.id
                            state.showPlayerControls = true
                            state.isReturningFromPlayer = false
                            state.isMiniPlayerMode = false
                        },
                        onBack = {
                            state.isRecordListOpen = false
                            if (state.openedSeriesTitle != null) {
                                state.isSeriesListOpen = true; state.openedSeriesTitle = null
                            }
                            recordViewModel.searchRecordings("")
                        },
                        isReturningFromPlayer = state.isReturningFromPlayer,
                        lastPlayedProgramId = state.lastPlayedRecordingId,
                        onReturnFocusConsumed = { state.isReturningFromPlayer = false },
                        timeFormat = timeFormat,
                        autoReserveKeywords = autoReserveKeywords,
                        onAutoReserveClick = { program ->
                            state.selectedProgramForAutoReserve = program
                        },
                        aiFocusReturnTick = state.aiFocusReturnTick,
                        onAiReturnConsumed = { state.aiFocusReturnTick = 0 }
                    )
                }

                state.isSmbLibraryOpen -> {
                    com.beeregg2001.komorebi.ui.video.smb.SmbLibraryScreen(
                        onBack = { state.isSmbLibraryOpen = false },
                        onFileClick = { item ->
                            // ★ 修正: SMBファイルをクリックしたら、プレイヤーを起動する
                            state.selectedSmbItem = item
                            state.showPlayerControls = true
                            state.isReturningFromPlayer = false
                            state.isMiniPlayerMode = false
                            state.initialPlaybackPositionMs = 0L

                            // ★ 追加: 再生したファイルのパスを保存（戻ってきた時のフォーカス復帰用）
                            state.lastPlayedSmbPath = item.path
                        },
                        // ★ 追加: 再生画面から戻ってきた際のフォーカス復帰用パラメータ
                        isReturningFromPlayer = state.isReturningFromPlayer,
                        lastPlayedPath = state.lastPlayedSmbPath,
                        onReturnFocusConsumed = { state.isReturningFromPlayer = false }
                    )
                }

                state.editingCondition != null -> {
                    val currentCondition = conditions.find { it.id == state.editingCondition!!.id }
                        ?: state.editingCondition!!
                    val relatedReserves =
                        reserves.filter { it.comment.contains(currentCondition.programSearchCondition.keyword) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.background)
                            .background(backgroundBrush)
                    )
                    ConditionEditDialog(
                        condition = currentCondition,
                        relatedReserves = relatedReserves,
                        onConfirmUpdate = { isEnabled, keyword, daysOfWeek, startH, startM, endH, endM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                            reserveViewModel.updateEpgReserve(
                                originalCondition = currentCondition,
                                isEnabled = isEnabled,
                                keyword = keyword,
                                daysOfWeek = daysOfWeek,
                                startHour = startH,
                                startMinute = startM,
                                endHour = endH,
                                endMinute = endM,
                                excludeKeyword = exc,
                                isTitleOnly = tOnly,
                                broadcastType = bType,
                                isFuzzySearch = fuzzy,
                                duplicateScope = dup,
                                priority = pri,
                                isEventRelay = relay,
                                isExactRecord = exact,
                                onSuccess = {
                                    scope.launch {
                                        state.editingCondition = null
                                        delay(300)
                                        state.toastMessage = "予約条件を更新しました"
                                    }
                                }
                            )
                        },
                        onConfirmDelete = { deleteRelated ->
                            reserveViewModel.deleteConditionWithCleanup(
                                condition = currentCondition, deleteRelatedReserves = deleteRelated,
                                onSuccess = {
                                    scope.launch {
                                        state.editingCondition = null
                                        delay(300)
                                        state.toastMessage =
                                            if (deleteRelated) "条件と関連する予約をすべて削除しました" else "予約条件を削除しました"
                                    }
                                }
                            )
                        },
                        onDismiss = { state.editingCondition = null },
                        onReserveItemClick = { state.selectedConditionReserveItem = it },
                        timeFormat = timeFormat
                    )
                }

                else -> {
                    HomeLauncherScreen(
                        channelViewModel = channelViewModel,
                        homeViewModel = homeViewModel,
                        epgViewModel = epgViewModel,
                        recordViewModel = recordViewModel,
                        reserveViewModel = reserveViewModel,
                        groupedChannels = groupedChannels,
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        initialTabIndex = safeTabIndex,
                        onTabChange = { state.currentTabIndex = it },
                        selectedChannel = state.selectedChannel,
                        onChannelClick = { channel, isBaseballMode ->
                            state.selectedChannel = channel
                            state.isBaseballMode = isBaseballMode
                            if (channel != null) {
                                state.lastSelectedChannelId = channel.id
                                state.lastSelectedProgramId = null
                                homeViewModel.saveLastChannel(channel)
                                state.isReturningFromPlayer = false
                                state.isMiniPlayerMode = false
                            }
                        },
                        selectedProgram = state.selectedProgram,
                        onProgramSelected = { program ->
                            if (program != null) {
                                val isAnalyzed = program.recordedVideo.hasKeyFrames?: true
                                if (!isAnalyzed) return@HomeLauncherScreen
                                val history =
                                    watchHistory.find { it.program.id.toString() == program.id.toString() }
                                val duration = program.recordedVideo.duration
                                state.initialPlaybackPositionMs =
                                    if (history != null && history.playback_position > 5.0 && (duration <= 0.0 || history.playback_position < (duration - 10.0))) {
                                        (history.playback_position * 1000).toLong()
                                    } else 0L
                                state.selectedProgram = program
                                state.lastSelectedProgramId = program.id.toString()
                                state.lastSelectedChannelId = null
                                state.showPlayerControls = true
                                state.isReturningFromPlayer = false
                                state.isMiniPlayerMode = false
                            }
                        },
                        onReserveSelected = { reserveItem -> state.selectedReserve = reserveItem },
                        onConditionClick = { condition -> state.editingCondition = condition },
                        isReserveOverlayOpen = state.selectedReserve != null || state.editingCondition != null,
                        epgSelectedProgram = state.epgSelectedProgram,
                        onEpgProgramSelected = { state.epgSelectedProgram = it },
                        isEpgJumpMenuOpen = state.isEpgJumpMenuOpen,
                        onEpgJumpMenuStateChanged = { state.isEpgJumpMenuOpen = it },
                        triggerBack = state.triggerHomeBack,
                        onBackTriggered = { state.triggerHomeBack = false },
                        onFinalBack = onExitApp,
                        onUiReady = { state.isUiReady = true },
                        onNavigateToPlayer = { channelId, _, _ ->
                            val channel =
                                groupedChannels.values.flatten().find { ch -> ch.id == channelId }
                            if (channel != null) {
                                state.selectedChannel = channel
                                state.isBaseballMode = false
                                state.lastSelectedChannelId = channelId
                                state.lastSelectedProgramId = null
                                homeViewModel.saveLastChannel(channel)
                                state.epgSelectedProgram = null; state.isEpgJumpMenuOpen = false
                                state.isReturningFromPlayer = false
                                state.isMiniPlayerMode = false
                            }
                        },
                        lastPlayerChannelId = state.lastSelectedChannelId,
                        lastPlayerProgramId = state.lastSelectedProgramId,
                        isSettingsOpen = state.isSettingsOpen,
                        onSettingsToggle = { state.isSettingsOpen = it },
                        isRecordListOpen = state.isRecordListOpen,
                        onShowAllRecordings = { state.isRecordListOpen = true },
                        onCloseRecordList = { state.isRecordListOpen = false },
                        onShowSeriesList = { state.isSeriesListOpen = true },
                        onShowSmbLibrary = { state.isSmbLibraryOpen = true },
                        isReturningFromPlayer = state.isReturningFromPlayer,
                        onReturnFocusConsumed = { state.isReturningFromPlayer = false },
                        isUiReadyFlag = state.isUiReady,
                        settingsViewModel = settingsViewModel,
                        timeFormat = timeFormat,
                        hasActivePlayer = state.isMiniPlayerMode,
                        onReturnToPlayerClick = { state.isMiniPlayerMode = false },
                        aiFocusReturnTick = state.aiFocusReturnTick,
                        onAiReturnConsumed = { state.aiFocusReturnTick = 0 }
                    )
                }
            }

            if (state.selectedChannel == null && state.selectedProgram == null && !isSyncingInitial) {
                SyncProgressIndicator(
                    recordViewModel = recordViewModel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 40.dp, bottom = 40.dp)
                )
            }
        }
    }
}