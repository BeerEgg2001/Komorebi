@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.mapper.ReserveMapper
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.components.AiConciergePanel
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.epg.ProgramDetailMode
import com.beeregg2001.komorebi.ui.epg.ProgramDetailScreen
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.ui.video.player.VideoPlayerScreen
import com.beeregg2001.komorebi.ui.reserve.ReserveSettingsDialog
import com.beeregg2001.komorebi.ui.reserve.ConditionEditDialog
import com.beeregg2001.komorebi.ui.reserve.EpgReserveDialog
import com.beeregg2001.komorebi.util.AudioRecorderHelper
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.beeregg2001.komorebi.util.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.OffsetDateTime

private const val TAG = "MainRootScreen"

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootScreen(
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel = hiltViewModel(),
    aiConciergeViewModel: AiConciergeViewModel = hiltViewModel(),
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMainRootState()

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

    val timeFormat by settingsViewModel.timeFormat.collectAsState()

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

    val closeAiConcierge = { restoreFocus: Boolean ->
        state.isAiConciergeOpen = false
        aiConciergeViewModel.resetState()

        if (restoreFocus) {
            if (state.currentTabIndex == 3) {
                epgViewModel.triggerRestore()
            } else {
                state.aiFocusReturnTick++
            }
        }
        epgViewModel.clearSearch()
    }

    // ★ 追加: HomeViewModel から現在のバックエンドタイプを監視
    val backendType by homeViewModel.backendType.collectAsState()

    // バックエンドタイプが変更されたら、MainRootStateにも反映する
    LaunchedEffect(backendType) {
        state.backendType = backendType
    }

    // ★ 修正: バックエンドに応じた動的なタブリストの取得
    val baseTabs = state.getVisibleTabs()
    val favoriteBaseballTeams by homeViewModel.favoriteBaseballTeams.collectAsState()
    val tabs = remember(favoriteBaseballTeams, baseTabs) {
        if (favoriteBaseballTeams.isNotEmpty() && !baseTabs.contains("プロ野球")) {
            baseTabs + "プロ野球"
        } else {
            baseTabs
        }
    }

    // タブの数よりも大きなインデックスが選択されていた場合の安全装置
    val safeTabIndex = state.currentTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    LaunchedEffect(tabs.size) {
        if (state.currentTabIndex >= tabs.size) {
            state.currentTabIndex = 0
        }
    }

    LaunchedEffect(Unit) {
        aiConciergeViewModel.pendingAction.collect { action ->
            when (action) {
                is AiConciergeAction.PlayLive -> {
                    closeAiConcierge(false)
                    val target = channelViewModel.groupedChannels.value.values.flatten()
                        .find { it.id == action.channelId }
                    if (target != null) {
                        state.selectedProgram = null
                        state.isPlayerMiniListOpen = false
                        state.playerIsSubMenuOpen = false
                        state.isPlayerSubMenuOpen = false
                        state.isPlayerSceneSearchOpen = false
                        state.isMiniPlayerMode = false

                        state.selectedChannel = target
                        state.lastSelectedChannelId = target.id
                        homeViewModel.saveLastChannel(target)
                        state.isReturningFromPlayer = false
                    }
                }

                is AiConciergeAction.PlayRecorded -> {
                    closeAiConcierge(false)
                    val target =
                        recordViewModel.recentRecordings.value.find { it.id == action.videoId }
                    if (target != null) {
                        state.selectedChannel = null
                        state.isPlayerMiniListOpen = false
                        state.playerIsSubMenuOpen = false
                        state.isPlayerSubMenuOpen = false
                        state.isPlayerSceneSearchOpen = false
                        state.isMiniPlayerMode = false

                        state.initialPlaybackPositionMs = 0L
                        state.selectedProgram = target
                        state.lastSelectedProgramId = target.id.toString()
                        state.showPlayerControls = true
                        state.isReturningFromPlayer = false
                    }
                }

                is AiConciergeAction.SearchEpg -> {
                    closeAiConcierge(false)

                    val isOnlyDate =
                        action.keyword.isBlank() && action.genre.isBlank() && action.date.isNotBlank() && !action.isLiveOnly && action.channelName.isBlank()

                    if (isOnlyDate) {
                        try {
                            val parsedDate =
                                java.time.LocalDate.parse(action.date.replace("/", "-"))
                            val jumpTime = java.time.OffsetDateTime.now()
                                .withYear(parsedDate.year)
                                .withMonth(parsedDate.monthValue)
                                .withDayOfMonth(parsedDate.dayOfMonth)
                                .withHour(4).withMinute(0).withSecond(0).withNano(0)

                            epgViewModel.updateTargetTime(jumpTime)
                            // ★ 修正: タブが存在する場合のみ移動
                            val tabIndex = tabs.indexOf("番組表")
                            if (tabIndex != -1) state.currentTabIndex = tabIndex
                            state.toastMessage = "${action.date} の番組表に移動しました"
                        } catch (e: Exception) {
                            state.toastMessage = "日付の指定が正しくありません"
                        }
                    } else {
                        epgViewModel.executeSearch(
                            action.keyword,
                            action.genre,
                            action.date,
                            action.isLiveOnly,
                            action.channelName
                        )
                        // ★ 修正: タブが存在する場合のみ移動
                        val tabIndex = tabs.indexOf("番組表")
                        if (tabIndex != -1) state.currentTabIndex = tabIndex
                        state.toastMessage =
                            if (action.keyword.isNotBlank()) "「${action.keyword}」の検索結果を表示します" else "検索結果を表示します"
                    }
                }

                is AiConciergeAction.SearchRecord -> {
                    closeAiConcierge(false)

                    // ★ 修正: タブが存在する場合のみ移動
                    val tabIndex = tabs.indexOf("ビデオ")
                    if (tabIndex != -1) {
                        state.currentTabIndex = tabIndex
                        state.isRecordListOpen = true

                        if (action.keyword.isNotBlank()) {
                            val firstKeyword = action.keyword.split(",").firstOrNull()?.trim() ?: ""
                            recordViewModel.searchRecordings(firstKeyword)
                        }
                        if (action.genre.isNotBlank()) {
                            recordViewModel.updateGenre(action.genre)
                        }

                        state.toastMessage =
                            if (action.keyword.isNotBlank()) "「${
                                action.keyword.split(",").firstOrNull()
                            }」の録画を検索します" else "録画リストを表示します"
                    } else {
                        state.toastMessage =
                            "現在設定されているシステムでは録画検索は利用できません"
                    }
                }

                is AiConciergeAction.ReqEpgSearch -> {
                    scope.launch {
                        val results = epgViewModel.searchSilently(
                            action.keyword,
                            action.genre,
                            action.date,
                            action.isLiveOnly,
                            action.channelName
                        )
                        aiConciergeViewModel.submitSilentSearchResult(action.keyword, results)
                    }
                }

                is AiConciergeAction.ReqRecSearch -> {
                    scope.launch {
                        val allRecs = recordViewModel.recentRecordings.value
                        val keywords =
                            action.keyword.split(",").map { it.trim() }.filter { it.isNotBlank() }

                        val results = allRecs.filter { program ->
                            val matchKeyword = keywords.isEmpty() || keywords.any { kw ->
                                program.title.contains(kw, ignoreCase = true) ||
                                        program.description.contains(kw, ignoreCase = true)
                            }
                            val matchGenre = action.genre.isBlank() ||
                                    program.genres?.any { g -> g.major.contains(action.genre) } == true

                            matchKeyword && matchGenre
                        }
                        aiConciergeViewModel.submitSilentRecordSearchResult(action.keyword, results)
                    }
                }

                is AiConciergeAction.ReserveSingle -> {
                    closeAiConcierge(true)
                    reserveViewModel.addReserve(action.programId) {
                        state.toastMessage = "番組の録画予約を完了しました"
                    }
                }

                is AiConciergeAction.ReserveAuto -> {
                    closeAiConcierge(true)
                    reserveViewModel.addEpgReserve(
                        keyword = action.keyword,
                        networkId = 0,
                        transportStreamId = 0,
                        serviceId = 0,
                        daysOfWeek = setOf(0, 1, 2, 3, 4, 5, 6),
                        startHour = 0,
                        startMinute = 0,
                        endHour = 23,
                        endMinute = 59,
                        excludeKeyword = "",
                        isTitleOnly = false,
                        broadcastType = "GR,BS,BS4K,CS,SKY",
                        isFuzzySearch = true,
                        duplicateScope = "SameTitle",
                        priority = 3,
                        isEventRelay = true,
                        isExactRecord = true,
                        onSuccess = {
                            state.toastMessage = "「${action.keyword}」の自動録画条件を登録しました"
                        }
                    )
                }
            }
        }
    }

    val themeName by settingsViewModel.appTheme.collectAsState(initial = "MONOTONE")
    val currentTheme = remember(themeName) {
        runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.MONOTONE)
    }

    val themeSeason = remember(themeName) {
        when (themeName) {
            "SPRING", "SPRING_LIGHT" -> "SPRING"
            "SUMMER", "SUMMER_LIGHT" -> "SUMMER"
            "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"
            "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"
            else -> "DEFAULT"
        }
    }

    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val detailFocusRequester = remember { FocusRequester() }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()

    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState(initial = emptyList())

    val conditions by reserveViewModel.conditions.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()

    val autoReserveKeywords = remember(conditions) {
        conditions.map { it.programSearchCondition.keyword }.filter { it.isNotBlank() }
    }

    val isSyncingInitial by remember(recordViewModel) {
        recordViewModel.syncProgress.map { it.isSyncing && it.isInitialBuild }
            .distinctUntilChanged()
    }.collectAsState(initial = false)

    val hasSyncError by remember(recordViewModel) {
        recordViewModel.syncProgress.map { it.error != null }.distinctUntilChanged()
    }.collectAsState(initial = false)

    val isEpgReady by epgViewModel.isInitialLoadComplete.collectAsState()

    // --- 各種IP/Port設定値の取得 ---
    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val edcbIp by settingsViewModel.edcbIp.collectAsState(initial = "")
    val edcbPort by settingsViewModel.edcbPort.collectAsState(initial = "")

    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    val startupChannelSetting by settingsViewModel.startupChannel.collectAsState()
    val updateState by homeViewModel.updateState.collectAsState()

    var isLongPressHandled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!state.hasAppliedStartupTab) {
            val tab = settingsViewModel.getStartupTabOnce()
            val index = tabs.indexOf(tab)
            state.currentTabIndex = if (index != -1) index else 0
            channelViewModel.fetchChannels()
            state.hasAppliedStartupTab = true
        }
    }

    LaunchedEffect(
        groupedChannels, state.hasAppliedStartupTab, isSettingsInitialized,
        isChannelLoading, isHomeLoading, lastChannels
    ) {
        if (isSettingsInitialized && state.hasAppliedStartupTab && !state.hasAppliedStartupChannel && !isChannelLoading && !isHomeLoading && groupedChannels.isNotEmpty()) {
            state.hasAppliedStartupChannel = true

            val flatChannels = groupedChannels.values.flatten()
            if (flatChannels.isNotEmpty()) {
                val startupChannelId = startupChannelSetting
                if (startupChannelId != "OFF") {
                    val targetChannel = if (startupChannelId == "LAST_WATCHED") {
                        val lastHistory = lastChannels.firstOrNull()
                        flatChannels.find { it.id == lastHistory?.id } ?: flatChannels.first()
                    } else {
                        flatChannels.find { it.id == startupChannelId } ?: flatChannels.first()
                    }

                    state.selectedChannel = targetChannel
                    state.isBaseballMode = false
                    state.lastSelectedChannelId = targetChannel.id
                    state.lastSelectedProgramId = null
                    homeViewModel.saveLastChannel(targetChannel)
                    state.isReturningFromPlayer = false

                    // ★ 修正: ライブタブが存在すればそこに移動
                    val liveIndex = tabs.indexOf("ライブ")
                    if (liveIndex != -1) state.currentTabIndex = liveIndex
                    state.isUiReady = true
                }
            }
        }
    }

    LaunchedEffect(state.isRecordListOpen) {
        if (state.isRecordListOpen) {
            recordViewModel.triggerSmartSync()
        }
    }

    val closeSettingsAndRefresh = {
        state.isSettingsOpen = false
        state.isDataReady = false
        state.isUiReady = false
        state.showConnectionErrorDialog = false
        state.currentTabIndex = 0
        channelViewModel.fetchChannels()
        epgViewModel.preloadAllEpgData()
        homeViewModel.refreshHomeData()
        recordViewModel.fetchRecentRecordings(forceRefresh = false)
        reserveViewModel.fetchReserves()
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3000); state.toastMessage = null
        }
    }

    BackHandler(enabled = true) {
        if (!state.canProcessBackPress()) return@BackHandler

        when {
            state.isAiConciergeOpen -> closeAiConcierge(true)
            state.selectedConditionReserveItem != null -> state.selectedConditionReserveItem = null
            state.editingNewProgram != null -> state.editingNewProgram = null
            state.editingReserveItem != null -> state.editingReserveItem = null
            state.reserveToDelete != null -> state.reserveToDelete = null
            state.selectedProgramForAutoReserve != null -> state.selectedProgramForAutoReserve =
                null

            state.showDeleteConfirmDialog -> state.showDeleteConfirmDialog = false
            state.isMiniPlayerMode -> {
                state.isMiniPlayerMode = false
                state.toastMessage = "フルスクリーンに戻りました"
            }

            state.isPlayerMiniListOpen -> state.isPlayerMiniListOpen = false
            state.playerIsSubMenuOpen -> state.playerIsSubMenuOpen = false
            state.isPlayerSubMenuOpen -> state.isPlayerSubMenuOpen = false
            state.isPlayerSceneSearchOpen -> {
                state.isPlayerSceneSearchOpen = false; state.showPlayerControls = false
            }

            state.selectedChannel != null -> {
                state.selectedChannel = null; state.isReturningFromPlayer = true
                state.isMiniPlayerMode = false
            }

            state.selectedProgram != null -> {
                state.selectedProgram = null; state.showPlayerControls =
                    true; state.isReturningFromPlayer = true
                state.isMiniPlayerMode = false
            }

            state.isSettingsOpen -> closeSettingsAndRefresh()
            state.epgSelectedProgram != null -> state.epgSelectedProgram = null
            state.selectedReserve != null -> state.selectedReserve = null
            state.isEpgJumpMenuOpen -> state.isEpgJumpMenuOpen = false
            state.isRecordListOpen -> {
                state.isRecordListOpen = false
                if (state.openedSeriesTitle != null) {
                    state.isSeriesListOpen = true; state.openedSeriesTitle = null
                }
                recordViewModel.searchRecordings("")
            }

            state.isSeriesListOpen -> {
                state.isSeriesListOpen = false; recordViewModel.searchRecordings("")
            }

            state.showConnectionErrorDialog -> onExitApp()
            !(state.isDataReady && state.isUiReady) -> {}
            else -> state.triggerHomeBack = true
        }
    }

    LaunchedEffect(isChannelLoading, isHomeLoading) {
        if (!isChannelLoading && !isHomeLoading) {
            delay(300)
            if (isChannelError) {
                state.showConnectionErrorDialog = true; state.isDataReady = false
            } else {
                state.showConnectionErrorDialog = false; state.isDataReady = true
            }
        }
    }

    LaunchedEffect(isEpgReady, state.isDataReady, isSettingsInitialized, state.currentTabIndex) {
        if (!isSettingsInitialized) {
            delay(500); state.isSplashFinished = true
        } else if (state.currentTabIndex == tabs.indexOf("番組表")) {
            if (isEpgReady && state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        } else {
            if (state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        }
    }

    val isSystemReady =
        ((state.isDataReady && state.isSplashFinished) || (!isSettingsInitialized && state.isSplashFinished)) &&
                state.hasAppliedStartupTab && (startupChannelSetting == "OFF" || state.hasAppliedStartupChannel)

    KomorebiTheme(theme = currentTheme) {
        val colors = KomorebiTheme.colors
        val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val isCenterKey =
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter

                    if (isCenterKey && event.type == KeyEventType.KeyUp) {
                        if (isLongPressHandled) {
                            isLongPressHandled = false
                            return@onPreviewKeyEvent true
                        }
                    }

                    if (state.isAiConciergeOpen || state.showAiKeyboardInput) {
                        return@onPreviewKeyEvent false
                    }

                    if (isCenterKey && event.type == KeyEventType.KeyDown) {
                        if ((event.nativeKeyEvent.isLongPress || event.nativeKeyEvent.repeatCount > 0) && !isLongPressHandled) {
                            isLongPressHandled = true
                            state.isAiConciergeOpen = true
                            aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT)
                            return@onPreviewKeyEvent true
                        }
                        if (isLongPressHandled) return@onPreviewKeyEvent true
                    }
                    false
                }
                .background(colors.background)
                .background(backgroundBrush)
        ) {
            if (state.selectedChannel == null && state.selectedProgram == null) {
                SeasonalDecor(
                    season = themeSeason,
                    isDark = colors.isDark,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            val showMainContent =
                isSystemReady && isSettingsInitialized && !state.showConnectionErrorDialog && !isSyncingInitial

            if (showMainContent) {
                Box(modifier = Modifier.fillMaxSize()) {

                    // ★ Z-index: 0 ========================================================
                    // 背面のホーム画面
                    val showHomeLayer =
                        (state.selectedChannel == null && state.selectedProgram == null) || state.isMiniPlayerMode
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
                                            if (!program.recordedVideo.hasKeyFrames) return@RecordListScreen
                                            val duration = program.recordedVideo.duration
                                            val history =
                                                watchHistory.find { it.program.id.toString() == program.id.toString() }

                                            val resumePos = when {
                                                forcedPosition != null -> forcedPosition
                                                program.playbackPosition > 5.0 && (duration <= 0 || program.playbackPosition < (duration - 10)) -> program.playbackPosition
                                                history != null && history.playback_position > 5.0 && (duration <= 0 || history.playback_position < (duration - 10)) -> history.playback_position
                                                else -> 0.0
                                            }
                                            state.initialPlaybackPositionMs =
                                                (resumePos * 1000).toLong()
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
                                                state.isSeriesListOpen =
                                                    true; state.openedSeriesTitle = null
                                            }
                                            recordViewModel.searchRecordings("")
                                        },
                                        isReturningFromPlayer = state.isReturningFromPlayer,
                                        lastPlayedProgramId = state.lastPlayedRecordingId,
                                        onReturnFocusConsumed = {
                                            state.isReturningFromPlayer = false
                                        },
                                        timeFormat = timeFormat,
                                        autoReserveKeywords = autoReserveKeywords,
                                        onAutoReserveClick = { program ->
                                            state.selectedProgramForAutoReserve = program
                                        },
                                        aiFocusReturnTick = state.aiFocusReturnTick,
                                        onAiReturnConsumed = { state.aiFocusReturnTick = 0 }
                                    )
                                }

                                state.editingCondition != null -> {
                                    val currentCondition =
                                        conditions.find { it.id == state.editingCondition!!.id }
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
                                                        state.toastMessage =
                                                            "予約条件を更新しました"
                                                    }
                                                }
                                            )
                                        },
                                        onConfirmDelete = { deleteRelated ->
                                            reserveViewModel.deleteConditionWithCleanup(
                                                condition = currentCondition,
                                                deleteRelatedReserves = deleteRelated,
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
                                        onReserveItemClick = {
                                            state.selectedConditionReserveItem = it
                                        },
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
                                        initialTabIndex = safeTabIndex, // ★ 修正: 安全なインデックスを渡す
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
                                                if (!program.recordedVideo.hasKeyFrames) return@HomeLauncherScreen
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
                                        onReserveSelected = { reserveItem ->
                                            state.selectedReserve = reserveItem
                                        },
                                        onConditionClick = { condition ->
                                            state.editingCondition = condition
                                        },
                                        isReserveOverlayOpen = state.selectedReserve != null || state.editingCondition != null,
                                        epgSelectedProgram = state.epgSelectedProgram,
                                        onEpgProgramSelected = { state.epgSelectedProgram = it },
                                        isEpgJumpMenuOpen = state.isEpgJumpMenuOpen,
                                        onEpgJumpMenuStateChanged = {
                                            state.isEpgJumpMenuOpen = it
                                        },
                                        triggerBack = state.triggerHomeBack,
                                        onBackTriggered = { state.triggerHomeBack = false },
                                        onFinalBack = onExitApp,
                                        onUiReady = { state.isUiReady = true },
                                        onNavigateToPlayer = { channelId, _, _ ->
                                            val channel = groupedChannels.values.flatten()
                                                .find { ch -> ch.id == channelId }
                                            if (channel != null) {
                                                state.selectedChannel = channel
                                                state.isBaseballMode = false
                                                state.lastSelectedChannelId = channelId
                                                state.lastSelectedProgramId = null
                                                homeViewModel.saveLastChannel(channel)
                                                state.epgSelectedProgram =
                                                    null; state.isEpgJumpMenuOpen = false
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
                                        isReturningFromPlayer = state.isReturningFromPlayer,
                                        onReturnFocusConsumed = {
                                            state.isReturningFromPlayer = false
                                        },
                                        isUiReadyFlag = state.isUiReady,
                                        settingsViewModel = settingsViewModel,
                                        timeFormat = timeFormat,
                                        hasActivePlayer = state.isMiniPlayerMode,
                                        onReturnToPlayerClick = {
                                            state.isMiniPlayerMode = false
                                        },
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

                    // ★ Z-index: 1 ========================================================
                    // 前面のプレイヤー画面（PiPモード時は縮小・右下へ移動）
                    if (state.selectedChannel != null || state.selectedProgram != null) {

                        val playerWidth by animateDpAsState(
                            targetValue = if (state.isMiniPlayerMode) 320.dp else 1920.dp,
                            label = "width",
                            animationSpec = tween(400)
                        )
                        val playerHeight by animateDpAsState(
                            targetValue = if (state.isMiniPlayerMode) 180.dp else 1080.dp,
                            label = "height",
                            animationSpec = tween(400)
                        )
                        val playerPadding by animateDpAsState(
                            targetValue = if (state.isMiniPlayerMode) 32.dp else 0.dp,
                            label = "padding",
                            animationSpec = tween(400)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f)
                                .let {
                                    if (state.isMiniPlayerMode) it
                                        .padding(
                                            bottom = playerPadding,
                                            end = playerPadding
                                        )
                                        .wrapContentSize(Alignment.BottomEnd) else it
                                }
                                .size(playerWidth, playerHeight)
                                .clip(RoundedCornerShape(if (state.isMiniPlayerMode) 12.dp else 0.dp))
                        ) {
                            if (state.selectedChannel != null) {
                                LivePlayerScreen(
                                    channel = state.selectedChannel!!,
                                    initialQuality = defaultLiveQuality,
                                    isBaseballMode = state.isBaseballMode,
                                    isMiniListOpen = state.isPlayerMiniListOpen,
                                    onMiniListToggle = { state.isPlayerMiniListOpen = it },
                                    showOverlay = state.playerShowOverlay,
                                    onShowOverlayChange = { state.playerShowOverlay = it },
                                    isManualOverlay = state.playerIsManualOverlay,
                                    onManualOverlayChange = { state.playerIsManualOverlay = it },
                                    isPinnedOverlay = state.playerIsPinnedOverlay,
                                    onPinnedOverlayChange = { state.playerIsPinnedOverlay = it },
                                    isSubMenuOpen = state.playerIsSubMenuOpen,
                                    onSubMenuToggle = { state.playerIsSubMenuOpen = it },
                                    onChannelSelect = { newChannel ->
                                        state.selectedChannel = newChannel
                                        state.lastSelectedChannelId = newChannel.id
                                        state.lastSelectedProgramId = null
                                        homeViewModel.saveLastChannel(newChannel)
                                        state.isReturningFromPlayer = false
                                    },
                                    onBackPressed = {
                                        state.selectedChannel = null; state.isReturningFromPlayer =
                                        true
                                        state.isMiniPlayerMode = false
                                    },
                                    onShowToast = { state.toastMessage = it },
                                    isPiPMode = state.isMiniPlayerMode,
                                    onPiPRequested = {
                                        state.isMiniPlayerMode = true
                                        state.toastMessage = "ミニプレイヤーに変更しました"
                                    },
                                    timeFormat = timeFormat
                                )
                            } else if (state.selectedProgram != null) {
                                VideoPlayerScreen(
                                    program = state.selectedProgram!!,
                                    initialPositionMs = state.initialPlaybackPositionMs,
                                    initialQuality = defaultVideoQuality,
                                    konomiIp = konomiIp, konomiPort = konomiPort,
                                    showControls = state.showPlayerControls,
                                    onShowControlsChange = { state.showPlayerControls = it },
                                    isSubMenuOpen = state.isPlayerSubMenuOpen,
                                    onSubMenuToggle = { state.isPlayerSubMenuOpen = it },
                                    isSceneSearchOpen = state.isPlayerSceneSearchOpen,
                                    onSceneSearchToggle = { state.isPlayerSceneSearchOpen = it },
                                    onBackPressed = {
                                        state.selectedProgram = null; state.isReturningFromPlayer =
                                        true
                                        state.isMiniPlayerMode = false
                                    },
                                    onShowToast = { state.toastMessage = it },
                                    isPiPMode = state.isMiniPlayerMode,
                                    onPiPRequested = {
                                        state.isMiniPlayerMode = true
                                        state.toastMessage = "ミニプレイヤーに変更しました"
                                    }
                                )
                            }
                        }
                    }

                    if (hasSyncError) {
                        val errorMessage =
                            recordViewModel.syncProgress.value.error ?: "不明なエラー"
                        SyncErrorDialog(
                            errorMessage = errorMessage,
                            onRetry = {
                                recordViewModel.clearSyncError()
                                recordViewModel.triggerSmartSync()
                            },
                            onDismiss = { recordViewModel.clearSyncError() }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !state.isUiReady && !state.showConnectionErrorDialog && isSettingsInitialized,
                enter = fadeIn(),
                exit = fadeOut(tween(250))
            ) {
                if (isSyncingInitial) {
                    val currentSync by recordViewModel.syncProgress.collectAsState()
                    val pRatio =
                        if (currentSync.total > 0) currentSync.current.toFloat() / currentSync.total.toFloat() else 0f
                    LoadingScreen(
                        message = currentSync.progressText,
                        progressRatio = pRatio
                    )
                } else {
                    LoadingScreen()
                }
            }

            if (state.selectedProgramForAutoReserve != null) {
                val program = state.selectedProgramForAutoReserve!!
                val initialKeyword = TitleNormalizer.extractDisplayTitle(program.title)
                val now = OffsetDateTime.now()
                val start =
                    runCatching { OffsetDateTime.parse(program.startTime) }.getOrDefault(now)
                val end = runCatching { OffsetDateTime.parse(program.endTime) }.getOrDefault(
                    now.plusHours(1)
                )

                EpgReserveDialog(
                    initialKeyword = initialKeyword,
                    initialStartTime = start,
                    initialEndTime = end,
                    onConfirm = { keyword, daysOfWeek, startH, startM, endH, endM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->

                        val channelId = program.channel?.id
                        val matchedChannel =
                            groupedChannels.values.flatten().find { it.id == channelId }

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
                            keyword = keyword,
                            networkId = nId,
                            transportStreamId = tsId,
                            serviceId = sId,
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
                                    state.selectedProgramForAutoReserve = null
                                    delay(300)
                                    state.toastMessage = "「${keyword}」の自動録画条件を登録しました"
                                }
                            }
                        )
                    },
                    onDismiss = {
                        state.selectedProgramForAutoReserve = null
                    },
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
                    initialFocusRequester = detailFocusRequester,
                    timeFormat = timeFormat
                )
            }

            if (state.epgSelectedProgram != null) {
                val relatedReserve =
                    reserves.find { it.program.id == state.epgSelectedProgram!!.id }
                ProgramDetailScreen(
                    program = state.epgSelectedProgram!!,
                    mode = ProgramDetailMode.EPG,
                    isReserved = relatedReserve != null,
                    onPlayClick = {
                        val channel =
                            groupedChannels.values.flatten().find { ch -> ch.id == it.channel_id }
                        if (channel != null) {
                            state.selectedChannel = channel
                            state.isBaseballMode = false
                            state.lastSelectedChannelId = channel.id
                            state.lastSelectedProgramId = null; homeViewModel.saveLastChannel(
                                channel
                            )
                            state.epgSelectedProgram = null; state.isReturningFromPlayer = false
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
                        val channel =
                            groupedChannels.values.flatten().find { it.id == program.channel_id }

                        var finalTsId = channel?.transportStreamId?.toInt() ?: 0

                        if (finalTsId == 0) {
                            val currentEpgState = epgViewModel.uiState
                            if (currentEpgState is EpgUiState.Success) {
                                val epgChannel = currentEpgState.data.find {
                                    it.channel.id == program.channel_id ||
                                            (it.channel.network_id == program.network_id && it.channel.service_id == program.service_id)
                                }?.channel
                                if (epgChannel != null) {
                                    finalTsId = epgChannel.transport_stream_id
                                }
                            }
                        }

                        if (finalTsId == 0) {
                            val searchResults = epgViewModel.searchResults.value
                            val matchedResult = searchResults.find {
                                it.program.id == program.id ||
                                        it.channel.id == program.channel_id ||
                                        (it.channel.network_id == program.network_id && it.channel.service_id == program.service_id)
                            }
                            if (matchedResult != null) {
                                finalTsId = matchedResult.channel.transport_stream_id
                            }
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
                                    state.epgSelectedProgram = null
                                    delay(300)
                                    state.toastMessage = "EPG予約を登録しました"
                                }
                            }
                        )
                    },
                    onRecordDetailClick = { program -> state.editingNewProgram = program },
                    onEditReserveClick = { _ ->
                        if (relatedReserve != null) reserveViewModel.refreshReserveItem(
                            relatedReserve.id
                        ) { state.editingReserveItem = it ?: relatedReserve }
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
                        reserveViewModel.updateReservation(
                            state.editingReserveItem!!,
                            newSettings
                        ) {
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
                    })
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
                        reserveViewModel.addReserveWithSettings(
                            state.editingNewProgram!!.id,
                            newSettings
                        ) {
                            scope.launch {
                                state.editingNewProgram = null; state.epgSelectedProgram = null
                                delay(300); state.toastMessage = AppStrings.TOAST_RESERVED
                            }
                        }
                    },
                    onDismiss = {
                        state.editingNewProgram = null
                        scope.launch { delay(200); detailFocusRequester.safeRequestFocus("ProgramDetail") }
                    })
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
                                if (state.epgSelectedProgram != null) state.epgSelectedProgram =
                                    null
                                delay(300); state.toastMessage = AppStrings.TOAST_RESERVE_DELETED
                            }
                        }
                    },
                    onCancel = { state.reserveToDelete = null })
            }

            if (!isSettingsInitialized && !state.isSettingsOpen && state.isSplashFinished) {
                InitialSetupDialog(onConfirm = { state.isSettingsOpen = true })
            }

            if (state.showConnectionErrorDialog && isSettingsInitialized && !state.isSettingsOpen) {
                ConnectionErrorDialog(onGoToSettings = {
                    state.showConnectionErrorDialog = false; state.isSettingsOpen = true
                }, onExit = onExitApp)
            }

            if (state.isSettingsOpen) {
                SettingsScreen(
                    onBack = closeSettingsAndRefresh,
                    onClearLastChannel = {
                        homeViewModel.clearLastChannelHistory(); state.toastMessage =
                        AppStrings.TOAST_CHANNEL_HISTORY_DELETED
                    },
                    onClearWatchHistory = {
                        recordViewModel.clearWatchHistory(); state.toastMessage =
                        AppStrings.TOAST_WATCH_HISTORY_DELETED
                    })
            }

            if (updateState is UpdateState.UpdateAvailable) {
                val available = updateState as UpdateState.UpdateAvailable
                RobustUpdateDialog(
                    versionName = available.versionName,
                    releaseNotes = available.releaseNotes,
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
                onClose = {
                    closeAiConcierge(true)
                },
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
                        scope.launch {
                            delay(150)
                            aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT)
                        }
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
                        scope.launch {
                            delay(150)
                            aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT)
                        }
                    }
                )
            }
        }
    }
}