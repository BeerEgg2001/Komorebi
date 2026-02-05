package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.komorebi.data.model.*
import com.example.komorebi.data.util.EpgUtils
import com.example.komorebi.viewmodel.*
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
    onBroadcastingTabFocusChanged: (Boolean) -> Unit = {},
    firstCellFocusRequester: FocusRequester,
    selectedProgram: EpgProgram?,
    onProgramSelected: (EpgProgram?) -> Unit,
    selectedBroadcastingType: String,
    onTypeSelected: (String) -> Unit,
    onChannelSelected: (String) -> Unit,
) {
    val uiState = viewModel.uiState
    val baseTime = remember { OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0) }

    val displayChannels by remember(selectedBroadcastingType, uiState) {
        derivedStateOf {
            if (uiState is EpgUiState.Success) {
                uiState.data.filter { it.channel.type == selectedBroadcastingType }
            } else emptyList()
        }
    }

    val categories = remember(uiState) {
        if (uiState is EpgUiState.Success) {
            val order = listOf("GR", "BS", "CS", "BS4K", "SKY")
            val availableTypes = uiState.data.map { it.channel.type }.distinct()
            order.filter { it in availableTypes } + (availableTypes - order.toSet())
        } else listOf("GR")
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            BroadcastingTypeTabs(
                selectedType = selectedBroadcastingType,
                onTypeSelected = onTypeSelected,
                tabFocusRequester = tabFocusRequester,
                onFocusChanged = onBroadcastingTabFocusChanged,
                firstCellFocusRequester = firstCellFocusRequester,
                categories = categories
            )

            when (uiState) {
                is EpgUiState.Success -> {
                    key(selectedBroadcastingType) {
                        EpgGrid(
                            channels = displayChannels,
                            baseTime = baseTime,
                            viewModel = viewModel,
                            onProgramClick = { onProgramSelected(it) },
                            firstCellFocusRequester = firstCellFocusRequester,
                            tabFocusRequester = tabFocusRequester,
                            visibleChannelCount = 7 // ★ここでチャンネル数を指定（例: 7チャンネル）
                        )
                    }
                }
                is EpgUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.Cyan)
                }
                is EpgUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    androidx.compose.material3.Text("エラーが発生しました", color = Color.Red)
                }
            }
        }

        if (selectedProgram != null) {
            ProgramDetailModal(
                program = selectedProgram,
                onPrimaryAction = onChannelSelected,
                onDismiss = { onProgramSelected(null) }
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgGrid(
    channels: List<EpgChannelWrapper>,
    baseTime: OffsetDateTime,
    viewModel: EpgViewModel,
    onProgramClick: (EpgProgram) -> Unit,
    firstCellFocusRequester: FocusRequester,
    tabFocusRequester: FocusRequester,
    visibleChannelCount: Int = 6 // ★デフォルト値を設定
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    // ★ 画面サイズからチャンネル幅を動的に計算
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val timeColumnWidth = 55.dp
    val headerHeight = 48.dp
    val channelWidth = (screenWidth - timeColumnWidth) / visibleChannelCount

    val now = OffsetDateTime.now()
    val minutesFromBase = Duration.between(baseTime, now).toMinutes()

    LaunchedEffect(channels) {
        val targetScrollDp = ((minutesFromBase - 30) * DP_PER_MINUTE).dp
        verticalScrollState.scrollTo(targetScrollDp.value.toInt())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().zIndex(4f)) {
            DateHeaderBox(baseTime, timeColumnWidth, headerHeight)
            Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                channels.forEach { wrapper ->
                    ChannelHeaderCell(wrapper.channel, channelWidth, headerHeight, viewModel.getMirakurunLogoUrl(wrapper.channel))
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(timeColumnWidth).fillMaxHeight().background(Color(0xFF111111)).verticalScroll(verticalScrollState).zIndex(2f)) {
                TimeColumnContent(baseTime)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState)
                    .focusGroup()
            ) {
                Row {
                    channels.forEachIndexed { channelIndex, channelWrapper ->
                        Box(modifier = Modifier.width(channelWidth).height(HOUR_HEIGHT * 24)) {
                            channelWrapper.programs.forEach { program ->
                                val startTime = OffsetDateTime.parse(program.start_time)
                                val endTime = startTime.plusSeconds(program.duration.toLong())
                                val isCurrentLiveOnFirstChannel = channelIndex == 0 &&
                                        now.isAfter(startTime.minusMinutes(5)) && now.isBefore(endTime)

                                CompactProgramCell(
                                    epgProgram = program,
                                    baseTime = baseTime,
                                    width = channelWidth,
                                    isFirstCellOfChannel = false,
                                    focusRequester = if (isCurrentLiveOnFirstChannel) firstCellFocusRequester else null,
                                    tabFocusRequester = tabFocusRequester,
                                    onProgramClick = onProgramClick
                                )
                            }
                        }
                    }
                }
                CurrentTimeIndicatorOptimized(baseTime, (channelWidth * channels.size), verticalScrollState.value)
            }
        }
    }
}

