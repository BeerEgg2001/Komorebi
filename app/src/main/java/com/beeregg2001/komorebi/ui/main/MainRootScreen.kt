@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.mapper.ReserveMapper
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.data.sync.SyncProgress
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
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import com.beeregg2001.komorebi.util.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.OffsetDateTime

private const val TAG = "MainRootScreen"

/**
 * アプリのすべての画面遷移と共通状態を管理するルートコンポーネント。
 * MainActivityのsetContentから直接呼び出されます。
 *
 * 各タブ（HomeLauncherScreen）の表示、プレイヤー（Live/Video）の切り替え、
 * グローバルなダイアログやトーストの表示、そしてAndroid TV特有の複雑な「戻る」ボタンの挙動を統括します。
 */
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
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // UIの表示状態（どの画面やダイアログが開いているか）を保持する独自ステート
    val state = rememberMainRootState()

    // ==========================================
    // 1. テーマ・季節・時刻の監視
    // ==========================================
    val themeName by settingsViewModel.appTheme.collectAsState(initial = "MONOTONE")
    val currentTheme = remember(themeName) {
        runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.MONOTONE)
    }

    // 選択されたテーマ名から、背景装飾用の「季節」を判定
    val themeSeason = remember(themeName) {
        when (themeName) {
            "SPRING", "SPRING_LIGHT" -> "SPRING"
            "SUMMER", "SUMMER_LIGHT" -> "SUMMER"
            "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"
            "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"
            else -> "DEFAULT"
        }
    }

    // アプリ全体の時計（背景の動的グラデーション等に使用するため1分ごとに更新）
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val detailFocusRequester = remember { FocusRequester() }

    // ==========================================
    // 2. 各ViewModelからのState収集
    // ==========================================
    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()

    val conditions by reserveViewModel.conditions.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()
    val syncProgress by recordViewModel.syncProgress.collectAsState()

    val isEpgReady by epgViewModel.isInitialLoadComplete.collectAsState()

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    val updateState by homeViewModel.updateState.collectAsState()

    // ==========================================
    // 3. アプリ起動時の初期処理
    // ==========================================

    // ユーザーが設定した「起動時に表示するタブ」を適用
    LaunchedEffect(Unit) {
        if (!state.hasAppliedStartupTab) {
            val tab = settingsViewModel.getStartupTabOnce()
            state.currentTabIndex = when (tab) {
                "ホーム" -> 0; "ライブ" -> 1; "ビデオ" -> 2; "番組表" -> 3; "録画予約" -> 4; else -> 0
            }
            state.hasAppliedStartupTab = true
            channelViewModel.fetchChannels() // タブ適用と同時にチャンネル情報をフェッチ
        }
    }

    // 録画リストを開いた瞬間に、新しい録画がないかバックグラウンドでスマート同期を行う
    LaunchedEffect(state.isRecordListOpen) {
        if (state.isRecordListOpen) {
            recordViewModel.triggerSmartSync()
        }
    }

    // 設定画面を閉じた際の再描画・データ再取得処理
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

    // トーストメッセージを3秒表示した後に自動で消去するループ
    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3000); state.toastMessage = null
        }
    }

    // アップデート（APKダウンロード）中のエラーをトーストで通知
    LaunchedEffect(updateState) {
        if (updateState is UpdateState.Error) {
            state.toastMessage = (updateState as UpdateState.Error).message
            homeViewModel.dismissUpdate()
        }
    }

    // ==========================================
    // 4. グローバル バックハンドラー (Back Navigation)
    // ==========================================
    // Android TVのリモコンの「戻る」ボタンが押されたときの挙動を、
    // 現在開いている画面やダイアログの状態に応じて優先順位をつけてルーティングします。
    BackHandler(enabled = true) {
        if (!state.canProcessBackPress()) return@BackHandler

        when {
            // ダイアログ・編集画面系を閉じる
            state.selectedConditionReserveItem != null -> state.selectedConditionReserveItem = null
            state.editingNewProgram != null -> state.editingNewProgram = null
            state.editingReserveItem != null -> state.editingReserveItem = null
            state.reserveToDelete != null -> state.reserveToDelete = null
            state.showDeleteConfirmDialog -> state.showDeleteConfirmDialog = false

            // プレイヤーのメニュー系を閉じる
            state.isPlayerMiniListOpen -> state.isPlayerMiniListOpen = false
            state.playerIsSubMenuOpen -> state.playerIsSubMenuOpen = false
            state.isPlayerSubMenuOpen -> state.isPlayerSubMenuOpen = false
            state.isPlayerSceneSearchOpen -> {
                state.isPlayerSceneSearchOpen = false; state.showPlayerControls = false
            }

            // プレイヤー画面自体を閉じてメインUIに戻る
            state.selectedChannel != null -> {
                state.selectedChannel = null; state.isReturningFromPlayer = true
            }

            state.selectedProgram != null -> {
                state.selectedProgram = null; state.showPlayerControls =
                    true; state.isReturningFromPlayer = true
            }

            // その他、設定画面やサブ画面を閉じる
            state.isSettingsOpen -> closeSettingsAndRefresh()
            state.epgSelectedProgram != null -> state.epgSelectedProgram = null
            state.selectedReserve != null -> state.selectedReserve = null
            state.isEpgJumpMenuOpen -> state.isEpgJumpMenuOpen = false

            // 録画リスト画面から戻る
            state.isRecordListOpen -> {
                state.isRecordListOpen = false
                // もしシリーズ画面から録画リストに飛んでいた場合は、シリーズ画面に戻る
                if (state.openedSeriesTitle != null) {
                    state.isSeriesListOpen = true; state.openedSeriesTitle = null
                }
                recordViewModel.searchRecordings("")
            }

            // シリーズリスト画面から戻る
            state.isSeriesListOpen -> {
                state.isSeriesListOpen = false; recordViewModel.searchRecordings("")
            }

            // 接続エラー画面の場合はアプリを終了
            state.showConnectionErrorDialog -> onExitApp()

            // まだデータ読み込み中の場合は何もしない
            !(state.isDataReady && state.isUiReady) -> {}

            // どれにも当てはまらない（メインUIのトップにいる）場合は、HomeLauncherScreen 側にバック処理を委譲
            else -> state.triggerHomeBack = true
        }
    }

    // ==========================================
    // 5. ローディング・スプラッシュ画面の解除判定
    // ==========================================
    // チャンネルとホームデータの取得が完了したか、エラーが発生したかをチェック
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

    // 各タブに応じたデータの準備が完了したら、スプラッシュ(LoadingScreen)を終了する
    LaunchedEffect(isEpgReady, state.isDataReady, isSettingsInitialized, state.currentTabIndex) {
        if (!isSettingsInitialized) {
            delay(500); state.isSplashFinished = true
        } else if (state.currentTabIndex == 3) {
            // 番組表タブが初期表示の場合は、番組表データ(isEpgReady)の完了も待つ
            if (isEpgReady && state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        } else {
            if (state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        }
    }

    // 画面にメインUIを表示して良いかどうかの最終フラグ
    val isSystemReady =
        ((state.isDataReady && state.isSplashFinished) || (!isSettingsInitialized && state.isSplashFinished)) && state.hasAppliedStartupTab

    // ==========================================
    // 6. UI コンポジション
    // ==========================================
    KomorebiTheme(theme = currentTheme) {
        val colors = KomorebiTheme.colors

        // 時刻とテーマに応じたグラデーション背景を生成
        val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .background(backgroundBrush)
        ) {
            // プレイヤー画面でなければ、右下に季節の装飾アイコン（桜や雪の結晶など）を表示
            if (state.selectedChannel == null && state.selectedProgram == null) {
                SeasonalDecor(
                    season = themeSeason,
                    isDark = colors.isDark,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            // メインコンテンツの表示条件:
            // 準備完了済み かつ 初期設定済み かつ エラー画面でない かつ DBの初回構築中でない
            val showMainContent =
                isSystemReady && isSettingsInitialized && !state.showConnectionErrorDialog && !(syncProgress.isSyncing && syncProgress.isInitialBuild)

            if (showMainContent) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        // ライブ視聴画面 (LivePlayerScreen)
                        state.selectedChannel != null -> {
                            LivePlayerScreen(
                                channel = state.selectedChannel!!,
                                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                initialQuality = defaultLiveQuality,
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
                                    state.selectedChannel = null; state.isReturningFromPlayer = true
                                },
                                onShowToast = { state.toastMessage = it })
                        }

                        // 録画視聴画面 (VideoPlayerScreen)
                        state.selectedProgram != null -> {
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
                                    state.selectedProgram = null; state.isReturningFromPlayer = true
                                },
                                onShowToast = { state.toastMessage = it })
                        }

                        // 録画リスト・検索画面 (RecordListScreen)
                        state.isRecordListOpen -> {
                            RecordListScreen(
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                customTitle = state.openedSeriesTitle,
                                onProgramClick = { program, forcedPosition ->
                                    if (!program.recordedVideo.hasKeyFrames) return@RecordListScreen
                                    val duration = program.recordedVideo.duration
                                    val history =
                                        watchHistory.find { it.program.id.toString() == program.id.toString() }

                                    // 続きから再生する位置の判定（強制的、履歴、番組自体のレジュームポイント）
                                    val resumePos = when {
                                        forcedPosition != null -> forcedPosition
                                        program.playbackPosition > 5.0 && (duration <= 0 || program.playbackPosition < (duration - 10)) -> program.playbackPosition
                                        history != null && history.playback_position > 5.0 && (duration <= 0 || history.playback_position < (duration - 10)) -> history.playback_position
                                        else -> 0.0
                                    }
                                    state.initialPlaybackPositionMs = (resumePos * 1000).toLong()
                                    state.selectedProgram = program
                                    state.lastSelectedProgramId = program.id.toString()
                                    state.showPlayerControls = true
                                    state.isReturningFromPlayer = false
                                },
                                onBack = {
                                    state.isRecordListOpen = false
                                    if (state.openedSeriesTitle != null) {
                                        state.isSeriesListOpen = true; state.openedSeriesTitle =
                                            null
                                    }
                                    recordViewModel.searchRecordings("")
                                })
                        }

                        // 自動予約条件の編集ダイアログ (ConditionEditDialog)
                        state.editingCondition != null -> {
                            val currentCondition =
                                conditions.find { it.id == state.editingCondition!!.id }
                                    ?: state.editingCondition!!
                            val relatedReserves =
                                reserves.filter { it.comment.contains(currentCondition.programSearchCondition.keyword) }

                            // 背景を暗くするためのBox
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
                                onReserveItemClick = { state.selectedConditionReserveItem = it }
                            )
                        }

                        // 全てのサブ画面が開いていない場合、メインのナビゲーションUI (HomeLauncherScreen) を表示
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
                                initialTabIndex = state.currentTabIndex,
                                onTabChange = { state.currentTabIndex = it },
                                selectedChannel = state.selectedChannel,
                                onChannelClick = { channel ->
                                    state.selectedChannel = channel
                                    if (channel != null) {
                                        state.lastSelectedChannelId = channel.id
                                        state.lastSelectedProgramId = null
                                        homeViewModel.saveLastChannel(channel)
                                        state.isReturningFromPlayer = false
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
                                    }
                                },
                                onReserveSelected = { reserveItem ->
                                    state.selectedReserve = reserveItem
                                },
                                onConditionClick = { condition ->
                                    state.editingCondition = condition
                                },
                                isReserveOverlayOpen = state.selectedReserve != null,
                                epgSelectedProgram = state.epgSelectedProgram,
                                onEpgProgramSelected = { state.epgSelectedProgram = it },
                                isEpgJumpMenuOpen = state.isEpgJumpMenuOpen,
                                onEpgJumpMenuStateChanged = { state.isEpgJumpMenuOpen = it },
                                triggerBack = state.triggerHomeBack,
                                onBackTriggered = { state.triggerHomeBack = false },
                                onFinalBack = onExitApp,
                                onUiReady = { state.isUiReady = true },
                                onNavigateToPlayer = { channelId, _, _ ->
                                    val channel = groupedChannels.values.flatten()
                                        .find { ch -> ch.id == channelId }
                                    if (channel != null) {
                                        state.selectedChannel =
                                            channel; state.lastSelectedChannelId = channelId
                                        state.lastSelectedProgramId =
                                            null; homeViewModel.saveLastChannel(channel)
                                        state.epgSelectedProgram = null; state.isEpgJumpMenuOpen =
                                            false
                                        state.isReturningFromPlayer = false
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
                                onReturnFocusConsumed = { state.isReturningFromPlayer = false },
                                isUiReadyFlag = state.isUiReady
                            )
                        }
                    }

                    // 録画DBの構築完了後、バックグラウンドでの同期中は右下に小さくインジケーターを表示
                    if (state.selectedChannel == null && state.selectedProgram == null && !syncProgress.isInitialBuild) {
                        SyncProgressIndicator(
                            syncProgress = syncProgress,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 40.dp, bottom = 40.dp)
                        )
                    }

                    // 同期エラー発生時のダイアログ
                    if (syncProgress.error != null) {
                        SyncErrorDialog(
                            errorMessage = syncProgress.error!!,
                            onRetry = {
                                recordViewModel.clearSyncError()
                                recordViewModel.triggerSmartSync()
                            },
                            onDismiss = { recordViewModel.clearSyncError() }
                        )
                    }
                }
            }

            // ==========================================
            // 7. スプラッシュ・ローディング画面の表示
            // ==========================================
            AnimatedVisibility(
                visible = !state.isUiReady && !state.showConnectionErrorDialog && isSettingsInitialized,
                enter = fadeIn(),
                exit = fadeOut(tween(500))
            ) {
                if (syncProgress.isSyncing && syncProgress.isInitialBuild) {
                    val pRatio =
                        if (syncProgress.total > 0) syncProgress.current.toFloat() / syncProgress.total.toFloat() else 0f
                    LoadingScreen(
                        message = syncProgress.progressText,
                        progressRatio = pRatio
                    )
                } else {
                    LoadingScreen()
                }
            }

            // ==========================================
            // 8. 予約・番組詳細系のオーバーレイダイアログ群
            // ==========================================

            // 自動予約条件プレビューリストから番組を選んだ際の詳細画面
            if (state.selectedConditionReserveItem != null) {
                val program =
                    remember(state.selectedConditionReserveItem) { ReserveMapper.toEpgProgram(state.selectedConditionReserveItem!!) }
                ProgramDetailScreen(
                    program = program,
                    mode = ProgramDetailMode.RESERVE,
                    isReserved = true,
                    isReadOnly = true,
                    onBackClick = { state.selectedConditionReserveItem = null },
                    initialFocusRequester = detailFocusRequester
                )
            }

            // 単発予約リストから番組を選んだ際の詳細・編集画面
            if (state.selectedReserve != null) {
                val program =
                    remember(state.selectedReserve) { ReserveMapper.toEpgProgram(state.selectedReserve!!) }
                ProgramDetailScreen(
                    program = program, mode = ProgramDetailMode.RESERVE, isReserved = true,
                    onBackClick = { state.selectedReserve = null },
                    onDeleteReserveClick = { _ -> state.reserveToDelete = state.selectedReserve },
                    onEditReserveClick = { _ ->
                        // 編集画面を開く直前に、最新の予約設定をAPIから再取得して不整合を防ぐ
                        reserveViewModel.refreshReserveItem(state.selectedReserve!!.id) { latest ->
                            state.editingReserveItem = latest ?: state.selectedReserve
                        }
                    },
                    initialFocusRequester = detailFocusRequester
                )
            }

            // 番組表（EPG）から番組を選んだ際の詳細画面
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
                            state.selectedChannel = channel; state.lastSelectedChannelId =
                                channel.id
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

                                // 現在放送中の番組を「録画する」とした場合は「録画を開始しました」、未来なら「予約しました」と文言を変える
                                val now = OffsetDateTime.now()
                                val start = try {
                                    OffsetDateTime.parse(program.start_time)
                                } catch (e: Exception) {
                                    now
                                }
                                val end = try {
                                    OffsetDateTime.parse(program.end_time)
                                } catch (e: Exception) {
                                    now
                                }
                                val isBroadcasting = now.isAfter(start) && now.isBefore(end)
                                state.toastMessage =
                                    if (isBroadcasting) AppStrings.TOAST_RECORDING_STARTED else AppStrings.TOAST_RESERVED
                            }
                        }
                    },
                    onEpgReserveClick = { program, keyword, daysOfWeek, startH, startM, endH, endM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                        // EPG予約に必要な TransportStreamId (tsId) を解決
                        val channel =
                            groupedChannels.values.flatten().find { it.id == program.channel_id }
                        var tsId = channel?.transportStreamId?.toInt()

                        if (tsId == null || tsId == 0) {
                            val epgState = epgViewModel.uiState
                            if (epgState is EpgUiState.Success) {
                                val epgChannel =
                                    epgState.data.find { it.channel.id == program.channel_id }?.channel
                                tsId = epgChannel?.transport_stream_id?.toInt()
                            }
                        }

                        val finalTsId = tsId ?: 0

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
                                    state.toastMessage =
                                        "EPG予約 (キーワード自動予約) を登録しました"
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
                    initialFocusRequester = detailFocusRequester
                )
            }

            // 予約設定の編集ダイアログ（詳細設定）
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

            // 新規の単発予約時の詳細設定ダイアログ
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

            // 予約削除の確認ダイアログ
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

            // ==========================================
            // 9. 初期設定とエラー処理
            // ==========================================
            // アプリ初回起動時（設定が空）の場合に表示する案内ダイアログ
            if (!isSettingsInitialized && !state.isSettingsOpen && state.isSplashFinished) {
                InitialSetupDialog(onConfirm = { state.isSettingsOpen = true })
            }

            // KonomiTVサーバーへの接続に失敗した場合のダイアログ
            if (state.showConnectionErrorDialog && isSettingsInitialized && !state.isSettingsOpen) {
                ConnectionErrorDialog(onGoToSettings = {
                    state.showConnectionErrorDialog = false; state.isSettingsOpen = true
                }, onExit = onExitApp)
            }

            // アプリ全体の設定画面
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

            // ==========================================
            // 10. アプリ内アップデート（OTA Update）UI
            // ==========================================
            // 新しいバージョンが見つかった場合のダイアログ
            if (updateState is UpdateState.UpdateAvailable) {
                val available = updateState as UpdateState.UpdateAvailable
                RobustUpdateDialog(
                    versionName = available.versionName,
                    releaseNotes = available.releaseNotes,
                    onConfirm = { homeViewModel.startUpdateDownload(available.apkUrl) },
                    onDismiss = { homeViewModel.dismissUpdate() }
                )
            }

            // ダウンロード中、およびインストール準備中の進行状況バナー（画面右下）
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

            // 全ての画面の上に重ねるグローバルトースト
            GlobalToast(message = state.toastMessage)
        }
    }
}

