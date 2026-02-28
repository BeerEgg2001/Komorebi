@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    selectedProgram: RecordedProgram?,
    restoreProgramId: Int?,
    isLoading: Boolean,
    konomiIp: String,
    konomiPort: String,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onProgramClick: (RecordedProgram) -> Unit,
    onViewAllClick: () -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    onShowAllRecordings: () -> Unit,
    onShowSeriesList: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val listState = rememberLazyListState()
    val watchedProgramFocusRequester = remember { FocusRequester() }

    if (isLoading && recentRecordings.isEmpty() && watchHistory.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp), // 上に少し余裕を持たせる
            verticalArrangement = Arrangement.spacedBy(28.dp) // セクション間の余白を広げて呼吸させる
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RecordListBannerButton(
                        modifier = Modifier
                            // ★修正: リクエスタを紐付け、上方向の移動先を指定
                            .focusRequester(contentFirstItemRequester)
                            .focusProperties { up = topNavFocusRequester },
                        onClick = onShowAllRecordings
                    )
                }
            }

            if (watchHistory.isNotEmpty()) {
                item {
                    RecordedSection(
                        title = "視聴履歴",
                        items = watchHistory,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        isFirstSection = false,
                        showResumeLabel = true,
                        topNavFocusRequester = null,
                        watchedProgramFocusRequester = watchedProgramFocusRequester,
                        selectedProgramId = restoreProgramId ?: selectedProgram?.id,
                        onProgramClick = onProgramClick
                    )
                }
            }

            if (recentRecordings.isNotEmpty()) {
                item {
                    RecordedSection(
                        title = "最近の録画",
                        items = recentRecordings.take(10),
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        isFirstSection = false,
                        showResumeLabel = false,
                        topNavFocusRequester = null,
                        watchedProgramFocusRequester = watchedProgramFocusRequester,
                        selectedProgramId = if (watchHistory.isEmpty()) (restoreProgramId ?: selectedProgram?.id) else null,
                        onProgramClick = onProgramClick,
                        onLoadMore = onLoadMore
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecordedSection(
    title: String,
    items: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    isFirstSection: Boolean,
    showResumeLabel: Boolean = false,
    topNavFocusRequester: FocusRequester?,
    watchedProgramFocusRequester: FocusRequester,
    selectedProgramId: Int?,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: (() -> Unit)? = null
) {
    val colors = KomorebiTheme.colors

    LaunchedEffect(items, selectedProgramId) {
        if (selectedProgramId != null && items.any { it.id == selectedProgramId }) {
            delay(300); runCatching { watchedProgramFocusRequester.requestFocus() }
        }
    }

    Column {
        if (title.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { _, p -> p.id }) { index, program ->
                if (onLoadMore != null && index >= items.size - 5) {
                    LaunchedEffect(Unit) { onLoadMore() }
                }

                val isSelected = program.id == selectedProgramId
                RecordedCard(
                    program = program,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    showResumeLabel = showResumeLabel,
                    onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .then(if (isSelected) Modifier.focusRequester(watchedProgramFocusRequester) else Modifier)
                        .focusProperties {
                            if (isFirstSection && topNavFocusRequester != null) up =
                                topNavFocusRequester
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListBannerButton(
    onClick: () -> Unit,
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
            .onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent))
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
                tint = (if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent).copy(alpha = 0.1f),
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
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    verticalArrangement = Arrangement.Center
                ) {
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
                        color = if (isFocused) (if (colors.isDark) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f)) else colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}