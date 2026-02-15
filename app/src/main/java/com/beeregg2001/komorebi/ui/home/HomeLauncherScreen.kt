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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

@OptIn(ExperimentalTvMaterial3Api::class)
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
    val watchHistoryPrograms = remember(watchHistory) { watchHistory.map { it.toRecordedProgram() } }

    val logoUrls = remember(epgUiState) {
        if (epgUiState is EpgUiState.Success) epgUiState.data.map { epgViewModel.getLogoUrl(it.channel) } else emptyList()
    }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val settingsFocusRequester = remember { FocusRequester() }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }

    var internalLastPlayerChannelId by remember(lastPlayerChannelId) { mutableStateOf(lastPlayerChannelId) }

    // ★ジャンプ中フラグ
    var isEpgJumping by remember { mutableStateOf(false) }

    // ★トップナビがフォーカスを持っているかの監視
    var topNavHasFocus by remember { mutableStateOf(false) }

    val isFullScreenMode = selectedChannel != null || selectedProgram != null || epgSelectedProgram != null || isSettingsOpen || isRecordListOpen

    LaunchedEffect(Unit) {
        delay(500)
        runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
    }

    // ★戻るボタンの修正
    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            if (!topNavHasFocus) {
                // 1. コンテンツ内にフォーカスがある場合は、まずトップナビへ移動（タブは変えない）
                runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
            } else {
                // 2. 既にトップナビにいる場合
                if (selectedTabIndex > 0) {
                    // ホーム以外のタブならホームタブへ移動
                    selectedTabIndex = 0
                    onTabChange(0)
                    delay(50)
                    runCatching { tabFocusRequesters[0].requestFocus() }
                } else {
                    // ホームタブならアプリ終了/ダイアログ
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
                        .padding(top = 18.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { topNavHasFocus = it.hasFocus }, // ★追加
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DigitalClock()
                    Spacer(modifier = Modifier.width(32.dp))
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier.weight(1f).focusGroup(),
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            TabRowDefaults.UnderlinedIndicator(currentTabPosition = tabPositions[selectedTabIndex], doesTabRowHaveFocus = doesTabRowHaveFocus, activeColor = Color.White)
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onFocus = { if (selectedTabIndex != index) { selectedTabIndex = index; onTabChange(index) } },
                                modifier = Modifier
                                    .focusRequester(tabFocusRequesters[index])
                                    .focusProperties {
                                        down = contentFirstItemRequesters[index]
                                        // ★番組表がジャンプを完了させるまで、ナビへの進入を物理的に拒絶する
                                        canFocus = !(selectedTabIndex == 2 && isEpgJumping)
                                    }
                            ) {
                                Text(text = title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = if (selectedTabIndex == index) Color.White else Color.Gray)
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
                        lastWatchedChannels = lastChannels, watchHistory = watchHistory, onChannelClick = onChannelClick,
                        onHistoryClick = { onProgramSelected(it.toRecordedProgram()) }, konomiIp = konomiIp, konomiPort = konomiPort,
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort, externalFocusRequester = contentFirstItemRequesters[0],
                        tabFocusRequester = tabFocusRequesters[0], lastFocusedChannelId = internalLastPlayerChannelId, lastFocusedProgramId = lastPlayerProgramId
                    )
                    1 -> LiveContent(
                        channelViewModel = channelViewModel,
                        groupedChannels = groupedChannels,
                        selectedChannel = selectedChannel,
                        onChannelClick = onChannelClick,
                        onFocusChannelChange = { internalLastPlayerChannelId = it },
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort, konomiIp = konomiIp, konomiPort = konomiPort,
                        topNavFocusRequester = tabFocusRequesters[1],
                        contentFirstItemRequester = contentFirstItemRequesters[1],
                        onPlayerStateChanged = { },
                        lastFocusedChannelId = internalLastPlayerChannelId,
                        isReturningFromPlayer = isReturningFromPlayer && selectedTabIndex == 1,
                        onReturnFocusConsumed = onReturnFocusConsumed
                    )
                    2 -> EpgNavigationContainer(
                        uiState = epgUiState, logoUrls = logoUrls, mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        mainTabFocusRequester = tabFocusRequesters[2], contentRequester = contentFirstItemRequesters[2],
                        selectedProgram = epgSelectedProgram, onProgramSelected = onEpgProgramSelected,
                        isJumpMenuOpen = isEpgJumpMenuOpen, onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                        onNavigateToPlayer = onNavigateToPlayer, currentType = currentBroadcastingType,
                        onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                        restoreChannelId = if (isReturningFromPlayer && selectedTabIndex == 2) lastPlayerChannelId else null,
                        availableTypes = groupedChannels.keys.toList(),
                        onJumpStateChanged = { isEpgJumping = it }
                    )
                    3 -> VideoTabContent(
                        recentRecordings = recentRecordings, watchHistory = watchHistoryPrograms, selectedProgram = selectedProgram,
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        topNavFocusRequester = tabFocusRequesters[3], // ★ビデオタブのフォーカス先を渡す
                        contentFirstItemRequester = contentFirstItemRequesters[3], onProgramClick = onProgramSelected,
                        onLoadMore = { recordViewModel.loadNextPage() }, isLoadingMore = isRecordingLoadingMore, onShowAllRecordings = onShowAllRecordings
                    )
                }
            }
        }
    }
}