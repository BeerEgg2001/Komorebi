@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.reserve.ReserveListScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val TAG = "HomeLauncher"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DigitalClock(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(1000)
        }
    }
    Text(
        text = currentTime.format(DateTimeFormatter.ofPattern("HH:mm")),
        color = KomorebiTheme.colors.textPrimary,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
        modifier = modifier
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String, mirakurunPort: String,
    konomiIp: String, konomiPort: String,
    onChannelClick: (Channel?) -> Unit,
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
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {}
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

    val favoriteBaseballTeams by homeViewModel.favoriteBaseballTeams.collectAsState()
    val favoriteBaseballGames by homeViewModel.favoriteBaseballGames.collectAsState()
    // ★追加: 日付オフセットの状態を収集
    val baseballDateOffset by homeViewModel.baseballDateOffset.collectAsState()

    val baseTabs = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
    val tabs = remember(favoriteBaseballTeams) {
        if (favoriteBaseballTeams.isNotEmpty()) baseTabs + "プロ野球" else baseTabs
    }

    val safeTabIndex = ui.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    LaunchedEffect(tabs.size) {
        if (ui.selectedTabIndex >= tabs.size) {
            ui.selectedTabIndex = 0
            onTabChange(0)
        }
    }

    LaunchedEffect(lastPlayerChannelId) { ui.internalLastPlayerChannelId = lastPlayerChannelId }

    LaunchedEffect(ui.selectedTabIndex) {
        if (ui.selectedTabIndex == 0) {
            channelViewModel.startPolling()
            homeViewModel.refreshHomeData()
        } else {
            channelViewModel.stopPolling()
        }
    }

    LaunchedEffect(Unit) {
        if (ui.selectedTabIndex == 0) {
            homeViewModel.refreshHomeData()
            channelViewModel.fetchChannels()
        }
        delay(300)
        if (!isReturningFromPlayer && !ui.isFullScreen(
                selectedChannel,
                selectedProgram,
                epgSelectedProgram,
                isSettingsOpen,
                isRecordListOpen,
                isReserveOverlayOpen
            )
        ) {
            ui.tabFocusRequesters.getOrNull(safeTabIndex)?.safeRequestFocus(TAG)
        }
    }

    val isFullScreenMode = ui.isFullScreen(
        selectedChannel,
        selectedProgram,
        epgSelectedProgram,
        isSettingsOpen,
        isRecordListOpen,
        isReserveOverlayOpen
    )

    LaunchedEffect(isFullScreenMode) {
        if (!isFullScreenMode) {
            delay(300)
            if (safeTabIndex == 4) ui.contentFirstItemRequesters[4].safeRequestFocus(TAG)
            else if (safeTabIndex != 3) ui.tabFocusRequesters.getOrNull(safeTabIndex)
                ?.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            ui.handleBackNavigation(onTabChange, onFinalBack, onBackTriggered)
            if (safeTabIndex == 0) {
                delay(100); ui.tabFocusRequesters[0].safeRequestFocus(TAG)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullScreenMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(top = 8.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { ui.topNavHasFocus = it.hasFocus },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DigitalClock()
                    Spacer(modifier = Modifier.width(32.dp))
                    TabRow(
                        selectedTabIndex = safeTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .focusGroup(),
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
                                    ui.selectedTabIndex = index
                                    ui.onTabSelected(
                                        index,
                                        onTabChange,
                                        homeViewModel,
                                        channelViewModel,
                                        recordViewModel,
                                        reserveViewModel
                                    )
                                    ui.topNavHasFocus = true
                                },
                                modifier = Modifier
                                    .focusRequester(ui.tabFocusRequesters[index])
                                    .focusProperties {
                                        down =
                                            if (safeTabIndex == index && ui.isCurrentTabContentReady) ui.contentFirstItemRequesters[index] else FocusRequester.Default
                                        canFocus = !(safeTabIndex == 3 && ui.isEpgJumping)
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
                    IconButton(
                        onClick = { onSettingsToggle(true) },
                        modifier = Modifier
                            .focusRequester(ui.settingsFocusRequester)
                            .focusProperties {
                                left = ui.tabFocusRequesters.last(); canFocus =
                                !(safeTabIndex == 3 && ui.isEpgJumping)
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
                val currentTabLabel = tabs.getOrNull(safeTabIndex) ?: "ホーム"

                key(currentTabLabel) {
                    ui.isCurrentTabContentReady = false

                    when (currentTabLabel) {
                        "ホーム" -> HomeContents(
                            lastWatchedChannels = ui.lastChannels,
                            watchHistory = ui.watchHistory,
                            hotChannels = ui.hotChannels,
                            upcomingReserves = ui.upcomingReserves,
                            genrePickup = ui.genrePickup,
                            pickupGenreName = ui.pickupGenreLabel,
                            pickupTimeSlot = ui.genrePickupTimeSlot,
                            groupedChannels = groupedChannels,
                            onChannelClick = onChannelClick,
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
                            onNavigateToTab = { index ->
                                ui.tabFocusRequesters.getOrNull(index)?.safeRequestFocus(TAG)
                                ui.onTabSelected(
                                    index,
                                    onTabChange,
                                    homeViewModel,
                                    channelViewModel,
                                    recordViewModel,
                                    reserveViewModel
                                )
                            },
                            konomiIp = konomiIp, konomiPort = konomiPort,
                            mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                            tabFocusRequester = ui.tabFocusRequesters[0],
                            externalFocusRequester = ui.contentFirstItemRequesters[0],
                            lastFocusedChannelId = ui.internalLastPlayerChannelId,
                            lastFocusedProgramId = lastPlayerProgramId,
                            isTopNavFocused = ui.topNavHasFocus,
                            onUiReady = { onUiReady(); ui.isCurrentTabContentReady = true }
                        )

                        "ライブ" -> {
                            LiveContent(
                                channelViewModel = channelViewModel,
                                epgViewModel = epgViewModel,
                                groupedChannels = groupedChannels,
                                selectedChannel = selectedChannel,
                                onChannelClick = onChannelClick,
                                onFocusChannelChange = { ui.internalLastPlayerChannelId = it },
                                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                topNavFocusRequester = ui.tabFocusRequesters[1],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[1],
                                onPlayerStateChanged = { },
                                lastFocusedChannelId = ui.internalLastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && safeTabIndex == 1,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                reserveViewModel = reserveViewModel
                            )
                            LaunchedEffect(Unit) {
                                delay(500); onUiReady(); ui.isCurrentTabContentReady = true
                            }
                        }

                        "ビデオ" -> {
                            VideoTabContent(
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                tabFocusRequester = ui.tabFocusRequesters[2],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[2],
                                onProgramClick = { program ->
                                    val betterProgram =
                                        ui.recentRecordings.find { it.id == program.id }
                                    onProgramSelected(
                                        betterProgram?.copy(playbackPosition = program.playbackPosition)
                                            ?: program
                                    )
                                },
                                onShowAllRecordings = onShowAllRecordings,
                                onShowSeriesList = onShowSeriesList,
                                openedSeriesTitle = ui.openedSeriesTitle,
                                onOpenedSeriesTitleChange = { ui.openedSeriesTitle = it },
                                recordViewModel = recordViewModel,
                                watchHistory = ui.watchHistory,
                                isTopNavFocused = ui.topNavHasFocus
                            )
                            LaunchedEffect(Unit) {
                                delay(500); onUiReady(); ui.isCurrentTabContentReady = true
                            }
                        }

                        "番組表" -> {
                            val epgSearchQuery by epgViewModel.searchQuery.collectAsState()
                            val epgSearchHistory by epgViewModel.searchHistory.collectAsState()
                            val epgActiveSearchQuery by epgViewModel.activeSearchQuery.collectAsState()
                            val epgSearchResults by epgViewModel.searchResults.collectAsState()
                            val epgIsSearching by epgViewModel.isSearching.collectAsState()

                            LaunchedEffect(groupedChannels.keys) {
                                epgViewModel.preloadEpgDataForSearch(
                                    groupedChannels.keys.toList()
                                )
                            }

                            EpgNavigationContainer(
                                uiState = ui.epgUiState,
                                logoUrls = ui.logoUrls,
                                mainTabFocusRequester = ui.tabFocusRequesters[3],
                                contentRequester = ui.contentFirstItemRequesters[3],
                                selectedProgram = epgSelectedProgram,
                                onProgramSelected = onEpgProgramSelected,
                                isJumpMenuOpen = isEpgJumpMenuOpen,
                                onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                onNavigateToPlayer = onNavigateToPlayer,
                                currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                                onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                                restoreChannelId = if (isReturningFromPlayer && safeTabIndex == 3) lastPlayerChannelId else null,
                                availableTypes = groupedChannels.keys.toList(),
                                onJumpStateChanged = { ui.isEpgJumping = it },
                                reserves = ui.reserves,
                                onUpdateTargetTime = { epgViewModel.updateTargetTime(it) },
                                searchQuery = epgSearchQuery,
                                searchHistory = epgSearchHistory,
                                onSearchQueryChange = { epgViewModel.updateSearchQuery(it) },
                                onExecuteSearch = { epgViewModel.executeSearch(it) },
                                activeSearchQuery = epgActiveSearchQuery,
                                searchResults = epgSearchResults,
                                isSearching = epgIsSearching,
                                onClearSearch = { epgViewModel.clearSearch() }
                            )
                            LaunchedEffect(Unit) {
                                delay(800); onUiReady(); ui.isCurrentTabContentReady = true
                            }
                        }

                        "録画予約" -> {
                            ReserveListScreen(
                                onBack = { ui.tabFocusRequesters[4].safeRequestFocus(TAG) },
                                onProgramClick = onReserveSelected,
                                onConditionClick = onConditionClick,
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                contentFirstItemRequester = ui.contentFirstItemRequesters[4],
                                topNavFocusRequester = ui.tabFocusRequesters[4],
                                groupedChannels = groupedChannels
                            )
                            LaunchedEffect(Unit) {
                                delay(500); onUiReady(); ui.isCurrentTabContentReady = true
                            }
                        }

                        "プロ野球" -> {
                            BaseballDashboardScreen(
                                groupedGames = favoriteBaseballGames,
                                groupedChannels = groupedChannels,
                                dateOffset = baseballDateOffset, // ★追加
                                onDateOffsetChange = { homeViewModel.updateBaseballDateOffset(it) }, // ★追加
                                onChannelClick = { channel ->
                                    val matchedChannel = groupedChannels.values.flatten()
                                        .find { it.id == channel.id }
                                    if (matchedChannel != null) onChannelClick(matchedChannel)
                                },
                                onProgramClick = { onEpgProgramSelected(it) },
                                topNavFocusRequester = ui.tabFocusRequesters[5],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[5],
                                onUiReady = {
                                    delay(500); onUiReady(); ui.isCurrentTabContentReady = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}