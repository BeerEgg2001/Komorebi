package com.example.komorebi.ui.home

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.program.EpgScreen
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
    lastWatchedChannel: Channel?,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    onChannelClick: (Channel) -> Unit,
    onTabChange: (Int) -> Unit,
    initialTabIndex: Int = 0,
    onUiReady: () -> Unit,
) {
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }
    var isContentReady by remember { mutableStateOf(false) }

    // --- フォーカス制御 ---
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }
    val epgTabFocusRequester = remember { FocusRequester() }
    val epgFirstCellFocusRequester = remember { FocusRequester() }

    // 各種状態保持
    var tabRowHasFocus by remember { mutableStateOf(false) }
    var selectedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var epgSelectedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var selectedBroadcastingType by rememberSaveable { mutableStateOf("GR") }
    var lastFocusedChannelId by remember { mutableStateOf<String?>(null) }

    // --- データ購読 ---
    val recentRecordings by channelViewModel.recentRecordings.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState()
    val watchHistoryPrograms = remember(watchHistory) { watchHistory.map { it.toRecordedProgram() } }

    // ★ キャッシュ管理用のリスト
    val loadedTabs = remember { mutableStateListOf<Int>() }

    // タブ切り替え時のライフサイクル管理
    LaunchedEffect(selectedTabIndex) {
        val alreadyLoaded = loadedTabs.contains(selectedTabIndex)

        if (!alreadyLoaded) {
            isContentReady = false
            // 初回ロード時は少し長めに待機して描画の安定を図る
            delay(350)
            yield()
            loadedTabs.add(selectedTabIndex)
        }

        // キャッシュがある場合でも、一瞬スレッドを解放してタブ移動アニメを優先
        isContentReady = true
    }

    LaunchedEffect(Unit) {
        onUiReady()
        delay(150)
        tabFocusRequesters[selectedTabIndex].requestFocus()
    }

    BackHandler(enabled = (selectedProgram != null) || (!tabRowHasFocus)) {
        when {
            selectedProgram != null -> { selectedProgram = null }
            !tabRowHasFocus -> { tabFocusRequesters[selectedTabIndex].requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // --- ナビゲーションバー (TabRow) ---
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
                        if (epgSelectedProgram == null && selectedTabIndex != index) {
                            // キャッシュがない場合のみロード中表示に倒す
                            if (!loadedTabs.contains(index)) {
                                isContentReady = false
                            }
                            selectedTabIndex = index
                            onTabChange(index)
                        }
                    },
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters[index])
                        .focusProperties {
                            down = if (title == "番組表") epgFirstCellFocusRequester else contentFirstItemRequesters[index]
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

        // --- コンテンツエリア ---
        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            tabs.forEachIndexed { index, _ ->
                // loadedTabs に含まれている、かつ現在のタブである場合のみ表示
                val isVisible = (selectedTabIndex == index) && isContentReady

                androidx.compose.animation.AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(animationSpec = tween(250)),
                    exit = fadeOut(animationSpec = tween(150)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 各タブの中身
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { up = tabFocusRequesters[index] }
                    ) {
                        when (index) {
                            0 -> HomeContents(
                                lastWatchedChannels = lastChannels,
                                watchHistory = watchHistory,
                                onChannelClick = onChannelClick,
                                onHistoryClick = { history -> selectedProgram = history.toRecordedProgram() },
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                externalFocusRequester = contentFirstItemRequesters[0],
                                tabFocusRequester = tabFocusRequesters[0]
                            )
                            1 -> LiveContent(
                                groupedChannels = groupedChannels,
                                lastWatchedChannel = lastWatchedChannel,
                                lastFocusedChannelId = lastFocusedChannelId,
                                onFocusChannelChange = { lastFocusedChannelId = it },
                                mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                                onChannelClick = onChannelClick,
                                externalFocusRequester = contentFirstItemRequesters[1]
                            )
                            2 -> EpgScreen(
                                viewModel = epgViewModel,
                                topTabFocusRequester = tabFocusRequesters[2],
                                tabFocusRequester = epgTabFocusRequester,
                                firstCellFocusRequester = epgFirstCellFocusRequester,
                                selectedProgram = epgSelectedProgram,
                                onProgramSelected = { epgSelectedProgram = it },
                                selectedBroadcastingType = selectedBroadcastingType,
                                onTypeSelected = { selectedBroadcastingType = it },
                                onChannelSelected = { channelId ->
                                    epgSelectedProgram = null
                                    val targetChannel = groupedChannels.values.flatten().find { it.id == channelId }
                                    if (targetChannel != null) onChannelClick(targetChannel)
                                }
                            )
                            3 -> VideoTabContent(
                                recentRecordings = recentRecordings,
                                watchHistory = watchHistoryPrograms,
                                konomiIp = konomiIp, konomiPort = konomiPort,
                                externalFocusRequester = contentFirstItemRequesters[3],
                                onProgramClick = { selectedProgram = it }
                            )
                        }
                    }
                }
            }

            // ロード中（キャッシュもなく、準備もできていない時）
            if (!isContentReady && !loadedTabs.contains(selectedTabIndex)) {
                LoadingScreen()
            }
        }
    }

    if (selectedProgram != null) {
        VideoPlayerScreen(
            program = selectedProgram!!,
            konomiIp = konomiIp, konomiPort = konomiPort,
            onBackPressed = {
                selectedProgram = null
                homeViewModel.refreshHomeData()
            }
        )
    }
}