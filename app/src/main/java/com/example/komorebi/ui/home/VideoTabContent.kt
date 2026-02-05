package com.example.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.ui.components.RecordedCard
import com.example.komorebi.ui.video.VideoPlayerScreen
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoTabContent(
    recentRecordings: List<RecordedProgram>,
    watchHistory: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    selectedProgram: RecordedProgram?,
    // HomeLauncherScreenから渡される引数
    topNavFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onProgramClick: (RecordedProgram?) -> Unit
) {
    val watchedProgramFocusRequester = remember { FocusRequester() }
    val isPlayerActive = selectedProgram != null

    LaunchedEffect(isPlayerActive) {
        if (!isPlayerActive && selectedProgram != null) {
            delay(150)
            runCatching { watchedProgramFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFirstItemRequester)
                .then(if (isPlayerActive) Modifier.focusProperties { canFocus = false } else Modifier),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            item {
                RecordedSection(
                    title = "新着の録画",
                    items = recentRecordings,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onProgramClick = onProgramClick,
                    firstItemFocusRequester = contentFirstItemRequester,
                    watchedProgramFocusRequester = watchedProgramFocusRequester,
                    selectedProgramId = selectedProgram?.id,
                    topNavFocusRequester = topNavFocusRequester,
                    isFirstSection = true
                )
            }

            if (watchHistory.isNotEmpty()) {
                item {
                    RecordedSection(
                        title = "視聴履歴",
                        items = watchHistory,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onProgramClick = onProgramClick,
                        firstItemFocusRequester = null,
                        watchedProgramFocusRequester = watchedProgramFocusRequester,
                        selectedProgramId = selectedProgram?.id,
                        topNavFocusRequester = null,
                        isFirstSection = false
                    )
                }
            }
        }
    }

    if (selectedProgram != null) {
        VideoPlayerScreen(
            program = selectedProgram,
            konomiIp = konomiIp, konomiPort = konomiPort,
            onBackPressed = { onProgramClick(null) }
        )
    }
}

@Composable
fun RecordedSection(
    title: String,
    items: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    isPlaceholder: Boolean = false,
    firstItemFocusRequester: FocusRequester? = null,
    watchedProgramFocusRequester: FocusRequester,
    selectedProgramId: Int?, // String? から Int? に修正
    topNavFocusRequester: FocusRequester?,
    isFirstSection: Boolean
) {
    Column(modifier = Modifier.graphicsLayer(clip = false)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 32.dp, bottom = 12.dp)
        )

        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer(clip = false)
        ) {
            if (isPlaceholder) {
                items(6) {
                    Box(
                        Modifier
                            .size(185.dp, 104.dp)
                            .background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
                    )
                }
            } else {
                itemsIndexed(items, key = { _, program -> program.id }) { index, program ->
                    val isSelected = program.id == selectedProgramId

                    RecordedCard(
                        program = program,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onProgramClick(program) },
                        modifier = Modifier
                            .then(
                                if (index == 0 && isFirstSection && firstItemFocusRequester != null) {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (isSelected) {
                                    Modifier.focusRequester(watchedProgramFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .focusProperties {
                                if (isFirstSection && topNavFocusRequester != null) {
                                    up = topNavFocusRequester
                                }
                            }
                    )
                }
            }
        }
    }
}