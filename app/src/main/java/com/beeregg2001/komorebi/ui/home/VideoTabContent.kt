@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.home.components.HomeHeroDashboard
import com.beeregg2001.komorebi.ui.home.components.HomeHeroInfo
import com.beeregg2001.komorebi.ui.home.components.SectionHeader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.ui.video.rememberFocusTicketManager
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SeriesInfo
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "VideoTabContent"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoTabContent(
    konomiIp: String,
    konomiPort: String,
    tabFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    // ★ 修正(Step1): HomeLauncherScreen からの呼び出しに合わせるためパラメータを追加
    getLogoUrl: suspend (String) -> String = { "" },
    shouldCropLogo: Boolean = false,
    onProgramClick: (RecordedProgram) -> Unit,
    onShowAllRecordings: () -> Unit,
    onShowSeriesList: () -> Unit,
    openedSeriesTitle: String?,
    onOpenedSeriesTitleChange: (String?) -> Unit,
    recordViewModel: RecordViewModel = hiltViewModel(),
    settingViewModel: SettingsViewModel = hiltViewModel(),
    watchHistory: List<KonomiHistoryProgram> = emptyList(),
    isTopNavFocused: Boolean = false,
    isReturningFromPlayer: Boolean = false,
    lastPlayedProgramId: String? = null,
    onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    val ticketManager = rememberFocusTicketManager()
    val listState = rememberTvLazyListState()
    val recentRowState = rememberTvLazyListState()
    val historyRowState = rememberTvLazyListState()

    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val groupedSeries by recordViewModel.groupedSeries.collectAsState()
    val availableGenres by recordViewModel.availableGenres.collectAsState()
    val selectedGenre by recordViewModel.selectedSeriesGenre.collectAsState()

    val programDetail by recordViewModel.programDetail.collectAsState()
    val backendType by settingViewModel.backendType.collectAsState()
    var focusedProgramId by remember { mutableStateOf<Int?>(null) }

    val initialHeroInfo = remember {
        HomeHeroInfo(
            title = "Video Contents",
            subtitle = "録画番組ライブラリ",
            description = "十字キーの「下」を押してコンテンツを選択してください。\nこれまでに録画した番組やシリーズを視聴できます。",
            isThumbnail = false,
            tag = "ビデオ"
        )
    }

    var pendingHeroInfo by remember { mutableStateOf<HomeHeroInfo?>(initialHeroInfo) }
    var currentHeroInfo by remember { mutableStateOf<HomeHeroInfo?>(initialHeroInfo) }

    LaunchedEffect(isTopNavFocused) {
        if (isTopNavFocused) {
            pendingHeroInfo = initialHeroInfo
            focusedProgramId = null
        }
    }

    LaunchedEffect(pendingHeroInfo) {
        pendingHeroInfo?.let {
            delay(300)
            currentHeroInfo = it
        }
    }

    LaunchedEffect(programDetail, focusedProgramId) {
        val detail = programDetail
        if (detail != null && detail.id == focusedProgramId) {
            val newDesc =
                if (detail.description.isNotBlank()) detail.description else "番組概要がありません"

            if (pendingHeroInfo?.title == detail.title) {
                pendingHeroInfo = pendingHeroInfo?.copy(description = newDesc)
            }
            if (currentHeroInfo?.title == detail.title) {
                currentHeroInfo = currentHeroInfo?.copy(description = newDesc)
            }
        }
    }

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            delay(150)
            if (focusedProgramId != null) {
                ticketManager.issue(FocusTicket.TARGET_ID, focusedProgramId!!)
            } else {
                contentFirstItemRequester.safeRequestFocusWithRetry("VideoTabFallbackAiReturn")
            }
            onAiReturnConsumed()
        }
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer) {
            delay(200)
            val targetId = lastPlayedProgramId?.toIntOrNull()
            if (targetId != null) {
                ticketManager.issue(FocusTicket.TARGET_ID, targetId)
            } else {
                contentFirstItemRequester.safeRequestFocusWithRetry("VideoTabFallback")
                onReturnFocusConsumed()
            }
        }
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId?.toString() ?: return@LaunchedEffect

            var currentIndex = 1
            var recentColIndex = -1
            var historyColIndex = -1

            if (recentRecordings.isNotEmpty()) recentColIndex = currentIndex++
            if (watchHistory.isNotEmpty()) historyColIndex = currentIndex++

            val rIndex = recentRecordings.take(20).indexOfFirst { it.id.toString() == targetId }
            if (rIndex != -1 && recentColIndex != -1) {
                listState.scrollToItem(recentColIndex)
                recentRowState.scrollToItem(maxOf(0, rIndex - 1))
                return@LaunchedEffect
            }

            val hIndex = watchHistory.take(20).indexOfFirst { it.program.id.toString() == targetId }
            if (hIndex != -1 && historyColIndex != -1) {
                listState.scrollToItem(historyColIndex)
                historyRowState.scrollToItem(maxOf(0, hIndex - 1))
                return@LaunchedEffect
            }

            delay(300)
            ticketManager.consume(FocusTicket.TARGET_ID)
            contentFirstItemRequester.safeRequestFocusWithRetry("VideoTabNotFoundFallback")
            onReturnFocusConsumed()
        }
    }

    val upToTabModifier = Modifier.onKeyEvent {
        if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
            it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        ) {
            tabFocusRequester.safeRequestFocus(TAG)
            true
        } else false
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 16.dp)
        ) {
            // ★ 修正(Step2): HeroDashboard にコールバックとフラグを渡す
            HomeHeroDashboard(
                state = currentHeroInfo ?: initialHeroInfo,
                getLogoUrl = getLogoUrl,
                shouldCropLogo = shouldCropLogo
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
        ) {
            TvLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                item {
                    RecordListBannerButton(
                        modifier = Modifier
                            .focusRequester(contentFirstItemRequester)
                            .then(upToTabModifier)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .padding(start = 48.dp, top = 12.dp),
                        onClick = { recordViewModel.clearSearch(); onShowAllRecordings() },
                        onFocus = {
                            focusedProgramId = null
                            pendingHeroInfo = HomeHeroInfo(
                                title = "録画リスト",
                                subtitle = "すべての番組や未視聴の番組を視聴できます。",
                                description = "これまでに保存されたすべての録画番組を一覧表示し、ジャンルやチャンネルで絞り込んで探すことができます。",
                                isThumbnail = false,
                                tag = "ビデオ"
                            )
                        }
                    )
                }

                if (recentRecordings.isNotEmpty()) {
                    item {
                        val itemsToTake = recentRecordings.take(20)
                        Column {
                            SectionHeader(
                                title = "最近の録画",
                                icon = Icons.Default.PlayCircle,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                state = recentRowState,
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(
                                    itemsToTake,
                                    key = { _, it -> "rec_${it.id}" }) { index, program ->
                                    val isCurrentlyRecording =
                                        program.isRecording || program.recordedVideo.status == "Recording"
                                    VideoRecentRecordCard(
                                        program = program,
                                        history = watchHistory.find { h -> h.program.id.toString() == program.id.toString() },
                                        konomiIp = konomiIp, konomiPort = konomiPort,
                                        ticketManager = ticketManager,
                                        onReturnFocusConsumed = onReturnFocusConsumed,
                                        onClick = {
                                            if (!isCurrentlyRecording) onProgramClick(
                                                program
                                            )
                                        },
                                        onFocus = {
                                            focusedProgramId = program.id
                                            recordViewModel.fetchProgramDetail(program.id)

                                            val startFormat = try {
                                                val pattern =
                                                    if (timeFormat == "12H") "yyyy/M/d(E) a h:mm" else "yyyy/M/d(E) HH:mm"
                                                OffsetDateTime.parse(program.startTime)
                                                    .format(
                                                        DateTimeFormatter.ofPattern(
                                                            pattern,
                                                            Locale.JAPANESE
                                                        )
                                                    )
                                            } catch (e: Exception) {
                                                program.startTime
                                            }

                                            val duration =
                                                if (program.duration > 0) program.duration else program.recordedVideo.duration
                                            val progress =
                                                if (duration > 0 && program.playbackPosition > 5.0) (program.playbackPosition / duration).toFloat()
                                                    .coerceIn(0f, 1f) else null

                                            pendingHeroInfo = HomeHeroInfo(
                                                title = program.title,
                                                subtitle = "$startFormat - ${program.channel?.name ?: "不明"}",
                                                description = "番組情報を取得中...",
                                                // ※ ビデオタブのサムネイルはKonomiTV固有のものなので UrlBuilder.getThumbnailUrl をそのまま使用
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    backendType,
                                                    konomiIp,
                                                    konomiPort,
                                                    program.id.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "最近の録画",
                                                progress = progress
                                            )
                                        },
                                        isCurrentlyRecording = isCurrentlyRecording,
                                        modifier = Modifier.focusProperties {
                                            if (index == 0) left = FocusRequester.Cancel
                                            if (index == itemsToTake.lastIndex) right =
                                                FocusRequester.Cancel
                                        },
                                        timeFormat = timeFormat
                                    )
                                }
                            }
                        }
                    }
                }

                if (watchHistory.isNotEmpty()) {
                    item {
                        val itemsToTake = watchHistory.take(20)
                        Column {
                            SectionHeader(
                                title = "続きから見る",
                                icon = Icons.Default.PlayCircle,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                state = historyRowState,
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(
                                    itemsToTake,
                                    key = { _, it -> "hist_${it.program.id}" }) { index, historyItem ->
                                    val matchedProgram =
                                        recentRecordings.find { it.id.toString() == historyItem.program.id.toString() }
                                    VideoWatchHistoryCard(
                                        historyItem = historyItem, matchedProgram = matchedProgram,
                                        konomiIp = konomiIp, konomiPort = konomiPort,
                                        ticketManager = ticketManager,
                                        onReturnFocusConsumed = onReturnFocusConsumed,
                                        onClick = {
                                            val programToPlay =
                                                matchedProgram?.copy(playbackPosition = historyItem.playback_position)
                                                    ?: KonomiDataMapper.toDomainModel(historyItem)
                                            onProgramClick(programToPlay)
                                        },
                                        onFocus = {
                                            val videoId = matchedProgram?.id ?: try {
                                                historyItem.program.id.toString().toInt()
                                            } catch (e: Exception) {
                                                0
                                            }
                                            if (videoId != 0) {
                                                focusedProgramId = videoId
                                                recordViewModel.fetchProgramDetail(videoId)
                                            }
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = historyItem.program.title.toString(),
                                                subtitle = "続きから再生を再開",
                                                description = "番組情報を取得中...",
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    backendType,
                                                    konomiIp,
                                                    konomiPort,
                                                    videoId.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "視聴履歴",
                                                progress = if ((matchedProgram?.duration
                                                        ?: 0.0) > 0
                                                ) (historyItem.playback_position / matchedProgram!!.duration).toFloat()
                                                    .coerceIn(0f, 1f) else null
                                            )
                                        },
                                        modifier = Modifier.focusProperties {
                                            if (index == 0) left = FocusRequester.Cancel
                                            if (index == itemsToTake.lastIndex) right =
                                                FocusRequester.Cancel
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (groupedSeries.isNotEmpty()) {
                    item {
                        val genreList = listOf(null) + availableGenres
                        Column {
                            SectionHeader(
                                title = "ジャンル別シリーズ",
                                icon = Icons.Default.VideoLibrary,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(
                                    genreList,
                                    key = { _, it -> it ?: "All" }) { index, genre ->
                                    val isSelected = genre == selectedGenre
                                    var isFocused by remember { mutableStateOf(false) }
                                    Surface(
                                        onClick = { recordViewModel.updateSeriesGenre(genre) },
                                        modifier = Modifier
                                            .height(40.dp)
                                            .focusProperties {
                                                if (index == 0) left = FocusRequester.Cancel
                                                if (index == genreList.lastIndex) right =
                                                    FocusRequester.Cancel
                                            }
                                            .onFocusChanged {
                                                isFocused =
                                                    it.hasFocus; if (isFocused) focusedProgramId =
                                                null
                                            },
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = if (isSelected) colors.textPrimary else Color.Transparent,
                                            focusedContainerColor = colors.textPrimary,
                                            contentColor = if (isSelected) colors.background else colors.textSecondary,
                                            focusedContentColor = colors.background
                                        ),
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                        border = ClickableSurfaceDefaults.border(
                                            Border(
                                                BorderStroke(
                                                    1.dp,
                                                    if (isSelected) Color.Transparent else colors.textPrimary.copy(
                                                        alpha = 0.2f
                                                    )
                                                )
                                            ),
                                            focusedBorder = Border(
                                                BorderStroke(
                                                    2.dp,
                                                    colors.accent
                                                )
                                            )
                                        )
                                    ) {
                                        Text(
                                            text = genre ?: "すべて",
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(
                                                horizontal = 20.dp,
                                                vertical = 8.dp
                                            )
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            val filteredSeries =
                                if (selectedGenre == null) groupedSeries.values.flatten() else groupedSeries[selectedGenre]
                                    ?: emptyList()
                            TvLazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(
                                    filteredSeries,
                                    key = { _, it -> it.displayTitle }) { index, series ->
                                    VideoSeriesCard(
                                        series = series,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { recordViewModel.searchRecordings(series.displayTitle); onShowAllRecordings() },
                                        onFocus = {
                                            focusedProgramId = null // シリーズには特定のVideoIDがないためnull
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = series.displayTitle,
                                                subtitle = "録画エピソード: ${series.programCount}件",
                                                description = "「${series.displayTitle}」の録画一覧を表示します。",
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    backendType,
                                                    konomiIp,
                                                    konomiPort,
                                                    series.representativeVideoId.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "シリーズ"
                                            )
                                        },
                                        modifier = Modifier.focusProperties {
                                            if (index == 0) left = FocusRequester.Cancel
                                            if (index == filteredSeries.lastIndex) right =
                                                FocusRequester.Cancel
                                        }
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

// ---------------- 以下、既存のカードコンポーネント等は一切変更なし ----------------

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun VideoRecentRecordCard(
    program: RecordedProgram,
    history: KonomiHistoryProgram?,
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentlyRecording: Boolean = false,
    ticketManager: FocusTicketManager,
    onReturnFocusConsumed: () -> Unit,
    timeFormat: String
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val backendType by settingViewModel.backendType.collectAsState()
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(backendType, konomiIp, konomiPort, program.id.toString())
    val duration = if (program.duration > 0) program.duration else program.recordedVideo.duration
    val progress = if (history != null && duration > 0 && history.playback_position > 5.0) {
        (history.playback_position / duration).toFloat().coerceIn(0f, 1f)
    } else null

    val specificRequester = remember { FocusRequester() }
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID && program.id == ticketManager.targetProgramId) {
            delay(100)
            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_VideoTab")
            ticketManager.consume(FocusTicket.TARGET_ID)
            onReturnFocusConsumed()
        }
    }

    Surface(
        onClick = onClick,
        enabled = !isCurrentlyRecording,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .focusRequester(specificRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.5f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            Border(
                BorderStroke(
                    1.dp,
                    colors.textPrimary.copy(alpha = 0.1f)
                )
            ), focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.8f else 0.5f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ), startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                val startFormat = try {
                    val pattern = if (timeFormat == "12H") "M/d(E) a h:mm" else "M/d(E) HH:mm"
                    OffsetDateTime.parse(program.startTime)
                        .format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))
                } catch (e: Exception) {
                    program.startTime
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrentlyRecording) {
                        Box(
                            modifier = Modifier
                                .background(colors.accent, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "録画中",
                                color = if (colors.isDark) Color.Black else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = startFormat,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent.copy(alpha = if (isFocused) 1f else 0.8f),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress != null && !isCurrentlyRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(colors.accent)
                    )
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun VideoWatchHistoryCard(
    historyItem: KonomiHistoryProgram,
    matchedProgram: RecordedProgram?,
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    ticketManager: FocusTicketManager,
    onReturnFocusConsumed: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val backendType by settingViewModel.backendType.collectAsState()
    var isFocused by remember { mutableStateOf(false) }
    val videoId = matchedProgram?.id ?: try {
        historyItem.program.id.toString().toInt()
    } catch (e: Exception) {
        0
    }
    val duration = matchedProgram?.duration ?: 0.0
    val progress = if (duration > 0) (historyItem.playback_position / duration).toFloat()
        .coerceIn(0f, 1f) else null
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(backendType, konomiIp, konomiPort, videoId.toString())

    val specificRequester = remember { FocusRequester() }
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID && videoId == ticketManager.targetProgramId) {
            delay(100)
            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_History")
            ticketManager.consume(FocusTicket.TARGET_ID)
            onReturnFocusConsumed()
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .focusRequester(specificRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.5f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            Border(
                BorderStroke(
                    1.dp,
                    colors.textPrimary.copy(alpha = 0.1f)
                )
            ), focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.8f else 0.5f),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ), startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "続きから再生",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = historyItem.program.title.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(colors.accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoSeriesCard(
    series: SeriesInfo,
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val backendType by settingViewModel.backendType.collectAsState()
    val thumbnailUrl =
        UrlBuilder.getThumbnailUrl(backendType, konomiIp, konomiPort, series.representativeVideoId.toString())

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.5f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            Border(
                BorderStroke(
                    1.dp,
                    colors.textPrimary.copy(alpha = 0.1f)
                )
            ), focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.8f else 0.4f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ), startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = "${series.programCount}エピソード",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = series.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListBannerButton(
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val backgroundBrush = remember(colors) {
        Brush.horizontalGradient(
            colors = listOf(
                colors.surface,
                colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.1f)
            )
        )
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(360.dp)
            .height(88.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            Border(
                BorderStroke(
                    1.dp,
                    colors.textPrimary.copy(alpha = 0.1f)
                )
            ), focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isFocused) SolidColor(Color.Transparent) else backgroundBrush)
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                tint = (if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent).copy(
                    alpha = 0.1f
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 24.dp, y = 16.dp)
                    .size(100.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isFocused) Color.Transparent else colors.accent.copy(alpha = 0.2f),
                            shape = CircleShape
                        ), contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        text = "録画リスト",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "すべての番組・シリーズから探す",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFocused) (if (colors.isDark) Color.Black.copy(alpha = 0.8f) else Color.White.copy(
                            alpha = 0.8f
                        )) else colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}