//package com.example.komorebi.ui.program
//
//import android.os.Build
//import androidx.annotation.RequiresApi
//import androidx.compose.animation.*
//import androidx.compose.animation.core.*
//import androidx.compose.foundation.*
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.draw.drawBehind
//import androidx.compose.ui.focus.*
//import androidx.compose.ui.geometry.Offset
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.RectangleShape
//import androidx.compose.ui.graphics.graphicsLayer
//import androidx.compose.ui.layout.ContentScale
//import androidx.compose.ui.layout.onGloballyPositioned
//import androidx.compose.ui.platform.LocalConfiguration
//import androidx.compose.ui.platform.LocalDensity
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.text.style.TextOverflow
//import androidx.compose.ui.unit.Dp
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.compose.ui.zIndex
//import androidx.tv.material3.*
//import coil.compose.AsyncImage
//import com.example.komorebi.data.model.*
//import com.example.komorebi.data.util.EpgUtils
//import com.example.komorebi.ui.program.EpgJumpMenu
//import com.example.komorebi.viewmodel.*
//import kotlinx.coroutines.delay
//import java.time.Duration
//import java.time.OffsetDateTime
//
//private const val DP_PER_MINUTE = 1.3f
//private val HOUR_HEIGHT = (60 * DP_PER_MINUTE).dp
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun EpgScreen(
//    viewModel: EpgViewModel,
//    topTabFocusRequester: FocusRequester,
//    tabFocusRequester: FocusRequester,
//    onBroadcastingTabFocusChanged: (Boolean) -> Unit = {},
//    firstCellFocusRequester: FocusRequester,
//    // ★ HomeLauncherScreenから受け取る日時指定メニューの状態
//    isJumpMenuOpen: Boolean,
//    onJumpMenuStateChanged: (Boolean) -> Unit,
//    selectedProgram: EpgProgram?,
//    onProgramSelected: (EpgProgram?) -> Unit,
//    selectedBroadcastingType: String,
//    onTypeSelected: (String) -> Unit,
//    onChannelSelected: (String) -> Unit,
//) {
//    val uiState = viewModel.uiState
//
//    // 表示の起点となる時間
//    var baseTime by remember {
//        mutableStateOf(OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0))
//    }
//
//    var isFirstLoad by remember { mutableStateOf(true) }
//
//    val displayChannels by remember(selectedBroadcastingType, uiState) {
//        derivedStateOf {
//            if (uiState is EpgUiState.Success) {
//                uiState.data.filter { it.channel.type == selectedBroadcastingType }
//            } else emptyList()
//        }
//    }
//
//    val categories = remember(uiState) {
//        if (uiState is EpgUiState.Success) {
//            val order = listOf("GR", "BS", "CS", "BS4K", "SKY")
//            val availableTypes = uiState.data.map { it.channel.type }.distinct()
//            order.filter { it in availableTypes } + (availableTypes - order.toSet())
//        } else listOf("GR")
//    }
//
//    // ジャンプメニュー用のデータ生成
//    val jumpDates = remember { (0..7).map { OffsetDateTime.now().plusDays(it.toLong()) } }
//    val jumpTimeSlots = remember { listOf(5, 8, 11, 14, 17, 20, 23, 2) }
//
//    Box(Modifier.fillMaxSize()) {
//        Column(Modifier.fillMaxSize().background(Color.Black)) {
//            BroadcastingTypeTabs(
//                selectedType = selectedBroadcastingType,
//                onTypeSelected = onTypeSelected,
//                tabFocusRequester = tabFocusRequester,
//                onFocusChanged = onBroadcastingTabFocusChanged,
//                firstCellFocusRequester = firstCellFocusRequester,
//                categories = categories,
//                // ★ ボタンクリックで親の状態を true に
//                onJumpToDateClick = { onJumpMenuStateChanged(true) }
//            )
//
//            Box(modifier = Modifier.fillMaxSize()) {
//                val animDuration = if (isFirstLoad) 0 else 200
//                AnimatedContent(
//                    targetState = uiState,
//                    transitionSpec = {
//                        (fadeIn(animationSpec = tween(animDuration)) + scaleIn(initialScale = 0.99f, animationSpec = tween(animDuration)))
//                            .togetherWith(fadeOut(animationSpec = tween(if (isFirstLoad) 0 else 100)))
//                    },
//                    label = "EpgGridTransition"
//                ) { state ->
//                    when (state) {
//                        is EpgUiState.Success -> {
//                            key(selectedBroadcastingType, baseTime) {
//                                EpgGrid(
//                                    channels = displayChannels,
//                                    baseTime = baseTime,
//                                    viewModel = viewModel,
//                                    onProgramClick = { onProgramSelected(it) },
//                                    firstCellFocusRequester = firstCellFocusRequester,
//                                    tabFocusRequester = tabFocusRequester,
//                                    visibleChannelCount = 7,
//                                    skipAnimation = isFirstLoad
//                                )
//                            }
//                            SideEffect { isFirstLoad = false }
//                        }
//                        is EpgUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
//                            androidx.compose.material3.CircularProgressIndicator(color = Color.Cyan)
//                        }
//                        is EpgUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
//                            androidx.compose.material3.Text("エラーが発生しました", color = Color.Red)
//                        }
//                    }
//                }
//            }
//        }
//
//        if (selectedProgram != null) {
//            ProgramDetailModal(
//                program = selectedProgram,
//                onPrimaryAction = onChannelSelected,
//                onDismiss = { onProgramSelected(null) }
//            )
//        }
//
//        // ★ 日時指定メニュー：親画面の状態を監視して表示
//        if (isJumpMenuOpen) {
//            EpgJumpMenu(
//                dates = jumpDates,
//                timeSlots = jumpTimeSlots,
//                onSelect = { selectedTime ->
//                    baseTime = selectedTime
//                    onJumpMenuStateChanged(false) // 選択後に閉じる
//                },
//                onDismiss = { onJumpMenuStateChanged(false) } // キャンセル時に閉じる
//            )
//        }
//    }
//}
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun EpgGrid(
//    channels: List<EpgChannelWrapper>,
//    baseTime: OffsetDateTime,
//    viewModel: EpgViewModel,
//    onProgramClick: (EpgProgram) -> Unit,
//    firstCellFocusRequester: FocusRequester,
//    tabFocusRequester: FocusRequester,
//    visibleChannelCount: Int = 7,
//    skipAnimation: Boolean = false
//) {
//    val verticalScrollState = rememberScrollState()
//    val horizontalScrollState = rememberScrollState()
//
//    var loadedChannelCount by remember(channels) {
//        mutableIntStateOf(if (skipAnimation) channels.size else (visibleChannelCount + 2).coerceAtMost(channels.size))
//    }
//    var isReadyToRender by remember(channels) {
//        mutableStateOf(skipAnimation)
//    }
//
//    val configuration = LocalConfiguration.current
//    val density = LocalDensity.current
//    val screenWidth = configuration.screenWidthDp.dp
//    val timeColumnWidth = 55.dp
//    val headerHeight = 48.dp
//    val channelWidth = remember(screenWidth) { (screenWidth - timeColumnWidth) / visibleChannelCount }
//
//    val vScrollPos = verticalScrollState.value
//    val hScrollPos = horizontalScrollState.value
//    val screenWidthPx = with(density) { screenWidth.toPx() }
//    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
//
//    // ★ スクロール位置の制御
//    LaunchedEffect(channels, baseTime) {
//        val now = OffsetDateTime.now()
//
//        // baseTimeが今日の場合のみ、現在時刻付近にスクロール
//        val targetScrollDp = if (baseTime.toLocalDate() == now.toLocalDate()) {
//            val minutesFromBase = Duration.between(baseTime, now).toMinutes()
//            ((minutesFromBase - 30) * DP_PER_MINUTE).dp
//        } else {
//            0.dp // 明日以降なら一番上(0時)に配置
//        }
//
//        verticalScrollState.scrollTo(targetScrollDp.value.toInt())
//
//        if (!skipAnimation) {
//            delay(16)
//            isReadyToRender = true
//            while (loadedChannelCount < channels.size) {
//                delay(32)
//                loadedChannelCount = (loadedChannelCount + 5).coerceAtMost(channels.size)
//            }
//        } else {
//            isReadyToRender = true
//        }
//    }
//
//    if (!isReadyToRender) {
//        Box(Modifier.fillMaxSize().background(Color.Black))
//        return
//    }
//
//    Column(modifier = Modifier.fillMaxSize()) {
//        Row(modifier = Modifier.fillMaxWidth().zIndex(4f)) {
//            DateHeaderBox(baseTime, timeColumnWidth, headerHeader = headerHeight)
//            Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
//                channels.forEachIndexed { index, wrapper ->
//                    val isLoaded = index < loadedChannelCount
//                    val opacity by animateFloatAsState(
//                        targetValue = if (isLoaded) 1f else 0f,
//                        animationSpec = tween(if (skipAnimation) 0 else 250),
//                        label = "ChannelFade"
//                    )
//
//                    val channelLeftPx = with(density) { (channelWidth * index).toPx() }
//                    val isVisible = isLoaded &&
//                            channelLeftPx + with(density) { channelWidth.toPx() } > hScrollPos &&
//                            channelLeftPx < hScrollPos + screenWidthPx
//
//                    Box(modifier = Modifier.graphicsLayer { alpha = opacity }) {
//                        if (isVisible) {
//                            ChannelHeaderCell(wrapper.channel, channelWidth, headerHeight, viewModel.getMirakurunLogoUrl(wrapper.channel))
//                        } else {
//                            Spacer(Modifier.width(channelWidth).height(headerHeight))
//                        }
//                    }
//                }
//            }
//        }
//
//        Row(modifier = Modifier.fillMaxSize()) {
//            Column(modifier = Modifier
//                .width(timeColumnWidth)
//                .fillMaxHeight()
//                .background(Color(0xFF111111))
//                .verticalScroll(verticalScrollState)
//                .zIndex(2f)) {
//                TimeColumnContent(baseTime)
//            }
//
//            Box(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .verticalScroll(verticalScrollState)
//                    .horizontalScroll(horizontalScrollState)
//                    .focusGroup()
//            ) {
//                Row {
//                    channels.forEachIndexed { channelIndex, channelWrapper ->
//                        val isLoaded = channelIndex < loadedChannelCount
//                        val opacity by animateFloatAsState(
//                            targetValue = if (isLoaded) 1f else 0f,
//                            animationSpec = tween(if (skipAnimation) 0 else 300),
//                            label = "GridFade"
//                        )
//
//                        Box(
//                            modifier = Modifier
//                                .width(channelWidth)
//                                .height(HOUR_HEIGHT * 24)
//                                .graphicsLayer { alpha = opacity }
//                        ) {
//                            if (isLoaded) {
//                                val channelLeftPx = with(density) { (channelWidth * channelIndex).toPx() }
//                                val isChannelInView = channelLeftPx + with(density) { channelWidth.toPx() } > hScrollPos - screenWidthPx &&
//                                        channelLeftPx < hScrollPos + screenWidthPx * 2
//
//                                if (isChannelInView) {
//                                    val now = OffsetDateTime.now()
//                                    channelWrapper.programs.forEach { program ->
//                                        ProgramContainer(
//                                            program = program,
//                                            baseTime = baseTime,
//                                            vScrollPos = vScrollPos,
//                                            screenHeightPx = screenHeightPx,
//                                            channelIndex = channelIndex,
//                                            now = now,
//                                            channelWidth = channelWidth,
//                                            firstCellFocusRequester = firstCellFocusRequester,
//                                            tabFocusRequester = tabFocusRequester,
//                                            onProgramClick = onProgramClick
//                                        )
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//                CurrentTimeIndicatorOptimized(baseTime, (channelWidth * channels.size), verticalScrollState.value)
//            }
//        }
//    }
//}
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun ProgramContainer(
//    program: EpgProgram,
//    baseTime: OffsetDateTime,
//    vScrollPos: Int,
//    screenHeightPx: Float,
//    channelIndex: Int,
//    now: OffsetDateTime,
//    channelWidth: Dp,
//    firstCellFocusRequester: FocusRequester,
//    tabFocusRequester: FocusRequester,
//    onProgramClick: (EpgProgram) -> Unit
//) {
//    val density = LocalDensity.current
//    val startTime = remember(program.start_time) { OffsetDateTime.parse(program.start_time) }
//    val startMinutes = remember(startTime, baseTime) { Duration.between(baseTime, startTime).toMinutes() }
//
//    val topPx = with(density) { (startMinutes * DP_PER_MINUTE).dp.toPx() }
//    val heightPx = with(density) { ((program.duration / 60) * DP_PER_MINUTE).dp.toPx() }
//
//    if (topPx + heightPx > vScrollPos && topPx < vScrollPos + screenHeightPx) {
//        val isCurrentLiveOnFirstChannel = channelIndex == 0 &&
//                now.isAfter(startTime.minusMinutes(5)) &&
//                now.isBefore(startTime.plusSeconds(program.duration.toLong()))
//
//        // 修正：スクロール位置の最上部（ヘッダー直下）に見えている番組だけ、上キーでタブに戻れるようにする
//        // 判定しきい値を10dp程度持たせる
//        val isAtTopVisibleArea = topPx <= vScrollPos + with(density) { 10.dp.toPx() }
//
//        CompactProgramCell(
//            epgProgram = program,
//            baseTime = baseTime,
//            width = channelWidth,
//            canGoUpToTab = isAtTopVisibleArea,
//            focusRequester = if (isCurrentLiveOnFirstChannel) firstCellFocusRequester else null,
//            tabFocusRequester = tabFocusRequester,
//            onProgramClick = onProgramClick
//        )
//    }
//}
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun CompactProgramCell(
//    epgProgram: EpgProgram,
//    baseTime: OffsetDateTime,
//    width: Dp,
//    canGoUpToTab: Boolean,
//    focusRequester: FocusRequester?,
//    tabFocusRequester: FocusRequester,
//    onProgramClick: (EpgProgram) -> Unit,
//) {
//    var isFocused by remember { mutableStateOf(false) }
//    val density = LocalDensity.current
//    var contentHeightDp by remember { mutableStateOf(0.dp) }
//
//    val cellData = remember(epgProgram.id, baseTime) {
//        val startTime = OffsetDateTime.parse(epgProgram.start_time)
//        val minutesFromBase = Duration.between(baseTime, startTime).toMinutes()
//        val durationMin = epgProgram.duration / 60
//        object {
//            val top = (minutesFromBase * DP_PER_MINUTE).dp
//            val height = (durationMin * DP_PER_MINUTE).dp
//            val startTimeStr = EpgUtils.formatTime(epgProgram.start_time)
//            val genreColor = EpgUtils.getGenreColor(epgProgram.majorGenre)
//            val isPast = startTime.plusSeconds(epgProgram.duration.toLong()).isBefore(OffsetDateTime.now())
//        }
//    }
//
//    val expansionAmount = if (isFocused) {
//        (contentHeightDp - cellData.height).coerceAtLeast(0.dp)
//    } else 0.dp
//
//    Box(
//        modifier = Modifier
//            .offset(y = cellData.top)
//            .width(width)
//            .height(cellData.height)
//            .zIndex(if (isFocused) 100f else 1f)
//            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
//            .onFocusChanged { isFocused = it.isFocused }
//            .focusProperties {
//                // 修正：最上位の時だけ放送波種別タブに指定、それ以外はシステムに任せる（上の番組へ行く）
//                if (canGoUpToTab) {
//                    up = tabFocusRequester
//                }
//            }
//            .focusable()
//            .clickable { onProgramClick(epgProgram) }
//            .graphicsLayer { clip = false }
//            .drawBehind {
//                val fullHeight = size.height + expansionAmount.toPx()
//                val bgColor = if (cellData.isPast) Color(0xFF151515) else Color(0xFF222222)
//                val borderColor = Color(0xFF333333)
//
//                drawRect(color = bgColor, size = size.copy(height = fullHeight))
//                drawRect(
//                    color = if (cellData.isPast) Color.Gray else cellData.genreColor,
//                    size = size.copy(width = 3.dp.toPx(), height = fullHeight)
//                )
//
//                drawLine(borderColor, Offset(size.width, 0f), Offset(size.width, fullHeight), 1.dp.toPx())
//                drawLine(borderColor, Offset(0f, fullHeight), Offset(size.width, fullHeight), 1.dp.toPx())
//
//                if (isFocused) {
//                    drawRect(
//                        color = Color.White,
//                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
//                        size = size.copy(height = fullHeight)
//                    )
//                }
//            }
//    ) {
//        if (cellData.height > 12.dp || isFocused) {
//            Column(
//                modifier = Modifier
//                    .width(width)
//                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
//                    .onGloballyPositioned { coords ->
//                        if (isFocused) {
//                            contentHeightDp = with(density) { coords.size.height.toDp() }
//                        }
//                    }
//                    .padding(start = 8.dp, top = 2.dp, end = 6.dp, bottom = 4.dp)
//            ) {
//                val textAlpha = if (cellData.isPast) 0.5f else 1.0f
//                Text(
//                    text = cellData.startTimeStr,
//                    fontSize = 9.sp,
//                    color = Color.LightGray.copy(alpha = textAlpha),
//                    maxLines = 1
//                )
//                Text(
//                    text = epgProgram.title,
//                    fontSize = 11.sp,
//                    color = Color.White.copy(alpha = textAlpha),
//                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
//                    maxLines = if (isFocused) 10 else 1,
//                    overflow = TextOverflow.Ellipsis,
//                    lineHeight = 13.sp
//                )
//                if (isFocused || cellData.height > 60.dp) {
//                    Spacer(modifier = Modifier.height(2.dp))
//                    Text(
//                        text = epgProgram.description ?: "",
//                        fontSize = 9.sp,
//                        color = Color.White.copy(alpha = if (isFocused) 0.8f else 0.5f * textAlpha),
//                        lineHeight = 11.sp,
//                        maxLines = if (isFocused) 10 else 2,
//                        overflow = TextOverflow.Ellipsis
//                    )
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun BroadcastingTypeTabs(
//    selectedType: String,
//    onTypeSelected: (String) -> Unit,
//    tabFocusRequester: FocusRequester,
//    onFocusChanged: (Boolean) -> Unit,
//    firstCellFocusRequester: FocusRequester,
//    categories: List<String>,
//    onJumpToDateClick: () -> Unit = {}
//) {
//    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")
//    val jumpButtonFocusRequester = remember { FocusRequester() }
//
//    Box(
//        modifier = Modifier
//            .fillMaxWidth()
//            .height(44.dp)
//            .padding(horizontal = 16.dp)
//            .onFocusChanged { onFocusChanged(it.hasFocus) }
//    ) {
//        Surface(
//            onClick = onJumpToDateClick,
//            modifier = Modifier
//                .align(Alignment.CenterStart)
//                .width(100.dp)
//                .height(36.dp)
//                .focusRequester(jumpButtonFocusRequester)
//                .focusProperties {
//                    down = firstCellFocusRequester
//                },
//            shape = ClickableSurfaceDefaults.shape(RectangleShape),
//            colors = ClickableSurfaceDefaults.colors(
//                containerColor = Color.Transparent,
//                focusedContainerColor = Color.White,
//                contentColor = Color.Gray,
//                focusedContentColor = Color.Black
//            ),
//            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
//        ) {
//            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
//                androidx.compose.material3.Text(
//                    text = "日時指定",
//                    fontSize = 15.sp,
//                    fontWeight = FontWeight.Normal,
//                    color = LocalContentColor.current
//                )
//            }
//        }
//
//        Row(
//            modifier = Modifier
//                .align(Alignment.Center)
//                .focusGroup(),
//            horizontalArrangement = Arrangement.Center,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            categories.forEach { code ->
//                val isSelected = selectedType == code
//
//                Surface(
//                    onClick = { onTypeSelected(code) },
//                    modifier = Modifier
//                        .width(100.dp)
//                        .height(36.dp)
//                        .padding(horizontal = 2.dp)
//                        .then(if (isSelected) Modifier.focusRequester(tabFocusRequester) else Modifier)
//                        .onFocusChanged {
//                            if (it.isFocused && selectedType != code) {
//                                onTypeSelected(code)
//                            }
//                        }
//                        .focusProperties {
//                            down = firstCellFocusRequester
//                        },
//                    shape = ClickableSurfaceDefaults.shape(RectangleShape),
//                    colors = ClickableSurfaceDefaults.colors(
//                        containerColor = Color.Transparent,
//                        focusedContainerColor = Color.White,
//                        contentColor = if (isSelected) Color.White else Color.Gray,
//                        focusedContentColor = Color.Black
//                    ),
//                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
//                ) {
//                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
//                        androidx.compose.material3.Text(
//                            text = typeLabels[code] ?: code,
//                            fontSize = 15.sp,
//                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
//                            color = LocalContentColor.current
//                        )
//                    }
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun ChannelHeaderCell(channel: EpgChannel, width: Dp, height: Dp, logoUrl: String) {
//    Surface(
//        modifier = Modifier.width(width).height(height).focusable(false),
//        shape = RectangleShape,
//        colors = SurfaceDefaults.colors(containerColor = Color(0xFF111111), contentColor = Color.White),
//        border = Border(border = BorderStroke(0.5.dp, Color(0xFF333333)), shape = RectangleShape)
//    ) {
//        Column(modifier = Modifier.fillMaxSize().padding(2.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
//            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
//                AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 32.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)))
//                Spacer(modifier = Modifier.width(6.dp))
//                androidx.compose.material3.Text(text = channel.channel_number ?: "", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Black))
//            }
//            androidx.compose.material3.Text(text = channel.name, style = MaterialTheme.typography.bodySmall.copy(color = Color.LightGray, fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
//        }
//    }
//}
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun TimeColumnContent(baseTime: OffsetDateTime) {
//    repeat(24) { hourOffset ->
//        val displayTime = baseTime.plusHours(hourOffset.toLong())
//        Box(
//            modifier = Modifier.height(HOUR_HEIGHT).fillMaxWidth()
//                .drawBehind { drawLine(Color(0xFF222222), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
//            contentAlignment = Alignment.Center
//        ) {
//            Column(horizontalAlignment = Alignment.CenterHorizontally) {
//                androidx.compose.material3.Text(text = "${displayTime.hour}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
//                androidx.compose.material3.Text(text = "時", color = Color.Gray, fontSize = 9.sp)
//            }
//        }
//    }
//}
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun CurrentTimeIndicatorOptimized(baseTime: OffsetDateTime, totalWidth: Dp, scrollOffset: Int) {
//    var nowOffsetMinutes by remember { mutableStateOf(Duration.between(baseTime, OffsetDateTime.now()).toMinutes()) }
//    LaunchedEffect(Unit) {
//        while (true) {
//            kotlinx.coroutines.delay(60_000)
//            nowOffsetMinutes = Duration.between(baseTime, OffsetDateTime.now()).toMinutes()
//        }
//    }
//    val density = LocalDensity.current
//    val absoluteLineOffsetDp = (nowOffsetMinutes * DP_PER_MINUTE).dp
//    val scrollOffsetDp = with(density) { scrollOffset.toDp() }
//    val finalOffset = absoluteLineOffsetDp - scrollOffsetDp
//    if (finalOffset > (-2).dp) {
//        Box(modifier = Modifier.width(totalWidth).offset(y = finalOffset).zIndex(10f)) {
//            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.Red))
//        }
//    }
//}
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun DateHeaderBox(baseTime: OffsetDateTime, width: Dp, headerHeader: Dp) {
//    val dayOfWeekJapan = listOf("日", "月", "火", "水", "木", "金", "土")
//    val dayOfWeekIndex = baseTime.dayOfWeek.value % 7
//    val dayColor = when (dayOfWeekIndex) {
//        0 -> Color(0xFFFF5252)
//        6 -> Color(0xFF448AFF)
//        else -> Color.White
//    }
//    Box(modifier = Modifier.width(width).height(headerHeader).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
//        Column(horizontalAlignment = Alignment.CenterHorizontally) {
//            androidx.compose.material3.Text(text = "${baseTime.monthValue}/${baseTime.dayOfMonth}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
//            androidx.compose.material3.Text(text = "(${dayOfWeekJapan[dayOfWeekIndex]})", color = dayColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
//        }
//    }
//}