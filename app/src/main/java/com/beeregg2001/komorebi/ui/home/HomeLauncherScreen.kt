@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.reserve.ReserveListScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "HomeLauncher"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DigitalClock(timeFormat: String, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
    val formattedTime = currentTime.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))

    Text(
        text = formattedTime,
        color = KomorebiTheme.colors.textPrimary,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
        modifier = modifier
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    settingsViewModel: SettingsViewModel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String, mirakurunPort: String,
    konomiIp: String, konomiPort: String,
    onChannelClick: (Channel?, Boolean) -> Unit,
    selectedChannel: Channel?,
    onTabChange: (Int) -> Unit,
    initialTabIndex: Int = 0,
    selectedProgram: RecordedProgram?,
    onProgramSelected: (RecordedProgram?) -> Unit,
    epgSelectedProgram: EpgProgram?,
    onEpgProgramSelected: (EpgProgram?) -> Unit,
    onReserveSelected: (ReserveItem) -> Unit = {},
    onConditionClick: (ReservationCondition) -> Unit = {},
    isReserveOverlayOpen: Boolean = false,
    isEpgJumpMenuOpen: Boolean,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    triggerBack: Boolean,
    onBackTriggered: () -> Unit,
    onFinalBack: () -> Unit,
    onUiReady: () -> Unit,
    isUiReadyFlag: Boolean,
    onNavigateToPlayer: (String, String, String) -> Unit,
    lastPlayerChannelId: String? = null,
    lastPlayerProgramId: String? = null,
    isSettingsOpen: Boolean = false,
    onSettingsToggle: (Boolean) -> Unit = {},
    isRecordListOpen: Boolean = false,
    onShowAllRecordings: () -> Unit = {},
    onCloseRecordList: () -> Unit = {},
    onShowSeriesList: () -> Unit = {},
    onShowSmbLibrary: () -> Unit = {},
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    hasActivePlayer: Boolean = false,
    onReturnToPlayerClick: () -> Unit = {},
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {}
) {
    val ui = rememberHomeLauncherState(
        initialTabIndex,
        channelViewModel,
        homeViewModel,
        epgViewModel,
        recordViewModel,
        reserveViewModel
    )
    val colors = KomorebiTheme.colors

    val ticketManager = rememberHomeFocusTicketManager()
    val scope = rememberCoroutineScope()

    val favoriteBaseballTeams by homeViewModel.favoriteBaseballTeams.collectAsState()
    val favoriteBaseballGames by homeViewModel.favoriteBaseballGames.collectAsState()
    val baseballDateOffset by homeViewModel.baseballDateOffset.collectAsState()

    val backendType by homeViewModel.backendType.collectAsState()
    val shouldCropLogo = remember(backendType) { backendType == "KONOMITV" }

    val tabs = remember(favoriteBaseballTeams, backendType) {
        val base = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
        if (favoriteBaseballTeams.isNotEmpty()) base + "プロ野球" else base
    }

    val safeTabIndex = ui.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    val isFullScreenMode = ui.isFullScreen(
        selectedChannel, selectedProgram, epgSelectedProgram,
        isSettingsOpen, isRecordListOpen, isReserveOverlayOpen
    ) && !hasActivePlayer

    val returnPlayerFocusRequester = remember { FocusRequester() }
    val displayFlatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    val translatedLastChannels = remember(ui.lastChannels, displayFlatChannels) {
        ui.lastChannels.map { savedChannel ->
            displayFlatChannels.find {
                it.networkId == savedChannel.networkId && it.serviceId == savedChannel.serviceId
            } ?: savedChannel
        }
    }

    LaunchedEffect(tabs.size) {
        if (ui.selectedTabIndex >= tabs.size) {
            ui.selectedTabIndex = 0
            onTabChange(0)
        }
    }

    LaunchedEffect(lastPlayerChannelId) {
        if (lastPlayerChannelId != null) {
            ui.internalLastPlayerChannelId = lastPlayerChannelId
        }
    }

    // =====================================================================================
    // ★ 究極の軽量化ロジック: デバウンス・レンダリング（Debounce Rendering）
    // 連打中は画面を構築せず、指が止まってから60ms後に初めてコンテンツを描画する。
    // 裏側のタブは完全に破棄するため、フォーカスの迷子や競合は物理的に発生しません。
    // =====================================================================================
    var activeRenderIndex by remember { mutableIntStateOf(safeTabIndex) }

    LaunchedEffect(safeTabIndex) {
        if (activeRenderIndex != safeTabIndex) {
            delay(60) // 連打中はキャンセルされ続け、指が止まった時だけここを通過する
            activeRenderIndex = safeTabIndex
        }
    }

    var lastHomeRefreshTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(activeRenderIndex) {
        val currentLabel = tabs.getOrNull(activeRenderIndex) ?: "ホーム"
        ui.isCurrentTabContentReady = false

        if (currentLabel == "ホーム") {
            channelViewModel.startPolling()
            val now = System.currentTimeMillis()
            if (now - lastHomeRefreshTime > 300_000L) {
                homeViewModel.refreshHomeData()
                lastHomeRefreshTime = now
            }
        } else if (currentLabel == "ライブ") {
            channelViewModel.startPolling()
        } else {
            channelViewModel.stopPolling()
        }
    }

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            delay(150)
            val currentTabName = tabs.getOrNull(safeTabIndex)
            when (currentTabName) {
                "ホーム" -> {
                    val section = homeViewModel.lastClickedSection
                    val itemId = homeViewModel.lastClickedItemId
                    if (section != null && itemId != null) {
                        ticketManager.issueForHomeRestore(section, itemId)
                    } else {
                        ticketManager.issue(HomeFocusTicket.CONTENT_TOP)
                    }
                    onAiReturnConsumed()
                }

                "プロ野球" -> {
                    ui.contentFirstItemRequesters.getOrNull(safeTabIndex)
                        ?.safeRequestFocusWithRetry("BaseballAiReturn")
                    onAiReturnConsumed()
                }

                else -> {
                    ui.tabFocusRequesters.getOrNull(safeTabIndex)
                        ?.safeRequestFocusWithRetry("FallbackAiReturn")
                    onAiReturnConsumed()
                }
            }
        }
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer && !isFullScreenMode) {
            ui.safeHouseRequester.safeRequestFocusWithRetry("SafeHouse_Return")
            delay(150)

            val currentTabName = tabs.getOrNull(safeTabIndex)
            if (currentTabName != "ライブ" && currentTabName != "ビデオ") {
                val section = homeViewModel.lastClickedSection
                val itemId = homeViewModel.lastClickedItemId
                if (currentTabName == "ホーム" && section != null && itemId != null) {
                    ticketManager.issueForHomeRestore(section, itemId)
                } else if (currentTabName == "番組表" || currentTabName == "録画予約") {
                    if (currentTabName == "番組表") {
                        onReturnFocusConsumed()
                    }
                } else {
                    ticketManager.issue(HomeFocusTicket.TAB_BAR)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ui.selectedTabIndex == 0) {
            homeViewModel.refreshHomeData()
            channelViewModel.fetchChannels()
        }
        if (!isReturningFromPlayer && !isFullScreenMode) {
            delay(300)
            ticketManager.issue(HomeFocusTicket.TAB_BAR)
        }
    }

    LaunchedEffect(isFullScreenMode) {
        if (!isFullScreenMode && !isReturningFromPlayer) {
            delay(300)
            val currentTabName = tabs.getOrNull(safeTabIndex)
            if (currentTabName == "録画予約") {
            } else if (currentTabName == "ホーム") {
                val section = homeViewModel.lastClickedSection
                val itemId = homeViewModel.lastClickedItemId
                if (section != null && itemId != null) {
                    ticketManager.issueForHomeRestore(section, itemId)
                } else {
                    ticketManager.issue(HomeFocusTicket.TAB_BAR)
                }
            } else if (currentTabName != "番組表") {
                ticketManager.issue(HomeFocusTicket.TAB_BAR)
            }
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            ui.handleBackNavigation(
                onTabChange = onTabChange,
                onFinalBack = onFinalBack,
                onBackTriggered = onBackTriggered,
                requestTopNavFocus = {
                    ticketManager.issue(HomeFocusTicket.TAB_BAR)
                },
                escapeToSafeHouse = {
                    scope.launch {
                        ui.safeHouseRequester.safeRequestFocusWithRetry("Back_SafeHouse")
                    }
                }
            )
        }
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        when (ticketManager.currentTicket) {
            HomeFocusTicket.TAB_BAR -> {
                delay(200)
                ui.tabFocusRequesters.getOrNull(safeTabIndex)
                    ?.safeRequestFocusWithRetry("HomeTicket_TAB_BAR")
                ticketManager.consume(HomeFocusTicket.TAB_BAR)

                val currentTabName = tabs.getOrNull(safeTabIndex)
                if (isReturningFromPlayer && currentTabName != "ライブ" && currentTabName != "ビデオ") {
                    onReturnFocusConsumed()
                }
            }

            HomeFocusTicket.CONTENT_TOP -> {
                delay(150)
                ui.contentFirstItemRequesters.getOrNull(safeTabIndex)
                    ?.safeRequestFocusWithRetry("HomeTicket_CONTENT_TOP")
                ticketManager.consume(HomeFocusTicket.CONTENT_TOP)
            }

            HomeFocusTicket.HOME_RESTORE -> {
                if (isReturningFromPlayer) {
                    onReturnFocusConsumed()
                }
            }

            else -> {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(ui.safeHouseRequester)
                .focusable()
        )

        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullScreenMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(top = 8.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { ui.topNavHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                ui.handleBackNavigation(
                                    onTabChange = onTabChange,
                                    onFinalBack = onFinalBack,
                                    onBackTriggered = onBackTriggered,
                                    requestTopNavFocus = { ticketManager.issue(HomeFocusTicket.TAB_BAR) },
                                    escapeToSafeHouse = {
                                        scope.launch {
                                            ui.safeHouseRequester.safeRequestFocusWithRetry(
                                                "Back_SafeHouse"
                                            )
                                        }
                                    }
                                )
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DigitalClock(timeFormat = timeFormat)
                    Spacer(modifier = Modifier.width(32.dp))
                    TabRow(
                        selectedTabIndex = safeTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties {
                                canFocus = !isReturningFromPlayer
                            },
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            if (safeTabIndex < tabPositions.size) {
                                TabRowDefaults.UnderlinedIndicator(
                                    currentTabPosition = tabPositions[safeTabIndex],
                                    doesTabRowHaveFocus = doesTabRowHaveFocus,
                                    activeColor = colors.accent
                                )
                            }
                        }) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = safeTabIndex == index,
                                onFocus = {
                                    if (!isReturningFromPlayer) {
                                        ui.selectedTabIndex = index
                                        ui.onTabSelected(
                                            index,
                                            tabs,
                                            onTabChange,
                                            homeViewModel,
                                            channelViewModel,
                                            recordViewModel,
                                            reserveViewModel
                                        )
                                    }
                                    ui.topNavHasFocus = true
                                },
                                modifier = Modifier
                                    .focusRequester(
                                        ui.tabFocusRequesters.getOrNull(index)
                                            ?: FocusRequester.Default
                                    )
                                    .focusProperties {
                                        down =
                                            if (safeTabIndex == index && ui.isCurrentTabContentReady)
                                                ui.contentFirstItemRequesters.getOrNull(index)
                                                    ?: FocusRequester.Default
                                            else FocusRequester.Default

                                        canFocus = !(title == "番組表" && ui.isEpgJumping)

                                        up = FocusRequester.Cancel
                                        if (index == 0) {
                                            left = FocusRequester.Cancel
                                        }
                                    }) {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 8.dp
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (safeTabIndex == index) colors.textPrimary else colors.textSecondary
                                )
                            }
                        }
                    }

                    if (hasActivePlayer) {
                        val isEpgJumping =
                            tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                        Button(
                            onClick = onReturnToPlayerClick,
                            modifier = Modifier
                                .focusRequester(returnPlayerFocusRequester)
                                .focusProperties {
                                    left = ui.tabFocusRequesters.getOrNull(tabs.lastIndex)
                                        ?: FocusRequester.Default
                                    right = ui.settingsFocusRequester
                                    canFocus = !isEpgJumping
                                    up = FocusRequester.Cancel
                                },
                            colors = ButtonDefaults.colors(
                                containerColor = colors.accent.copy(alpha = 0.2f),
                                focusedContainerColor = colors.accent,
                                contentColor = colors.accent,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            ),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(20.dp)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "再生中",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "再生中",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    IconButton(
                        onClick = { onSettingsToggle(true) },
                        modifier = Modifier
                            .focusRequester(ui.settingsFocusRequester)
                            .focusProperties {
                                val isEpgJumping =
                                    tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                                left =
                                    if (hasActivePlayer) returnPlayerFocusRequester else (ui.tabFocusRequesters.getOrNull(
                                        tabs.lastIndex
                                    ) ?: FocusRequester.Default)
                                canFocus = !isEpgJumping
                                up = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            },
                        colors = IconButtonDefaults.colors(
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White,
                            contentColor = colors.textSecondary
                        )
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                // ★ 完全に物理的に1つのタブのみを描画する（他はツリーから完全に消去され、フォーカス迷子が100%防がれる）
                if (activeRenderIndex == safeTabIndex) {
                    val currentTabLabel = tabs.getOrNull(activeRenderIndex) ?: "ホーム"
                    val handleUiReady = {
                        onUiReady()
                        ui.isCurrentTabContentReady = true
                    }

                    when (currentTabLabel) {
                        "ホーム" -> HomeContents(
                            lastWatchedChannels = translatedLastChannels,
                            watchHistory = ui.watchHistory,
                            hotChannels = ui.hotChannels,
                            upcomingReserves = ui.upcomingReserves,
                            genrePickup = ui.genrePickup,
                            pickupGenreName = ui.pickupGenreLabel,
                            pickupTimeSlot = ui.genrePickupTimeSlot,
                            groupedChannels = groupedChannels,
                            getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                            shouldCropLogo = shouldCropLogo,
                            onChannelClick = {
                                if (it != null) onChannelClick(it, false)
                            },
                            onHistoryClick = { historyItem ->
                                val programId = historyItem.program.id.toIntOrNull()
                                val betterProgram = ui.recentRecordings.find { it.id == programId }
                                onProgramSelected(
                                    betterProgram?.copy(playbackPosition = historyItem.playback_position)
                                        ?: KonomiDataMapper.toDomainModel(historyItem)
                                )
                            },
                            onReserveClick = onReserveSelected,
                            onProgramClick = { onEpgProgramSelected(it) },
                            onNavigateToTab = { targetIndex ->
                                ui.tabFocusRequesters.getOrNull(targetIndex)
                                    ?.safeRequestFocus(TAG); ui.onTabSelected(
                                targetIndex,
                                tabs,
                                onTabChange,
                                homeViewModel,
                                channelViewModel,
                                recordViewModel,
                                reserveViewModel
                            )
                            },
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                            externalFocusRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                            lastFocusedChannelId = ui.internalLastPlayerChannelId
                                ?: lastPlayerChannelId,
                            lastFocusedProgramId = lastPlayerProgramId,
                            isTopNavFocused = ui.topNavHasFocus,
                            onUiReady = handleUiReady,
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            timeFormat = timeFormat
                        )

                        "ライブ" -> {
                            LiveContent(
                                channelViewModel = channelViewModel,
                                epgViewModel = epgViewModel,
                                groupedChannels = groupedChannels,
                                selectedChannel = selectedChannel,
                                onChannelClick = { onChannelClick(it, false) },
                                onFocusChannelChange = { ui.internalLastPlayerChannelId = it },
                                mirakurunIp = mirakurunIp,
                                mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                onPlayerStateChanged = { },
                                lastFocusedChannelId = ui.internalLastPlayerChannelId
                                    ?: lastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "ライブ",
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                reserveViewModel = reserveViewModel,
                                timeFormat = timeFormat,
                                isPiPMode = hasActivePlayer,
                                aiFocusReturnTick = if (currentTabLabel == "ライブ") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed
                            )
                            LaunchedEffect(Unit) { delay(500); handleUiReady() }
                        }

                        "ビデオ" -> {
                            VideoTabContent(
                                recordViewModel = recordViewModel,
                                onProgramClick = { onProgramSelected(it) },
                                onShowAllRecordings = onShowAllRecordings,
                                onShowSeriesList = onShowSeriesList,
                                onShowSmbLibrary = onShowSmbLibrary,
                                openedSeriesTitle = ui.openedSeriesTitle,
                                onOpenedSeriesTitleChange = { ui.openedSeriesTitle = it },
                                tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                isTopNavFocused = ui.topNavHasFocus,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "ビデオ",
                                lastPlayedProgramId = lastPlayerProgramId,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                timeFormat = timeFormat,
                                watchHistory = ui.watchHistory,
                                aiFocusReturnTick = if (currentTabLabel == "ビデオ") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed
                            )
                            LaunchedEffect(Unit) { delay(500); handleUiReady() }
                        }

                        "番組表" -> {
                            EpgNavigationContainer(
                                uiState = ui.epgUiState,
                                logoUrls = ui.logoUrls,
                                mainTabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                selectedProgram = epgSelectedProgram,
                                onProgramSelected = onEpgProgramSelected,
                                isJumpMenuOpen = isEpgJumpMenuOpen,
                                onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                onNavigateToPlayer = onNavigateToPlayer,
                                currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                                onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                                restoreChannelId = if (isReturningFromPlayer && currentTabLabel == "番組表") lastPlayerChannelId else null,
                                availableTypes = groupedChannels.keys.toList(),
                                onJumpStateChanged = { ui.isEpgJumping = it },
                                reserves = ui.reserves,
                                onUpdateTargetTime = { epgViewModel.updateTargetTime(it) },
                                searchQuery = epgViewModel.searchQuery.collectAsState().value,
                                searchHistory = epgViewModel.searchHistory.collectAsState().value,
                                onSearchQueryChange = { epgViewModel.updateSearchQuery(it) },
                                onExecuteSearch = { epgViewModel.executeSearch(it) },
                                activeSearchQuery = epgViewModel.activeSearchQuery.collectAsState().value,
                                searchResults = epgViewModel.searchResults.collectAsState().value,
                                isSearching = epgViewModel.isSearching.collectAsState().value,
                                onClearSearch = { epgViewModel.clearSearch() },
                                timeFormat = timeFormat
                            )
                            LaunchedEffect(Unit) { delay(800); handleUiReady() }
                        }

                        "録画予約" -> {
                            ReserveListScreen(
                                onBack = {
                                    ui.tabFocusRequesters[activeRenderIndex].safeRequestFocus(
                                        TAG
                                    )
                                },
                                onProgramClick = onReserveSelected,
                                onConditionClick = onConditionClick,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                groupedChannels = groupedChannels,
                                isReserveOverlayOpen = isReserveOverlayOpen,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "録画予約",
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (currentTabLabel == "録画予約") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed
                            )
                            LaunchedEffect(Unit) { delay(500); handleUiReady() }
                        }

                        "プロ野球" -> {
                            BaseballDashboardScreen(
                                groupedGames = favoriteBaseballGames,
                                groupedChannels = groupedChannels,
                                dateOffset = baseballDateOffset,
                                onDateOffsetChange = { homeViewModel.updateBaseballDateOffset(it) },
                                onChannelClick = { channel ->
                                    val matchedChannel = displayFlatChannels.find {
                                        it.networkId == channel.networkId && it.serviceId == channel.serviceId
                                    } ?: channel
                                    onChannelClick(matchedChannel, true)
                                },
                                onProgramClick = { onEpgProgramSelected(it) },
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                onUiReady = { delay(500); handleUiReady() },
                                timeFormat = timeFormat
                            )
                        }
                    }
                }
            }
        }
    }
}