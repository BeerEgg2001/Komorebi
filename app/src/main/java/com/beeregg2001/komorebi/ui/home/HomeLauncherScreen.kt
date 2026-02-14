package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.viewmodel.Channel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val TAG = "EPG_DEBUG_HOME"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DigitalClock(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var isVisible by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, delayMillis = 300),
        label = "ClockFadeIn"
    )

    LaunchedEffect(Unit) {
        isVisible = true
        while (true) {
            currentTime = LocalTime.now()
            delay(1000)
        }
    }

    val timeStr = currentTime.format(DateTimeFormatter.ofPattern("HH:mm"))

    Text(
        text = timeStr,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            fontSize = 20.sp
        ),
        modifier = modifier.graphicsLayer(alpha = alpha)
    )
}

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
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialTabIndex) {
        if (selectedTabIndex != initialTabIndex) {
            selectedTabIndex = initialTabIndex
        }
    }

    val isFullScreenMode = (selectedChannel != null) || (selectedProgram != null) ||
            (epgSelectedProgram != null) || isSettingsOpen

    val epgUiState = epgViewModel.uiState
    val currentBroadcastingType by epgViewModel.selectedBroadcastingType.collectAsState()

    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val isRecordingLoadingMore by recordViewModel.isLoadingMore.collectAsState()

    val watchHistoryPrograms = remember(watchHistory) { watchHistory.map { it.toRecordedProgram() } }

    val logoUrls = remember(epgUiState) {
        if (epgUiState is EpgUiState.Success) {
            epgUiState.data.map { epgViewModel.getLogoUrl(it.channel) }
        } else {
            emptyList()
        }
    }

    var cachedLogoUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(logoUrls) { if (logoUrls.isNotEmpty()) cachedLogoUrls = logoUrls }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val settingsFocusRequester = remember { FocusRequester() }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }

    var topNavHasFocus by remember { mutableStateOf(false) }
    var internalLastPlayerChannelId by remember(lastPlayerChannelId) { mutableStateOf(lastPlayerChannelId) }
    val availableTypes = remember(groupedChannels) { groupedChannels.keys.toList() }

    // ★フォーカスガード
    var isFocusGuardActive by remember { mutableStateOf(false) }

    LaunchedEffect(isEpgJumpMenuOpen, isRecordListOpen) {
        if (isEpgJumpMenuOpen || isRecordListOpen) {
            isFocusGuardActive = true
        } else {
            // ★高速化のため、クールダウンを 800ms -> 300ms に短縮
            delay(300)
            isFocusGuardActive = false
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            if (!topNavHasFocus) {
                runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
            } else {
                if (selectedTabIndex > 0) {
                    selectedTabIndex = 0
                    onTabChange(0)
                    delay(50); runCatching { tabFocusRequesters[0].requestFocus() }
                } else {
                    onFinalBack()
                }
            }
            onBackTriggered()
        }
    }

    LaunchedEffect(isSettingsOpen) {
        if (!isSettingsOpen && !isFullScreenMode) {
            if (lastPlayerChannelId == null && lastPlayerProgramId == null) {
                delay(100); settingsFocusRequester.requestFocus()
            }
        }
    }

    var isInitialFocusSet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        onUiReady()
        if (!isInitialFocusSet && !isReturningFromPlayer && lastPlayerChannelId == null && lastPlayerProgramId == null) {
            delay(100); runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
            isInitialFocusSet = true
        }
    }

    LaunchedEffect(isReturningFromPlayer, selectedTabIndex) {
        if (isReturningFromPlayer) {
            delay(150)
            if (selectedTabIndex == 0) {
                runCatching { tabFocusRequesters[0].requestFocus() }
            } else if (selectedTabIndex == 3) {
                runCatching { contentFirstItemRequesters[3].requestFocus() }
            }
            onReturnFocusConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
            AnimatedVisibility(
                visible = !isFullScreenMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, start = 40.dp, end = 40.dp)
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
                                    if (isFocusGuardActive) {
                                        if (!isEpgJumpMenuOpen && !isRecordListOpen) {
                                            scope.launch { runCatching { contentFirstItemRequesters[selectedTabIndex].requestFocus() } }
                                        }
                                        return@Tab
                                    }

                                    if (selectedTabIndex != index) {
                                        selectedTabIndex = index
                                        onTabChange(index)
                                        onReturnFocusConsumed()
                                    }
                                },
                                modifier = Modifier
                                    .focusRequester(tabFocusRequesters[index])
                                    .focusProperties {
                                        down = contentFirstItemRequesters[index]
                                        if (index == tabs.size - 1) right = settingsFocusRequester
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
                                down = contentFirstItemRequesters[selectedTabIndex]
                            }
                    ) {
                        Icon(Icons.Default.Settings, "設定", tint = if (topNavHasFocus || !isSettingsOpen) Color.White else Color.Gray)
                    }
                }
            }

            // ★高速化の肝: AnimatedContent ではなく Crossfade を使用
            // Crossfade はレイアウトのサイズ変更を伴わないため、フォーカスの荒ぶりが発生しません。
            Box(modifier = Modifier.weight(1f)) {
                Crossfade(
                    targetState = selectedTabIndex,
                    animationSpec = tween(durationMillis = 150), // スピーディーな切り替え
                    label = "TabContentTransition"
                ) { targetIndex ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (targetIndex) {
                            0 -> HomeContents(
                                lastWatchedChannels = lastChannels, watchHistory = watchHistory,
                                onChannelClick = onChannelClick, onHistoryClick = { onProgramSelected(it.toRecordedProgram()) },
                                konomiIp = konomiIp, konomiPort = konomiPort, mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                externalFocusRequester = contentFirstItemRequesters[0], tabFocusRequester = tabFocusRequesters[0],
                                lastFocusedChannelId = internalLastPlayerChannelId, lastFocusedProgramId = lastPlayerProgramId
                            )
                            1 -> LiveContent(
                                groupedChannels = groupedChannels, selectedChannel = selectedChannel, lastWatchedChannel = null,
                                onChannelClick = onChannelClick, onFocusChannelChange = { internalLastPlayerChannelId = it },
                                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort, konomiIp = konomiIp, konomiPort = konomiPort,
                                topNavFocusRequester = tabFocusRequesters[1], contentFirstItemRequester = contentFirstItemRequesters[1],
                                onPlayerStateChanged = { }, lastFocusedChannelId = internalLastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && selectedTabIndex == 1, onReturnFocusConsumed = onReturnFocusConsumed
                            )
                            2 -> EpgNavigationContainer(
                                uiState = epgUiState, logoUrls = cachedLogoUrls, mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                mainTabFocusRequester = tabFocusRequesters[2], contentRequester = contentFirstItemRequesters[2],
                                selectedProgram = epgSelectedProgram, onProgramSelected = onEpgProgramSelected,
                                isJumpMenuOpen = isEpgJumpMenuOpen, onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                onNavigateToPlayer = onNavigateToPlayer, currentType = currentBroadcastingType,
                                onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                                restoreChannelId = if (isReturningFromPlayer && selectedTabIndex == 2) internalLastPlayerChannelId else null,
                                availableTypes = availableTypes
                            )
                            3 -> {
                                Crossfade(
                                    targetState = isRecordListOpen,
                                    label = "VideoTabTransition",
                                    animationSpec = tween(300)
                                ) { isOpen ->
                                    if (isOpen) {
                                        RecordListScreen(
                                            recentRecordings = recentRecordings, konomiIp = konomiIp, konomiPort = konomiPort,
                                            onProgramClick = onProgramSelected, onLoadMore = { recordViewModel.loadNextPage() },
                                            isLoadingMore = isRecordingLoadingMore, onBack = onCloseRecordList
                                        )
                                    } else {
                                        VideoTabContent(
                                            recentRecordings = recentRecordings, watchHistory = watchHistoryPrograms,
                                            selectedProgram = selectedProgram, konomiIp = konomiIp, konomiPort = konomiPort,
                                            topNavFocusRequester = tabFocusRequesters[3],
                                            contentFirstItemRequester = contentFirstItemRequesters[3],
                                            onProgramClick = onProgramSelected, onLoadMore = { recordViewModel.loadNextPage() },
                                            isLoadingMore = isRecordingLoadingMore, onShowAllRecordings = onShowAllRecordings
                                        )
                                    }
                                }
                            }
                            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("${tabs[targetIndex]} コンテンツは準備中です", color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}