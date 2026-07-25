@file:OptIn(ExperimentalComposeUiApi::class)

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
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.LocalTime

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    initialCategoryIndex: Int = 0,
    initialFocusItemIndex: Int? = null,
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
    val uiState = rememberSettingUiState(initialCategoryIndex)

    val totalRecordCount by viewModel.totalRecordCount.collectAsState()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsState()
    val receiveBetaUpdates by viewModel.receiveBetaUpdates.collectAsState()
    val isValidatingGeminiApiKey by viewModel.isValidatingGeminiApiKey.collectAsState()
    val playerUiMode by viewModel.playerUiMode.collectAsState()
    val autoCmSkip by viewModel.autoCmSkip.collectAsState()
    val availableQualities by viewModel.availableQualities.collectAsState()
    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3500)
            toastMessage = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.smbServerAddedEvent.collect { name ->
            toastMessage = "設定を受信しました！\n「$name」"
        }
    }

    val categories = listOf(
        Category(AppStrings.SETTINGS_CATEGORY_GENERAL, Icons.Default.SettingsApplications),
        Category(AppStrings.SETTINGS_CATEGORY_CONNECTION, Icons.Default.CastConnected),
        Category(AppStrings.SETTINGS_CATEGORY_PLAYBACK, Icons.Default.PlayCircle),
        Category("録画設定", Icons.Default.VideoSettings),
        Category(AppStrings.SETTINGS_CATEGORY_HOME, Icons.Default.Home),
        Category(AppStrings.SETTINGS_CATEGORY_DISPLAY, Icons.Default.Dashboard),
        Category("番組表設定", Icons.Default.GridOn),
        Category(AppStrings.SETTINGS_CATEGORY_COMMENT, Icons.Default.Tv),
        Category(AppStrings.SETTINGS_CATEGORY_LAB, Icons.Default.Science),
        Category(AppStrings.SETTINGS_CATEGORY_APP_INFO, Icons.Default.Info)
    )
    val categoryFocusRequesters = remember { List(categories.size) { FocusRequester() } }
    val homeBackRequester = remember { FocusRequester() }

    val batchItemRs =
        remember(prefs.postRecordingBatchList) { List(prefs.postRecordingBatchList.size) { FocusRequester() } }
    val edcbPlayMethodR = remember { FocusRequester() }
    val smbItemRs =
        remember(prefs.smbServerList) { List(prefs.smbServerList.size) { FocusRequester() } }

    val itemFocusRequesters = remember {
        listOf(
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ),
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                // ★ 追加: Cloudflare Zero Trust (Client ID / Secret) 用に2個増強
                FocusRequester(),
                FocusRequester()
            ), // 1: Connection
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ),
            listOf(FocusRequester()),
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ),
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ),
            listOf(FocusRequester(), FocusRequester(), FocusRequester(), FocusRequester()),
            listOf(
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester(),
                FocusRequester()
            ),
            listOf(FocusRequester(), FocusRequester(), FocusRequester()),
            listOf(FocusRequester())
        )
    }

    val mainScrollState = rememberScrollState()
    val sidebarScrollState = rememberScrollState()

    LaunchedEffect(uiState.selectedCategoryIndex) {
        if (initialFocusItemIndex == null || uiState.selectedCategoryIndex != initialCategoryIndex) {
            mainScrollState.scrollTo(0)
        }
    }

    LaunchedEffect(Unit) {
        delay(400)

        if (initialFocusItemIndex != null) {
            uiState.isSidebarFocused = false
            val targetRequester = itemFocusRequesters.getOrNull(initialCategoryIndex)
                ?.getOrNull(initialFocusItemIndex)

            var success = false
            for (i in 0..5) {
                try {
                    targetRequester?.requestFocus()
                    success = true
                    break
                } catch (e: Exception) {
                    delay(150)
                }
            }

            if (!success) {
                categoryFocusRequesters.getOrNull(initialCategoryIndex)
                    ?.safeRequestFocus("Settings_Fallback")
            }
        } else {
            categoryFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
                ?.safeRequestFocus("Settings_Initial")
        }
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                                .focusProperties {
                                    left = FocusRequester.Cancel // ★ 修正: 左キーでフォーカスが迷子になるのを防ぐ
                                    right = targetR
                                    if (index == 0) up = FocusRequester.Cancel
                                    if (index == categories.lastIndex) down = homeBackRequester
                                }
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
                    modifier = Modifier
                        .focusRequester(homeBackRequester)
                        .focusProperties {
                            left = FocusRequester.Cancel // ★ 修正: こちらも左キーへの防波堤を追加
                            up = categoryFocusRequesters.lastOrNull() ?: FocusRequester.Default
                            down = FocusRequester.Cancel
                            right = itemFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
                                ?.firstOrNull() ?: FocusRequester.Default
                        }
                )
            }

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
                            totalRecordCount,
                            lastSyncedAt,
                            receiveBetaUpdates,
                            {
                                scope.launch {
                                    repository.saveBoolean(
                                        SettingsRepository.RECEIVE_BETA_UPDATES,
                                        it
                                    )
                                }
                            },
                            itemFocusRequesters[0][0],
                            {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "データベースの再構築",
                                    "すべての録画データをサーバーから再取得します。よろしいですか？"
                                ) { viewModel.triggerFullSync() }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                                    AppStrings.DIALOG_CLEAR_CHANNEL_HISTORY_MSG
                                ) { onClearLastChannel() }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                                    AppStrings.DIALOG_CLEAR_WATCH_HISTORY_MSG
                                ) { onClearWatchHistory() }
                            },
                            itemFocusRequesters[0][1],
                            itemFocusRequesters[0][2],
                            itemFocusRequesters[0][3],
                            itemFocusRequesters[0][4],
                            categoryFocusRequesters[0]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 0 }

                        1 -> ConnectionSettingsContent(
                            prefs.backendType,
                            prefs.edcbIp,
                            prefs.edcbPort,
                            prefs.edcbHttpPort,
                            prefs.epgStationIp,
                            prefs.epgStationPort,
                            prefs.konomiIp,
                            prefs.konomiPort,
                            prefs.mirakurunIp,
                            prefs.mirakurunPort,
                            prefs.preferredSource,
                            prefs.edcbRecordPlayMethod,
                            prefs.smbServerList,
                            { uiState.activeDialog = SettingDialogState.SmbSetup(null) },
                            { uiState.activeDialog = SettingDialogState.SmbAction(it) },
                            itemFocusRequesters[1][7],
                            smbItemRs,
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "録画ファイルの再生方式",
                                    listOf(
                                        "API経由 (api/xcode)" to "API",
                                        "直接アクセス (高速シーク可)" to "DIRECT"
                                    ),
                                    if (listOf(
                                            "API",
                                            "DIRECT"
                                        ).any { it == prefs.edcbRecordPlayMethod }
                                    ) prefs.edcbRecordPlayMethod else "API"
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        repository.saveString(
                                            SettingsRepository.EDCB_RECORD_PLAY_METHOD,
                                            it
                                        )
                                    }
                                    viewModel.updateEdcbRecordPlayMethod(it)
                                }
                            },
                            edcbPlayMethodR,
                            prefs.cfAccessClientId,
                            prefs.cfAccessClientSecret,
                            { t, v ->
                                uiState.activeDialog = SettingDialogState.Input(
                                    title = t,
                                    initialValue = v,
                                    isLongToken = t == AppStrings.SETTINGS_INPUT_CF_CLIENT_ID || t == AppStrings.SETTINGS_INPUT_CF_CLIENT_SECRET,
                                    placeholder = when (t) {
                                        AppStrings.SETTINGS_INPUT_CF_CLIENT_ID -> AppStrings.SETTINGS_PLACEHOLDER_CF_CLIENT_ID
                                        AppStrings.SETTINGS_INPUT_CF_CLIENT_SECRET -> AppStrings.SETTINGS_PLACEHOLDER_CF_CLIENT_SECRET
                                        else -> null
                                    }
                                ) { input ->
                                    scope.launch(Dispatchers.IO) {
                                        // ★ 追加: CF Access のトークンには空白・改行は含まれ得ないため、
                                        // TV の画面キーボードが誤って挿入した改行等も除去する
                                        val sanitizedInput =
                                            if (t == AppStrings.SETTINGS_INPUT_CF_CLIENT_ID || t == AppStrings.SETTINGS_INPUT_CF_CLIENT_SECRET)
                                                input.replace(Regex("\\s+"), "")
                                            else input
                                        when (t) {
                                            "KonomiTV (IPアドレス)" -> repository.saveString(
                                                SettingsRepository.KONOMI_IP,
                                                sanitizedInput
                                            )

                                            "KonomiTV (ポート)" -> repository.saveString(
                                                SettingsRepository.KONOMI_PORT,
                                                sanitizedInput
                                            )

                                            "EDCB (IPアドレス)" -> repository.saveString(
                                                SettingsRepository.EDCB_IP,
                                                sanitizedInput
                                            )

                                            "EDCB (TCPポート)" -> repository.saveString(
                                                SettingsRepository.EDCB_PORT,
                                                sanitizedInput
                                            )

                                            "EDCB (HTTP/HTTPSポート)" -> repository.saveString(
                                                SettingsRepository.EDCB_HTTP_PORT,
                                                sanitizedInput
                                            )

                                            "Mirakurun (IPアドレス)" -> repository.saveString(
                                                SettingsRepository.MIRAKURUN_IP,
                                                sanitizedInput
                                            )

                                            "Mirakurun (ポート)" -> repository.saveString(
                                                SettingsRepository.MIRAKURUN_PORT,
                                                sanitizedInput
                                            )

                                            AppStrings.SETTINGS_INPUT_CF_CLIENT_ID -> repository.saveString(
                                                SettingsRepository.CF_ACCESS_CLIENT_ID,
                                                sanitizedInput
                                            )

                                            AppStrings.SETTINGS_INPUT_CF_CLIENT_SECRET -> repository.saveString(
                                                SettingsRepository.CF_ACCESS_CLIENT_SECRET,
                                                sanitizedInput
                                            )
                                        }
                                    }
                                    when (t) {
                                        "KonomiTV (IPアドレス)" -> viewModel.updateKonomiIp(input)
                                        "KonomiTV (ポート)" -> viewModel.updateKonomiPort(input)
                                        "EDCB (IPアドレス)" -> viewModel.updateEdcbIp(input)
                                        "EDCB (TCPポート)" -> viewModel.updateEdcbPort(input)
                                        "Mirakurun (IPアドレス)" -> viewModel.updateMirakurunIp(
                                            input
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "バックエンドシステムの選択",
                                    listOf(
                                        "KonomiTV" to "KONOMITV",
                                        "EDCB (EpgTimerSrv)" to "EDCB",
                                        "Mirakurun (録画なし)" to "MIRAKURUN_ONLY"
                                    ),
                                    if (listOf(
                                            "KONOMITV",
                                            "EDCB",
                                            "MIRAKURUN_ONLY"
                                        ).any { it == prefs.backendType }
                                    ) prefs.backendType else "KONOMITV"
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        repository.saveString(
                                            SettingsRepository.BACKEND_TYPE,
                                            it
                                        )
                                    }
                                    viewModel.updateBackendType(it)
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                                    mutableListOf(
                                        "メインシステムに従う\n（トランスコード）" to "KONOMITV",
                                        "Mirakurun を優先" to "MIRAKURUN"
                                    ).apply {
                                        if (prefs.backendType != "EDCB") add("EDCB (TCP) を優先" to "EDCB") else add(
                                            "EDCB (ダイレクトストリーミング)" to "EDCB"
                                        )
                                    },
                                    if (listOf(
                                            "KONOMITV",
                                            "MIRAKURUN",
                                            "EDCB"
                                        ).any { it == prefs.preferredSource }
                                    ) prefs.preferredSource else "KONOMITV"
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        repository.saveString(
                                            SettingsRepository.PREFERRED_STREAM_SOURCE,
                                            it
                                        )
                                    }
                                }
                            },
                            itemFocusRequesters[1][0],
                            itemFocusRequesters[1][1],
                            itemFocusRequesters[1][2],
                            itemFocusRequesters[1][3],
                            itemFocusRequesters[1][4],
                            itemFocusRequesters[1][5],
                            itemFocusRequesters[1][6],
                            itemFocusRequesters[1][8],
                            itemFocusRequesters[1][9],
                            categoryFocusRequesters[1]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 1 }

                        2 -> PlaybackSettingsContent(
                            prefs.liveQuality,
                            prefs.videoQuality,
                            prefs.liveSubtitleDefault,
                            prefs.videoSubtitleDefault,
                            prefs.subtitleCommentLayer,
                            prefs.audioOutputMode,
                            playerUiMode,
                            autoCmSkip,
                            availableQualities,
                            itemFocusRequesters[2][0],
                            itemFocusRequesters[2][1],
                            itemFocusRequesters[2][2],
                            itemFocusRequesters[2][3],
                            itemFocusRequesters[2][4],
                            itemFocusRequesters[2][5],
                            itemFocusRequesters[2][6],
                            itemFocusRequesters[2][7],
                            categoryFocusRequesters[2],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_QUALITY_TITLE,
                                    availableQualities.map { it.label to it.value },
                                    if (availableQualities.any { it.value == prefs.liveQuality }) prefs.liveQuality else availableQualities.firstOrNull()?.value
                                        ?: ""
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.LIVE_QUALITY,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_QUALITY_TITLE,
                                    availableQualities.map { it.label to it.value },
                                    if (availableQualities.any { it.value == prefs.videoQuality }) prefs.videoQuality else availableQualities.firstOrNull()?.value
                                        ?: ""
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.VIDEO_QUALITY,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.LIVE_SUBTITLE_DEFAULT,
                                        if (prefs.liveSubtitleDefault == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.VIDEO_SUBTITLE_DEFAULT,
                                        if (prefs.videoSubtitleDefault == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            {
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
                            {
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
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "プレイヤーUIモード",
                                    listOf(
                                        "モダン (オンスクリーン操作)" to "MODERN",
                                        "クラシック (D-Pad完結)" to "CLASSIC"
                                    ),
                                    playerUiMode
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.PLAYER_UI_MODE,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "自動CMスキップ",
                                    listOf("有効" to "ON", "無効" to "OFF"),
                                    autoCmSkip
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.AUTO_CM_SKIP,
                                            it
                                        )
                                    }
                                }
                            }
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 2 }

                        3 -> RecordingSettingsContent(
                            prefs.postRecordingBatchList,
                            {
                                uiState.activeDialog = SettingDialogState.BatchInput { n, p ->
                                    viewModel.addPostRecordingBatch(
                                        n,
                                        p
                                    )
                                }
                            },
                            { b ->
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "バッチの削除",
                                    "「${b.name}」を削除しますか？"
                                ) { viewModel.deletePostRecordingBatch(b) }
                            },
                            itemFocusRequesters[3][0], batchItemRs, categoryFocusRequesters[3]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 3 }

                        4 -> HomeDisplaySettingsContent(
                            prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE" || prefs.currentThemeName == "KOMOREBI_DAY" || prefs.currentThemeName == "KYLE_DAY",
                            when (prefs.currentThemeName) {
                                "SPRING", "SPRING_LIGHT" -> "SPRING"; "SUMMER", "SUMMER_LIGHT" -> "SUMMER"; "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"; "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"; "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI"; "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE"; else -> "DEFAULT"
                            },
                            prefs.pickupGenre,
                            prefs.excludePaid,
                            prefs.pickupTime,
                            prefs.startupTab,
                            itemFocusRequesters[4][0],
                            itemFocusRequesters[4][1],
                            itemFocusRequesters[4][2],
                            itemFocusRequesters[4][3],
                            itemFocusRequesters[4][4],
                            itemFocusRequesters[4][5],
                            categoryFocusRequesters[4],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_BASE_THEME,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_THEME_DARK to "DARK",
                                        AppStrings.SETTINGS_VALUE_THEME_LIGHT to "LIGHT",
                                        "時間連動テーマ" to "TIME_LINKED"
                                    ),
                                    if (prefs.currentThemeName.startsWith("KOMOREBI") || prefs.currentThemeName.startsWith(
                                            "KYLE"
                                        )
                                    ) "TIME_LINKED" else if (prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE") "LIGHT" else "DARK"
                                ) {
                                    val nt = when (it) {
                                        "TIME_LINKED" -> "KOMOREBI"; "DARK" -> getThemeFromModeAndSeason(
                                            true,
                                            "DEFAULT"
                                        ); "LIGHT" -> getThemeFromModeAndSeason(
                                            false,
                                            "DEFAULT"
                                        ); else -> "MONOTONE"
                                    }
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.APP_THEME,
                                            nt
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_THEME_COLOR,
                                    if (prefs.currentThemeName.startsWith("KOMOREBI") || prefs.currentThemeName.startsWith(
                                            "KYLE"
                                        )
                                    ) listOf(
                                        "木漏れ日セット" to "KOMOREBI",
                                        "カイルセット" to "KYLE"
                                    ) else listOf(
                                        AppStrings.SETTINGS_VALUE_SEASON_DEFAULT to "DEFAULT",
                                        AppStrings.SETTINGS_VALUE_SEASON_SPRING to "SPRING",
                                        AppStrings.SETTINGS_VALUE_SEASON_SUMMER to "SUMMER",
                                        AppStrings.SETTINGS_VALUE_SEASON_AUTUMN to "AUTUMN",
                                        AppStrings.SETTINGS_VALUE_SEASON_WINTER to "WINTER"
                                    ),
                                    "DEFAULT"
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.APP_THEME,
                                            if (it == "KOMOREBI" || it == "KYLE") it else getThemeFromModeAndSeason(
                                                true,
                                                it
                                            )
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                    if (prefs.favoriteBaseballTeams.isNotEmpty()) listOf(
                                        "ホーム" to "ホーム",
                                        "ライブ" to "ライブ",
                                        "ビデオ" to "ビデオ",
                                        "番組表" to "番組表",
                                        "録画予約" to "録画予約",
                                        "プロ野球" to "プロ野球"
                                    ) else listOf(
                                        "ホーム" to "ホーム",
                                        "ライブ" to "ライブ",
                                        "ビデオ" to "ビデオ",
                                        "番組表" to "番組表",
                                        "録画予約" to "録画予約"
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
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_PICKUP_GENRE_TITLE,
                                    listOf(
                                        "アニメ" to "アニメ",
                                        "映画" to "映画",
                                        "ドラマ" to "ドラマ",
                                        "スポーツ" to "スポーツ",
                                        "音楽" to "音楽",
                                        "バラエティ" to "バラエティ",
                                        "ドキュメンタリー" to "ドキュメンタリー"
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
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_PICKUP_TIME_TITLE,
                                    listOf(
                                        "自動" to "自動",
                                        "朝" to "朝",
                                        "昼" to "昼",
                                        "夜" to "夜"
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
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.EXCLUDE_PAID_BROADCASTS,
                                        if (prefs.excludePaid == "ON") "OFF" else "ON"
                                    )
                                }
                            }
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 4 }

                        5 -> DisplaySettingsContent(
                            prefs,
                            when (prefs.startupChannel) {
                                "OFF" -> AppStrings.SETTINGS_VALUE_STARTUP_OFF; "LAST_WATCHED" -> AppStrings.SETTINGS_VALUE_STARTUP_LAST; else -> flatChannels.find { it.id == prefs.startupChannel }?.name
                                ?: prefs.startupChannel
                            },
                            categoryFocusRequesters[5],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                    if (prefs.favoriteBaseballTeams.isNotEmpty()) listOf(
                                        "ホーム" to "ホーム",
                                        "ライブ" to "ライブ",
                                        "ビデオ" to "ビデオ",
                                        "番組表" to "番組表",
                                        "録画予約" to "録画予約",
                                        "プロ野球" to "プロ野球"
                                    ) else listOf(
                                        "ホーム" to "ホーム",
                                        "ライブ" to "ライブ",
                                        "ビデオ" to "ビデオ",
                                        "番組表" to "番組表",
                                        "録画予約" to "録画予約"
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
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_STARTUP_CHANNEL_TITLE,
                                    (listOf(
                                        AppStrings.SETTINGS_VALUE_STARTUP_OFF to "OFF",
                                        AppStrings.SETTINGS_VALUE_STARTUP_LAST to "LAST_WATCHED"
                                    ) + flatChannels.map { it.name to it.id }),
                                    prefs.startupChannel
                                ) { viewModel.updateStartupChannel(it) }
                            },
                            {
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
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "時刻の表示形式",
                                    listOf("24時間表記" to "24H", "12時間表記 (AM/PM)" to "12H"),
                                    prefs.timeFormat
                                ) { viewModel.updateTimeFormat(it) }
                            },
                            { viewModel.toggleHideSubChannels() },
                            itemFocusRequesters[5].dropLast(1), itemFocusRequesters[5].last()
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 5 }

                        6 -> EpgSettingsContent(
                            pref = prefs,
                            onEditColumn = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "表示チャンネル数",
                                    listOf(
                                        "5チャンネル" to "5",
                                        "7チャンネル" to "7",
                                        "9チャンネル" to "9",
                                        "11チャンネル" to "11"
                                    ),
                                    prefs.epgColumnCount
                                ) { viewModel.updateEpgColumnCount(it) }
                            },
                            onEditHour = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "表示時間数 (縦幅)",
                                    listOf(
                                        "4時間" to "4",
                                        "6時間" to "6",
                                        "8時間" to "8",
                                        "12時間" to "12"
                                    ),
                                    prefs.epgVisibleHours
                                ) { viewModel.updateEpgVisibleHours(it) }
                            },
                            onEditFontSize = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "文字サイズ",
                                    listOf(
                                        "80%" to "0.8",
                                        "90%" to "0.9",
                                        "100% (標準)" to "1.0",
                                        "110%" to "1.1",
                                        "120%" to "1.2"
                                    ),
                                    prefs.epgFontSizeScale
                                ) { viewModel.updateEpgFontSizeScale(it) }
                            },
                            colR = itemFocusRequesters[6][0],
                            hourR = itemFocusRequesters[6][1],
                            fontR = itemFocusRequesters[6][2],
                            sidebarR = categoryFocusRequesters[6],
                            onClick = {
                                uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 6
                            }
                        )

                        7 -> CommentSettingsContent(
                            prefs.commentDefaultDisplay,
                            prefs.commentSpeed,
                            prefs.commentFontSize,
                            prefs.commentOpacity,
                            prefs.commentMaxLines,
                            { t, v ->
                                uiState.activeDialog = SettingDialogState.Input(t, v) {
                                    scope.launch {
                                        repository.saveString(
                                            if (t == AppStrings.SETTINGS_INPUT_COMMENT_SPEED) SettingsRepository.COMMENT_SPEED else if (t == AppStrings.SETTINGS_INPUT_COMMENT_SIZE) SettingsRepository.COMMENT_FONT_SIZE else if (t == AppStrings.SETTINGS_INPUT_COMMENT_OPACITY) SettingsRepository.COMMENT_OPACITY else SettingsRepository.COMMENT_MAX_LINES,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.COMMENT_DEFAULT_DISPLAY,
                                        if (prefs.commentDefaultDisplay == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            itemFocusRequesters[7][0],
                            itemFocusRequesters[7][1],
                            itemFocusRequesters[7][2],
                            itemFocusRequesters[7][3],
                            itemFocusRequesters[7][4],
                            categoryFocusRequesters[7]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 7 }

                        8 -> LabSettingsContent(
                            prefs.geminiApiKey,
                            prefs.geminiApiKeyStatus,
                            isValidatingGeminiApiKey,
                            prefs.favoriteBaseballTeams,
                            prefs.labAllowMirakurunDual,
                            itemFocusRequesters[8][0],
                            itemFocusRequesters[8][1],
                            itemFocusRequesters[8][2],
                            categoryFocusRequesters[8],
                            { uiState.activeDialog = SettingDialogState.GeminiSetup },
                            {
                                uiState.activeDialog = SettingDialogState.MultiSelection(
                                    "フォロー球団の選択",
                                    listOf(
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
                                    ),
                                    prefs.favoriteBaseballTeams
                                ) { viewModel.updateFavoriteBaseballTeams(it) }
                            },
                            {
                                if (prefs.labAllowMirakurunDual == "OFF") {
                                    uiState.activeDialog = SettingDialogState.ConfirmClear(
                                        "【警告】ハードウェア負荷について",
                                        "Mirakurunソースでの2画面再生やPiPモードは、端末に極めて高い負荷をかけます。よろしいですか？"
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
                            }) {
                            uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 8
                        }

                        9 -> AppInfoContent(
                            { uiState.activeDialog = SettingDialogState.Licenses },
                            itemFocusRequesters[9][0], categoryFocusRequesters[9]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 9 }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        // --- ダイアログ表示制御 ---
        when (val state = uiState.activeDialog) {
            is SettingDialogState.Input -> com.beeregg2001.komorebi.ui.components.InputDialog(
                title = state.title,
                initialValue = state.initialValue,
                isLongToken = state.isLongToken,
                placeholder = state.placeholder,
                onDismiss = { closeDialog() },
                onConfirm = { state.onConfirm(it); closeDialog() })

            is SettingDialogState.BatchInput -> BatchInputDialog(
                { closeDialog() },
                { n, p -> state.onConfirm(n, p); closeDialog() })

            is SettingDialogState.Selection -> SelectionDialog(
                state.title,
                state.options,
                state.current,
                { closeDialog() },
                { state.onSelect(it); closeDialog() })

            is SettingDialogState.MultiSelection -> MultiSelectionDialog(
                state.title,
                state.options,
                state.currentSelections,
                { closeDialog() },
                { state.onConfirm(it); closeDialog() })

            is SettingDialogState.ConfirmClear -> ConfirmClearDialog(
                state.title,
                state.message,
                if (state.title.contains("警告")) "有効にする" else "削除",
                { state.onConfirm(); closeDialog() },
                { closeDialog() })

            is SettingDialogState.Licenses -> OpenSourceLicensesScreen(onBack = { closeDialog() })
            is SettingDialogState.GeminiSetup -> {
                val localIp by viewModel.localIpAddress.collectAsState()
                GeminiSetupDialog(
                    prefs.geminiApiKey,
                    prefs.geminiApiKeyStatus,
                    isValidatingGeminiApiKey,
                    localIp,
                    { viewModel.startGeminiLocalServer() },
                    { viewModel.stopGeminiLocalServer() },
                    { closeDialog() },
                    {
                        viewModel.stopGeminiLocalServer(); uiState.activeDialog =
                        SettingDialogState.Input(
                            "Gemini API Key",
                            prefs.geminiApiKey
                        ) { key ->
                            viewModel.saveAndValidateGeminiApiKey(key)
                        }
                    },
                    {
                        viewModel.stopGeminiLocalServer()
                        viewModel.clearGeminiApiKey()
                        closeDialog()
                    })
            }

            is SettingDialogState.SmbAction -> SelectionDialog(
                title = "${state.target.name} の操作",
                options = listOf("編集する" to "EDIT", "削除する" to "DELETE"),
                current = "",
                onDismiss = { closeDialog() },
                onSelect = { action ->
                    if (action == "EDIT") {
                        uiState.activeDialog = SettingDialogState.SmbSetup(state.target)
                    } else if (action == "DELETE") {
                        uiState.activeDialog = SettingDialogState.ConfirmClear(
                            "SMBサーバーの削除",
                            "「${state.target.name}」を削除しますか？"
                        ) { viewModel.deleteSmbServer(state.target.id) }
                    }
                })

            is SettingDialogState.SmbSetup -> {
                val localIp by viewModel.localIpAddress.collectAsState()
                SmbSetupDialog(
                    target = state.target,
                    serverIp = localIp,
                    onStartServer = { viewModel.startGeminiLocalServer() },
                    onStopServer = { viewModel.stopGeminiLocalServer() },
                    onDismiss = { closeDialog() },
                    onManualInputClick = {
                        viewModel.stopGeminiLocalServer()
                        uiState.activeDialog =
                            SettingDialogState.SmbManualInput(state.target) { server ->
                                viewModel.saveSmbServer(server)
                            }
                    },
                    onShowToast = { msg -> toastMessage = msg })
            }

            is SettingDialogState.SmbManualInput -> {
                SmbManualInputDialog(
                    state.target,
                    { closeDialog() },
                    { viewModel.saveSmbServer(it); closeDialog() })
            }

            else -> {}
        }
        GlobalToast(message = toastMessage)
    }
}