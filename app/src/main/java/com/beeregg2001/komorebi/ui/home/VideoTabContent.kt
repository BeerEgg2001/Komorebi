@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.home.components.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.ui.video.rememberFocusTicketManager
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
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
    onAiReturnConsumed: () -> Unit = {},
    onShowSmbLibrary: () -> Unit = {} // ★ 追加: SMBライブラリ画面への遷移
) {
    val colors = KomorebiTheme.colors

    val ticketManager = rememberFocusTicketManager()
    val listState = rememberLazyListState()
    val recentRowState = rememberLazyListState()
    val historyRowState = rememberLazyListState()

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
            subtitle = "ライブラリ",
            description = "十字キーの「下」を押してコンテンツを選択してください。\n録画した番組やネットワーク上の動画を視聴できます。",
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
            if (pendingHeroInfo?.title == detail.title) pendingHeroInfo =
                pendingHeroInfo?.copy(description = newDesc)
            if (currentHeroInfo?.title == detail.title) currentHeroInfo =
                currentHeroInfo?.copy(description = newDesc)
        }
    }

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            delay(150)
            if (focusedProgramId != null) ticketManager.issue(
                FocusTicket.TARGET_ID,
                focusedProgramId!!
            )
            else contentFirstItemRequester.safeRequestFocusWithRetry("VideoTabFallbackAiReturn")
            onAiReturnConsumed()
        }
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer) {
            delay(200)
            val targetId = lastPlayedProgramId?.toIntOrNull()
            if (targetId != null) ticketManager.issue(FocusTicket.TARGET_ID, targetId)
            else {
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // ★ 変更点: 録画リストとSMBのボタンを並べて表示する
                item {
                    Row(
                        modifier = Modifier
                            .padding(start = 48.dp, top = 12.dp, end = 48.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        RecordListBannerButton(
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(contentFirstItemRequester)
                                .then(upToTabModifier)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                },
                            onClick = { recordViewModel.clearSearch(); onShowAllRecordings() },
                            onFocus = {
                                focusedProgramId = null
                                pendingHeroInfo = HomeHeroInfo(
                                    title = "録画リスト",
                                    subtitle = "すべての録画番組",
                                    description = "これまでに保存されたすべての録画番組を一覧表示し、ジャンルやチャンネルで絞り込んで探すことができます。",
                                    isThumbnail = false,
                                    tag = "ビデオ"
                                )
                            }
                        )

                        SmbLibraryBannerButton(
                            modifier = Modifier
                                .weight(1f)
                                .then(upToTabModifier)
                                .focusProperties {
                                    right = FocusRequester.Cancel
                                },
                            onClick = { onShowSmbLibrary() },
                            onFocus = {
                                focusedProgramId = null
                                pendingHeroInfo = HomeHeroInfo(
                                    title = "ファイルライブラリ",
                                    subtitle = "ネットワーク(SMB)上の動画を再生",
                                    description = "NASや共有フォルダに保存されている動画ファイル（mp4, mkv, ts等）を直接再生します。",
                                    isThumbnail = false,
                                    tag = "ネットワーク"
                                )
                            }
                        )
                    }
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
                            LazyRow(
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
                                                OffsetDateTime.parse(program.startTime).format(
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

                                            val fallbackUrl = program.apiThumbnailUrl
                                                ?: UrlBuilder.getThumbnailUrl(
                                                    backendType,
                                                    konomiIp,
                                                    konomiPort,
                                                    program.id.toString()
                                                )
                                            val primaryUrl =
                                                program.directThumbnailUrl ?: fallbackUrl

                                            pendingHeroInfo = HomeHeroInfo(
                                                title = program.title,
                                                subtitle = "$startFormat - ${program.channel?.name ?: "不明"}",
                                                description = "番組情報を取得中...",
                                                imageUrl = primaryUrl,
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
                                        timeFormat = timeFormat,
                                        backendType = backendType
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
                            LazyRow(
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

                                            val fallbackUrl = matchedProgram?.apiThumbnailUrl
                                                ?: UrlBuilder.getThumbnailUrl(
                                                    backendType,
                                                    konomiIp,
                                                    konomiPort,
                                                    videoId.toString()
                                                )
                                            val primaryUrl =
                                                matchedProgram?.directThumbnailUrl ?: fallbackUrl

                                            pendingHeroInfo = HomeHeroInfo(
                                                title = historyItem.program.title.toString(),
                                                subtitle = "続きから再生を再開",
                                                description = "番組情報を取得中...",
                                                imageUrl = primaryUrl,
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
                                        },
                                        backendType = backendType
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
                            LazyRow(
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
                            LazyRow(
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
                                            focusedProgramId = null
                                            val fallbackUrl = series.apiThumbnailUrl
                                                ?: UrlBuilder.getThumbnailUrl(
                                                    backendType,
                                                    konomiIp,
                                                    konomiPort,
                                                    series.representativeVideoId.toString()
                                                )
                                            val primaryUrl =
                                                series.directThumbnailUrl ?: fallbackUrl
                                            pendingHeroInfo = HomeHeroInfo(
                                                title = series.displayTitle,
                                                subtitle = "録画エピソード: ${series.programCount}件",
                                                description = "「${series.displayTitle}」の録画一覧を表示します。",
                                                imageUrl = primaryUrl,
                                                isThumbnail = true,
                                                tag = "シリーズ"
                                            )
                                        },
                                        modifier = Modifier.focusProperties {
                                            if (index == 0) left = FocusRequester.Cancel
                                            if (index == filteredSeries.lastIndex) right =
                                                FocusRequester.Cancel
                                        },
                                        backendType = backendType
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