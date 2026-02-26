package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft // ★追加
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.util.EpgUtils
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordSeriesContent(
    groupedSeries: Map<String, List<Pair<String, String>>>,
    isLoading: Boolean,
    onSeriesClick: (String) -> Unit,
    onOpenNavPane: () -> Unit,
    isListView: Boolean,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean
) {
    val colors = KomorebiTheme.colors
    var selectedGenre by remember(groupedSeries) { mutableStateOf(groupedSeries.keys.firstOrNull()) }
    val firstGenreRequester = remember { FocusRequester() }

    if (isLoading && groupedSeries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.textPrimary)
        }
        return
    }

    // 全体をBoxで包み、アイコンを重ねられるようにする
    Box(modifier = Modifier.fillMaxSize()) {
        // --- メインコンテンツ ---
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. ジャンル選択チップ
            TvLazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(groupedSeries.keys.toList()) { index, genre ->
                    val genreColor = EpgUtils.getGenreColor(genre)
                    val inverseColor = if (colors.isDark) Color.Black else Color.White

                    FilterChip(
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = genre },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstGenreRequester) else Modifier)
                            .focusProperties {
                                up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                                down = firstItemFocusRequester
                            }
                            .onKeyEvent { event ->
                                if (!isListView && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && index == 0) {
                                    onOpenNavPane()
                                    true
                                } else false
                            },
                        scale = FilterChipDefaults.scale(focusedScale = 1.05f),
                        colors = FilterChipDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.05f),
                            contentColor = colors.textSecondary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = inverseColor,
                            selectedContainerColor = genreColor.copy(alpha = 0.2f),
                            selectedContentColor = colors.textPrimary,
                            focusedSelectedContainerColor = colors.textPrimary,
                            focusedSelectedContentColor = inverseColor
                        ),
                        border = FilterChipDefaults.border(
                            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent)),
                            selectedBorder = Border(BorderStroke(1.dp, genreColor))
                        )
                    ) {
                        Text(text = genre)
                    }
                }
            }

            // 2. 作品名グリッド
            TvLazyVerticalGrid(
                columns = TvGridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 32.dp),
                modifier = Modifier.weight(1f)
            ) {
                val seriesList = groupedSeries[selectedGenre] ?: emptyList()
                itemsIndexed(seriesList) { index, pair ->
                    var isFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSeriesClick(pair.second) },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusProperties {
                                if (index < 3) {
                                    up = firstGenreRequester
                                }
                            }
                            .onKeyEvent { event ->
                                if (!isListView && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && index % 3 == 0) {
                                    onOpenNavPane()
                                    true
                                } else false
                            },
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.05f),
                            focusedContainerColor = colors.textPrimary,
                            contentColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                        )
                    ) {
                        Text(
                            text = pair.first,
                            modifier = Modifier
                                .padding(16.dp)
                                .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}