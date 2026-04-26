package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.components.InputDialog
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val colors = KomorebiTheme.colors
    val currentTime = remember { LocalTime.now() }
    val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

    val prefs = rememberSettingPreferences(repository)
    val uiState = rememberSettingUiState()

    val totalRecordCount by viewModel.totalRecordCount.collectAsState()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsState()

    val receiveBetaUpdates by viewModel.receiveBetaUpdates.collectAsState()

    val playerUiMode by viewModel.playerUiMode.collectAsState()
    val autoCmSkip by viewModel.autoCmSkip.collectAsState()
    val availableQualities by viewModel.availableQualities.collectAsState()

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    val categories = listOf(
        Category(AppStrings.SETTINGS_CATEGORY_GENERAL, Icons.Default.SettingsApplications),
        Category(AppStrings.SETTINGS_CATEGORY_CONNECTION, Icons.Default.CastConnected),
        Category(AppStrings.SETTINGS_CATEGORY_PLAYBACK, Icons.Default.PlayCircle),
        Category("録画設定", Icons.Default.VideoSettings),
        Category(AppStrings.SETTINGS_CATEGORY_HOME, Icons.Default.Home),
        Category(AppStrings.SETTINGS_CATEGORY_DISPLAY, Icons.Default.Dashboard),
        Category(AppStrings.SETTINGS_CATEGORY_COMMENT, Icons.Default.Tv),
        Category(AppStrings.SETTINGS_CATEGORY_LAB, Icons.Default.Science),
        Category(AppStrings.SETTINGS_CATEGORY_APP_INFO, Icons.Default.Info)
    )
    val categoryFocusRequesters = remember { List(categories.size) { FocusRequester() } }

    val batchItemRs =
        remember(prefs.postRecordingBatchList) { List(prefs.postRecordingBatchList.size) { FocusRequester() } }

    val edcbPlayMethodR = remember { FocusRequester() }

    val itemFocusRequesters = remember {
        listOf(
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 0: General
            listOf(
                FocusRequester(), // BackendType
                FocusRequester(), FocusRequester(), // Backend IP, Port
                FocusRequester(), // Stream Priority
                FocusRequester(), FocusRequester(),  // Override IP, Port
                FocusRequester()  // EDCBHttp/Httpsポート用のFocusRequester
            ), // 1: Connection
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(), // プレイヤーUIモード用
                FocusRequester()  // 自動CMスキップ用 FocusRequester
            ), // 2: Playback
            listOf(FocusRequester()), // 3: Recording
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 4: Home
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 5: Display
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 6: Comment
            listOf(FocusRequester(), FocusRequester(), FocusRequester()), // 7: Lab
            listOf(FocusRequester()) // 8: AppInfo
        )
    }

    val mainScrollState = rememberScrollState()
    val sidebarScrollState = rememberScrollState()

    LaunchedEffect(uiState.selectedCategoryIndex) { mainScrollState.scrollTo(0) }
    LaunchedEffect(Unit) {
        delay(300)
        categoryFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
            ?.safeRequestFocus("Settings_Initial")
    }

    val closeDialog = {
        uiState.isRestoringFocus = true
        uiState.activeDialog = SettingDialogState.None
        scope.launch {
            delay(300)
            uiState.restoreFocusRequester?.safeRequestFocus("SettingScreen_Restore")
            delay(100)
            uiState.isRestoringFocus = false
        }
    }

    val isDialogOpen = uiState.activeDialog !is SettingDialogState.None

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(backgroundBrush)
            .focusProperties { canFocus = !isDialogOpen }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK || it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_ESCAPE)) {
                    if (!uiState.isSidebarFocused) {
                        categoryFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
                            ?.safeRequestFocus("Back_To_Sidebar")
                    } else {
                        onBack()
                    }
                    true
                } else false
            }
    ) {
        // サイドバー
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(colors.surface.copy(alpha = 0.6f))
                .padding(top = 32.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
                .onFocusChanged { uiState.isSidebarFocused = it.hasFocus }
                .focusProperties { canFocus = !uiState.isRestoringFocus }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp, start = 8.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    AppStrings.SETTINGS_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(sidebarScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEachIndexed { index, category ->
                    val targetR = itemFocusRequesters.getOrNull(index)?.firstOrNull()
                        ?: FocusRequester.Default
                    CategoryItem(
                        title = category.name,
                        icon = category.icon,
                        isSelected = uiState.selectedCategoryIndex == index,
                        onFocused = {
                            if (uiState.isSidebarFocused) uiState.selectedCategoryIndex = index
                        },
                        onClick = { targetR.safeRequestFocus("CategoryItem_Click") },
                        enabled = !uiState.isRestoringFocus,
                        modifier = Modifier
                            .focusRequester(categoryFocusRequesters[index])
                            .focusProperties { right = targetR }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            CategoryItem(
                title = AppStrings.SETTINGS_BACK_TO_HOME,
                icon = Icons.Default.Home,
                isSelected = false,
                onFocused = { },
                onClick = onBack,
                enabled = !uiState.isRestoringFocus,
                modifier = Modifier.focusProperties {
                    up = categoryFocusRequesters.lastOrNull() ?: FocusRequester.Default; right =
                    itemFocusRequesters.getOrNull(uiState.selectedCategoryIndex)?.firstOrNull()
                        ?: FocusRequester.Default
                })
        }

        // メインコンテンツ
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 48.dp, horizontal = 64.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(mainScrollState)
            ) {
                when (uiState.selectedCategoryIndex) {
                    0 -> GeneralSettingsContent(
                        totalRecordCount = totalRecordCount,
                        lastSyncedAt = lastSyncedAt,
                        receiveBetaUpdates = receiveBetaUpdates,
                        onToggleBetaUpdates = { newValue ->
                            scope.launch {
                                repository.saveBoolean(
                                    SettingsRepository.RECEIVE_BETA_UPDATES,
                                    newValue
                                )
                            }
                        },
                        betaUpdateR = itemFocusRequesters[0][0],
                        dbInfoR = itemFocusRequesters[0][1],
                        forceSyncR = itemFocusRequesters[0][2],
                        clearChannelR = itemFocusRequesters[0][3],
                        clearHistoryR = itemFocusRequesters[0][4],
                        onForceSync = {
                            uiState.activeDialog = SettingDialogState.ConfirmClear(
                                "データベースの再構築",
                                "すべての録画データをサーバーから再取得します。よろしいですか？"
                            ) { viewModel.triggerFullSync() }
                        },
                        onClearChannel = {
                            uiState.activeDialog = SettingDialogState.ConfirmClear(
                                AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                                AppStrings.DIALOG_CLEAR_CHANNEL_HISTORY_MSG
                            ) { onClearLastChannel() }
                        },
                        onClearHistory = {
                            uiState.activeDialog = SettingDialogState.ConfirmClear(
                                AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                                AppStrings.DIALOG_CLEAR_WATCH_HISTORY_MSG
                            ) { onClearWatchHistory() }
                        },
                        sidebarR = categoryFocusRequesters[0],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 0
                        }
                    )

                    1 -> ConnectionSettingsContent(
                        backendType = prefs.backendType,
                        edcbIp = prefs.edcbIp,
                        edcbPort = prefs.edcbPort,
                        edcbHttpPort = prefs.edcbHttpPort,
                        epgStationIp = prefs.epgStationIp,
                        epgStationPort = prefs.epgStationPort,
                        kIp = prefs.konomiIp,
                        kPort = prefs.konomiPort,
                        mIp = prefs.mirakurunIp,
                        mPort = prefs.mirakurunPort,
                        prefSrc = prefs.preferredSource,
                        edcbPlayMethod = prefs.edcbRecordPlayMethod,
                        onSelectEdcbPlayMethod = {
                            val options = listOf(
                                "API経由 (api/xcode)" to "API",
                                "直接アクセス (高速シーク可)" to "DIRECT"
                            )
                            // ★ 修正: ダイアログのフォーカスバグを防ぐための安全装置を追加
                            val safeCurrent =
                                if (options.any { it.second == prefs.edcbRecordPlayMethod }) prefs.edcbRecordPlayMethod else "API"

                            uiState.activeDialog = SettingDialogState.Selection(
                                "録画ファイルの再生方式",
                                options,
                                safeCurrent
                            ) { newValue ->
                                scope.launch(Dispatchers.IO) {
                                    repository.saveString(
                                        SettingsRepository.EDCB_RECORD_PLAY_METHOD,
                                        newValue
                                    )
                                }
                                try {
                                    viewModel.updateEdcbRecordPlayMethod(newValue)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        edcbPlayMethodR = edcbPlayMethodR,
                        onEdit = { t, v ->
                            uiState.activeDialog = SettingDialogState.Input(t, v) { input ->
                                scope.launch(Dispatchers.IO) {
                                    when (t) {
                                        "KonomiTV (IPアドレス)" -> repository.saveString(
                                            SettingsRepository.KONOMI_IP,
                                            input
                                        )

                                        "KonomiTV (ポート)" -> repository.saveString(
                                            SettingsRepository.KONOMI_PORT,
                                            input
                                        )

                                        "EDCB (IPアドレス)" -> repository.saveString(
                                            SettingsRepository.EDCB_IP,
                                            input
                                        )

                                        "EDCB (TCPポート)" -> repository.saveString(
                                            SettingsRepository.EDCB_PORT,
                                            input
                                        )

                                        // ★ 追加: HTTPポートの保存処理を追加
                                        "EDCB (HTTP/HTTPSポート)" -> repository.saveString(
                                            SettingsRepository.EDCB_HTTP_PORT,
                                            input
                                        )

                                        "Mirakurun (IPアドレス)" -> repository.saveString(
                                            SettingsRepository.MIRAKURUN_IP,
                                            input
                                        )

                                        "Mirakurun (ポート)" -> repository.saveString(
                                            SettingsRepository.MIRAKURUN_PORT,
                                            input
                                        )
                                    }
                                }
                                try {
                                    when (t) {
                                        "KonomiTV (IPアドレス)" -> viewModel.updateKonomiIp(input)
                                        "KonomiTV (ポート)" -> viewModel.updateKonomiPort(input)
                                        "EDCB (IPアドレス)" -> viewModel.updateEdcbIp(input)
                                        "EDCB (TCPポート)" -> viewModel.updateEdcbPort(input)
                                        "Mirakurun (IPアドレス)" -> viewModel.updateMirakurunIp(
                                            input
                                        )
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onSelectBackend = {
                            val options = listOf(
                                "KonomiTV" to "KONOMITV",
                                "EDCB (EpgTimerSrv)" to "EDCB",
                                "Mirakurun (録画なし)" to "MIRAKURUN_ONLY"
                            )
                            val safeCurrent =
                                if (options.any { it.second == prefs.backendType }) prefs.backendType else "KONOMITV"

                            uiState.activeDialog = SettingDialogState.Selection(
                                "バックエンドシステムの選択",
                                options,
                                safeCurrent
                            ) { selectedType ->
                                scope.launch(Dispatchers.IO) {
                                    repository.saveString(
                                        SettingsRepository.BACKEND_TYPE,
                                        selectedType
                                    )
                                }
                                try {
                                    viewModel.updateBackendType(selectedType)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onSelectSrc = {
                            val options = mutableListOf(
                                "メインシステムに従う" to "KONOMITV",
                                "Mirakurun を優先" to "MIRAKURUN"
                            )
                            if (prefs.backendType != "EDCB") {
                                options.add("EDCB (TCP) を優先" to "EDCB")
                            } else {
                                options.add("EDCB (ダイレクトストリーミング)" to "EDCB")
                            }

                            val safeCurrent =
                                if (options.any { it.second == prefs.preferredSource }) {
                                    prefs.preferredSource
                                } else {
                                    "KONOMITV"
                                }

                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                                options,
                                safeCurrent
                            ) {
                                scope.launch(Dispatchers.IO) {
                                    repository.saveString(
                                        SettingsRepository.PREFERRED_STREAM_SOURCE,
                                        it
                                    )
                                }
                            }
                        },
                        backendTypeR = itemFocusRequesters[1][0],
                        backendIpR = itemFocusRequesters[1][1],
                        backendPortR = itemFocusRequesters[1][2],
                        edcbHttpPortR = itemFocusRequesters[1][3],
                        prefSrcR = itemFocusRequesters[1][4],
                        overrideIpR = itemFocusRequesters[1][5],
                        overridePortR = itemFocusRequesters[1][6],
                        sidebarR = categoryFocusRequesters[1],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 1
                        }
                    )

                    2 -> PlaybackSettingsContent(
                        liveQ = prefs.liveQuality,
                        videoQ = prefs.videoQuality,
                        liveSub = prefs.liveSubtitleDefault,
                        videoSub = prefs.videoSubtitleDefault,
                        layerOrder = prefs.subtitleCommentLayer,
                        audioMode = prefs.audioOutputMode,
                        uiMode = playerUiMode,
                        autoCmSkip = autoCmSkip,
                        availableQualities = availableQualities,
                        liveR = itemFocusRequesters[2][0],
                        videoR = itemFocusRequesters[2][1],
                        liveSubR = itemFocusRequesters[2][2],
                        videoSubR = itemFocusRequesters[2][3],
                        audioR = itemFocusRequesters[2][4],
                        layerR = itemFocusRequesters[2][5],
                        uiModeR = itemFocusRequesters[2][6],
                        autoCmSkipR = itemFocusRequesters[2][7],
                        sidebarR = categoryFocusRequesters[2],
                        onL = {
                            val options = availableQualities.map { it.label to it.value }
                            val safeCurrent =
                                if (options.any { it.second == prefs.liveQuality }) prefs.liveQuality
                                else availableQualities.firstOrNull()?.value
                                    ?: StreamQuality.DEFAULT_QUALITIES.first().value

                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_QUALITY_TITLE,
                                options,
                                safeCurrent
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.LIVE_QUALITY,
                                        it
                                    )
                                }
                            }
                        },
                        onV = {
                            val options = availableQualities.map { it.label to it.value }
                            val safeCurrent =
                                if (options.any { it.second == prefs.videoQuality }) prefs.videoQuality
                                else availableQualities.firstOrNull()?.value
                                    ?: StreamQuality.DEFAULT_QUALITIES.first().value

                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_QUALITY_TITLE,
                                options,
                                safeCurrent
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.VIDEO_QUALITY,
                                        it
                                    )
                                }
                            }
                        },
                        onLiveSub = {
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.LIVE_SUBTITLE_DEFAULT,
                                    if (prefs.liveSubtitleDefault == "ON") "OFF" else "ON"
                                )
                            }
                        },
                        onVideoSub = {
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.VIDEO_SUBTITLE_DEFAULT,
                                    if (prefs.videoSubtitleDefault == "ON") "OFF" else "ON"
                                )
                            }
                        },
                        onLayer = {
                            val options = listOf(
                                AppStrings.DIALOG_LAYER_COMMENT_TOP to "CommentOnTop",
                                AppStrings.DIALOG_LAYER_SUBTITLE_TOP to "SubtitleOnTop"
                            )
                            val safeCurrent =
                                if (options.any { it.second == prefs.subtitleCommentLayer }) prefs.subtitleCommentLayer else "CommentOnTop"
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_LAYER_ORDER_TITLE,
                                options,
                                safeCurrent
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.SUBTITLE_COMMENT_LAYER,
                                        it
                                    )
                                }
                            }
                        },
                        onAudioMode = {
                            val options = listOf(
                                AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX_DESC to "DOWNMIX",
                                AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH_DESC to "PASSTHROUGH"
                            )
                            val safeCurrent =
                                if (options.any { it.second == prefs.audioOutputMode }) prefs.audioOutputMode else "DOWNMIX"
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_AUDIO_OUTPUT_TITLE,
                                options,
                                safeCurrent
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.AUDIO_OUTPUT_MODE,
                                        it
                                    )
                                }
                            }
                        },
                        onUiMode = {
                            val options = listOf(
                                "モダン (オンスクリーン操作)" to "MODERN",
                                "クラシック (D-Pad完結)" to "CLASSIC"
                            )
                            val safeCurrent =
                                if (options.any { it.second == playerUiMode }) playerUiMode else "MODERN"
                            uiState.activeDialog = SettingDialogState.Selection(
                                "プレイヤーUIモード",
                                options,
                                safeCurrent
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.PLAYER_UI_MODE,
                                        it
                                    )
                                }
                            }
                        },
                        onAutoCmSkip = {
                            val options = listOf(
                                "有効" to "ON",
                                "無効" to "OFF"
                            )
                            val safeCurrent = if (autoCmSkip == "ON") "ON" else "OFF"
                            uiState.activeDialog = SettingDialogState.Selection(
                                "自動CMスキップ",
                                options,
                                safeCurrent
                            ) {
                                scope.launch {
                                    repository.saveString(SettingsRepository.AUTO_CM_SKIP, it)
                                }
                            }
                        },
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 2
                        }
                    )

                    3 -> RecordingSettingsContent(
                        batchList = prefs.postRecordingBatchList,
                        onAdd = {
                            uiState.activeDialog = SettingDialogState.BatchInput { n, p ->
                                viewModel.addPostRecordingBatch(n, p)
                            }
                        },
                        onDelete = { batch ->
                            uiState.activeDialog = SettingDialogState.ConfirmClear(
                                "バッチの削除",
                                "「${batch.name}」を削除しますか？"
                            ) { viewModel.deletePostRecordingBatch(batch) }
                        },
                        addR = itemFocusRequesters[3][0],
                        itemRs = batchItemRs,
                        sidebarR = categoryFocusRequesters[3],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 3
                        }
                    )

                    4 -> {
                        val isLightMode =
                            prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE"
                        val isDarkMode = !isLightMode
                        val currentSeason = when (prefs.currentThemeName) {
                            "SPRING", "SPRING_LIGHT" -> "SPRING"
                            "SUMMER", "SUMMER_LIGHT" -> "SUMMER"
                            "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"
                            "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"
                            else -> "DEFAULT"
                        }

                        HomeDisplaySettingsContent(
                            isDarkMode = isDarkMode,
                            themeSeason = currentSeason,
                            genre = prefs.pickupGenre,
                            excludePaid = prefs.excludePaid,
                            pickupTime = prefs.pickupTime,
                            startupTab = prefs.startupTab,
                            modeR = itemFocusRequesters[4][0],
                            colorR = itemFocusRequesters[4][1],
                            startR = itemFocusRequesters[4][2],
                            genreR = itemFocusRequesters[4][3],
                            timeR = itemFocusRequesters[4][4],
                            exPaidR = itemFocusRequesters[4][5],
                            sidebarR = categoryFocusRequesters[4],
                            onMode = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_BASE_THEME,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_THEME_DARK to "DARK",
                                        AppStrings.SETTINGS_VALUE_THEME_LIGHT to "LIGHT"
                                    ),
                                    if (isLightMode) "LIGHT" else "DARK"
                                ) {
                                    val nt = getThemeFromModeAndSeason(it == "DARK", currentSeason)
                                    scope.launch {
                                        repository.saveString(SettingsRepository.APP_THEME, nt)
                                    }
                                }
                            },
                            onColor = {
                                val options = listOf(
                                    AppStrings.SETTINGS_VALUE_SEASON_DEFAULT to "DEFAULT",
                                    AppStrings.SETTINGS_VALUE_SEASON_SPRING to "SPRING",
                                    AppStrings.SETTINGS_VALUE_SEASON_SUMMER to "SUMMER",
                                    AppStrings.SETTINGS_VALUE_SEASON_AUTUMN to "AUTUMN",
                                    AppStrings.SETTINGS_VALUE_SEASON_WINTER to "WINTER"
                                )
                                val safeCurrent =
                                    if (options.any { it.second == currentSeason }) currentSeason else "DEFAULT"
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_THEME_COLOR,
                                    options,
                                    safeCurrent
                                ) {
                                    val nt = getThemeFromModeAndSeason(isDarkMode, it)
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.APP_THEME,
                                            nt
                                        )
                                    }
                                }
                            },
                            onStart = {
                                val options = if (prefs.favoriteBaseballTeams.isNotEmpty()) {
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_TAB_HOME to "ホーム",
                                        AppStrings.SETTINGS_VALUE_TAB_LIVE to "ライブ",
                                        AppStrings.SETTINGS_VALUE_TAB_VIDEO to "ビデオ",
                                        AppStrings.SETTINGS_VALUE_TAB_EPG to "番組表",
                                        AppStrings.SETTINGS_VALUE_TAB_RESERVE to "録画予約",
                                        "プロ野球" to "プロ野球"
                                    )
                                } else {
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_TAB_HOME to "ホーム",
                                        AppStrings.SETTINGS_VALUE_TAB_LIVE to "ライブ",
                                        AppStrings.SETTINGS_VALUE_TAB_VIDEO to "ビデオ",
                                        AppStrings.SETTINGS_VALUE_TAB_EPG to "番組表",
                                        AppStrings.SETTINGS_VALUE_TAB_RESERVE to "録画予約"
                                    )
                                }

                                val safeCurrent =
                                    if (options.any { it.second == prefs.startupTab }) prefs.startupTab else "ホーム"

                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                    options,
                                    safeCurrent
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.STARTUP_TAB,
                                            it
                                        )
                                    }
                                }
                            },
                            onG = {
                                val options = listOf(
                                    AppStrings.SETTINGS_GENRE_ANIME to "アニメ",
                                    AppStrings.SETTINGS_GENRE_MOVIE to "映画",
                                    AppStrings.SETTINGS_GENRE_DRAMA to "ドラマ",
                                    AppStrings.SETTINGS_GENRE_SPORTS to "スポーツ",
                                    AppStrings.SETTINGS_GENRE_MUSIC to "音楽",
                                    AppStrings.SETTINGS_GENRE_VARIETY to "バラエティ",
                                    AppStrings.SETTINGS_GENRE_DOCUMENTARY to "ドキュメンタリー"
                                )
                                val safeCurrent =
                                    if (options.any { it.second == prefs.pickupGenre }) prefs.pickupGenre else "アニメ"
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_PICKUP_GENRE_TITLE,
                                    options,
                                    safeCurrent
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.HOME_PICKUP_GENRE,
                                            it
                                        )
                                    }
                                }
                            },
                            onTime = {
                                val options = listOf(
                                    AppStrings.SETTINGS_TIME_AUTO to "自動",
                                    AppStrings.SETTINGS_TIME_MORNING to "朝",
                                    AppStrings.SETTINGS_TIME_NOON to "昼",
                                    AppStrings.SETTINGS_TIME_NIGHT to "夜"
                                )
                                val safeCurrent =
                                    if (options.any { it.second == prefs.pickupTime }) prefs.pickupTime else "自動"
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_PICKUP_TIME_TITLE,
                                    options,
                                    safeCurrent
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.HOME_PICKUP_TIME,
                                            it
                                        )
                                    }
                                }
                            },
                            onExPaid = {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.EXCLUDE_PAID_BROADCASTS,
                                        if (prefs.excludePaid == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            onClick = {
                                uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 4
                            }
                        )
                    }

                    5 -> {
                        val channelName = when (prefs.startupChannel) {
                            "OFF" -> AppStrings.SETTINGS_VALUE_STARTUP_OFF
                            "LAST_WATCHED" -> AppStrings.SETTINGS_VALUE_STARTUP_LAST
                            else -> flatChannels.find { it.id == prefs.startupChannel }?.name
                                ?: prefs.startupChannel
                        }

                        DisplaySettingsContent(
                            preferences = prefs,
                            startupChannelName = channelName,
                            sidebarR = categoryFocusRequesters[5],
                            onEditTab = {
                                val options = if (prefs.favoriteBaseballTeams.isNotEmpty()) {
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_TAB_HOME to "ホーム",
                                        AppStrings.SETTINGS_VALUE_TAB_LIVE to "ライブ",
                                        AppStrings.SETTINGS_VALUE_TAB_VIDEO to "ビデオ",
                                        AppStrings.SETTINGS_VALUE_TAB_EPG to "番組表",
                                        AppStrings.SETTINGS_VALUE_TAB_RESERVE to "録画予約",
                                        "プロ野球" to "プロ野球"
                                    )
                                } else {
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_TAB_HOME to "ホーム",
                                        AppStrings.SETTINGS_VALUE_TAB_LIVE to "ライブ",
                                        AppStrings.SETTINGS_VALUE_TAB_VIDEO to "ビデオ",
                                        AppStrings.SETTINGS_VALUE_TAB_EPG to "番組表",
                                        AppStrings.SETTINGS_VALUE_TAB_RESERVE to "録画予約"
                                    )
                                }

                                val safeCurrent =
                                    if (options.any { it.second == prefs.startupTab }) prefs.startupTab else "ホーム"

                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                    options,
                                    safeCurrent
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.STARTUP_TAB,
                                            it
                                        )
                                    }
                                }
                            },
                            onEditStartupChannel = {
                                val baseOptions = listOf(
                                    AppStrings.SETTINGS_VALUE_STARTUP_OFF to "OFF",
                                    AppStrings.SETTINGS_VALUE_STARTUP_LAST to "LAST_WATCHED"
                                )
                                val channelOptions = flatChannels.map { it.name to it.id }
                                val allOptions = baseOptions + channelOptions
                                val safeCurrent =
                                    if (allOptions.any { it.second == prefs.startupChannel }) prefs.startupChannel else "OFF"

                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_STARTUP_CHANNEL_TITLE,
                                    allOptions,
                                    safeCurrent
                                ) {
                                    viewModel.updateStartupChannel(it)
                                }
                            },
                            onEditDefaultView = {
                                val options = listOf(
                                    AppStrings.SETTINGS_VALUE_VIEW_LIST to "LIST",
                                    AppStrings.SETTINGS_VALUE_VIEW_GRID to "GRID"
                                )
                                val safeCurrent =
                                    if (options.any { it.second == prefs.defaultRecordListView }) prefs.defaultRecordListView else "LIST"
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                                    options,
                                    safeCurrent
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                                            it
                                        )
                                    }
                                }
                            },
                            onEditTimeFormat = {
                                val options = listOf(
                                    "24時間表記" to "24H",
                                    "12時間表記 (AM/PM)" to "12H"
                                )
                                val safeCurrent =
                                    if (options.any { it.second == prefs.timeFormat }) prefs.timeFormat else "24H"
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "時刻の表示形式",
                                    options,
                                    safeCurrent
                                ) {
                                    viewModel.updateTimeFormat(it)
                                }
                            },
                            onToggleHideSubChannels = {
                                viewModel.toggleHideSubChannels()
                            },
                            itemRs = itemFocusRequesters[5].dropLast(1),
                            hideSubChannelsR = itemFocusRequesters[5].last(),
                            onClick = {
                                uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 5
                            }
                        )
                    }

                    6 -> CommentSettingsContent(
                        def = prefs.commentDefaultDisplay,
                        speed = prefs.commentSpeed,
                        size = prefs.commentFontSize,
                        opacity = prefs.commentOpacity,
                        max = prefs.commentMaxLines,
                        onEdit = { t, v ->
                            uiState.activeDialog = SettingDialogState.Input(
                                t,
                                v
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        if (t == AppStrings.SETTINGS_INPUT_COMMENT_SPEED) SettingsRepository.COMMENT_SPEED else if (t == AppStrings.SETTINGS_INPUT_COMMENT_SIZE) SettingsRepository.COMMENT_FONT_SIZE else if (t == AppStrings.SETTINGS_INPUT_COMMENT_OPACITY) SettingsRepository.COMMENT_OPACITY else SettingsRepository.COMMENT_MAX_LINES,
                                        it
                                    )
                                }
                            }
                        },
                        onT = {
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.COMMENT_DEFAULT_DISPLAY,
                                    if (prefs.commentDefaultDisplay == "ON") "OFF" else "ON"
                                )
                            }
                        },
                        defR = itemFocusRequesters[6][0],
                        spR = itemFocusRequesters[6][1],
                        szR = itemFocusRequesters[6][2],
                        opR = itemFocusRequesters[6][3],
                        mxR = itemFocusRequesters[6][4],
                        sidebarR = categoryFocusRequesters[6],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 6
                        }
                    )

                    7 -> LabSettingsContent(
                        apiKey = prefs.geminiApiKey,
                        baseball = prefs.favoriteBaseballTeams,
                        mirakurunDual = prefs.labAllowMirakurunDual,
                        dualR = itemFocusRequesters[7][0],
                        baseballR = itemFocusRequesters[7][1],
                        apiKeyR = itemFocusRequesters[7][2],
                        sidebarR = categoryFocusRequesters[7],
                        onEditApiKey = {
                            uiState.activeDialog = SettingDialogState.GeminiSetup
                        },
                        onBaseball = {
                            val npbTeams = listOf(
                                "阪神タイガース" to "阪神",
                                "広島東洋カープ" to "広島",
                                "横浜DeNAベイスターズ" to "DeNA",
                                "読売ジャイアンツ" to "巨人",
                                "東京ヤクルトスワローズ" to "ヤクルト",
                                "中日ドラゴンズ" to "中日",
                                "オリックス・バファローズ" to "オリックス",
                                "千葉ロッテマリーンズ" to "ロッテ",
                                "福岡ソフトバンクホークス" to "ソフトバンク",
                                "東北楽天ゴールデンイーグルス" to "楽天",
                                "埼玉西武ライオンズ" to "西武",
                                "北海道日本ハムファイターズ" to "日本ハム",
                                "侍ジャパン" to "侍ジャパン"
                            )
                            uiState.activeDialog = SettingDialogState.MultiSelection(
                                "フォロー球団の選択",
                                npbTeams,
                                prefs.favoriteBaseballTeams
                            ) { selectedTeams ->
                                viewModel.updateFavoriteBaseballTeams(selectedTeams)
                            }
                        },
                        onToggleMirakurunDual = {
                            if (prefs.labAllowMirakurunDual == "OFF") {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    title = "【警告】ハードウェア負荷について",
                                    message = "Mirakurunソース（生TS）での2画面再生やPiPモードは、端末のハードウェアデコーダーとメモリに極めて高い負荷をかけます。\n\n" +
                                            "Fire TV Stick等の低スペック端末では、画面が緑色になったり、アプリがクラッシュする可能性があります。\n\n" +
                                            "このリスクを理解した上で有効にしますか？"
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.LAB_ALLOW_MIRAKURUN_DUAL,
                                            "ON"
                                        )
                                    }
                                }
                            } else {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.LAB_ALLOW_MIRAKURUN_DUAL,
                                        "OFF"
                                    )
                                }
                            }
                        },
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 7
                        }
                    )

                    8 -> AppInfoContent(
                        onShow = {
                            uiState.activeDialog = SettingDialogState.Licenses
                        },
                        licR = itemFocusRequesters[8][0],
                        sidebarR = categoryFocusRequesters[8],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 8
                        })
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    when (val state = uiState.activeDialog) {
        is SettingDialogState.Input -> InputDialog(
            title = state.title,
            initialValue = state.initialValue,
            onDismiss = { closeDialog() },
            onConfirm = { state.onConfirm(it); closeDialog() })

        is SettingDialogState.BatchInput -> BatchInputDialog(
            onDismiss = { closeDialog() },
            onConfirm = { n, p -> state.onConfirm(n, p); closeDialog() })

        is SettingDialogState.Selection -> SelectionDialog(
            title = state.title,
            options = state.options,
            current = state.current,
            onDismiss = { closeDialog() },
            onSelect = { state.onSelect(it); closeDialog() })

        is SettingDialogState.MultiSelection -> MultiSelectionDialog(
            title = state.title,
            options = state.options,
            currentSelections = state.currentSelections,
            onDismiss = { closeDialog() },
            onConfirm = { state.onConfirm(it); closeDialog() }
        )

        is SettingDialogState.ConfirmClear -> ConfirmClearDialog(
            title = state.title,
            message = state.message,
            confirmButtonText = if (state.title.contains("警告")) "有効にする" else "削除",
            onConfirm = { state.onConfirm(); closeDialog() },
            onDismiss = { closeDialog() })

        is SettingDialogState.Licenses -> OpenSourceLicensesScreen(onBack = { closeDialog() })

        is SettingDialogState.GeminiSetup -> {
            val localIp by viewModel.localIpAddress.collectAsState()
            GeminiSetupDialog(
                currentKey = prefs.geminiApiKey,
                serverIp = localIp,
                onStartServer = { viewModel.startGeminiLocalServer() },
                onStopServer = { viewModel.stopGeminiLocalServer() },
                onDismiss = { closeDialog() },
                onManualInputClick = {
                    viewModel.stopGeminiLocalServer()
                    uiState.activeDialog = SettingDialogState.Input(
                        "Gemini API Key",
                        prefs.geminiApiKey
                    ) { key ->
                        scope.launch {
                            repository.saveString(SettingsRepository.GEMINI_API_KEY, key)
                        }
                    }
                },
                onDeleteKey = {
                    viewModel.stopGeminiLocalServer()
                    scope.launch {
                        repository.saveString(SettingsRepository.GEMINI_API_KEY, "")
                    }
                    closeDialog()
                }
            )
        }

        else -> {}
    }
}