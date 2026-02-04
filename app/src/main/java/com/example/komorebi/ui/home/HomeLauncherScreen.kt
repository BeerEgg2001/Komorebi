package com.example.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.epg.EpgNavigationContainer
import com.example.komorebi.viewmodel.Channel
import com.example.komorebi.viewmodel.ChannelViewModel
import com.example.komorebi.viewmodel.EpgUiState
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
    // 追加: 番組表からプレイヤーへの遷移用コールバック
    onNavigateToPlayer: (String, String, String) -> Unit
) {
    val tabs = listOf("ホーム", "ライブ", "番組表", "ビデオ")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }
    var isContentReady by remember { mutableStateOf(false) }

    // チャンネル視聴中やビデオ再生中はフルスクリーン（タブを隠す）
    val isFullScreenMode = (selectedChannel != null) || (selectedProgram != null) || (epgSelectedProgram != null)
    var isNavigatingToTabRow by remember { mutableStateOf(false) }

    // EPGのUI状態を監視
    val epgUiState = epgViewModel.uiState

    // 取得したチャンネルリストに基づいてロゴURLのリストを生成
    val logoUrls = remember(epgUiState) {
        if (epgUiState is EpgUiState.Success) {
            epgUiState.data.map { epgViewModel.getLogoUrl(it.channel) }
        } else {
            emptyList()
        }
    }

    // フォーカス制御用のRequester
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }
    val contentFirstItemRequesters = remember { List(tabs.size) { FocusRequester() } }
    var tabRowHasFocus by remember { mutableStateOf(false) }

    // タブ切り替え時のコンテンツロード制御
    val loadedTabs = remember { mutableStateListOf<Int>() }
    LaunchedEffect(selectedTabIndex) {
        if (!loadedTabs.contains(selectedTabIndex)) {
            isContentReady = false
            delay(200)
            yield()
            loadedTabs.add(selectedTabIndex)
        }
        isContentReady = true
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            if (selectedTabIndex > 0) {
                // 1. インデックスが0より大きい（ライブ、番組表など）なら「ホーム」に戻す
                selectedTabIndex = 0
                onTabChange(0)
                // タブにフォーカスを戻す
                tabFocusRequesters[0].requestFocus()
            } else {
                // 2. すでに「ホーム(0)」にいるなら、アプリ終了シーケンスへ
                onFinalBack()
            }
            // 処理が終わったことを親に通知（フラグをfalseに戻してもらう）
            onBackTriggered()
        }
    }

    // 初回表示時のフォーカスセット
    LaunchedEffect(Unit) {
        onUiReady()
        delay(500)
        runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // --- 上部ナビゲーションタブ ---
        AnimatedVisibility(
            visible = !isFullScreenMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, start = 40.dp, end = 40.dp)
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
                                if (index == 2) {
                                    down = contentFirstItemRequesters[index]
                                } else {
                                    down = FocusRequester.Default
                                }
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
        }

        // --- メインコンテンツエリア ---
        Box(modifier = Modifier
            .weight(1f)
            .onFocusChanged { state ->
                // 必要に応じてフォーカス管理ロジックを記述
            }
        ) {
            AnimatedContent(
                targetState = selectedTabIndex,
                contentKey = { it },
                transitionSpec = {
                    fadeIn(animationSpec = androidx.compose.animation.core.tween(100)) togetherWith
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(100))
                }
            ) { targetIndex ->
                when (targetIndex) {
                    2 -> { // 番組表タブ
                        when (val state = epgUiState) {
                            is EpgUiState.Success -> {
                                EpgNavigationContainer(
                                    uiState = state,
                                    logoUrls = logoUrls,
                                    mirakurunIp = mirakurunIp,
                                    mirakurunPort = mirakurunPort,
                                    mainTabFocusRequester = tabFocusRequesters[2],
                                    contentRequester = contentFirstItemRequesters[2],
                                    selectedProgram = epgSelectedProgram,
                                    onProgramSelected = onEpgProgramSelected,
                                    isJumpMenuOpen = isEpgJumpMenuOpen,
                                    onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                    // ★ 修正箇所: 不足していた引数を追加
                                    onNavigateToPlayer = onNavigateToPlayer
                                )
                            }
                            is EpgUiState.Loading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }
                            is EpgUiState.Error -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "エラーが発生しました: ${state.message}",
                                        color = Color.Red,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "${tabs[targetIndex]} コンテンツは準備中です",
                                color = Color.Gray,
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    }
                }
            }
        }
    }
}