// --- CompactProgramCell, BroadcastingTypeTabs, ChannelHeaderCell, TimeColumnContent, CurrentTimeIndicatorOptimized, DateHeaderBox は変更なし（境界線実装済み版） ---

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CompactProgramCell(
    epgProgram: EpgProgram,
    baseTime: OffsetDateTime,
    width: Dp,
    isFirstCellOfChannel: Boolean,
    focusRequester: FocusRequester?,
    tabFocusRequester: FocusRequester,
    onProgramClick: (EpgProgram) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    var contentHeightDp by remember { mutableStateOf(0.dp) }

    val cellData = remember(epgProgram.id, baseTime) {
        val startTime = OffsetDateTime.parse(epgProgram.start_time)
        val minutesFromBase = Duration.between(baseTime, startTime).toMinutes()
        val durationMin = epgProgram.duration / 60
        val isVisible = (minutesFromBase + durationMin) > 0 && minutesFromBase < 1440

        if (!isVisible) null else object {
            val top = (minutesFromBase * DP_PER_MINUTE).dp
            val height = (durationMin * DP_PER_MINUTE).dp
            val startTimeStr = EpgUtils.formatTime(epgProgram.start_time)
            val genreColor = EpgUtils.getGenreColor(epgProgram.majorGenre)
            val isPast = startTime.plusSeconds(epgProgram.duration.toLong()).isBefore(OffsetDateTime.now())
        }
    } ?: return

    val expansionAmount = if (isFocused) {
        (contentHeightDp - cellData.height).coerceAtLeast(0.dp)
    } else 0.dp

    val animatedExpansion by animateDpAsState(
        targetValue = expansionAmount,
        animationSpec = tween(150),
        label = "expansion"
    )

    Box(
        modifier = Modifier
            .offset(y = cellData.top.coerceAtLeast(0.dp))
            .width(width)
            .height(cellData.height)
            .zIndex(if (isFocused) 100f else 1f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusProperties { if (isFirstCellOfChannel) up = tabFocusRequester }
            .focusable()
            .clickable { onProgramClick(epgProgram) }
            .graphicsLayer(clip = false)
            .drawBehind {
                val fullHeight = size.height + animatedExpansion.toPx()
                val bgColor = if (cellData.isPast) Color(0xFF151515) else Color(0xFF222222)
                val borderColor = Color(0xFF333333)

                drawRect(
                    color = bgColor,
                    topLeft = Offset.Zero,
                    size = size.copy(height = fullHeight)
                )

                drawRect(
                    color = if (cellData.isPast) Color.Gray else cellData.genreColor,
                    topLeft = Offset.Zero,
                    size = size.copy(width = 3.dp.toPx(), height = fullHeight)
                )

                // セルの境界線（右と下）
                drawLine(
                    color = borderColor,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, fullHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = borderColor,
                    start = Offset(0f, fullHeight),
                    end = Offset(size.width, fullHeight),
                    strokeWidth = 1.dp.toPx()
                )

                if (isFocused) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset.Zero,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                        size = size.copy(height = fullHeight)
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onGloballyPositioned { coords ->
                    contentHeightDp = with(density) { coords.size.height.toDp() }
                }
                .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 6.dp)
        ) {
            val textAlpha = if (cellData.isPast) 0.5f else 1.0f

            Text(
                text = cellData.startTimeStr,
                fontSize = 9.sp,
                color = Color.LightGray.copy(alpha = textAlpha),
                maxLines = 1
            )

            Text(
                text = epgProgram.title,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = textAlpha),
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = if (isFocused) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )

            if (isFocused || cellData.height > 60.dp) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = epgProgram.description ?: "",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = if (isFocused) 0.8f else 0.5f * textAlpha),
                    lineHeight = 11.sp,
                    maxLines = if (isFocused) 10 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BroadcastingTypeTabs(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    tabFocusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    firstCellFocusRequester: FocusRequester,
    categories: List<String>
) {
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusGroup(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { code ->
            val isSelected = selectedType == code

            Surface(
                onClick = { onTypeSelected(code) },
                modifier = Modifier
                    .width(110.dp)
                    .height(36.dp)
                    .padding(horizontal = 4.dp)
                    .then(if (isSelected) Modifier.focusRequester(tabFocusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused && selectedType != code) {
                            onTypeSelected(code)
                        }
                    }
                    .focusProperties {
                        down = firstCellFocusRequester
                    },
                shape = ClickableSurfaceDefaults.shape(RectangleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.White,
                    contentColor = if (isSelected) Color.White else Color.Gray,
                    focusedContentColor = Color.Black
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    androidx.compose.material3.Text(
                        text = typeLabels[code] ?: code,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = LocalContentColor.current
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelHeaderCell(channel: EpgChannel, width: Dp, height: Dp, logoUrl: String) {
    Surface(
        modifier = Modifier.width(width).height(height).focusable(false),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(containerColor = Color(0xFF111111), contentColor = Color.White),
        border = Border(border = BorderStroke(0.5.dp, Color(0xFF333333)), shape = RectangleShape)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(2.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 32.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(6.dp))
                androidx.compose.material3.Text(text = channel.channel_number ?: "", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Black))
            }
            androidx.compose.material3.Text(text = channel.name, style = MaterialTheme.typography.bodySmall.copy(color = Color.LightGray, fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                .drawBehind { drawLine(Color(0xFF222222), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
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
fun CurrentTimeIndicatorOptimized(baseTime: OffsetDateTime, totalWidth: Dp, scrollOffset: Int) {
    var nowOffsetMinutes by remember { mutableStateOf(Duration.between(baseTime, OffsetDateTime.now()).toMinutes()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffsetMinutes = Duration.between(baseTime, OffsetDateTime.now()).toMinutes()
        }
    }

    val density = LocalDensity.current
    val absoluteLineOffsetDp = (nowOffsetMinutes * DP_PER_MINUTE).dp
    val scrollOffsetDp = with(density) { scrollOffset.toDp() }
    val finalOffset = absoluteLineOffsetDp - scrollOffsetDp

    if (finalOffset > (-2).dp) {
        Box(modifier = Modifier.width(totalWidth).offset(y = finalOffset).zIndex(10f)) {
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.Red))
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
    Box(modifier = Modifier.width(width).height(height).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(text = "${baseTime.monthValue}/${baseTime.dayOfMonth}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text(text = "(${dayOfWeekJapan[dayOfWeekIndex]})", color = dayColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}