/**
 * 新規追加: 裏画面のフォーカス強奪を防ぐ防衛ループ付きダイアログ
 * Android TVでは、ダイアログ表示中に背後のリスト（HomeLauncherScreen等）がデータをロードし終えると
 * 勝手にフォーカスを奪ってしまう現象が発生するため、それを検知して強制的にフォーカスを取り返します。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RobustUpdateDialog(
    versionName: String,
    releaseNotes: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val confirmRequester = remember { FocusRequester() }
    var isDialogFocused by remember { mutableStateOf(true) }

    // ダイアログ表示直後にボタンへフォーカスを当てる
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { confirmRequester.requestFocus() }
    }

    // 裏画面(HomeLauncher等)が遅れてフォーカスを奪った際に、即座にダイアログへ取り返すループ
    LaunchedEffect(isDialogFocused) {
        if (!isDialogFocused) {
            delay(150)
            runCatching { confirmRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .zIndex(1000f) // 最前面に配置
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } } // 十字キーでダイアログ外にフォーカスが逃げるのを防ぐ
            .onFocusChanged { isDialogFocused = it.hasFocus },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(500.dp),
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "アップデートのお知らせ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "バージョン $versionName が利用可能です。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .background(
                            colors.textPrimary.copy(alpha = 0.05f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) { Text("後で", fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(confirmRequester),
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) { Text("ダウンロード", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/**
 * 録画DBのバックグラウンド同期中に、画面の右下に小さく進行状況を表示するインジケーター。
 */
