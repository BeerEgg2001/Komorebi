@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.reserve.ReserveListScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加

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
    isReserveOverlayOpen: Boolean = false,
    isEpgJumpMenuOpen: Boolean,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    triggerBack: Boolean,
    onBackTriggered: () -> Unit,
    onFinalBack: () -> Unit,
    onUiReady: () -> Unit,
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
    val colors = KomorebiTheme.colors
    val tabs = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }

    val epgUiState = epgViewModel.uiState
    val currentBroadcastingType by epgViewModel.selectedBroadcastingType.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val isRecordingLoadingInitial by recordViewModel.isRecordingLoading.collectAsState()
    val isRecordingLoadingMore by recordViewModel.isLoadingMore.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()

    val hotChannels by remember { derivedStateOf { homeViewModel.getHotChannels(channelViewModel.liveRows.value) } }
    val upcomingReserves by remember { derivedStateOf { homeViewModel.getUpcomingReserves(reserves) } }
    val genrePickup by homeViewModel.genrePickupPrograms.collectAsState()
    val pickupGenreLabel by homeViewModel.pickupGenreLabel.collectAsState()
    // ★追加: 現在のピックアップ時間帯（朝・昼・夜）を取得
    val genrePickupTimeSlot by homeViewModel.genrePickupTimeSlot.collectAsState()

    val watchHistoryPrograms = remember(watchHistory) { watchHistory.map { KonomiDataMapper.toDomainModel(it) } }
    val logoUrls = remember(epgUiState) { if (epgUiState is EpgUiState.Success) epgUiState.data.map { epgViewModel.getLogoUrl(it.channel) } else emptyList() }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val settingsFocusRequester = remember { FocusRequester() }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }

    var internalLastPlayerChannelId by remember(lastPlayerChannelId) { mutableStateOf(lastPlayerChannelId) }
    var isEpgJumping by remember { mutableStateOf(false) }
    var topNavHasFocus by remember { mutableStateOf(false) }

    val restoreProgramIdInt = remember(lastPlayerProgramId) { lastPlayerProgramId?.toIntOrNull() }
    val isFullScreenMode = selectedChannel != null || selectedProgram != null || epgSelectedProgram != null || isSettingsOpen || isRecordListOpen || isReserveOverlayOpen

    LaunchedEffect(selectedTabIndex) {
        when (selectedTabIndex) {
            0 -> homeViewModel.refreshHomeData()
            1 -> channelViewModel.fetchChannels()
            2 -> recordViewModel.fetchRecentRecordings(forceRefresh = false)
            4 -> reserveViewModel.fetchReserves()
        }
    }

    LaunchedEffect(Unit) {
        if (!isReturningFromPlayer) {
            delay(500)
            if (!isFullScreenMode) tabFocusRequesters.getOrNull(selectedTabIndex)?.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(isFullScreenMode) {
        if (!isFullScreenMode) {
            delay(200)
            if (selectedTabIndex == 4) contentFirstItemRequesters[4].safeRequestFocus(TAG)
            else if (selectedTabIndex != 3) tabFocusRequesters.getOrNull(selectedTabIndex)?.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            if (!topNavHasFocus) {
                tabFocusRequesters.getOrNull(selectedTabIndex)?.safeRequestFocus(TAG)
            } else {
                if (selectedTabIndex > 0) {
                    selectedTabIndex = 0
                    onTabChange(0)
                    delay(50); tabFocusRequesters[0].safeRequestFocus(TAG)
                } else {
                    onFinalBack()
                }
            }
            onBackTriggered()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullScreenMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 8.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { topNavHasFocus = it.hasFocus },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DigitalClock()
                    Spacer(modifier = Modifier.width(32.dp))
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier.weight(1f).focusGroup(),
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            TabRowDefaults.UnderlinedIndicator(
                                currentTabPosition = tabPositions[selectedTabIndex],
                                doesTabRowHaveFocus = doesTabRowHaveFocus,
                                activeColor = colors.accent                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onFocus = { if (selectedTabIndex != index) { selectedTabIndex = index; onTabChange(index) } },
                                modifier = Modifier.focusRequester(tabFocusRequesters[index])
                                    .focusProperties { down = contentFirstItemRequesters[index]; canFocus = !(selectedTabIndex == 3 && isEpgJumping) }
                            ) {
                                Text(text = title,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selectedTabIndex == index) colors.textPrimary else colors.textSecondary
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { onSettingsToggle(true) },
                        modifier = Modifier.focusRequester(settingsFocusRequester)
                            .focusProperties { left = tabFocusRequesters.last(); canFocus = !(selectedTabIndex == 3 && isEpgJumping) }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "設定", tint = colors.textSecondary)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> HomeContents(
                        lastWatchedChannels = lastChannels, watchHistory = watchHistory,
                        hotChannels = hotChannels, upcomingReserves = upcomingReserves,
                        genrePickup = genrePickup, pickupGenreName = pickupGenreLabel,
                        pickupTimeSlot = genrePickupTimeSlot, // ★追加
                        onChannelClick = onChannelClick,
                        onHistoryClick = { historyItem ->
                            val programId = historyItem.program.id.toIntOrNull()
                            val betterProgram = recentRecordings.find { it.id == programId }
                            if (betterProgram != null) onProgramSelected(betterProgram.copy(playbackPosition = historyItem.playback_position))
                            else onProgramSelected(KonomiDataMapper.toDomainModel(historyItem))
                        },
                        onReserveClick = onReserveSelected,
                        onProgramClick = { onEpgProgramSelected(it) },
                        onNavigateToTab = { index ->
                            tabFocusRequesters.getOrNull(index)?.safeRequestFocus(TAG)
                            selectedTabIndex = index
                            onTabChange(index)
                        },
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        tabFocusRequester = tabFocusRequesters[0], externalFocusRequester = contentFirstItemRequesters[0],
                        lastFocusedChannelId = internalLastPlayerChannelId, lastFocusedProgramId = lastPlayerProgramId
                    )
                    1 -> LiveContent(
                        channelViewModel = channelViewModel, epgViewModel = epgViewModel, groupedChannels = groupedChannels,
                        selectedChannel = selectedChannel, onChannelClick = onChannelClick, onFocusChannelChange = { internalLastPlayerChannelId = it },
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort, konomiIp = konomiIp, konomiPort = konomiPort,
                        topNavFocusRequester = tabFocusRequesters[1], contentFirstItemRequester = contentFirstItemRequesters[1],
                        onPlayerStateChanged = { }, lastFocusedChannelId = internalLastPlayerChannelId,
                        isReturningFromPlayer = isReturningFromPlayer && selectedTabIndex == 1, onReturnFocusConsumed = onReturnFocusConsumed,
                        reserveViewModel = reserveViewModel
                    )
                    2 -> VideoTabContent(
                        recentRecordings = recentRecordings, watchHistory = watchHistoryPrograms, selectedProgram = selectedProgram,
                        restoreProgramId = if (isReturningFromPlayer && selectedTabIndex == 2) restoreProgramIdInt else null,
                        konomiIp = konomiIp, konomiPort = konomiPort, topNavFocusRequester = tabFocusRequesters[2],
                        contentFirstItemRequester = contentFirstItemRequesters[2],
                        onProgramClick = { program ->
                            val betterProgram = recentRecordings.find { it.id == program.id }
                            if (betterProgram != null) onProgramSelected(betterProgram.copy(playbackPosition = program.playbackPosition))
                            else onProgramSelected(program)
                        },
                        onLoadMore = { recordViewModel.loadNextPage() }, isLoadingMore = isRecordingLoadingMore,
                        onShowAllRecordings = onShowAllRecordings,
                        onShowSeriesList = onShowSeriesList
                    )
                    3 -> EpgNavigationContainer(
                        uiState = epgUiState, logoUrls = logoUrls, mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        mainTabFocusRequester = tabFocusRequesters[3], contentRequester = contentFirstItemRequesters[3],
                        selectedProgram = epgSelectedProgram, onProgramSelected = onEpgProgramSelected, isJumpMenuOpen = isEpgJumpMenuOpen,
                        onJumpMenuStateChanged = onEpgJumpMenuStateChanged, onNavigateToPlayer = onNavigateToPlayer,
                        currentType = currentBroadcastingType, onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                        restoreChannelId = if (isReturningFromPlayer && selectedTabIndex == 3) lastPlayerChannelId else null,
                        availableTypes = groupedChannels.keys.toList(), onJumpStateChanged = { isEpgJumping = it }, reserves = reserves
                    )
                    4 -> ReserveListScreen(
                        onBack = { tabFocusRequesters[4].safeRequestFocus(TAG) }, onProgramClick = onReserveSelected,
                        konomiIp = konomiIp, konomiPort = konomiPort, contentFirstItemRequester = contentFirstItemRequesters[4]
                    )
                }
            }
        }
    }
}