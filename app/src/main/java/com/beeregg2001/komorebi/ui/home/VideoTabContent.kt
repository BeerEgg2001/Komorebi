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
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
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
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "VideoTabContent"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoTabContent(
    konomiIp: String,
    konomiPort: String,
    tabFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onProgramClick: (RecordedProgram) -> Unit,
    onShowAllRecordings: () -> Unit,
    onShowSeriesList: () -> Unit,
    openedSeriesTitle: String?,
    onOpenedSeriesTitleChange: (String?) -> Unit,
    recordViewModel: RecordViewModel,
    watchHistory: List<KonomiHistoryProgram> = emptyList(),
    isTopNavFocused: Boolean = false,
    // 🌟 追加: 復帰用の引数を受け取る
    isReturningFromPlayer: Boolean = false,
    lastPlayedProgramId: String? = null,
    onReturnFocusConsumed: () -> Unit = {}
) {
    val colors = KomorebiTheme.colors

    // 🌟 追加: スクロールやフォーカスを制御するための状態
    val ticketManager = rememberFocusTicketManager()
    val listState = rememberTvLazyListState()
    val recentRowState = rememberTvLazyListState()
    val historyRowState = rememberTvLazyListState()

    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val groupedSeries by recordViewModel.groupedSeries.collectAsState()
    val availableGenres by recordViewModel.availableGenres.collectAsState()
    val selectedGenre by recordViewModel.selectedSeriesGenre.collectAsState()

    val programDetail by recordViewModel.programDetail.collectAsState()
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

    // 🌟 追加: 復帰時にチケットを発行する
    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer) {
            delay(200) // リストが初期化されるのを待つ
            val targetId = lastPlayedProgramId?.toIntOrNull()
            if (targetId != null) {
                ticketManager.issue(FocusTicket.TARGET_ID, targetId)
            } else {
                contentFirstItemRequester.safeRequestFocusWithRetry("VideoTabFallback")
                onReturnFocusConsumed() // フラグを下ろす
            }
        }
    }

    // 🌟 追加: TARGET_ID チケットが発行されたら、該当する行までスクロールする
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID) {
            val targetId = ticketManager.targetProgramId?.toString() ?: return@LaunchedEffect

            var currentIndex = 1 // 0は「録画リストボタン」
            var recentColIndex = -1
            var historyColIndex = -1

            if (recentRecordings.isNotEmpty()) recentColIndex = currentIndex++
            if (watchHistory.isNotEmpty()) historyColIndex = currentIndex++

            // 「最近の録画」にあるか探す
            val rIndex = recentRecordings.take(20).indexOfFirst { it.id.toString() == targetId }
            if (rIndex != -1 && recentColIndex != -1) {
                listState.scrollToItem(recentColIndex)
                recentRowState.scrollToItem(maxOf(0, rIndex - 1)) // 少し手前までスクロール
                return@LaunchedEffect
            }

            // 「視聴履歴」にあるか探す
            val hIndex = watchHistory.take(20).indexOfFirst { it.program.id.toString() == targetId }
            if (hIndex != -1 && historyColIndex != -1) {
                listState.scrollToItem(historyColIndex)
                historyRowState.scrollToItem(maxOf(0, hIndex - 1))
                return@LaunchedEffect
            }

            // 見つからなかった場合は先頭へ
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
            HomeHeroDashboard(info = currentHeroInfo ?: initialHeroInfo)
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
                        Column {
                            SectionHeader(
                                title = "最近の録画",
                                icon = Icons.Default.PlayCircle,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                state = recentRowState, // 🌟 追加
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    recentRecordings.take(20),
                                    key = { "rec_${it.id}" }) { program ->
                                    val isCurrentlyRecording =
                                        program.isRecording || program.recordedVideo.status == "Recording"
                                    VideoRecentRecordCard(
                                        program = program,
                                        history = watchHistory.find { h -> h.program.id.toString() == program.id.toString() },
                                        konomiIp = konomiIp, konomiPort = konomiPort,
                                        ticketManager = ticketManager, // 🌟 追加
                                        onReturnFocusConsumed = onReturnFocusConsumed, // 🌟 追加
                                        onClick = {
                                            if (!isCurrentlyRecording) onProgramClick(
                                                program
                                            )
                                        },
                                        onFocus = {
                                            focusedProgramId = program.id
                                            recordViewModel.fetchProgramDetail(program.id)
                                            val startFormat = try {
                                                OffsetDateTime.parse(program.startTime)
                                                    .format(DateTimeFormatter.ofPattern("yyyy/M/d(E) HH:mm"))
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
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    konomiIp,
                                                    konomiPort,
                                                    program.id.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "最近の録画",
                                                progress = progress
                                            )
                                        },
                                        isCurrentlyRecording = isCurrentlyRecording
                                    )
                                }
                            }
                        }
                    }
                }

                if (watchHistory.isNotEmpty()) {
                    item {
                        Column {
                            SectionHeader(
                                title = "続きから見る",
                                icon = Icons.Default.PlayCircle,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                state = historyRowState, // 🌟 追加
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    watchHistory.take(20),
                                    key = { "hist_${it.program.id}" }) { historyItem ->
                                    val matchedProgram =
                                        recentRecordings.find { it.id.toString() == historyItem.program.id.toString() }
                                    VideoWatchHistoryCard(
                                        historyItem = historyItem, matchedProgram = matchedProgram,
                                        konomiIp = konomiIp, konomiPort = konomiPort,
                                        ticketManager = ticketManager, // 🌟 追加
                                        onReturnFocusConsumed = onReturnFocusConsumed, // 🌟 追加
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
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (groupedSeries.isNotEmpty()) {
                    item {
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
                                items(
                                    listOf(null) + availableGenres,
                                    key = { it ?: "All" }) { genre ->
                                    val isSelected = genre == selectedGenre
                                    var isFocused by remember { mutableStateOf(false) }
                                    Surface(
                                        onClick = { recordViewModel.updateSeriesGenre(genre) },
                                        modifier = Modifier
                                            .height(40.dp)
                                            .onFocusChanged { isFocused = it.hasFocus },
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
                                items(filteredSeries, key = { it.displayTitle }) { series ->
                                    VideoSeriesCard(
                                        series = series,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { recordViewModel.searchRecordings(series.displayTitle); onShowAllRecordings() },
                                        onFocus = {
                                            focusedProgramId = null
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = series.displayTitle,
                                                subtitle = "録画エピソード: ${series.programCount}件",
                                                description = "「${series.displayTitle}」の録画一覧を表示します。",
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    konomiIp,
                                                    konomiPort,
                                                    series.representativeVideoId.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "シリーズ"
                                            )
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

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun VideoRecentRecordCard(
    program: RecordedProgram,
    history: KonomiHistoryProgram?,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentlyRecording: Boolean = false,
    // 🌟 追加
    ticketManager: FocusTicketManager,
    onReturnFocusConsumed: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())
    val duration = if (program.duration > 0) program.duration else program.recordedVideo.duration
    val progress = if (history != null && duration > 0 && history.playback_position > 5.0) {
        (history.playback_position / duration).toFloat().coerceIn(0f, 1f)
    } else null

    // 🌟 追加: カードがチケットを受け取ってフォーカスを奪うロジック
    val specificRequester = remember { FocusRequester() }
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID && program.id == ticketManager.targetProgramId) {
            delay(100)
            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_VideoTab")
            ticketManager.consume(FocusTicket.TARGET_ID)
            onReturnFocusConsumed() // フラグを下ろす
        }
    }

    Surface(
        onClick = onClick,
        enabled = !isCurrentlyRecording,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .focusRequester(specificRequester) // 🌟 追加
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
            Column(modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)) {
                val startFormat = try {
                    OffsetDateTime.parse(program.startTime)
                        .format(DateTimeFormatter.ofPattern("M/d(E) HH:mm"))
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
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    // 🌟 追加
    ticketManager: FocusTicketManager,
    onReturnFocusConsumed: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val videoId = matchedProgram?.id ?: try {
        historyItem.program.id.toString().toInt()
    } catch (e: Exception) {
        0
    }
    val duration = matchedProgram?.duration ?: 0.0
    val progress = if (duration > 0) (historyItem.playback_position / duration).toFloat()
        .coerceIn(0f, 1f) else null
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, videoId.toString())

    // 🌟 追加: カードがチケットを受け取ってフォーカスを奪うロジック
    val specificRequester = remember { FocusRequester() }
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID && videoId == ticketManager.targetProgramId) {
            delay(100)
            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_History")
            ticketManager.consume(FocusTicket.TARGET_ID)
            onReturnFocusConsumed() // フラグを下ろす
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .focusRequester(specificRequester) // 🌟 追加
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
            Column(modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)) {
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
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val thumbnailUrl =
        UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, series.representativeVideoId.toString())

    Surface(
        onClick = onClick,
        modifier = Modifier
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
            Column(modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)) {
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