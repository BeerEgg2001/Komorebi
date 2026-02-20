@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.beeregg2001.komorebi.ui.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.*
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.util.EpgUtils
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加
import kotlinx.coroutines.delay

@Composable
fun SeriesListScreen(
    groupedSeries: Map<String, List<Pair<String, String>>>,
    isLoading: Boolean,
    onSeriesClick: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var selectedGenre by remember(groupedSeries) { mutableStateOf(groupedSeries.keys.firstOrNull()) }
    val colors = KomorebiTheme.colors // ★追加
    val backButtonRequester = remember { FocusRequester() }
    val firstGenreRequester = remember { FocusRequester() }
    val firstItemRequester = remember { FocusRequester() }

    var initialFocusSet by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading, groupedSeries) {
        if (!isLoading && !initialFocusSet) {
            delay(100)
            if (groupedSeries.isNotEmpty()) {
                firstGenreRequester.safeRequestFocus("SeriesList_FirstGenre")
            } else {
                backButtonRequester.safeRequestFocus("SeriesList_Back")
            }
            initialFocusSet = true
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).padding(48.dp)) { // ★修正
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.focusRequester(backButtonRequester)) {
                Icon(Icons.Default.ArrowBack, "戻る", tint = colors.textPrimary) // ★修正
            }
            Text("シリーズ(作品名)から探す", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, modifier = Modifier.padding(start = 16.dp)) // ★修正
        }

        Spacer(Modifier.height(24.dp))

        if (isLoading && groupedSeries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary) // ★修正
            }
        } else {
            TvLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(groupedSeries.keys.toList()) { index, genre ->
                    val genreColor = EpgUtils.getGenreColor(genre)

                    FilterChip(
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = genre },
                        modifier = Modifier.then(if (index == 0) Modifier.focusRequester(firstGenreRequester) else Modifier),
                        scale = FilterChipDefaults.scale(focusedScale = 1.0f),
                        colors = FilterChipDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.05f), // ★修正
                            focusedContainerColor = genreColor.copy(alpha = 0.4f),
                            selectedContainerColor = genreColor.copy(alpha = 0.2f),
                            focusedSelectedContainerColor = genreColor.copy(alpha = 0.6f)
                        ),
                        border = FilterChipDefaults.border(
                            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.2f))), // ★修正
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent)), // ★修正
                            selectedBorder = Border(BorderStroke(1.dp, genreColor)),
                            focusedSelectedBorder = Border(BorderStroke(2.dp, colors.textPrimary)) // ★修正
                        )
                    ) {
                        Text(genre, color = colors.textPrimary) // ★修正
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            TvLazyVerticalGrid(
                columns = TvGridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val seriesList = groupedSeries[selectedGenre] ?: emptyList()
                itemsIndexed(seriesList) { index, pair ->
                    var isFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSeriesClick(pair.second, pair.first) },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstItemRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused },
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.05f), // ★修正
                            focusedContainerColor = colors.textPrimary.copy(alpha = 0.15f), // ★修正
                            contentColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White // ★修正: 白テーマなら白、黒なら黒に反転
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent)) // ★修正
                        )
                    ) {
                        Text(
                            text = pair.first,
                            modifier = Modifier
                                .padding(16.dp)
                                .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                            maxLines = 1,
                            color = LocalContentColor.current // ★修正: Surfaceの自動配色に従う
                        )
                    }
                }
            }
        }
    }
}