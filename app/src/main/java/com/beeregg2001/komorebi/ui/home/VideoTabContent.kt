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
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.beeregg2001.komorebi.ui.home.components.HomeHeroDashboard
import com.beeregg2001.komorebi.ui.home.components.HomeHeroInfo
import com.beeregg2001.komorebi.ui.home.components.SectionHeader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
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
    isTopNavFocused: Boolean = false
) {
    val colors = KomorebiTheme.colors
    val listState = rememberTvLazyListState()

    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val groupedSeries by recordViewModel.groupedSeries.collectAsState()
    val availableGenres by recordViewModel.availableGenres.collectAsState()
    val selectedGenre by recordViewModel.selectedSeriesGenre.collectAsState()

    // 初期表示のヒーロー情報
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

    // トップナビ（タブ）にフォーカスが戻ったらヒーローをリセット
    LaunchedEffect(isTopNavFocused) {
        if (isTopNavFocused) {
            pendingHeroInfo = initialHeroInfo
        }
    }

    LaunchedEffect(pendingHeroInfo) {
        pendingHeroInfo?.let {
            delay(300)
            currentHeroInfo = it
        }
    }

    val animatedScrollProgress by animateFloatAsState(
        targetValue = if (listState.layoutInfo.totalItemsCount > 0) {
            listState.firstVisibleItemIndex.toFloat() / listState.layoutInfo.totalItemsCount.toFloat()
        } else 0f,
        animationSpec = tween(300),
        label = "ScrollProgress"
    )

    // 上部ナビゲーションへ戻るキーイベント
    val upToTabModifier = Modifier.onKeyEvent {
        if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
            it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        ) {
            tabFocusRequester.safeRequestFocus(TAG)
            true
        } else false
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- 1. 上部: ヒーローゾーン (HomeContentsと同じ構成) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 16.dp)
        ) {
            HomeHeroDashboard(info = currentHeroInfo ?: initialHeroInfo)
        }

        // --- 2. 下部: スクロール領域 ---
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
                // --- 録画リストボタン (セクション最上部) ---
                item {
                    RecordListBannerButton(
                        modifier = Modifier
                            .focusRequester(contentFirstItemRequester)
                            .then(upToTabModifier)
                            .padding(start = 48.dp, top = 12.dp),
                        onClick = {
                            // 検索状態をクリアして全録画を表示
                            recordViewModel.clearSearch()
                            onShowAllRecordings()
                        },
                        onFocus = {
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

                // --- 最近の録画 ---
                if (recentRecordings.isNotEmpty()) {
                    item {
                        Column {
                            SectionHeader(
                                title = "最近の録画",
                                icon = Icons.Default.PlayCircle,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    recentRecordings.take(20),
                                    key = { "rec_${it.id}" }) { program ->
                                    val history =
                                        watchHistory.find { h -> h.program.id.toString() == program.id.toString() }
                                    VideoRecentRecordCard(
                                        program = program,
                                        history = history,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = { onProgramClick(program) },
                                        onFocus = {
                                            val startFormat = try {
                                                OffsetDateTime.parse(program.startTime)
                                                    .format(DateTimeFormatter.ofPattern("yyyy/M/d(E) HH:mm"))
                                            } catch (e: Exception) {
                                                program.startTime
                                            }

                                            val duration =
                                                if (program.duration > 0) program.duration else program.recordedVideo.duration
                                            val progress =
                                                if (duration > 0 && program.playbackPosition > 5.0) {
                                                    (program.playbackPosition / duration).toFloat()
                                                        .coerceIn(0f, 1f)
                                                } else null

                                            pendingHeroInfo = HomeHeroInfo(
                                                title = program.title,
                                                subtitle = "$startFormat - ${program.channel?.name ?: "不明"}",
                                                description = program.description,
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    konomiIp,
                                                    konomiPort,
                                                    program.id.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "最近の録画",
                                                progress = progress
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // --- 続きから見る ---
                if (watchHistory.isNotEmpty()) {
                    item {
                        Column {
                            SectionHeader(
                                title = "続きから見る",
                                icon = Icons.Default.PlayCircle,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )
                            TvLazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    watchHistory.take(20),
                                    key = { "hist_${it.program.id}" }) { historyItem ->
                                    val matchedProgram =
                                        recentRecordings.find { it.id.toString() == historyItem.program.id.toString() }
                                    VideoWatchHistoryCard(
                                        historyItem = historyItem,
                                        matchedProgram = matchedProgram,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
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
                                            val duration = matchedProgram?.duration ?: 0.0
                                            val progress =
                                                if (duration > 0) (historyItem.playback_position / duration).toFloat()
                                                    .coerceIn(0f, 1f) else null

                                            pendingHeroInfo = HomeHeroInfo(
                                                title = historyItem.program.title.toString(),
                                                subtitle = "続きから再生を再開",
                                                description = historyItem.program.description ?: "",
                                                imageUrl = UrlBuilder.getThumbnailUrl(
                                                    konomiIp,
                                                    konomiPort,
                                                    videoId.toString()
                                                ),
                                                isThumbnail = true,
                                                tag = "視聴履歴",
                                                progress = progress
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // --- ジャンル別シリーズ ---
                if (groupedSeries.isNotEmpty()) {
                    item {
                        Column {
                            SectionHeader(
                                title = "ジャンル別シリーズ",
                                icon = Icons.Default.VideoLibrary,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )

                            // ジャンルタブ
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
                                            .onFocusChanged {
                                                isFocused = it.isFocused || it.hasFocus
                                            },
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = if (isSelected) colors.textPrimary else Color.Transparent,
                                            contentColor = if (isSelected) colors.background else colors.textSecondary,
                                            focusedContainerColor = colors.textPrimary,
                                            focusedContentColor = colors.background
                                        ),
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                        border = ClickableSurfaceDefaults.border(
                                            border = Border(
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
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = genre ?: "すべて",
                                                color = if (isSelected || isFocused) colors.background else colors.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 20.dp)
                                            )
                                        }
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
                                        onClick = {
                                            // ★修正: シリーズ名で録画検索を実行してから遷移
                                            recordViewModel.searchRecordings(series.displayTitle)
                                            onShowAllRecordings()
                                        },
                                        onFocus = {
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

// --- プライベートカードコンポーネント ---

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun VideoRecentRecordCard(
    program: RecordedProgram,
    history: KonomiHistoryProgram?,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())
    val duration = if (program.duration > 0) program.duration else program.recordedVideo.duration
    val progress = if (history != null && duration > 0 && history.playback_position > 5.0) {
        (history.playback_position / duration).toFloat().coerceIn(0f, 1f)
    } else null

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
            border = Border(
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
                Text(
                    text = startFormat,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent.copy(alpha = if (isFocused) 1f else 0.8f),
                    fontWeight = FontWeight.Bold
                )
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
    onFocus: () -> Unit
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
            border = Border(
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
            border = Border(
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
            border = Border(
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