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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val colors = KomorebiTheme.colors
    val currentTime = remember { LocalTime.now() }
    val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

    val prefs = rememberSettingPreferences(repository)
    val uiState = rememberSettingUiState()

    val categories = listOf(
        Category(AppStrings.SETTINGS_CATEGORY_GENERAL, Icons.Default.SettingsApplications),
        Category(AppStrings.SETTINGS_CATEGORY_CONNECTION, Icons.Default.CastConnected),
        Category(AppStrings.SETTINGS_CATEGORY_PLAYBACK, Icons.Default.PlayCircle),
        Category("録画設定", Icons.Default.VideoSettings), // ★追加
        Category(AppStrings.SETTINGS_CATEGORY_HOME, Icons.Default.Home),
        Category(AppStrings.SETTINGS_CATEGORY_DISPLAY, Icons.Default.Dashboard),
        Category(AppStrings.SETTINGS_CATEGORY_COMMENT, Icons.Default.Tv),
        Category(AppStrings.SETTINGS_CATEGORY_LAB, Icons.Default.Science),
        Category(AppStrings.SETTINGS_CATEGORY_APP_INFO, Icons.Default.Info)
    )
    val categoryFocusRequesters = remember { List(categories.size) { FocusRequester() } }

    // ★修正: バッチリストの数に応じてRequestersを調整
    val batchItemRs =
        remember(prefs.postRecordingBatchList) { List(prefs.postRecordingBatchList.size) { FocusRequester() } }

    val itemFocusRequesters = remember {
        listOf(
            listOf(FocusRequester(), FocusRequester()), // 0: General
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 1: Connection
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 2: Playback
            listOf(FocusRequester()), // 3: Recording (Addボタン分) - 動的リストはbatchItemRsで管理
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 4: Home
            listOf(FocusRequester(), FocusRequester()), // 5: Display
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 6: Comment
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ), // 7: Lab
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
            Column(modifier = Modifier
                .fillMaxSize()
                .verticalScroll(mainScrollState)) {
                when (uiState.selectedCategoryIndex) {
                    0 -> GeneralSettingsContent(
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
                        clearChannelR = itemFocusRequesters[0][0],
                        clearHistoryR = itemFocusRequesters[0][1],
                        sidebarR = categoryFocusRequesters[0],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 0
                        }
                    )

                    1 -> ConnectionSettingsContent(
                        kIp = prefs.konomiIp,
                        kPort = prefs.konomiPort,
                        mIp = prefs.mirakurunIp,
                        mPort = prefs.mirakurunPort,
                        prefSrc = prefs.preferredSource,
                        onEdit = { t, v ->
                            uiState.activeDialog = SettingDialogState.Input(
                                t,
                                v
                            ) {
                                if (t == AppStrings.SETTINGS_INPUT_KONOMITV_ADDRESS) viewModel.updateKonomiIp(
                                    it
                                ) else if (t == AppStrings.SETTINGS_INPUT_KONOMITV_PORT) viewModel.updateKonomiPort(
                                    it
                                ) else scope.launch {
                                    repository.saveString(
                                        if (t == AppStrings.SETTINGS_INPUT_MIRAKURUN_ADDRESS) SettingsRepository.MIRAKURUN_IP else SettingsRepository.MIRAKURUN_PORT,
                                        it
                                    )
                                }
                            }
                        },
                        onSelectSrc = {
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                                if (prefs.mirakurunIp.isBlank()) listOf(AppStrings.SETTINGS_VALUE_SOURCE_KONOMITV_FIXED to "KONOMITV") else listOf(
                                    AppStrings.SETTINGS_VALUE_SOURCE_KONOMITV_PREFERRED to "KONOMITV",
                                    AppStrings.SETTINGS_VALUE_SOURCE_MIRAKURUN_PREFERRED to "MIRAKURUN"
                                ),
                                prefs.preferredSource
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.PREFERRED_STREAM_SOURCE,
                                        it
                                    )
                                }
                            }
                        },
                        kIpR = itemFocusRequesters[1][0],
                        kPortR = itemFocusRequesters[1][1],
                        mIpR = itemFocusRequesters[1][2],
                        mPortR = itemFocusRequesters[1][3],
                        prefSrcR = itemFocusRequesters[1][4],
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
                        liveR = itemFocusRequesters[2][0],
                        videoR = itemFocusRequesters[2][1],
                        liveSubR = itemFocusRequesters[2][2],
                        videoSubR = itemFocusRequesters[2][3],
                        audioR = itemFocusRequesters[2][4],
                        layerR = itemFocusRequesters[2][5],
                        sidebarR = categoryFocusRequesters[2],
                        onL = {
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_QUALITY_TITLE,
                                StreamQuality.entries.map { it.label to it.value },
                                prefs.liveQuality
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
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_QUALITY_TITLE,
                                StreamQuality.entries.map { it.label to it.value },
                                prefs.videoQuality
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
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_LAYER_ORDER_TITLE,
                                listOf(
                                    AppStrings.DIALOG_LAYER_COMMENT_TOP to "CommentOnTop",
                                    AppStrings.DIALOG_LAYER_SUBTITLE_TOP to "SubtitleOnTop"
                                ),
                                prefs.subtitleCommentLayer
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
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_AUDIO_OUTPUT_TITLE,
                                listOf(
                                    AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX_DESC to "DOWNMIX",
                                    AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH_DESC to "PASSTHROUGH"
                                ),
                                prefs.audioOutputMode
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.AUDIO_OUTPUT_MODE,
                                        it
                                    )
                                }
                            }
                        },
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 2
                        }
                    )
                    // ★追加: 録画設定 (カテゴリー3)
                    3 -> RecordingSettingsContent(
                        batchList = prefs.postRecordingBatchList,
                        onAdd = {
                            uiState.activeDialog = SettingDialogState.BatchInput { n, p ->
                                viewModel.addPostRecordingBatch(
                                    n,
                                    p
                                )
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
                    // 以降のインデックスを+1
                    4 -> HomeDisplaySettingsContent(
                        isDarkMode = !prefs.currentThemeName.contains("LIGHT"),
                        themeSeason = when (prefs.currentThemeName) {
                            "SPRING", "SPRING_LIGHT" -> "SPRING"; "SUMMER", "SUMMER_LIGHT" -> "SUMMER"; "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"; "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"; else -> "DEFAULT"
                        },
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
                                if (prefs.currentThemeName.contains("LIGHT")) "LIGHT" else "DARK"
                            ) {
                                val nt = getThemeFromModeAndSeason(
                                    it == "DARK", when (prefs.currentThemeName) {
                                        "SPRING", "SPRING_LIGHT" -> "SPRING"; "SUMMER", "SUMMER_LIGHT" -> "SUMMER"; "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"; "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"; else -> "DEFAULT"
                                    }
                                ); scope.launch {
                                repository.saveString(
                                    SettingsRepository.APP_THEME,
                                    nt
                                )
                            }
                            }
                        },
                        onColor = {
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.SETTINGS_ITEM_THEME_COLOR,
                                listOf(
                                    AppStrings.SETTINGS_VALUE_SEASON_DEFAULT to "DEFAULT",
                                    AppStrings.SETTINGS_VALUE_SEASON_SPRING to "SPRING",
                                    AppStrings.SETTINGS_VALUE_SEASON_SUMMER to "SUMMER",
                                    AppStrings.SETTINGS_VALUE_SEASON_AUTUMN to "AUTUMN",
                                    AppStrings.SETTINGS_VALUE_SEASON_WINTER to "WINTER"
                                ),
                                when (prefs.currentThemeName) {
                                    "SPRING", "SPRING_LIGHT" -> "SPRING"; "SUMMER", "SUMMER_LIGHT" -> "SUMMER"; "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"; "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"; else -> "DEFAULT"
                                }
                            ) {
                                val nt = getThemeFromModeAndSeason(
                                    !prefs.currentThemeName.contains("LIGHT"),
                                    it
                                ); scope.launch {
                                repository.saveString(
                                    SettingsRepository.APP_THEME,
                                    nt
                                )
                            }
                            }
                        },
                        onStart = {
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                listOf(
                                    AppStrings.SETTINGS_VALUE_TAB_HOME to "ホーム",
                                    AppStrings.SETTINGS_VALUE_TAB_LIVE to "ライブ",
                                    AppStrings.SETTINGS_VALUE_TAB_VIDEO to "ビデオ",
                                    AppStrings.SETTINGS_VALUE_TAB_EPG to "番組表",
                                    AppStrings.SETTINGS_VALUE_TAB_RESERVE to "録画予約"
                                ),
                                prefs.startupTab
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
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_PICKUP_GENRE_TITLE,
                                listOf(
                                    AppStrings.SETTINGS_GENRE_ANIME to "アニメ",
                                    AppStrings.SETTINGS_GENRE_MOVIE to "映画",
                                    AppStrings.SETTINGS_GENRE_DRAMA to "ドラマ",
                                    AppStrings.SETTINGS_GENRE_SPORTS to "スポーツ",
                                    AppStrings.SETTINGS_GENRE_MUSIC to "音楽",
                                    AppStrings.SETTINGS_GENRE_VARIETY to "バラエティ",
                                    AppStrings.SETTINGS_GENRE_DOCUMENTARY to "ドキュメンタリー"
                                ),
                                prefs.pickupGenre
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
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.DIALOG_PICKUP_TIME_TITLE,
                                listOf(
                                    AppStrings.SETTINGS_TIME_AUTO to "自動",
                                    AppStrings.SETTINGS_TIME_MORNING to "朝",
                                    AppStrings.SETTINGS_TIME_NOON to "昼",
                                    AppStrings.SETTINGS_TIME_NIGHT to "夜"
                                ),
                                prefs.pickupTime
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

                    5 -> DisplaySettingsContent(
                        preferences = prefs,
                        sidebarR = categoryFocusRequesters[5],
                        onEditTab = {
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                listOf(
                                    AppStrings.SETTINGS_VALUE_TAB_HOME to "ホーム",
                                    AppStrings.SETTINGS_VALUE_TAB_LIVE to "ライブ",
                                    AppStrings.SETTINGS_VALUE_TAB_VIDEO to "ビデオ",
                                    AppStrings.SETTINGS_VALUE_TAB_EPG to "番組表",
                                    AppStrings.SETTINGS_VALUE_TAB_RESERVE to "録画予約"
                                ),
                                prefs.startupTab
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.STARTUP_TAB,
                                        it
                                    )
                                }
                            }
                        },
                        onEditDefaultView = {
                            uiState.activeDialog = SettingDialogState.Selection(
                                AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                                listOf(
                                    AppStrings.SETTINGS_VALUE_VIEW_LIST to "LIST",
                                    AppStrings.SETTINGS_VALUE_VIEW_GRID to "GRID"
                                ),
                                prefs.defaultRecordListView
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                                        it
                                    )
                                }
                            }
                        },
                        itemRs = itemFocusRequesters[5],
                        onClick = {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 5
                        }
                    )

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
                        annict = prefs.labAnnict,
                        shobocal = prefs.labShobocal,
                        postCmd = prefs.defaultPostCommand,
                        enableAi = prefs.enableAiNormalization,
                        apiKey = prefs.geminiApiKey,
                        annictR = itemFocusRequesters[7][0],
                        shobocalR = itemFocusRequesters[7][1],
                        cmdR = itemFocusRequesters[7][2],
                        enableAiR = itemFocusRequesters[7][3],
                        apiKeyR = itemFocusRequesters[7][4],
                        sidebarR = categoryFocusRequesters[7],
                        onAnnict = {
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.LAB_ANNICT_INTEGRATION,
                                    if (prefs.labAnnict == "ON") "OFF" else "ON"
                                )
                            }
                        },
                        onShobocal = {
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.LAB_SHOBOCAL_INTEGRATION,
                                    if (prefs.labShobocal == "ON") "OFF" else "ON"
                                )
                            }
                        },
                        onEditCmd = {
                            uiState.activeDialog = SettingDialogState.Input(
                                AppStrings.SETTINGS_INPUT_POST_COMMAND,
                                prefs.defaultPostCommand
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.DEFAULT_POST_COMMAND,
                                        it
                                    )
                                }
                            }
                        },
                        onToggleAi = {
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.ENABLE_AI_NORMALIZATION,
                                    if (prefs.enableAiNormalization == "ON") "OFF" else "ON"
                                )
                            }
                        },
                        onEditApiKey = {
                            uiState.activeDialog = SettingDialogState.Input(
                                "Gemini API Key",
                                prefs.geminiApiKey
                            ) {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.GEMINI_API_KEY,
                                        it
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
        // ★追加: バッチ入力
        is SettingDialogState.BatchInput -> BatchInputDialog(
            onDismiss = { closeDialog() },
            onConfirm = { n, p -> state.onConfirm(n, p); closeDialog() })

        is SettingDialogState.Selection -> SelectionDialog(
            title = state.title,
            options = state.options,
            current = state.current,
            onDismiss = { closeDialog() },
            onSelect = { state.onSelect(it); closeDialog() })

        is SettingDialogState.ConfirmClear -> ConfirmClearDialog(
            title = state.title,
            message = state.message,
            onConfirm = { state.onConfirm(); closeDialog() },
            onDismiss = { closeDialog() })

        is SettingDialogState.Licenses -> OpenSourceLicensesScreen(onBack = { closeDialog() })
        else -> {}
    }
}