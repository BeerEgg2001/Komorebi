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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import com.example.komorebi.data.local.entity.toRecordedProgram
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.model.KonomiHistoryProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.program.EpgScreen
import com.example.komorebi.ui.video.VideoPlayerScreen
import com.example.komorebi.viewmodel.Channel
import com.example.komorebi.viewmodel.ChannelViewModel
import com.example.komorebi.viewmodel.EpgViewModel
import com.example.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onSettingsClick: () -> Unit = {},
    onUiReady: () -> Unit,
) {
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")
    val scope = rememberCoroutineScope()

    // --- 状態管理 ---
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(if (lastWatchedChannel != null) 1 else initialTabIndex) }
    var focusedTabIndex by remember { mutableIntStateOf(selectedTabIndex) }
    var tabRowHasFocus by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var selectedProgram by remember { mutableStateOf<RecordedProgram?>(null) }
    var epgSelectedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var lastFocusedChannelId by remember { mutableStateOf<String?>(null) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var selectedBroadcastingType by rememberSaveable { mutableStateOf("GR") }
    var isSuppressingTabChange by remember { mutableStateOf(false) }

    // --- フォーカス制御用 ---
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val contentFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val epgTabFocusRequester = remember { FocusRequester() }
    val epgFirstCellFocusRequester = remember { FocusRequester() }
    var isEpgBroadcastingTabFocused by remember { mutableStateOf(false) }

    val activity = (LocalContext.current as? Activity)

    // --- データ購読 ---
    val recentRecordings by channelViewModel.recentRecordings.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState(initial = emptyList())
    val watchHistoryEntities by homeViewModel.localWatchHistory.collectAsState()
    val watchHistoryPrograms = remember(watchHistoryEntities) {
        watchHistoryEntities.map { it.toRecordedProgram() }
    }

    // ★ 描画安定化ロジックの軽量化
    LaunchedEffect(Unit) {
        isVisible = true
        delay(300) // 2秒から大幅短縮。TV向けにはこれくらいで十分
        onUiReady()
        delay(200)
        tabFocusRequesters[selectedTabIndex].requestFocus()
    }

    BackHandler(enabled = true) {
        // ログを出してデバッグすると、ここが2回走っていないか確認できます
        println("DEBUG: Back Pressed. epgSelectedProgram: ${epgSelectedProgram != null}")

        when {
            // A. 番組詳細が開いているなら、詳細を閉じる「だけ」で終了
            epgSelectedProgram != null -> {
                isSuppressingTabChange = true
                epgSelectedProgram = null

                scope.launch {
                    // 確実に詳細が消えてからフォーカスを戻す
                    delay(100)
                    epgFirstCellFocusRequester.requestFocus()
                    delay(400)
                    isSuppressingTabChange = false
                }
                // when文なので、ここで処理は止まり、下の「ホームに戻る」は実行されません
            }

            // B. プレイヤーが開いているならプレイヤーを閉じる
            selectedProgram != null -> {
                selectedProgram = null
                homeViewModel.refreshHomeData()
            }

            // C. 番組表タブの中にいる場合の挙動
            selectedTabIndex == 2 -> {
                if (tabRowHasFocus) {
                    // タブにフォーカスがあるならホームへ
                    selectedTabIndex = 0
                    tabFocusRequesters[0].requestFocus()
                } else {
                    // コンテンツ（番組表内）にいるなら上部タブへフォーカスを戻す
                    tabFocusRequesters[2].requestFocus()
                }
            }

            // D. その他のタブならホームへ
            selectedTabIndex != 0 -> {
                selectedTabIndex = 0
                tabFocusRequesters[0].requestFocus()
            }

            // E. 最後にアプリ終了確認
            else -> {
                showExitDialog = true
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("アプリを終了しますか？") },
            confirmButton = { Button(onClick = { activity?.finish() }) { Text("終了") } },
            dismissButton = { OutlinedButton(onClick = { showExitDialog = false }) { Text("キャンセル") } }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(500))
        ) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
                // --- ナビゲーションバー ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { tabRowHasFocus = it.hasFocus },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        tabs.forEachIndexed { index, title ->
                            val isSelected = selectedTabIndex == index
                            val isFocused = tabRowHasFocus && focusedTabIndex == index

                            Box(
                                modifier = Modifier
                                    .focusRequester(tabFocusRequesters[index])
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            focusedTabIndex = index
                                            // ★ 抑制フラグだけでなく、「詳細画面が非表示であること」を条件に加える
                                            if (!isSuppressingTabChange && epgSelectedProgram == null) {
                                                selectedTabIndex = index
                                            }
                                        }
                                    }
                                    .focusProperties {
                                        down = when (title) {
                                            "番組表" -> epgTabFocusRequester
                                            else -> contentFocusRequesters[index]
                                        }
                                    }
                                    .focusable()
                                    .clickable { selectedTabIndex = index }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Light,
                                            letterSpacing = 1.2.sp,
                                            fontSize = 16.sp,
                                        ),
                                        color = if (isSelected) Color.White else if (isFocused) Color.White.copy(0.85f) else Color.White.copy(0.25f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(modifier = Modifier.width(28.dp).height(1.5.dp).background(if (isSelected) Color.White else Color.Transparent))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "設定", tint = Color.White.copy(0.4f), modifier = Modifier.size(20.dp))
                    }
                }

                // --- コンテンツエリア (表示のみに絞り、かつ状態は保持する) ---
                Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                    // ホーム
                    if (selectedTabIndex == 0) {
                        HistoryRow(
                            historyList = watchHistory,
                            onHistoryClick = { /* 履歴再生 */ },
                            modifier = Modifier.focusRequester(contentFocusRequesters[0])
                        )
                    }

                    // ライブ（最重要：ここで LiveContent を呼ぶ）
                    // ライブタブが非表示の時は、LiveContent 自体が再構成されないようにする
                    if (selectedTabIndex == 1) {
                        LiveContent(
                            modifier = Modifier.focusRequester(contentFocusRequesters[1]),
                            groupedChannels = groupedChannels,
                            lastWatchedChannel = lastWatchedChannel,
                            lastFocusedChannelId = lastFocusedChannelId,
                            onFocusChannelChange = { lastFocusedChannelId = it },
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            topTabFocusRequester = tabFocusRequesters[1],
                            onChannelClick = onChannelClick,
                        )
                    }

                    // 番組表
                    if (selectedTabIndex == 2) {
                        EpgScreen(
                            viewModel = epgViewModel,
                            topTabFocusRequester = tabFocusRequesters[2],
                            tabFocusRequester = epgTabFocusRequester,
                            onBroadcastingTabFocusChanged = { isEpgBroadcastingTabFocused = it },
                            firstCellFocusRequester = epgFirstCellFocusRequester,
                            selectedProgram = epgSelectedProgram,
                            onProgramSelected = { program ->
                                // ★ 修正: program が null（戻るボタン押下時）でも、タブを移動させない
                                epgSelectedProgram = program
                            },
                            selectedBroadcastingType = selectedBroadcastingType,
                            onTypeSelected = { selectedBroadcastingType = it },
                            onChannelSelected = { channelId ->
                                val targetChannel = groupedChannels.values.flatten().find { it.id == channelId }
                                if (targetChannel != null) {
                                    // epgSelectedProgram = null // ここで消すと戻る場所がなくなるため保持するか、
                                    // 視聴画面（VideoPlayerScreen）を EpgScreen の上に重ねる構成にします
                                    onChannelClick(targetChannel)
                                }
                            }
                        )
                    }

                    // ビデオ
                    if (selectedTabIndex == 3) {
                        VideoTabContent(
                            recentRecordings = recentRecordings,
                            watchHistory = watchHistoryPrograms,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            externalFocusRequester = contentFocusRequesters[3],
                            onProgramClick = { selectedProgram = it }
                        )
                    }
                }
            }
        }

        // プレイヤー表示 (変更なし)
        if (selectedProgram != null) {
            LaunchedEffect(selectedProgram!!.id) { homeViewModel.saveToHistory(selectedProgram!!) }
            VideoPlayerScreen(
                program = selectedProgram!!,
                konomiIp = konomiIp, konomiPort = konomiPort,
                onBackPressed = {
                    lastBackPressTime = System.currentTimeMillis()
                    selectedProgram = null
                    homeViewModel.refreshHomeData()
                }
            )
        }
    }


        // 番組表詳細 (EpgProgram用)
//        if (epgSelectedProgram != null) {
//            EpgSelectedProgramDetail(
//                program = epgSelectedProgram!!,
//                onClose = { epgSelectedProgram = null }
//            )
//        }

        // ビデオプレイヤー (RecordedProgram用)
        key(selectedProgram?.id) {
            if (selectedProgram != null) {
                LaunchedEffect(selectedProgram!!.id) { homeViewModel.saveToHistory(selectedProgram!!) }
                VideoPlayerScreen(
                    program = selectedProgram!!,
                    konomiIp = konomiIp, konomiPort = konomiPort,
                    onBackPressed = {
                        lastBackPressTime = System.currentTimeMillis()
                        selectedProgram = null
                        homeViewModel.refreshHomeData()
                    }
                )
            }
        }
    }
