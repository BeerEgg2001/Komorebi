@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.*
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.home.components.*
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
    isTopNavFocused: Boolean = false
) {
    val lazyListState = rememberTvLazyListState()
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

    LaunchedEffect(lastWatchedChannels.isNotEmpty()) {
        if (lastWatchedChannels.isNotEmpty()) lazyListState.scrollToItem(0)
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
            HomeHeroDashboard(info = currentHeroInfo)
        }

        Box(modifier = Modifier
            .weight(0.55f)
            .fillMaxWidth()) {
            TvLazyColumn(
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
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            modifier = if (topSection == "lastWatched") upToTabModifier else Modifier,
                            onChannelClick = onChannelClick,
                            onUpdateHeroInfo = { pendingHeroInfo = it }
                        )
                    }
                }
                if (hotChannels.isNotEmpty()) {
                    item(key = "section_hot") {
                        HotChannelSection(
                            hotChannels = hotChannels,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            modifier = if (topSection == "hot") upToTabModifier else Modifier,
                            onChannelClick = onChannelClick,
                            onUpdateHeroInfo = { pendingHeroInfo = it }
                        )
                    }
                }
                if (genrePickup.isNotEmpty()) {
                    item(key = "section_pickup") {
                        GenrePickupSection(
                            genrePickup = genrePickup,
                            pickupGenreName = pickupGenreName,
                            pickupTimeSlot = pickupTimeSlot,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            modifier = if (topSection == "pickup") upToTabModifier else Modifier,
                            onProgramClick = onProgramClick,
                            onNavigateToTab = onNavigateToTab,
                            onUpdateHeroInfo = { pendingHeroInfo = it }
                        )
                    }
                }
                if (watchHistory.isNotEmpty()) {
                    item(key = "section_history") {
                        WatchHistorySection(
                            watchHistory = watchHistory,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            modifier = if (topSection == "history") upToTabModifier else Modifier,
                            onHistoryClick = onHistoryClick,
                            onUpdateHeroInfo = { pendingHeroInfo = it }
                        )
                    }
                }
                if (upcomingReserves.isNotEmpty()) {
                    item(key = "section_upcoming") {
                        UpcomingReserveSection(
                            upcomingReserves = upcomingReserves,
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            modifier = if (topSection == "upcoming") upToTabModifier else Modifier,
                            onReserveClick = onReserveClick,
                            onNavigateToTab = onNavigateToTab,
                            onUpdateHeroInfo = { pendingHeroInfo = it }
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