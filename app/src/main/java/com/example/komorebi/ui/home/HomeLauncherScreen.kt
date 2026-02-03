package com.example.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.program.EpgConfig
import com.example.komorebi.ui.program.EpgScreen
import com.example.komorebi.ui.program.LocalEpgConfig
import com.example.komorebi.ui.video.VideoPlayerScreen
import com.example.komorebi.viewmodel.Channel
import com.example.komorebi.viewmodel.ChannelViewModel
import com.example.komorebi.viewmodel.EpgViewModel
import com.example.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
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
) {
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }
    var isContentReady by remember { mutableStateOf(false) }

    val isFullScreenMode = (selectedChannel != null) || (selectedProgram != null)
    var isNavigatingToTabRow by remember { mutableStateOf(false) }

    // --- 番組表用の基本設定計算 ---
    val configuration = LocalConfiguration.current
    val epgConfig = remember(configuration.screenWidthDp) {
        val timeColumnWidth = 55.dp
        val visibleChannelCount = 7
        EpgConfig(
            dpPerMinute = 1.3f,
            timeColumnWidth = timeColumnWidth,
            headerHeight = 48.dp,
            channelWidth = (configuration.screenWidthDp.dp - timeColumnWidth) / visibleChannelCount
        )
    }

    // --- フォーカス制御用 ---
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }
    val epgTabFocusRequester = remember { FocusRequester() }
    val epgFirstCellFocusRequester = remember { FocusRequester() }

    var tabRowHasFocus by remember { mutableStateOf(false) }
    var selectedBroadcastingType by rememberSaveable { mutableStateOf("GR") }
    var epgBroadcastingTabHasFocus by remember { mutableStateOf(false) }

    // --- データ購読 ---
    val recentRecordings by channelViewModel.recentRecordings.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val watchHistoryPrograms = remember(watchHistory) { watchHistory.map { it.toRecordedProgram() } }
    val loadedTabs = remember { mutableStateListOf<Int>() }

    // バックボタン通知の監視
    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            when {
                isFullScreenMode -> { /* root側で処理 */ }
                tabs[selectedTabIndex] == "番組表" && !tabRowHasFocus -> {
                    if (epgBroadcastingTabHasFocus) {
                        isNavigatingToTabRow = true
                        runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
                        delay(200)
                        isNavigatingToTabRow = false
                    } else {
                        runCatching { epgTabFocusRequester.requestFocus() }
                    }
                }
                !tabRowHasFocus -> {
                    isNavigatingToTabRow = true
                    runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
                    delay(200)
                    isNavigatingToTabRow = false
                }
                else -> onFinalBack()
            }
            onBackTriggered()
        }
    }

    // タブ切り替え制御
    LaunchedEffect(selectedTabIndex) {
        if (!loadedTabs.contains(selectedTabIndex)) {
            isContentReady = false
            delay(200)
            yield()
            loadedTabs.add(selectedTabIndex)
        }
        isContentReady = true
    }

    LaunchedEffect(Unit) {
        onUiReady()
        delay(500)
        runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
    }

    CompositionLocalProvider(LocalEpgConfig provides epgConfig) {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

            // --- ナビゲーションバー (TabRow) ---
            AnimatedVisibility(
                visible = !isFullScreenMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { tabRowHasFocus = it.hasFocus }
                        .focusGroup(),
                    indicator = { tabPositions, doesTabRowHaveFocus ->
                        TabRowDefaults.UnderlinedIndicator(
                            currentTabPosition = tabPositions[selectedTabIndex],
                            doesTabRowHaveFocus = doesTabRowHaveFocus,
                            activeColor = Color.White,
                            inactiveColor = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.height(2.dp)
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onFocus = {
                                if (epgSelectedProgram == null && !isNavigatingToTabRow && selectedTabIndex != index) {
                                    selectedTabIndex = index
                                    onTabChange(index)
                                }
                            },
                            modifier = Modifier
                                .focusRequester(tabFocusRequesters[index])
                                .focusProperties {
                                    down = if (selectedTabIndex == index && isContentReady) {
                                        when (tabs[index]) {
                                            "番組表" -> epgTabFocusRequester
                                            else -> contentFirstItemRequesters[index]
                                        }
                                    } else {
                                        FocusRequester.Default
                                    }
                                }
                        ) {
                            Text(
                                text = title,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 16.sp
                                ),
                                color = if (tabRowHasFocus || selectedTabIndex == index) Color.White else Color.White.copy(0.4f)
                            )
                        }
                    }
                }
            }

            // --- コンテンツエリア ---
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = if (isContentReady || loadedTabs.contains(selectedTabIndex)) selectedTabIndex else -1,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(150))
                    },
                    label = "TabContent"
                ) { targetIndex ->
                    when (targetIndex) {
                        0 -> HomeContents(
                            lastWatchedChannels = lastChannels,
                            watchHistory = watchHistory,
                            onChannelClick = onChannelClick,
                            onHistoryClick = { history -> onProgramSelected(history.toRecordedProgram()) },
                            konomiIp = konomiIp, konomiPort = konomiPort,
                            mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                            externalFocusRequester = contentFirstItemRequesters[0],
                            tabFocusRequester = tabFocusRequesters[0]
                        )
                        1 -> LiveContent(
                            groupedChannels = groupedChannels,
                            selectedChannel = selectedChannel,
                            onChannelClick = { channel ->
                                if (channel != null) homeViewModel.saveLastChannel(channel)
                                onChannelClick(channel)
                            },
                            lastWatchedChannel = lastChannels.firstOrNull(),
                            onFocusChannelChange = { },
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            topNavFocusRequester = tabFocusRequesters[1],
                            contentFirstItemRequester = contentFirstItemRequesters[1],
                            onPlayerStateChanged = { }
                        )
                        2 -> EpgScreen(
                            viewModel = epgViewModel,
                            topTabFocusRequester = tabFocusRequesters[2],
                            tabFocusRequester = epgTabFocusRequester,
                            firstCellFocusRequester = epgFirstCellFocusRequester,
                            onBroadcastingTabFocusChanged = { epgBroadcastingTabHasFocus = it },
                            isJumpMenuOpen = isEpgJumpMenuOpen,
                            onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                            selectedProgram = epgSelectedProgram,
                            onProgramSelected = onEpgProgramSelected,
                            onChannelSelected = { channelId ->
                                onEpgProgramSelected(null)
                                val targetChannel = groupedChannels.values.flatten().find { it.id == channelId }
                                if (targetChannel != null) onChannelClick(targetChannel)
                            }
                        )
                        3 -> VideoTabContent(
                            recentRecordings = recentRecordings,
                            watchHistory = watchHistoryPrograms,
                            konomiIp = konomiIp, konomiPort = konomiPort,
                            externalFocusRequester = contentFirstItemRequesters[3],
                            onProgramClick = onProgramSelected
                        )
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    if (selectedProgram != null) {
        VideoPlayerScreen(
            program = selectedProgram,
            konomiIp = konomiIp, konomiPort = konomiPort,
            onBackPressed = { onProgramSelected(null) }
        )
    }
}