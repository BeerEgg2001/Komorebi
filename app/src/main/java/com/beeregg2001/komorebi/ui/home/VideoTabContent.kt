@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    selectedProgram: RecordedProgram?,
    restoreProgramId: Int? = null,
    konomiIp: String,
    konomiPort: String,
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
    onShowAllRecordings: () -> Unit = {},
    onShowSeriesList: () -> Unit = {}
) {
    val listState = rememberTvLazyListState()
    var isContentReady by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors // ★追加

    LaunchedEffect(Unit) { yield(); delay(300); isContentReady = true }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isContentReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary.copy(alpha = 0.5f)) // ★修正
            }
        } else {
            TvLazyColumn(
                state = listState, modifier = Modifier.fillMaxSize().focusRequester(contentFirstItemRequester).onKeyEvent { event ->
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                        if (event.type == KeyEventType.KeyUp) { topNavFocusRequester.safeRequestFocus(); return@onKeyEvent true }
                    }
                    false
                },
                contentPadding = PaddingValues(top = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                item {
                    if (watchHistory.isNotEmpty()) {
                        VideoSectionRow(title = "視聴履歴", items = watchHistory, selectedProgramId = selectedProgram?.id ?: restoreProgramId, konomiIp = konomiIp, konomiPort = konomiPort, onProgramClick = onProgramClick, isFirstSection = true, topNavFocusRequester = topNavFocusRequester)
                    }
                }
                item {
                    VideoSectionRow(title = "最近の録画", items = recentRecordings.take(10), selectedProgramId = selectedProgram?.id ?: restoreProgramId, konomiIp = konomiIp, konomiPort = konomiPort, onProgramClick = onProgramClick, isFirstSection = watchHistory.isEmpty(), topNavFocusRequester = if (watchHistory.isEmpty()) topNavFocusRequester else null)
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onShowAllRecordings, modifier = Modifier.width(260.dp).focusProperties { left = FocusRequester.Cancel }, colors = ButtonDefaults.colors(containerColor = colors.surface, contentColor = colors.textPrimary)) { // ★修正
                            Icon(Icons.Default.List, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("すべての録画を表示")
                        }
                        Button(onClick = onShowSeriesList, modifier = Modifier.width(260.dp).focusProperties { right = FocusRequester.Cancel }, colors = ButtonDefaults.colors(containerColor = colors.surface, contentColor = colors.textPrimary)) { // ★修正
                            Icon(Icons.Default.LibraryBooks, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("シリーズから探す")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoSectionRow(
    title: String, items: List<RecordedProgram>, selectedProgramId: Int?,
    konomiIp: String, konomiPort: String, onProgramClick: (RecordedProgram) -> Unit,
    isFirstSection: Boolean = false, topNavFocusRequester: FocusRequester? = null
) {
    val watchedProgramFocusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors // ★追加
    LaunchedEffect(selectedProgramId) {
        if (selectedProgramId != null && items.any { it.id == selectedProgramId }) { delay(300); runCatching { watchedProgramFocusRequester.requestFocus() } }
    }
    Column {
        if (title.isNotEmpty()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.textSecondary, modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)) // ★修正
        }
        TvLazyRow(contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items, key = { _, p -> p.id }) { _, program ->
                val isSelected = program.id == selectedProgramId
                var isFocused by remember { mutableStateOf(false) }
                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort, onClick = { onProgramClick(program) },
                    modifier = Modifier.onFocusChanged { isFocused = it.isFocused }.then(if (isSelected) Modifier.focusRequester(watchedProgramFocusRequester) else Modifier).focusProperties { if (isFirstSection && topNavFocusRequester != null) up = topNavFocusRequester }
                )
            }
        }
    }
}