@Composable
fun SyncProgressIndicator(
    syncProgress: SyncProgress,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val progress = if (syncProgress.total > 0) {
        syncProgress.current.toFloat() / syncProgress.total.toFloat()
    } else {
        0f
    }

    AnimatedVisibility(
        visible = syncProgress.isSyncing,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface.copy(alpha = 0.9f),
                contentColor = colors.textPrimary
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(min = 200.dp, max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = syncProgress.progressText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (syncProgress.total > 0) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = colors.textPrimary.copy(alpha = 0.2f)
                    )
                } else {
                    // 総数が不明な場合は不確定（Indeterminate）のプログレスバーを表示
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = colors.textPrimary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

/**
 * 同期処理中に致命的なエラーが発生した場合に表示するダイアログ。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SyncErrorDialog(errorMessage: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("SyncError") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "同期エラー",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("閉じる") }

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    ) { Text("再実行") }
                }
            }
        }
    }
}

/**
 * 旧バージョンのシンプルなアップデートダイアログ。
 * 現在は `RobustUpdateDialog` がメインで使用されます。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateDialog(
    versionName: String,
    releaseNotes: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .zIndex(200f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface,
                contentColor = colors.textPrimary
            ),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "アップデートのお知らせ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "最新バージョン ($versionName) が利用可能です。\n\n$releaseNotes\n\nアップデート開始後、Androidのシステム画面が開きますので、「インストール」または「更新」を選択してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("後で") }

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    ) { Text("今すぐ更新") }
                }
            }
        }
    }
}

/**
 * APKのダウンロード中、およびインストール準備中に表示される小さなバナー。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateProgressBanner(
    updateState: UpdateState,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val isReady = updateState is UpdateState.ReadyToInstall
    val progress =
        if (updateState is UpdateState.Downloading) updateState.progressPercentage else 100

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(8.dp),
        colors = SurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.95f),
            contentColor = colors.textPrimary
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Download,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReady) "インストーラ起動中..." else "アップデートをダウンロード中",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isReady) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            colors.textSecondary.copy(alpha = 0.2f),
                            RoundedCornerShape(2.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress / 100f)
                            .fillMaxHeight()
                            .background(colors.accent, RoundedCornerShape(2.dp))
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$progress %",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}