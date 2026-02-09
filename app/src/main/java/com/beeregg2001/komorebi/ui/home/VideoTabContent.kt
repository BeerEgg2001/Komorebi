package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.ui.video.VideoPlayerScreen
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    selectedProgram: RecordedProgram?,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onProgramClick: (RecordedProgram?) -> Unit
) {
    val watchedProgramFocusRequester = remember { FocusRequester() }
    val isPlayerActive = selectedProgram != null

    // このコンポーザブル内で画面遷移状態を管理する
    var isRecordListOpen by remember { mutableStateOf(false) }

    val allRecordingsButtonRequester = remember { FocusRequester() }

    // ダミーデータの生成
    val displayRecentRecordings = remember(recentRecordings) {
        if (recentRecordings.isEmpty()) {
            List(10) {
                RecordedProgram(
                    id = it,
                    title = "ダミー録画番組 $it",
                    description = "これはダミーデータです。",
                    startTime = "2024-01-01T19:00:00+09:00",
                    endTime = "2024-01-01T20:00:00+09:00",
                    duration = 3600.0,
                    isPartiallyRecorded = false,
                    recordedVideo = RecordedVideo(
                        id = it, filePath = "", recordingStartTime = "", recordingEndTime = "",
                        duration = 3600.0, containerFormat = "", videoCodec = "", audioCodec = ""
                    )
                )
            }
        } else {
            recentRecordings
        }
    }

    // 録画リストが開いているときは、ここでのバック操作でリストを閉じる
    BackHandler(enabled = isRecordListOpen && !isPlayerActive) {
        isRecordListOpen = false
    }

    // 【修正】初期表示時にフォーカスを奪わないように、状態変化を監視してフォーカスを戻す
    LaunchedEffect(Unit) {
        snapshotFlow { isRecordListOpen }
            .collect { isOpen ->
                // falseになった（戻ってきた）時のみ実行したいが、初期値もfalseなので
                // ここではcollectLatestは不向きか、あるいは初期値をスキップする必要がある。
                // もっと単純に、isRecordListOpenがtrueからfalseに変わった瞬間だけ検知したい。
            }
    }

    // シンプルな解決策: 前回の状態を覚えておき、True -> False の変化時のみフォーカス要求する
    var previousState by remember { mutableStateOf(isRecordListOpen) }
    LaunchedEffect(isRecordListOpen) {
        if (previousState && !isRecordListOpen) {
            // 録画リスト(True)から戻ってきた(False)場合のみフォーカスをボタンに戻す
            delay(100)
            try {
                allRecordingsButtonRequester.requestFocus()
            } catch (e: Exception) {
                // 無視
            }
        }
        previousState = isRecordListOpen
    }

    LaunchedEffect(isPlayerActive) {
        if (!isPlayerActive && selectedProgram != null) {
            delay(150)
            runCatching { watchedProgramFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 画面遷移のアニメーション (内部遷移)
        AnimatedContent(
            targetState = isRecordListOpen,
            transitionSpec = {
                if (targetState) {
                    // リストを開く: 右からイン
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    // リストを閉じる: 左からイン（戻る動き）
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "VideoTabNavigation"
        ) { isOpen ->
            if (isOpen) {
                // --- 録画一覧画面 ---
                RecordListScreen(
                    recordings = displayRecentRecordings,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onProgramClick = { onProgramClick(it) },
                    onBack = { isRecordListOpen = false },
                    topNavFocusRequester = topNavFocusRequester
                )
            } else {
                // --- メインダッシュボード画面 ---
                TvLazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        // 最初のアイテム（新着録画の最初のカードなど）にフォーカスを当てるためのRequester
                        // HomeLauncherScreenから指定されたRequesterをここに割り当てるのが基本だが、
                        // 内部のアイテムに直接割り当てる必要がある場合は修正が必要。
                        // 今回はTvLazyColumn自体には当てず、中のアイテムに任せるか、
                        // もしくはTvLazyColumnがフォーカスを受け取ったら最初の子に流す挙動を利用する。
                        // contentFirstItemRequesterはHomeLauncherScreenで「タブ切り替え直後」に使われる。
                        // ここではLazyColumnに割り当てておく。
                        // .focusRequester(contentFirstItemRequester)
                        // ↑ これだとLazyColumn全体がフォーカス対象になりそうだが、TvLazyColumnは通常内部アイテムにフォーカスを委譲する。
                        // ただし、ボタンに勝手にフォーカスが行く問題とは別。
                        .then(if (isPlayerActive) Modifier.focusProperties { canFocus = false } else Modifier),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    // 1. 新着の録画
                    item {
                        RecordedSection(
                            title = "新着の録画",
                            items = displayRecentRecordings,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            onProgramClick = onProgramClick,
                            // ここで contentFirstItemRequester を最初のアイテムに割り当てる
                            firstItemFocusRequester = contentFirstItemRequester,
                            watchedProgramFocusRequester = watchedProgramFocusRequester,
                            selectedProgramId = selectedProgram?.id,
                            topNavFocusRequester = topNavFocusRequester,
                            isFirstSection = true
                        )
                    }

                    // 2. 視聴履歴
                    if (watchHistory.isNotEmpty()) {
                        item {
                            RecordedSection(
                                title = "視聴履歴",
                                items = watchHistory,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                onProgramClick = onProgramClick,
                                firstItemFocusRequester = null,
                                watchedProgramFocusRequester = watchedProgramFocusRequester,
                                selectedProgramId = selectedProgram?.id,
                                topNavFocusRequester = null,
                                isFirstSection = false
                            )
                        }
                    }

                    // 3. すべての録画を見るボタン (最下部)
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Button(
                                onClick = { isRecordListOpen = true },
                                modifier = Modifier
                                    .focusRequester(allRecordingsButtonRequester),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White.copy(0.1f),
                                    focusedContainerColor = Color.White,
                                    contentColor = Color.White,
                                    focusedContentColor = Color.Black
                                ),
                                shape = ButtonDefaults.shape(shape = MaterialTheme.shapes.small)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("すべての録画を見る")
                            }
                        }
                    }
                }
            }
        }
    }

    // プレイヤー画面 (最前面)
    if (selectedProgram != null) {
        VideoPlayerScreen(
            program = selectedProgram,
            konomiIp = konomiIp, konomiPort = konomiPort,
            onBackPressed = { onProgramClick(null) }
        )
    }
}

@Composable
fun RecordedSection(
    title: String,
    items: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    isPlaceholder: Boolean = false,
    firstItemFocusRequester: FocusRequester? = null,
    watchedProgramFocusRequester: FocusRequester,
    selectedProgramId: Int?,
    topNavFocusRequester: FocusRequester?,
    isFirstSection: Boolean
) {
    Column(modifier = Modifier.graphicsLayer(clip = false)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)
        )

        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer(clip = false)
        ) {
            if (isPlaceholder) {
                items(6) {
                    Box(
                        Modifier
                            .size(185.dp, 104.dp)
                            .background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
                    )
                }
            } else {
                itemsIndexed(items, key = { _, program -> program.id }) { index, program ->
                    val isSelected = program.id == selectedProgramId

                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier
                            .then(
                                // 【修正】最初のセクションの最初のアイテムに、タブ遷移時のフォーカスリクエスターを割り当てる
                                if (index == 0 && isFirstSection && firstItemFocusRequester != null) {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (isSelected) {
                                    Modifier.focusRequester(watchedProgramFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .focusProperties {
                                if (isFirstSection && topNavFocusRequester != null) {
                                    up = topNavFocusRequester
                                }
                            }
                    )
                }
            }
        }
    }
}