@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ActionButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        label = "録画リスト",
                        focusRequester = contentFirstItemRequester,
                        topNavFocusRequester = topNavFocusRequester,
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
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    focusRequester: FocusRequester,
    topNavFocusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.1f),
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties { up = topNavFocusRequester }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
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
    onLoadMore: (() -> Unit)? = null // ★追加
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
        LazyRow( // ★TvLazyRowから変更
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { _, p -> p.id }) { index, program ->
                // 無限スクロールのトリガー
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