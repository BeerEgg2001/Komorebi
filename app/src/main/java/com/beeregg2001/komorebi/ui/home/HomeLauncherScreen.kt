package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.theme.SettingsScreen
import com.beeregg2001.komorebi.viewmodel.Channel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.delay

val loadedTabs = mutableStateListOf<Int>()

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
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
    onNavigateToPlayer: (String, String, String) -> Unit,
    lastPlayerChannelId: String? = null,
    isSettingsOpen: Boolean = false,
    onSettingsToggle: (Boolean) -> Unit = {},
    onShowAllRecordings: () -> Unit // 追加：録画一覧への遷移コールバック
) {
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }

    val isFullScreenMode = (selectedChannel != null) || (selectedProgram != null) ||
            (epgSelectedProgram != null) || isSettingsOpen
    var isNavigatingToTabRow by remember { mutableStateOf(false) }

    val epgUiState = epgViewModel.uiState
    val currentBroadcastingType by epgViewModel.selectedBroadcastingType.collectAsState()

    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val watchHistoryPrograms = remember(watchHistory) { watchHistory.map { it.toRecordedProgram() } }

    val logoUrls = remember(epgUiState) {
        if (epgUiState is EpgUiState.Success) {
            epgUiState.data.map { epgViewModel.getLogoUrl(it.channel) }
        } else {
            emptyList()
        }
    }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val settingsFocusRequester = remember { FocusRequester() }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }
    var tabRowHasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTabIndex) {
        if (!loadedTabs.contains(selectedTabIndex)) {
            loadedTabs.add(selectedTabIndex)
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            if (selectedTabIndex > 0) {
                selectedTabIndex = 0
                onTabChange(0)
                tabFocusRequesters[0].requestFocus()
            } else {
                onFinalBack()
            }
            onBackTriggered()
        }
    }

    LaunchedEffect(isSettingsOpen) {
        if (!isSettingsOpen && !isFullScreenMode) {
            delay(100)
            settingsFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        onUiReady()
        if (lastPlayerChannelId == null) {
            delay(100)
            runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            AnimatedVisibility(
                visible = !isFullScreenMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, start = 40.dp, end = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .focusable(!isFullScreenMode)
                            .onFocusChanged { tabRowHasFocus = it.hasFocus }
                            .focusGroup(),
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
                                    if (!isNavigatingToTabRow && selectedTabIndex != index) {
                                        selectedTabIndex = index
                                        onTabChange(index)
                                    }
                                },
                                modifier = Modifier
                                    .focusRequester(tabFocusRequesters[index])
                                    .focusProperties {
                                        if (index == tabs.size - 1) {
                                            right = settingsFocusRequester
                                        }
                                        down = FocusRequester.Default
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
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "設定",
                            tint = if (tabRowHasFocus && !isSettingsOpen) Color.Gray else Color.White
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = selectedTabIndex,
                    contentKey = { it },
                    transitionSpec = {
                        fadeIn(animationSpec = androidx.compose.animation.core.tween(500)) togetherWith
                                fadeOut(animationSpec = androidx.compose.animation.core.tween(500))
                    },
                    label = "TabContentTransition"
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
                            lastWatchedChannel = null,
                            onChannelClick = onChannelClick,
                            onFocusChannelChange = { },
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            topNavFocusRequester = tabFocusRequesters[1],
                            contentFirstItemRequester = contentFirstItemRequesters[1],
                            onPlayerStateChanged = { }
                        )
                        2 -> EpgNavigationContainer(
                            uiState = epgUiState,
                            logoUrls = logoUrls,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            mainTabFocusRequester = tabFocusRequesters[2],
                            contentRequester = contentFirstItemRequesters[2],
                            selectedProgram = epgSelectedProgram,
                            onProgramSelected = onEpgProgramSelected,
                            isJumpMenuOpen = isEpgJumpMenuOpen,
                            onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                            onNavigateToPlayer = onNavigateToPlayer,
                            currentType = currentBroadcastingType,
                            onTypeChanged = { newType -> epgViewModel.updateBroadcastingType(newType) },
                            restoreChannelId = lastPlayerChannelId
                        )
                        3 -> VideoTabContent(
                            recentRecordings = recentRecordings,
                            watchHistory = watchHistoryPrograms,
                            selectedProgram = selectedProgram,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            topNavFocusRequester = tabFocusRequesters[3],
                            contentFirstItemRequester = contentFirstItemRequesters[3],
                            onProgramClick = onProgramSelected,
                        )
                    }
                }
            }
        }

        if (isSettingsOpen) {
            SettingsScreen(onBack = { onSettingsToggle(false) })
        }
    }
}