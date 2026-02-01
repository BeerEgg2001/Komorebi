package com.example.komorebi.ui.program

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
                    Box(modifier = Modifier.weight(1f)) {
                        key(selectedBroadcastingType) {
                            EpgGrid(
                                channels = displayChannels,
                                baseTime = baseTime,
                                viewModel = viewModel,
                                onProgramClick = { onProgramSelected(it) },
                                firstCellFocusRequester = firstCellFocusRequester,
                                selectedBroadcastingType = selectedBroadcastingType,
                                tabFocusRequester = tabFocusRequester
                            )
                        }
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

        if (selectedProgram != null) {
            key(selectedProgram.id) {
                ProgramDetailModal(
                    program = selectedProgram,
                    onPrimaryAction = onChannelSelected,
                    onDismiss = { onProgramSelected(null) }
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
    val focusRequesters = remember(categories) { categories.associateWith { FocusRequester() } }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(0.dp)
                .focusRequester(tabFocusRequester)
                .onFocusChanged {
                    onFocusChanged(it.isFocused || it.hasFocus)
                    if (it.isFocused) { focusRequesters[selectedType]?.requestFocus() }
                }
                .focusable()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color.Black)
                .focusGroup()
                .onFocusChanged { onFocusChanged(it.isFocused || it.hasFocus) },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { code ->
                val isSelected = selectedType == code
                val requester = focusRequesters[code]!!

                Surface(
                    onClick = { firstCellFocusRequester.requestFocus() },
                    modifier = Modifier
                        .width(110.dp)
                        .height(36.dp)
                        .padding(horizontal = 4.dp)
                        .focusRequester(requester)
                        .onFocusChanged { if (it.isFocused && selectedType != code) onTypeSelected(code) }
                        .focusProperties { down = firstCellFocusRequester },
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
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgGrid(
    channels: List<EpgChannelWrapper>,
    baseTime: OffsetDateTime,
    viewModel: EpgViewModel,
    onProgramClick: (EpgProgram) -> Unit,
    firstCellFocusRequester: FocusRequester,
    selectedBroadcastingType: String,
    tabFocusRequester: FocusRequester
) {
    val verticalScrollState = rememberScrollState()
    val channelWidth = 150.dp
    val timeColumnWidth = 55.dp
    val headerHeight = 48.dp
    val totalGridWidth = remember(channels.size) { channelWidth * channels.size }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().zIndex(4f)) {
            DateHeaderBox(baseTime, timeColumnWidth, headerHeight)
            androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.fillMaxWidth()) {
                items(channels.size, key = { channels[it].channel.id }) { index ->
                    val wrapper = channels[index]
                    ChannelHeaderCell(wrapper.channel, channelWidth, headerHeight, viewModel.getMirakurunLogoUrl(wrapper.channel))
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier
                .width(timeColumnWidth)
                .fillMaxHeight()
                .background(Color(0xFF111111))
                .verticalScroll(verticalScrollState)
                .zIndex(2f)
            ) {
                TimeColumnContent(baseTime)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScrollState)
                        .focusGroup()
                ) {
                    items(channels.size, key = { "${selectedBroadcastingType}_${channels[it].channel.id}" }) { channelIndex ->
                        val channelWrapper = channels[channelIndex]
                        val programFocusRequesters = remember(channelWrapper.programs.size) {
                            List(channelWrapper.programs.size) { FocusRequester() }
                        }

                        Box(modifier = Modifier.width(channelWidth).height(HOUR_HEIGHT * 24)) {
                            channelWrapper.programs.forEachIndexed { programIndex, program ->
                                CompactProgramCell(
                                    epgProgram = program,
                                    baseTime = baseTime,
                                    width = channelWidth,
                                    columnIndex = channelIndex,
                                    totalColumns = channels.size,
                                    focusRequester = programFocusRequesters[programIndex],
                                    upRequester = if (programIndex > 0) programFocusRequesters[programIndex - 1] else tabFocusRequester,
                                    downRequester = if (programIndex < programFocusRequesters.lastIndex) programFocusRequesters[programIndex + 1] else null,
                                    onProgramClick = onProgramClick,
                                    modifier = if (channelIndex == 0 && programIndex == 0)
                                        Modifier.focusRequester(firstCellFocusRequester) else Modifier
                                )
                            }
                        }
                    }
                }
                // ★ 修正：スクロールオフセットを渡す
                CurrentTimeIndicatorOptimized(baseTime, totalGridWidth, verticalScrollState.value)
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
        val topOffset = if (minutesFromBase < 0) 0.dp else (minutesFromBase * DP_PER_MINUTE).dp
        val isVisible = endTime.isAfter(baseTime) && minutesFromBase < 1440
        val adjustedHeight = if (minutesFromBase < 0) {
            ((durationMin + minutesFromBase) * DP_PER_MINUTE).dp
        } else {
            (durationMin * DP_PER_MINUTE).dp
        }
        val needsExpansion = adjustedHeight < 70.dp
        object {
            val top = topOffset
            val height = adjustedHeight
            val isVisible = isVisible
            val isPast = endTime.isBefore(OffsetDateTime.now())
            val startTimeStr = EpgUtils.formatTime(epgProgram.start_time)
            val genreColor = EpgUtils.getGenreColor(epgProgram.majorGenre)
            val needsExpansion = needsExpansion
        }
    }

    if (!cellData.isVisible || cellData.height <= 0.dp) return
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .offset(y = cellData.top.coerceAtLeast(0.dp))
            .width(width)
            .zIndex(if (isFocused) 100f else 1f)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusProperties {
                up = upRequester ?: FocusRequester.Default
                down = downRequester ?: FocusRequester.Default
            }
            .focusable()
            .onKeyEvent { keyEvent ->
                val isCenterKey = keyEvent.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER ||
                        keyEvent.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_ENTER

                if (isCenterKey) {
                    if (keyEvent.type == KeyEventType.KeyUp) return@onKeyEvent true
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        onProgramClick(epgProgram)
                        return@onKeyEvent true
                    }
                }
                false
            }
            .layout { measurable, constraints ->
                val expandedHeight = if (isFocused && cellData.needsExpansion) 110.dp.roundToPx() else cellData.height.roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
                val reportHeight = if (cellData.top < 0.dp) (cellData.height + cellData.top).coerceAtLeast(0.dp).roundToPx() else cellData.height.roundToPx()

                layout(placeable.width, reportHeight) {
                    val yOffset = if (isFocused && expandedHeight > reportHeight) {
                        (-(expandedHeight - reportHeight) / 2).coerceAtLeast(if (cellData.top > 0.dp) -cellData.top.roundToPx() else 0)
                    } else 0
                    placeable.place(0, yOffset)
                }
            }
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
            androidx.compose.material3.Text(text = cellData.startTimeStr, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = Color.LightGray.copy(alpha = textAlpha)))
            androidx.compose.material3.Text(
                text = epgProgram.title,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = Color.White.copy(alpha = textAlpha), fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold, lineHeight = 13.sp),
                maxLines = if (cellData.height > 30.dp || isFocused) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (epgProgram.description.isNotEmpty()) {
                val descMaxLines = when {
                    isFocused && cellData.needsExpansion -> 4
                    cellData.height > 90.dp -> 4
                    cellData.height > 70.dp -> 3
                    cellData.height > 50.dp -> 2
                    cellData.height > 40.dp -> 1
                    else -> 0
                }
                if (descMaxLines > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    androidx.compose.material3.Text(
                        text = epgProgram.description,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = Color.LightGray.copy(alpha = textAlpha * 0.8f), lineHeight = 12.sp),
                        maxLines = descMaxLines,
                        overflow = TextOverflow.Ellipsis,
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
fun CurrentTimeIndicatorOptimized(baseTime: OffsetDateTime, totalWidth: Dp, scrollOffset: Int) {
    var nowOffsetMinutes by remember { mutableStateOf(Duration.between(baseTime, OffsetDateTime.now()).toMinutes()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffsetMinutes = Duration.between(baseTime, OffsetDateTime.now()).toMinutes()
        }
    }

    val density = LocalDensity.current
    // 本来の位置(dp)
    val absoluteLineOffsetDp = (nowOffsetMinutes * DP_PER_MINUTE).dp
    // スクロールによる補正(px -> dp)
    val scrollOffsetDp = with(density) { scrollOffset.toDp() }
    val finalOffset = absoluteLineOffsetDp - scrollOffsetDp

    // 画面外（上部）に消えた場合は描画しない
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