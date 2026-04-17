@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
// ★ 変更: TvLazy系のインポートを削除し、標準のLazy系のインポートに変更
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.home.HomeFocusTicket
import com.beeregg2001.komorebi.ui.home.HomeFocusTicketManager
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalIcon
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = colors.textPrimary.copy(0.6f))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}

@Composable
fun NavigationLinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    Box(modifier = Modifier.padding(start = 48.dp, top = 12.dp)) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.colors(
                containerColor = colors.textPrimary.copy(0.05f),
                contentColor = colors.textPrimary,
                focusedContainerColor = colors.textPrimary,
                focusedContentColor = if (colors.isDark) Color.Black else Color.White
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LastWatchedSection(
    channels: List<Channel>,
    groupedChannels: Map<String, List<Channel>>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    onChannelClick: (Channel) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit,
    ticketManager: HomeFocusTicketManager,
    homeViewModel: HomeViewModel,
    sectionId: String
) {
    // ★ 変更: rememberTvLazyListState -> rememberLazyListState
    val rowState = rememberLazyListState()

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE && ticketManager.targetSection == sectionId) {
            val index = channels.indexOfFirst { it.id == ticketManager.targetItemId }
            if (index != -1) rowState.scrollToItem(index)
        }
    }

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "前回視聴したチャンネル",
            Icons.Default.History,
            Modifier.padding(horizontal = 48.dp)
        )
        // ★ 変更: TvLazyRow -> LazyRow
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(channels, key = { _, it -> "ch_${it.id}" }) { index, channel ->
                val liveChannel = remember(groupedChannels, channel.id) {
                    groupedChannels.values.flatten().find { it.id == channel.id }
                }

                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE &&
                        ticketManager.targetSection == sectionId &&
                        ticketManager.targetItemId == channel.id
                    ) {
                        delay(150)
                        specificRequester.safeRequestFocusWithRetry("HomeRestore_LastWatched")
                        ticketManager.consume(HomeFocusTicket.HOME_RESTORE)
                        homeViewModel.clearFocusMemory()
                    }
                }

                LastWatchedChannelCard(
                    channel = channel,
                    liveChannel = liveChannel,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    onClick = { onChannelClick(channel) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = liveChannel?.programPresent?.title ?: channel.name,
                                subtitle = channel.name,
                                description = liveChannel?.programPresent?.description
                                    ?: "前回視聴していたチャンネルです。",
                                channelId = channel.id,
                                isThumbnail = false,
                                tag = "前回視聴"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == channels.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = channel.id
                            }
                        }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HotChannelSection(
    hotChannels: List<UiChannelState>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    onChannelClick: (Channel) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit,
    ticketManager: HomeFocusTicketManager,
    homeViewModel: HomeViewModel,
    sectionId: String
) {
    // ★ 変更: rememberTvLazyListState -> rememberLazyListState
    val rowState = rememberLazyListState()

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE && ticketManager.targetSection == sectionId) {
            val index = hotChannels.indexOfFirst { it.channel.id == ticketManager.targetItemId }
            if (index != -1) rowState.scrollToItem(index)
        }
    }

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "今、盛り上がっているチャンネル",
            Icons.Default.TrendingUp,
            Modifier.padding(horizontal = 48.dp)
        )
        // ★ 変更: TvLazyRow -> LazyRow
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(hotChannels, key = { _, it -> "hot_${it.channel.id}" }) { index, uiState ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE &&
                        ticketManager.targetSection == sectionId &&
                        ticketManager.targetItemId == uiState.channel.id
                    ) {
                        delay(150)
                        specificRequester.safeRequestFocusWithRetry("HomeRestore_Hot")
                        ticketManager.consume(HomeFocusTicket.HOME_RESTORE)
                        homeViewModel.clearFocusMemory()
                    }
                }

                HotChannelCard(
                    uiState = uiState,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    onClick = { onChannelClick(uiState.channel) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = uiState.programTitle,
                                subtitle = uiState.name,
                                description = uiState.channel.programPresent?.description ?: "",
                                channelId = uiState.channel.id,
                                isThumbnail = false,
                                tag = "盛り上がり"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == hotChannels.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = uiState.channel.id
                            }
                        }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun WatchHistorySection(
    watchHistory: List<KonomiHistoryProgram>,
    recentRecordings: List<RecordedProgram>, // ★ 追加: DBのメタデータを持つ録画リスト
    konomiIp: String, konomiPort: String,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit,
    ticketManager: HomeFocusTicketManager,
    homeViewModel: HomeViewModel,
    sectionId: String
) {
    // ★ 変更: rememberTvLazyListState -> rememberLazyListState
    val rowState = rememberLazyListState()

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE && ticketManager.targetSection == sectionId) {
            val index =
                watchHistory.indexOfFirst { it.program.id.toString() == ticketManager.targetItemId }
            if (index != -1) rowState.scrollToItem(index)
        }
    }

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "録画の視聴履歴",
            Icons.Default.PlayCircle,
            Modifier.padding(start = 48.dp, bottom = 12.dp)
        )
        // ★ 変更: TvLazyRow -> LazyRow
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                watchHistory,
                key = { _, it -> "hist_${it.program.id}" }) { index, history ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE &&
                        ticketManager.targetSection == sectionId &&
                        ticketManager.targetItemId == history.program.id.toString()
                    ) {
                        delay(150)
                        specificRequester.safeRequestFocusWithRetry("HomeRestore_History")
                        ticketManager.consume(HomeFocusTicket.HOME_RESTORE)
                        homeViewModel.clearFocusMemory()
                    }
                }

                // ★ 追加: KonomiHistoryProgram と DBの RecordedProgram を突き合わせる
                val matchedProgram =
                    recentRecordings.find { it.id.toString() == history.program.id.toString() }

                WatchHistoryCard(
                    history = history,
                    matchedProgram = matchedProgram, // ★ 追加: カードに渡す
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onHistoryClick(history) },
                    onFocus = { progressVal, thumbnailUrl ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = history.program.title,
                                subtitle = "視聴履歴から再開",
                                description = history.program.description,
                                imageUrl = thumbnailUrl, // サムネイル画像をそのままHeroへ
                                isThumbnail = true,
                                tag = "視聴履歴",
                                progress = progressVal
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == watchHistory.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = history.program.id.toString()
                            }
                        }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun UpcomingReserveSection(
    upcomingReserves: List<ReserveItem>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    onReserveClick: (ReserveItem) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit,
    ticketManager: HomeFocusTicketManager,
    homeViewModel: HomeViewModel,
    sectionId: String,
    timeFormat: String
) {
    // ★ 変更: rememberTvLazyListState -> rememberLazyListState
    val rowState = rememberLazyListState()

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE && ticketManager.targetSection == sectionId) {
            val index =
                upcomingReserves.indexOfFirst { it.id.toString() == ticketManager.targetItemId }
            if (index != -1) rowState.scrollToItem(index)
        }
    }

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "これからの録画予約",
            Icons.Default.RadioButtonChecked,
            Modifier.padding(horizontal = 48.dp)
        )
        // ★ 変更: TvLazyRow -> LazyRow
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(upcomingReserves, key = { _, it -> "res_${it.id}" }) { index, reserve ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE &&
                        ticketManager.targetSection == sectionId &&
                        ticketManager.targetItemId == reserve.id.toString()
                    ) {
                        delay(150)
                        specificRequester.safeRequestFocusWithRetry("HomeRestore_Reserve")
                        ticketManager.consume(HomeFocusTicket.HOME_RESTORE)
                        homeViewModel.clearFocusMemory()
                    }
                }

                UpcomingReserveCard(
                    reserve = reserve,
                    onClick = { onReserveClick(reserve) },
                    onFocus = { startFormat ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = reserve.program.title,
                                subtitle = "$startFormat - ${reserve.channel.name}",
                                description = reserve.program.description ?: "",
                                channelId = reserve.channel.id,
                                isThumbnail = false,
                                tag = "録画予約"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == upcomingReserves.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = reserve.id.toString()
                            }
                        },
                    timeFormat = timeFormat
                )
            }
        }
        NavigationLinkButton(
            "録画予約リストを表示",
            Icons.Default.List,
            onClick = { onNavigateToTab(4) })
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GenrePickupSection(
    genrePickup: List<Pair<EpgProgram, String>>,
    pickupGenreName: String,
    pickupTimeSlot: String,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    onProgramClick: (EpgProgram) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit,
    ticketManager: HomeFocusTicketManager,
    homeViewModel: HomeViewModel,
    sectionId: String,
    timeFormat: String
) {
    // ★ 変更: rememberTvLazyListState -> rememberLazyListState
    val rowState = rememberLazyListState()

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE && ticketManager.targetSection == sectionId) {
            val index = genrePickup.indexOfFirst { it.first.id == ticketManager.targetItemId }
            if (index != -1) rowState.scrollToItem(index)
        }
    }

    Column(modifier = Modifier.animateContentSize()) {
        val timePrefix = when (pickupTimeSlot) {
            "朝" -> "今朝の"; "昼" -> "今日の"; else -> "今夜の"
        }
        SectionHeader(
            "${timePrefix}${pickupGenreName}ピックアップ ${getSeasonalIcon(KomorebiTheme.theme)}",
            Icons.Default.Star,
            Modifier.padding(horizontal = 48.dp)
        )
        // ★ 変更: TvLazyRow -> LazyRow
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                genrePickup,
                key = { _, it -> "pick_${it.first.id}" }) { index, (program, channelName) ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
                    if (ticketManager.currentTicket == HomeFocusTicket.HOME_RESTORE &&
                        ticketManager.targetSection == sectionId &&
                        ticketManager.targetItemId == program.id
                    ) {
                        delay(150)
                        specificRequester.safeRequestFocusWithRetry("HomeRestore_Pickup")
                        ticketManager.consume(HomeFocusTicket.HOME_RESTORE)
                        homeViewModel.clearFocusMemory()
                    }
                }

                GenrePickupCard(
                    program = program,
                    channelName = channelName,
                    timeSlot = pickupTimeSlot,
                    onClick = { onProgramClick(program) },
                    onFocus = { startFormat ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = program.title,
                                subtitle = "$startFormat - $channelName",
                                description = program.description,
                                channelId = program.channel_id,
                                isThumbnail = false,
                                tag = "ピックアップ"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == genrePickup.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = program.id
                            }
                        },
                    timeFormat = timeFormat
                )
            }
        }
        NavigationLinkButton(
            "番組表を開く",
            Icons.Default.CalendarToday,
            onClick = { onNavigateToTab(3) })
    }
}