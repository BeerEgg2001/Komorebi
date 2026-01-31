package com.example.komorebi.ui.program

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
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
    firstCellFocusRequester: FocusRequester
) {
    val uiState = viewModel.uiState
    // EPGの起点となる時間（0分0秒）
    val baseTime = remember { OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0) }
    var selectedBroadcastingType by remember { mutableStateOf("GR") }

    // 放送波ごとのグループ化
    val groupedChannels by remember(uiState) {
        derivedStateOf {
            if (uiState is EpgUiState.Success) {
                uiState.data.groupBy { it.channel.type ?: "UNKNOWN" }
            } else {
                emptyMap()
            }
        }
    }

    // 表示順序の定義
    val order = listOf("GR", "BS", "CS", "BS4K", "SKY")
    val categories = remember(groupedChannels) {
        order.filter { it in groupedChannels.keys } +
                (groupedChannels.keys - order.toSet()).toList()
    }

    // 選択中の放送波のチャンネル
    val displayChannels = remember(selectedBroadcastingType, groupedChannels) {
        groupedChannels[selectedBroadcastingType] ?: emptyList()
    }

    var isTabFocused by remember { mutableStateOf(false) }
    var isGridFocused by remember { mutableStateOf(false) }

    // リモコン戻るボタン制御
    BackHandler(enabled = true) {
        when {
            isGridFocused -> tabFocusRequester.requestFocus()
            else -> topTabFocusRequester.requestFocus()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // 1. 放送波切替タブ（フォーカス連動）
        BroadcastingTypeTabs(
            selectedType = selectedBroadcastingType,
            onTypeSelected = { selectedBroadcastingType = it },
            tabFocusRequester = tabFocusRequester,
            topTabFocusRequester = topTabFocusRequester,
            firstCellFocusRequester = firstCellFocusRequester,
            categories = categories,
            modifier = Modifier.onFocusChanged { isTabFocused = it.hasFocus }
        )

        // 2. メイン番組表エリア
        when (uiState) {
            is EpgUiState.Success -> {
                Box(modifier = Modifier.weight(1f).onFocusChanged { isGridFocused = it.hasFocus }) {
                    EpgGrid(
                        channels = displayChannels,
                        baseTime = baseTime,
                        viewModel = viewModel,
                        tabFocusRequester = tabFocusRequester,
                        firstCellFocusRequester = firstCellFocusRequester
                    )
                }
            }
            is EpgUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.Cyan)
                }
            }
            is EpgUiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text("エラーが発生しました: ${uiState.message}", color = Color.Red)
                }
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
    firstCellFocusRequester: FocusRequester
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    // 現在時刻線の更新用
    fun getNowOffsetMinutes() = Duration.between(baseTime, OffsetDateTime.now()).toMinutes()
    var nowOffsetMinutes by remember { mutableStateOf(getNowOffsetMinutes()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffsetMinutes = getNowOffsetMinutes()
        }
    }

    val channelWidth = 150.dp
    val timeColumnWidth = 55.dp
    val headerHeight = 60.dp // 日付表示用に少し高く
    val totalGridWidth = channelWidth * channels.size

    Column(modifier = Modifier.fillMaxSize()) {
        // --- 上部：日付ヘッダー ＋ チャンネル名ヘッダー ---
        Row(modifier = Modifier.fillMaxWidth().zIndex(4f)) {
            // 左上：日付固定エリア
            DateHeaderBox(baseTime, timeColumnWidth, headerHeight)

            // 右上：チャンネル名リスト（横スクロールのみ）
            Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                channels.forEach { wrapper ->
                    key(wrapper.channel.id) {
                        ChannelHeaderCell(
                            wrapper.channel,
                            channelWidth,
                            headerHeight,
                            viewModel.getMirakurunLogoUrl(wrapper.channel)
                        )
                    }
                }
            }
        }

        // --- 下部：時刻軸 ＋ 番組表グリッド ---
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：固定時刻軸（垂直スクロールのみ同期）
            Column(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .fillMaxHeight()
                    .background(Color(0xFF111111))
                    .verticalScroll(verticalScrollState)
                    .zIndex(2f)
            ) {
                TimeColumnContent(baseTime)
            }

            // 右：番組表本体（全方向スクロール）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
                    .verticalScroll(verticalScrollState)
            ) {
                Row {
                    channels.forEachIndexed { channelIndex, channelWrapper ->
                        key(channelWrapper.channel.id) {
                            Box(modifier = Modifier.width(channelWidth).height(HOUR_HEIGHT * 24)) {
                                channelWrapper.programs.forEachIndexed { programIndex, program ->
                                    CompactProgramCell(
                                        epgProgram = program,
                                        baseTime = baseTime,
                                        now = OffsetDateTime.now(),
                                        width = channelWidth,
                                        tabFocusRequester = tabFocusRequester,
                                        columnIndex = channelIndex,
                                        totalColumns = channels.size,
                                        modifier = if (channelIndex == 0 && programIndex == 0)
                                            Modifier.focusRequester(firstCellFocusRequester) else Modifier
                                    )
                                }
                            }
                        }
                    }
                }

                // 現在時刻線
                val lineOffset = (nowOffsetMinutes * DP_PER_MINUTE).dp
                Box(modifier = Modifier.width(totalGridWidth).offset(y = lineOffset)) {
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.Red))
                    Canvas(modifier = Modifier.size(8.dp).offset(y = (-3).dp)) {
                        drawCircle(color = Color.Red, radius = size.width / 2)
                    }
                }
            }
        }
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

    Box(
        modifier = Modifier.width(width).height(height).background(Color(0xFF111111)).padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(
                text = "${baseTime.monthValue}/${baseTime.dayOfMonth}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            androidx.compose.material3.Text(
                text = "(${dayOfWeekJapan[dayOfWeekIndex]})",
                color = dayColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TimeColumnContent(baseTime: OffsetDateTime) {
    repeat(24) { hourOffset ->
        val displayTime = baseTime.plusHours(hourOffset.toLong())
        Box(
            modifier = Modifier.height(HOUR_HEIGHT).fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = Color(0xFF222222),
                        start = androidx.compose.ui.geometry.Offset(0f, size.height),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(
                    text = "${displayTime.hour}",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                androidx.compose.material3.Text(text = "時", color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CompactProgramCell(
    epgProgram: EpgProgram,
    baseTime: OffsetDateTime,
    now: OffsetDateTime,
    width: Dp,
    tabFocusRequester: FocusRequester,
    columnIndex: Int,
    totalColumns: Int,
    modifier: Modifier = Modifier
) {
    val startTime = remember(epgProgram.start_time) { OffsetDateTime.parse(epgProgram.start_time) }
    val endTime = remember(startTime, epgProgram.duration) { startTime.plusSeconds(epgProgram.duration.toLong()) }
    if (endTime.isBefore(baseTime)) return

    val minutesFromBase = Duration.between(baseTime, startTime).toMinutes()
    val topOffset = (minutesFromBase * DP_PER_MINUTE).dp
    val cellHeight = (epgProgram.duration / 60 * DP_PER_MINUTE).dp

    var isFocused by remember { mutableStateOf(false) }
    val isPast = endTime.isBefore(now)
    val expandedMinHeight = 120.dp

    Box(
        modifier = modifier
            .offset(y = topOffset.coerceAtLeast(0.dp))
            .width(width)
            .height(if (topOffset < 0.dp) (cellHeight + topOffset).coerceAtLeast(0.dp) else cellHeight)
            .zIndex(if (isFocused) 100f else 1f)
            .focusProperties {
                if (columnIndex == 0) left = FocusRequester.Cancel
                if (columnIndex == totalColumns - 1) right = FocusRequester.Cancel
            }
            .onPreviewKeyEvent { event ->
                if (topOffset <= 0.dp && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                    tabFocusRequester.requestFocus()
                    true
                } else false
            }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { /* 番組詳細画面への遷移など */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .animateContentSize()
                .background(if (isPast) Color(0xFF151515) else Color(0xFF222222))
                .then(
                    if (isFocused) {
                        Modifier.heightIn(min = expandedMinHeight).border(2.dp, Color.White, RectangleShape).shadow(12.dp)
                    } else {
                        Modifier.border(0.5.dp, Color(0xFF333333), RectangleShape)
                    }
                )
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(modifier = Modifier.fillMaxHeight().width(4.dp).background(if (isPast) Color.Gray else EpgUtils.getGenreColor(epgProgram.majorGenre)))
                Column(modifier = Modifier.padding(6.dp).fillMaxWidth()) {
                    val alpha = if (isPast) 0.5f else 1.0f
                    androidx.compose.material3.Text(
                        text = EpgUtils.formatTime(epgProgram.start_time),
                        fontSize = 10.sp,
                        color = Color.LightGray.copy(alpha = alpha),
                        fontWeight = FontWeight.Bold
                    )
                    androidx.compose.material3.Text(
                        text = epgProgram.title,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = alpha),
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = if (isFocused) 5 else if (cellHeight > 30.dp) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )
                    if (isFocused) {
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.material3.Text(
                            text = epgProgram.description ?: "",
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            lineHeight = 14.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelHeaderCell(channel: EpgChannel, width: Dp, height: Dp, logoUrl: String) {
    Surface(
        onClick = {},
        modifier = Modifier.width(width).height(height),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF111111), contentColor = Color.White),
        border = ClickableSurfaceDefaults.border(border = Border(border = BorderStroke(0.5.dp, Color(0xFF333333))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(width = 40.dp, height = 24.dp).clip(RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.Text(
                    text = channel.channel_number ?: "",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            androidx.compose.material3.Text(text = channel.name, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    categories: List<String>,
    modifier: Modifier = Modifier
) {
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")

    Row(
        modifier = modifier.fillMaxWidth().height(56.dp).background(Color.Black),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEachIndexed { index, code ->
            val label = typeLabels[code] ?: code
            val isSelected = selectedType == code

            Surface(
                onClick = { firstCellFocusRequester.requestFocus() },
                modifier = Modifier
                    .width(120.dp).height(42.dp).padding(horizontal = 4.dp)
                    .focusRequester(if (index == 0) tabFocusRequester else FocusRequester())
                    .focusProperties {
                        up = topTabFocusRequester
                        down = firstCellFocusRequester
                    }
                    .onFocusChanged { if (it.isFocused) onTypeSelected(code) },
                shape = ClickableSurfaceDefaults.shape(RectangleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.White,
                    contentColor = if (isSelected) Color.White else Color.Gray,
                    focusedContentColor = Color.Black
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.Text(
                            text = label,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = LocalContentColor.current
                        )
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