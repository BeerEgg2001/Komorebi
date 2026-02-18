@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
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

private const val TAG = "HomeLauncher"

// ★復元: DigitalClock
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
        color = Color.White,
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
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {}
) {
    val tabs = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }

    val epgUiState = epgViewModel.uiState
    val liveRows by channelViewModel.liveRows.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val pickupGenre by homeViewModel.pickupGenreLabel.collectAsState()
    // ★追加: 全放送波を対象としたピックアップデータ
    val genrePickup by homeViewModel.genrePickupPrograms.collectAsState()

    val hotChannels by remember { derivedStateOf { homeViewModel.getHotChannels(liveRows) } }
    val upcomingReserves by remember { derivedStateOf { homeViewModel.getUpcomingReserves(reserves) } }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }

    var internalLastPlayerChannelId by remember(lastPlayerChannelId) { mutableStateOf(lastPlayerChannelId) }
    var isEpgJumping by remember { mutableStateOf(false) }

    val isFullScreenMode = selectedChannel != null || selectedProgram != null || epgSelectedProgram != null || isSettingsOpen || isRecordListOpen || isReserveOverlayOpen

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullScreenMode) {
                Row(modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 8.dp, start = 40.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    DigitalClock()
                    Spacer(modifier = Modifier.width(32.dp))
                    // ★復元: 下線タイプのTabRow
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier.weight(1f).focusGroup(),
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            TabRowDefaults.UnderlinedIndicator(
                                currentTabPosition = tabPositions[selectedTabIndex],
                                doesTabRowHaveFocus = doesTabRowHaveFocus,
                                activeColor = Color.White
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onFocus = { if (selectedTabIndex != index) { selectedTabIndex = index; onTabChange(index) } },
                                modifier = Modifier.focusRequester(tabFocusRequesters[index]).focusProperties { down = contentFirstItemRequesters[index] }
                            ) {
                                Text(text = title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = if (selectedTabIndex == index) Color.White else Color.Gray)
                            }
                        }
                    }
                    IconButton(onClick = { onSettingsToggle(true) }) { Icon(Icons.Default.Settings, contentDescription = "設定", tint = Color.Gray) }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> HomeContents(
                        lastWatchedChannels = lastChannels, watchHistory = watchHistory,
                        hotChannels = hotChannels, upcomingReserves = upcomingReserves,
                        genrePickup = genrePickup, pickupGenreName = pickupGenre,
                        onChannelClick = { onChannelClick(it) },
                        onHistoryClick = { onProgramSelected(KonomiDataMapper.toDomainModel(it)) },
                        onReserveClick = onReserveSelected,
                        onProgramClick = { onEpgProgramSelected(it) },
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
                        recentRecordings = recordViewModel.recentRecordings.collectAsState().value,
                        watchHistory = watchHistory.map { KonomiDataMapper.toDomainModel(it) },
                        selectedProgram = selectedProgram,
                        restoreProgramId = if (isReturningFromPlayer && selectedTabIndex == 2) lastPlayerProgramId?.toIntOrNull() else null,
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        topNavFocusRequester = tabFocusRequesters[2], contentFirstItemRequester = contentFirstItemRequesters[2],
                        onProgramClick = { onProgramSelected(it) }, onLoadMore = { recordViewModel.loadNextPage() },
                        isLoadingMore = recordViewModel.isLoadingMore.collectAsState().value, onShowAllRecordings = onShowAllRecordings
                    )
                    3 -> EpgNavigationContainer(
                        uiState = epgUiState,
                        logoUrls = epgUiState.let { if (it is EpgUiState.Success) it.data.map { wrap -> epgViewModel.getLogoUrl(wrap.channel) } else emptyList() },
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        mainTabFocusRequester = tabFocusRequesters[3], contentRequester = contentFirstItemRequesters[3],
                        selectedProgram = epgSelectedProgram, onProgramSelected = onEpgProgramSelected,
                        isJumpMenuOpen = isEpgJumpMenuOpen,
                        // ★修正: HomeLauncherScreenの引数名に合わせる
                        onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                        onNavigateToPlayer = onNavigateToPlayer, currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                        onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                        restoreChannelId = if (isReturningFromPlayer && selectedTabIndex == 3) lastPlayerChannelId else null,
                        availableTypes = groupedChannels.keys.toList(), onJumpStateChanged = { isEpgJumping = it }, reserves = reserves
                    )
                    4 -> ReserveListScreen(
                        onBack = { tabFocusRequesters[4].safeRequestFocus(TAG) },
                        onProgramClick = onReserveSelected, konomiIp = konomiIp, konomiPort = konomiPort,
                        contentFirstItemRequester = contentFirstItemRequesters[4]
                    )
                }
            }
        }
    }
}