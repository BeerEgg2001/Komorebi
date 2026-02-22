@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
    val ui = rememberHomeLauncherState(
        initialTabIndex,
        channelViewModel,
        homeViewModel,
        epgViewModel,
        recordViewModel,
        reserveViewModel
    )

    val colors = KomorebiTheme.colors
    val tabs = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
    val scope = rememberCoroutineScope()

    LaunchedEffect(lastPlayerChannelId) { ui.internalLastPlayerChannelId = lastPlayerChannelId }

    // 初期起動時のデータ取得とフォーカス初期化
    LaunchedEffect(Unit) {
        Log.i(TAG, "Screen Initialized. selectedTabIndex: ${ui.selectedTabIndex}")
        if (ui.selectedTabIndex == 0) {
            homeViewModel.refreshHomeData()
            channelViewModel.fetchChannels()
        }

        // ★修正: 初回起動時に最下部へ飛ばないよう、まずTabRowにフォーカスを当てる
        delay(600)
        if (!isReturningFromPlayer && ui.isFullScreen(
                selectedChannel,
                selectedProgram,
                epgSelectedProgram,
                isSettingsOpen,
                isRecordListOpen,
                isReserveOverlayOpen
            ).not()
        ) {
            Log.i(TAG, "Requesting initial focus to Tab ${ui.selectedTabIndex}")
            ui.tabFocusRequesters.getOrNull(ui.selectedTabIndex)?.safeRequestFocus(TAG)
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

    // フルスクリーン解除時のフォーカス復帰
    LaunchedEffect(isFullScreenMode) {
        if (!isFullScreenMode) {
            Log.i(TAG, "FullScreen closed. Restoring focus.")
            delay(300)
            if (ui.selectedTabIndex == 4) ui.contentFirstItemRequesters[4].safeRequestFocus(TAG)
            else if (ui.selectedTabIndex != 3) ui.tabFocusRequesters.getOrNull(ui.selectedTabIndex)
                ?.safeRequestFocus(TAG)
        }
    }

    // 戻るボタン処理
    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            ui.handleBackNavigation(onTabChange, onFinalBack, onBackTriggered)
            if (ui.selectedTabIndex == 0) {
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
                        selectedTabIndex = ui.selectedTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .focusGroup(),
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            TabRowDefaults.UnderlinedIndicator(
                                currentTabPosition = tabPositions[ui.selectedTabIndex],
                                doesTabRowHaveFocus = doesTabRowHaveFocus,
                                activeColor = colors.accent
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = ui.selectedTabIndex == index,
                                onFocus = {
                                    ui.onTabSelected(
                                        index,
                                        onTabChange,
                                        homeViewModel,
                                        channelViewModel,
                                        recordViewModel,
                                        reserveViewModel
                                    )
                                },
                                modifier = Modifier
                                    .focusRequester(ui.tabFocusRequesters[index])
                                    .focusProperties {
                                        down = ui.contentFirstItemRequesters[index]; canFocus =
                                        !(ui.selectedTabIndex == 3 && ui.isEpgJumping)
                                    }
                            ) {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 8.dp
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (ui.selectedTabIndex == index) colors.textPrimary else colors.textSecondary
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
                                !(ui.selectedTabIndex == 3 && ui.isEpgJumping)
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
                when (ui.selectedTabIndex) {
                    0 -> HomeContents(
                        lastWatchedChannels = ui.lastChannels,
                        watchHistory = ui.watchHistory,
                        hotChannels = ui.hotChannels,
                        upcomingReserves = ui.upcomingReserves,
                        genrePickup = ui.genrePickup,
                        pickupGenreName = ui.pickupGenreLabel,
                        pickupTimeSlot = ui.genrePickupTimeSlot,
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
                        lastFocusedProgramId = lastPlayerProgramId
                    )

                    1 -> LiveContent(
                        channelViewModel = channelViewModel,
                        epgViewModel = epgViewModel,
                        groupedChannels = groupedChannels,
                        selectedChannel = selectedChannel,
                        onChannelClick = onChannelClick,
                        onFocusChannelChange = { ui.internalLastPlayerChannelId = it },
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        topNavFocusRequester = ui.tabFocusRequesters[1],
                        contentFirstItemRequester = ui.contentFirstItemRequesters[1],
                        onPlayerStateChanged = { },
                        lastFocusedChannelId = ui.internalLastPlayerChannelId,
                        isReturningFromPlayer = isReturningFromPlayer && ui.selectedTabIndex == 1,
                        onReturnFocusConsumed = onReturnFocusConsumed,
                        reserveViewModel = reserveViewModel
                    )

                    2 -> VideoTabContent(
                        recentRecordings = ui.recentRecordings,
                        watchHistory = ui.watchHistoryPrograms,
                        selectedProgram = selectedProgram,
                        restoreProgramId = if (isReturningFromPlayer && ui.selectedTabIndex == 2) lastPlayerProgramId?.toIntOrNull() else null,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        topNavFocusRequester = ui.tabFocusRequesters[2],
                        contentFirstItemRequester = ui.contentFirstItemRequesters[2],
                        onProgramClick = { program ->
                            val betterProgram =
                                ui.recentRecordings.find { it.id == program.id }; onProgramSelected(
                            betterProgram?.copy(playbackPosition = program.playbackPosition)
                                ?: program
                        )
                        },
                        onLoadMore = { recordViewModel.loadNextPage() },
                        isLoadingMore = ui.isLoadingMore,
                        onShowAllRecordings = onShowAllRecordings,
                        onShowSeriesList = onShowSeriesList
                    )

                    3 -> EpgNavigationContainer(
                        uiState = ui.epgUiState,
                        logoUrls = ui.logoUrls,
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort,
                        mainTabFocusRequester = ui.tabFocusRequesters[3],
                        contentRequester = ui.contentFirstItemRequesters[3],
                        selectedProgram = epgSelectedProgram,
                        onProgramSelected = onEpgProgramSelected,
                        isJumpMenuOpen = isEpgJumpMenuOpen,
                        onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                        onNavigateToPlayer = onNavigateToPlayer,
                        currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                        onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                        restoreChannelId = if (isReturningFromPlayer && ui.selectedTabIndex == 3) lastPlayerChannelId else null,
                        availableTypes = groupedChannels.keys.toList(),
                        onJumpStateChanged = { ui.isEpgJumping = it },
                        reserves = ui.reserves
                    )

                    4 -> ReserveListScreen(
                        onBack = { ui.tabFocusRequesters[4].safeRequestFocus(TAG) },
                        onProgramClick = onReserveSelected,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        contentFirstItemRequester = ui.contentFirstItemRequesters[4]
                    )
                }
            }
        }
    }
}