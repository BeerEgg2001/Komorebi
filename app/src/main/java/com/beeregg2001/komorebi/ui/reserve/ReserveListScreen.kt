package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.components.KeywordConditionCard
import com.beeregg2001.komorebi.ui.components.ReserveCard
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

private const val TAG = "ReserveListScreen"

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ReserveListScreen(
    onBack: () -> Unit,
    onProgramClick: (ReserveItem) -> Unit,
    onConditionClick: (ReservationCondition) -> Unit = {},
    konomiIp: String,
    konomiPort: String,
    groupedChannels: Map<String, List<Channel>> = emptyMap(), // ★追加: チャンネル一覧を受け取る
    contentFirstItemRequester: FocusRequester? = null,
    topNavFocusRequester: FocusRequester? = null,
    viewModel: ReserveViewModel = hiltViewModel()
) {
    val reserves by viewModel.reserves.collectAsState()
    val normalReserves by viewModel.normalReserves.collectAsState()
    val conditions by viewModel.conditions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val colors = KomorebiTheme.colors

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("全ての予約", "通常予約", "キーワード自動予約")

    val listFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .onKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                    if (event.type == KeyEventType.KeyUp) {
                        onBack()
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .focusGroup()
                .focusProperties {
                    enter = { tabFocusRequesters[selectedTabIndex] }
                },
            contentAlignment = Alignment.Center
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                indicator = { tabPositions, doesTabRowHaveFocus ->
                    TabRowDefaults.UnderlinedIndicator(
                        currentTabPosition = tabPositions[selectedTabIndex],
                        doesTabRowHaveFocus = doesTabRowHaveFocus,
                        activeColor = colors.accent,
                        inactiveColor = Color.Transparent
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = index == selectedTabIndex,
                        onFocus = { selectedTabIndex = index },
                        onClick = { selectedTabIndex = index },
                        modifier = Modifier
                            .focusRequester(tabFocusRequesters[index])
                            .then(
                                if (index == selectedTabIndex && contentFirstItemRequester != null) {
                                    Modifier.focusRequester(contentFirstItemRequester)
                                } else Modifier
                            )
                            .focusProperties {
                                down = listFocusRequester
                                up = topNavFocusRequester ?: FocusRequester.Cancel
                            },
                        colors = TabDefaults.underlinedIndicatorTabColors(
                            contentColor = colors.textSecondary,
                            selectedContentColor = colors.accent,
                            focusedContentColor = colors.textPrimary,
                            focusedSelectedContentColor = colors.textPrimary
                        )
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else {
            AnimatedContent(
                targetState = selectedTabIndex,
                label = "ReserveTabContent"
            ) { targetIndex ->
                when (targetIndex) {
                    0 -> {
                        if (reserves.isEmpty()) {
                            EmptyMessage(
                                message = "現在、予約されている番組はありません。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[0]
                            )
                        } else {
                            TvLazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(listFocusRequester)
                                    .focusRestorer()
                                    .focusProperties { up = tabFocusRequesters[0] }
                            ) {
                                items(reserves) { program ->
                                    ReserveCard(
                                        item = program,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { onProgramClick(program) }
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        if (normalReserves.isEmpty()) {
                            EmptyMessage(
                                message = "現在、手動で予約されている番組はありません。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[1]
                            )
                        } else {
                            TvLazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(listFocusRequester)
                                    .focusRestorer()
                                    .focusProperties { up = tabFocusRequesters[1] }
                            ) {
                                items(normalReserves) { program ->
                                    ReserveCard(
                                        item = program,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { onProgramClick(program) }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        if (conditions.isEmpty()) {
                            EmptyMessage(
                                message = "キーワード自動予約の条件は登録されていません。\n番組表の番組詳細から「簡単連ドラ予約」が可能です。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[2]
                            )
                        } else {
                            TvLazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(listFocusRequester)
                                    .focusRestorer()
                                    .focusProperties { up = tabFocusRequesters[2] }
                            ) {
                                items(conditions) { condition ->
                                    // ★修正: 追加の引数を渡す
                                    KeywordConditionCard(
                                        condition = condition,
                                        onClick = { onConditionClick(condition) },
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        groupedChannels = groupedChannels,
                                        reserves = reserves
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EmptyMessage(
    message: String,
    listFocusRequester: FocusRequester,
    targetTabRequester: FocusRequester
) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocusRequester)
            .focusRestorer()
            .focusProperties { up = targetTabRequester }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}