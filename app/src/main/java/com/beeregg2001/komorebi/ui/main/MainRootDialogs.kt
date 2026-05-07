@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.mapper.ReserveMapper
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.components.AiConciergePanel
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.ui.epg.ProgramDetailMode
import com.beeregg2001.komorebi.ui.epg.ProgramDetailScreen
import com.beeregg2001.komorebi.ui.reserve.EpgReserveDialog
import com.beeregg2001.komorebi.ui.reserve.ReserveSettingsDialog
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.util.AudioRecorderHelper
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootDialogs(
    state: MainRootState,
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    aiConciergeViewModel: AiConciergeViewModel,
    groupedChannels: Map<String, List<Channel>>,
    reserves: List<ReserveItem>,
    updateState: UpdateState,
    timeFormat: String,
    isSettingsInitialized: Boolean,
    hasSyncError: Boolean,
    detailFocusRequester: FocusRequester,
    apiKey: String, // ★ 追加: 取得したAPIキーを受け取る
    onExitApp: () -> Unit,
    closeSettingsAndRefresh: () -> Unit,
    closeAiConcierge: (Boolean) -> Unit,
    onGoToSettings: () -> Unit // ★ 追加: 設定画面誘導コールバック
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ========================================================================
    // AIコンシェルジュ 音声入力ロジック
    // ========================================================================
    val aiTicketManager = state.aiTicketManager
    val isSpeechSupported = remember {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        context.packageManager.resolveActivity(intent, 0) != null
    }

    val audioRecorderHelper = remember { AudioRecorderHelper(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            state.toastMessage = "マイク権限が必要です。文字入力をご利用ください。"
        }
    }

    val stopRecordingAndSend = {
        if (isRecordingVoice) {
            val file = audioRecorderHelper.stopRecording()
            isRecordingVoice = false

            if (file != null && file.exists() && file.length() > 0) {
                Log.i("AI_Concierge", "🎙️ 録音完了、Geminiに送信します (${file.length()} bytes)")
                val audioBytes = file.readBytes()

                aiConciergeViewModel.sendAudioWithContext(
                    audioBytes = audioBytes,
                    liveChannels = channelViewModel.groupedChannels.value,
                    recentRecordings = recordViewModel.recentRecordings.value,
                    groupedSeries = recordViewModel.groupedSeries.value,
                    activeReserves = reserveViewModel.reserves.value
                )
            } else {
                state.toastMessage = "音声が短すぎるか、録音できませんでした"
            }
        }
    }

    // ========================================================================
    // ダイアログ & オーバーレイ UI
    // ========================================================================

    if (hasSyncError) {
        val errorMessage = recordViewModel.syncProgress.value.error ?: "不明なエラー"
        SyncErrorDialog(
            errorMessage = errorMessage,
            onRetry = {
                recordViewModel.clearSyncError()
                recordViewModel.triggerSmartSync()
            },
            onDismiss = { recordViewModel.clearSyncError() }
        )
    }

    if (state.selectedProgramForAutoReserve != null) {
        val program = state.selectedProgramForAutoReserve!!
        val initialKeyword = TitleNormalizer.extractDisplayTitle(program.title)
        val now = OffsetDateTime.now()
        val start = runCatching { OffsetDateTime.parse(program.startTime) }.getOrDefault(now)
        val end =
            runCatching { OffsetDateTime.parse(program.endTime) }.getOrDefault(now.plusHours(1))

        EpgReserveDialog(
            initialKeyword = initialKeyword,
            initialStartTime = start,
            initialEndTime = end,
            onConfirm = { keyword, daysOfWeek, startH, startM, endH, endM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                val channelId = program.channel?.id
                val matchedChannel = groupedChannels.values.flatten().find { it.id == channelId }

                val nId = matchedChannel?.networkId?.toInt() ?: 0
                val sId = matchedChannel?.serviceId?.toInt() ?: 0
                var tsId = matchedChannel?.transportStreamId?.toInt() ?: 0

                if (tsId == 0 && nId != 0 && sId != 0) {
                    val currentEpgState = epgViewModel.uiState
                    if (currentEpgState is EpgUiState.Success) {
                        val epgChannel = currentEpgState.data.find {
                            it.channel.id == channelId || (it.channel.network_id == nId && it.channel.service_id == sId)
                        }?.channel
                        if (epgChannel != null) {
                            tsId = epgChannel.transport_stream_id
                        }
                    }
                }

                if (tsId == 0 && nId != 0 && sId != 0) {
                    val searchResults = epgViewModel.searchResults.value
                    val matchedSearch = searchResults.find {
                        it.channel.id == channelId || (it.channel.network_id == nId && it.channel.service_id == sId)
                    }
                    if (matchedSearch != null) {
                        tsId = matchedSearch.channel.transport_stream_id
                    }
                }

                if (tsId == 0 && nId in 32736..32742) {
                    tsId = nId
                }

                reserveViewModel.addEpgReserve(
                    keyword = keyword, networkId = nId, transportStreamId = tsId, serviceId = sId,
                    daysOfWeek = daysOfWeek, startHour = startH, startMinute = startM,
                    endHour = endH, endMinute = endM, excludeKeyword = exc, isTitleOnly = tOnly,
                    broadcastType = bType, isFuzzySearch = fuzzy, duplicateScope = dup,
                    priority = pri, isEventRelay = relay, isExactRecord = exact,
                    onSuccess = {
                        scope.launch {
                            state.selectedProgramForAutoReserve = null
                            delay(300)
                            state.toastMessage = "「${keyword}」の自動録画条件を登録しました"
                        }
                    }
                )
            },
            onDismiss = { state.selectedProgramForAutoReserve = null },
            timeFormat = timeFormat
        )
    }

    if (state.selectedConditionReserveItem != null) {
        val program =
            remember(state.selectedConditionReserveItem) { ReserveMapper.toEpgProgram(state.selectedConditionReserveItem!!) }
        ProgramDetailScreen(
            program = program,
            mode = ProgramDetailMode.RESERVE,
            isReserved = true,
            isReadOnly = true,
            onBackClick = { state.selectedConditionReserveItem = null },
            initialFocusRequester = detailFocusRequester,
            timeFormat = timeFormat
        )
    }

    if (state.selectedReserve != null) {
        val program =
            remember(state.selectedReserve) { ReserveMapper.toEpgProgram(state.selectedReserve!!) }
        ProgramDetailScreen(
            program = program, mode = ProgramDetailMode.RESERVE, isReserved = true,
            onBackClick = { state.selectedReserve = null },
            onDeleteReserveClick = { _ -> state.reserveToDelete = state.selectedReserve },
            onEditReserveClick = { _ ->
                reserveViewModel.refreshReserveItem(state.selectedReserve!!.id) { latest ->
                    state.editingReserveItem = latest ?: state.selectedReserve
                }
            },
            initialFocusRequester = detailFocusRequester, timeFormat = timeFormat
        )
    }

    if (state.epgSelectedProgram != null) {
        val relatedReserve = reserves.find { it.program.id == state.epgSelectedProgram!!.id }
        ProgramDetailScreen(
            program = state.epgSelectedProgram!!,
            mode = ProgramDetailMode.EPG,
            isReserved = relatedReserve != null,
            onPlayClick = {
                val channel = groupedChannels.values.flatten().find { ch -> ch.id == it.channel_id }
                if (channel != null) {
                    state.selectedChannel = channel
                    state.isBaseballMode = false
                    state.lastSelectedChannelId = channel.id
                    state.lastSelectedProgramId = null
                    homeViewModel.saveLastChannel(channel)
                    state.epgSelectedProgram = null
                    state.isReturningFromPlayer = false
                }
            },
            onRecordClick = { program ->
                reserveViewModel.addReserve(program.id) {
                    scope.launch {
                        state.epgSelectedProgram = null; delay(300)
                        state.toastMessage = AppStrings.TOAST_RESERVED
                    }
                }
            },
            onEpgReserveClick = { program, keyword, daysOfWeek, startH, startM, endH, endM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                val channel = groupedChannels.values.flatten().find { it.id == program.channel_id }
                var finalTsId = channel?.transportStreamId?.toInt() ?: 0

                if (finalTsId == 0) {
                    val currentEpgState = epgViewModel.uiState
                    if (currentEpgState is EpgUiState.Success) {
                        val epgChannel = currentEpgState.data.find {
                            it.channel.id == program.channel_id || (it.channel.network_id == program.network_id && it.channel.service_id == program.service_id)
                        }?.channel
                        if (epgChannel != null) finalTsId = epgChannel.transport_stream_id
                    }
                }

                if (finalTsId == 0) {
                    val searchResults = epgViewModel.searchResults.value
                    val matchedResult = searchResults.find {
                        it.program.id == program.id || it.channel.id == program.channel_id || (it.channel.network_id == program.network_id && it.channel.service_id == program.service_id)
                    }
                    if (matchedResult != null) finalTsId = matchedResult.channel.transport_stream_id
                }

                if (finalTsId == 0 && program.network_id in 32736..32742) {
                    finalTsId = program.network_id
                }

                reserveViewModel.addEpgReserve(
                    keyword = keyword,
                    networkId = program.network_id,
                    transportStreamId = finalTsId,
                    serviceId = program.service_id,
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
                            state.epgSelectedProgram = null; delay(300)
                            state.toastMessage = "EPG予約を登録しました"
                        }
                    }
                )
            },
            onRecordDetailClick = { program -> state.editingNewProgram = program },
            onEditReserveClick = { _ ->
                if (relatedReserve != null) reserveViewModel.refreshReserveItem(relatedReserve.id) {
                    state.editingReserveItem = it ?: relatedReserve
                }
            },
            onDeleteReserveClick = { _ ->
                if (relatedReserve != null) state.reserveToDelete = relatedReserve
            },
            onBackClick = { state.epgSelectedProgram = null },
            initialFocusRequester = detailFocusRequester,
            timeFormat = timeFormat
        )
    }

    if (state.editingReserveItem != null) {
        ReserveSettingsDialog(
            programTitle = state.editingReserveItem!!.program.title,
            initialSettings = state.editingReserveItem!!.recordSettings,
            isNewReservation = false,
            onConfirm = { newSettings ->
                reserveViewModel.updateReservation(state.editingReserveItem!!, newSettings) {
                    scope.launch {
                        state.editingReserveItem = null; state.toastMessage =
                        AppStrings.TOAST_RESERVE_UPDATED
                        delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail")
                    }
                }
            },
            onDismiss = {
                state.editingReserveItem = null
                scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") }
            }
        )
    }

    if (state.editingNewProgram != null) {
        val defaultSettings = remember {
            ReserveRecordSettings(
                isEnabled = true,
                priority = 3,
                recordingMode = "SpecifiedService",
                isEventRelayFollowEnabled = true
            )
        }
        ReserveSettingsDialog(
            programTitle = state.editingNewProgram!!.title,
            initialSettings = defaultSettings,
            isNewReservation = true,
            onConfirm = { newSettings ->
                reserveViewModel.addReserveWithSettings(state.editingNewProgram!!.id, newSettings) {
                    scope.launch {
                        state.editingNewProgram = null; state.epgSelectedProgram = null
                        delay(300); state.toastMessage = AppStrings.TOAST_RESERVED
                    }
                }
            },
            onDismiss = {
                state.editingNewProgram = null
                scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") }
            }
        )
    }

    if (state.reserveToDelete != null) {
        DeleteConfirmationDialog(
            title = AppStrings.DIALOG_DELETE_RESERVE_TITLE,
            message = String.format(
                AppStrings.DIALOG_DELETE_RESERVE_MESSAGE,
                state.reserveToDelete?.program?.title ?: ""
            ),
            onConfirm = {
                val id = state.reserveToDelete!!.id
                reserveViewModel.deleteReservation(id) {
                    scope.launch {
                        state.reserveToDelete = null
                        if (state.selectedReserve != null) state.selectedReserve = null
                        if (state.epgSelectedProgram != null) state.epgSelectedProgram = null
                        delay(300); state.toastMessage = AppStrings.TOAST_RESERVE_DELETED
                    }
                }
            },
            onCancel = { state.reserveToDelete = null }
        )
    }

    if (!isSettingsInitialized && !state.isSettingsOpen && state.isSplashFinished) {
        InitialSetupDialog(onConfirm = { state.isSettingsOpen = true })
    }

    if (state.showConnectionErrorDialog && isSettingsInitialized && !state.isSettingsOpen) {
        ConnectionErrorDialog(
            onGoToSettings = {
                state.showConnectionErrorDialog = false; state.isSettingsOpen = true
            },
            onExit = onExitApp
        )
    }

    if (state.isSettingsOpen) {
        SettingsScreen(
            onBack = closeSettingsAndRefresh,
            initialCategoryIndex = state.settingsInitialCategoryIndex,
            initialFocusItemIndex = state.settingsInitialFocusItemIndex,
            onClearLastChannel = {
                homeViewModel.clearLastChannelHistory(); state.toastMessage =
                AppStrings.TOAST_CHANNEL_HISTORY_DELETED
            },
            onClearWatchHistory = {
                recordViewModel.clearWatchHistory(); state.toastMessage =
                AppStrings.TOAST_WATCH_HISTORY_DELETED
            }
        )
    }

    if (updateState is UpdateState.UpdateAvailable) {
        val available = updateState as UpdateState.UpdateAvailable
        RobustUpdateDialog(
            versionName = available.versionName, releaseNotes = available.releaseNotes,
            onConfirm = { homeViewModel.startUpdateDownload(available.apkUrl) },
            onDismiss = { homeViewModel.dismissUpdate() }
        )
    }

    if (updateState is UpdateState.Downloading || updateState is UpdateState.ReadyToInstall) {
        Box(modifier = Modifier.fillMaxSize()) {
            UpdateProgressBanner(
                updateState = updateState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(48.dp)
            )
        }
    }

    GlobalToast(message = state.toastMessage)

    AiConciergePanel(
        isOpen = state.isAiConciergeOpen,
        chatHistory = aiConciergeViewModel.chatHistory.collectAsState().value,
        isSpeechSupported = isSpeechSupported,
        isRecording = isRecordingVoice,
        ticketManager = aiTicketManager,
        apiKey = apiKey, // ★ 追加: 取得したAPIキーを渡す
        onGoToSettings = onGoToSettings, // ★ 追加: 設定への誘導処理
        onClose = { closeAiConcierge(true) },
        onMicLongPressStart = {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                audioRecorderHelper.startRecording()
                isRecordingVoice = true
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onMicLongPressEnd = { stopRecordingAndSend() },
        onKeyboardClick = { state.showAiKeyboardInput = true }
    )

    if (state.showAiKeyboardInput) {
        AiTextInputDialog(
            onSubmit = { text ->
                state.showAiKeyboardInput = false
                scope.launch { delay(150); aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT) } // 必要に応じてパッケージ調整
                if (text.isNotBlank()) {
                    aiConciergeViewModel.sendTextWithContext(
                        userInput = text,
                        liveChannels = channelViewModel.groupedChannels.value,
                        recentRecordings = recordViewModel.recentRecordings.value,
                        groupedSeries = recordViewModel.groupedSeries.value,
                        activeReserves = reserveViewModel.reserves.value
                    )
                }
            },
            onDismiss = {
                state.showAiKeyboardInput = false
                scope.launch { delay(150); aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT) }
            }
        )
    }
}