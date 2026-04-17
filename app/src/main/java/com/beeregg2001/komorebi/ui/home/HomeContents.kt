@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.home.components.*
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.delay

private const val TAG = "HomeContents"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeContents(
    lastWatchedChannels: List<Channel>,
    watchHistory: List<KonomiHistoryProgram>,
    hotChannels: List<UiChannelState>,
    upcomingReserves: List<ReserveItem>,
    genrePickup: List<Pair<EpgProgram, String>>,
    pickupGenreName: String,
    pickupTimeSlot: String,
    groupedChannels: Map<String, List<Channel>>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    onChannelClick: (Channel) -> Unit,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onReserveClick: (ReserveItem) -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    konomiIp: String, konomiPort: String,
    mirakurunIp: String, mirakurunPort: String,
    tabFocusRequester: FocusRequester,
    externalFocusRequester: FocusRequester,
    onUiReady: () -> Unit = {},
    modifier: Modifier = Modifier,
    lastFocusedChannelId: String? = null,
    lastFocusedProgramId: String? = null,
    isTopNavFocused: Boolean = false,
    ticketManager: HomeFocusTicketManager,
    homeViewModel: HomeViewModel,
    recordViewModel: RecordViewModel = hiltViewModel(),
    timeFormat: String,
) {
    val lazyListState = rememberLazyListState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val isFirstItemRendered =
        remember { derivedStateOf { lazyListState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isFirstItemRendered.value) {
        if (isFirstItemRendered.value) {
            delay(100); onUiReady()
        }
    }

    LaunchedEffect(lastWatchedChannels, hotChannels) {
        if (lastWatchedChannels.isNotEmpty() || hotChannels.isNotEmpty()) {
            delay(400); onUiReady()
        }
    }

    LaunchedEffect(lastWatchedChannels, hotChannels, genrePickup) {
        if (lastWatchedChannels.isNotEmpty() || hotChannels.isNotEmpty() || genrePickup.isNotEmpty()) {
            delay(300); onUiReady()
        }
    }

    LaunchedEffect(Unit) { delay(3000); onUiReady() }

    val welcomeHeroInfo = remember {
        HomeHeroInfo(
            title = "Komorebi へようこそ",
            subtitle = "ホーム",
            description = "十字キーの「下」を押してコンテンツを選択してください。\n現在放送中の人気番組や、録画した番組の続きをここから楽しめます。",
            tag = "Welcome"
        )
    }

    var pendingHeroInfo by remember { mutableStateOf<HomeHeroInfo?>(welcomeHeroInfo) }
    var currentHeroInfo by remember { mutableStateOf<HomeHeroInfo>(welcomeHeroInfo) }
    var isFirstHeroLoad by remember { mutableStateOf(true) }
    val colors = KomorebiTheme.colors

    LaunchedEffect(isTopNavFocused) { if (isTopNavFocused) pendingHeroInfo = welcomeHeroInfo }

    LaunchedEffect(pendingHeroInfo) {
        if (pendingHeroInfo != null) {
            if (isFirstHeroLoad) {
                currentHeroInfo = pendingHeroInfo!!; isFirstHeroLoad = false
            } else {
                delay(300); currentHeroInfo = pendingHeroInfo!!
            }
        }
    }

    val topSection =
        remember(lastWatchedChannels, hotChannels, genrePickup, watchHistory, upcomingReserves) {
            when {
                lastWatchedChannels.isNotEmpty() -> "lastWatched"
                hotChannels.isNotEmpty() -> "hot"
                genrePickup.isNotEmpty() -> "pickup"
                watchHistory.isNotEmpty() -> "history"
                upcomingReserves.isNotEmpty() -> "upcoming"
                else -> ""
            }
        }

    val availableSections =
        remember(lastWatchedChannels, hotChannels, genrePickup, watchHistory, upcomingReserves) {
            val list = mutableListOf<String>()
            if (lastWatchedChannels.isNotEmpty()) list.add("lastWatched")
            if (hotChannels.isNotEmpty()) list.add("hot")
            if (genrePickup.isNotEmpty()) list.add("pickup")
            if (watchHistory.isNotEmpty()) list.add("history")
            if (upcomingReserves.isNotEmpty()) list.add("upcoming")
            list
        }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE) {
            val targetSection = ticketManager.targetSection
            if (targetSection != null) {
                val index = availableSections.indexOf(targetSection)
                if (index != -1) {
                    Log.i(
                        "KomorebiFocus",
                        "[$TAG] 第1段階: 対象セクション($targetSection) インデックス $index へ縦スクロール"
                    )
                    delay(50)
                    lazyListState.scrollToItem(index)
                    delay(200)
                }
            }
            ticketManager.consume(HomeFocusTicket.HOME_RESTORE)
        }
    }

    val upToTabModifier = Modifier.onKeyEvent {
        if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            tabFocusRequester.safeRequestFocus(TAG); true
        } else false
    }

    val layoutInfo = lazyListState.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    val scrollProgress by remember(totalItemsCount, visibleItems) {
        derivedStateOf {
            if (totalItemsCount == 0 || visibleItems.isEmpty()) 0f
            else (visibleItems.first().index.toFloat() / totalItemsCount.toFloat()).coerceIn(0f, 1f)
        }
    }
    val animatedScrollProgress by animateFloatAsState(
        targetValue = scrollProgress,
        animationSpec = tween(300),
        label = "ScrollIndicator"
    )

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 16.dp)
        ) {
            HomeHeroDashboard(
                state = currentHeroInfo,
                getLogoUrl = getLogoUrl,
                shouldCropLogo = shouldCropLogo
            )
        }

        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(externalFocusRequester),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                if (lastWatchedChannels.isNotEmpty()) {
                    item(key = "section_last_watched") {
                        LastWatchedSection(
                            channels = lastWatchedChannels,
                            groupedChannels = groupedChannels,
                            getLogoUrl = getLogoUrl,
                            shouldCropLogo = shouldCropLogo,
                            modifier = if (topSection == "lastWatched") upToTabModifier else Modifier,
                            onChannelClick = onChannelClick,
                            onUpdateHeroInfo = { pendingHeroInfo = it },
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            sectionId = "lastWatched"
                        )
                    }
                }
                if (hotChannels.isNotEmpty()) {
                    item(key = "section_hot") {
                        HotChannelSection(
                            hotChannels = hotChannels,
                            getLogoUrl = getLogoUrl,
                            shouldCropLogo = shouldCropLogo,
                            modifier = if (topSection == "hot") upToTabModifier else Modifier,
                            onChannelClick = onChannelClick,
                            onUpdateHeroInfo = { pendingHeroInfo = it },
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            sectionId = "hot"
                        )
                    }
                }
                if (genrePickup.isNotEmpty()) {
                    item(key = "section_pickup") {
                        GenrePickupSection(
                            genrePickup = genrePickup,
                            pickupGenreName = pickupGenreName,
                            pickupTimeSlot = pickupTimeSlot,
                            getLogoUrl = getLogoUrl,
                            shouldCropLogo = shouldCropLogo,
                            modifier = if (topSection == "pickup") upToTabModifier else Modifier,
                            onProgramClick = onProgramClick,
                            onNavigateToTab = onNavigateToTab,
                            onUpdateHeroInfo = { pendingHeroInfo = it },
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            sectionId = "pickup",
                            timeFormat = timeFormat
                        )
                    }
                }
                if (watchHistory.isNotEmpty()) {
                    item(key = "section_history") {
                        WatchHistorySection(
                            watchHistory = watchHistory,
                            recentRecordings = recentRecordings, // ★ 追加: サムネイル用データを渡す
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            modifier = if (topSection == "history") upToTabModifier else Modifier,
                            onHistoryClick = onHistoryClick,
                            onUpdateHeroInfo = { pendingHeroInfo = it },
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            sectionId = "history"
                        )
                    }
                }
                if (upcomingReserves.isNotEmpty()) {
                    item(key = "section_upcoming") {
                        UpcomingReserveSection(
                            upcomingReserves = upcomingReserves,
                            getLogoUrl = getLogoUrl,
                            shouldCropLogo = shouldCropLogo,
                            modifier = if (topSection == "upcoming") upToTabModifier else Modifier,
                            onReserveClick = onReserveClick,
                            onNavigateToTab = onNavigateToTab,
                            onUpdateHeroInfo = { pendingHeroInfo = it },
                            ticketManager = ticketManager,
                            homeViewModel = homeViewModel,
                            sectionId = "upcoming",
                            timeFormat = timeFormat
                        )
                    }
                }
            }

            if (totalItemsCount > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp, top = 24.dp, bottom = 24.dp)
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(colors.textPrimary.copy(alpha = 0.1f), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.3f)
                            .offset(
                                y = animateDpAsState(
                                    targetValue = (layoutInfo.viewportSize.height * 0.7f * animatedScrollProgress).dp,
                                    animationSpec = tween(150),
                                    label = "ScrollIndicatorOffset"
                                ).value
                            )
                            .background(colors.textPrimary.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
        }
    }
}