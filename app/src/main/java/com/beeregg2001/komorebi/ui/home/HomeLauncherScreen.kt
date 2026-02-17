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
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
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
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }

    val epgUiState = epgViewModel.uiState
    val currentBroadcastingType by epgViewModel.selectedBroadcastingType.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val isRecordingLoadingMore by recordViewModel.isLoadingMore.collectAsState()

    val watchHistoryPrograms = remember(watchHistory) {
        watchHistory.map { KonomiDataMapper.toDomainModel(it) }
    }

    val logoUrls = remember(epgUiState) {
        if (epgUiState is EpgUiState.Success) epgUiState.data.map { epgViewModel.getLogoUrl(it.channel) } else emptyList()
    }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val settingsFocusRequester = remember { FocusRequester() }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }

    var internalLastPlayerChannelId by remember(lastPlayerChannelId) { mutableStateOf(lastPlayerChannelId) }
    var isEpgJumping by remember { mutableStateOf(false) }
    var topNavHasFocus by remember { mutableStateOf(false) }

    // ビデオタブ復帰用のID変換
    val restoreProgramIdInt = remember(lastPlayerProgramId) { lastPlayerProgramId?.toIntOrNull() }

    val isFullScreenMode = selectedChannel != null || selectedProgram != null || epgSelectedProgram != null || isSettingsOpen || isRecordListOpen

    LaunchedEffect(Unit) {
        // ★修正（引き算）: プレイヤーから戻ってきた場合(isReturningFromPlayer)は、
        // コンテンツ側がフォーカス復帰処理を行うため、ここではタブへのフォーカス強制を行わない。
        // これにより「一瞬カードに合うがすぐにタブへ飛んでしまう」現象を防ぐ。
        if (!isReturningFromPlayer) {
            delay(500)
            if (!isFullScreenMode) {
                tabFocusRequesters.getOrNull(selectedTabIndex)?.safeRequestFocus(TAG)
            }
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
                    delay(50)
                    tabFocusRequesters[0].safeRequestFocus(TAG)
                } else {
                    onFinalBack()
                }
            }
            onBackTriggered()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullScreenMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(top = 8.dp, start = 40.dp, end = 40.dp)
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
                                activeColor = Color.White
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onFocus = {
                                    if (selectedTabIndex != index) {
                                        selectedTabIndex = index
                                        onTabChange(index)
                                    }
                                },
                                modifier = Modifier
                                    .focusRequester(tabFocusRequesters[index])
                                    .focusProperties {
                                        down = contentFirstItemRequesters[index]
                                        canFocus = !(selectedTabIndex == 2 && isEpgJumping)
                                    }
                            ) {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selectedTabIndex == index) Color.White else Color.Gray
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { onSettingsToggle(true) },
                        modifier = Modifier
                            .focusRequester(settingsFocusRequester)
                            .focusProperties {
                                left = tabFocusRequesters.last()
                                canFocus = !(selectedTabIndex == 2 && isEpgJumping)
                            }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "設定", tint = Color.Gray)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> HomeContents(
                        lastWatchedChannels = lastChannels,
                        watchHistory = watchHistory,
                        onChannelClick = onChannelClick,
                        onHistoryClick = { historyItem ->
                            val programId = historyItem.program.id.toIntOrNull()
                            val betterProgram = recentRecordings.find { it.id == programId }

                            if (betterProgram != null) {
                                Log.d(TAG, "HomeTab: Playing from History with better metadata. ID=$programId")
                                val mergedProgram = betterProgram.copy(playbackPosition = historyItem.playback_position)
                                onProgramSelected(mergedProgram)
                            } else {
                                Log.w(TAG, "HomeTab: Playing from History with incomplete metadata. ID=$programId")
                                onProgramSelected(KonomiDataMapper.toDomainModel(historyItem))
                            }
                        },
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        externalFocusRequester = contentFirstItemRequesters[0],
                        tabFocusRequester = tabFocusRequesters[0],
                        lastFocusedChannelId = internalLastPlayerChannelId,
                        lastFocusedProgramId = lastPlayerProgramId
                    )
                    1 -> LiveContent(
                        channelViewModel = channelViewModel,
                        groupedChannels = groupedChannels,
                        selectedChannel = selectedChannel,
                        onChannelClick = onChannelClick,
                        onFocusChannelChange = { internalLastPlayerChannelId = it },
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        topNavFocusRequester = tabFocusRequesters[1],
                        contentFirstItemRequester = contentFirstItemRequesters[1],
                        onPlayerStateChanged = { },
                        lastFocusedChannelId = internalLastPlayerChannelId,
                        isReturningFromPlayer = isReturningFromPlayer && selectedTabIndex == 1,
                        onReturnFocusConsumed = onReturnFocusConsumed
                    )
                    2 -> EpgNavigationContainer(
                        uiState = epgUiState,
                        logoUrls = logoUrls,
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        mainTabFocusRequester = tabFocusRequesters[2],
                        contentRequester = contentFirstItemRequesters[2],
                        selectedProgram = epgSelectedProgram,
                        onProgramSelected = onEpgProgramSelected,
                        isJumpMenuOpen = isEpgJumpMenuOpen,
                        onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                        onNavigateToPlayer = onNavigateToPlayer,
                        currentType = currentBroadcastingType,
                        onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                        restoreChannelId = if (isReturningFromPlayer && selectedTabIndex == 2) lastPlayerChannelId else null,
                        availableTypes = groupedChannels.keys.toList(),
                        onJumpStateChanged = { isEpgJumping = it }
                    )
                    3 -> VideoTabContent(
                        recentRecordings = recentRecordings,
                        watchHistory = watchHistoryPrograms,
                        selectedProgram = selectedProgram,
                        // ★修正: ビデオタブにも復帰用IDを渡す
                        restoreProgramId = if (isReturningFromPlayer && selectedTabIndex == 3) restoreProgramIdInt else null,
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        topNavFocusRequester = tabFocusRequesters[3],
                        contentFirstItemRequester = contentFirstItemRequesters[3],
                        onProgramClick = { program ->
                            val betterProgram = recentRecordings.find { it.id == program.id }

                            if (betterProgram != null) {
                                Log.d(TAG, "VideoTab: Playing from History with better metadata. ID=${program.id}")
                                val mergedProgram = betterProgram.copy(playbackPosition = program.playbackPosition)
                                onProgramSelected(mergedProgram)
                            } else {
                                Log.w(TAG, "VideoTab: Playing with original metadata. ID=${program.id}")
                                onProgramSelected(program)
                            }
                        },
                        onLoadMore = { recordViewModel.loadNextPage() },
                        isLoadingMore = isRecordingLoadingMore,
                        onShowAllRecordings = onShowAllRecordings
                    )
                }
            }
        }
    }
}