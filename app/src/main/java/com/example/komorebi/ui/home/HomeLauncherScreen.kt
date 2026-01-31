package com.example.komorebi.ui.home

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import com.example.komorebi.data.local.entity.toRecordedProgram
import com.example.komorebi.data.model.KonomiHistoryProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.program.EpgScreen
import com.example.komorebi.ui.video.VideoPlayerScreen
import com.example.komorebi.viewmodel.Channel
import com.example.komorebi.viewmodel.ChannelViewModel
import com.example.komorebi.viewmodel.EpgViewModel
import com.example.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel = viewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    epgViewModel: EpgViewModel = hiltViewModel(),
    groupedChannels: Map<String, List<Channel>>,
    lastWatchedChannel: Channel?,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    onChannelClick: (Channel) -> Unit,
    initialTabIndex: Int = 0,
    onSettingsClick: () -> Unit = {}
) {
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")

    var selectedTabIndex by remember { mutableIntStateOf(if (lastWatchedChannel != null) 1 else initialTabIndex) }
    var focusedTabIndex by remember { mutableIntStateOf(selectedTabIndex) }
    var tabRowHasFocus by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var selectedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var lastFocusedChannelId by remember { mutableStateOf<String?>(null) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val videoContentFocusRequester = remember { FocusRequester() }
    val homeContentFocusRequester = remember { FocusRequester() }

    val activity = (LocalContext.current as? Activity)
    val recentRecordings by channelViewModel.recentRecordings.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState(initial = emptyList<KonomiHistoryProgram>())
    // 追加: 放送波タブ用のRequesterを定義
    val epgTabFocusRequester = remember { FocusRequester() }
    val epgFirstCellFocusRequester = remember { FocusRequester() }
    val watchHistoryEntities by homeViewModel.localWatchHistory.collectAsState()

// EntityリストをProgramリストに変換してUIに渡す
    val watchHistoryPrograms = remember(watchHistoryEntities) {
        watchHistoryEntities.map { it.toRecordedProgram() }
    }

    LaunchedEffect(Unit) {
        isVisible = true
        if (lastWatchedChannel == null) {
            delay(500)
            tabFocusRequesters[selectedTabIndex].requestFocus()
        }
    }

    LaunchedEffect(selectedTabIndex) {
        when (tabs[selectedTabIndex]) {
            "ビデオ" -> channelViewModel.fetchRecentRecordings()
            "ホーム" -> homeViewModel.refreshHomeData()
            "番組表" -> epgViewModel.loadEpg()
        }
    }

    BackHandler(enabled = true) {
        when {
            selectedProgram != null -> {
                lastBackPressTime = System.currentTimeMillis()
                selectedProgram = null
                homeViewModel.refreshHomeData()
                videoContentFocusRequester.requestFocus()
            }
            showExitDialog -> showExitDialog = false
            !tabRowHasFocus -> tabFocusRequesters[selectedTabIndex].requestFocus()
            selectedTabIndex != 0 -> {
                selectedTabIndex = 0
                tabFocusRequesters[0].requestFocus()
            }
            else -> showExitDialog = true
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("アプリを終了しますか？") },
            confirmButton = {
                Button(onClick = { activity?.finish() }) { Text("終了") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) { Text("キャンセル") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(1000))
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // --- スリム化・タイポグラフィ最適化されたナビゲーション ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, start = 40.dp, end = 40.dp) // 余白を広げ優雅さを強調
                            .onFocusChanged { tabRowHasFocus = it.hasFocus },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            tabs.forEachIndexed { index, title ->
                                val isEpgTab = title == "番組表"
                                val currentTime = System.currentTimeMillis()
                                val isTabFocusable = selectedProgram == null && (currentTime - lastBackPressTime > 1000)

                                val isSelected = selectedTabIndex == index
                                val isFocused = tabRowHasFocus && focusedTabIndex == index

                                Box(
                                    modifier = Modifier
                                        .focusRequester(tabFocusRequesters[index])
                                        .onFocusChanged { state ->
                                            if (state.isFocused && isTabFocusable) {
                                                focusedTabIndex = index
                                                selectedTabIndex = index
                                            }
                                        }
                                        .focusProperties {
                                            down = when(title) {
                                                "ホーム" -> homeContentFocusRequester
                                                "ビデオ" -> videoContentFocusRequester
                                                "番組表" -> epgTabFocusRequester
                                                else -> FocusRequester.Default
                                            }
                                        }
                                        .focusable(enabled = isTabFocusable)
                                        .clickable(enabled = isTabFocusable) { selectedTabIndex = index }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Light, // 日本語をスタイリッシュにする細いウェイト
                                                letterSpacing = 1.2.sp,        // 文字間隔を広く取り、モダンな印象に
                                                fontSize = 16.sp,              // 大きすぎない繊細なサイズ
                                            ),
                                            color = when {
                                                isSelected -> Color.White
                                                isFocused -> Color.White.copy(alpha = 0.85f)
                                                else -> Color.White.copy(alpha = 0.25f) // 非アクティブをさらに薄くし、洗練さをアップ
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        // インジケーター：選択中のみ表示される細い線
                                        Box(
                                            modifier = Modifier
                                                .width(28.dp)
                                                .height(1.5.dp) // 3dpから1.5dpに細くし、シャープな印象に
                                                .background(if (isSelected) Color.White else Color.Transparent)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f).focusProperties { canFocus = false })

                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "設定",
                                tint = Color.White.copy(alpha = 0.4f), // アイコンも主張を抑える
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // --- コンテンツエリア (ナビゲーションとの一体感を出すため余白を調整) ---
                    Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                        AnimatedContent(
                            targetState = selectedTabIndex,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(200))
                            },
                            label = "TabTransition"
                        ) { targetIndex ->
                            key(targetIndex) {
                                when (tabs[targetIndex]) {
                                    "ホーム" -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            HistoryRow(
                                                historyList = watchHistory,
                                                onHistoryClick = { /* 履歴再生ロジック */ },
                                                modifier = Modifier.focusRequester(homeContentFocusRequester)
                                            )
                                        }
                                    }
                                    "ライブ" -> LiveContent(
                                        groupedChannels = groupedChannels,
                                        lastWatchedChannel = lastWatchedChannel,
                                        lastFocusedChannelId = lastFocusedChannelId,
                                        onFocusChannelChange = { lastFocusedChannelId = it },
                                        mirakurunIp = mirakurunIp,
                                        mirakurunPort = mirakurunPort,
                                        topTabFocusRequester = tabFocusRequesters[1],
                                        onChannelClick = onChannelClick
                                    )
                                    "番組表" -> {
                                        EpgScreen(
                                            viewModel = epgViewModel,
                                            topTabFocusRequester = tabFocusRequesters[2],
                                            tabFocusRequester = epgTabFocusRequester, // 修正
                                            firstCellFocusRequester = epgFirstCellFocusRequester
                                        )
                                    }
                                    "ビデオ" -> VideoTabContent(
                                        recentRecordings = recentRecordings,
                                        watchHistory = watchHistoryPrograms,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        externalFocusRequester = videoContentFocusRequester,
                                        onProgramClick = { program -> selectedProgram = program }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        key(selectedProgram?.id) {
            if (selectedProgram != null) {
                LaunchedEffect(selectedProgram!!.id) {
                    homeViewModel.saveToHistory(selectedProgram!!)
                }
                VideoPlayerScreen(
                    program = selectedProgram!!,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onBackPressed = {
                        lastBackPressTime = System.currentTimeMillis()
                        selectedProgram = null
                        homeViewModel.refreshHomeData()
                    }
                )
            }
        }
    }
}