package com.example.komorebi.ui.program

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.komorebi.data.model.EpgChannel
import com.example.komorebi.data.model.EpgChannelWrapper
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.util.EpgUtils
import com.example.komorebi.viewmodel.EpgUiState
import com.example.komorebi.viewmodel.EpgViewModel
import java.time.Duration
import java.time.OffsetDateTime

private const val DP_PER_MINUTE = 1.3f
private val HOUR_HEIGHT = (60 * DP_PER_MINUTE).dp

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgScreen(
    viewModel: EpgViewModel,
    topTabFocusRequester: FocusRequester,
    tabFocusRequester: FocusRequester,
    firstCellFocusRequester: FocusRequester,
    selectedProgram: EpgProgram?,
    onProgramSelected: (EpgProgram?) -> Unit
) {
    val uiState = viewModel.uiState
    val baseTime = remember { OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0) }
    var selectedBroadcastingType by remember { mutableStateOf("GR") }
    var isGridFocused by remember { mutableStateOf(false) }

    // 放送波切り替え時の自動フォーカス
    LaunchedEffect(selectedBroadcastingType, uiState) {
        if (uiState is EpgUiState.Success) {
            kotlinx.coroutines.delay(100)
            try {
                firstCellFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    val displayChannels by remember(selectedBroadcastingType, uiState) {
        derivedStateOf {
            if (uiState is EpgUiState.Success) {
                uiState.data.filter { it.channel.type == selectedBroadcastingType }
            } else {
                emptyList()
            }
        }
    }

    val categories = remember(uiState) {
        if (uiState is EpgUiState.Success) {
            val order = listOf("GR", "BS", "CS", "BS4K", "SKY")
            val availableTypes = uiState.data.mapNotNull { it.channel.type }.distinct()
            order.filter { it in availableTypes } + (availableTypes - order.toSet())
        } else {
            listOf("GR")
        }
    }

//    BackHandler(enabled = selectedProgram == null) {
//        if (isGridFocused) {
//            tabFocusRequester.requestFocus()
//        } else {
//            topTabFocusRequester.requestFocus()
//        }
//    }

    // ★ 修正ポイント1: Boxで包むことでモーダルを全画面オーバーレイにする
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            BroadcastingTypeTabs(
                selectedType = selectedBroadcastingType,
                onTypeSelected = { selectedBroadcastingType = it },
                tabFocusRequester = tabFocusRequester,
                topTabFocusRequester = topTabFocusRequester,
                firstCellFocusRequester = firstCellFocusRequester,
                categories = categories
            )

            when (uiState) {
                is EpgUiState.Success -> {
                    Box(modifier = Modifier.weight(1f).onFocusChanged { isGridFocused = it.hasFocus }) {
                        EpgGrid(
                            channels = displayChannels,
                            baseTime = baseTime,
                            viewModel = viewModel,
                            tabFocusRequester = tabFocusRequester,
                            onProgramClick = { onProgramSelected(it) }, // ★ 親へ通知
                            firstCellFocusRequester = firstCellFocusRequester
                        )
                    }
                }
                is EpgUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.Cyan)
                }
                is EpgUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text("エラーが発生しました", color = Color.Red)
                }
            }
        }

        // ★ 修正ポイント2: Columnの外側に配置して全画面表示を確保
        if (selectedProgram != null) {
            key(selectedProgram.id) {
                ProgramDetailModal(
                    program = selectedProgram,
                    onPrimaryAction = {
                        val p = selectedProgram
                        onProgramSelected(null)
                        p.let { viewModel.playChannel(it.channel_id) }
                    },
                    onDismiss = { onProgramSelected(null) } // ★ 親へ通知
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgGrid(
    channels: List<EpgChannelWrapper>,
    baseTime: OffsetDateTime,
    viewModel: EpgViewModel,
    tabFocusRequester: FocusRequester,
    onProgramClick: (EpgProgram) -> Unit,
    firstCellFocusRequester: FocusRequester
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val channelWidth = 150.dp
    val timeColumnWidth = 55.dp
    val headerHeight = 48.dp
    val totalGridWidth = remember(channels.size) { channelWidth * channels.size }

    Column(modifier = Modifier.fillMaxSize()) {
        // ヘッダー行 (放送局名)
        Row(modifier = Modifier.fillMaxWidth().zIndex(4f)) {
            DateHeaderBox(baseTime, timeColumnWidth, headerHeight)
            Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                channels.forEach { wrapper ->
                    key(wrapper.channel.id) {
                        ChannelHeaderCell(wrapper.channel, channelWidth, headerHeight, viewModel.getMirakurunLogoUrl(wrapper.channel))
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // 時刻カラム
            Column(modifier = Modifier.width(timeColumnWidth).fillMaxHeight().background(Color(0xFF111111))
                .verticalScroll(verticalScrollState).zIndex(2f)) {
                TimeColumnContent(baseTime)
            }

            // メイングリッド
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
                    .verticalScroll(verticalScrollState)
                    .focusGroup()
            ) {
                Row {
                    channels.forEachIndexed { channelIndex, channelWrapper ->
                        key(channelWrapper.channel.id) {
                            val programFocusRequesters = remember(channelWrapper.programs) {
                                List(channelWrapper.programs.size) { FocusRequester() }
                            }
                            Box(modifier = Modifier.width(channelWidth).height(HOUR_HEIGHT * 24)) {
                                channelWrapper.programs.forEachIndexed { programIndex, program ->
                                    CompactProgramCell(
                                        epgProgram = program,
                                        baseTime = baseTime,
                                        width = channelWidth,
                                        tabFocusRequester = tabFocusRequester,
                                        columnIndex = channelIndex,
                                        totalColumns = channels.size,
                                        focusRequester = programFocusRequesters[programIndex],
                                        upRequester = if (programIndex > 0) programFocusRequesters[programIndex - 1] else null,
                                        downRequester = if (programIndex < programFocusRequesters.lastIndex) programFocusRequesters[programIndex + 1] else null,
                                        onProgramClick = onProgramClick,
                                        modifier = if (channelIndex == 0 && programIndex == 0)
                                            Modifier.focusRequester(firstCellFocusRequester) else Modifier
                                    )
                                }
                            }
                        }
                    }
                }
                CurrentTimeIndicatorOptimized(baseTime, totalGridWidth)
            }
        }
    }
}

@Composable
fun ChannelHeaderCell(channel: EpgChannel, width: Dp, height: Dp, logoUrl: String) {
    Surface(
        modifier = Modifier.width(width).height(height).focusable(false), // ★ スキップさせる
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(containerColor = Color(0xFF111111), contentColor = Color.White),
        border = Border(border = BorderStroke(0.5.dp, Color(0xFF333333)), shape = RectangleShape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 32.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(6.dp))
                androidx.compose.material3.Text(text = channel.channel_number ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            androidx.compose.material3.Text(text = channel.name, color = Color.LightGray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun BroadcastingTypeTabs(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    firstCellFocusRequester: FocusRequester,
    tabFocusRequester: FocusRequester,
    topTabFocusRequester: FocusRequester,
    categories: List<String>
) {
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).background(Color.Black).focusGroup(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEachIndexed { index, code ->
            val isSelected = selectedType == code
            Surface(
                onClick = { firstCellFocusRequester.requestFocus() },
                modifier = Modifier.width(110.dp).height(36.dp).padding(horizontal = 4.dp)
                    .focusRequester(if (index == 0) tabFocusRequester else remember { FocusRequester() })
                    .focusProperties {
                        up = topTabFocusRequester
                        down = firstCellFocusRequester // ★ 番組セルへ直接飛ばす
                    }
                    .onFocusChanged { if (it.isFocused) onTypeSelected(code) },
                shape = ClickableSurfaceDefaults.shape(RectangleShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White, contentColor = if (isSelected) Color.White else Color.Gray, focusedContentColor = Color.Black),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.Text(text = typeLabels[code] ?: code, fontSize = 15.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = LocalContentColor.current)
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(modifier = Modifier.width(20.dp).height(2.dp).background(LocalContentColor.current))
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CompactProgramCell(
    epgProgram: EpgProgram,
    baseTime: OffsetDateTime,
    width: Dp,
    tabFocusRequester: FocusRequester,
    columnIndex: Int,
    totalColumns: Int,
    focusRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    onProgramClick: (EpgProgram) -> Unit,
    modifier: Modifier = Modifier
) {
    val cellData = remember(epgProgram.id, baseTime) {
        val startTime = OffsetDateTime.parse(epgProgram.start_time)
        val endTime = startTime.plusSeconds(epgProgram.duration.toLong())
        val minutesFromBase = Duration.between(baseTime, startTime).toMinutes()
        val durationMin = epgProgram.duration / 60
        object {
            val top = (minutesFromBase * DP_PER_MINUTE).dp
            val height = (durationMin * DP_PER_MINUTE).dp
            val isVisible = endTime.isAfter(baseTime)
            val isPast = endTime.isBefore(OffsetDateTime.now())
            val startTimeStr = EpgUtils.formatTime(epgProgram.start_time)
            val genreColor = EpgUtils.getGenreColor(epgProgram.majorGenre)
        }
    }

    if (!cellData.isVisible) return
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .offset(y = cellData.top.coerceAtLeast(0.dp))
            .width(width)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusProperties {
                // ★ 修正ポイント3: 上キーでタブに戻るパスを構築
                if (upRequester != null) up = upRequester else up = tabFocusRequester
                downRequester?.let { down = it }
                if (columnIndex == 0) left = FocusRequester.Cancel
                if (columnIndex == totalColumns - 1) right = FocusRequester.Cancel
            }
            .focusable()
            .clickable { onProgramClick(epgProgram) }
            .layout { measurable, constraints ->
                val expandedHeight = if (isFocused) 110.dp.roundToPx() else cellData.height.roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
                val reportHeight = if (cellData.top < 0.dp) (cellData.height + cellData.top).coerceAtLeast(0.dp).roundToPx() else cellData.height.roundToPx()
                layout(placeable.width, reportHeight) {
                    val yOffset = if (isFocused && expandedHeight > reportHeight) {
                        val idealOffset = -(expandedHeight - reportHeight) / 2
                        val topLimit = -cellData.top.roundToPx()
                        idealOffset.coerceAtMost(0).coerceAtLeast(topLimit)
                    } else 0
                    placeable.place(0, yOffset)
                }
            }
            .then(Modifier.zIndex(if (isFocused) 100f else 1f))
            .drawBehind {
                val bgColor = if (cellData.isPast) Color(0xFF151515) else Color(0xFF222222)
                drawRect(color = bgColor)
                drawRect(color = if (cellData.isPast) Color.Gray else cellData.genreColor, size = size.copy(width = 3.dp.toPx()))
                if (isFocused) {
                    drawRect(color = Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                } else {
                    drawRect(color = Color(0xFF333333), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5.dp.toPx()))
                }
            }
    ) {
        Column(modifier = Modifier.padding(start = 6.dp, top = 2.dp, end = 4.dp)) {
            val textAlpha = if (cellData.isPast) 0.5f else 1.0f
            androidx.compose.material3.Text(text = cellData.startTimeStr, fontSize = 9.sp, color = Color.LightGray.copy(alpha = textAlpha))
            androidx.compose.material3.Text(
                text = epgProgram.title,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = textAlpha),
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = if (isFocused) 2 else if (cellData.height > 35.dp) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )
            if (isFocused && epgProgram.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.material3.Text(text = epgProgram.description, fontSize = 10.sp, color = Color.LightGray, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
            }
        }
    }
}

// --- 残りの補助的なComposable (TimeColumn, DateHeader等) は元のコードと同様です ---
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TimeColumnContent(baseTime: OffsetDateTime) {
    repeat(24) { hourOffset ->
        val displayTime = baseTime.plusHours(hourOffset.toLong())
        Box(
            modifier = Modifier.height(HOUR_HEIGHT).fillMaxWidth()
                .drawBehind { drawLine(Color(0xFF222222), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx()) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(text = "${displayTime.hour}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                androidx.compose.material3.Text(text = "時", color = Color.Gray, fontSize = 9.sp)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CurrentTimeIndicatorOptimized(baseTime: OffsetDateTime, totalWidth: Dp) {
    var nowOffsetMinutes by remember { mutableStateOf(Duration.between(baseTime, OffsetDateTime.now()).toMinutes()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffsetMinutes = Duration.between(baseTime, OffsetDateTime.now()).toMinutes()
        }
    }
    val lineOffset = (nowOffsetMinutes * DP_PER_MINUTE).dp
    Box(modifier = Modifier.width(totalWidth).offset(y = lineOffset)) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.Red))
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DateHeaderBox(baseTime: OffsetDateTime, width: Dp, height: Dp) {
    val dayOfWeekJapan = listOf("日", "月", "火", "水", "木", "金", "土")
    val dayOfWeekIndex = baseTime.dayOfWeek.value % 7
    val dayColor = when (dayOfWeekIndex) {
        0 -> Color(0xFFFF5252)
        6 -> Color(0xFF448AFF)
        else -> Color.White
    }
    Box(modifier = Modifier.width(width).height(height).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(text = "${baseTime.monthValue}/${baseTime.dayOfMonth}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text(text = "(${dayOfWeekJapan[dayOfWeekIndex]})", color = dayColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}